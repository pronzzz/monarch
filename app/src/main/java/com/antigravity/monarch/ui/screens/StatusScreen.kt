package com.antigravity.monarch.ui.screens

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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.antigravity.monarch.domain.engine.QuestEngine
import com.antigravity.monarch.domain.model.PlayerStatus
import com.antigravity.monarch.domain.model.Quest
import com.antigravity.monarch.domain.repository.ActivityRepository
import com.antigravity.monarch.domain.repository.PlanRepository
import com.antigravity.monarch.domain.repository.ProfileRepository
import com.antigravity.monarch.domain.repository.TimeProvider
import com.antigravity.monarch.domain.usecase.HealthSyncOutcome
import com.antigravity.monarch.domain.usecase.HealthSyncService
import com.antigravity.monarch.domain.usecase.PlanService
import com.antigravity.monarch.domain.usecase.ProgressionService
import com.antigravity.monarch.ui.components.GlowBar
import com.antigravity.monarch.ui.components.QuestRow
import com.antigravity.monarch.ui.components.SecondaryButton
import com.antigravity.monarch.ui.components.StatRow
import com.antigravity.monarch.ui.components.SystemPanel
import com.antigravity.monarch.ui.components.describe
import com.antigravity.monarch.ui.theme.MonarchColors
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class StatusUi(
    val status: PlayerStatus,
    val quests: List<Quest>,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class StatusViewModel @Inject constructor(
    private val progression: ProgressionService,
    private val healthSync: HealthSyncService,
    private val planService: PlanService,
    profiles: ProfileRepository,
    activities: ActivityRepository,
    plans: PlanRepository,
    private val time: TimeProvider,
) : ViewModel() {

    private val today = MutableStateFlow(time.today())
    private val messages = Channel<String>(Channel.BUFFERED)
    val messageFlow = messages.receiveAsFlow()
    val syncing = MutableStateFlow(false)

    val ui: StateFlow<StatusUi?> = today.flatMapLatest { date ->
        combine(
            progression.observeStatus(date),
            profiles.observeProfile().filterNotNull(),
            plans.observePlan(),
            activities.observeDayTotals(date),
        ) { status, profile, plan, totals ->
            StatusUi(status = status, quests = QuestEngine.questsFor(date, profile, plan?.workout, status.level, totals))
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Called on resume: roll the day over, refresh the plan for a new week, sync health. */
    fun onResume() {
        today.value = time.today()
        viewModelScope.launch {
            runCatching { planService.ensureCurrentPlan() }
            runCatching { progression.refresh() }
            sync(silent = true)
        }
    }

    fun sync(silent: Boolean = false) {
        if (syncing.value) return
        viewModelScope.launch {
            syncing.value = true
            val outcome = runCatching { healthSync.sync() }.getOrElse { HealthSyncOutcome.Failed(it.message ?: "error") }
            syncing.value = false
            if (silent && outcome !is HealthSyncOutcome.Synced) return@launch
            messages.send(
                when (outcome) {
                    is HealthSyncOutcome.Synced -> buildString {
                        append(if (outcome.result.totalXp > 0) "Synced · ${outcome.result.describe()}" else "Synced ${outcome.sources.joinToString(" + ")}. Nothing new.")
                        if (outcome.warnings.isNotEmpty()) append(" (${outcome.warnings.joinToString("; ")})")
                    }
                    HealthSyncOutcome.MissingPermissions -> "Link Health Connect or Strava in Settings to sync."
                    HealthSyncOutcome.Unavailable -> "Health Connect isn't available on this device."
                    HealthSyncOutcome.NoProfile -> "Finish onboarding first."
                    is HealthSyncOutcome.Failed -> "Sync failed: ${outcome.message}"
                },
            )
        }
    }

}

@Composable
fun StatusScreen(
    padding: PaddingValues,
    snackbar: SnackbarHostState,
    onOpenClasses: () -> Unit,
    onOpenDaily: () -> Unit,
    onOpenWeekly: () -> Unit,
    viewModel: StatusViewModel = hiltViewModel(),
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val syncing by viewModel.syncing.collectAsStateWithLifecycle()
    MessageEffect(viewModel.messageFlow, snackbar)
    LifecycleResumeEffect(Unit) {
        viewModel.onResume()
        onPauseOrDispose { }
    }

    val current = ui
    if (current == null) {
        Column(Modifier.fillMaxSize().padding(padding), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            CircularProgressIndicator(color = MonarchColors.Rim)
        }
        return
    }

    val status = current.status
    LazyColumn(
        modifier = Modifier.fillMaxSize().testTag("status_list"),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 12.dp, bottom = padding.calculateBottomPadding() + 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item { HeaderPanel(status, onOpenClasses) }
        item {
            SystemPanel(title = "Attributes") {
                status.stats.forEachIndexed { i, stat ->
                    if (i > 0) Spacer(Modifier.height(12.dp))
                    StatRow(stat)
                }
                Spacer(Modifier.height(10.dp))
                Text("Each stat can gain up to ${status.stats.first().dailyCap} XP per day.", style = MaterialTheme.typography.bodySmall, color = MonarchColors.TextDim)
            }
        }
        item { StreakPanel(status) }
        item {
            SystemPanel(title = "Daily quests", accent = MonarchColors.Gold) {
                current.quests.forEachIndexed { i, quest ->
                    if (i > 0) Spacer(Modifier.height(14.dp))
                    QuestRow(quest)
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SecondaryButton("Today", onOpenDaily, Modifier.weight(1f))
                SecondaryButton("Weekly recap", onOpenWeekly, Modifier.weight(1f))
                SecondaryButton(if (syncing) "Syncing…" else "Sync", { viewModel.sync() }, Modifier.weight(1f), enabled = !syncing)
            }
        }
    }
}

@Composable
private fun HeaderPanel(status: PlayerStatus, onOpenClasses: () -> Unit) {
    SystemPanel(title = "Status") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(status.name.uppercase(), style = MaterialTheme.typography.titleLarge, color = MonarchColors.Gold, modifier = Modifier.testTag("player_name"))
                Spacer(Modifier.height(2.dp))
                Text("${status.rank}-RANK HUNTER", style = MaterialTheme.typography.labelMedium, color = MonarchColors.Rim)
                TextButton(onClick = onOpenClasses, contentPadding = PaddingValues(0.dp)) {
                    Text(
                        status.playerClass?.let { "CLASS: ${it.displayName.uppercase()} ›" }
                            ?: if (status.level >= 10) "CHOOSE YOUR CLASS ›" else "CLASS: UNAWAKENED (LV 10) ›",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (status.playerClass == null && status.level >= 10) MonarchColors.Gold else MonarchColors.TextDim,
                    )
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("LEVEL", style = MaterialTheme.typography.labelSmall, color = MonarchColors.TextDim)
                Text(status.level.toString().padStart(2, '0'), style = MaterialTheme.typography.displaySmall, color = MonarchColors.Rim, modifier = Modifier.testTag("player_level"))
            }
        }
        Spacer(Modifier.height(10.dp))
        GlowBar(status.levelProgress, height = 10.dp)
        Spacer(Modifier.height(6.dp))
        Row {
            Text(
                if (status.isMaxLevel) "MAX LEVEL" else "XP ${status.xpIntoLevel} / ${status.xpForLevel}",
                style = MaterialTheme.typography.labelSmall,
                color = MonarchColors.TextDim,
                modifier = Modifier.weight(1f).testTag("xp_label"),
            )
            Text("TODAY +${status.todayXp}", style = MaterialTheme.typography.labelSmall, color = MonarchColors.Success)
        }
    }
}

@Composable
private fun StreakPanel(status: PlayerStatus) {
    val streak = status.streak
    SystemPanel(title = "Streak", accent = if (streak.current >= 7) MonarchColors.Gold else MonarchColors.Rim) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("${streak.current} DAY${if (streak.current == 1) "" else "S"}", style = MaterialTheme.typography.headlineSmall, color = MonarchColors.Text)
                Text("Best ${streak.longest}", style = MaterialTheme.typography.labelSmall, color = MonarchColors.TextDim)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("SHIELDS " + "◆".repeat(streak.shields) + "◇".repeat((2 - streak.shields).coerceAtLeast(0)), style = MaterialTheme.typography.labelMedium, color = MonarchColors.Rim)
                Spacer(Modifier.width(4.dp))
                Text(
                    when {
                        streak.todayIsRestDay && streak.todayPending -> "Rest day. Your streak is safe."
                        streak.todayPending -> "Earn 20 XP today to extend it."
                        else -> "Today secured ✔"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (streak.todayPending) MonarchColors.TextDim else MonarchColors.Success,
                )
            }
        }
        if (streak.shieldsUsed > 0) {
            Spacer(Modifier.height(6.dp))
            Text("Shields have protected your streak ${streak.shieldsUsed}×. Missing a day never costs XP.", style = MaterialTheme.typography.bodySmall, color = MonarchColors.TextDim)
        }
    }
}
