package com.antigravity.monarch.domain.engine

import com.antigravity.monarch.domain.model.HabitType
import com.antigravity.monarch.domain.model.Intensity
import com.antigravity.monarch.domain.model.PlayerClass
import com.antigravity.monarch.domain.model.StatType
import com.antigravity.monarch.domain.model.WorkoutType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToInt

class XpRulesTest {

    @Test
    fun `workout stat weights sum to one`() {
        for (type in WorkoutType.entries) {
            assertEquals(type.name, 1.0, type.statWeights.values.sum(), 1e-9)
        }
    }

    @Test
    fun `effective minutes apply diminishing returns`() {
        assertEquals(0.0, XpRules.effectiveMinutes(4), 0.0)
        assertEquals(5.0, XpRules.effectiveMinutes(5), 0.0)
        assertEquals(60.0, XpRules.effectiveMinutes(60), 0.0)
        assertEquals(75.0, XpRules.effectiveMinutes(90), 0.0)
        assertEquals(90.0, XpRules.effectiveMinutes(120), 0.0)
        assertEquals(90.0, XpRules.effectiveMinutes(600), 0.0)
        assertEquals(0.0, XpRules.effectiveMinutes(-30), 0.0)
    }

    @Test
    fun `workout xp maps to the right stats and totals correctly`() {
        val xp = XpRules.workoutXp(WorkoutType.STRENGTH, 40, Intensity.MODERATE)
        assertEquals(40, xp.values.sum())
        assertEquals(30, xp[StatType.STRENGTH])
        assertEquals(6, xp[StatType.ENDURANCE])
        assertEquals(4, xp[StatType.HEALTH])

        val run = XpRules.workoutXp(WorkoutType.RUNNING, 30, Intensity.INTENSE)
        assertEquals((30 * 1.25).roundToInt(), run.values.sum())
        assertTrue(run.getValue(StatType.AGILITY) > run.getValue(StatType.ENDURANCE))
    }

    @Test
    fun `intensity scales xp`() {
        val light = XpRules.workoutXp(WorkoutType.HIIT, 30, Intensity.LIGHT).values.sum()
        val moderate = XpRules.workoutXp(WorkoutType.HIIT, 30, Intensity.MODERATE).values.sum()
        val intense = XpRules.workoutXp(WorkoutType.HIIT, 30, Intensity.INTENSE).values.sum()
        assertTrue(light < moderate && moderate < intense)
    }

    @Test
    fun `distribute always preserves the rounded total`() {
        val weights = listOf(
            mapOf(StatType.STRENGTH to 1.0),
            mapOf(StatType.STRENGTH to 0.5, StatType.AGILITY to 0.5),
            mapOf(StatType.STRENGTH to 1 / 3.0, StatType.AGILITY to 1 / 3.0, StatType.HEALTH to 1 / 3.0),
            WorkoutType.SWIMMING.statWeights,
            WorkoutType.YOGA.statWeights,
        )
        for (w in weights) for (total in listOf(0.4, 1.0, 7.0, 13.7, 99.5, 112.5)) {
            val parts = XpRules.distribute(total, w)
            assertEquals("total=$total w=$w", total.roundToInt(), parts.values.sum())
            assertTrue(parts.values.all { it > 0 })
        }
    }

    @Test
    fun `steps xp saturates and never exceeds 60`() {
        assertTrue(XpRules.stepsXp(0).isEmpty())
        val values = listOf(1_000L, 5_000L, 10_000L, 20_000L, 100_000L, 10_000_000L).map { XpRules.stepsXp(it)[StatType.ENDURANCE] ?: 0 }
        values.zipWithNext().forEach { (a, b) -> assertTrue(b >= a) }
        assertTrue(values.all { it <= 60 })
        assertEquals(38, XpRules.stepsXp(10_000)[StatType.ENDURANCE])
    }

