package com.antigravity.monarch.domain.usecase

import com.antigravity.monarch.domain.engine.LevelCurve
import com.antigravity.monarch.domain.engine.XpRules
import com.antigravity.monarch.domain.model.AchievementDef
import com.antigravity.monarch.domain.model.FitnessGoal
import com.antigravity.monarch.domain.model.Gender
import com.antigravity.monarch.domain.model.HabitType
import com.antigravity.monarch.domain.model.Intensity
import com.antigravity.monarch.domain.model.PlayerClass
import com.antigravity.monarch.domain.model.PlayerProfile
import com.antigravity.monarch.domain.model.StatType
import com.antigravity.monarch.domain.model.WorkoutType
import com.antigravity.monarch.domain.model.XpEvent
import com.antigravity.monarch.domain.model.XpKind
import com.antigravity.monarch.domain.repository.AppSettings
import com.antigravity.monarch.domain.repository.ExternalWorkout
import com.antigravity.monarch.domain.repository.HealthAvailability
import com.antigravity.monarch.domain.repository.HealthDaySnapshot
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate

/** Monday. */
private val TODAY: LocalDate = LocalDate.of(2026, 10, 19)

private fun testProfile(playerClass: PlayerClass? = null) = PlayerProfile(
    name = "Sung", age = 24, gender = Gender.MALE, heightCm = 180, weightKg = 78.0, targetWeightKg = 75.0,
    goal = FitnessGoal.MUSCLE_GAIN, intensity = Intensity.MODERATE,
    workoutDays = setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY),
    playerClass = playerClass, createdOn = TODAY,
)

private class World(profile: PlayerProfile? = testProfile(), settings: AppSettings = AppSettings()) {
    val time = FakeTime(TODAY)
    val profiles = FakeProfiles(profile)
    val ledger = FakeLedger()
    val activities = FakeActivities()
    val achievements = FakeAchievements()
    val plans = FakePlans()
    val settings = FakeSettings(settings)
    val listener = CountingListener()
    val health = FakeHealth()
    val calendar = FakeCalendar()
    val generator = RecordingGenerator()
    val progression = ProgressionService(profiles, ledger, activities, achievements, plans, this.settings, DirectTransactions, time, listener)
    val healthSync = HealthSyncService(health, progression, profiles, this.settings, time)
    val planService = PlanService(profiles, plans, activities, this.settings, generator, calendar, time)

    suspend fun statXpToday() = ledger.statXpForDay(time.today)
}

class ProgressionServiceTest {

    @Test
    fun `logging a workout awards stat xp, quest bonus, achievement and level up`() = runTest {
        val w = World()
        val result = w.progression.logWorkout(WorkoutType.STRENGTH, "Push day", 40, Intensity.MODERATE)

        assertEquals(mapOf(StatType.STRENGTH to 30, StatType.ENDURANCE to 6, StatType.HEALTH to 4), result.xpByStat)
        assertTrue(AchievementDef.FIRST_WORKOUT in result.achievements)
        assertTrue(result.questsCompleted.any { it.id == "train" })
        assertEquals(40 + 50, result.bonusXp)
        assertEquals(130L, w.ledger.totalXp())
        assertEquals(1, result.levelUp?.fromLevel)
        assertEquals(2, result.levelUp?.toLevel)
        assertEquals(40, w.activities.workouts.single().xpAwarded)
        assertTrue(w.listener.calls > 0)
    }

    @Test
    fun `class bonus boosts focus stat`() = runTest {
        val w = World(testProfile(PlayerClass.WARRIOR))
        val result = w.progression.logWorkout(WorkoutType.STRENGTH, "Lift", 40, Intensity.MODERATE)
        assertEquals(33, result.xpByStat[StatType.STRENGTH])
    }

    @Test
    fun `daily stat cap stops farming`() = runTest {
        val w = World()
        repeat(5) { w.progression.logWorkout(WorkoutType.STRENGTH, "Lift", 120, Intensity.INTENSE) }
        val last = w.progression.logWorkout(WorkoutType.STRENGTH, "Lift", 120, Intensity.INTENSE)
        assertEquals(XpRules.DAILY_STAT_CAP, w.statXpToday()[StatType.STRENGTH])
        assertNull(last.xpByStat[StatType.STRENGTH])
        assertTrue(last.cappedXp > 0)
        assertTrue(w.statXpToday().values.all { it <= XpRules.DAILY_STAT_CAP })
    }

    @Test
    fun `next day the cap resets`() = runTest {
        val w = World()
        repeat(3) { w.progression.logWorkout(WorkoutType.STRENGTH, "Lift", 120, Intensity.INTENSE) }
        w.time.today = TODAY.plusDays(1)
        val next = w.progression.logWorkout(WorkoutType.STRENGTH, "Lift", 60, Intensity.MODERATE)
        assertEquals(45, next.xpByStat[StatType.STRENGTH])
    }

