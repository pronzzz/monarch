package com.antigravity.monarch.domain.usecase

import com.antigravity.monarch.domain.engine.PlanGenerator
import com.antigravity.monarch.domain.engine.PlanRequest
import com.antigravity.monarch.domain.engine.RuleBasedPlanGenerator
import com.antigravity.monarch.domain.model.AchievementDef
import com.antigravity.monarch.domain.model.DailyHealthMetrics
import com.antigravity.monarch.domain.model.DayTotals
import com.antigravity.monarch.domain.model.GeneratedPlan
import com.antigravity.monarch.domain.model.HabitEntry
import com.antigravity.monarch.domain.model.HabitType
import com.antigravity.monarch.domain.model.PlayerProfile
import com.antigravity.monarch.domain.model.StatType
import com.antigravity.monarch.domain.model.WorkoutEntry
import com.antigravity.monarch.domain.model.WorkoutPlan
import com.antigravity.monarch.domain.model.XpEvent
import com.antigravity.monarch.domain.model.XpKind
import com.antigravity.monarch.domain.repository.AchievementRepository
import com.antigravity.monarch.domain.repository.ActivityRepository
import com.antigravity.monarch.domain.repository.AppSettings
import com.antigravity.monarch.domain.repository.CalendarSync
import com.antigravity.monarch.domain.repository.CalendarSyncResult
import com.antigravity.monarch.domain.repository.HealthAvailability
import com.antigravity.monarch.domain.repository.HealthDataSource
import com.antigravity.monarch.domain.repository.HealthDaySnapshot
import com.antigravity.monarch.domain.repository.PlanRepository
import com.antigravity.monarch.domain.repository.ProfileRepository
import com.antigravity.monarch.domain.repository.SettingsRepository
import com.antigravity.monarch.domain.repository.StatusListener
import com.antigravity.monarch.domain.repository.TimeProvider
import com.antigravity.monarch.domain.repository.TransactionRunner
import com.antigravity.monarch.domain.repository.XpLedgerRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import java.time.LocalTime

class FakeTime(var today: LocalDate, var now: Long = 1_000L) : TimeProvider {
    override fun today() = today
    override fun nowEpochMs() = now++
}

class FakeProfiles(initial: PlayerProfile? = null) : ProfileRepository {
    val state = MutableStateFlow(initial)
    override fun observeProfile() = state
    override suspend fun getProfile() = state.value
    override suspend fun saveProfile(profile: PlayerProfile) { state.value = profile }
}

class FakeLedger : XpLedgerRepository {
    val events = MutableStateFlow<List<XpEvent>>(emptyList())
    private val list get() = events.value

    override fun observeTotalXp(): Flow<Long> = events.map { e -> e.sumOf { it.amount.toLong() } }
    override fun observeStatXp(): Flow<Map<StatType, Long>> = events.map { statXpOf(it) }
    override fun observeStatXpForDay(date: LocalDate): Flow<Map<StatType, Int>> = events.map { dayXpOf(it, date) }
    override fun observeActivityXpByDay(): Flow<Map<LocalDate, Int>> = events.map { byDayOf(it) }
    override fun observeEventsBetween(from: LocalDate, to: LocalDate) = events.map { e -> e.filter { it.date in from..to } }

    override suspend fun totalXp() = list.sumOf { it.amount.toLong() }
    override suspend fun totalXpBefore(date: LocalDate) = list.filter { it.date < date }.sumOf { it.amount.toLong() }
    override suspend fun statXp() = statXpOf(list)
    override suspend fun statXpForDay(date: LocalDate) = dayXpOf(list, date)
    override suspend fun activityXpByDay() = byDayOf(list)
    override suspend fun hasEvent(sourceKey: String) = list.any { it.sourceKey == sourceKey }
    override suspend fun countSourceKeysLike(pattern: String): Int {
        val regex = Regex(pattern.split("%").joinToString(".*") { Regex.escape(it) })
        return list.map { it.sourceKey }.distinct().count { regex.matches(it) }
    }
    override suspend fun addEvents(events: List<XpEvent>) {
        val existing = list.map { it.sourceKey to it.stat }.toSet()
        this.events.value = list + events.filter { (it.sourceKey to it.stat) !in existing }
    }
    override suspend fun deleteEvents(sourceKey: String) { events.value = list.filter { it.sourceKey != sourceKey } }
    override suspend fun clearAll() { events.value = emptyList() }

