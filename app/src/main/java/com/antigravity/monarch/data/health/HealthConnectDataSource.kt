package com.antigravity.monarch.data.health

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectFeatures
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.antigravity.monarch.domain.model.WorkoutType
import com.antigravity.monarch.domain.repository.ExternalWorkout
import com.antigravity.monarch.domain.repository.HealthAvailability
import com.antigravity.monarch.domain.repository.HealthDataSource
import com.antigravity.monarch.domain.repository.HealthDaySnapshot
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

/** Reads steps, workouts, sleep and active calories from Health Connect (Google Health). */
@Singleton
class HealthConnectDataSource @Inject constructor(
    @ApplicationContext private val context: Context,
) : HealthDataSource {

    private val client: HealthConnectClient? by lazy {
        if (availability() == HealthAvailability.AVAILABLE) HealthConnectClient.getOrCreate(context) else null
    }

    override fun availability(): HealthAvailability = when (HealthConnectClient.getSdkStatus(context, PROVIDER_PACKAGE)) {
        HealthConnectClient.SDK_AVAILABLE -> HealthAvailability.AVAILABLE
        HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> HealthAvailability.NEEDS_UPDATE
        else -> HealthAvailability.UNAVAILABLE
    }

    override suspend fun hasRequiredPermissions(): Boolean {
        val c = client ?: return false
        return c.permissionController.getGrantedPermissions().containsAll(READ_PERMISSIONS)
    }

    /** Background reads let the periodic worker sync while the app is closed (optional). */
    suspend fun backgroundReadSupported(): Boolean {
        val c = client ?: return false
        return c.features.getFeatureStatus(HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_IN_BACKGROUND) ==
            HealthConnectFeatures.FEATURE_STATUS_AVAILABLE
    }

    override suspend fun readDay(date: LocalDate): HealthDaySnapshot {
        val c = client ?: return HealthDaySnapshot(date, 0, 0, emptyList())
        val zone = ZoneId.systemDefault()
        val start = date.atStartOfDay(zone).toInstant()
        val end = minOf(date.plusDays(1).atStartOfDay(zone).toInstant(), Instant.now())
        if (!end.isAfter(start)) return HealthDaySnapshot(date, 0, 0, emptyList())
        val range = TimeRangeFilter.between(start, end)

        val aggregate = c.aggregate(
            AggregateRequest(
                metrics = setOf(StepsRecord.COUNT_TOTAL, ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL),
                timeRangeFilter = range,
            ),
        )
        val steps = aggregate[StepsRecord.COUNT_TOTAL] ?: 0L
        val calories = aggregate[ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL]?.inKilocalories?.roundToInt() ?: 0

        val sessions = c.readRecords(ReadRecordsRequest(ExerciseSessionRecord::class, range)).records
            .filter { it.metadata.dataOrigin.packageName != context.packageName }
            .map { record ->
                ExternalWorkout(
                    externalId = record.metadata.id,
                    type = mapExerciseType(record.exerciseType),
                    title = record.title?.takeIf { it.isNotBlank() } ?: exerciseName(record.exerciseType),
                    startEpochMs = record.startTime.toEpochMilli(),
                    durationMinutes = Duration.between(record.startTime, record.endTime).toMinutes().toInt(),
                )
            }

        // Sleep is credited to the day it ends on (last night's sleep counts for today).
        val sleepWindow = TimeRangeFilter.between(start.minus(Duration.ofHours(12)), end)
        val sleepMinutes = c.readRecords(ReadRecordsRequest(SleepSessionRecord::class, sleepWindow)).records
            .filter { !it.endTime.isBefore(start) && it.endTime.isBefore(end.plusMillis(1)) }
            .sumOf { Duration.between(it.startTime, it.endTime).toMinutes() }
            .toInt()
            .coerceAtMost(16 * 60)

        return HealthDaySnapshot(date, steps, sleepMinutes, sessions, calories)
    }

    companion object {
        const val PROVIDER_PACKAGE = "com.google.android.apps.healthdata"

        val READ_PERMISSIONS: Set<String> = setOf(
            HealthPermission.getReadPermission(StepsRecord::class),
            HealthPermission.getReadPermission(ExerciseSessionRecord::class),
            HealthPermission.getReadPermission(SleepSessionRecord::class),
            HealthPermission.getReadPermission(ActiveCaloriesBurnedRecord::class),
        )
        const val BACKGROUND_PERMISSION = HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND

        fun mapExerciseType(type: Int): WorkoutType = when (type) {
            ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING,
            ExerciseSessionRecord.EXERCISE_TYPE_WEIGHTLIFTING,
            ExerciseSessionRecord.EXERCISE_TYPE_CALISTHENICS,
            -> WorkoutType.STRENGTH
            ExerciseSessionRecord.EXERCISE_TYPE_RUNNING,
            ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL,
            -> WorkoutType.RUNNING
            ExerciseSessionRecord.EXERCISE_TYPE_BIKING,
            ExerciseSessionRecord.EXERCISE_TYPE_BIKING_STATIONARY,
            -> WorkoutType.CYCLING
            ExerciseSessionRecord.EXERCISE_TYPE_HIGH_INTENSITY_INTERVAL_TRAINING,
            ExerciseSessionRecord.EXERCISE_TYPE_BOOT_CAMP,
            ExerciseSessionRecord.EXERCISE_TYPE_BOXING,
            -> WorkoutType.HIIT
            ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_POOL,
            ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_OPEN_WATER,
            -> WorkoutType.SWIMMING
            ExerciseSessionRecord.EXERCISE_TYPE_WALKING,
            ExerciseSessionRecord.EXERCISE_TYPE_HIKING,
            -> WorkoutType.WALKING
            ExerciseSessionRecord.EXERCISE_TYPE_YOGA,
            ExerciseSessionRecord.EXERCISE_TYPE_PILATES,
            ExerciseSessionRecord.EXERCISE_TYPE_STRETCHING,
            -> WorkoutType.YOGA
            ExerciseSessionRecord.EXERCISE_TYPE_BADMINTON,
            ExerciseSessionRecord.EXERCISE_TYPE_BASKETBALL,
            ExerciseSessionRecord.EXERCISE_TYPE_FOOTBALL_AMERICAN,
            ExerciseSessionRecord.EXERCISE_TYPE_FOOTBALL_AUSTRALIAN,
            ExerciseSessionRecord.EXERCISE_TYPE_SOCCER,
            ExerciseSessionRecord.EXERCISE_TYPE_TENNIS,
            ExerciseSessionRecord.EXERCISE_TYPE_VOLLEYBALL,
            ExerciseSessionRecord.EXERCISE_TYPE_MARTIAL_ARTS,
            ExerciseSessionRecord.EXERCISE_TYPE_DANCING,
            -> WorkoutType.SPORTS
            else -> WorkoutType.OTHER
        }

        private fun exerciseName(type: Int): String = mapExerciseType(type).displayName
    }
}
