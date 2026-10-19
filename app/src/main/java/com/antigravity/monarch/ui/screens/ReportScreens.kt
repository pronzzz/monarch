package com.antigravity.monarch.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.antigravity.monarch.domain.engine.LevelCurve
import com.antigravity.monarch.domain.engine.QuestEngine
import com.antigravity.monarch.domain.engine.RecapBuilder
import com.antigravity.monarch.domain.engine.StatScaling
import com.antigravity.monarch.domain.engine.WeeklyRecap
import com.antigravity.monarch.domain.model.AchievementCategory
import com.antigravity.monarch.domain.model.AchievementDef
import com.antigravity.monarch.domain.model.DayTotals
import com.antigravity.monarch.domain.model.Quest
import com.antigravity.monarch.domain.model.StatType
import com.antigravity.monarch.domain.model.XpEvent
import com.antigravity.monarch.domain.repository.AchievementRepository
import com.antigravity.monarch.domain.repository.ActivityRepository
import com.antigravity.monarch.domain.repository.PlanRepository
import com.antigravity.monarch.domain.repository.ProfileRepository
import com.antigravity.monarch.domain.repository.TimeProvider
import com.antigravity.monarch.domain.repository.XpLedgerRepository
import com.antigravity.monarch.ui.components.GlowBar
import com.antigravity.monarch.ui.components.KeyValue
import com.antigravity.monarch.ui.components.QuestRow
import com.antigravity.monarch.ui.components.SecondaryButton
import com.antigravity.monarch.ui.components.SystemPanel
import com.antigravity.monarch.ui.theme.MonarchColors
import com.antigravity.monarch.ui.theme.statColor
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

// ------------------------------------------------------------------ daily summary

data class StatGain(val stat: StatType, val xp: Int, val from: Int, val to: Int)

data class DailySummaryUi(
    val date: LocalDate,
    val totalXp: Int,
    val gains: List<StatGain>,
    val events: List<XpEvent>,
    val totals: DayTotals,
    val quests: List<Quest>,
)

@HiltViewModel
class DailySummaryViewModel @Inject constructor(
    ledger: XpLedgerRepository,
    activities: ActivityRepository,
    profiles: ProfileRepository,
    plans: PlanRepository,
    time: TimeProvider,
) : ViewModel() {
    private val today = time.today()

    val ui: StateFlow<DailySummaryUi?> = combine(
        ledger.observeEventsBetween(today, today),
        ledger.observeStatXp(),
        activities.observeDayTotals(today),
        profiles.observeProfile().filterNotNull(),
        combine(plans.observePlan(), ledger.observeTotalXp()) { p, xp -> p to xp },
    ) { events, statXp, totals, profile, (plan, totalXp) ->
        val todayByStat = events.filter { it.stat != null }.groupBy { it.stat!! }.mapValues { (_, v) -> v.sumOf { it.amount } }
        val gains = StatType.entries.mapNotNull { s ->
            val xp = todayByStat[s] ?: return@mapNotNull null
            val total = statXp[s] ?: 0L
            StatGain(s, xp, StatScaling.valueFor(total - xp), StatScaling.valueFor(total))
        }
        val level = LevelCurve.levelFor(totalXp)
        DailySummaryUi(today, events.sumOf { it.amount }, gains, events.reversed(), totals, QuestEngine.questsFor(today, profile, plan?.workout, level, totals))
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}

@Composable
fun DailySummaryScreen(padding: PaddingValues, viewModel: DailySummaryViewModel = hiltViewModel()) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val s = ui ?: return
    LazyColumn(
        modifier = Modifier.fillMaxSize().testTag("daily_list"),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 12.dp, bottom = padding.calculateBottomPadding() + 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            SystemPanel(title = "Daily summary · ${s.date}") {
                Text("+${s.totalXp} XP", style = MaterialTheme.typography.displaySmall, color = MonarchColors.Gold)
                Spacer(Modifier.height(10.dp))
                if (s.gains.isEmpty()) EmptyNote("No stat XP yet today. A walk or a quick quest is enough to start.")
                s.gains.forEach { g ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(g.stat.displayName, color = statColor(g.stat), style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                        Text("+${g.xp} XP", style = MaterialTheme.typography.labelMedium, color = MonarchColors.Text)
                        Spacer(Modifier.width(12.dp))
                        Text(if (g.to > g.from) "${g.from} → ${g.to} ▲" else "${g.to}", style = MaterialTheme.typography.labelMedium, color = if (g.to > g.from) MonarchColors.Success else MonarchColors.TextDim)
                    }
                }
            }
        }
        item {
            SystemPanel(title = "Today's data") {
                KeyValue("Steps", s.totals.steps.toString())
                KeyValue("Active calories", if (s.totals.activeCalories > 0) "${s.totals.activeCalories} kcal" else "—")
                KeyValue("Workouts", "${s.totals.workoutCount} (${s.totals.workoutMinutes} min)")
                KeyValue("Sleep", if (s.totals.sleepMinutes > 0) "%.1f h".format(s.totals.sleepMinutes / 60.0) else "—")
                KeyValue("Meals / water", "${s.totals.meals} / ${s.totals.waterGlasses}")
                KeyValue("Mind", "${s.totals.mindMinutes} min")
            }
        }
        item {
            SystemPanel(title = "Quests", accent = MonarchColors.Gold) {
                s.quests.forEachIndexed { i, q ->
                    if (i > 0) Spacer(Modifier.height(12.dp))
                    QuestRow(q)
                }
            }
        }
        item { Text("[ XP LOG ]", style = MaterialTheme.typography.titleSmall, color = MonarchColors.Rim) }
        items(s.events) { e ->
            Row(Modifier.fillMaxWidth()) {
                Text(e.label, style = MaterialTheme.typography.bodyMedium, color = MonarchColors.Text, modifier = Modifier.weight(1f))
                Text("${e.stat?.shortName ?: "BONUS"} +${e.amount}", style = MaterialTheme.typography.labelMedium, color = e.stat?.let(::statColor) ?: MonarchColors.Gold)
            }
        }
    }
}

