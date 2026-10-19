package com.antigravity.monarch.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.antigravity.monarch.data.ai.SelectingPlanGenerator
import com.antigravity.monarch.data.calendar.DeviceCalendarSync
import com.antigravity.monarch.domain.model.AdaptationDirection
import com.antigravity.monarch.domain.model.GeneratedPlan
import com.antigravity.monarch.domain.model.PlanDay
import com.antigravity.monarch.domain.repository.CalendarSync
import com.antigravity.monarch.domain.repository.SettingsRepository
import com.antigravity.monarch.domain.repository.TimeProvider
import com.antigravity.monarch.domain.usecase.PlanService
import com.antigravity.monarch.ui.components.DisclaimerText
import com.antigravity.monarch.ui.components.KeyValue
import com.antigravity.monarch.ui.components.SecondaryButton
import com.antigravity.monarch.ui.components.SystemPanel
import com.antigravity.monarch.ui.theme.MonarchColors
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PlanViewModel @Inject constructor(
    private val planService: PlanService,
    private val settings: SettingsRepository,
    private val calendar: CalendarSync,
    private val generator: SelectingPlanGenerator,
    private val time: TimeProvider,
) : ViewModel() {
    val plan: StateFlow<GeneratedPlan?> = planService.observePlan().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val busy = MutableStateFlow(false)
    val cloudAi = MutableStateFlow(false)
    private val messages = Channel<String>(Channel.BUFFERED)
    val messageFlow = messages.receiveAsFlow()

    val today get() = time.today()

    init {
        viewModelScope.launch {
            cloudAi.value = generator.cloudAvailable()
            runCatching { planService.ensureCurrentPlan() }
        }
    }

    fun regenerate() = launchBusy {
        val outcome = planService.regenerate()
        val cal = outcome.calendar?.let { if (it.ok) " ${it.created} sessions sent to ${it.calendarName}." else " Calendar: ${it.error}" }.orEmpty()
        messages.send("New plan from ${outcome.plan.workout.source.displayName}.$cal")
    }

    fun syncCalendar() = launchBusy {
        if (!calendar.hasPermission()) {
            messages.send("Calendar permission is needed.")
            return@launchBusy
        }
        settings.update { it.copy(calendarSyncEnabled = true) }
        val result = planService.syncCalendar()
        messages.send(if (result.ok) "${result.created} upcoming sessions added to ${result.calendarName}." else "Calendar: ${result.error}")
    }

    fun hasCalendarPermission() = calendar.hasPermission()

    private fun launchBusy(block: suspend () -> Unit) {
        if (busy.value) return
        viewModelScope.launch {
            busy.value = true
            try {
                block()
            } catch (e: Exception) {
                messages.send("Failed: ${e.message}")
            } finally {
                busy.value = false
            }
        }
    }
}

@Composable
fun PlanScreen(padding: PaddingValues, snackbar: SnackbarHostState, viewModel: PlanViewModel = hiltViewModel()) {
    val plan by viewModel.plan.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val cloud by viewModel.cloudAi.collectAsStateWithLifecycle()
    MessageEffect(viewModel.messageFlow, snackbar)
    val calendarLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        if (r.values.all { it }) viewModel.syncCalendar()
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().testTag("plan_list"),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 12.dp, bottom = padding.calculateBottomPadding() + 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        val p = plan
        if (p == null) {
            item { SystemPanel(title = "Plan") { EmptyNote("Generating your plan…") } }
            return@LazyColumn
        }
        item {
            SystemPanel(title = "Weekly protocol") {
                Text(p.workout.title, style = MaterialTheme.typography.titleLarge, color = MonarchColors.Text)
                Spacer(Modifier.height(4.dp))
                Text(p.workout.summary, style = MaterialTheme.typography.bodyMedium, color = MonarchColors.TextDim)
                Spacer(Modifier.height(10.dp))
                KeyValue("Planner", p.workout.source.displayName)
                KeyValue("Week of", p.workout.weekStart)
                val adaptation = p.workout.adaptation
                Spacer(Modifier.height(8.dp))
                Text(
                    "${adaptationIcon(adaptation.direction)} ${adaptation.note}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MonarchColors.Rim,
                )
            }
        }
        items(p.workout.days, key = { it.day.name }) { day -> PlanDayCard(day, isToday = day.day == viewModel.today.dayOfWeek) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SecondaryButton("Regenerate", viewModel::regenerate, Modifier.weight(1f), enabled = !busy)
                SecondaryButton("Send to calendar", {
                    if (viewModel.hasCalendarPermission()) viewModel.syncCalendar() else calendarLauncher.launch(DeviceCalendarSync.PERMISSIONS)
                }, Modifier.weight(1f), enabled = !busy)
            }
            if (!cloud) {
                Text(
                    "Plans are generated on-device. Add a Gemini API key to enable cloud AI personalisation.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MonarchColors.TextDim,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
        item {
            SystemPanel(title = "Fuel guideline", accent = MonarchColors.Gold) {
                KeyValue("Daily energy", "~${p.diet.dailyCalories} kcal")
                KeyValue("Protein", "${p.diet.proteinGrams} g")
                KeyValue("Carbohydrates", "${p.diet.carbsGrams} g")
                KeyValue("Fat", "${p.diet.fatGrams} g")
                KeyValue("Water", "~${"%.1f".format(p.diet.waterMl / 1000.0)} L")
                Spacer(Modifier.height(10.dp))
                p.diet.tips.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall, color = MonarchColors.Text, modifier = Modifier.padding(vertical = 2.dp)) }
                Spacer(Modifier.height(10.dp))
                p.diet.mealIdeas.forEach { KeyValue(it.meal, "", valueColor = MonarchColors.Text); Text(it.idea, style = MaterialTheme.typography.bodySmall, color = MonarchColors.TextDim) }
            }
        }
        item { DisclaimerText() }
    }
}

private fun adaptationIcon(d: AdaptationDirection) = when (d) {
    AdaptationDirection.PROGRESS -> "▲"
    AdaptationDirection.HOLD -> "■"
    AdaptationDirection.DELOAD -> "▼"
}

@Composable
private fun PlanDayCard(day: PlanDay, isToday: Boolean) {
    var expanded by rememberSaveable(day.day) { mutableStateOf(isToday) }
    SystemPanel(
        modifier = Modifier.animateContentSize().clickable { expanded = !expanded },
        accent = if (isToday) MonarchColors.Gold else MonarchColors.Rim,
        contentPadding = 14.dp,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(dayShort(day.day).uppercase(), style = MaterialTheme.typography.labelLarge, color = if (isToday) MonarchColors.Gold else MonarchColors.Rim, modifier = Modifier.width(52.dp))
            Column(Modifier.weight(1f)) {
                Text(day.title, style = MaterialTheme.typography.titleMedium, color = MonarchColors.Text)
                Text("${day.workoutType.displayName} · ${day.targetMinutes} min", style = MaterialTheme.typography.bodySmall, color = MonarchColors.TextDim)
            }
            Text(if (expanded) "▾" else "▸", color = MonarchColors.TextDim)
        }
        if (expanded) {
            Spacer(Modifier.height(10.dp))
            day.exercises.forEach { ex ->
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                    Text("• ${ex.name}", style = MaterialTheme.typography.bodyMedium, color = MonarchColors.Text, modifier = Modifier.weight(1f))
                    Text(ex.prescription(), style = MaterialTheme.typography.labelMedium, color = MonarchColors.Rim)
                }
                ex.notes?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MonarchColors.TextDim, modifier = Modifier.padding(start = 12.dp)) }
            }
        }
    }
}
