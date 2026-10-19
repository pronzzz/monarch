package com.antigravity.monarch.domain.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LevelCurveTest {

    @Test
    fun `level 1 starts at zero and level 2 needs 50 xp`() {
        assertEquals(0L, LevelCurve.totalXpForLevel(1))
        assertEquals(50L, LevelCurve.totalXpForLevel(2))
        assertEquals(1, LevelCurve.levelFor(0))
        assertEquals(1, LevelCurve.levelFor(49))
        assertEquals(2, LevelCurve.levelFor(50))
    }

    @Test
    fun `each level costs strictly more than the previous one`() {
        for (level in 1 until LevelCurve.MAX_LEVEL - 1) {
            assertTrue(
                "level $level -> ${level + 1} should be cheaper than ${level + 1} -> ${level + 2}",
                LevelCurve.xpToAdvanceFrom(level) < LevelCurve.xpToAdvanceFrom(level + 1),
            )
        }
    }

    @Test
    fun `levelFor is exact at every threshold boundary`() {
        for (level in 2..LevelCurve.MAX_LEVEL) {
            val threshold = LevelCurve.totalXpForLevel(level)
            assertEquals(level, LevelCurve.levelFor(threshold))
            assertEquals(level - 1, LevelCurve.levelFor(threshold - 1))
        }
    }

    @Test
    fun `curve is calibrated for multi-month progression`() {
        // ~2 weeks at ~200 XP/day for level 10, years for 100.
        assertTrue(LevelCurve.totalXpForLevel(10) in 2_000L..3_500L)
        assertTrue(LevelCurve.totalXpForLevel(50) in 45_000L..70_000L)
        assertTrue(LevelCurve.totalXpForLevel(100) in 150_000L..250_000L)
    }

    @Test
    fun `level is capped at 100 and progress is full there`() {
        val huge = LevelCurve.totalXpForLevel(100) * 10
        assertEquals(100, LevelCurve.levelFor(huge))
        val progress = LevelCurve.progressFor(huge)
        assertEquals(1f, progress.fraction)
        assertEquals(0L, progress.xpForLevel)
        assertEquals(0L, LevelCurve.xpToAdvanceFrom(100))
    }

    @Test
    fun `progress within a level is consistent`() {
        val xp = LevelCurve.totalXpForLevel(7) + 25
        val p = LevelCurve.progressFor(xp)
        assertEquals(7, p.level)
        assertEquals(25L, p.xpIntoLevel)
        assertEquals(LevelCurve.xpToAdvanceFrom(7), p.xpForLevel)
        assertEquals(p.xpForLevel - 25, p.xpRemaining)
        assertTrue(p.fraction > 0f && p.fraction < 1f)
    }

    @Test
    fun `negative xp is treated as zero`() {
        assertEquals(1, LevelCurve.levelFor(-500))
        assertEquals(0L, LevelCurve.progressFor(-500).xpIntoLevel)
    }
}

class StatScalingTest {

    @Test
    fun `stats start at base value`() {
        assertEquals(10, StatScaling.valueFor(0))
        assertEquals(10, StatScaling.valueFor(-10))
        assertEquals(10, StatScaling.valueFor(9))
        assertEquals(11, StatScaling.valueFor(10))
    }

    @Test
    fun `xpForValue is the exact inverse of valueFor`() {
        for (value in 10..300) {
            val xp = StatScaling.xpForValue(value)
            assertEquals(value, StatScaling.valueFor(xp))
            if (value > 10) assertEquals(value - 1, StatScaling.valueFor(xp - 1))
        }
    }

    @Test
    fun `growth is non-linear so early bursts cannot inflate stats`() {
        val costs = (11..60).map { StatScaling.xpForValue(it) - StatScaling.xpForValue(it - 1) }
        costs.zipWithNext().forEach { (a, b) -> assertTrue(b > a) }
        // A maxed-out day (150 XP) from zero only moves a stat by a few points.
        assertTrue(StatScaling.valueFor(150) <= 13)
    }

    @Test
    fun `progress to next point stays within bounds`() {
        for (xp in listOf(0L, 5L, 10L, 39L, 40L, 1_000L, 55_555L)) {
            val p = StatScaling.progressToNext(xp)
            assertTrue("xp=$xp p=$p", p in 0f..1f)
        }
        assertEquals(0f, StatScaling.progressToNext(StatScaling.xpForValue(20)))
    }
}
