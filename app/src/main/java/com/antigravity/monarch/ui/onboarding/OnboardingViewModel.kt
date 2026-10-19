package com.antigravity.monarch.ui.onboarding

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.antigravity.monarch.data.ai.SelectingPlanGenerator
import com.antigravity.monarch.data.auth.GoogleAuthManager
import com.antigravity.monarch.data.auth.SignInResult
import com.antigravity.monarch.domain.engine.ProfileValidator
import com.antigravity.monarch.domain.engine.ProfileValidator.Field
import com.antigravity.monarch.domain.model.FitnessGoal
import com.antigravity.monarch.domain.model.Gender
import com.antigravity.monarch.domain.model.GeneratedPlan
import com.antigravity.monarch.domain.model.Intensity
import com.antigravity.monarch.domain.model.PlayerProfile
import com.antigravity.monarch.domain.model.defaultWorkoutDays
import com.antigravity.monarch.domain.repository.CalendarSync
import com.antigravity.monarch.domain.repository.HealthAvailability
import com.antigravity.monarch.domain.repository.HealthDataSource
import com.antigravity.monarch.domain.repository.ProfileRepository
import com.antigravity.monarch.domain.repository.SettingsRepository
import com.antigravity.monarch.domain.repository.TimeProvider
import com.antigravity.monarch.domain.usecase.HealthSyncService
import com.antigravity.monarch.domain.usecase.PlanService
import com.antigravity.monarch.domain.usecase.ProgressionService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalTime
import javax.inject.Inject

enum class OnboardingStep { AWAKENING, IDENTITY, BODY, GOAL, TRAINING, LINKS, CONFIRM, REVEAL }

