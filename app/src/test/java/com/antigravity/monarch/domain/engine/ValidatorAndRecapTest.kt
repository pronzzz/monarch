package com.antigravity.monarch.domain.engine

import com.antigravity.monarch.domain.engine.ProfileValidator.Field
import com.antigravity.monarch.domain.model.AchievementDef
import com.antigravity.monarch.domain.model.Intensity
import com.antigravity.monarch.domain.model.StatType
import com.antigravity.monarch.domain.model.WorkoutEntry
import com.antigravity.monarch.domain.model.WorkoutType
import com.antigravity.monarch.domain.model.XpEvent
import com.antigravity.monarch.domain.model.XpKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate

class ProfileValidatorTest {

    @Test
    fun `identity validation`() {
        assertTrue(ProfileValidator.identity("Jin", "25", true).isEmpty())
        val errors = ProfileValidator.identity("  ", "abc", false)
        assertEquals(setOf(Field.NAME, Field.AGE, Field.GENDER), errors.keys)
        assertTrue(Field.AGE in ProfileValidator.identity("Jin", "12", true))
        assertTrue(Field.AGE in ProfileValidator.identity("Jin", "130", true))
        assertTrue(Field.NAME in ProfileValidator.identity("x".repeat(25), "20", true))
    }

    @Test
    fun `body validation accepts decimal commas and rejects out of range values`() {
        assertTrue(ProfileValidator.body("175", "72,5", "70.0").isEmpty())
        assertEquals(setOf(Field.HEIGHT, Field.WEIGHT, Field.TARGET_WEIGHT), ProfileValidator.body("50", "1000", "").keys)
        assertNull(ProfileValidator.parseDecimal("NaN"))
        assertEquals(72.5, ProfileValidator.parseDecimal(" 72,5 ")!!, 1e-9)
    }

    @Test
    fun `training validation`() {
        assertTrue(ProfileValidator.training(true, setOf(DayOfWeek.MONDAY)).isEmpty())
        assertEquals(setOf(Field.INTENSITY, Field.DAYS), ProfileValidator.training(false, emptySet()).keys)
    }
}

class RecapBuilderTest {
    private val today = LocalDate.of(2026, 10, 25)

    private fun event(daysAgo: Long, stat: StatType?, amount: Int, kind: XpKind = XpKind.ACTIVITY) =
        XpEvent("k$daysAgo$stat$amount", stat, amount, today.minusDays(daysAgo), kind, "x", 0)

    @Test
    fun `aggregates the last seven days only`() {
        val events = listOf(
            event(0, StatType.STRENGTH, 40),
            event(1, StatType.AGILITY, 30),
            event(1, null, 50, XpKind.ACHIEVEMENT),
            event(6, StatType.HEALTH, 25),
            event(7, StatType.HEALTH, 999), // outside window
        )
        val workouts = listOf(
            WorkoutEntry(1, WorkoutType.STRENGTH, "Lift", 45, Intensity.MODERATE, today, 0),
            WorkoutEntry(2, WorkoutType.RUNNING, "Run", 30, Intensity.MODERATE, today.minusDays(10), 0),
        )
        val recap = RecapBuilder.weekly(today, events, workouts, xpBeforeStart = 0, unlockedAt = mapOf(AchievementDef.FIRST_WORKOUT to today))
        assertEquals(145, recap.totalXp)
        assertEquals(7, recap.xpByDay.size)
        assertEquals(today.minusDays(6), recap.start)
        assertEquals(1, recap.workouts)
        assertEquals(45, recap.workoutMinutes)
        assertEquals(3, recap.activeDays)
        assertEquals(today.minusDays(1), recap.bestDay)
        assertEquals(StatType.STRENGTH, recap.topStat)
        assertEquals(listOf(AchievementDef.FIRST_WORKOUT), recap.achievements)
        assertEquals(LevelCurve.levelFor(145), recap.levelEnd)
        assertTrue(recap.levelsGained >= 1)
    }

    @Test
    fun `empty week gets a gentle headline`() {
        val recap = RecapBuilder.weekly(today, emptyList(), emptyList(), 5_000, emptyMap())
        assertEquals(0, recap.totalXp)
        assertEquals(0, recap.levelsGained)
        assertNull(recap.bestDay)
        assertNull(recap.topStat)
        assertTrue(recap.headline.startsWith("A quiet week"))
    }
}
