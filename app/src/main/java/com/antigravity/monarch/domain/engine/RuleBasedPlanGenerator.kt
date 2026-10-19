package com.antigravity.monarch.domain.engine

import com.antigravity.monarch.domain.model.FitnessGoal
import com.antigravity.monarch.domain.model.GeneratedPlan
import com.antigravity.monarch.domain.model.Intensity
import com.antigravity.monarch.domain.model.PlanAdaptation
import com.antigravity.monarch.domain.model.PlanDay
import com.antigravity.monarch.domain.model.PlanExercise
import com.antigravity.monarch.domain.model.PlanSource
import com.antigravity.monarch.domain.model.PlayerProfile
import com.antigravity.monarch.domain.model.WorkoutPlan
import com.antigravity.monarch.domain.model.WorkoutType
import java.time.DayOfWeek
import java.time.LocalDate
import kotlin.math.roundToInt

data class PlanRequest(
    val profile: PlayerProfile,
    val adaptation: PlanAdaptation,
    val weekStart: LocalDate,
    val nowEpochMs: Long,
)

/** Anything that can turn a [PlanRequest] into a plan (rules on-device, or an LLM). */
interface PlanGenerator {
    suspend fun generate(request: PlanRequest): GeneratedPlan
}

/**
 * Deterministic on-device planner. Always available offline and used as the fallback for the
 * LLM planner. Personalises by goal, weekly frequency, intensity, age, BMI and adherence.
 */
object RuleBasedPlanGenerator : PlanGenerator {

    override suspend fun generate(request: PlanRequest): GeneratedPlan = build(request)

    fun build(request: PlanRequest): GeneratedPlan {
        val workout = workoutPlan(request)
        return GeneratedPlan(workout, DietCalculator.guideline(request.profile))
    }

    /** Parameters derived from the profile that shape every session. */
    data class Dosage(val minutes: Int, val sets: Int, val reps: String, val lowImpact: Boolean, val cautious: Boolean)

    fun dosage(profile: PlayerProfile, adaptation: PlanAdaptation): Dosage {
        val cautious = profile.age >= 50
        val effectiveIntensity = if (cautious && profile.intensity == Intensity.INTENSE) Intensity.MODERATE else profile.intensity
        val baseMinutes = when (effectiveIntensity) {
            Intensity.LIGHT -> 30
            Intensity.MODERATE -> 45
            Intensity.INTENSE -> 60
        }
        val baseSets = when (effectiveIntensity) {
            Intensity.LIGHT -> 2
            Intensity.MODERATE -> 3
            Intensity.INTENSE -> 4
        }
        val minutes = (((baseMinutes * adaptation.direction.volumeMultiplier) / 5).roundToInt() * 5).coerceIn(15, 90)
        val sets = (baseSets + adaptation.direction.extraSets).coerceIn(1, 5)
        val reps = when (profile.goal) {
            FitnessGoal.MUSCLE_GAIN -> "8-12"
            FitnessGoal.FAT_LOSS -> "12-15"
            FitnessGoal.GENERAL_FITNESS -> "10-12"
        }
        return Dosage(minutes, sets, reps, lowImpact = cautious || profile.bmi >= 30, cautious = cautious)
    }

    enum class Session { FULL_A, FULL_B, PUSH, PULL, LEGS, UPPER, LOWER, CIRCUIT, ZONE2, INTERVALS, MOBILITY }

    fun rotation(goal: FitnessGoal, days: Int): List<Session> {
        val n = days.coerceIn(1, 7)
        val cycle = when (goal) {
            FitnessGoal.FAT_LOSS -> listOf(Session.FULL_A, Session.CIRCUIT, Session.ZONE2, Session.FULL_B, Session.INTERVALS, Session.MOBILITY, Session.ZONE2)
            FitnessGoal.GENERAL_FITNESS -> listOf(Session.FULL_A, Session.ZONE2, Session.MOBILITY, Session.FULL_B, Session.INTERVALS, Session.ZONE2, Session.MOBILITY)
            FitnessGoal.MUSCLE_GAIN -> when (n) {
                1 -> listOf(Session.FULL_A)
                2 -> listOf(Session.FULL_A, Session.FULL_B)
                3 -> listOf(Session.PUSH, Session.PULL, Session.LEGS)
                4 -> listOf(Session.UPPER, Session.LOWER, Session.UPPER, Session.LOWER)
                5 -> listOf(Session.PUSH, Session.PULL, Session.LEGS, Session.UPPER, Session.LOWER)
                6 -> listOf(Session.PUSH, Session.PULL, Session.LEGS, Session.PUSH, Session.PULL, Session.LEGS)
                else -> listOf(Session.PUSH, Session.PULL, Session.LEGS, Session.UPPER, Session.LOWER, Session.ZONE2, Session.MOBILITY)
            }
        }
        return cycle.take(n)
    }

