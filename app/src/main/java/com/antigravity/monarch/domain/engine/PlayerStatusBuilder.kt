package com.antigravity.monarch.domain.engine

import com.antigravity.monarch.domain.model.PlayerProfile
import com.antigravity.monarch.domain.model.PlayerStatus
import com.antigravity.monarch.domain.model.Rank
import com.antigravity.monarch.domain.model.StatSnapshot
import com.antigravity.monarch.domain.model.StatType
import java.time.LocalDate

/** Assembles the stat-window read model from raw ledger aggregates. Pure. */
object PlayerStatusBuilder {
    fun build(
        profile: PlayerProfile,
        totalXp: Long,
        statXp: Map<StatType, Long>,
        todayStatXp: Map<StatType, Int>,
        activityXpByDay: Map<LocalDate, Int>,
        today: LocalDate,
        todayBonusXp: Int = 0,
    ): PlayerStatus {
        val progress = LevelCurve.progressFor(totalXp)
        val stats = StatType.entries.map { type ->
            val xp = statXp[type] ?: 0L
            StatSnapshot(
                type = type,
                xp = xp,
                value = StatScaling.valueFor(xp),
                todayXp = todayStatXp[type] ?: 0,
                dailyCap = XpRules.DAILY_STAT_CAP,
                progressToNext = StatScaling.progressToNext(xp),
            )
        }
        val streak = StreakCalculator.compute(
            activeDays = activeDays(activityXpByDay),
            restDays = profile.restDays,
            today = today,
            startDate = profile.createdOn,
        )
        return PlayerStatus(
            name = profile.name,
            level = progress.level,
            rank = Rank.forLevel(progress.level),
            playerClass = profile.playerClass,
            totalXp = totalXp,
            xpIntoLevel = progress.xpIntoLevel,
            xpForLevel = progress.xpForLevel,
            levelProgress = progress.fraction,
            stats = stats,
            streak = streak,
            todayXp = todayStatXp.values.sum() + todayBonusXp,
        )
    }

    fun activeDays(activityXpByDay: Map<LocalDate, Int>): Set<LocalDate> =
        activityXpByDay.filterValues { it >= XpRules.ACTIVE_DAY_XP }.keys
}