    @Test
    fun `sleep rewards a healthy window not more sleep`() {
        assertEquals(30, XpRules.sleepXp(8 * 60)[StatType.HEALTH])
        assertEquals(20, XpRules.sleepXp(390)[StatType.HEALTH])
        assertEquals(20, XpRules.sleepXp(570 + 30)[StatType.HEALTH])
        assertEquals(10, XpRules.sleepXp(5 * 60 + 30)[StatType.HEALTH])
        assertTrue(XpRules.sleepXp(12 * 60).isEmpty())
        assertTrue(XpRules.sleepXp(3 * 60).isEmpty())
    }

    @Test
    fun `habits respect per-day limits`() {
        assertEquals(16, XpRules.habitXp(HabitType.MEAL, 2, 0)[StatType.HEALTH])
        // Already logged 3 meals: only the 4th counts.
        assertEquals(8, XpRules.habitXp(HabitType.MEAL, 3, 3)[StatType.HEALTH])
        assertTrue(XpRules.habitXp(HabitType.MEAL, 1, 4).isEmpty())

        assertEquals(20, XpRules.habitXp(HabitType.HYDRATION, 50, 0)[StatType.HEALTH])
        assertTrue(XpRules.habitXp(HabitType.HYDRATION, 2, 10).isEmpty())

        assertEquals(24, XpRules.habitXp(HabitType.READING, 30, 0)[StatType.INTELLIGENCE])
        assertTrue("sessions under 5 minutes earn nothing", XpRules.habitXp(HabitType.READING, 4, 0).isEmpty())
        assertEquals(16, XpRules.habitXp(HabitType.LEARNING, 60, 100)[StatType.INTELLIGENCE])
    }

    @Test
    fun `meditation feeds intelligence and health`() {
        val xp = XpRules.habitXp(HabitType.MEDITATION, 20, 0)
        assertEquals(16, xp.values.sum())
        assertTrue(xp.getValue(StatType.INTELLIGENCE) > xp.getValue(StatType.HEALTH))
    }
}

class XpAllocatorTest {

    @Test
    fun `caps each stat per day and reports the overflow`() {
        val alloc = XpAllocator.allocate(
            raw = mapOf(StatType.STRENGTH to 80, StatType.HEALTH to 10),
            alreadyToday = mapOf(StatType.STRENGTH to 100),
            playerClass = null,
        )
        assertEquals(50, alloc.awarded[StatType.STRENGTH])
        assertEquals(10, alloc.awarded[StatType.HEALTH])
        assertEquals(30, alloc.capped)
    }

    @Test
    fun `fully capped stat is omitted`() {
        val alloc = XpAllocator.allocate(mapOf(StatType.AGILITY to 5), mapOf(StatType.AGILITY to 150), null)
        assertTrue(alloc.awarded.isEmpty())
        assertEquals(5, alloc.capped)
    }

    @Test
    fun `class bonus boosts focus stats but never breaks the cap`() {
        val raw = mapOf(StatType.STRENGTH to 100, StatType.ENDURANCE to 100, StatType.AGILITY to 100)
        val alloc = XpAllocator.allocate(raw, emptyMap(), PlayerClass.WARRIOR)
        assertEquals(110, alloc.awarded[StatType.STRENGTH])
        assertEquals(105, alloc.awarded[StatType.ENDURANCE])
        assertEquals(100, alloc.awarded[StatType.AGILITY])

        val capped = XpAllocator.allocate(mapOf(StatType.STRENGTH to 145), emptyMap(), PlayerClass.WARRIOR)
        assertEquals(XpRules.DAILY_STAT_CAP, capped.awarded[StatType.STRENGTH])
    }

    @Test
    fun `farming a stat all day is bounded by the cap`() {
        var today = mapOf<StatType, Int>()
        repeat(50) {
            val alloc = XpAllocator.allocate(XpRules.workoutXp(WorkoutType.STRENGTH, 120, Intensity.INTENSE), today, PlayerClass.WARRIOR)
            today = (today.keys + alloc.awarded.keys).associateWith { (today[it] ?: 0) + (alloc.awarded[it] ?: 0) }
        }
        assertTrue(today.values.all { it <= XpRules.DAILY_STAT_CAP })
        assertEquals(XpRules.DAILY_STAT_CAP, today[StatType.STRENGTH])
    }
}