// ------------------------------------------------------------------ weekly recap

@HiltViewModel
class WeeklyRecapViewModel @Inject constructor(
    ledger: XpLedgerRepository,
    activities: ActivityRepository,
    achievements: AchievementRepository,
    time: TimeProvider,
) : ViewModel() {
    private val today = time.today()
    private val start = today.minusDays(6)

    val recap: StateFlow<WeeklyRecap?> = combine(
        ledger.observeEventsBetween(start, today),
        activities.observeWorkoutsBetween(start, today),
        achievements.observeUnlocked(),
        flow { emit(ledger.totalXpBefore(start)) },
    ) { events, workouts, unlocked, before ->
        val dates = unlocked.mapValues { Instant.ofEpochMilli(it.value).atZone(ZoneId.systemDefault()).toLocalDate() }
        RecapBuilder.weekly(today, events, workouts, before, dates)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}

@Composable
fun WeeklyRecapScreen(padding: PaddingValues, viewModel: WeeklyRecapViewModel = hiltViewModel()) {
    val recap by viewModel.recap.collectAsStateWithLifecycle()
    val r = recap ?: return
    LazyColumn(
        modifier = Modifier.fillMaxSize().testTag("weekly_list"),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 12.dp, bottom = padding.calculateBottomPadding() + 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            SystemPanel(title = "Weekly recap", accent = MonarchColors.Gold) {
                Text("${r.start} → ${r.end}", style = MaterialTheme.typography.labelSmall, color = MonarchColors.TextDim)
                Spacer(Modifier.height(6.dp))
                Text(r.headline, style = MaterialTheme.typography.titleMedium, color = MonarchColors.Text)
                Spacer(Modifier.height(12.dp))
                KeyValue("Total XP", "+${r.totalXp}", valueColor = MonarchColors.Gold)
                KeyValue("Level", if (r.levelsGained > 0) "${r.levelStart} → ${r.levelEnd}" else "${r.levelEnd}")
                KeyValue("Active days", "${r.activeDays} / 7")
                KeyValue("Workouts", "${r.workouts} (${r.workoutMinutes} min)")
                r.topStat?.let { KeyValue("Top stat", it.displayName, valueColor = statColor(it)) }
                r.bestDay?.let { KeyValue("Best day", dayLong(it.dayOfWeek)) }
            }
        }
        item {
            SystemPanel(title = "XP per day") { XpBars(r.xpByDay) }
        }
        item {
            SystemPanel(title = "XP per stat") {
                val max = (r.xpByStat.values.maxOrNull() ?: 0).coerceAtLeast(1)
                r.xpByStat.forEach { (stat, xp) ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Text(stat.shortName, color = statColor(stat), style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(48.dp))
                        GlowBar(xp / max.toFloat(), Modifier.weight(1f), color = statColor(stat), secondary = statColor(stat).copy(alpha = 0.5f), height = 8.dp)
                        Text("$xp", style = MaterialTheme.typography.labelMedium, color = MonarchColors.Text, modifier = Modifier.width(56.dp), textAlign = TextAlign.End)
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
        if (r.achievements.isNotEmpty()) {
            item {
                SystemPanel(title = "Achievements this week") {
                    r.achievements.forEach { KeyValue(it.title, "+${it.xpReward} XP", valueColor = MonarchColors.Gold) }
                }
            }
        }
    }
}

@Composable
private fun XpBars(days: List<Pair<LocalDate, Int>>) {
    val max = (days.maxOfOrNull { it.second } ?: 0).coerceAtLeast(1)
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(120.dp)
            .semantics { contentDescription = days.joinToString { "${dayShort(it.first.dayOfWeek)} ${it.second} XP" } },
    ) {
        val slot = size.width / days.size
        val barWidth = slot * 0.55f
        days.forEachIndexed { i, (_, xp) ->
            val h = (xp / max.toFloat()) * (size.height - 4f)
            drawRoundRect(
                color = if (xp > 0) MonarchColors.Rim else MonarchColors.Track,
                topLeft = Offset(i * slot + (slot - barWidth) / 2, size.height - h.coerceAtLeast(4f)),
                size = Size(barWidth, h.coerceAtLeast(4f)),
                cornerRadius = CornerRadius(6f, 6f),
            )
        }
    }
    Row(Modifier.fillMaxWidth()) {
        days.forEach { (d, _) ->
            Text(dayShort(d.dayOfWeek), style = MaterialTheme.typography.labelSmall, color = MonarchColors.TextDim, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
        }
    }
}

// ------------------------------------------------------------------ records (achievements)

@HiltViewModel
class RecordsViewModel @Inject constructor(achievements: AchievementRepository) : ViewModel() {
    val unlocked: StateFlow<Map<AchievementDef, Long>> = achievements.observeUnlocked().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())
}

@Composable
fun RecordsScreen(padding: PaddingValues, onOpenWeekly: () -> Unit, onOpenDaily: () -> Unit, viewModel: RecordsViewModel = hiltViewModel()) {
    val unlocked by viewModel.unlocked.collectAsStateWithLifecycle()
    LazyColumn(
        modifier = Modifier.fillMaxSize().testTag("records_list"),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 12.dp, bottom = padding.calculateBottomPadding() + 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SecondaryButton("Today's summary", onOpenDaily, Modifier.weight(1f))
                SecondaryButton("Weekly recap", onOpenWeekly, Modifier.weight(1f))
            }
        }
        item {
            Text(
                "[ ACHIEVEMENTS ${unlocked.size}/${AchievementDef.entries.size} ]",
                style = MaterialTheme.typography.titleSmall,
                color = MonarchColors.Rim,
                modifier = Modifier.testTag("achievement_count"),
            )
        }
        AchievementCategory.entries.forEach { category ->
            val defs = AchievementDef.entries.filter { it.category == category }
            item(key = category.name) {
                SystemPanel(title = category.name.lowercase().replaceFirstChar { it.uppercase() }, contentPadding = 14.dp) {
                    defs.forEachIndexed { i, def ->
                        if (i > 0) Spacer(Modifier.height(10.dp))
                        val got = def in unlocked
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(if (got) "★" else "☆", color = if (got) MonarchColors.Gold else MonarchColors.TextDim, style = MaterialTheme.typography.titleLarge)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(def.title, style = MaterialTheme.typography.titleMedium, color = if (got) MonarchColors.Text else MonarchColors.TextDim)
                                Text(def.description, style = MaterialTheme.typography.bodySmall, color = MonarchColors.TextDim)
                            }
                            Text("+${def.xpReward}", style = MaterialTheme.typography.labelMedium, color = if (got) MonarchColors.Gold else MonarchColors.TextDim)
                        }
                    }
                }
            }
        }
    }
}
