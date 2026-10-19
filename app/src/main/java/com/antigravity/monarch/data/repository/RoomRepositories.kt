package com.antigravity.monarch.data.repository

import androidx.room.withTransaction
import com.antigravity.monarch.data.local.AchievementDao
import com.antigravity.monarch.data.local.AchievementUnlockEntity
import com.antigravity.monarch.data.local.ActivityDao
import com.antigravity.monarch.data.local.HabitEntity
import com.antigravity.monarch.data.local.HealthMetricsEntity
import com.antigravity.monarch.data.local.KeyedSum
import com.antigravity.monarch.data.local.MonarchDatabase
import com.antigravity.monarch.data.local.PlanDao
import com.antigravity.monarch.data.local.PlanEntity
import com.antigravity.monarch.data.local.ProfileDao
import com.antigravity.monarch.data.local.ProfileEntity
import com.antigravity.monarch.data.local.WorkoutEntity
import com.antigravity.monarch.data.local.XpEventDao
import com.antigravity.monarch.data.local.XpEventEntity
import com.antigravity.monarch.domain.model.AchievementDef
import com.antigravity.monarch.domain.model.ActivitySource
import com.antigravity.monarch.domain.model.DailyHealthMetrics
import com.antigravity.monarch.domain.model.DayTotals
import com.antigravity.monarch.domain.model.FitnessGoal
import com.antigravity.monarch.domain.model.Gender
import com.antigravity.monarch.domain.model.GeneratedPlan
import com.antigravity.monarch.domain.model.HabitEntry
import com.antigravity.monarch.domain.model.HabitType
import com.antigravity.monarch.domain.model.Intensity
import com.antigravity.monarch.domain.model.PlayerClass
import com.antigravity.monarch.domain.model.PlayerProfile
import com.antigravity.monarch.domain.model.StatType
import com.antigravity.monarch.domain.model.WorkoutEntry
import com.antigravity.monarch.domain.model.WorkoutType
import com.antigravity.monarch.domain.model.XpEvent
import com.antigravity.monarch.domain.model.XpKind
import com.antigravity.monarch.domain.repository.AchievementRepository
import com.antigravity.monarch.domain.repository.ActivityRepository
import com.antigravity.monarch.domain.repository.PlanRepository
import com.antigravity.monarch.domain.repository.ProfileRepository
import com.antigravity.monarch.domain.repository.TransactionRunner
import com.antigravity.monarch.domain.repository.XpLedgerRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import javax.inject.Inject
import javax.inject.Singleton

private val MIND_HABITS = setOf(HabitType.READING, HabitType.LEARNING, HabitType.MEDITATION)

@Singleton
class RoomTransactionRunner @Inject constructor(private val db: MonarchDatabase) : TransactionRunner {
    override suspend fun <T> run(block: suspend () -> T): T = db.withTransaction { block() }
}

@Singleton
class RoomProfileRepository @Inject constructor(private val dao: ProfileDao) : ProfileRepository {
    override fun observeProfile(): Flow<PlayerProfile?> = dao.observe().map { it?.toDomain() }
    override suspend fun getProfile(): PlayerProfile? = dao.get()?.toDomain()
    override suspend fun saveProfile(profile: PlayerProfile) = dao.upsert(profile.toEntity())
}

internal fun ProfileEntity.toDomain() = PlayerProfile(
    name = name,
    age = age,
    gender = enumOr(gender, Gender.OTHER),
    heightCm = heightCm,
    weightKg = weightKg,
    targetWeightKg = targetWeightKg,
    goal = enumOr(goal, FitnessGoal.GENERAL_FITNESS),
    intensity = enumOr(intensity, Intensity.MODERATE),
    workoutDays = workoutDays.split(',').mapNotNull { runCatching { DayOfWeek.valueOf(it.trim()) }.getOrNull() }.toSet(),
    preferredWorkoutTime = LocalTime.ofSecondOfDay((preferredWorkoutMinuteOfDay.coerceIn(0, 1439) * 60).toLong()),
    playerClass = playerClass?.let { runCatching { PlayerClass.valueOf(it) }.getOrNull() },
    classChangedOn = classChangedEpochDay?.let(LocalDate::ofEpochDay),
    createdOn = LocalDate.ofEpochDay(createdEpochDay),
)