    private fun statXpOf(e: List<XpEvent>) = e.filter { it.stat != null }.groupBy { it.stat!! }.mapValues { (_, v) -> v.sumOf { it.amount.toLong() } }
    private fun dayXpOf(e: List<XpEvent>, date: LocalDate) = e.filter { it.stat != null && it.date == date }.groupBy { it.stat!! }.mapValues { (_, v) -> v.sumOf { it.amount } }
    private fun byDayOf(e: List<XpEvent>) = e.filter { it.kind == XpKind.ACTIVITY }.groupBy { it.date }.mapValues { (_, v) -> v.sumOf { it.amount } }
}

class FakeActivities : ActivityRepository {
    val workouts = mutableListOf<WorkoutEntry>()
    val habits = mutableListOf<HabitEntry>()
    val metrics = mutableMapOf<LocalDate, DailyHealthMetrics>()
    private var nextId = 1L

    override suspend fun insertWorkout(entry: WorkoutEntry): Long = (nextId++).also { workouts += entry.copy(id = it) }
    override suspend fun setWorkoutXp(id: Long, xp: Int) { replaceWorkout(id) { it.copy(xpAwarded = xp) } }
    override suspend fun workoutById(id: Long) = workouts.firstOrNull { it.id == id }
    override suspend fun workoutByExternalId(externalId: String) = workouts.firstOrNull { it.externalId == externalId }
    override suspend fun workoutsOn(date: LocalDate) = workouts.filter { it.date == date }
    override suspend fun deleteWorkout(id: Long) { workouts.removeAll { it.id == id } }
    override suspend fun insertHabit(entry: HabitEntry): Long = (nextId++).also { habits += entry.copy(id = it) }
    override suspend fun setHabitXp(id: Long, xp: Int) {
        val i = habits.indexOfFirst { it.id == id }
        if (i >= 0) habits[i] = habits[i].copy(xpAwarded = xp)
    }
    override suspend fun habitById(id: Long) = habits.firstOrNull { it.id == id }
    override suspend fun deleteHabit(id: Long) { habits.removeAll { it.id == id } }
    override suspend fun habitQuantity(type: HabitType, date: LocalDate) = habits.filter { it.type == type && it.date == date }.sumOf { it.quantity }
    override suspend fun upsertHealthMetrics(metrics: DailyHealthMetrics) { this.metrics[metrics.date] = metrics }
    override suspend fun dayTotals(date: LocalDate): DayTotals {
        val w = workouts.filter { it.date == date }
        val h = habits.filter { it.date == date }
        val m = metrics[date]
        return DayTotals(
            date = date, steps = m?.steps ?: 0,
            sleepMinutes = (m?.sleepMinutes ?: 0).takeIf { it > 0 } ?: h.filter { it.type == HabitType.SLEEP }.sumOf { it.quantity },
            workoutCount = w.size, workoutMinutes = w.sumOf { it.durationMinutes },
            meals = h.filter { it.type == HabitType.MEAL }.sumOf { it.quantity },
            waterGlasses = h.filter { it.type == HabitType.HYDRATION }.sumOf { it.quantity },
            mindMinutes = h.filter { it.type in setOf(HabitType.READING, HabitType.LEARNING, HabitType.MEDITATION) }.sumOf { it.quantity },
        )
    }
    override suspend fun workoutCount() = workouts.size
    override suspend fun trainedDates(since: LocalDate, minMinutes: Int) =
        workouts.filter { !it.date.isBefore(since) && it.durationMinutes >= minMinutes }.map { it.date }.toSet()
    override suspend fun maxDailySteps() = metrics.values.maxOfOrNull { it.steps } ?: 0L
    override suspend fun totalMindMinutes() = habits.filter { it.type in setOf(HabitType.READING, HabitType.LEARNING, HabitType.MEDITATION) }.sumOf { it.quantity }
    override fun observeDayTotals(date: LocalDate): Flow<DayTotals> = throw UnsupportedOperationException()
    override fun observeRecentWorkouts(limit: Int): Flow<List<WorkoutEntry>> = throw UnsupportedOperationException()
    override fun observeWorkoutsBetween(from: LocalDate, to: LocalDate): Flow<List<WorkoutEntry>> = throw UnsupportedOperationException()
    override fun observeHabitsForDay(date: LocalDate): Flow<List<HabitEntry>> = throw UnsupportedOperationException()
    override suspend fun clearAll() { workouts.clear(); habits.clear(); metrics.clear() }

