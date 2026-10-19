package com.antigravity.monarch.domain.engine

import com.antigravity.monarch.domain.model.WorkoutEntry
import com.antigravity.monarch.domain.repository.ExternalWorkout

/**
 * Decides whether an incoming synced workout is one the player already has.
 *
 * The same session often reaches Monarch twice, e.g. a run recorded on a Zepp watch shows up
 * through Health Connect *and* through Strava. Matching is by source id first, then by time:
 * if the new session overlaps an already-synced session by at least [MIN_OVERLAP] of the
 * shorter one, it is the same workout and the first copy wins. Manual logs are never used for
 * matching because their timestamps are when they were logged, not when they happened.
 */
object WorkoutDeduplicator {
    const val MIN_OVERLAP = 0.5

    fun isDuplicate(candidate: ExternalWorkout, existing: List<WorkoutEntry>): Boolean =
        existing.any { it.externalId == candidate.externalId } ||
            existing.any { it.source.isSynced && overlapRatio(candidate.startEpochMs, candidate.endEpochMs, it.startedAtEpochMs, it.endEpochMs) >= MIN_OVERLAP }

    /** Overlap as a fraction of the shorter interval; 0 when they don't overlap or one is empty. */
    fun overlapRatio(aStart: Long, aEnd: Long, bStart: Long, bEnd: Long): Double {
        val shorter = minOf(aEnd - aStart, bEnd - bStart)
        if (shorter <= 0) return 0.0
        val overlap = minOf(aEnd, bEnd) - maxOf(aStart, bStart)
        return if (overlap <= 0) 0.0 else overlap.toDouble() / shorter
    }

    /** Removes duplicates inside one incoming batch too (two sources in the same sync). */
    fun dedupeBatch(incoming: List<ExternalWorkout>): List<ExternalWorkout> {
        val kept = mutableListOf<ExternalWorkout>()
        for (w in incoming.sortedBy { it.startEpochMs }) {
            val clash = kept.any { it.externalId == w.externalId || overlapRatio(w.startEpochMs, w.endEpochMs, it.startEpochMs, it.endEpochMs) >= MIN_OVERLAP }
            if (!clash) kept += w
        }
        return kept
    }
}
