package com.antigravity.monarch.domain.engine

import com.antigravity.monarch.domain.model.DietGuideline
import com.antigravity.monarch.domain.model.FitnessGoal
import com.antigravity.monarch.domain.model.Gender
import com.antigravity.monarch.domain.model.MealIdea
import com.antigravity.monarch.domain.model.PlayerProfile
import kotlin.math.roundToInt

/**
 * Basic, conservative nutrition targets from standard formulas (Mifflin-St Jeor + activity
 * factor). Deliberately mild: deficits are capped and never go below estimated BMR or a
 * minimum floor. These are general estimates, not medical advice.
 */
object DietCalculator {
    private const val FAT_LOSS_DEFICIT = 400
    private const val MUSCLE_GAIN_SURPLUS = 250

    fun bmr(profile: PlayerProfile): Double {
        val sexConstant = when (profile.gender) {
            Gender.MALE -> 5.0
            Gender.FEMALE -> -161.0
            Gender.OTHER -> -78.0
        }
        return 10 * profile.weightKg + 6.25 * profile.heightCm - 5 * profile.age + sexConstant
    }

    fun activityFactor(daysPerWeek: Int): Double = when {
        daysPerWeek <= 0 -> 1.2
        daysPerWeek <= 2 -> 1.375
        daysPerWeek <= 4 -> 1.55
        else -> 1.725
    }

    private fun calorieFloor(gender: Gender) = when (gender) {
        Gender.MALE -> 1500
        Gender.FEMALE -> 1200
        Gender.OTHER -> 1350
    }

    fun guideline(profile: PlayerProfile): DietGuideline {
        val bmr = bmr(profile)
        val tdee = bmr * activityFactor(profile.daysPerWeek)
        val raw = when (profile.goal) {
            FitnessGoal.FAT_LOSS -> tdee - FAT_LOSS_DEFICIT
            FitnessGoal.MUSCLE_GAIN -> tdee + MUSCLE_GAIN_SURPLUS
            FitnessGoal.GENERAL_FITNESS -> tdee
        }
        val calories = roundTo(maxOf(raw, bmr, calorieFloor(profile.gender).toDouble()), 50)

        // Protein from a reference weight so very high body weights don't produce extreme targets.
        val heightM = profile.heightCm / 100.0
        val referenceWeight = if (profile.bmi > 30) 27.0 * heightM * heightM else profile.weightKg
        val proteinPerKg = when (profile.goal) {
            FitnessGoal.FAT_LOSS, FitnessGoal.MUSCLE_GAIN -> 1.8
            FitnessGoal.GENERAL_FITNESS -> 1.4
        }
        val protein = (referenceWeight * proteinPerKg).roundToInt()
        var fat = (calories * 0.27 / 9).roundToInt()
        var carbs = ((calories - protein * 4 - fat * 9) / 4.0).roundToInt()
        if (carbs < 50) {
            carbs = 50
            fat = ((calories - protein * 4 - carbs * 4) / 9.0).roundToInt().coerceAtLeast(30)
        }
        val water = roundTo((profile.weightKg * 35 + profile.daysPerWeek * 70).coerceIn(1500.0, 4500.0), 250)

        return DietGuideline(
            dailyCalories = calories,
            proteinGrams = protein,
            carbsGrams = carbs,
            fatGrams = fat,
            waterMl = water,
            tips = tips(profile.goal),
            mealIdeas = meals(profile.goal),
        )
    }

    private fun tips(goal: FitnessGoal): List<String> = buildList {
        add("Build each meal around a palm-sized protein source and a fist of vegetables.")
        add("Spread protein across 3–4 meals rather than one large serving.")
        when (goal) {
            FitnessGoal.FAT_LOSS -> {
                add("Aim for a gentle deficit. Fast drops in weight are harder to keep off.")
                add("Favour high-volume foods (vegetables, fruit, soups) to stay full.")
            }
            FitnessGoal.MUSCLE_GAIN -> {
                add("Eat a carb + protein meal within a few hours after training.")
                add("If weight hasn't moved in two weeks, add a small snack (~200 kcal).")
            }
            FitnessGoal.GENERAL_FITNESS -> {
                add("Prioritise whole foods most of the time; leave room for foods you enjoy.")
                add("Keep a water bottle visible; thirst is easy to miss.")
            }
        }
        add("Sleep 7–9 hours. Recovery is where stats actually grow.")
    }

    private fun meals(goal: FitnessGoal): List<MealIdea> = when (goal) {
        FitnessGoal.FAT_LOSS -> listOf(
            MealIdea("Breakfast", "Greek yoghurt, berries and a sprinkle of oats"),
            MealIdea("Lunch", "Chicken or tofu salad bowl with beans and olive-oil dressing"),
            MealIdea("Dinner", "Grilled fish with roasted vegetables and a small portion of rice"),
            MealIdea("Snack", "Apple with a tablespoon of peanut butter"),
        )
        FitnessGoal.MUSCLE_GAIN -> listOf(
            MealIdea("Breakfast", "Eggs on whole-grain toast with fruit"),
            MealIdea("Lunch", "Rice, lean beef or lentils, and stir-fried vegetables"),
            MealIdea("Dinner", "Salmon, potatoes and greens"),
            MealIdea("Snack", "Milk or soy smoothie with banana and oats"),
        )
        FitnessGoal.GENERAL_FITNESS -> listOf(
            MealIdea("Breakfast", "Overnight oats with seeds and fruit"),
            MealIdea("Lunch", "Whole-grain wrap with chicken or hummus and salad"),
            MealIdea("Dinner", "Vegetable curry with chickpeas and rice"),
            MealIdea("Snack", "Handful of nuts and a piece of fruit"),
        )
    }

    private fun roundTo(value: Double, step: Int): Int = ((value / step).roundToInt()) * step
}
