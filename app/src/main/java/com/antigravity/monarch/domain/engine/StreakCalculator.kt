package com.antigravity.monarch.domain.engine

import com.antigravity.monarch.domain.model.StreakState
import java.time.DayOfWeek
import java.time.LocalDate

/**
 * Recomputes the streak from history every time, so it can never drift from the ledger.
 *
 * Rules (no punishment, PRD §6):
 *  - An active day (>= [XpRules.ACTIVE_DAY_XP] activity XP) extends the streak.
 *  - A designated rest day without activity is neutral: it neither extends nor breaks.
 *  - A missed training day consumes a streak shield if one is held; otherwise the streak
 *    resets to 0. No XP or stats are ever taken away.
 *  - One shield is earned per [ACTIVE_DAYS_PER_SHIELD] active days, holding at most
 *    [MAX_SHIELDS]. Players start with [STARTING_SHIELDS].
 *  - Today never breaks a streak: until it is earned it is reported as pending.
 */
object StreakCalculator {
    const val MAX_SHIELDS = 2
    const val STARTING_SHIELDS = 1
    const val ACTIVE_DAYS_PER_SHIELD = 7

    fun compute(
        activeDays: Set<LocalDate>,
        restDays: Set<DayOfWeek>,
        today: LocalDate,
        startDate: LocalDate? = activeDays.minOrNull(),
    ): StreakState {
        val todayIsRest = today.dayOfWeek in restDays
        val first = listOfNotNull(startDate, activeDays.minOrNull()).minOrNull()
            ?: return StreakState(shields = STARTING_SHIELDS, todayPending = true, todayIsRestDay = todayIsRest)

        var current = 0
        var longest = 0
        var shields = STARTING_SHIELDS
        var shieldsUsed = 0
        var activeCount = 0

        var day = first
        while (day.isBefore(today)) {
            when {
                day in activeDays -> {
                    current++
                    activeCount++
                    if (activeCount % ACTIVE_DAYS_PER_SHIELD == 0) shields = minOf(MAX_SHIELDS, shields + 1)
                }
                day.dayOfWeek in restDays -> Unit
                current == 0 -> Unit // nothing to protect yet
                shields > 0 -> {
                    shields--
                    shieldsUsed++
                }
                else -> current = 0
            }
            longest = maxOf(longest, current)
            day = day.plusDays(1)
        }

        val todayActive = today in activeDays
        if (todayActive) {
            current++
            activeCount++
            if (activeCount % ACTIVE_DAYS_PER_SHIELD == 0) shields = minOf(MAX_SHIELDS, shields + 1)
            longest = maxOf(longest, current)
        }
        return StreakState(
            current = current,
            longest = longest,
            shields = shields,
            shieldsUsed = shieldsUsed,
            todayPending = !todayActive,
            todayIsRestDay = todayIsRest,
        )
    }
}
