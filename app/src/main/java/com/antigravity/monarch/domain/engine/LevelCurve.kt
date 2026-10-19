package com.antigravity.monarch.domain.engine

import kotlin.math.pow
import kotlin.math.roundToLong

/**
 * Player level curve, levels 1..100.
 *
 * Cumulative XP needed to *reach* level L is `round(BASE * (L - 1)^EXPONENT)`:
 *  - Level 2 needs 50 XP (first session), level 10 ~2.6k (about two weeks of steady play),
 *  - level 50 ~55k (~10 months), level 100 ~195k (multi-year goal).
 *
 * Each level costs strictly more than the previous one, so early levels feel fast and later
 * ones stay meaningful without becoming unreachable.
 */
object LevelCurve {
    const val MIN_LEVEL = 1
    const val MAX_LEVEL = 100
    private const val BASE = 50.0
    private const val EXPONENT = 1.8

    private val thresholds: LongArray = LongArray(MAX_LEVEL + 1) { level ->
        if (level <= 1) 0L else (BASE * (level - 1).toDouble().pow(EXPONENT)).roundToLong()
    }

    /** Total XP required to reach [level] (level 1 = 0). */
    fun totalXpForLevel(level: Int): Long = thresholds[level.coerceIn(MIN_LEVEL, MAX_LEVEL)]

    /** XP needed to go from [level] to level + 1; 0 at the cap. */
    fun xpToAdvanceFrom(level: Int): Long {
        val l = level.coerceIn(MIN_LEVEL, MAX_LEVEL)
        return if (l == MAX_LEVEL) 0L else thresholds[l + 1] - thresholds[l]
    }

    fun levelFor(totalXp: Long): Int {
        if (totalXp <= 0) return MIN_LEVEL
        // Largest level whose threshold is <= totalXp (binary search over 100 entries).
        var lo = MIN_LEVEL
        var hi = MAX_LEVEL
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (thresholds[mid] <= totalXp) lo = mid else hi = mid - 1
        }
        return lo
    }

    data class Progress(val level: Int, val xpIntoLevel: Long, val xpForLevel: Long) {
        val fraction: Float get() = if (xpForLevel == 0L) 1f else (xpIntoLevel.toFloat() / xpForLevel).coerceIn(0f, 1f)
        val xpRemaining: Long get() = (xpForLevel - xpIntoLevel).coerceAtLeast(0)
    }

    fun progressFor(totalXp: Long): Progress {
        val xp = totalXp.coerceAtLeast(0)
        val level = levelFor(xp)
        if (level == MAX_LEVEL) return Progress(level, 0, 0)
        return Progress(level, xp - thresholds[level], xpToAdvanceFrom(level))
    }
}
