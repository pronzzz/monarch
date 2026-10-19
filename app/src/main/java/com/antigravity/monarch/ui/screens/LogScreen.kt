package com.antigravity.monarch.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.antigravity.monarch.domain.engine.XpAllocator
import com.antigravity.monarch.domain.engine.XpRules
import com.antigravity.monarch.domain.model.ActivitySource
import com.antigravity.monarch.domain.model.AwardResult
import com.antigravity.monarch.domain.model.HabitEntry
import com.antigravity.monarch.domain.model.HabitType
import com.antigravity.monarch.domain.model.Intensity
import com.antigravity.monarch.domain.model.StatType
import com.antigravity.monarch.domain.model.WorkoutEntry
import com.antigravity.monarch.domain.model.WorkoutType
import com.antigravity.monarch.domain.repository.ActivityRepository
import com.antigravity.monarch.domain.repository.ProfileRepository
import com.antigravity.monarch.domain.repository.TimeProvider
import com.antigravity.monarch.domain.repository.XpLedgerRepository
import com.antigravity.monarch.domain.usecase.ProgressionService
import com.antigravity.monarch.ui.components.ChoiceChips
import com.antigravity.monarch.ui.components.PrimaryButton
import com.antigravity.monarch.ui.components.SecondaryButton
import com.antigravity.monarch.ui.components.SystemPanel
import com.antigravity.monarch.ui.components.describe
import com.antigravity.monarch.ui.onboarding.MonarchField
import com.antigravity.monarch.ui.theme.MonarchColors
import com.antigravity.monarch.ui.theme.statColor
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

data class WorkoutForm(
    val type: WorkoutType = WorkoutType.STRENGTH,
    val title: String = "",
    val minutes: Int = 30,
    val intensity: Intensity = Intensity.MODERATE,
    val yesterday: Boolean = false,
)