internal fun PlayerProfile.toEntity() = ProfileEntity(
    name = name,
    age = age,
    gender = gender.name,
    heightCm = heightCm,
    weightKg = weightKg,
    targetWeightKg = targetWeightKg,
    goal = goal.name,
    intensity = intensity.name,
    workoutDays = workoutDays.sorted().joinToString(",") { it.name },
    preferredWorkoutMinuteOfDay = preferredWorkoutTime.hour * 60 + preferredWorkoutTime.minute,
    playerClass = playerClass?.name,
    classChangedEpochDay = classChangedOn?.toEpochDay(),
    createdEpochDay = createdOn.toEpochDay(),
)

private inline fun <reified E : Enum<E>> enumOr(name: String, fallback: E): E =
    runCatching { enumValueOf<E>(name) }.getOrDefault(fallback)

@Singleton
class RoomXpLedgerRepository @Inject constructor(private val dao: XpEventDao) : XpLedgerRepository {
    override fun observeTotalXp(): Flow<Long> = dao.observeTotal()
    override fun observeStatXp(): Flow<Map<StatType, Long>> = dao.observeByStat().map { it.toStatMap() }
    override fun observeStatXpForDay(date: LocalDate): Flow<Map<StatType, Int>> =
        dao.observeByStatForDay(date.toEpochDay()).map { list -> list.toStatMap().mapValues { it.value.toInt() } }
    override fun observeActivityXpByDay(): Flow<Map<LocalDate, Int>> =
        dao.observeActivityByDay().map { list -> list.associate { LocalDate.ofEpochDay(it.day) to it.total.toInt() } }
    override fun observeEventsBetween(from: LocalDate, to: LocalDate): Flow<List<XpEvent>> =
        dao.observeBetween(from.toEpochDay(), to.toEpochDay()).map { list -> list.map { it.toDomain() } }

    override suspend fun totalXp(): Long = dao.total()
    override suspend fun totalXpBefore(date: LocalDate): Long = dao.totalBefore(date.toEpochDay())
    override suspend fun statXp(): Map<StatType, Long> = dao.byStat().toStatMap()
    override suspend fun statXpForDay(date: LocalDate): Map<StatType, Int> =
        dao.byStatForDay(date.toEpochDay()).toStatMap().mapValues { it.value.toInt() }
    override suspend fun activityXpByDay(): Map<LocalDate, Int> =
        dao.activityByDay().associate { LocalDate.ofEpochDay(it.day) to it.total.toInt() }
    override suspend fun hasEvent(sourceKey: String): Boolean = dao.exists(sourceKey)
    override suspend fun countSourceKeysLike(pattern: String): Int = dao.countSourceKeysLike(pattern)
    override suspend fun addEvents(events: List<XpEvent>) = dao.insertAll(events.map { it.toEntity() })
    override suspend fun deleteEvents(sourceKey: String) = dao.deleteBySource(sourceKey)
    override suspend fun clearAll() = dao.clear()

    private fun List<KeyedSum>.toStatMap(): Map<StatType, Long> =
        mapNotNull { sum -> runCatching { StatType.valueOf(sum.key) }.getOrNull()?.let { it to sum.total } }.toMap()
}

internal fun XpEvent.toEntity() = XpEventEntity(
    sourceKey = sourceKey,
    stat = stat?.name ?: XpEventEntity.BONUS_STAT,
    amount = amount,
    epochDay = date.toEpochDay(),
    kind = kind.name,
    label = label,
    createdAt = createdAtEpochMs,
)

