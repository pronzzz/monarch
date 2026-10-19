package com.antigravity.monarch

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.antigravity.monarch.data.local.MonarchDatabase
import com.antigravity.monarch.data.local.PlanEntity
import com.antigravity.monarch.data.repository.RoomAchievementRepository
import com.antigravity.monarch.data.repository.RoomActivityRepository
import com.antigravity.monarch.data.repository.RoomPlanRepository
import com.antigravity.monarch.data.repository.RoomProfileRepository
import com.antigravity.monarch.data.repository.RoomTransactionRunner
import com.antigravity.monarch.data.repository.RoomXpLedgerRepository
import com.antigravity.monarch.domain.engine.PlanRequest
import com.antigravity.monarch.domain.engine.RuleBasedPlanGenerator
import com.antigravity.monarch.domain.engine.XpRules
import com.antigravity.monarch.domain.model.AchievementDef
import com.antigravity.monarch.domain.model.ActivitySource
import com.antigravity.monarch.domain.model.DailyHealthMetrics
import com.antigravity.monarch.domain.model.FitnessGoal
import com.antigravity.monarch.domain.model.Gender
import com.antigravity.monarch.domain.model.HabitEntry
import com.antigravity.monarch.domain.model.HabitType
import com.antigravity.monarch.domain.model.Intensity
import com.antigravity.monarch.domain.model.PlanAdaptation
import com.antigravity.monarch.domain.model.PlayerClass
import com.antigravity.monarch.domain.model.PlayerProfile
import com.antigravity.monarch.domain.model.StatType
import com.antigravity.monarch.domain.model.WorkoutEntry
import com.antigravity.monarch.domain.model.WorkoutType
import com.antigravity.monarch.domain.model.XpEvent
import com.antigravity.monarch.domain.model.XpKind
import com.antigravity.monarch.domain.repository.AppSettings
import com.antigravity.monarch.domain.repository.SettingsRepository
import com.antigravity.monarch.domain.repository.StatusListener
import com.antigravity.monarch.domain.repository.TimeProvider
import com.antigravity.monarch.domain.usecase.ProgressionService
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

private val DAY: LocalDate = LocalDate.of(2026, 10, 19)

private val PROFILE = PlayerProfile(
    name = "Jin", age = 27, gender = Gender.FEMALE, heightCm = 165, weightKg = 60.5, targetWeightKg = 58.0,
    goal = FitnessGoal.MUSCLE_GAIN, intensity = Intensity.INTENSE,
    workoutDays = setOf(DayOfWeek.MONDAY, DayOfWeek.THURSDAY), preferredWorkoutTime = LocalTime.of(6, 45),
    playerClass = PlayerClass.RUNNER, classChangedOn = DAY.minusDays(3), createdOn = DAY,
)

