package com.antigravity.monarch.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface ProfileDao {
    @Query("SELECT * FROM profile WHERE id = 1")
    fun observe(): Flow<ProfileEntity?>

    @Query("SELECT * FROM profile WHERE id = 1")
    suspend fun get(): ProfileEntity?

    @Upsert
    suspend fun upsert(profile: ProfileEntity)

    @Query("DELETE FROM profile")
    suspend fun clear()
}

@Dao
interface XpEventDao {
    @Query("SELECT COALESCE(SUM(amount), 0) FROM xp_event")
    fun observeTotal(): Flow<Long>

    @Query("SELECT COALESCE(SUM(amount), 0) FROM xp_event")
    suspend fun total(): Long

    @Query("SELECT COALESCE(SUM(amount), 0) FROM xp_event WHERE epochDay < :day")
    suspend fun totalBefore(day: Long): Long

    @Query("SELECT stat AS `key`, SUM(amount) AS total FROM xp_event WHERE stat != 'BONUS' GROUP BY stat")
    fun observeByStat(): Flow<List<KeyedSum>>

    @Query("SELECT stat AS `key`, SUM(amount) AS total FROM xp_event WHERE stat != 'BONUS' GROUP BY stat")
    suspend fun byStat(): List<KeyedSum>

    @Query("SELECT stat AS `key`, SUM(amount) AS total FROM xp_event WHERE stat != 'BONUS' AND epochDay = :day GROUP BY stat")
    fun observeByStatForDay(day: Long): Flow<List<KeyedSum>>

    @Query("SELECT stat AS `key`, SUM(amount) AS total FROM xp_event WHERE stat != 'BONUS' AND epochDay = :day GROUP BY stat")
    suspend fun byStatForDay(day: Long): List<KeyedSum>

    @Query("SELECT epochDay AS day, SUM(amount) AS total FROM xp_event WHERE kind = 'ACTIVITY' GROUP BY epochDay")
    fun observeActivityByDay(): Flow<List<DaySum>>

    @Query("SELECT epochDay AS day, SUM(amount) AS total FROM xp_event WHERE kind = 'ACTIVITY' GROUP BY epochDay")
    suspend fun activityByDay(): List<DaySum>

    @Query("SELECT * FROM xp_event WHERE epochDay BETWEEN :from AND :to ORDER BY createdAt, id")
    fun observeBetween(from: Long, to: Long): Flow<List<XpEventEntity>>

    @Query("SELECT EXISTS(SELECT 1 FROM xp_event WHERE sourceKey = :sourceKey)")
    suspend fun exists(sourceKey: String): Boolean

    @Query("SELECT COUNT(DISTINCT sourceKey) FROM xp_event WHERE sourceKey LIKE :pattern")
    suspend fun countSourceKeysLike(pattern: String): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(events: List<XpEventEntity>)

    @Query("DELETE FROM xp_event WHERE sourceKey = :sourceKey")
    suspend fun deleteBySource(sourceKey: String)

    @Query("DELETE FROM xp_event")
    suspend fun clear()
}

@Dao
interface ActivityDao {
    @Insert
    suspend fun insertWorkout(workout: WorkoutEntity): Long

    @Query("UPDATE workout SET xpAwarded = :xp WHERE id = :id")
    suspend fun setWorkoutXp(id: Long, xp: Int)

    @Query("SELECT * FROM workout WHERE id = :id")
    suspend fun workout(id: Long): WorkoutEntity?

    @Query("SELECT * FROM workout WHERE externalId = :externalId LIMIT 1")
    suspend fun workoutByExternalId(externalId: String): WorkoutEntity?

    @Query("DELETE FROM workout WHERE id = :id")
    suspend fun deleteWorkout(id: Long)

    @Query("SELECT * FROM workout WHERE epochDay = :day ORDER BY startedAt DESC")
    suspend fun workoutsOn(day: Long): List<WorkoutEntity>

    @Query("SELECT * FROM workout WHERE epochDay = :day ORDER BY startedAt DESC")
    fun observeWorkoutsOn(day: Long): Flow<List<WorkoutEntity>>

