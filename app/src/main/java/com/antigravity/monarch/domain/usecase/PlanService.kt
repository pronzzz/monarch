package com.antigravity.monarch.domain.usecase

import com.antigravity.monarch.domain.engine.PlanAdaptationCalculator
import com.antigravity.monarch.domain.engine.PlanGenerator
import com.antigravity.monarch.domain.engine.PlanRequest
import com.antigravity.monarch.domain.model.GeneratedPlan
import com.antigravity.monarch.domain.model.PlanAdaptation
import com.antigravity.monarch.domain.repository.ActivityRepository
import com.antigravity.monarch.domain.repository.CalendarSync
import com.antigravity.monarch.domain.repository.CalendarSyncResult
import com.antigravity.monarch.domain.repository.PlanRepository
import com.antigravity.monarch.domain.repository.ProfileRepository
import com.antigravity.monarch.domain.repository.SettingsRepository
import com.antigravity.monarch.domain.repository.TimeProvider
import kotlinx.coroutines.flow.Flow
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import javax.inject.Inject
import javax.inject.Singleton

data class PlanOutcome(val plan: GeneratedPlan, val calendar: CalendarSyncResult?)

/**
 * Owns the weekly plan lifecycle: generate after onboarding, re-generate each new week with an
 * adherence-based adaptation, and push sessions to the calendar when the player opted in.
 */
@Singleton
class PlanService @Inject constructor(
    private val profiles: ProfileRepository,
    private val plans: PlanRepository,
    private val activities: ActivityRepository,
    private val settings: SettingsRepository,
    private val generator: PlanGenerator,
    private val calendar: CalendarSync,
    private val time: TimeProvider,
) {
    fun observePlan(): Flow<GeneratedPlan?> = plans.observePlan()

    /** Regenerates only when there is no plan or the stored plan belongs to a past week. */
    suspend fun ensureCurrentPlan(): PlanOutcome? {
        profiles.getProfile() ?: return null
        val existing = plans.getPlan()
        val currentWeek = weekStart(time.today())
        if (existing != null && !LocalDate.parse(existing.workout.weekStart).isBefore(currentWeek)) return null
        return regenerate()
    }

    suspend fun regenerate(): PlanOutcome {
        val profile = profiles.getProfile() ?: error("Complete onboarding first")
        val today = time.today()
        val adaptation = currentAdaptation()
        val plan = generator.generate(PlanRequest(profile, adaptation, weekStart(today), time.nowEpochMs()))
        plans.savePlan(plan)
        val calendarResult = if (settings.current().calendarSyncEnabled && calendar.hasPermission()) {
            calendar.syncPlan(plan.workout, today, profile.preferredWorkoutTime)
        } else {
            null
        }
        return PlanOutcome(plan, calendarResult)
    }

    suspend fun currentAdaptation(): PlanAdaptation {
        val profile = profiles.getProfile() ?: return PlanAdaptation.NEUTRAL
        val today = time.today()
        if (plans.getPlan() == null) return PlanAdaptation.NEUTRAL
        val trained = activities.trainedDates(today.minusDays(PlanAdaptationCalculator.WINDOW_DAYS))
        return PlanAdaptationCalculator.compute(profile.workoutDays, trained, today, profile.createdOn)
    }

    suspend fun syncCalendar(): CalendarSyncResult {
        val profile = profiles.getProfile() ?: return CalendarSyncResult(0, 0, null, "No profile")
        val plan = plans.getPlan() ?: return CalendarSyncResult(0, 0, null, "No plan yet")
        if (!calendar.hasPermission()) return CalendarSyncResult(0, 0, null, "Calendar permission not granted")
        return calendar.syncPlan(plan.workout, time.today(), profile.preferredWorkoutTime)
    }

    suspend fun removeCalendarEvents(): Int = if (calendar.hasPermission()) calendar.removeUpcoming(time.today()) else 0

    companion object {
        fun weekStart(date: LocalDate): LocalDate = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    }
}
