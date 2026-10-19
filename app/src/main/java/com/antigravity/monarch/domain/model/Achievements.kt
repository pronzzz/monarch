package com.antigravity.monarch.domain.model

enum class AchievementCategory { FIRSTS, STREAK, LEVEL, STAT, VOLUME }

/** Static achievement catalogue. Unlocks are stored by [name]; never rename an entry. */
enum class AchievementDef(
    val title: String,
    val description: String,
    val category: AchievementCategory,
    val xpReward: Int,
) {
    FIRST_WORKOUT("Awakening", "Complete your first workout", AchievementCategory.FIRSTS, 50),
    FIRST_SYNC("Linked", "Sync a health source for the first time", AchievementCategory.FIRSTS, 25),
    STREAK_3("Consistent", "Reach a 3-day streak", AchievementCategory.STREAK, 30),
    STREAK_7("Dedicated", "Reach a 7-day streak", AchievementCategory.STREAK, 100),
    STREAK_30("Unbreakable", "Reach a 30-day streak", AchievementCategory.STREAK, 300),
    LEVEL_5("Initiate", "Reach level 5", AchievementCategory.LEVEL, 50),
    LEVEL_10("Class Awakening", "Reach level 10 and unlock classes", AchievementCategory.LEVEL, 100),
    LEVEL_25("C-Rank Hunter", "Reach level 25", AchievementCategory.LEVEL, 250),
    LEVEL_50("B-Rank Hunter", "Reach level 50", AchievementCategory.LEVEL, 500),
    LEVEL_100("Monarch", "Reach the level cap of 100", AchievementCategory.LEVEL, 1000),
    STAT_25("Breakthrough", "Raise any stat to 25", AchievementCategory.STAT, 75),
    STAT_50("Peak Form", "Raise any stat to 50", AchievementCategory.STAT, 250),
    BALANCED("Balanced Build", "Raise every stat to 15", AchievementCategory.STAT, 100),
    WORKOUTS_10("Regular", "Log 10 workouts", AchievementCategory.VOLUME, 75),
    WORKOUTS_50("Veteran", "Log 50 workouts", AchievementCategory.VOLUME, 250),
    STEPS_10K("Long March", "Walk 10,000 steps in one day", AchievementCategory.VOLUME, 40),
    BOOKWORM("Bookworm", "Spend 300 minutes reading or learning", AchievementCategory.VOLUME, 75),
    PERFECT_DAY("Perfect Day", "Complete every daily quest in one day", AchievementCategory.VOLUME, 50),
}

/** Inputs the achievement rules look at. */
data class AchievementSnapshot(
    val level: Int,
    val statValues: Map<StatType, Int>,
    val longestStreak: Int,
    val totalWorkouts: Int,
    val maxDailySteps: Long,
    val totalMindMinutes: Int,
    val hasSynced: Boolean,
    val perfectQuestDays: Int,
)

enum class QuestKind { MOVEMENT, TRAINING, RECOVERY, NUTRITION, HYDRATION, MIND }

/** A daily quest. Quests are regenerated deterministically each day; completion is idempotent. */
data class Quest(
    val id: String,
    val kind: QuestKind,
    val title: String,
    val description: String,
    val target: Int,
    val progress: Int,
    val unit: String,
    val xpReward: Int,
) {
    val completed: Boolean get() = progress >= target
    val fraction: Float get() = if (target <= 0) 1f else (progress.toFloat() / target).coerceIn(0f, 1f)
}
