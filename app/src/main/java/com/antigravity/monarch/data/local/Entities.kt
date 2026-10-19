package com.antigravity.monarch.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "profile")
data class ProfileEntity(
    @PrimaryKey val id: Int = 1,
    val name: String,
    val age: Int,
    val gender: String,
    val heightCm: Int,
    val weightKg: Double,
    val targetWeightKg: Double,
    val goal: String,
    val intensity: String,
    /** Comma-separated [java.time.DayOfWeek] names. */
    val workoutDays: String,
    val preferredWorkoutMinuteOfDay: Int,
    val playerClass: String?,
    val classChangedEpochDay: Long?,
    val createdEpochDay: Long,
)

/**
 * XP ledger row. [stat] is a [com.antigravity.monarch.domain.model.StatType] name or [BONUS_STAT];
 * it is never null so the unique index on (sourceKey, stat) also de-duplicates bonus rows.
 */
@Entity(
    tableName = "xp_event",
    indices = [Index(value = ["sourceKey", "stat"], unique = true), Index("epochDay")],
)
data class XpEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sourceKey: String,
    val stat: String,
    val amount: Int,
    val epochDay: Long,
    val kind: String,
    val label: String,
    val createdAt: Long,
) {
    companion object {
        const val BONUS_STAT = "BONUS"
    }
}

@Entity(
    tableName = "workout",
    indices = [Index(value = ["externalId"], unique = true), Index("epochDay")],
)
data class WorkoutEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: String,
    val title: String,
    val durationMinutes: Int,
    val intensity: String,
    val epochDay: Long,
    val startedAt: Long,
    val source: String,
    val externalId: String?,
    val notes: String,
    val xpAwarded: Int,
)

@Entity(tableName = "habit", indices = [Index("epochDay")])
data class HabitEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: String,
    val quantity: Int,
    val epochDay: Long,
    val loggedAt: Long,
    val xpAwarded: Int,
)

@Entity(tableName = "health_metrics")
data class HealthMetricsEntity(
    @PrimaryKey val epochDay: Long,
    val steps: Long,
    val sleepMinutes: Int,
    @ColumnInfo(defaultValue = "0") val activeCalories: Int,
)

@Entity(tableName = "achievement_unlock")
data class AchievementUnlockEntity(
    @PrimaryKey val id: String,
    val unlockedAt: Long,
)

/** The single active plan, stored as JSON ([com.antigravity.monarch.domain.model.GeneratedPlan]). */
@Entity(tableName = "plan")
data class PlanEntity(
    @PrimaryKey val id: Int = 1,
    val json: String,
    val updatedAt: Long,
)

/** Calendar events Monarch created, so re-syncs replace rather than duplicate them. */
@Entity(tableName = "calendar_event", indices = [Index("epochDay")])
data class CalendarEventEntity(
    @PrimaryKey val eventId: Long,
    val calendarId: Long,
    val epochDay: Long,
)

/** Projection for grouped sums. */
data class KeyedSum(val key: String, val total: Long)
data class DaySum(val day: Long, val total: Long)