@HiltViewModel
class LogViewModel @Inject constructor(
    private val progression: ProgressionService,
    private val time: TimeProvider,
    activities: ActivityRepository,
    ledger: XpLedgerRepository,
    profiles: ProfileRepository,
) : ViewModel() {
    val form = MutableStateFlow(WorkoutForm())
    val busy = MutableStateFlow(false)
    private val messages = Channel<String>(Channel.BUFFERED)
    val messageFlow = messages.receiveAsFlow()
    private val today: LocalDate = time.today()

    /** Live estimate of what the current form would award after class bonus and caps. */
    val preview: StateFlow<Map<StatType, Int>> = combine(form, ledger.observeStatXpForDay(today), profiles.observeProfile()) { f, todayXp, profile ->
        val raw = XpRules.workoutXp(f.type, f.minutes, f.intensity)
        if (f.yesterday) raw else XpAllocator.allocate(raw, todayXp, profile?.playerClass).awarded
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    val recentWorkouts: StateFlow<List<WorkoutEntry>> = activities.observeRecentWorkouts(10).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val todayHabits: StateFlow<List<HabitEntry>> = activities.observeHabitsForDay(today).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun edit(transform: (WorkoutForm) -> WorkoutForm) = form.update(transform)

    fun logWorkout() = act {
        val f = form.value
        val date = if (f.yesterday) time.today().minusDays(1) else time.today()
        val result = progression.logWorkout(f.type, f.title, f.minutes, f.intensity, date = date)
        form.update { it.copy(title = "") }
        result
    }

    fun logHabit(type: HabitType, quantity: Int) = act { progression.logHabit(type, quantity) }

    fun deleteWorkout(id: Long) = viewModelScope.launch {
        messages.send(if (progression.deleteWorkout(id)) "Workout removed. Its XP was withdrawn." else "Synced workouts can't be removed here.")
    }

    fun deleteHabit(id: Long) = viewModelScope.launch {
        if (progression.deleteHabit(id)) messages.send("Entry removed.")
    }

    private fun act(block: suspend () -> AwardResult) {
        if (busy.value) return
        viewModelScope.launch {
            busy.value = true
            try {
                messages.send(block().describe())
            } catch (e: IllegalArgumentException) {
                messages.send(e.message ?: "Invalid entry")
            } catch (e: Exception) {
                messages.send("Couldn't log: ${e.message}")
            } finally {
                busy.value = false
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LogScreen(padding: PaddingValues, snackbar: SnackbarHostState, viewModel: LogViewModel = hiltViewModel()) {
    val form by viewModel.form.collectAsStateWithLifecycle()
    val preview by viewModel.preview.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val recent by viewModel.recentWorkouts.collectAsStateWithLifecycle()
    val habits by viewModel.todayHabits.collectAsStateWithLifecycle()
    MessageEffect(viewModel.messageFlow, snackbar)

    LazyColumn(
        modifier = Modifier.fillMaxSize().testTag("log_list"),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 12.dp, bottom = padding.calculateBottomPadding() + 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            SystemPanel(title = "Log workout") {
                ChoiceChips(WorkoutType.entries, setOf(form.type), { it.displayName }, { t -> viewModel.edit { it.copy(type = t) } })
                Spacer(Modifier.height(12.dp))
                MonarchField("Title (optional)", form.title, { v -> viewModel.edit { it.copy(title = v.take(60)) } }, null, tag = "workout_title")
                Text("Duration: ${form.minutes} min", style = MaterialTheme.typography.labelLarge, color = MonarchColors.Text)
                Slider(
                    value = form.minutes.toFloat(),
                    onValueChange = { v -> viewModel.edit { it.copy(minutes = v.toInt()) } },
                    valueRange = 5f..180f,
                    steps = 34,
                    colors = SliderDefaults.colors(thumbColor = MonarchColors.Rim, activeTrackColor = MonarchColors.Rim),
                    modifier = Modifier.testTag("duration_slider"),
                )
                ChoiceChips(Intensity.entries, setOf(form.intensity), { it.displayName }, { i -> viewModel.edit { it.copy(intensity = i) } })
                Spacer(Modifier.height(10.dp))
                ChoiceChips(listOf(false, true), setOf(form.yesterday), { if (it) "Yesterday" else "Today" }, { y -> viewModel.edit { it.copy(yesterday = y) } })
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Estimated: ", style = MaterialTheme.typography.labelMedium, color = MonarchColors.TextDim)
                    if (preview.isEmpty()) {
                        Text("0 XP (daily cap reached)", style = MaterialTheme.typography.labelMedium, color = MonarchColors.Warning)
                    } else {
                        preview.entries.sortedByDescending { it.value }.forEach { (stat, xp) ->
                            Text("${stat.shortName}+$xp  ", style = MaterialTheme.typography.labelMedium, color = statColor(stat))
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                PrimaryButton("Log workout", viewModel::logWorkout, Modifier.testTag("log_workout_button"), loading = busy)
            }
        }
        item {
            SystemPanel(title = "Quick habits", accent = MonarchColors.Gold) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    HabitButton("+1 Meal", Modifier.testTag("habit_meal")) { viewModel.logHabit(HabitType.MEAL, 1) }
                    HabitButton("+1 Water") { viewModel.logHabit(HabitType.HYDRATION, 1) }
                    HabitButton("Read 15m") { viewModel.logHabit(HabitType.READING, 15) }
                    HabitButton("Read 30m") { viewModel.logHabit(HabitType.READING, 30) }
                    HabitButton("Study 30m") { viewModel.logHabit(HabitType.LEARNING, 30) }
                    HabitButton("Meditate 10m") { viewModel.logHabit(HabitType.MEDITATION, 10) }
                    HabitButton("Slept 7h") { viewModel.logHabit(HabitType.SLEEP, 420) }
                    HabitButton("Slept 8h") { viewModel.logHabit(HabitType.SLEEP, 480) }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "Health XP rewards consistency: up to ${XpRules.MAX_MEALS_PER_DAY} meals and ${XpRules.MAX_GLASSES_PER_DAY} glasses count each day.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MonarchColors.TextDim,
                )
            }
        }
        if (habits.isNotEmpty()) {
            item {
                SystemPanel(title = "Today's habits") {
                    habits.forEach { h ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("${h.type.displayName} · ${h.quantity} ${h.type.unit}", style = MaterialTheme.typography.bodyMedium, color = MonarchColors.Text, modifier = Modifier.weight(1f))
                            Text("+${h.xpAwarded}", style = MaterialTheme.typography.labelMedium, color = MonarchColors.Success)
                            TextButton(onClick = { viewModel.deleteHabit(h.id) }) { Text("Undo", color = MonarchColors.TextDim) }
                        }
                    }
                }
            }
        }
        item { Text("[ RECENT WORKOUTS ]", style = MaterialTheme.typography.titleSmall, color = MonarchColors.Rim) }
        if (recent.isEmpty()) item { EmptyNote("No workouts yet. Your first one unlocks an achievement.") }
        items(recent, key = { it.id }) { w ->
            SystemPanel(contentPadding = 12.dp) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(w.title, style = MaterialTheme.typography.titleMedium, color = MonarchColors.Text)
                        Text(
                            "${w.date} · ${w.durationMinutes} min · ${w.intensity.displayName}" + if (w.source == ActivitySource.HEALTH_CONNECT) " · Health Connect" else "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MonarchColors.TextDim,
                        )
                    }
                    Text("+${w.xpAwarded} XP", style = MaterialTheme.typography.labelMedium, color = MonarchColors.Success)
                    if (w.source == ActivitySource.MANUAL) {
                        TextButton(onClick = { viewModel.deleteWorkout(w.id) }) { Text("Undo", color = MonarchColors.TextDim) }
                    }
                }
            }
        }
    }
}

@Composable
private fun HabitButton(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    SecondaryButton(text, onClick, modifier.padding(0.dp))
}