data class OnboardingState(
    val step: OnboardingStep = OnboardingStep.AWAKENING,
    val name: String = "",
    val age: String = "",
    val gender: Gender? = null,
    val heightCm: String = "",
    val weightKg: String = "",
    val targetWeightKg: String = "",
    val goal: FitnessGoal? = null,
    val intensity: Intensity? = null,
    val workoutDays: Set<DayOfWeek> = defaultWorkoutDays(3),
    val workoutTime: LocalTime = LocalTime.of(18, 0),
    val errors: Map<Field, String> = emptyMap(),
    val authConfigured: Boolean = false,
    val signedInEmail: String? = null,
    val healthAvailability: HealthAvailability = HealthAvailability.UNAVAILABLE,
    val healthLinked: Boolean = false,
    val calendarEnabled: Boolean = false,
    val cloudAiAvailable: Boolean = false,
    val acceptedDisclaimer: Boolean = false,
    val generating: Boolean = false,
    val plan: GeneratedPlan? = null,
    val message: String? = null,
    val finished: Boolean = false,
) {
    val stepIndex: Int get() = step.ordinal
}

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val profiles: ProfileRepository,
    private val settings: SettingsRepository,
    private val planService: PlanService,
    private val progression: ProgressionService,
    private val healthSync: HealthSyncService,
    private val health: HealthDataSource,
    private val calendar: CalendarSync,
    private val auth: GoogleAuthManager,
    private val generator: SelectingPlanGenerator,
    private val time: TimeProvider,
) : ViewModel() {

    private val _state = MutableStateFlow(OnboardingState())
    val state: StateFlow<OnboardingState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val s = settings.current()
            _state.update {
                it.copy(
                    authConfigured = auth.isConfigured,
                    signedInEmail = s.signedInEmail,
                    name = s.signedInName?.take(PlayerProfile.MAX_NAME_LENGTH) ?: it.name,
                    healthAvailability = health.availability(),
                    healthLinked = runCatching { health.hasRequiredPermissions() }.getOrDefault(false),
                    calendarEnabled = calendar.hasPermission() && s.calendarSyncEnabled,
                    cloudAiAvailable = generator.cloudAvailable(),
                )
            }
        }
    }

    fun update(transform: (OnboardingState) -> OnboardingState) = _state.update { transform(it).copy(errors = emptyMap(), message = null) }

    fun toggleDay(day: DayOfWeek) = update { s ->
        s.copy(workoutDays = if (day in s.workoutDays) s.workoutDays - day else s.workoutDays + day)
    }

    fun signIn(activityContext: Context) = viewModelScope.launch {
        when (val result = auth.signIn(activityContext)) {
            is SignInResult.Success -> _state.update {
                it.copy(signedInEmail = result.email, name = it.name.ifBlank { result.displayName.orEmpty().take(PlayerProfile.MAX_NAME_LENGTH) }, step = OnboardingStep.IDENTITY)
            }
            SignInResult.Cancelled -> Unit
            SignInResult.NotConfigured -> _state.update { it.copy(message = "Google Sign-In isn't configured in this build. Continue as guest; your data stays on this device.") }
            is SignInResult.Error -> _state.update { it.copy(message = "Sign-in failed: ${result.message}") }
        }
    }

    fun onHealthPermissionsResult(granted: Set<String>) = viewModelScope.launch {
        val linked = runCatching { health.hasRequiredPermissions() }.getOrDefault(false)
        _state.update { it.copy(healthLinked = linked, message = if (linked) "Health Connect linked." else "Health Connect not linked. You can link it later in Settings.") }
    }

    fun onCalendarPermissionResult(granted: Boolean) = _state.update {
        it.copy(calendarEnabled = granted, message = if (granted) null else "Calendar permission denied. Plans will stay in-app.")
    }

    fun back() = _state.update { s -> if (s.step.ordinal == 0 || s.generating) s else s.copy(step = OnboardingStep.entries[s.step.ordinal - 1], errors = emptyMap()) }

    fun next() {
        val s = _state.value
        val errors = when (s.step) {
            OnboardingStep.IDENTITY -> ProfileValidator.identity(s.name, s.age, s.gender != null)
            OnboardingStep.BODY -> ProfileValidator.body(s.heightCm, s.weightKg, s.targetWeightKg)
            OnboardingStep.GOAL -> if (s.goal == null) mapOf(Field.GOAL to "Choose a goal") else emptyMap()
            OnboardingStep.TRAINING -> ProfileValidator.training(s.intensity != null, s.workoutDays)
            else -> emptyMap()
        }
        if (errors.isNotEmpty()) {
            _state.update { it.copy(errors = errors) }
            return
        }
        when (s.step) {
            OnboardingStep.CONFIRM -> awaken()
            OnboardingStep.REVEAL -> _state.update { it.copy(finished = true) }
            else -> _state.update { it.copy(step = OnboardingStep.entries[s.step.ordinal + 1]) }
        }
    }

    private fun awaken() {
        val s = _state.value
        if (!s.acceptedDisclaimer) {
            _state.update { it.copy(message = "Please acknowledge the health notice to continue.") }
            return
        }
        _state.update { it.copy(generating = true, message = null) }
        viewModelScope.launch {
            try {
                val profile = PlayerProfile(
                    name = s.name.trim(),
                    age = s.age.trim().toInt(),
                    gender = s.gender!!,
                    heightCm = s.heightCm.trim().toInt(),
                    weightKg = ProfileValidator.parseDecimal(s.weightKg)!!,
                    targetWeightKg = ProfileValidator.parseDecimal(s.targetWeightKg)!!,
                    goal = s.goal!!,
                    intensity = s.intensity!!,
                    workoutDays = s.workoutDays,
                    preferredWorkoutTime = s.workoutTime,
                    createdOn = time.today(),
                )
                profiles.saveProfile(profile)
                settings.update { it.copy(lastSeenLevel = 1, calendarSyncEnabled = s.calendarEnabled) }
                val outcome = planService.regenerate()
                if (s.healthLinked) healthSync.sync() else progression.refresh()
                val calendarNote = outcome.calendar?.let { c ->
                    if (c.ok) "${c.created} sessions added to ${c.calendarName ?: "your calendar"}." else "Calendar: ${c.error}"
                }
                _state.update { it.copy(generating = false, plan = outcome.plan, step = OnboardingStep.REVEAL, message = calendarNote) }
            } catch (e: Exception) {
                _state.update { it.copy(generating = false, message = "Something went wrong: ${e.message}") }
            }
        }
    }
}
