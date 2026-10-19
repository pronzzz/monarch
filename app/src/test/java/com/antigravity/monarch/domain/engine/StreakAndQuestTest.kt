package com.antigravity.monarch.domain.engine

import com.antigravity.monarch.domain.model.AchievementDef
import com.antigravity.monarch.domain.model.AchievementSnapshot
import com.antigravity.monarch.domain.model.DayTotals
import com.antigravity.monarch.domain.model.FitnessGoal
import com.antigravity.monarch.domain.model.Gender
import com.antigravity.monarch.domain.model.Intensity
import com.antigravity.monarch.domain.model.PlayerProfile
import com.antigravity.monarch.domain.model.QuestKind
import com.antigravity.monarch.domain.model.StatType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate

/** 2026-10-19 is a Monday. */
private val MONDAY: LocalDate = LocalDate.of(2026, 10, 19)

private fun days(vararg offsets: Int) = offsets.map { MONDAY.plusDays(it.toLong()) }.toSet()

class StreakCalculatorTest {
    private val noRest = emptySet<DayOfWeek>()

    @Test
    fun `no history means zero streak with a starter shield`() {
        val s = StreakCalculator.compute(emptySet(), noRest, MONDAY)
        assertEquals(0, s.current)
        assertEquals(StreakCalculator.STARTING_SHIELDS, s.shields)
        assertTrue(s.todayPending)
    }

    @Test
    fun `consecutive active days build the streak`() {
        val s = StreakCalculator.compute(days(0, 1, 2, 3), noRest, MONDAY.plusDays(3))
        assertEquals(4, s.current)
        assertEquals(4, s.longest)
        assertFalse(s.todayPending)
    }

    @Test
    fun `today not yet active keeps the streak alive as pending`() {
        val s = StreakCalculator.compute(days(0, 1, 2), noRest, MONDAY.plusDays(3))
        assertEquals(3, s.current)
        assertTrue(s.todayPending)
    }

    @Test
    fun `rest days are neutral`() {
        // Active Mon, Tue; Wed is a rest day; active Thu.
        val s = StreakCalculator.compute(days(0, 1, 3), setOf(DayOfWeek.WEDNESDAY), MONDAY.plusDays(3))
        assertEquals(3, s.current)
        assertEquals(0, s.shieldsUsed)
        assertEquals(StreakCalculator.STARTING_SHIELDS, s.shields)
    }

    @Test
    fun `a missed training day consumes a shield instead of breaking`() {
        val s = StreakCalculator.compute(days(0, 1, 3), noRest, MONDAY.plusDays(3))
        assertEquals(3, s.current)
        assertEquals(1, s.shieldsUsed)
        assertEquals(0, s.shields)
    }

    @Test
    fun `missing without shields resets the streak but keeps the longest`() {
        // Active Mon-Wed, miss Thu (shield), miss Fri (reset), active Sat.
        val s = StreakCalculator.compute(days(0, 1, 2, 5), noRest, MONDAY.plusDays(5))
        assertEquals(1, s.current)
        assertEquals(3, s.longest)
    }

    @Test
    fun `shields are earned every seven active days and capped`() {
        val active = (0 until 21).map { MONDAY.plusDays(it.toLong()) }.toSet()
        val s = StreakCalculator.compute(active, noRest, MONDAY.plusDays(20))
        assertEquals(21, s.current)
        assertEquals(StreakCalculator.MAX_SHIELDS, s.shields)
    }

    @Test
    fun `missed days before any activity do not consume shields`() {
        val s = StreakCalculator.compute(days(3), noRest, MONDAY.plusDays(3), startDate = MONDAY)
        assertEquals(1, s.current)
        assertEquals(0, s.shieldsUsed)
    }
}

class AchievementRulesTest {
    private val base = AchievementSnapshot(
        level = 1, statValues = StatType.entries.associateWith { 10 }, longestStreak = 0, totalWorkouts = 0,
        maxDailySteps = 0, totalMindMinutes = 0, hasSynced = false, perfectQuestDays = 0,
    )

