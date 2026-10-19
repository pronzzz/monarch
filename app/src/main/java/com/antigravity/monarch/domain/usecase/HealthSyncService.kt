package com.antigravity.monarch.domain.usecase

import com.antigravity.monarch.domain.model.AwardResult
import com.antigravity.monarch.domain.repository.HealthAvailability
import com.antigravity.monarch.domain.repository.HealthDataSource
import com.antigravity.monarch.domain.repository.ProfileRepository
import com.antigravity.monarch.domain.repository.SettingsRepository
import com.antigravity.monarch.domain.repository.TimeProvider
import javax.inject.Inject
import javax.inject.Singleton

sealed interface HealthSyncOutcome {
    data object Unavailable : HealthSyncOutcome
    data object MissingPermissions : HealthSyncOutcome
    data object NoProfile : HealthSyncOutcome
    data class Synced(val days: Int, val result: AwardResult) : HealthSyncOutcome
    data class Failed(val message: String) : HealthSyncOutcome
}

/** Pulls recent days from Health Connect and feeds them into the progression engine. */
@Singleton
class HealthSyncService @Inject constructor(
    private val health: HealthDataSource,
    private val progression: ProgressionService,
    private val profiles: ProfileRepository,
    private val settings: SettingsRepository,
    private val time: TimeProvider,
) {
    suspend fun sync(days: Int = DEFAULT_DAYS): HealthSyncOutcome {
        if (health.availability() != HealthAvailability.AVAILABLE) return HealthSyncOutcome.Unavailable
        if (!health.hasRequiredPermissions()) return HealthSyncOutcome.MissingPermissions
        val profile = profiles.getProfile() ?: return HealthSyncOutcome.NoProfile
        return try {
            val today = time.today()
            // Never award XP for days before the player registered.
            val dates = (days - 1 downTo 0).map { today.minusDays(it.toLong()) }.filterNot { it.isBefore(profile.createdOn) }
            val snapshots = dates.map { health.readDay(it) }
            settings.update { it.copy(hasSyncedHealth = true, lastHealthSyncEpochMs = time.nowEpochMs()) }
            var result = AwardResult()
            for (snapshot in snapshots) result += progression.applyHealthDay(snapshot)
            HealthSyncOutcome.Synced(snapshots.size, result)
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
