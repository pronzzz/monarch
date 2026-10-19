package com.antigravity.monarch.domain.model

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

enum class Gender(val displayName: String) {
    MALE("Male"),
    FEMALE("Female"),
    OTHER("Other / prefer not to say"),
}

enum class FitnessGoal(val displayName: String, val blurb: String) {
    FAT_LOSS("Fat loss", "Lean out while keeping strength"),
    MUSCLE_GAIN("Muscle gain", "Build size and raw strength"),
    GENERAL_FITNESS("General fitness", "Feel better, move better"),
}

/** Everything collected during onboarding plus progression choices the player makes later. */
data class PlayerProfile(
    val name: String,
    val age: Int,
    val gender: Gender,
    val heightCm: Int,
    val weightKg: Double,
    val targetWeightKg: Double,
    val goal: FitnessGoal,
    val intensity: Intensity,
    val workoutDays: Set<DayOfWeek>,
    val preferredWorkoutTime: LocalTime = LocalTime.of(18, 0),
    val playerClass: PlayerClass? = null,
    val classChangedOn: LocalDate? = null,
    val createdOn: LocalDate,
) {
    val daysPerWeek: Int get() = workoutDays.size

    /** Days without a planned session. Missing one of these never touches a streak. */
    val restDays: Set<DayOfWeek> get() = DayOfWeek.entries.toSet() - workoutDays

    val bmi: Double get() = weightKg / ((heightCm / 100.0) * (heightCm / 100.0))

    companion object {
        const val MIN_AGE = 16
        const val MAX_AGE = 100
        val HEIGHT_RANGE_CM = 100..250
        val WEIGHT_RANGE_KG = 30.0..300.0
        const val MAX_NAME_LENGTH = 24
    }
}

/** Sensible default training days for a given weekly frequency (spread out for recovery). */
fun defaultWorkoutDays(daysPerWeek: Int): Set<DayOfWeek> = when (daysPerWeek.coerceIn(1, 7)) {
    1 -> setOf(DayOfWeek.WEDNESDAY)
    2 -> setOf(DayOfWeek.TUESDAY, DayOfWeek.FRIDAY)
    3 -> setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY)
    4 -> setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)
    5 -> setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY, DayOfWeek.SATURDAY)
    6 -> DayOfWeek.entries.toSet() - DayOfWeek.SUNDAY
    else -> DayOfWeek.entries.toSet()
}
