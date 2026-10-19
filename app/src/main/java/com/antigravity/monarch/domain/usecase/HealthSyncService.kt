package com.antigravity.monarch.domain.usecase

import com.antigravity.monarch.domain.model.AwardResult
import com.antigravity.monarch.domain.repository.ExternalWorkout
import com.antigravity.monarch.domain.repository.HealthAvailability
import com.antigravity.monarch.domain.repository.HealthDataSource
import com.antigravity.monarch.domain.repository.HealthDaySnapshot
import com.antigravity.monarch.domain.repository.ProfileRepository
import com.antigravity.monarch.domain.repository.SettingsRepository
import com.antigravity.monarch.domain.repository.TimeProvider
import com.antigravity.monarch.domain.repository.WorkoutFeed
import kotlinx.coroutines.CancellationException
import java.time.Instant
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

sealed interface HealthSyncOutcome {
    data object Unavailable : HealthSyncOutcome
    data object MissingPermissions : HealthSyncOutcome
    data object NoProfile : HealthSyncOutcome

    /**
     * @property sources the sources read this time (e.g. "Health Connect", "Strava").
     * @property warnings per-source failures that didn't stop the rest of the sync.
     */
    data class Synced(
        val days: Int,
        val result: AwardResult,
        val sources: List<String> = emptyList(),
        val warnings: List<String> = emptyList(),
    ) : HealthSyncOutcome

    data class Failed(val message: String) : HealthSyncOutcome
}

/**
 * Pulls recent days from every connected source and feeds them into the progression engine.
 *
 * Health Connect carries steps, sleep and workouts from Samsung Health, Zepp, Google Fit and
 * others; API feeds (Strava) add workouts. Duplicates across sources are removed downstream.
 * One failing source never blocks the others.
 */
@Singleton
class HealthSyncService @Inject constructor(
    private val health: HealthDataSource,
    private val feeds: Set<@JvmSuppressWildcards WorkoutFeed>,
    private val progression: ProgressionService,
    private val profiles: ProfileRepository,
    private val settings: SettingsRepository,
    private val time: TimeProvider,
) {
    suspend fun sync(days: Int = DEFAULT_DAYS): HealthSyncOutcome {
        val profile = profiles.getProfile() ?: return HealthSyncOutcome.NoProfile
        val healthAvailable = health.availability() == HealthAvailability.AVAILABLE
        val healthReady = healthAvailable && runCatching { health.hasRequiredPermissions() }.getOrDefault(false)
        val activeFeeds = feeds.filter { runCatching { it.isConnected() }.getOrDefault(false) }
        if (!healthReady && activeFeeds.isEmpty()) {
            return if (healthAvailable) HealthSyncOutcome.MissingPermissions else HealthSyncOutcome.Unavailable
        }
        return try {
            val today = time.today()
            // Never award XP for days before the player registered.
            val dates = (days - 1 downTo 0).map { today.minusDays(it.toLong()) }.filterNot { it.isBefore(profile.createdOn) }
            if (dates.isEmpty()) return HealthSyncOutcome.Synced(0, AwardResult())

            val warnings = mutableListOf<String>()
            val snapshots = dates.associateWith { date ->
                if (healthReady) health.readDay(date) else HealthDaySnapshot(date, 0, 0, emptyList(), metricsAvailable = false)
            }
            val from = dates.first().atStartOfDay(time.zone()).toInstant()
            val to = Instant.ofEpochMilli(time.nowEpochMs())
            val feedWorkouts = activeFeeds.flatMap { feed ->
                try {
                    feed.workoutsBetween(from, to)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    warnings += "${feed.source.displayName}: ${e.message ?: e.javaClass.simpleName}"
                    emptyList()
                }
            }
            val byDate: Map<LocalDate, List<ExternalWorkout>> =
                feedWorkouts.groupBy { Instant.ofEpochMilli(it.startEpochMs).atZone(time.zone()).toLocalDate() }

            settings.update { it.copy(hasSyncedHealth = true, lastHealthSyncEpochMs = time.nowEpochMs()) }
            var result = AwardResult()
            for (date in dates) {
                val snapshot = snapshots.getValue(date)
                result += progression.applyHealthDay(snapshot.copy(workouts = snapshot.workouts + byDate[date].orEmpty()))
            }
            val sources = listOfNotNull("Health Connect".takeIf { healthReady }) + activeFeeds.map { it.source.displayName }
            HealthSyncOutcome.Synced(dates.size, result, sources, warnings)
        } catch (e: CancellationException) {
            throw e
        } catch (e: SecurityException) {
            HealthSyncOutcome.MissingPermissions
        } catch (e: Exception) {
            HealthSyncOutcome.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    companion object {
        /** Today plus the two previous days, so late-arriving data (e.g. sleep) is picked up. */
        const val DEFAULT_DAYS = 3
    }
}
