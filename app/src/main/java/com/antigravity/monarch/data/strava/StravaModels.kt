package com.antigravity.monarch.data.strava

import com.antigravity.monarch.domain.model.ActivitySource
import com.antigravity.monarch.domain.model.WorkoutType
import com.antigravity.monarch.domain.repository.ExternalWorkout
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Instant

@Serializable
internal data class StravaTokenResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String,
    @SerialName("expires_at") val expiresAt: Long,
    val athlete: StravaAthlete? = null,
)

@Serializable
internal data class StravaAthlete(
    val id: Long? = null,
    val firstname: String? = null,
    val lastname: String? = null,
)

@Serializable
internal data class StravaActivity(
    val id: Long,
    val name: String = "",
    @SerialName("sport_type") val sportType: String? = null,
    val type: String? = null,
    @SerialName("start_date") val startDate: String,
    @SerialName("moving_time") val movingTime: Int = 0,
    @SerialName("elapsed_time") val elapsedTime: Int = 0,
)

/** What Monarch keeps after connecting. Stored encrypted; never logged. */
@Serializable
data class StravaTokens(
    val accessToken: String,
    val refreshToken: String,
    /** Epoch seconds. */
    val expiresAt: Long,
    val athleteName: String? = null,
)

object StravaMapper {
    /** Maps Strava `sport_type` (falling back to the legacy `type`) onto Monarch workout types. */
    fun workoutType(sportType: String?, legacyType: String? = null): WorkoutType {
        val t = (sportType ?: legacyType ?: "").lowercase()
        return when {
            t.isEmpty() -> WorkoutType.OTHER
            "run" in t -> WorkoutType.RUNNING // Run, TrailRun, VirtualRun
            "ride" in t || "cycl" in t || "bike" in t -> WorkoutType.CYCLING // Ride, MountainBikeRide, GravelRide, EBikeRide, VirtualRide
            "swim" in t -> WorkoutType.SWIMMING
            t == "walk" || t == "hike" -> WorkoutType.WALKING
            t == "weighttraining" || t == "crossfit" || t == "rockclimbing" -> WorkoutType.STRENGTH
            t in HIIT -> WorkoutType.HIIT
            t == "yoga" || t == "pilates" -> WorkoutType.YOGA
            t in SPORTS -> WorkoutType.SPORTS
            else -> WorkoutType.OTHER
        }
    }

    private val HIIT = setOf("highintensityintervaltraining", "rowing", "virtualrow", "elliptical", "stairstepper", "nordicski")

    private val SPORTS = setOf(
        "soccer", "tennis", "badminton", "basketball", "volleyball", "squash", "racquetball", "pickleball",
        "tabletennis", "golf", "skateboard", "inlineskate", "iceskate", "alpineski", "backcountryski",
        "snowboard", "surfing", "kitesurf", "windsurf", "standuppaddling", "canoeing", "kayaking", "sail",
    )

    internal fun toExternal(a: StravaActivity): ExternalWorkout? {
        val start = runCatching { Instant.parse(a.startDate) }.getOrNull() ?: return null
        // Moving time excludes pauses; fall back to elapsed time for manual entries.
        val seconds = (if (a.movingTime > 0) a.movingTime else a.elapsedTime).coerceAtLeast(0)
        return ExternalWorkout(
            externalId = "strava:${a.id}",
            type = workoutType(a.sportType, a.type),
            title = a.name.ifBlank { a.sportType ?: "Strava activity" },
            startEpochMs = start.toEpochMilli(),
            durationMinutes = seconds / 60,
            source = ActivitySource.STRAVA,
            originApp = "Strava",
        )
    }
}
