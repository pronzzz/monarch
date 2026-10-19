package com.antigravity.monarch.ui.onboarding

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.PermissionController
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.antigravity.monarch.data.calendar.DeviceCalendarSync
import com.antigravity.monarch.data.health.HealthConnectDataSource
import com.antigravity.monarch.domain.engine.ProfileValidator.Field
import com.antigravity.monarch.domain.model.FitnessGoal
import com.antigravity.monarch.domain.model.Gender
import com.antigravity.monarch.domain.model.Intensity
import com.antigravity.monarch.domain.repository.HealthAvailability
import com.antigravity.monarch.ui.components.ChoiceChips
import com.antigravity.monarch.ui.components.DisclaimerText
import com.antigravity.monarch.ui.components.KeyValue
import com.antigravity.monarch.ui.components.OptionCard
import com.antigravity.monarch.ui.components.PrimaryButton
import com.antigravity.monarch.ui.components.SecondaryButton
import com.antigravity.monarch.ui.components.SectionLabel
import com.antigravity.monarch.ui.components.SystemPanel
import com.antigravity.monarch.ui.screens.TimePickerField
import com.antigravity.monarch.ui.screens.dayShort
import com.antigravity.monarch.ui.theme.MonarchColors
import java.time.DayOfWeek

@Composable
fun OnboardingScreen(onFinished: () -> Unit, viewModel: OnboardingViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.finished) { if (state.finished) onFinished() }

    Column(
        Modifier
            .fillMaxSize()
            .background(MonarchColors.Void)
            .safeDrawingPadding()
            .imePadding()
            .padding(horizontal = 20.dp),
    ) {
        Spacer(Modifier.height(12.dp))
        StepIndicator(state.stepIndex, OnboardingStep.entries.size)
        Spacer(Modifier.height(16.dp))
        AnimatedContent(
            targetState = state.step,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            modifier = Modifier.weight(1f),
            label = "step",
        ) { step ->
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                when (step) {
                    OnboardingStep.AWAKENING -> AwakeningStep(state, viewModel)
                    OnboardingStep.IDENTITY -> IdentityStep(state, viewModel)
                    OnboardingStep.BODY -> BodyStep(state, viewModel)
                    OnboardingStep.GOAL -> GoalStep(state, viewModel)
                    OnboardingStep.TRAINING -> TrainingStep(state, viewModel)
                    OnboardingStep.LINKS -> LinksStep(state, viewModel)
                    OnboardingStep.CONFIRM -> ConfirmStep(state, viewModel)
                    OnboardingStep.REVEAL -> RevealStep(state)
                }
                Spacer(Modifier.height(16.dp))
            }
        }
        state.message?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MonarchColors.Warning, modifier = Modifier.padding(vertical = 6.dp).testTag("onboarding_message"))
        }
        if (state.step != OnboardingStep.AWAKENING) {
            Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (state.step != OnboardingStep.REVEAL) {
                    SecondaryButton("Back", viewModel::back, Modifier.width(110.dp), enabled = !state.generating)
                }
                PrimaryButton(
                    text = when (state.step) {
                        OnboardingStep.CONFIRM -> "Awaken"
                        OnboardingStep.REVEAL -> "Enter the System"
                        else -> "Next"
                    },
                    onClick = viewModel::next,
                    loading = state.generating,
                    modifier = Modifier.weight(1f).testTag("onboarding_next"),
                )
            }
        }
    }
}

@Composable
private fun StepIndicator(index: Int, count: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        repeat(count) { i ->
            Box(
                Modifier
                    .weight(1f)
                    .height(3.dp)
                    .background(if (i <= index) MonarchColors.Rim else MonarchColors.Track),
            )
        }
    }
}

@Composable
private fun Title(system: String, title: String) {
    SectionLabel(system)
    Spacer(Modifier.height(8.dp))
    Text(title, style = MaterialTheme.typography.headlineSmall, color = MonarchColors.Text)
    Spacer(Modifier.height(20.dp))
}