internal fun XpEventEntity.toDomain() = XpEvent(
    sourceKey = sourceKey,
    stat = if (stat == XpEventEntity.BONUS_STAT) null else runCatching { StatType.valueOf(stat) }.getOrNull(),
    amount = amount,
    date = LocalDate.ofEpochDay(epochDay),
    kind = enumOr(kind, XpKind.ACTIVITY),
    label = label,
    createdAtEpochMs = createdAt,
)

@Singleton
class RoomActivityRepository @Inject constructor(private val dao: ActivityDao) : ActivityRepository {
    override suspend fun insertWorkout(entry: WorkoutEntry): Long = dao.insertWorkout(entry.toEntity())
    override suspend fun setWorkoutXp(id: Long, xp: Int) = dao.setWorkoutXp(id, xp)
    override suspend fun workoutById(id: Long): WorkoutEntry? = dao.workout(id)?.toDomain()
    override suspend fun workoutByExternalId(externalId: String): WorkoutEntry? = dao.workoutByExternalId(externalId)?.toDomain()
    override suspend fun workoutsOn(date: LocalDate): List<WorkoutEntry> = dao.workoutsOn(date.toEpochDay()).map { it.toDomain() }
    override suspend fun deleteWorkout(id: Long) = dao.deleteWorkout(id)
    override suspend fun insertHabit(entry: HabitEntry): Long = dao.insertHabit(entry.toEntity())
    override suspend fun setHabitXp(id: Long, xp: Int) = dao.setHabitXp(id, xp)
    override suspend fun habitById(id: Long): HabitEntry? = dao.habit(id)?.toDomain()
    override suspend fun deleteHabit(id: Long) = dao.deleteHabit(id)
    override suspend fun habitQuantity(type: HabitType, date: LocalDate): Int = dao.habitQuantity(type.name, date.toEpochDay())
    override suspend fun upsertHealthMetrics(metrics: DailyHealthMetrics) = dao.upsertMetrics(
        HealthMetricsEntity(metrics.date.toEpochDay(), metrics.steps, metrics.sleepMinutes, metrics.activeCalories),
    )

    override suspend fun dayTotals(date: LocalDate): DayTotals {
        val day = date.toEpochDay()
        return totals(date, dao.workoutsOn(day), dao.habitsOn(day), dao.metrics(day))
    }

    override fun observeDayTotals(date: LocalDate): Flow<DayTotals> {
        val day = date.toEpochDay()
        return combine(dao.observeWorkoutsOn(day), dao.observeHabitsOn(day), dao.observeMetrics(day)) { w, h, m -> totals(date, w, h, m) }
    }

    override suspend fun workoutCount(): Int = dao.workoutCount()
    override suspend fun trainedDates(since: LocalDate, minMinutes: Int): Set<LocalDate> =
        dao.trainedDays(since.toEpochDay(), minMinutes).map(LocalDate::ofEpochDay).toSet()
    override suspend fun maxDailySteps(): Long = dao.maxDailySteps()
    override suspend fun totalMindMinutes(): Int = dao.totalMindMinutes()
    override fun observeRecentWorkouts(limit: Int): Flow<List<WorkoutEntry>> = dao.observeRecentWorkouts(limit).map { l -> l.map { it.toDomain() } }
    override fun observeWorkoutsBetween(from: LocalDate, to: LocalDate): Flow<List<WorkoutEntry>> =
        dao.observeWorkoutsBetween(from.toEpochDay(), to.toEpochDay()).map { l -> l.map { it.toDomain() } }
    override fun observeHabitsForDay(date: LocalDate): Flow<List<HabitEntry>> = dao.observeHabitsOn(date.toEpochDay()).map { l -> l.map { it.toDomain() } }

    override suspend fun clearAll() {
        dao.clearWorkouts()
        dao.clearHabits()
        dao.clearMetrics()
    }

