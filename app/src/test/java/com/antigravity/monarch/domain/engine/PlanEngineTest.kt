package com.antigravity.monarch.domain.engine

import com.antigravity.monarch.domain.model.AdaptationDirection
import com.antigravity.monarch.domain.model.FitnessGoal
import com.antigravity.monarch.domain.model.Gender
import com.antigravity.monarch.domain.model.Intensity
import com.antigravity.monarch.domain.model.PlanAdaptation
import com.antigravity.monarch.domain.model.PlanSource
import com.antigravity.monarch.domain.model.PlayerProfile
import com.antigravity.monarch.domain.model.WorkoutPlan
import com.antigravity.monarch.domain.model.defaultWorkoutDays
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate

private val WEEK: LocalDate = LocalDate.of(2026, 10, 19)

private fun profile(
    goal: FitnessGoal = FitnessGoal.GENERAL_FITNESS,
    days: Int = 3,
    intensity: Intensity = Intensity.MODERATE,
    age: Int = 28,
    gender: Gender = Gender.FEMALE,
    weight: Double = 65.0,
    height: Int = 168,
) = PlayerProfile(
    name = "Cha", age = age, gender = gender, heightCm = height, weightKg = weight, targetWeightKg = weight - 3,
    goal = goal, intensity = intensity, workoutDays = defaultWorkoutDays(days), createdOn = WEEK,
)

class RuleBasedPlanGeneratorTest {

    @Test
    fun `every goal, frequency and intensity yields a valid plan on the chosen days`() {
        for (goal in FitnessGoal.entries) for (days in 1..7) for (intensity in Intensity.entries) {
            val p = profile(goal, days, intensity)
            val plan = RuleBasedPlanGenerator.build(PlanRequest(p, PlanAdaptation.NEUTRAL, WEEK, 0L)).workout
            val label = "$goal/$days/$intensity"
            assertEquals(label, p.workoutDays, plan.workoutDays)
            assertEquals(label, days, plan.days.size)
            assertEquals(PlanSource.RULES, plan.source)
            for (day in plan.days) {
                assertTrue(label, day.targetMinutes in 15..90)
                assertTrue(label, day.exercises.isNotEmpty())
                day.exercises.forEach { ex -> ex.sets?.let { assertTrue(label, it in 1..8) } }
            }
        }
    }

    @Test
    fun `muscle gain at three days is a push pull legs split`() {
        val plan = RuleBasedPlanGenerator.build(PlanRequest(profile(FitnessGoal.MUSCLE_GAIN, 3), PlanAdaptation.NEUTRAL, WEEK, 0L)).workout
        assertEquals(listOf("Push", "Pull", "Legs"), plan.days.sortedBy { it.day }.map { it.title })
    }

    @Test
    fun `adaptation changes volume`() {
        val p = profile(intensity = Intensity.MODERATE)
        val hold = RuleBasedPlanGenerator.dosage(p, PlanAdaptation(AdaptationDirection.HOLD))
        val deload = RuleBasedPlanGenerator.dosage(p, PlanAdaptation(AdaptationDirection.DELOAD))
        val progress = RuleBasedPlanGenerator.dosage(p, PlanAdaptation(AdaptationDirection.PROGRESS))
        assertTrue(deload.minutes < hold.minutes && hold.minutes < progress.minutes)
        assertTrue(deload.sets < hold.sets && hold.sets < progress.sets)
    }

    @Test
    fun `older or higher-bmi players get joint-friendly, capped programming`() {
        val senior = RuleBasedPlanGenerator.dosage(profile(age = 62, intensity = Intensity.INTENSE), PlanAdaptation.NEUTRAL)
        assertTrue(senior.lowImpact)
        assertTrue(senior.cautious)
        assertEquals(45, senior.minutes) // intense is capped to moderate volume

        val highBmi = profile(goal = FitnessGoal.FAT_LOSS, days = 7, weight = 120.0, height = 170)
        assertTrue(RuleBasedPlanGenerator.dosage(highBmi, PlanAdaptation.NEUTRAL).lowImpact)
        val names = RuleBasedPlanGenerator.build(PlanRequest(highBmi, PlanAdaptation.NEUTRAL, WEEK, 0L)).workout.days.flatMap { d -> d.exercises.map { it.name } }
        assertTrue(names.none { it.contains("Jump", ignoreCase = true) || it == "Burpee" })
    }

