package com.antigravity.monarch.domain.usecase

import com.antigravity.monarch.domain.engine.WorkoutDeduplicator
import com.antigravity.monarch.domain.model.ActivitySource
import com.antigravity.monarch.domain.model.Intensity
import com.antigravity.monarch.domain.model.StatType
import com.antigravity.monarch.domain.model.WorkoutEntry
import com.antigravity.monarch.domain.model.WorkoutType
import com.antigravity.monarch.domain.repository.ExternalWorkout
import com.antigravity.monarch.domain.repository.HealthAvailability
import com.antigravity.monarch.domain.repository.HealthDaySnapshot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

private fun at(hour: Int, minute: Int = 0): Long = TODAY.atTime(hour, minute).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

private fun ext(id: String, startHour: Int, minutes: Int, source: ActivitySource = ActivitySource.HEALTH_CONNECT, origin: String? = null, startMinute: Int = 0) =
    ExternalWorkout(id, WorkoutType.RUNNING, "Run $id", at(startHour, startMinute), minutes, source, origin)

class WorkoutDeduplicatorTest {

    private fun synced(start: Long, minutes: Int, source: ActivitySource = ActivitySource.HEALTH_CONNECT, id: String? = "x") =
        WorkoutEntry(1, WorkoutType.RUNNING, "r", minutes, Intensity.MODERATE, TODAY, start, source, id)

    @Test
    fun `overlap ratio is relative to the shorter session`() {
        assertEquals(1.0, WorkoutDeduplicator.overlapRatio(0, 10, 0, 100), 1e-9)
        assertEquals(0.5, WorkoutDeduplicator.overlapRatio(0, 10, 5, 15), 1e-9)
        assertEquals(0.0, WorkoutDeduplicator.overlapRatio(0, 10, 10, 20), 1e-9)
        assertEquals(0.0, WorkoutDeduplicator.overlapRatio(0, 0, 0, 10), 1e-9)
    }

    @Test
    fun `same session from another source is a duplicate, back-to-back sessions are not`() {
        val existing = listOf(synced(at(7), 30))
        // Strava copy starts two minutes later and is slightly shorter.
        assertTrue(WorkoutDeduplicator.isDuplicate(ext("strava:1", 7, 27, ActivitySource.STRAVA, startMinute = 2), existing))
        // A second run right after the first.
        assertFalse(WorkoutDeduplicator.isDuplicate(ext("hc:2", 7, 20, startMinute = 31), existing))
        // Same external id is always a duplicate.
        assertTrue(WorkoutDeduplicator.isDuplicate(ext("x", 18, 20), existing))
    }

    @Test
    fun `manual logs never suppress synced sessions`() {
        val manual = listOf(synced(at(7), 30, ActivitySource.MANUAL, id = null))
        assertFalse(WorkoutDeduplicator.isDuplicate(ext("hc:1", 7, 30), manual))
    }

    @Test
    fun `duplicates inside one batch keep the earliest copy`() {
        val kept = WorkoutDeduplicator.dedupeBatch(
            listOf(ext("strava:9", 7, 29, ActivitySource.STRAVA, startMinute = 1), ext("hc:9", 7, 30), ext("hc:10", 19, 45)),
        )
        assertEquals(listOf("hc:9", "hc:10"), kept.map { it.externalId })
    }
}

class MultiSourceSyncTest {

    private fun world() = World().apply { time.now = at(21) }

    @Test
    fun `strava and health connect copies of one run award xp once`() = runTest {
        val w = world()
        w.health.days[TODAY] = HealthDaySnapshot(TODAY, 4_000, 0, listOf(ext("hc-run", 7, 30, origin = "Zepp (Amazfit)")))
        w.feed.connected = true
        w.feed.workouts = listOf(ext("strava:42", 7, 29, ActivitySource.STRAVA, "Strava", startMinute = 1), ext("strava:43", 18, 40, ActivitySource.STRAVA, "Strava"))

        val outcome = w.healthSync.sync() as HealthSyncOutcome.Synced
        assertEquals(listOf("Health Connect", "Strava"), outcome.sources)
        assertEquals(2, w.activities.workouts.size)
        assertEquals(setOf("Zepp (Amazfit)", "Strava"), w.activities.workouts.map { it.originApp }.toSet())
        assertEquals(setOf(ActivitySource.HEALTH_CONNECT, ActivitySource.STRAVA), w.activities.workouts.map { it.source }.toSet())

        // Syncing again changes nothing.
        val xp = w.ledger.totalXp()
        w.healthSync.sync()
        assertEquals(2, w.activities.workouts.size)
        assertEquals(xp, w.ledger.totalXp())
    }

    @Test
    fun `strava alone works without health connect and never wipes earlier steps`() = runTest {
        val w = world()
        w.health.days[TODAY] = HealthDaySnapshot(TODAY, 10_000, 0, emptyList())
        w.healthSync.sync()
        val stepsXp = w.ledger.statXpForDay(TODAY)[StatType.ENDURANCE]
        assertEquals(38, stepsXp)

        // Health Connect permission revoked; only Strava is connected now.
        w.health.permitted = false
        w.feed.connected = true
        w.feed.workouts = listOf(ext("strava:1", 9, 45, ActivitySource.STRAVA, "Strava"))
        val outcome = w.healthSync.sync() as HealthSyncOutcome.Synced
        assertEquals(listOf("Strava"), outcome.sources)
        assertEquals(1, w.activities.workouts.size)
        assertEquals(38, w.ledger.events.value.filter { it.sourceKey == "steps:$TODAY" }.sumOf { it.amount })
    }

    @Test
    fun `a failing feed is reported but does not block health connect`() = runTest {
        val w = world()
        w.health.days[TODAY] = HealthDaySnapshot(TODAY, 0, 0, listOf(ext("hc-1", 8, 30)))
        w.feed.connected = true
        w.feed.failure = java.io.IOException("Strava HTTP 503")
        val outcome = w.healthSync.sync() as HealthSyncOutcome.Synced
        assertEquals(1, w.activities.workouts.size)
        assertTrue(outcome.warnings.single().contains("503"))
    }

    @Test
    fun `nothing connected reports why`() = runTest {
        val w = world()
        w.health.permitted = false
        assertEquals(HealthSyncOutcome.MissingPermissions, w.healthSync.sync())
        w.health.availability = HealthAvailability.UNAVAILABLE
        assertEquals(HealthSyncOutcome.Unavailable, w.healthSync.sync())
        w.feed.connected = true
        assertTrue(w.healthSync.sync() is HealthSyncOutcome.Synced)
    }

    @Test
    fun `synced workouts from any source cannot be deleted`() = runTest {
        val w = world()
        w.feed.connected = true
        w.feed.workouts = listOf(ext("strava:5", 6, 30, ActivitySource.STRAVA, "Strava"))
        w.healthSync.sync()
        assertFalse(w.progression.deleteWorkout(w.activities.workouts.single().id))
    }
}