    @Query("SELECT * FROM workout ORDER BY epochDay DESC, startedAt DESC LIMIT :limit")
    fun observeRecentWorkouts(limit: Int): Flow<List<WorkoutEntity>>

    @Query("SELECT * FROM workout WHERE epochDay BETWEEN :from AND :to ORDER BY epochDay, startedAt")
    fun observeWorkoutsBetween(from: Long, to: Long): Flow<List<WorkoutEntity>>

    @Query("SELECT COUNT(*) FROM workout")
    suspend fun workoutCount(): Int

    @Query("SELECT DISTINCT epochDay FROM workout WHERE epochDay >= :since AND durationMinutes >= :minMinutes")
    suspend fun trainedDays(since: Long, minMinutes: Int): List<Long>

    @Insert
    suspend fun insertHabit(habit: HabitEntity): Long

    @Query("UPDATE habit SET xpAwarded = :xp WHERE id = :id")
    suspend fun setHabitXp(id: Long, xp: Int)

    @Query("SELECT * FROM habit WHERE id = :id")
    suspend fun habit(id: Long): HabitEntity?

    @Query("DELETE FROM habit WHERE id = :id")
    suspend fun deleteHabit(id: Long)

    @Query("SELECT COALESCE(SUM(quantity), 0) FROM habit WHERE type = :type AND epochDay = :day")
    suspend fun habitQuantity(type: String, day: Long): Int

    @Query("SELECT * FROM habit WHERE epochDay = :day ORDER BY loggedAt DESC")
    suspend fun habitsOn(day: Long): List<HabitEntity>

    @Query("SELECT * FROM habit WHERE epochDay = :day ORDER BY loggedAt DESC")
    fun observeHabitsOn(day: Long): Flow<List<HabitEntity>>

    @Query("SELECT COALESCE(SUM(quantity), 0) FROM habit WHERE type IN ('READING', 'LEARNING', 'MEDITATION')")
    suspend fun totalMindMinutes(): Int

    @Upsert
    suspend fun upsertMetrics(metrics: HealthMetricsEntity)

    @Query("SELECT * FROM health_metrics WHERE epochDay = :day")
    suspend fun metrics(day: Long): HealthMetricsEntity?

    @Query("SELECT * FROM health_metrics WHERE epochDay = :day")
    fun observeMetrics(day: Long): Flow<HealthMetricsEntity?>

    @Query("SELECT COALESCE(MAX(steps), 0) FROM health_metrics")
    suspend fun maxDailySteps(): Long

    @Query("DELETE FROM workout")
    suspend fun clearWorkouts()

    @Query("DELETE FROM habit")
    suspend fun clearHabits()

    @Query("DELETE FROM health_metrics")
    suspend fun clearMetrics()
}

@Dao
interface AchievementDao {
    @Query("SELECT * FROM achievement_unlock")
    fun observeAll(): Flow<List<AchievementUnlockEntity>>

    @Query("SELECT * FROM achievement_unlock")
    suspend fun all(): List<AchievementUnlockEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(unlock: AchievementUnlockEntity)

    @Query("DELETE FROM achievement_unlock")
    suspend fun clear()
}

@Dao
interface PlanDao {
    @Query("SELECT * FROM plan WHERE id = 1")
    fun observe(): Flow<PlanEntity?>

    @Query("SELECT * FROM plan WHERE id = 1")
    suspend fun get(): PlanEntity?

    @Upsert
    suspend fun upsert(plan: PlanEntity)

    @Query("DELETE FROM plan")
    suspend fun clear()
}

@Dao
interface CalendarEventDao {
    @Query("SELECT * FROM calendar_event WHERE epochDay >= :fromDay")
    suspend fun upcoming(fromDay: Long): List<CalendarEventEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(event: CalendarEventEntity)

    @Query("DELETE FROM calendar_event WHERE eventId = :eventId")
    suspend fun delete(eventId: Long)
}
