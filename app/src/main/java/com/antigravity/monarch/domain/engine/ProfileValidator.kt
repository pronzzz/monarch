package com.antigravity.monarch.domain.engine

import com.antigravity.monarch.domain.model.PlayerProfile
import java.time.DayOfWeek

/** Input validation for onboarding / profile edits. Returns field -> message; empty when valid. */
object ProfileValidator {
    enum class Field { NAME, AGE, GENDER, HEIGHT, WEIGHT, TARGET_WEIGHT, GOAL, INTENSITY, DAYS }

    fun identity(name: String, age: String, genderChosen: Boolean): Map<Field, String> = buildMap {
        val trimmed = name.trim()
        when {
            trimmed.isEmpty() -> put(Field.NAME, "Enter a player name")
            trimmed.length > PlayerProfile.MAX_NAME_LENGTH -> put(Field.NAME, "Max ${PlayerProfile.MAX_NAME_LENGTH} characters")
        }
        val a = age.trim().toIntOrNull()
        when {
            a == null -> put(Field.AGE, "Enter your age")
            a < PlayerProfile.MIN_AGE -> put(Field.AGE, "Monarch is for ages ${PlayerProfile.MIN_AGE}+")
            a > PlayerProfile.MAX_AGE -> put(Field.AGE, "Enter a valid age")
        }
        if (!genderChosen) put(Field.GENDER, "Choose an option (used only for calorie estimates)")
    }

    fun body(height: String, weight: String, target: String): Map<Field, String> = buildMap {
        val h = height.trim().toIntOrNull()
        if (h == null || h !in PlayerProfile.HEIGHT_RANGE_CM) {
            put(Field.HEIGHT, "Height must be ${PlayerProfile.HEIGHT_RANGE_CM.first}-${PlayerProfile.HEIGHT_RANGE_CM.last} cm")
        }
        val range = PlayerProfile.WEIGHT_RANGE_KG
        val w = parseDecimal(weight)
        if (w == null || w !in range) put(Field.WEIGHT, "Weight must be ${range.start.toInt()}-${range.endInclusive.toInt()} kg")
        val t = parseDecimal(target)
        if (t == null || t !in range) put(Field.TARGET_WEIGHT, "Target must be ${range.start.toInt()}-${range.endInclusive.toInt()} kg")
    }

    fun training(intensityChosen: Boolean, days: Set<DayOfWeek>): Map<Field, String> = buildMap {
        if (!intensityChosen) put(Field.INTENSITY, "Choose an intensity")
        if (days.isEmpty()) put(Field.DAYS, "Pick at least one training day")
    }

    /** Accepts "72", "72.5" and "72,5". */
    fun parseDecimal(value: String): Double? = value.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() }
}
