package com.antigravity.monarch.domain.engine

import com.antigravity.monarch.domain.model.AchievementDef
import com.antigravity.monarch.domain.model.StatType
import com.antigravity.monarch.domain.model.WorkoutEntry
import com.antigravity.monarch.domain.model.XpEvent
import com.antigravity.monarch.domain.model.XpKind
import java.time.LocalDate

data class WeeklyRecap(
    val start: LocalDate,
    val end: LocalDate,
    val xpByDay: List<Pair<LocalDate, Int>>,
    val xpByStat: Map<StatType, Int>,
    val totalXp: Int,
    val workouts: Int,
    val workoutMinutes: Int,
    val levelStart: Int,
    val levelEnd: Int,
    val activeDays: Int,
    val bestDay: LocalDate?,
    val achievements: List<AchievementDef>,
    val headline: String,
) {
    val levelsGained: Int get() = levelEnd - levelStart
    val topStat: StatType? get() = xpByStat.maxByOrNull { it.value }?.takeIf { it.value > 0 }?.key
}

/** Builds the 7-day recap (today and the six days before). Pure. */
object RecapBuilder {
    fun weekly(
        today: LocalDate,
        events: List<XpEvent>,
        workouts: List<WorkoutEntry>,
        xpBeforeStart: Long,
        unlockedAt: Map<AchievementDef, LocalDate>,
    ): WeeklyRecap {
        val start = today.minusDays(6)
        val inRange = events.filter { it.date in start..today }
        val days = (0..6).map { start.plusDays(it.toLong()) }
        val byDay = days.map { d -> d to inRange.filter { it.date == d }.sumOf { it.amount } }
        val activityByDay = days.associateWith { d -> inRange.filter { it.date == d && it.kind == XpKind.ACTIVITY }.sumOf { it.amount } }
        val byStat = StatType.entries.associateWith { s -> inRange.filter { it.stat == s }.sumOf { it.amount } }
        val total = inRange.sumOf { it.amount }
        val weekWorkouts = workouts.filter { it.date in start..today }
        val levelStart = LevelCurve.levelFor(xpBeforeStart)
        val levelEnd = LevelCurve.levelFor(xpBeforeStart + total)
        val activeDays = activityByDay.count { it.value >= XpRules.ACTIVE_DAY_XP }
        val best = byDay.filter { it.second > 0 }.maxByOrNull { it.second }?.first
        val achievements = unlockedAt.filterValues { it in start..today }.keys.sortedBy { it.ordinal }

        val headline = when {
            total == 0 -> "A quiet week. Rest is part of the climb. One small quest today restarts the engine."
            levelEnd > levelStart -> "You gained ${levelEnd - levelStart} level${if (levelEnd - levelStart > 1) "s" else ""} this week. The System has noticed."
            activeDays >= 5 -> "$activeDays active days. That consistency is the real stat."
            else -> "$activeDays active day${if (activeDays == 1) "" else "s"} and $total XP. Steady progress counts."
        }
        return WeeklyRecap(
            start = start, end = today, xpByDay = byDay, xpByStat = byStat, totalXp = total,
            workouts = weekWorkouts.size, workoutMinutes = weekWorkouts.sumOf { it.durationMinutes },
            levelStart = levelStart, levelEnd = levelEnd, activeDays = activeDays, bestDay = best,
            achievements = achievements, headline = headline,
        )
    }
}