    @Test
    fun `plan survives a json round trip`() {
        val plan = RuleBasedPlanGenerator.build(PlanRequest(profile(), PlanAdaptation.NEUTRAL, WEEK, 123L))
        val json = Json.encodeToString(WorkoutPlan.serializer(), plan.workout)
        assertEquals(plan.workout, Json.decodeFromString(WorkoutPlan.serializer(), json))
    }
}

class DietCalculatorTest {

    @Test
    fun `bmr follows mifflin st jeor`() {
        val p = profile(gender = Gender.MALE, weight = 80.0, height = 180, age = 30)
        assertEquals(10 * 80 + 6.25 * 180 - 5 * 30 + 5, DietCalculator.bmr(p), 1e-9)
    }

    @Test
    fun `macros roughly add up to calories and goals shift energy`() {
        for (goal in FitnessGoal.entries) {
            val g = DietCalculator.guideline(profile(goal = goal))
            val fromMacros = g.proteinGrams * 4 + g.carbsGrams * 4 + g.fatGrams * 9
            assertTrue("$goal $g", kotlin.math.abs(fromMacros - g.dailyCalories) <= 25)
            assertTrue(g.waterMl in 1500..4500)
            assertTrue(g.tips.isNotEmpty() && g.mealIdeas.isNotEmpty())
        }
        val loss = DietCalculator.guideline(profile(goal = FitnessGoal.FAT_LOSS)).dailyCalories
        val maintain = DietCalculator.guideline(profile(goal = FitnessGoal.GENERAL_FITNESS)).dailyCalories
        val gain = DietCalculator.guideline(profile(goal = FitnessGoal.MUSCLE_GAIN)).dailyCalories
        assertTrue(loss < maintain && maintain < gain)
    }

    @Test
    fun `fat loss never goes below bmr or the safety floor`() {
        val small = profile(goal = FitnessGoal.FAT_LOSS, gender = Gender.FEMALE, weight = 45.0, height = 150, age = 60, days = 1)
        val g = DietCalculator.guideline(small)
        assertTrue(g.dailyCalories >= 1200)
        assertTrue(g.dailyCalories >= DietCalculator.bmr(small) - 25)
    }
}

class PlanAdaptationCalculatorTest {
    private val mwf = setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY)
    private val today = WEEK.plusDays(14) // a Monday two weeks later
    private val start = WEEK.minusDays(30)

    private fun trainedOn(vararg offsets: Int) = offsets.map { WEEK.plusDays(it.toLong()) }.toSet()

    @Test
    fun `high adherence progresses`() {
        val a = PlanAdaptationCalculator.compute(mwf, trainedOn(0, 2, 4, 7, 9, 11), today, start)
        assertEquals(AdaptationDirection.PROGRESS, a.direction)
        assertEquals(6, a.plannedSessions)
        assertEquals(6, a.completedSessions)
    }

    @Test
    fun `low adherence deloads without penalty language`() {
        val a = PlanAdaptationCalculator.compute(mwf, trainedOn(0, 9), today, start)
        assertEquals(AdaptationDirection.DELOAD, a.direction)
        assertTrue(a.note.contains("No penalty"))
    }

    @Test
    fun `moved sessions still count`() {
        // Trained on Tue/Thu/Sat instead of Mon/Wed/Fri.
        val a = PlanAdaptationCalculator.compute(mwf, trainedOn(1, 3, 5, 8, 10, 12), today, start)
        assertEquals(AdaptationDirection.PROGRESS, a.direction)
    }

    @Test
    fun `too little history holds`() {
        val a = PlanAdaptationCalculator.compute(mwf, emptySet(), today, today.minusDays(2))
        assertEquals(AdaptationDirection.HOLD, a.direction)
        assertNull(a.completionRate)
    }
}
