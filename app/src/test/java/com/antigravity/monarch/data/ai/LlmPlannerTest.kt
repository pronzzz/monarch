package com.antigravity.monarch.data.ai

import com.antigravity.monarch.domain.engine.PlanRequest
import com.antigravity.monarch.domain.engine.RuleBasedPlanGenerator
import com.antigravity.monarch.domain.model.FitnessGoal
import com.antigravity.monarch.domain.model.Gender
import com.antigravity.monarch.domain.model.Intensity
import com.antigravity.monarch.domain.model.PlanAdaptation
import com.antigravity.monarch.domain.model.PlanSource
import com.antigravity.monarch.domain.model.PlayerProfile
import com.antigravity.monarch.domain.model.WorkoutType
import com.antigravity.monarch.domain.repository.AppSettings
import com.antigravity.monarch.domain.usecase.FakeSettings
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate

private val WEEK = LocalDate.of(2026, 10, 19)
private val MWF = setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY)

private val REQUEST = PlanRequest(
    profile = PlayerProfile(
        name = "Private Name", age = 30, gender = Gender.MALE, heightCm = 180, weightKg = 80.0, targetWeightKg = 76.0,
        goal = FitnessGoal.FAT_LOSS, intensity = Intensity.MODERATE, workoutDays = MWF, createdOn = WEEK,
    ),
    adaptation = PlanAdaptation.NEUTRAL,
    weekStart = WEEK,
    nowEpochMs = 42L,
)

class LlmPlanParserTest {

    private val good = """
        Here is your plan:
        ```json
        {
          "title": "Shred Week",
          "summary": "Three focused sessions.",
          "days": [
            {"day": "Monday", "title": "Lower Power", "type": "strength", "minutes": 50,
             "exercises": [{"name": "Back squat", "sets": 4, "reps": "6-8"}, {"name": "RDL", "sets": 3, "reps": "10"}]},
            {"day": "wed", "title": "Engine", "type": "HIIT intervals", "minutes": 30,
             "exercises": [{"name": "Rower sprints", "sets": 8, "reps": "30s"}]},
            {"day": "FRIDAY", "title": "Upper", "type": "weights", "minutes": 45,
             "exercises": [{"name": "Bench press", "sets": 4, "reps": "8"}]}
          ],
          "tips": ["Eat protein at every meal.", "Try a 72-hour fasting detox!", "Sleep 8 hours."]
        }
        ```
    """.trimIndent()

    @Test
    fun `parses fenced json into an llm plan on the requested days`() {
        val plan = LlmPlanParser.parse(good, REQUEST)
        assertEquals(PlanSource.LLM, plan.workout.source)
        assertEquals("Shred Week", plan.workout.title)
        assertEquals(MWF, plan.workout.workoutDays)
        assertEquals(WorkoutType.HIIT, plan.workout.dayFor(DayOfWeek.WEDNESDAY)!!.workoutType)
        assertEquals(WorkoutType.STRENGTH, plan.workout.dayFor(DayOfWeek.FRIDAY)!!.workoutType)
        assertEquals("Back squat", plan.workout.dayFor(DayOfWeek.MONDAY)!!.exercises.first().name)
    }

    @Test
    fun `nutrition numbers always come from the deterministic calculator and unsafe tips are dropped`() {
        val plan = LlmPlanParser.parse(good, REQUEST)
        val rules = RuleBasedPlanGenerator.build(REQUEST).diet
        assertEquals(rules.dailyCalories, plan.diet.dailyCalories)
        assertEquals(rules.proteinGrams, plan.diet.proteinGrams)
        assertTrue(plan.diet.tips.contains("Eat protein at every meal."))
        assertFalse(plan.diet.tips.any { it.contains("fasting", ignoreCase = true) })
    }

    @Test
    fun `days outside the schedule are ignored and missing days are filled from rules`() {
        val raw = """{"days": [
            {"day": "sunday", "title": "Bonus", "type": "running", "minutes": 40, "exercises": [{"name": "Run"}]},
            {"day": "monday", "title": "Only Monday", "type": "yoga", "minutes": 25, "exercises": [{"name": "Flow"}]}
        ]}"""
        val plan = LlmPlanParser.parse(raw, REQUEST)
        assertEquals(MWF, plan.workout.workoutDays)
        assertEquals("Only Monday", plan.workout.dayFor(DayOfWeek.MONDAY)!!.title)
        val rules = RuleBasedPlanGenerator.build(REQUEST).workout
        assertEquals(rules.dayFor(DayOfWeek.WEDNESDAY), plan.workout.dayFor(DayOfWeek.WEDNESDAY))
    }

