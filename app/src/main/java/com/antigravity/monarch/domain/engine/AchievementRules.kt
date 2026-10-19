package com.antigravity.monarch.domain.engine

import com.antigravity.monarch.domain.model.AchievementDef
import com.antigravity.monarch.domain.model.AchievementDef.BALANCED
import com.antigravity.monarch.domain.model.AchievementDef.BOOKWORM
import com.antigravity.monarch.domain.model.AchievementDef.FIRST_SYNC
import com.antigravity.monarch.domain.model.AchievementDef.FIRST_WORKOUT
import com.antigravity.monarch.domain.model.AchievementDef.LEVEL_10
import com.antigravity.monarch.domain.model.AchievementDef.LEVEL_100
import com.antigravity.monarch.domain.model.AchievementDef.LEVEL_25
import com.antigravity.monarch.domain.model.AchievementDef.LEVEL_5
import com.antigravity.monarch.domain.model.AchievementDef.LEVEL_50
import com.antigravity.monarch.domain.model.AchievementDef.PERFECT_DAY
import com.antigravity.monarch.domain.model.AchievementDef.STAT_25
import com.antigravity.monarch.domain.model.AchievementDef.STAT_50
import com.antigravity.monarch.domain.model.AchievementDef.STEPS_10K
import com.antigravity.monarch.domain.model.AchievementDef.STREAK_30
import com.antigravity.monarch.domain.model.AchievementDef.STREAK_3
import com.antigravity.monarch.domain.model.AchievementDef.STREAK_7
import com.antigravity.monarch.domain.model.AchievementDef.WORKOUTS_10
import com.antigravity.monarch.domain.model.AchievementDef.WORKOUTS_50
import com.antigravity.monarch.domain.model.AchievementSnapshot
import com.antigravity.monarch.domain.model.StatType

object AchievementRules {
    fun earned(s: AchievementSnapshot): Set<AchievementDef> = AchievementDef.entries.filterTo(mutableSetOf()) { isEarned(it, s) }

    fun newlyEarned(s: AchievementSnapshot, alreadyUnlocked: Set<AchievementDef>): List<AchievementDef> =
        AchievementDef.entries.filter { it !in alreadyUnlocked && isEarned(it, s) }

    private fun maxStat(s: AchievementSnapshot) = s.statValues.values.maxOrNull() ?: StatScaling.BASE_VALUE

    private fun isEarned(def: AchievementDef, s: AchievementSnapshot): Boolean = when (def) {
        FIRST_WORKOUT -> s.totalWorkouts >= 1
        FIRST_SYNC -> s.hasSynced
        STREAK_3 -> s.longestStreak >= 3
        STREAK_7 -> s.longestStreak >= 7
        STREAK_30 -> s.longestStreak >= 30
        LEVEL_5 -> s.level >= 5
        LEVEL_10 -> s.level >= 10
        LEVEL_25 -> s.level >= 25
        LEVEL_50 -> s.level >= 50
        LEVEL_100 -> s.level >= 100
        STAT_25 -> maxStat(s) >= 25
        STAT_50 -> maxStat(s) >= 50
        BALANCED -> StatType.entries.all { (s.statValues[it] ?: StatScaling.BASE_VALUE) >= 15 }
        WORKOUTS_10 -> s.totalWorkouts >= 10
        WORKOUTS_50 -> s.totalWorkouts >= 50
        STEPS_10K -> s.maxDailySteps >= 10_000
        BOOKWORM -> s.totalMindMinutes >= 300
        PERFECT_DAY -> s.perfectQuestDays >= 1
    }
}