@RunWith(AndroidJUnit4::class)
class RoomRepositoriesTest {
    private lateinit var db: MonarchDatabase
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), MonarchDatabase::class.java).build()
    }

    @After
    fun tearDown() = db.close()

    private fun ev(key: String, stat: StatType?, amount: Int, date: LocalDate = DAY, kind: XpKind = XpKind.ACTIVITY) =
        XpEvent(key, stat, amount, date, kind, "label", 1L)

    @Test
    fun profileRoundTripsEveryField() = runBlocking {
        val repo = RoomProfileRepository(db.profileDao())
        assertNull(repo.getProfile())
        repo.saveProfile(PROFILE)
        assertEquals(PROFILE, repo.getProfile())
        assertEquals(PROFILE, repo.observeProfile().first())
        repo.saveProfile(PROFILE.copy(name = "Jinwoo"))
        assertEquals("Jinwoo", repo.getProfile()!!.name)
    }

    @Test
    fun ledgerAggregatesAndDeduplicates() = runBlocking {
        val ledger = RoomXpLedgerRepository(db.xpEventDao())
        ledger.addEvents(listOf(ev("workout:1", StatType.STRENGTH, 30), ev("workout:1", StatType.HEALTH, 4)))
        // Same (sourceKey, stat) is ignored, including bonus rows with no stat.
        ledger.addEvents(listOf(ev("workout:1", StatType.STRENGTH, 999)))
        ledger.addEvents(listOf(ev("quest:a", null, 20, kind = XpKind.QUEST)))
        ledger.addEvents(listOf(ev("quest:a", null, 20, kind = XpKind.QUEST)))
        ledger.addEvents(listOf(ev("steps:old", StatType.ENDURANCE, 10, DAY.minusDays(2))))

        assertEquals(64L, ledger.totalXp())
        assertEquals(64L, ledger.observeTotalXp().first())
        assertEquals(10L, ledger.totalXpBefore(DAY))
        assertEquals(mapOf(StatType.STRENGTH to 30L, StatType.HEALTH to 4L, StatType.ENDURANCE to 10L), ledger.statXp())
        assertEquals(mapOf(StatType.STRENGTH to 30, StatType.HEALTH to 4), ledger.statXpForDay(DAY))
        assertEquals(mapOf(DAY to 34, DAY.minusDays(2) to 10), ledger.activityXpByDay())
        assertTrue(ledger.hasEvent("quest:a"))
        assertEquals(1, ledger.countSourceKeysLike("quest:%"))
        val today = ledger.observeEventsBetween(DAY, DAY).first()
        assertEquals(3, today.size)
        assertNull(today.first { it.sourceKey == "quest:a" }.stat)

        ledger.deleteEvents("workout:1")
        assertFalse(ledger.hasEvent("workout:1"))
        assertEquals(30L, ledger.totalXp())
    }

    @Test
    fun activityTotalsCombineWorkoutsHabitsAndMetrics() = runBlocking {
        val repo = RoomActivityRepository(db.activityDao())
        val id = repo.insertWorkout(WorkoutEntry(type = WorkoutType.RUNNING, title = "Run", durationMinutes = 35, intensity = Intensity.MODERATE, date = DAY, startedAtEpochMs = 5))
        repo.insertWorkout(WorkoutEntry(type = WorkoutType.YOGA, title = "Flow", durationMinutes = 8, intensity = Intensity.LIGHT, date = DAY, startedAtEpochMs = 6))
        repo.insertWorkout(
            WorkoutEntry(type = WorkoutType.WALKING, title = "Walk", durationMinutes = 40, intensity = Intensity.LIGHT, date = DAY.minusDays(1), startedAtEpochMs = 1, source = ActivitySource.HEALTH_CONNECT, externalId = "hc-1"),
        )
        repo.setWorkoutXp(id, 35)
        repo.insertHabit(HabitEntry(type = HabitType.MEAL, quantity = 2, date = DAY, loggedAtEpochMs = 1))
        repo.insertHabit(HabitEntry(type = HabitType.HYDRATION, quantity = 5, date = DAY, loggedAtEpochMs = 2))
        repo.insertHabit(HabitEntry(type = HabitType.READING, quantity = 20, date = DAY, loggedAtEpochMs = 3))
        repo.insertHabit(HabitEntry(type = HabitType.MEDITATION, quantity = 10, date = DAY, loggedAtEpochMs = 4))
        repo.insertHabit(HabitEntry(type = HabitType.SLEEP, quantity = 400, date = DAY, loggedAtEpochMs = 5))

        var totals = repo.dayTotals(DAY)
        assertEquals(2, totals.workoutCount)
        assertEquals(43, totals.workoutMinutes)
        assertEquals(2, totals.meals)
        assertEquals(5, totals.waterGlasses)
        assertEquals(30, totals.mindMinutes)
        assertEquals(400, totals.sleepMinutes) // manual sleep used when nothing synced

        repo.upsertHealthMetrics(DailyHealthMetrics(DAY, steps = 9_876, sleepMinutes = 450, activeCalories = 321))
        totals = repo.observeDayTotals(DAY).first()
        assertEquals(9_876L, totals.steps)
        assertEquals(450, totals.sleepMinutes) // tracked sleep wins
        assertEquals(321, totals.activeCalories)

        assertEquals(35, repo.workoutById(id)!!.xpAwarded)
        assertNotNull(repo.workoutByExternalId("hc-1"))
        assertEquals(3, repo.workoutCount())
        assertEquals(setOf(DAY, DAY.minusDays(1)), repo.trainedDates(DAY.minusDays(7)))
        assertEquals(9_876L, repo.maxDailySteps())
        assertEquals(30, repo.totalMindMinutes())
        assertEquals(2, repo.habitQuantity(HabitType.MEAL, DAY))
        assertEquals(3, repo.observeRecentWorkouts(10).first().size)
    }

    @Test
    fun achievementsAndPlansPersist() = runBlocking {
        val achievements = RoomAchievementRepository(db.achievementDao())
        achievements.unlock(AchievementDef.FIRST_WORKOUT, 10)
        achievements.unlock(AchievementDef.FIRST_WORKOUT, 99) // ignored
        assertEquals(mapOf(AchievementDef.FIRST_WORKOUT to 10L), achievements.observeUnlocked().first())

        val plans = RoomPlanRepository(db.planDao(), json)
        assertNull(plans.getPlan())
        val plan = RuleBasedPlanGenerator.build(PlanRequest(PROFILE, PlanAdaptation.NEUTRAL, DAY, 77))
        plans.savePlan(plan)
        assertEquals(plan, plans.getPlan())
        assertEquals(plan, plans.observePlan().first())

        // A corrupt stored plan is treated as missing rather than crashing.
        db.planDao().upsert(PlanEntity(json = "{broken", updatedAt = 0))
        assertNull(plans.getPlan())
    }

    @Test
    fun concurrentLogsNeverExceedTheDailyCap() = runBlocking {
        val profiles = RoomProfileRepository(db.profileDao())
        profiles.saveProfile(PROFILE.copy(playerClass = null))
        val ledger = RoomXpLedgerRepository(db.xpEventDao())
        val service = ProgressionService(
            profiles, ledger, RoomActivityRepository(db.activityDao()), RoomAchievementRepository(db.achievementDao()),
            RoomPlanRepository(db.planDao(), json), InMemorySettings(), RoomTransactionRunner(db), FixedTime(DAY), NoopListener,
        )
        (1..12).map { async { service.logWorkout(WorkoutType.STRENGTH, "Lift $it", 90, Intensity.INTENSE, date = DAY) } }.awaitAll()
        val today = ledger.statXpForDay(DAY)
        assertEquals(XpRules.DAILY_STAT_CAP, today[StatType.STRENGTH])
        assertTrue(today.values.all { it <= XpRules.DAILY_STAT_CAP })
        assertEquals(12, db.activityDao().workoutCount())
        // FIRST_WORKOUT and WORKOUTS_10 each unlocked exactly once.
        assertEquals(1, ledger.countSourceKeysLike("achievement:FIRST_WORKOUT"))
        assertTrue(ledger.hasEvent("achievement:WORKOUTS_10"))
    }
}

class FixedTime(private val day: LocalDate) : TimeProvider {
    private var now = 1_000L
    override fun today() = day
    override fun nowEpochMs() = now++
}

object NoopListener : StatusListener {
    override suspend fun onStatusChanged() = Unit
}

class InMemorySettings : SettingsRepository {
    private val state = MutableStateFlow(AppSettings())
    override val settings = state
    override suspend fun current() = state.value
    override suspend fun update(transform: (AppSettings) -> AppSettings) {
        state.value = transform(state.value)
    }
}