    @Test
    fun `hostile values are clamped and cleaned`() {
        val longName = "x".repeat(500)
        val raw = """{"title": "<script>alert(1)</script>", "days": [
            {"day": "monday", "title": "   ", "type": "???", "minutes": 9999, "exercises": [
              {"name": "$longName", "sets": 500, "reps": "${"9".repeat(100)}", "minutes": -4},
              {"name": "   "},
              {"name": "Plank\u0000\u0007", "sets": -2}
            ]}
        ]}"""
        val plan = LlmPlanParser.parse(raw, REQUEST)
        val monday = plan.workout.dayFor(DayOfWeek.MONDAY)!!
        assertEquals(90, monday.targetMinutes)
        assertEquals(2, monday.exercises.size)
        val first = monday.exercises[0]
        assertTrue(first.name.length <= 60)
        assertEquals(8, first.sets)
        assertTrue(first.reps!!.length <= 20)
        assertEquals(1, first.durationMinutes)
        assertEquals("Plank", monday.exercises[1].name)
        assertEquals(1, monday.exercises[1].sets)
        assertFalse(plan.workout.title.contains("<"))
        // Blank title / unknown type fall back to the rule plan for that day.
        val rulesMonday = RuleBasedPlanGenerator.build(REQUEST).workout.dayFor(DayOfWeek.MONDAY)!!
        assertEquals(rulesMonday.title, monday.title)
        assertEquals(rulesMonday.workoutType, monday.workoutType)
    }

    @Test
    fun `garbage output is rejected so the caller can fall back`() {
        for (raw in listOf("", "I can't help with that.", "{not json", """{"days": []}""", """{"days": [{"day": "xx"}]}""")) {
            try {
                LlmPlanParser.parse(raw, REQUEST)
                fail("expected rejection for: $raw")
            } catch (expected: LlmPlanException) {
                // ok
            }
        }
    }

    @Test
    fun `day and type parsing is forgiving`() {
        assertEquals(DayOfWeek.TUESDAY, LlmPlanParser.parseDay("Tue"))
        assertEquals(DayOfWeek.THURSDAY, LlmPlanParser.parseDay(" thursday "))
        assertEquals(null, LlmPlanParser.parseDay("t"))
        assertEquals(WorkoutType.CYCLING, LlmPlanParser.parseType("Indoor bike"))
        assertEquals(WorkoutType.YOGA, LlmPlanParser.parseType("mobility"))
        assertEquals(WorkoutType.RUNNING, LlmPlanParser.parseType("cardio"))
        assertEquals(null, LlmPlanParser.parseType("???"))
    }

    @Test
    fun `prompt never includes the player's name`() {
        val prompt = PlanPrompts.workout(REQUEST)
        assertFalse(prompt.contains("Private Name"))
        assertTrue(prompt.contains("monday, wednesday, friday"))
    }

    @Test
    fun `gemini response text is extracted`() {
        val response = """{"candidates":[{"content":{"parts":[{"text":"{\"a\":"},{"text":"1}"}],"role":"model"}}]}"""
        assertEquals("""{"a":1}""", GeminiClient.extractText(response))
        assertEquals(null, GeminiClient.extractText("""{"error":{"code":429}}"""))
    }
}

private class FakeLlm(
    override val isConfigured: Boolean = true,
    val respond: suspend () -> String,
) : LlmClient {
    var calls = 0
    override suspend fun complete(system: String, prompt: String): String {
        calls++
        return respond()
    }
}

class SelectingPlanGeneratorTest {

    @Test
    fun `uses the llm when configured and enabled`() = runTest {
        val llm = FakeLlm { """{"days":[{"day":"monday","title":"AI Day","type":"strength","minutes":40,"exercises":[{"name":"Squat","sets":3,"reps":"8"}]}]}""" }
        val plan = SelectingPlanGenerator(llm, FakeSettings()).generate(REQUEST)
        assertEquals(PlanSource.LLM, plan.workout.source)
        assertEquals("AI Day", plan.workout.dayFor(DayOfWeek.MONDAY)!!.title)
    }

    @Test
    fun `falls back to rules when disabled, unconfigured, failing, invalid or slow`() = runTest {
        val disabled = FakeLlm { error("should not be called") }
        assertEquals(PlanSource.RULES, SelectingPlanGenerator(disabled, FakeSettings(AppSettings(cloudAiEnabled = false))).generate(REQUEST).workout.source)
        assertEquals(0, disabled.calls)

        val unconfigured = FakeLlm(isConfigured = false) { error("no") }
        assertEquals(PlanSource.RULES, SelectingPlanGenerator(unconfigured, FakeSettings()).generate(REQUEST).workout.source)

        val failing = FakeLlm { throw java.io.IOException("HTTP 500") }
        assertEquals(PlanSource.RULES, SelectingPlanGenerator(failing, FakeSettings()).generate(REQUEST).workout.source)

        val invalid = FakeLlm { "sorry" }
        assertEquals(PlanSource.RULES, SelectingPlanGenerator(invalid, FakeSettings()).generate(REQUEST).workout.source)

        val slow = FakeLlm { delay(120_000); "{}" }
        val plan = SelectingPlanGenerator(slow, FakeSettings()).generate(REQUEST)
        assertNotNull(plan)
        assertEquals(PlanSource.RULES, plan.workout.source)
    }
}
