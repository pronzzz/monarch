package com.antigravity.monarch.domain.repository

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
import com.antigravity.monarch.domain.model.WorkoutType
import com.antigravity.monarch.domain.model.XpEvent
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate
import java.time.LocalTime

interface ProfileRepository {
    fun observeProfile(): Flow<PlayerProfile?>
    suspend fun getProfile(): PlayerProfile?
    suspend fun saveProfile(profile: PlayerProfile)
}

/** The XP ledger. All progression is derived from these rows. */
interface XpLedgerRepository {
    fun observeTotalXp(): Flow<Long>
    fun observeStatXp(): Flow<Map<StatType, Long>>
    fun observeStatXpForDay(date: LocalDate): Flow<Map<StatType, Int>>
    fun observeActivityXpByDay(): Flow<Map<LocalDate, Int>>
    fun observeEventsBetween(from: LocalDate, to: LocalDate): Flow<List<XpEvent>>

    suspend fun totalXp(): Long
    suspend fun totalXpBefore(date: LocalDate): Long
    suspend fun statXp(): Map<StatType, Long>
    suspend fun statXpForDay(date: LocalDate): Map<StatType, Int>
    suspend fun activityXpByDay(): Map<LocalDate, Int>
    suspend fun hasEvent(sourceKey: String): Boolean
    suspend fun countSourceKeysLike(pattern: String): Int
    suspend fun addEvents(events: List<XpEvent>)
    suspend fun deleteEvents(sourceKey: String)
    suspend fun clearAll()
}

interface ActivityRepository {
    suspend fun insertWorkout(entry: WorkoutEntry): Long
    suspend fun setWorkoutXp(id: Long, xp: Int)
    suspend fun workoutById(id: Long): WorkoutEntry?
    suspend fun workoutByExternalId(externalId: String): WorkoutEntry?
    suspend fun deleteWorkout(id: Long)
    suspend fun insertHabit(entry: HabitEntry): Long
    suspend fun setHabitXp(id: Long, xp: Int)
    suspend fun habitById(id: Long): HabitEntry?
    suspend fun deleteHabit(id: Long)
    suspend fun habitQuantity(type: HabitType, date: LocalDate): Int
    suspend fun upsertHealthMetrics(metrics: DailyHealthMetrics)
    suspend fun dayTotals(date: LocalDate): DayTotals
    suspend fun workoutCount(): Int
    suspend fun trainedDates(since: LocalDate, minMinutes: Int = 10): Set<LocalDate>
    suspend fun maxDailySteps(): Long
    suspend fun totalMindMinutes(): Int

    fun observeDayTotals(date: LocalDate): Flow<DayTotals>
    fun observeRecentWorkouts(limit: Int): Flow<List<WorkoutEntry>>
    fun observeWorkoutsBetween(from: LocalDate, to: LocalDate): Flow<List<WorkoutEntry>>
    fun observeHabitsForDay(date: LocalDate): Flow<List<HabitEntry>>
    suspend fun clearAll()
}

interface AchievementRepository {
    fun observeUnlocked(): Flow<Map<AchievementDef, Long>>
    suspend fun unlocked(): Set<AchievementDef>
    suspend fun unlock(def: AchievementDef, atEpochMs: Long)
    suspend fun clearAll()
}

interface PlanRepository {
    fun observePlan(): Flow<GeneratedPlan?>
    suspend fun getPlan(): GeneratedPlan?
    suspend fun savePlan(plan: GeneratedPlan)
    suspend fun clearAll()
}

enum class WidgetMode { MINIMAL, DETAILED }

data class AppSettings(
    val widgetMode: WidgetMode = WidgetMode.DETAILED,
    val remindersEnabled: Boolean = false,
    val reminderTime: LocalTime = LocalTime.of(8, 0),
    val calendarSyncEnabled: Boolean = false,
    val cloudAiEnabled: Boolean = true,
    val hasSyncedHealth: Boolean = false,
    val lastHealthSyncEpochMs: Long = 0,
    val lastSeenLevel: Int = 1,
    val signedInEmail: String? = null,
    val signedInName: String? = null,
)

interface SettingsRepository {
    val settings: Flow<AppSettings>
    suspend fun current(): AppSettings
    suspend fun update(transform: (AppSettings) -> AppSettings)
}

/** Runs a block atomically (a Room transaction in production). */
interface TransactionRunner {
    suspend fun <T> run(block: suspend () -> T): T
}

interface TimeProvider {
    fun today(): LocalDate
    fun nowEpochMs(): Long
}

enum class HealthAvailability { AVAILABLE, NEEDS_UPDATE, UNAVAILABLE }

/** A workout session recorded by another app and read through Health Connect. */
data class ExternalWorkout(
    val externalId: String,
    val type: WorkoutType,
    val title: String,
    val startEpochMs: Long,
    val durationMinutes: Int,
)

data class HealthDaySnapshot(
    val date: LocalDate,
    val steps: Long,
    val sleepMinutes: Int,
    val workouts: List<ExternalWorkout>,
    val activeCalories: Int = 0,
)

interface HealthDataSource {
    fun availability(): HealthAvailability
    suspend fun hasRequiredPermissions(): Boolean
    suspend fun readDay(date: LocalDate): HealthDaySnapshot
}

data class CalendarSyncResult(val created: Int, val removed: Int, val calendarName: String?, val error: String? = null) {
    val ok: Boolean get() = error == null
}

interface CalendarSync {
    fun hasPermission(): Boolean
    suspend fun syncPlan(plan: WorkoutPlan, from: LocalDate, time: LocalTime, days: Int = 7): CalendarSyncResult
    suspend fun removeUpcoming(from: LocalDate): Int
}

/** Notified after progression changes so passive surfaces (widgets) can refresh. */
interface StatusListener {
    suspend fun onStatusChanged()
}