    @Test
    fun `health sync is idempotent and steps replace the earlier award`() = runTest {
        val w = World()
        val run = ExternalWorkout("hc-1", WorkoutType.RUNNING, "Morning run", 0L, 30)
        w.progression.applyHealthDay(HealthDaySnapshot(TODAY, 5_000, 0, listOf(run)))
        val afterFirst = w.ledger.events.value.filter { it.sourceKey == "steps:$TODAY" }.sumOf { it.amount }
        assertEquals(24, afterFirst)

        w.progression.applyHealthDay(HealthDaySnapshot(TODAY, 10_000, 0, listOf(run)))
        w.progression.applyHealthDay(HealthDaySnapshot(TODAY, 10_000, 0, listOf(run)))
        val steps = w.ledger.events.value.filter { it.sourceKey == "steps:$TODAY" }.sumOf { it.amount }
        assertEquals(38, steps)
        assertEquals(1, w.activities.workouts.size)
        val runEvents = w.ledger.events.value.filter { it.sourceKey == "workout:1" }
        assertEquals(runEvents.map { it.stat }.distinct().size, runEvents.size)
        assertEquals(30, runEvents.sumOf { it.amount })
    }

    @Test
    fun `quests are only awarded once per day`() = runTest {
        val w = World()
        val first = w.progression.logHabit(HabitType.MEAL, 3)
        assertTrue(first.questsCompleted.any { it.id == "nourish" })
        val second = w.progression.logHabit(HabitType.MEAL, 1)
        assertTrue(second.questsCompleted.isEmpty())
        assertEquals(1, w.ledger.events.value.count { it.sourceKey == "quest:$TODAY:nourish" })
    }

    @Test
    fun `completing every quest grants the perfect day bonus`() = runTest {
        val w = World()
        w.progression.applyHealthDay(HealthDaySnapshot(TODAY, 9_000, 0, emptyList()))
        w.progression.logWorkout(WorkoutType.STRENGTH, "Lift", 30, Intensity.MODERATE)
        w.progression.logHabit(HabitType.MEAL, 3)
        w.progression.logHabit(HabitType.HYDRATION, 8)
        val last = w.progression.logHabit(HabitType.READING, 20)
        assertTrue(w.ledger.hasEvent("quest:$TODAY:perfect"))
        assertTrue(AchievementDef.PERFECT_DAY in last.achievements)
    }

    @Test
    fun `classes are locked until their level and then have a cooldown`() = runTest {
        val w = World()
        assertEquals(ClassChangeResult.Locked(10), w.progression.chooseClass(PlayerClass.WARRIOR))

        w.ledger.addEvents(listOf(XpEvent("grant", null, LevelCurve.totalXpForLevel(10).toInt(), TODAY, XpKind.QUEST, "test", 0)))
        assertEquals(ClassChangeResult.Success, w.progression.chooseClass(PlayerClass.WARRIOR))
        assertEquals(PlayerClass.WARRIOR, w.profiles.getProfile()?.playerClass)
        assertTrue(w.progression.chooseClass(PlayerClass.RUNNER) is ClassChangeResult.Cooldown)
        assertEquals(ClassChangeResult.Locked(20), w.progression.chooseClass(PlayerClass.MONK))

        w.time.today = TODAY.plusDays(PlayerClass.CHANGE_COOLDOWN_DAYS)
        assertEquals(ClassChangeResult.Success, w.progression.chooseClass(PlayerClass.RUNNER))
    }

    @Test
    fun `deleting a manual workout removes its xp but synced ones are protected`() = runTest {
        val w = World()
        w.progression.logWorkout(WorkoutType.CYCLING, "Ride", 30, Intensity.MODERATE)
        val id = w.activities.workouts.single().id
        assertTrue(w.progression.deleteWorkout(id))
        assertFalse(w.ledger.hasEvent("workout:$id"))
        assertTrue(w.activities.workouts.isEmpty())

        w.progression.applyHealthDay(HealthDaySnapshot(TODAY, 0, 0, listOf(ExternalWorkout("hc-9", WorkoutType.RUNNING, "Run", 0, 20))))
        assertFalse(w.progression.deleteWorkout(w.activities.workouts.single().id))
    }

