package com.antigravity.monarch.data.ai

import com.antigravity.monarch.BuildConfig
import com.antigravity.monarch.domain.engine.PlanRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

/** Minimal text-in / text-out LLM client so the provider can be swapped. */
interface LlmClient {
    val isConfigured: Boolean
    suspend fun complete(system: String, prompt: String): String
}

/**
 * Gemini REST client (no SDK). The key comes from `GEMINI_API_KEY` in local.properties and is
 * sent as a header, never in the URL. Only an anonymised profile is sent: no name or email.
 */
@Singleton
class GeminiClient @Inject constructor() : LlmClient {
    private val apiKey: String = BuildConfig.GEMINI_API_KEY
    private val model: String = BuildConfig.GEMINI_MODEL.ifBlank { "gemini-2.5-flash" }

    override val isConfigured: Boolean get() = apiKey.isNotBlank()

    override suspend fun complete(system: String, prompt: String): String = withContext(Dispatchers.IO) {
        check(isConfigured) { "Gemini API key not configured" }
        val body = buildJsonObject {
            putJsonObject("systemInstruction") { putJsonArray("parts") { add(buildJsonObject { put("text", system) }) } }
            putJsonArray("contents") {
                add(buildJsonObject {
                    put("role", "user")
                    putJsonArray("parts") { add(buildJsonObject { put("text", prompt) }) }
                })
            }
            putJsonObject("generationConfig") {
                put("responseMimeType", "application/json")
                put("temperature", 0.6)
                put("maxOutputTokens", 4096)
            }
        }
        val connection = (URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 45_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("x-goog-api-key", apiKey)
        }
        try {
            connection.outputStream.bufferedWriter().use { it.write(body.toString()) }
            val code = connection.responseCode
            if (code !in 200..299) throw IOException("Gemini HTTP $code")
            val response = connection.inputStream.bufferedReader().use { it.readText() }
            extractText(response) ?: throw IOException("Gemini returned no text")
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        fun extractText(response: String): String? = runCatching {
            Json.parseToJsonElement(response).jsonObject["candidates"]!!.jsonArray[0].jsonObject["content"]!!
                .jsonObject["parts"]!!.jsonArray.joinToString("") { (it as JsonObject)["text"]?.jsonPrimitive?.contentOrNull.orEmpty() }
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }
}

object PlanPrompts {
    val SYSTEM = """
        You are "The System", the planning engine of a gamified fitness app.
        Produce a safe, realistic one-week training plan for a healthy adult using common gym or home exercises.
        Never give medical advice, diagnoses, supplement or drug advice, or extreme dieting guidance.
        Respond with a single JSON object only. No markdown.
    """.trimIndent()

    fun workout(request: PlanRequest): String {
        val p = request.profile
        val days = p.workoutDays.sorted().joinToString(", ") { it.name.lowercase() }
        return """
            Player parameters (anonymous):
            - age: ${p.age}, sex: ${p.gender.name.lowercase()}, height_cm: ${p.heightCm}, weight_kg: ${"%.1f".format(p.weightKg)}, target_weight_kg: ${"%.1f".format(p.targetWeightKg)}
            - goal: ${p.goal.displayName}
            - preferred intensity: ${p.intensity.displayName}
            - training days (use exactly these): $days
            - adherence signal: ${request.adaptation.direction.name.lowercase()} (${request.adaptation.note})

            Return JSON with this shape:
            {
              "title": "short plan name",
              "summary": "one sentence",
              "days": [
                {"day": "monday", "title": "session name", "type": "strength|running|cycling|hiit|swimming|walking|yoga|sports",
                 "minutes": 45, "exercises": [{"name": "Goblet squat", "sets": 3, "reps": "10-12", "minutes": null, "notes": "optional cue"}]}
              ],
              "tips": ["up to 3 short, general nutrition or recovery habits"]
            }
            Rules: one entry per training day, 4-7 exercises each, minutes between 20 and 75,
            balance muscle groups across the week, prefer joint-friendly options if age >= 50.
        """.trimIndent()
    }
}
