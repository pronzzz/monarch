package com.antigravity.monarch.domain.engine

import com.antigravity.monarch.domain.model.AdaptationDirection
import com.antigravity.monarch.domain.model.PlanAdaptation
import java.time.DayOfWeek
import java.time.LocalDate

/**
 * Looks at the last two weeks of planned vs completed sessions and decides whether next
 * week's plan should progress, hold or deload. Workouts on unplanned days still count, so
 * rescheduling a session is never treated as a miss.
 */
object PlanAdaptationCalculator {
    const val WINDOW_DAYS = 14L
    const val MIN_PLANNED_FOR_SIGNAL = 3
    const val PROGRESS_THRESHOLD = 0.85
    const val DELOAD_THRESHOLD = 0.5

    fun compute(
        workoutDays: Set<DayOfWeek>,
        trainedDates: Set<LocalDate>,
        today: LocalDate,
        trackingStart: LocalDate,
    ): PlanAdaptation {
        val windowStart = maxOf(today.minusDays(WINDOW_DAYS), trackingStart)
        val windowDays = generateSequence(windowStart) { it.plusDays(1) }.takeWhile { it.isBefore(today) }.toList()
        val planned = windowDays.count { it.dayOfWeek in workoutDays }
        val trained = windowDays.count { it in trainedDates }
        val completed = minOf(planned, trained)

        if (planned < MIN_PLANNED_FOR_SIGNAL) {
            return PlanAdaptation(
                direction = AdaptationDirection.HOLD,
                completionRate = null,
                plannedSessions = planned,
                completedSessions = completed,
                note = "Not enough history yet. Holding volume steady while The System calibrates.",
            )
        }
        val rate = completed.toDouble() / planned
        val pct = (rate * 100).toInt()
        return when {
            rate >= PROGRESS_THRESHOLD -> PlanAdaptation(
                AdaptationDirection.PROGRESS, rate, planned, completed,
                "You cleared $completed of $planned sessions ($pct%). Volume increased slightly.",
            )
            rate < DELOAD_THRESHOLD -> PlanAdaptation(
                AdaptationDirection.DELOAD, rate, planned, completed,
                "You cleared $completed of $planned sessions. Sessions are shorter this week so they fit your life. No penalty.",
            )
            else -> PlanAdaptation(
                AdaptationDirection.HOLD, rate, planned, completed,
                "You cleared $completed of $planned sessions ($pct%). Holding steady.",
            )
        }
    }
}