    private fun workoutPlan(request: PlanRequest): WorkoutPlan {
        val profile = request.profile
        val dose = dosage(profile, request.adaptation)
        val days = profile.workoutDays.sorted()
        val sessions = rotation(profile.goal, days.size)
        val planDays = days.mapIndexed { i, day -> session(sessions[i % sessions.size], dose, day) }
        val summary = buildString {
            append("${days.size} sessions/week · ${profile.intensity.displayName.lowercase()} intensity · ~${dose.minutes} min each.")
            if (dose.lowImpact) append(" Joint-friendly exercise choices.")
            if (dose.cautious) append(" Warm up for 10 minutes before every session.")
        }
        return WorkoutPlan(
            title = "${profile.goal.displayName} Protocol",
            summary = summary,
            days = planDays,
            source = PlanSource.RULES,
            adaptation = request.adaptation,
            weekStart = request.weekStart.toString(),
            generatedAtEpochMs = request.nowEpochMs,
        )
    }

    private fun session(type: Session, d: Dosage, day: DayOfWeek): PlanDay {
        fun lift(name: String, alt: String? = null) = PlanExercise(if (d.lowImpact && alt != null) alt else name, sets = d.sets, reps = d.reps)
        fun hold(name: String, seconds: Int) = PlanExercise(name, sets = d.sets, reps = "${seconds}s")
        fun timed(name: String, minutes: Int, notes: String? = null) = PlanExercise(name, durationMinutes = minutes.coerceAtLeast(1), notes = notes)
        val m = d.minutes
        return when (type) {
            Session.FULL_A -> PlanDay(day, "Full Body A", WorkoutType.STRENGTH, m, listOf(
                lift("Goblet squat"), lift("Push-up", "Incline push-up"), lift("Dumbbell row"),
                lift("Romanian deadlift"), hold("Plank", 30),
            ))
            Session.FULL_B -> PlanDay(day, "Full Body B", WorkoutType.STRENGTH, m, listOf(
                lift("Split squat", "Box squat"), lift("Overhead press"), lift("Lat pulldown or band pulldown"),
                lift("Glute bridge"), hold("Dead bug", 40),
            ))
            Session.PUSH -> PlanDay(day, "Push", WorkoutType.STRENGTH, m, listOf(
                lift("Bench press or push-up", "Incline push-up"), lift("Overhead press"), lift("Incline dumbbell press"),
                lift("Lateral raise"), lift("Triceps dip", "Triceps pushdown"),
            ))
            Session.PULL -> PlanDay(day, "Pull", WorkoutType.STRENGTH, m, listOf(
                lift("Pull-up or lat pulldown", "Lat pulldown"), lift("Bent-over row"), lift("Face pull"),
                lift("Biceps curl"), lift("Back extension"),
            ))
            Session.LEGS -> PlanDay(day, "Legs", WorkoutType.STRENGTH, m, listOf(
                lift("Squat", "Box squat"), lift("Romanian deadlift"), lift("Walking lunge", "Step-up"),
                lift("Calf raise"), lift("Hanging knee raise", "Reverse crunch"),
            ))
            Session.UPPER -> PlanDay(day, "Upper Body", WorkoutType.STRENGTH, m, listOf(
                lift("Bench press or push-up", "Incline push-up"), lift("Seated row"), lift("Overhead press"),
                lift("Lat pulldown"), lift("Biceps curl"), lift("Triceps extension"),
            ))
            Session.LOWER -> PlanDay(day, "Lower Body", WorkoutType.STRENGTH, m, listOf(
                lift("Squat", "Box squat"), lift("Hip thrust"), lift("Reverse lunge", "Step-up"),
                lift("Leg curl or slider curl"), lift("Calf raise"),
            ))
            Session.CIRCUIT -> PlanDay(day, "Metabolic Circuit", WorkoutType.HIIT, m, listOf(
                timed("Warm-up: easy cardio", 5),
                lift("Kettlebell swing", "Dumbbell deadlift"), lift("Jump squat", "Bodyweight squat"),
                lift("Mountain climber", "Step-up"), lift("Burpee", "Incline push-up"),
                timed("Cool-down walk", 5),
            ))
            Session.ZONE2 -> PlanDay(
                day, "Zone 2 Cardio", if (d.lowImpact) WorkoutType.CYCLING else WorkoutType.RUNNING, m,
                listOf(timed(if (d.lowImpact) "Steady cycling or brisk walk" else "Easy run or brisk walk", m, "Conversational pace: you can still talk in full sentences.")),
            )
            Session.INTERVALS -> PlanDay(
                day, "Speed Intervals", if (d.lowImpact) WorkoutType.CYCLING else WorkoutType.RUNNING, m,
                listOf(
                    timed("Warm-up", 8),
                    PlanExercise(if (d.lowImpact) "Bike: 1 min hard / 2 min easy" else "Run: 1 min hard / 2 min easy", sets = (d.sets + 3).coerceAtMost(8), reps = "rounds"),
                    timed("Cool-down", (m - 8 - (d.sets + 3) * 3).coerceAtLeast(5)),
                ),
            )
            Session.MOBILITY -> PlanDay(day, "Mobility & Core", WorkoutType.YOGA, m, listOf(
                timed("Sun salutation flow", 8), timed("Hip openers", 6), timed("Thoracic rotations", 4),
                hold("Bird dog", 30), hold("Side plank (each side)", 25), timed("Breathing cool-down", 3),
            ))
        }
    }
}