    @Test
    fun `logging is limited to today and yesterday with sane durations`() = runTest {
        val w = World()
        w.progression.logWorkout(WorkoutType.YOGA, "Flow", 20, Intensity.LIGHT, date = TODAY.minusDays(1))
        for (bad in listOf(
            suspend { w.progression.logWorkout(WorkoutType.YOGA, "x", 20, Intensity.LIGHT, date = TODAY.plusDays(1)) },
            suspend { w.progression.logWorkout(WorkoutType.YOGA, "x", 20, Intensity.LIGHT, date = TODAY.minusDays(2)) },
            suspend { w.progression.logWorkout(WorkoutType.YOGA, "x", 0, Intensity.LIGHT) },
            suspend { w.progression.logHabit(HabitType.MEAL, 0) },
        )) {
            try {
                bad()
                fail("expected rejection")
            } catch (expected: IllegalArgumentException) {
                // ok
            }
        }
    }

    @Test
    fun `status read model reflects the ledger`() = runTest {
        val w = World()
        w.progression.logWorkout(WorkoutType.RUNNING, "Run", 30, Intensity.MODERATE)
        val status = w.progression.observeStatus(TODAY).first()
        assertEquals("Sung", status.name)
        assertEquals(w.ledger.totalXp(), status.totalXp)
        assertEquals(LevelCurve.levelFor(status.totalXp), status.level)
        assertEquals(StatType.entries.size, status.stats.size)
        assertEquals(1, status.streak.current)
        assertEquals(3, status.topStats().size)
        assertEquals(status.totalXp.toInt(), status.todayXp)
    }

    @Test
    fun `refresh without profile fails loudly`() = runTest {
        val w = World(profile = null)
        try {
            w.progression.refresh()
            fail()
        } catch (expected: IllegalStateException) {
            // ok
        }
        assertNull(w.progression.currentStatus())
    }
}

class HealthSyncServiceTest {

    @Test
    fun `reports unavailable and missing permissions`() = runTest {
        val w = World()
        w.health.availability = HealthAvailability.UNAVAILABLE
        assertEquals(HealthSyncOutcome.Unavailable, w.healthSync.sync())
        w.health.availability = HealthAvailability.AVAILABLE
        w.health.permitted = false
        assertEquals(HealthSyncOutcome.MissingPermissions, w.healthSync.sync())
    }

    @Test
    fun `sync never reads days before registration and unlocks first sync`() = runTest {
        val w = World()
        w.health.days[TODAY] = HealthDaySnapshot(TODAY, 12_000, 0, emptyList())
        val outcome = w.healthSync.sync()
        assertTrue(outcome is HealthSyncOutcome.Synced)
        assertEquals(listOf(TODAY), w.health.reads)
        assertTrue(w.settings.current().hasSyncedHealth)
        val unlocked = w.achievements.unlocked()
        assertTrue(AchievementDef.FIRST_SYNC in unlocked)
        assertTrue(AchievementDef.STEPS_10K in unlocked)
    }

    @Test
    fun `later sync covers the trailing window`() = runTest {
        val w = World()
        w.time.today = TODAY.plusDays(5)
        w.healthSync.sync()
        assertEquals(listOf(TODAY.plusDays(3), TODAY.plusDays(4), TODAY.plusDays(5)), w.health.reads)
    }
}

class PlanServiceTest {

    @Test
    fun `ensureCurrentPlan generates once per week`() = runTest {
        val w = World()
        assertNotNull(w.planService.ensureCurrentPlan())
        assertNull(w.planService.ensureCurrentPlan())
        w.time.today = TODAY.plusDays(6) // Sunday, same week
        assertNull(w.planService.ensureCurrentPlan())
        w.time.today = TODAY.plusDays(7) // next Monday
        assertNotNull(w.planService.ensureCurrentPlan())
        assertEquals(2, w.plans.saves)
        assertEquals(TODAY.plusDays(7).toString(), w.plans.getPlan()?.workout?.weekStart)
    }

    @Test
    fun `calendar sync only runs when enabled and permitted`() = runTest {
        val w = World()
        w.planService.regenerate()
        assertTrue(w.calendar.synced.isEmpty())

        w.settings.update { it.copy(calendarSyncEnabled = true) }
        w.calendar.permitted = false
        assertNull(w.planService.regenerate().calendar)

        w.calendar.permitted = true
        val outcome = w.planService.regenerate()
        assertEquals(3, outcome.calendar?.created)
        assertEquals(1, w.calendar.synced.size)
    }

    @Test
    fun `second week adapts to adherence`() = runTest {
        val w = World()
        w.planService.regenerate()
        // Train every planned day for two weeks.
        for (offset in listOf(0, 2, 4, 7, 9, 11)) {
            w.time.today = TODAY.plusDays(offset.toLong())
            w.progression.logWorkout(WorkoutType.STRENGTH, "Lift", 45, Intensity.MODERATE)
        }
        w.time.today = TODAY.plusDays(14)
        w.planService.ensureCurrentPlan()
        val adaptation = w.generator.requests.last().adaptation
        assertEquals(com.antigravity.monarch.domain.model.AdaptationDirection.PROGRESS, adaptation.direction)
    }
}
