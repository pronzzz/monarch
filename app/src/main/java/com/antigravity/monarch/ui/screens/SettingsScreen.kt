package com.antigravity.monarch.ui.screens

import android.Manifest
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.PermissionController
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.antigravity.monarch.data.ai.LlmClient
import com.antigravity.monarch.data.auth.GoogleAuthManager
import com.antigravity.monarch.data.auth.SignInResult
import com.antigravity.monarch.data.calendar.DeviceCalendarSync
import com.antigravity.monarch.data.health.HealthConnectDataSource
import com.antigravity.monarch.data.local.DataResetter
import com.antigravity.monarch.data.sources.SourceState
import com.antigravity.monarch.data.sources.SourceStatusReader
import com.antigravity.monarch.data.strava.StravaClient
import com.antigravity.monarch.domain.model.Intensity
import com.antigravity.monarch.domain.model.PlayerProfile
import com.antigravity.monarch.domain.repository.AppSettings
import com.antigravity.monarch.domain.repository.CalendarSync
import com.antigravity.monarch.domain.repository.HealthAvailability
import com.antigravity.monarch.domain.repository.ProfileRepository
import com.antigravity.monarch.domain.repository.SettingsRepository
import com.antigravity.monarch.domain.repository.StatusListener
import com.antigravity.monarch.domain.repository.WidgetMode
import com.antigravity.monarch.domain.usecase.PlanService
import com.antigravity.monarch.ui.components.ChoiceChips
import com.antigravity.monarch.ui.components.DisclaimerText
import com.antigravity.monarch.ui.components.KeyValue
import com.antigravity.monarch.ui.components.SecondaryButton
import com.antigravity.monarch.ui.components.SystemPanel
import com.antigravity.monarch.ui.theme.MonarchColors
import com.antigravity.monarch.widget.MonarchWidgetReceiver
import com.antigravity.monarch.work.QuestNotifier
import com.antigravity.monarch.work.WorkScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalTime
import javax.inject.Inject

data class LinkState(
    val health: HealthAvailability = HealthAvailability.UNAVAILABLE,
    val healthLinked: Boolean = false,
    val calendarPermission: Boolean = false,
    val notificationsAllowed: Boolean = true,
    val sources: List<SourceState> = emptyList(),
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val profiles: ProfileRepository,
    private val settingsRepo: SettingsRepository,
    private val planService: PlanService,
    private val health: HealthConnectDataSource,
    private val calendar: CalendarSync,
    private val scheduler: WorkScheduler,
    private val notifier: QuestNotifier,
    private val resetter: DataResetter,
    private val statusListener: StatusListener,
    val strava: StravaClient,
    val sourceReader: SourceStatusReader,
    val auth: GoogleAuthManager,
    llm: LlmClient,
) : ViewModel() {
    val profile: StateFlow<PlayerProfile?> = profiles.observeProfile().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val settings: StateFlow<AppSettings> = settingsRepo.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())
    val links = MutableStateFlow(LinkState())
    val cloudConfigured = llm.isConfigured
    private val messages = Channel<String>(Channel.BUFFERED)
    val messageFlow = messages.receiveAsFlow()
    val resetDone = MutableStateFlow(false)

    fun refreshLinks() = viewModelScope.launch {
        links.value = LinkState(
            health = health.availability(),
            healthLinked = runCatching { health.hasRequiredPermissions() }.getOrDefault(false),
            calendarPermission = calendar.hasPermission(),
            notificationsAllowed = notifier.canNotify(),
            sources = sourceReader.read(),
        )
        strava.refreshStatus()
    }

    fun connectStrava(context: Context) = viewModelScope.launch {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(strava.authorizationUrl())).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            messages.send("Couldn't open Strava: ${e.message}")
        }
    }

    fun disconnectStrava() = viewModelScope.launch {
        strava.disconnect()
        messages.send("Strava disconnected. Workouts already synced keep their XP.")
    }

    fun setWidgetMode(mode: WidgetMode) = viewModelScope.launch {
        settingsRepo.update { it.copy(widgetMode = mode) }
        statusListener.onStatusChanged()
    }

    fun setReminders(enabled: Boolean, time: LocalTime) = viewModelScope.launch {
        settingsRepo.update { it.copy(remindersEnabled = enabled, reminderTime = time) }
        scheduler.scheduleReminder(enabled, time)
        if (enabled) messages.send("Daily quest notice set for ${formatTime(time)}.")
    }

    fun setCloudAi(enabled: Boolean) = viewModelScope.launch { settingsRepo.update { it.copy(cloudAiEnabled = enabled) } }

    fun setCalendarSync(enabled: Boolean) = viewModelScope.launch {
        settingsRepo.update { it.copy(calendarSyncEnabled = enabled) }
        if (enabled) {
            val r = planService.syncCalendar()
            messages.send(if (r.ok) "${r.created} sessions added to ${r.calendarName}." else "Calendar: ${r.error}")
        } else {
            messages.send("Removed ${planService.removeCalendarEvents()} upcoming Monarch events.")
        }
        refreshLinks()
    }

    fun updateTraining(days: Set<DayOfWeek>, intensity: Intensity, time: LocalTime) = viewModelScope.launch {
        val p = profiles.getProfile() ?: return@launch
        if (days.isEmpty()) {
            messages.send("Pick at least one training day.")
            return@launch
        }
        profiles.saveProfile(p.copy(workoutDays = days, intensity = intensity, preferredWorkoutTime = time))
        planService.regenerate()
        messages.send("Training updated. The System rebuilt your plan.")
    }

    fun signIn(context: Context) = viewModelScope.launch {
        messages.send(
            when (val r = auth.signIn(context)) {
                is SignInResult.Success -> "Signed in as ${r.email}"
                SignInResult.Cancelled -> "Sign-in cancelled"
                SignInResult.NotConfigured -> "Google Sign-In isn't configured in this build."
                is SignInResult.Error -> "Sign-in failed: ${r.message}"
            },
        )
    }

    fun signOut(context: Context) = viewModelScope.launch {
        auth.signOut(context)
        messages.send("Signed out. Your progress stays on this device.")
    }

    fun resetEverything() = viewModelScope.launch {
        scheduler.cancelAll()
        resetter.resetEverything()
        resetDone.value = true
    }
}

