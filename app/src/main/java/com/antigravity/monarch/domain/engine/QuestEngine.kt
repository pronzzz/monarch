package com.antigravity.monarch.domain.engine

import com.antigravity.monarch.domain.model.DayTotals
import com.antigravity.monarch.domain.model.Intensity
import com.antigravity.monarch.domain.model.PlayerProfile
import com.antigravity.monarch.domain.model.Quest
import com.antigravity.monarch.domain.model.QuestKind
import com.antigravity.monarch.domain.model.WorkoutPlan
import java.time.LocalDate
import kotlin.math.roundToInt

/**
 * Builds today's quests from the profile, plan and level, then fills in progress from what the
 * player actually did. Deterministic: the same inputs always yield the same quests, so
 * completion can be keyed by `quest:<date>:<id>` without storing quests.
 */
object QuestEngine {
    const val PERFECT_DAY_BONUS = 25

    fun questsFor(
        date: LocalDate,
        profile: PlayerProfile,
        plan: WorkoutPlan?,
        level: Int,
        totals: DayTotals,
    ): List<Quest> {
        val quests = mutableListOf<Quest>()

        val baseSteps = when (profile.intensity) {
            Intensity.LIGHT -> 6_000
            Intensity.MODERATE -> 8_000
            Intensity.INTENSE -> 10_000
        }
        val stepTarget = roundTo(baseSteps + minOf(level * 40, 2_000), 500)
        quests += Quest(
            id = "move", kind = QuestKind.MOVEMENT, title = "Daily march",
            description = "Walk $stepTarget steps.", target = stepTarget,
            progress = totals.steps.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), unit = "steps", xpReward = 20,
        )

        val planned = plan?.dayFor(date.dayOfWeek)
        val isTrainingDay = date.dayOfWeek in profile.workoutDays
        if (isTrainingDay) {
            val minutes = ((planned?.targetMinutes ?: 30) * 0.6).roundToInt().coerceAtLeast(10)
            quests += Quest(
                id = "train", kind = QuestKind.TRAINING,
                title = planned?.let { "Clear: ${it.title}" } ?: "Training session",
                description = "Train for at least $minutes minutes today.",
                target = minutes, progress = totals.workoutMinutes, unit = "min", xpReward = 40,
            )
        } else {
            quests += Quest(
                id = "recover", kind = QuestKind.RECOVERY, title = "Rest & recover",
                description = "Rest day. Sleep 7+ hours to restore your HP.",
                target = 420, progress = totals.sleepMinutes, unit = "min sleep", xpReward = 15,
            )
        }

        quests += Quest(
            id = "nourish", kind = QuestKind.NUTRITION, title = "Fuel the vessel",
            description = "Log 3 balanced meals.", target = 3, progress = totals.meals, unit = "meals", xpReward = 15,
        )
        quests += Quest(
            id = "hydrate", kind = QuestKind.HYDRATION, title = "Hydration ritual",
            description = "Drink 8 glasses of water.", target = 8, progress = totals.waterGlasses, unit = "glasses", xpReward = 10,
        )
        val mindTarget = (15 + (level / 10) * 5).coerceAtMost(30)
        quests += Quest(
            id = "mind", kind = QuestKind.MIND, title = "Sharpen the mind",
            description = "Read or study for $mindTarget minutes.", target = mindTarget,
            progress = totals.mindMinutes, unit = "min", xpReward = 20,
        )
        return quests
    }

    fun sourceKey(date: LocalDate, questId: String) = "quest:$date:$questId"
    fun perfectDayKey(date: LocalDate) = "quest:$date:perfect"

    private fun roundTo(value: Int, step: Int) = ((value + step / 2) / step) * step
}