@Composable
private fun AwakeningStep(state: OnboardingState, vm: OnboardingViewModel) {
    val context = LocalContext.current
    Spacer(Modifier.height(32.dp))
    Text("MONARCH", style = MaterialTheme.typography.displaySmall, color = MonarchColors.Gold, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
    Spacer(Modifier.height(24.dp))
    SystemPanel(title = "System notice") {
        Text(
            "You have been selected as a Player.\n\nReal workouts, steps, sleep, meals and study become XP. " +
                "Level up from 1 to 100, raise five stats, unlock classes and earn achievements.\n\n" +
                "Rest days are part of the game. There is no XP loss, ever.",
            style = MaterialTheme.typography.bodyLarge,
            color = MonarchColors.Text,
        )
    }
    Spacer(Modifier.height(28.dp))
    if (state.signedInEmail != null) {
        Text("Signed in as ${state.signedInEmail}", style = MaterialTheme.typography.bodySmall, color = MonarchColors.TextDim)
        Spacer(Modifier.height(8.dp))
        PrimaryButton("Begin", { vm.update { it.copy(step = OnboardingStep.IDENTITY) } }, Modifier.testTag("onboarding_begin"))
    } else {
        PrimaryButton("Sign in with Google", { vm.signIn(context) }, enabled = state.authConfigured)
        if (!state.authConfigured) {
            Text(
                "Google Sign-In needs a client ID in this build (see README).",
                style = MaterialTheme.typography.bodySmall,
                color = MonarchColors.TextDim,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        Spacer(Modifier.height(12.dp))
        SecondaryButton(
            "Continue as guest (offline)",
            { vm.update { it.copy(step = OnboardingStep.IDENTITY) } },
            Modifier.fillMaxWidth().testTag("onboarding_guest"),
        )
    }
}

@Composable
private fun IdentityStep(state: OnboardingState, vm: OnboardingViewModel) {
    Title("Player registration", "Who is awakening?")
    MonarchField("Player name", state.name, { v -> vm.update { it.copy(name = v) } }, state.errors[Field.NAME], tag = "field_name")
    MonarchField("Age", state.age, { v -> vm.update { it.copy(age = v.filter(Char::isDigit).take(3)) } }, state.errors[Field.AGE], KeyboardType.Number, tag = "field_age")
    Spacer(Modifier.height(8.dp))
    Text("Sex (for calorie estimates)", style = MaterialTheme.typography.labelMedium, color = MonarchColors.TextDim)
    Spacer(Modifier.height(8.dp))
    ChoiceChips(Gender.entries, setOfNotNull(state.gender), { it.displayName }, { g -> vm.update { it.copy(gender = g) } })
    ErrorText(state.errors[Field.GENDER])
}

@Composable
private fun BodyStep(state: OnboardingState, vm: OnboardingViewModel) {
    Title("Physical scan", "Your starting stats")
    MonarchField("Height (cm)", state.heightCm, { v -> vm.update { it.copy(heightCm = v.filter(Char::isDigit).take(3)) } }, state.errors[Field.HEIGHT], KeyboardType.Number, tag = "field_height")
    MonarchField("Weight (kg)", state.weightKg, { v -> vm.update { it.copy(weightKg = v.take(6)) } }, state.errors[Field.WEIGHT], KeyboardType.Decimal, tag = "field_weight")
    MonarchField("Target weight (kg)", state.targetWeightKg, { v -> vm.update { it.copy(targetWeightKg = v.take(6)) } }, state.errors[Field.TARGET_WEIGHT], KeyboardType.Decimal, ImeAction.Done, tag = "field_target")
}

@Composable
private fun GoalStep(state: OnboardingState, vm: OnboardingViewModel) {
    Title("Objective", "Choose your path")
    FitnessGoal.entries.forEach { goal ->
        OptionCard(goal.displayName, goal.blurb, state.goal == goal, { vm.update { it.copy(goal = goal) } }, Modifier.padding(bottom = 10.dp).testTag("goal_${goal.name}"))
    }
    ErrorText(state.errors[Field.GOAL])
}

@Composable
private fun TrainingStep(state: OnboardingState, vm: OnboardingViewModel) {
    Title("Training protocol", "How hard, and when?")
    Text("Preferred intensity", style = MaterialTheme.typography.labelMedium, color = MonarchColors.TextDim)
    Spacer(Modifier.height(8.dp))
    ChoiceChips(Intensity.entries, setOfNotNull(state.intensity), { it.displayName }, { i -> vm.update { it.copy(intensity = i) } }, Modifier.testTag("intensity_chips"))
    ErrorText(state.errors[Field.INTENSITY])
    Spacer(Modifier.height(20.dp))
    Text("Training days (${state.workoutDays.size}/week). Other days are rest days.", style = MaterialTheme.typography.labelMedium, color = MonarchColors.TextDim)
    Spacer(Modifier.height(8.dp))
    ChoiceChips(DayOfWeek.entries, state.workoutDays, ::dayShort, vm::toggleDay)
    ErrorText(state.errors[Field.DAYS])
    Spacer(Modifier.height(20.dp))
    TimePickerField("Usual workout time", state.workoutTime) { t -> vm.update { it.copy(workoutTime = t) } }
}

@Composable
private fun LinksStep(state: OnboardingState, vm: OnboardingViewModel) {
    val context = LocalContext.current
    val healthLauncher = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) { granted ->
        vm.onHealthPermissionsResult(granted)
    }
    val calendarLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        vm.onCalendarPermissionResult(result.values.all { it })
    }
    Title("System links", "Connect your world (optional)")
    SystemPanel(title = "Health Connect") {
        Text(
            "Steps, workouts, sleep and active calories from Google Health / Health Connect turn into XP automatically. Read-only.",
            style = MaterialTheme.typography.bodyMedium,
            color = MonarchColors.Text,
        )
        Spacer(Modifier.height(12.dp))
        when {
            state.healthLinked -> Text("✔ Linked", color = MonarchColors.Success, style = MaterialTheme.typography.labelLarge)
            state.healthAvailability == HealthAvailability.AVAILABLE ->
                SecondaryButton("Link Health Connect", { healthLauncher.launch(HealthConnectDataSource.READ_PERMISSIONS) }, Modifier.fillMaxWidth())
            else -> {
                Text(
                    if (state.healthAvailability == HealthAvailability.NEEDS_UPDATE) "Health Connect needs an update." else "Health Connect isn't available on this device.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MonarchColors.TextDim,
                )
                Spacer(Modifier.height(8.dp))
                SecondaryButton("Get Health Connect", {
                    val uri = Uri.parse("market://details?id=${HealthConnectDataSource.PROVIDER_PACKAGE}&url=healthconnect%3A%2F%2Fonboarding")
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }, Modifier.fillMaxWidth())
            }
        }
    }
    Spacer(Modifier.height(16.dp))
    SystemPanel(title = "Calendar") {
        Text(
            "Put each week's sessions in your calendar. With a Google account on this phone they appear in Google Calendar.",
            style = MaterialTheme.typography.bodyMedium,
            color = MonarchColors.Text,
        )
        Spacer(Modifier.height(12.dp))
        if (state.calendarEnabled) {
            Text("✔ Calendar sync on", color = MonarchColors.Success, style = MaterialTheme.typography.labelLarge)
        } else {
            SecondaryButton("Enable calendar sync", { calendarLauncher.launch(DeviceCalendarSync.PERMISSIONS) }, Modifier.fillMaxWidth())
        }
    }
    Spacer(Modifier.height(12.dp))
    Text("You can change these any time in Settings.", style = MaterialTheme.typography.bodySmall, color = MonarchColors.TextDim)
}

@Composable
private fun ConfirmStep(state: OnboardingState, vm: OnboardingViewModel) {
    Title("Final check", "Ready to awaken, ${state.name.trim()}?")
    SystemPanel {
        KeyValue("Goal", state.goal?.displayName.orEmpty())
        KeyValue("Intensity", state.intensity?.displayName.orEmpty())
        KeyValue("Training days", state.workoutDays.sorted().joinToString(" ") { dayShort(it) })
        KeyValue("Planner", if (state.cloudAiAvailable) "Cloud AI + on-device rules" else "On-device rules")
    }
    Spacer(Modifier.height(16.dp))
    if (state.generating) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.width(20.dp).height(20.dp), color = MonarchColors.Rim, strokeWidth = 2.dp)
            Spacer(Modifier.width(12.dp))
            Text("The System is analysing your parameters…", color = MonarchColors.Rim, style = MaterialTheme.typography.labelMedium)
        }
        Spacer(Modifier.height(16.dp))
    }
    DisclaimerText()
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { vm.update { it.copy(acceptedDisclaimer = !it.acceptedDisclaimer) } }
            .padding(vertical = 8.dp)
            .testTag("accept_disclaimer"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = state.acceptedDisclaimer,
            onCheckedChange = { c -> vm.update { it.copy(acceptedDisclaimer = c) } },
            colors = CheckboxDefaults.colors(checkedColor = MonarchColors.Rim, checkmarkColor = MonarchColors.Void),
        )
        Text("I understand this is general guidance, not medical advice.", style = MaterialTheme.typography.bodyMedium, color = MonarchColors.Text)
    }
}

