package com.antigravity.monarch.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        ProfileEntity::class,
        XpEventEntity::class,
        WorkoutEntity::class,
        HabitEntity::class,
        HealthMetricsEntity::class,
        AchievementUnlockEntity::class,
        PlanEntity::class,
        CalendarEventEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class MonarchDatabase : RoomDatabase() {
    abstract fun profileDao(): ProfileDao
    abstract fun xpEventDao(): XpEventDao
    abstract fun activityDao(): ActivityDao
    abstract fun achievementDao(): AchievementDao
    abstract fun planDao(): PlanDao
    abstract fun calendarEventDao(): CalendarEventDao

    companion object {
        const val NAME = "monarch.db"
    }
}
