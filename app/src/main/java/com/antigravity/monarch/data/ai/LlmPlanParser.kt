package com.antigravity.monarch.data.ai

import com.antigravity.monarch.domain.engine.PlanRequest
import com.antigravity.monarch.domain.engine.RuleBasedPlanGenerator
import com.antigravity.monarch.domain.model.GeneratedPlan
import com.antigravity.monarch.domain.model.PlanDay
import com.antigravity.monarch.domain.model.PlanExercise
import com.antigravity.monarch.domain.model.PlanSource
import com.antigravity.monarch.domain.model.WorkoutType
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.DayOfWeek

/** Loose shape the model is asked to return. Every field is optional; validation happens after. */
@Serializable
internal data class LlmPlanDto(
    val title: String? = null,
    val summary: String? = null,
    val days: List<LlmDayDto> = emptyList(),
    val tips: List<String> = emptyList(),
)

@Serializable
internal data class LlmDayDto(
    val day: String? = null,
    val title: String? = null,
    val type: String? = null,
    val minutes: Int? = null,
    val exercises: List<LlmExerciseDto> = emptyList(),
)

@Serializable
internal data class LlmExerciseDto(
    val name: String? = null,
    val sets: Int? = null,
    val reps: String? = null,
    val minutes: Int? = null,
    val notes: String? = null,
)

class LlmPlanException(message: String) : Exception(message)

/**
 * Turns untrusted LLM text into a safe [GeneratedPlan].
 *
 * The model only shapes the *workout content*. Structure is enforced from the request: days
 * are forced onto the player's chosen training days, volumes are clamped, text is trimmed and
 * stripped of control characters, and any gap is filled from the on-device rule plan.
 * Nutrition numbers always come from the deterministic calculator; the model may only add a
 * few short tips, and tips that drift into medical territory are dropped.
 */
object LlmPlanParser {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    private val BLOCKED_TIP_TERMS = listOf(
        "cure", "treat", "diagnos", "medicat", "prescri", "steroid", "sarm", "starv", "laxative", "diuretic", "purg",
        "fasting", "detox", "supplement", "insulin",
    )

    const val MAX_EXERCISES = 8
    const val MAX_TIPS = 3

    fun parse(raw: String, request: PlanRequest): GeneratedPlan {
        val dto = decode(raw)
        val fallback = RuleBasedPlanGenerator.build(request)
        val wanted = request.profile.workoutDays

        val byDay = LinkedHashMap<DayOfWeek, PlanDay>()
        for (d in dto.days) {
            val day = parseDay(d.day) ?: continue
            if (day !in wanted || day in byDay) continue
            val fallbackDay = fallback.workout.dayFor(day) ?: continue
            val exercises = d.exercises.mapNotNull(::sanitizeExercise).take(MAX_EXERCISES)
            byDay[day] = PlanDay(
                day = day,
                title = clean(d.title, 40) ?: fallbackDay.title,
                workoutType = parseType(d.type) ?: fallbackDay.workoutType,
                targetMinutes = (d.minutes ?: fallbackDay.targetMinutes).coerceIn(15, 90),
                exercises = exercises.ifEmpty { fallbackDay.exercises },
            )
        }
        if (byDay.isEmpty()) throw LlmPlanException("Model returned no usable training days")
        val days = wanted.sorted().map { byDay[it] ?: fallback.workout.dayFor(it)!! }

        val tips = dto.tips.mapNotNull { clean(it, 140) }
            .filter { tip -> BLOCKED_TIP_TERMS.none { tip.lowercase().contains(it) } }
            .take(MAX_TIPS)

        return GeneratedPlan(
            workout = fallback.workout.copy(
                title = clean(dto.title, 60) ?: fallback.workout.title,
                summary = clean(dto.summary, 220) ?: fallback.workout.summary,
                days = days,
                source = PlanSource.LLM,
            ),
            diet = fallback.diet.copy(tips = (tips + fallback.diet.tips).distinct().take(fallback.diet.tips.size + MAX_TIPS)),
        )
    }

    private fun decode(raw: String): LlmPlanDto {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start < 0 || end <= start) throw LlmPlanException("No JSON object in model output")
        return try {
            json.decodeFromString(LlmPlanDto.serializer(), raw.substring(start, end + 1))
        } catch (e: Exception) {
            throw LlmPlanException("Malformed plan JSON: ${e.message?.take(120)}")
        }
    }

    private fun sanitizeExercise(e: LlmExerciseDto): PlanExercise? {
        val name = clean(e.name, 60) ?: return null
        return PlanExercise(
            name = name,
            sets = e.sets?.coerceIn(1, 8),
            reps = clean(e.reps, 20),
            durationMinutes = e.minutes?.coerceIn(1, 90),
            notes = clean(e.notes, 100),
        )
    }

    internal fun parseDay(value: String?): DayOfWeek? {
        val v = value?.trim()?.lowercase() ?: return null
        if (v.length < 3) return null
        return DayOfWeek.entries.firstOrNull { it.name.lowercase().startsWith(v.take(3)) }
    }

    internal fun parseType(value: String?): WorkoutType? {
        val v = value?.trim()?.lowercase() ?: return null
        return when {
            v.isEmpty() -> null
            "hiit" in v || "interval" in v || "circuit" in v -> WorkoutType.HIIT
            "strength" in v || "weight" in v || "lift" in v || "resistance" in v -> WorkoutType.STRENGTH
            "run" in v || "jog" in v -> WorkoutType.RUNNING
            "cycl" in v || "bike" in v -> WorkoutType.CYCLING
            "swim" in v -> WorkoutType.SWIMMING
            "walk" in v || "hike" in v -> WorkoutType.WALKING
            "yoga" in v || "mobility" in v || "stretch" in v || "pilates" in v || "flexib" in v -> WorkoutType.YOGA
            "sport" in v -> WorkoutType.SPORTS
            "cardio" in v -> WorkoutType.RUNNING
            else -> WorkoutType.entries.firstOrNull { it.name.equals(v, ignoreCase = true) }
        }
    }

    /** Trim, collapse whitespace, drop control/markup characters, cap length. Null when blank. */
    internal fun clean(value: String?, maxLength: Int): String? {
        val s = value
            ?.replace(Regex("[\\p{Cntrl}<>`*#]"), " ")
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            ?: return null
        if (s.isEmpty()) return null
        return if (s.length <= maxLength) s else s.take(maxLength - 1).trimEnd() + "…"
    }
}
