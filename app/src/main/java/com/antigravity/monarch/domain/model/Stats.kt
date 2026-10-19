package com.antigravity.monarch.domain.model

/**
 * The five core attributes from the PRD. Every XP-earning action feeds one or more of these.
 */
enum class StatType(
    val displayName: String,
    val shortName: String,
    val description: String,
) {
    STRENGTH("Strength", "STR", "Strength training and lifting"),
    AGILITY("Agility", "AGI", "Running, cycling and HIIT"),
    ENDURANCE("Endurance", "END", "Steps, long sessions and cardio duration"),
    HEALTH("Health", "HLT", "Nutrition, sleep and hydration"),
    INTELLIGENCE("Intelligence", "INT", "Reading, learning and discipline (mana)"),
}

/**
 * Classes unlock at level milestones. A class only nudges XP gain toward its focus stats; it
 * never bypasses the daily caps, so it cannot be used to farm.
 */
enum class PlayerClass(
    val displayName: String,
    val tagline: String,
    val unlockLevel: Int,
    val primaryStat: StatType,
    val secondaryStat: StatType,
) {
    WARRIOR("Warrior", "Iron discipline. Lifts that move mountains.", 10, StatType.STRENGTH, StatType.ENDURANCE),
    RUNNER("Runner", "Speed and stamina, one stride at a time.", 10, StatType.AGILITY, StatType.ENDURANCE),
    SCHOLAR("Scholar", "Knowledge is mana. Mana is power.", 10, StatType.INTELLIGENCE, StatType.HEALTH),
    MONK("Monk", "Balance of body and mind.", 20, StatType.HEALTH, StatType.INTELLIGENCE),
    ;

    fun xpMultiplier(stat: StatType): Double = when (stat) {
        primaryStat -> PRIMARY_BONUS
        secondaryStat -> SECONDARY_BONUS
        else -> 1.0
    }

    fun isUnlockedAt(level: Int): Boolean = level >= unlockLevel

    companion object {
        const val PRIMARY_BONUS = 1.10
        const val SECONDARY_BONUS = 1.05

        /** Days a player must wait before switching class again. */
        const val CHANGE_COOLDOWN_DAYS = 7L
    }
}

/** Hunter rank shown next to the level, purely cosmetic. */
enum class Rank(val minLevel: Int) {
    E(1), D(10), C(25), B(50), A(75), S(100);

    companion object {
        fun forLevel(level: Int): Rank = entries.last { level >= it.minLevel }
    }
}

enum class Intensity(val displayName: String, val multiplier: Double) {
    LIGHT("Light", 0.8),
    MODERATE("Moderate", 1.0),
    INTENSE("Intense", 1.25),
}

/**
 * Workout categories. Each distributes its XP across stats; weights sum to 1.0.
 */
enum class WorkoutType(val displayName: String, val statWeights: Map<StatType, Double>) {
    STRENGTH("Strength training", mapOf(StatType.STRENGTH to 0.75, StatType.ENDURANCE to 0.15, StatType.HEALTH to 0.10)),
    RUNNING("Running", mapOf(StatType.AGILITY to 0.55, StatType.ENDURANCE to 0.40, StatType.HEALTH to 0.05)),
    CYCLING("Cycling", mapOf(StatType.AGILITY to 0.45, StatType.ENDURANCE to 0.50, StatType.HEALTH to 0.05)),
    HIIT("HIIT", mapOf(StatType.AGILITY to 0.55, StatType.STRENGTH to 0.25, StatType.ENDURANCE to 0.20)),
    SWIMMING("Swimming", mapOf(StatType.ENDURANCE to 0.45, StatType.AGILITY to 0.35, StatType.STRENGTH to 0.20)),
    WALKING("Walking / hiking", mapOf(StatType.ENDURANCE to 0.80, StatType.HEALTH to 0.20)),
    YOGA("Yoga / mobility", mapOf(StatType.HEALTH to 0.50, StatType.AGILITY to 0.25, StatType.INTELLIGENCE to 0.25)),
    SPORTS("Sports", mapOf(StatType.AGILITY to 0.50, StatType.ENDURANCE to 0.30, StatType.STRENGTH to 0.20)),
    OTHER("Other", mapOf(StatType.ENDURANCE to 0.60, StatType.HEALTH to 0.40)),
}

/** Non-workout habits that still feed the stat window. */
enum class HabitType(val displayName: String, val unit: String, val stat: StatType) {
    MEAL("Balanced meal", "meals", StatType.HEALTH),
    HYDRATION("Water", "glasses", StatType.HEALTH),
    SLEEP("Sleep", "minutes", StatType.HEALTH),
    READING("Reading", "minutes", StatType.INTELLIGENCE),
    LEARNING("Learning session", "minutes", StatType.INTELLIGENCE),
    MEDITATION("Meditation", "minutes", StatType.INTELLIGENCE),
}
