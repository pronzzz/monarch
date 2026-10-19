package com.antigravity.monarch.domain.engine

import com.antigravity.monarch.domain.model.HabitType
import com.antigravity.monarch.domain.model.Intensity
import com.antigravity.monarch.domain.model.PlayerClass
import com.antigravity.monarch.domain.model.StatType
import com.antigravity.monarch.domain.model.WorkoutType
import kotlin.math.exp
import kotlin.math.roundToInt

/**
 * Raw XP rules: real-world activity -> XP per stat, before class bonuses and daily caps.
 *
 * Anti-farming is layered:
 *  1. per-session diminishing returns (minutes past 60 count half, past 120 count zero),
 *  2. per-habit daily limits (e.g. only 4 meals/day earn XP),
 *  3. a hard per-stat daily cap applied by [XpAllocator].
 */
object XpRules {
    /** Hard cap on XP any single stat can earn in one day. */
    const val DAILY_STAT_CAP = 150

    /** A day counts toward the streak once this much activity XP is earned. */
    const val ACTIVE_DAY_XP = 20

    const val MIN_SESSION_MINUTES = 5
    const val MAX_SESSION_MINUTES = 300
    private const val XP_PER_WORKOUT_MINUTE = 1.0

    private const val STEP_XP_MAX = 60.0
    private const val STEP_XP_SCALE = 10_000.0

    private const val XP_PER_MEAL = 8
    const val MAX_MEALS_PER_DAY = 4
    private const val XP_PER_GLASS = 2
    const val MAX_GLASSES_PER_DAY = 10
    private const val XP_PER_MIND_MINUTE = 0.8
    const val MAX_MIND_MINUTES_PER_DAY = 120

    /**
     * Minutes that actually earn XP for one session: full value to 60 min, half value from
     * 60–120 min, nothing beyond. Sessions under 5 minutes earn nothing.
     */
    fun effectiveMinutes(minutes: Int): Double {
        val m = minutes.coerceIn(0, MAX_SESSION_MINUTES)
        if (m < MIN_SESSION_MINUTES) return 0.0
        val full = minOf(m, 60).toDouble()
        val half = (minOf(m, 120) - 60).coerceAtLeast(0) * 0.5
        return full + half
    }

    fun workoutXp(type: WorkoutType, minutes: Int, intensity: Intensity): Map<StatType, Int> {
        val total = effectiveMinutes(minutes) * XP_PER_WORKOUT_MINUTE * intensity.multiplier
        return distribute(total, type.statWeights)
    }

    /** Smooth saturating curve: ~38 XP at 10k steps, ~52 at 20k, never above 60. */
    fun stepsXp(steps: Long): Map<StatType, Int> {
        if (steps <= 0) return emptyMap()
        val xp = (STEP_XP_MAX * (1 - exp(-steps / STEP_XP_SCALE))).roundToInt()
        return if (xp > 0) mapOf(StatType.ENDURANCE to xp) else emptyMap()
    }

    /** Rewards a healthy sleep window rather than "more is better". */
    fun sleepXp(minutes: Int): Map<StatType, Int> {
        val hours = minutes / 60.0
        val xp = when {
            hours >= 7.0 && hours <= 9.0 -> 30
            hours >= 6.0 && hours <= 10.0 -> 20
            hours >= 5.0 && hours < 6.0 -> 10
            else -> 0
        }
        return if (xp > 0) mapOf(StatType.HEALTH to xp) else emptyMap()
    }

    /**
     * XP for logging [quantity] of a habit when [alreadyToday] has been logged earlier the same
     * day. Only the portion inside the habit's daily limit earns XP.
     */
    fun habitXp(type: HabitType, quantity: Int, alreadyToday: Int): Map<StatType, Int> {
        val q = quantity.coerceAtLeast(0)
        val before = alreadyToday.coerceAtLeast(0)
        val xp = when (type) {
            HabitType.MEAL -> countable(q, before, MAX_MEALS_PER_DAY) * XP_PER_MEAL
            HabitType.HYDRATION -> countable(q, before, MAX_GLASSES_PER_DAY) * XP_PER_GLASS
            HabitType.READING, HabitType.LEARNING, HabitType.MEDITATION -> {
                if (q < MIN_SESSION_MINUTES) 0 else (countable(q, before, MAX_MIND_MINUTES_PER_DAY) * XP_PER_MIND_MINUTE).roundToInt()
            }
            HabitType.SLEEP -> return sleepXp(q)
        }
        if (xp <= 0) return emptyMap()
        return if (type == HabitType.MEDITATION) {
            distribute(xp.toDouble(), mapOf(StatType.INTELLIGENCE to 0.6, StatType.HEALTH to 0.4))
        } else {
            mapOf(type.stat to xp)
        }
    }

    private fun countable(quantity: Int, alreadyToday: Int, limit: Int): Int =
        (minOf(alreadyToday + quantity, limit) - alreadyToday).coerceAtLeast(0)

    /** Splits [total] by weight, rounding so that the parts add up to round(total). */
    fun distribute(total: Double, weights: Map<StatType, Double>): Map<StatType, Int> {
        val target = total.roundToInt()
        if (target <= 0 || weights.isEmpty()) return emptyMap()
        val exact = weights.mapValues { (_, w) -> total * w }
        val floors = exact.mapValues { it.value.toInt() }.toMutableMap()
        var remainder = target - floors.values.sum()
        // Largest-remainder rounding, ties broken by stat order for determinism.
        val order = exact.entries.sortedWith(compareByDescending<Map.Entry<StatType, Double>> { it.value - it.value.toInt() }.thenBy { it.key.ordinal })
        for (entry in order) {
            if (remainder <= 0) break
            floors[entry.key] = floors.getValue(entry.key) + 1
            remainder--
        }
        return floors.filterValues { it > 0 }
    }
}

/** Applies class bonuses and the per-stat daily cap. Pure and deterministic. */
object XpAllocator {
    data class Allocation(val awarded: Map<StatType, Int>, val capped: Int)

    fun allocate(
        raw: Map<StatType, Int>,
        alreadyToday: Map<StatType, Int>,
        playerClass: PlayerClass?,
        dailyCap: Int = XpRules.DAILY_STAT_CAP,
    ): Allocation {
        var capped = 0
        val awarded = buildMap {
            for ((stat, amount) in raw) {
                if (amount <= 0) continue
                val boosted = (amount * (playerClass?.xpMultiplier(stat) ?: 1.0)).roundToInt()
                val room = (dailyCap - (alreadyToday[stat] ?: 0)).coerceAtLeast(0)
                val granted = minOf(boosted, room)
                capped += boosted - granted
                if (granted > 0) put(stat, granted)
            }
        }
        return Allocation(awarded, capped)
    }
}