@Composable
private fun RevealStep(state: OnboardingState) {
    val plan = state.plan ?: return
    Title("Plan generated", plan.workout.title)
    Text("Source: ${plan.workout.source.displayName}", style = MaterialTheme.typography.labelSmall, color = MonarchColors.TextDim)
    Spacer(Modifier.height(12.dp))
    SystemPanel(title = "This week") {
        plan.workout.days.forEach { day ->
            KeyValue("${dayShort(day.day)} · ${day.title}", "${day.targetMinutes} min")
        }
        Spacer(Modifier.height(8.dp))
        Text(plan.workout.summary, style = MaterialTheme.typography.bodySmall, color = MonarchColors.TextDim)
    }
    Spacer(Modifier.height(12.dp))
    SystemPanel(title = "Fuel guideline", accent = MonarchColors.Gold) {
        KeyValue("Daily energy", "~${plan.diet.dailyCalories} kcal")
        KeyValue("Protein / Carbs / Fat", "${plan.diet.proteinGrams} / ${plan.diet.carbsGrams} / ${plan.diet.fatGrams} g")
        KeyValue("Water", "~${plan.diet.waterMl / 1000.0} L")
    }
    Spacer(Modifier.height(12.dp))
    DisclaimerText()
}

@Composable
fun MonarchField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    error: String?,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Next,
    tag: String = label,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        isError = error != null,
        supportingText = error?.let { { Text(it) } },
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = imeAction),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = MonarchColors.Rim,
            unfocusedBorderColor = MonarchColors.RimDim,
            focusedLabelColor = MonarchColors.Rim,
            cursorColor = MonarchColors.Rim,
        ),
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp).testTag(tag),
    )
}

@Composable
fun ErrorText(error: String?) {
    if (error != null) {
        Text(error, style = MaterialTheme.typography.bodySmall, color = MonarchColors.Danger, modifier = Modifier.padding(top = 6.dp))
    }
}