    @Test
    fun `fresh player has no achievements`() {
        assertTrue(AchievementRules.earned(base).isEmpty())
    }

    @Test
    fun `milestones unlock at their thresholds`() {
        val s = base.copy(level = 10, longestStreak = 7, totalWorkouts = 1, maxDailySteps = 10_000, hasSynced = true)
        val earned = AchievementRules.earned(s)
        assertTrue(earned.containsAll(listOf(
            AchievementDef.FIRST_WORKOUT, AchievementDef.FIRST_SYNC, AchievementDef.STREAK_3, AchievementDef.STREAK_7,
            AchievementDef.LEVEL_5, AchievementDef.LEVEL_10, AchievementDef.STEPS_10K,
        )))
        assertFalse(AchievementDef.STREAK_30 in earned)
        assertFalse(AchievementDef.LEVEL_25 in earned)
    }

    @Test
    fun `balanced requires every stat`() {
        val almost = base.copy(statValues = StatType.entries.associateWith { 15 } + (StatType.INTELLIGENCE to 14))
        assertFalse(AchievementDef.BALANCED in AchievementRules.earned(almost))
        val all = base.copy(statValues = StatType.entries.associateWith { 15 })
        assertTrue(AchievementDef.BALANCED in AchievementRules.earned(all))
    }

    @Test
    fun `newlyEarned excludes existing unlocks`() {
        val s = base.copy(totalWorkouts = 12)
        val fresh = AchievementRules.newlyEarned(s, setOf(AchievementDef.FIRST_WORKOUT))
        assertEquals(listOf(AchievementDef.WORKOUTS_10), fresh)
    }
}

class QuestEngineTest {
    private val profile = PlayerProfile(
        name = "Jin", age = 25, gender = Gender.MALE, heightCm = 178, weightKg = 75.0, targetWeightKg = 72.0,
        goal = FitnessGoal.GENERAL_FITNESS, intensity = Intensity.MODERATE,
        workoutDays = setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY), createdOn = MONDAY,
    )

    @Test
    fun `training day gets a training quest, rest day gets recovery`() {
        val monday = QuestEngine.questsFor(MONDAY, profile, null, 1, DayTotals(MONDAY))
        assertTrue(monday.any { it.kind == QuestKind.TRAINING })
        assertFalse(monday.any { it.kind == QuestKind.RECOVERY })

        val tuesday = QuestEngine.questsFor(MONDAY.plusDays(1), profile, null, 1, DayTotals(MONDAY.plusDays(1)))
        assertTrue(tuesday.any { it.kind == QuestKind.RECOVERY })
        assertFalse(tuesday.any { it.kind == QuestKind.TRAINING })
    }

    @Test
    fun `quest ids are unique and progress reflects totals`() {
        val totals = DayTotals(MONDAY, steps = 9_000, workoutMinutes = 30, meals = 3, waterGlasses = 2, mindMinutes = 15)
        val quests = QuestEngine.questsFor(MONDAY, profile, null, 1, totals)
        assertEquals(quests.size, quests.map { it.id }.toSet().size)
        assertTrue(quests.first { it.id == "move" }.completed)
        assertTrue(quests.first { it.id == "train" }.completed)
        assertTrue(quests.first { it.id == "nourish" }.completed)
        assertFalse(quests.first { it.id == "hydrate" }.completed)
        assertTrue(quests.first { it.id == "mind" }.completed)
        assertEquals(0.25f, quests.first { it.id == "hydrate" }.fraction)
    }

    @Test
    fun `targets scale gently with level and are bounded`() {
        val low = QuestEngine.questsFor(MONDAY, profile, null, 1, DayTotals(MONDAY))
        val high = QuestEngine.questsFor(MONDAY, profile, null, 100, DayTotals(MONDAY))
        assertTrue(high.first { it.id == "move" }.target > low.first { it.id == "move" }.target)
        assertEquals(10_000, high.first { it.id == "move" }.target)
        assertEquals(30, high.first { it.id == "mind" }.target)
    }
}
