package com.antigravity.monarch.data.ai

import android.util.Log
import com.antigravity.monarch.domain.engine.PlanGenerator
import com.antigravity.monarch.domain.engine.PlanRequest
import com.antigravity.monarch.domain.engine.RuleBasedPlanGenerator
import com.antigravity.monarch.domain.model.GeneratedPlan
import com.antigravity.monarch.domain.repository.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Uses the cloud LLM when it is configured and the player allows it, otherwise (or on any
 * failure) the on-device rule planner. Plan generation therefore never fails or blocks.
 */
@Singleton
class SelectingPlanGenerator @Inject constructor(
    private val llm: LlmClient,
    private val settings: SettingsRepository,
) : PlanGenerator {

    suspend fun cloudAvailable(): Boolean = llm.isConfigured && settings.current().cloudAiEnabled

    override suspend fun generate(request: PlanRequest): GeneratedPlan {
        if (!cloudAvailable()) return RuleBasedPlanGenerator.build(request)
        return try {
            val raw = withTimeoutOrNull(LLM_TIMEOUT_MS) { llm.complete(PlanPrompts.SYSTEM, PlanPrompts.workout(request)) }
                ?: return RuleBasedPlanGenerator.build(request)
            LlmPlanParser.parse(raw, request)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Cloud plan failed, using on-device planner: ${e.message}")
            RuleBasedPlanGenerator.build(request)
        }
    }

    private companion object {
        const val TAG = "PlanGenerator"
        const val LLM_TIMEOUT_MS = 40_000L
    }
}
