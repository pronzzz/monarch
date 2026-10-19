package com.antigravity.monarch.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

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
    version = 2,
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

        /** v2: synced workouts remember which app recorded them (Samsung Health, Zepp, Strava...). */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE workout ADD COLUMN originApp TEXT")
            }
        }

        val MIGRATIONS = arrayOf(MIGRATION_1_2)
    }
}