@Composable
fun SettingsScreen(padding: PaddingValues, snackbar: SnackbarHostState, onReset: () -> Unit, viewModel: SettingsViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val profile by viewModel.profile.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val links by viewModel.links.collectAsStateWithLifecycle()
    val resetDone by viewModel.resetDone.collectAsStateWithLifecycle()
    val stravaStatus by viewModel.strava.status.collectAsStateWithLifecycle()
    MessageEffect(viewModel.messageFlow, snackbar)
    LifecycleResumeEffect(Unit) {
        viewModel.refreshLinks()
        onPauseOrDispose { }
    }
    if (resetDone) {
        LaunchedEffect(Unit) { onReset() }
    }

    val healthLauncher = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) { viewModel.refreshLinks() }
    val calendarLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        if (r.values.all { it }) viewModel.setCalendarSync(true) else viewModel.refreshLinks()
    }
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        viewModel.refreshLinks()
        if (granted) viewModel.setReminders(true, settings.reminderTime)
    }
    var confirmReset by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().testTag("settings_list"),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 12.dp, bottom = padding.calculateBottomPadding() + 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        profile?.let { p ->
            item { TrainingPanel(p, viewModel::updateTraining) }
        }
        item {
            SystemPanel(title = "Home-screen widget") {
                Text("Choose how much the status HUD shows. Resize the widget for more detail.", style = MaterialTheme.typography.bodySmall, color = MonarchColors.TextDim)
                Spacer(Modifier.height(10.dp))
                ChoiceChips(WidgetMode.entries, setOf(settings.widgetMode), { if (it == WidgetMode.MINIMAL) "Minimal" else "Detailed" }, viewModel::setWidgetMode)
                Spacer(Modifier.height(10.dp))
                SecondaryButton("Add widget to home screen", { requestPinWidget(context) }, Modifier.fillMaxWidth())
            }
        }
        item {
            SystemPanel(title = "Quest notices") {
                ToggleRow("Daily quest notice", settings.remindersEnabled) { on ->
                    if (on && !links.notificationsAllowed && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        viewModel.setReminders(on, settings.reminderTime)
                    }
                }
                if (settings.remindersEnabled) {
                    Spacer(Modifier.height(8.dp))
                    TimePickerField("Notice time", settings.reminderTime) { viewModel.setReminders(true, it) }
                }
                Text("One calm notice a day at most. No streak guilt.", style = MaterialTheme.typography.bodySmall, color = MonarchColors.TextDim)
            }
        }
        item {
            SyncSourcesPanel(
                links = links,
                strava = stravaStatus,
                onLinkHealth = { healthLauncher.launch(HealthConnectDataSource.READ_PERMISSIONS + HealthConnectDataSource.BACKGROUND_PERMISSION) },
                onOpenHealthSettings = { runCatching { context.startActivity(viewModel.sourceReader.healthConnectSettingsIntent()) } },
                onOpenApp = { app -> runCatching { context.startActivity(viewModel.sourceReader.openIntent(app)) } },
                onConnectStrava = { viewModel.connectStrava(context) },
                onDisconnectStrava = viewModel::disconnectStrava,
            )
        }
        item {
            SystemPanel(title = "Calendar") {
                ToggleRow("Send weekly sessions to calendar", settings.calendarSyncEnabled && links.calendarPermission) { on ->
                    if (on && !links.calendarPermission) calendarLauncher.launch(DeviceCalendarSync.PERMISSIONS) else viewModel.setCalendarSync(on)
                }
                Text("Uses your phone's Google calendar when present, so events sync to Google Calendar.", style = MaterialTheme.typography.bodySmall, color = MonarchColors.TextDim)
            }
        }
        item {
            SystemPanel(title = "AI planner") {
                ToggleRow("Use cloud AI for plans", settings.cloudAiEnabled && viewModel.cloudConfigured, enabled = viewModel.cloudConfigured, onChange = viewModel::setCloudAi)
                Text(
                    if (viewModel.cloudConfigured) {
                        "Sends an anonymous profile (age, body metrics, goal, schedule; never your name) to Gemini. Falls back to the on-device planner automatically."
                    } else {
                        "No API key in this build. Plans come from the on-device planner."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MonarchColors.TextDim,
                )
            }
        }
        item {
            SystemPanel(title = "Account") {
                if (settings.signedInEmail != null) {
                    KeyValue("Google account", settings.signedInEmail!!)
                    SecondaryButton("Sign out", { viewModel.signOut(context) }, Modifier.fillMaxWidth())
                } else {
                    KeyValue("Mode", "Guest (offline)")
                    if (viewModel.auth.isConfigured) SecondaryButton("Sign in with Google", { viewModel.signIn(context) }, Modifier.fillMaxWidth())
                }
            }
        }
        item {
            SystemPanel(title = "Danger zone", accent = MonarchColors.Danger) {
                SecondaryButton("Reset all progress", { confirmReset = true }, Modifier.fillMaxWidth().testTag("reset_button"))
            }
        }
        item { DisclaimerText() }
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("Reset everything?") },
            text = { Text("This deletes your profile, XP, stats, achievements, plan and Monarch calendar events on this device. It cannot be undone.") },
            confirmButton = {
                TextButton(onClick = { confirmReset = false; viewModel.resetEverything() }, modifier = Modifier.testTag("confirm_reset")) {
                    Text("Reset", color = MonarchColors.Danger)
                }
            },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun TrainingPanel(profile: PlayerProfile, onSave: (Set<DayOfWeek>, Intensity, LocalTime) -> Unit) {
    var days by remember(profile) { mutableStateOf(profile.workoutDays) }
    var intensity by remember(profile) { mutableStateOf(profile.intensity) }
    var time by remember(profile) { mutableStateOf(profile.preferredWorkoutTime) }
    val dirty = days != profile.workoutDays || intensity != profile.intensity || time != profile.preferredWorkoutTime
    SystemPanel(title = "Training & rest days") {
        KeyValue("Player", profile.name)
        KeyValue("Goal", profile.goal.displayName)
        Spacer(Modifier.height(8.dp))
        Text("Training days. Unselected days are rest days and never break your streak.", style = MaterialTheme.typography.bodySmall, color = MonarchColors.TextDim)
        Spacer(Modifier.height(8.dp))
        ChoiceChips(DayOfWeek.entries, days, ::dayShort, { d -> days = if (d in days) days - d else days + d })
        Spacer(Modifier.height(10.dp))
        ChoiceChips(Intensity.entries, setOf(intensity), { it.displayName }, { intensity = it })
        Spacer(Modifier.height(10.dp))
        TimePickerField("Usual workout time", time) { time = it }
        if (dirty) {
            Spacer(Modifier.height(10.dp))
            SecondaryButton("Save & rebuild plan", { onSave(days, intensity, time) }, Modifier.fillMaxWidth(), enabled = days.isNotEmpty())
        }
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyLarge, color = if (enabled) MonarchColors.Text else MonarchColors.TextDim, modifier = Modifier.weight(1f))
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            enabled = enabled,
            colors = SwitchDefaults.colors(checkedThumbColor = MonarchColors.Void, checkedTrackColor = MonarchColors.Rim),
        )
    }
}

private fun requestPinWidget(context: Context) {
    val manager = AppWidgetManager.getInstance(context)
    if (manager.isRequestPinAppWidgetSupported) {
        manager.requestPinAppWidget(ComponentName(context, MonarchWidgetReceiver::class.java), null, null)
    }
}
