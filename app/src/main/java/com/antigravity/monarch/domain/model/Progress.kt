package com.antigravity.monarch.domain.model

import java.time.LocalDate

/** Where a piece of XP came from. BONUS XP counts toward level but not toward any stat. */
enum class XpKind { ACTIVITY, QUEST, ACHIEVEMENT }

/**
 * One row in the XP ledger, the single source of truth for progression. Levels and stats are
 * always derived from the ledger, never stored, so they cannot drift.
 *
 * @property sourceKey idempotency key, e.g. `workout:42`, `steps:2026-10-20`, `quest:2026-10-20:move`.
 * @property stat null for bonus XP (quests, achievements).
 */
data class XpEvent(
    val sourceKey: String,
    val stat: StatType?,
    val amount: Int,
    val date: LocalDate,
    val kind: XpKind,
    val label: String,
    val createdAtEpochMs: Long,
)

enum class ActivitySource(val displayName: String) {
    MANUAL("Manual"),
    HEALTH_CONNECT("Health Connect"),
    STRAVA("Strava"),
    ;

    val isSynced: Boolean get() = this != MANUAL
}

/** A logged workout (manual or synced). */
data class WorkoutEntry(
    val id: Long = 0,
    val type: WorkoutType,
    val title: String,
    val durationMinutes: Int,
    val intensity: Intensity,
    val date: LocalDate,
    val startedAtEpochMs: Long,
    val source: ActivitySource = ActivitySource.MANUAL,
    val externalId: String? = null,
    val notes: String = "",
    val xpAwarded: Int = 0,
    /** App that recorded a synced session (e.g. "Samsung Health", "Zepp"); null for manual logs. */
    val originApp: String? = null,
) {
    val endEpochMs: Long get() = startedAtEpochMs + durationMinutes * 60_000L
}

/** A logged habit (meal, water, reading...). Quantity unit depends on [HabitType]. */
data class HabitEntry(
    val id: Long = 0,
    val type: HabitType,
    val quantity: Int,
    val date: LocalDate,
    val loggedAtEpochMs: Long,
    val xpAwarded: Int = 0,
)

/** Synced daily aggregates from Health Connect. */
data class DailyHealthMetrics(
    val date: LocalDate,
    val steps: Long = 0,
    val sleepMinutes: Int = 0,
    val activeCalories: Int = 0,
)

/** Everything that happened on one day, used by quests and summaries. */
data class DayTotals(
    val date: LocalDate,
    val steps: Long = 0,
    val sleepMinutes: Int = 0,
    val workoutCount: Int = 0,
    val workoutMinutes: Int = 0,
    val meals: Int = 0,
    val waterGlasses: Int = 0,
    val mindMinutes: Int = 0,
    val activeCalories: Int = 0,
)

data class StatSnapshot(
    val type: StatType,
    val xp: Long,
    val value: Int,
    val todayXp: Int,
    val dailyCap: Int,
    /** 0..1 progress toward the next visible stat point. */
    val progressToNext: Float,
) {
    val capReached: Boolean get() = todayXp >= dailyCap
}

data class StreakState(
    val current: Int = 0,
    val longest: Int = 0,
    val shields: Int = 0,
    val shieldsUsed: Int = 0,
    /** True when today hasn't been earned yet but the streak is still alive. */
    val todayPending: Boolean = false,
    val todayIsRestDay: Boolean = false,
)

/** The read model behind the stat window and the widget. */
data class PlayerStatus(
    val name: String,
    val level: Int,
    val rank: Rank,
    val playerClass: PlayerClass?,
    val totalXp: Long,
    val xpIntoLevel: Long,
    val xpForLevel: Long,
    val levelProgress: Float,
    val stats: List<StatSnapshot>,
    val streak: StreakState,
    val todayXp: Int,
) {
    val isMaxLevel: Boolean get() = xpForLevel == 0L
    fun topStats(n: Int = 3): List<StatSnapshot> = stats.sortedWith(compareByDescending<StatSnapshot> { it.value }.thenByDescending { it.xp }).take(n)
}

data class LevelUp(val fromLevel: Int, val toLevel: Int, val newlyUnlockedClasses: List<PlayerClass>)

/** What a single action produced; drives toasts and the level-up animation. */
data class AwardResult(
    val xpByStat: Map<StatType, Int> = emptyMap(),
    val cappedXp: Int = 0,
    val bonusXp: Int = 0,
    val levelUp: LevelUp? = null,
    val achievements: List<AchievementDef> = emptyList(),
    val questsCompleted: List<Quest> = emptyList(),
) {
    val statXp: Int get() = xpByStat.values.sum()
    val totalXp: Int get() = statXp + bonusXp

    operator fun plus(other: AwardResult): AwardResult = AwardResult(
        xpByStat = (xpByStat.keys + other.xpByStat.keys).associateWith { (xpByStat[it] ?: 0) + (other.xpByStat[it] ?: 0) },
        cappedXp = cappedXp + other.cappedXp,
        bonusXp = bonusXp + other.bonusXp,
        levelUp = mergeLevelUps(levelUp, other.levelUp),
        achievements = achievements + other.achievements,
        questsCompleted = questsCompleted + other.questsCompleted,
    )

    private fun mergeLevelUps(a: LevelUp?, b: LevelUp?): LevelUp? = when {
        a == null -> b
        b == null -> a
        else -> LevelUp(minOf(a.fromLevel, b.fromLevel), maxOf(a.toLevel, b.toLevel), (a.newlyUnlockedClasses + b.newlyUnlockedClasses).distinct())
    }
}