    private fun replaceWorkout(id: Long, f: (WorkoutEntry) -> WorkoutEntry) {
        val i = workouts.indexOfFirst { it.id == id }
        if (i >= 0) workouts[i] = f(workouts[i])
    }
}

class FakeAchievements : AchievementRepository {
    val unlockedMap = MutableStateFlow<Map<AchievementDef, Long>>(emptyMap())
    override fun observeUnlocked() = unlockedMap
    override suspend fun unlocked() = unlockedMap.value.keys
    override suspend fun unlock(def: AchievementDef, atEpochMs: Long) { unlockedMap.value = unlockedMap.value + (def to atEpochMs) }
    override suspend fun clearAll() { unlockedMap.value = emptyMap() }
}

class FakePlans(initial: GeneratedPlan? = null) : PlanRepository {
    val state = MutableStateFlow(initial)
    var saves = 0
    override fun observePlan() = state
    override suspend fun getPlan() = state.value
    override suspend fun savePlan(plan: GeneratedPlan) { saves++; state.value = plan }
    override suspend fun clearAll() { state.value = null }
}

class FakeSettings(initial: AppSettings = AppSettings()) : SettingsRepository {
    val state = MutableStateFlow(initial)
    override val settings = state
    override suspend fun current() = state.value
    override suspend fun update(transform: (AppSettings) -> AppSettings) { state.value = transform(state.value) }
}

object DirectTransactions : TransactionRunner {
    override suspend fun <T> run(block: suspend () -> T): T = block()
}

class CountingListener : StatusListener {
    var calls = 0
    override suspend fun onStatusChanged() { calls++ }
}

class FakeHealth(
    var availability: HealthAvailability = HealthAvailability.AVAILABLE,
    var permitted: Boolean = true,
    val days: MutableMap<LocalDate, HealthDaySnapshot> = mutableMapOf(),
) : HealthDataSource {
    val reads = mutableListOf<LocalDate>()
    override fun availability() = availability
    override suspend fun hasRequiredPermissions() = permitted
    override suspend fun readDay(date: LocalDate): HealthDaySnapshot {
        reads += date
        return days[date] ?: HealthDaySnapshot(date, 0, 0, emptyList())
    }
}

class FakeFeed(
    override val source: com.antigravity.monarch.domain.model.ActivitySource = com.antigravity.monarch.domain.model.ActivitySource.STRAVA,
    var connected: Boolean = true,
    var workouts: List<com.antigravity.monarch.domain.repository.ExternalWorkout> = emptyList(),
    var failure: Exception? = null,
) : com.antigravity.monarch.domain.repository.WorkoutFeed {
    override suspend fun isConnected() = connected
    override suspend fun workoutsBetween(from: java.time.Instant, to: java.time.Instant): List<com.antigravity.monarch.domain.repository.ExternalWorkout> {
        failure?.let { throw it }
        return workouts.filter { it.startEpochMs in from.toEpochMilli()..to.toEpochMilli() }
    }
}

class FakeCalendar(var permitted: Boolean = true) : CalendarSync {
    val synced = mutableListOf<WorkoutPlan>()
    override fun hasPermission() = permitted
    override suspend fun syncPlan(plan: WorkoutPlan, from: LocalDate, time: LocalTime, days: Int): CalendarSyncResult {
        synced += plan
        return CalendarSyncResult(plan.days.size, 0, "Test")
    }
    override suspend fun removeUpcoming(from: LocalDate) = 0
}

class RecordingGenerator : PlanGenerator {
    val requests = mutableListOf<PlanRequest>()
    override suspend fun generate(request: PlanRequest): GeneratedPlan {
        requests += request
        return RuleBasedPlanGenerator.build(request)
    }
}