    private fun totals(date: LocalDate, workouts: List<WorkoutEntity>, habits: List<HabitEntity>, metrics: HealthMetricsEntity?): DayTotals {
        fun habitSum(vararg types: HabitType) = habits.filter { h -> types.any { it.name == h.type } }.sumOf { it.quantity }
        val manualSleep = habitSum(HabitType.SLEEP)
        return DayTotals(
            date = date,
            steps = metrics?.steps ?: 0,
            // Prefer tracked sleep; fall back to a manual entry when nothing was synced.
            sleepMinutes = (metrics?.sleepMinutes ?: 0).takeIf { it > 0 } ?: manualSleep,
            workoutCount = workouts.size,
            workoutMinutes = workouts.sumOf { it.durationMinutes },
            meals = habitSum(HabitType.MEAL),
            waterGlasses = habitSum(HabitType.HYDRATION),
            mindMinutes = habitSum(*MIND_HABITS.toTypedArray()),
            activeCalories = metrics?.activeCalories ?: 0,
        )
    }
}

internal fun WorkoutEntry.toEntity() = WorkoutEntity(
    id = id, type = type.name, title = title, durationMinutes = durationMinutes, intensity = intensity.name,
    epochDay = date.toEpochDay(), startedAt = startedAtEpochMs, source = source.name, externalId = externalId,
    notes = notes, xpAwarded = xpAwarded, originApp = originApp,
)

internal fun WorkoutEntity.toDomain() = WorkoutEntry(
    id = id, type = enumOr(type, WorkoutType.OTHER), title = title, durationMinutes = durationMinutes,
    intensity = enumOr(intensity, Intensity.MODERATE), date = LocalDate.ofEpochDay(epochDay), startedAtEpochMs = startedAt,
    source = enumOr(source, ActivitySource.MANUAL), externalId = externalId, notes = notes, xpAwarded = xpAwarded,
    originApp = originApp,
)

internal fun HabitEntry.toEntity() = HabitEntity(id, type.name, quantity, date.toEpochDay(), loggedAtEpochMs, xpAwarded)

internal fun HabitEntity.toDomain() = HabitEntry(
    id = id, type = enumOr(type, HabitType.MEAL), quantity = quantity, date = LocalDate.ofEpochDay(epochDay),
    loggedAtEpochMs = loggedAt, xpAwarded = xpAwarded,
)

@Singleton
class RoomAchievementRepository @Inject constructor(private val dao: AchievementDao) : AchievementRepository {
    override fun observeUnlocked(): Flow<Map<AchievementDef, Long>> = dao.observeAll().map { it.toMap() }
    override suspend fun unlocked(): Set<AchievementDef> = dao.all().toMap().keys
    override suspend fun unlock(def: AchievementDef, atEpochMs: Long) = dao.insert(AchievementUnlockEntity(def.name, atEpochMs))
    override suspend fun clearAll() = dao.clear()

    private fun List<AchievementUnlockEntity>.toMap() =
        mapNotNull { e -> runCatching { AchievementDef.valueOf(e.id) }.getOrNull()?.let { it to e.unlockedAt } }.toMap()
}

@Singleton
class RoomPlanRepository @Inject constructor(private val dao: PlanDao, private val json: Json) : PlanRepository {
    override fun observePlan(): Flow<GeneratedPlan?> = dao.observe().map { it?.decode() }
    override suspend fun getPlan(): GeneratedPlan? = dao.get()?.decode()
    override suspend fun savePlan(plan: GeneratedPlan) =
        dao.upsert(PlanEntity(json = json.encodeToString(GeneratedPlan.serializer(), plan), updatedAt = plan.workout.generatedAtEpochMs))
    override suspend fun clearAll() = dao.clear()

    /** A corrupt or outdated stored plan is treated as missing so a fresh one is generated. */
    private fun PlanEntity.decode(): GeneratedPlan? =
        runCatching { this@RoomPlanRepository.json.decodeFromString(GeneratedPlan.serializer(), json) }.getOrNull()
}
