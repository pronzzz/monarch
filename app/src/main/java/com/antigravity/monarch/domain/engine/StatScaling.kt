package com.antigravity.monarch.domain.engine

import kotlin.math.floor
import kotlin.math.sqrt

/**
 * Converts accumulated stat XP into the visible stat value.
 *
 * `value = 10 + floor(sqrt(xp / 10))` — square-root growth means each point costs more than the
 * last (point n costs ~20n XP), so a burst of early activity cannot inflate stats. A year of
 * steady, capped play lands around 40–80 in a focus stat.
 */
object StatScaling {
    const val BASE_VALUE = 10
    const val MAX_VALUE = 999
    private const val DIVISOR = 10.0

    fun valueFor(xp: Long): Int {
        if (xp <= 0) return BASE_VALUE
        return (BASE_VALUE + floor(sqrt(xp / DIVISOR)).toInt()).coerceAtMost(MAX_VALUE)
    }

    /** Minimum XP needed for the stat to show [value]. */
    fun xpForValue(value: Int): Long {
        val bonus = (value - BASE_VALUE).coerceAtLeast(0).toLong()
        return (bonus * bonus * DIVISOR).toLong()
    }

    fun progressToNext(xp: Long): Float {
        val value = valueFor(xp)
        if (value >= MAX_VALUE) return 1f
        val floorXp = xpForValue(value)
        val nextXp = xpForValue(value + 1)
        return ((xp - floorXp).toFloat() / (nextXp - floorXp)).coerceIn(0f, 1f)
    }
}
