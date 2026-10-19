package com.antigravity.monarch.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.ImageProvider
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.LinearProgressIndicator
import androidx.glance.appwidget.SizeMode
import androidx.glance.action.actionStartActivity
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontFamily
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.antigravity.monarch.MainActivity
import com.antigravity.monarch.R
import com.antigravity.monarch.domain.model.PlayerStatus
import com.antigravity.monarch.domain.repository.ProfileRepository
import com.antigravity.monarch.domain.repository.SettingsRepository
import com.antigravity.monarch.domain.repository.StatusListener
import com.antigravity.monarch.domain.repository.WidgetMode
import com.antigravity.monarch.domain.usecase.ProgressionService
import com.antigravity.monarch.ui.theme.statColor
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

@EntryPoint
@InstallIn(SingletonComponent::class)
interface WidgetEntryPoint {
    fun progression(): ProgressionService
    fun settings(): SettingsRepository
    fun profiles(): ProfileRepository
}

private val Cyan = Color(0xFF00E5FF)
private val Gold = Color(0xFFFFC940)
private val TextMain = Color(0xFFE6F1FF)
private val TextDim = Color(0xFF7C8BA5)
private val Track = Color(0xFF1A2236)

/**
 * The status HUD for the home screen: name, level, rank, class, XP bar and top three stats.
 * Responsive: small cells show the essentials, larger cells (and "detailed" mode) add stats.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MonarchWidget : GlanceAppWidget() {

    override val sizeMode = SizeMode.Responsive(setOf(SMALL, WIDE, LARGE))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val entry = EntryPointAccessors.fromApplication(context.applicationContext, WidgetEntryPoint::class.java)
        val initialStatus = runCatching { entry.progression().currentStatus() }.getOrNull()
        val initialMode = runCatching { entry.settings().current().widgetMode }.getOrDefault(WidgetMode.DETAILED)
        // Glance keeps a session alive between updates and only recomposes it, so the content
        // must observe live data rather than a snapshot taken here.
        val live = entry.profiles().observeProfile().flatMapLatest { profile ->
            if (profile == null) flowOf(null) else entry.progression().observeStatus(LocalDate.now())
        }
        provideContent {
            val status by live.collectAsState(initialStatus)
            val mode by entry.settings().settings.map { it.widgetMode }.collectAsState(initialMode)
            Content(status, mode)
        }
    }

    @Composable
    private fun Content(status: PlayerStatus?, mode: WidgetMode) {
        val size = LocalSize.current
        Box(
            modifier = GlanceModifier
                .fillMaxSize()
                .background(ImageProvider(R.drawable.system_window_bg))
                .padding(12.dp)
                .clickable(actionStartActivity<MainActivity>()),
        ) {
            if (status == null) {
                Column {
                    Text("[ SYSTEM ]", style = TextStyle(color = ColorProvider(Cyan), fontSize = 11.sp, fontFamily = FontFamily.Monospace))
                    Text("Awaken to begin.", style = TextStyle(color = ColorProvider(TextMain), fontSize = 13.sp))
                }
                return@Box
            }
            val showStats = mode == WidgetMode.DETAILED && size.height >= LARGE.height
            val compact = size.width < WIDE.width
            Column(modifier = GlanceModifier.fillMaxSize()) {
                Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = GlanceModifier.defaultWeight()) {
                        Text(
                            status.name.uppercase(),
                            maxLines = 1,
                            style = TextStyle(color = ColorProvider(Gold), fontSize = 13.sp, fontWeight = FontWeight.Bold),
                        )
                        Text(
                            "${status.rank}-RANK · ${status.playerClass?.displayName?.uppercase() ?: "UNAWAKENED"}",
                            maxLines = 1,
                            style = TextStyle(color = ColorProvider(Cyan), fontSize = 10.sp, fontFamily = FontFamily.Monospace),
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("LV", style = TextStyle(color = ColorProvider(TextDim), fontSize = 9.sp))
                        Text(
                            status.level.toString().padStart(2, '0'),
                            style = TextStyle(color = ColorProvider(Cyan), fontSize = if (compact) 22.sp else 26.sp, fontWeight = FontWeight.Bold),
                        )
                    }
                }
                Spacer(GlanceModifier.height(6.dp))
                LinearProgressIndicator(
                    progress = status.levelProgress,
                    modifier = GlanceModifier.fillMaxWidth().height(6.dp),
                    color = ColorProvider(Cyan),
                    backgroundColor = ColorProvider(Track),
                )
                Spacer(GlanceModifier.height(3.dp))
                Text(
                    if (status.isMaxLevel) "MAX LEVEL" else "${status.xpIntoLevel} / ${status.xpForLevel} XP",
                    style = TextStyle(color = ColorProvider(TextDim), fontSize = 9.sp, fontFamily = FontFamily.Monospace),
                )
                if (showStats) {
                    Spacer(GlanceModifier.height(8.dp))
                    for (stat in status.topStats(3)) {
                        Row(modifier = GlanceModifier.fillMaxWidth().padding(vertical = 1.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(GlanceModifier.width(3.dp).height(11.dp).background(statColor(stat.type))) {}
                            Spacer(GlanceModifier.width(6.dp))
                            Text(stat.type.shortName, style = TextStyle(color = ColorProvider(statColor(stat.type)), fontSize = 11.sp, fontWeight = FontWeight.Bold))
                            Spacer(GlanceModifier.defaultWeight())
                            Text(stat.value.toString(), style = TextStyle(color = ColorProvider(TextMain), fontSize = 12.sp, fontWeight = FontWeight.Bold))
                        }
                    }
                    if (status.streak.current > 0) {
                        Spacer(GlanceModifier.height(4.dp))
                        Text("STREAK ${status.streak.current}d", style = TextStyle(color = ColorProvider(Gold), fontSize = 10.sp, fontFamily = FontFamily.Monospace))
                    }
                } else if (!compact && mode == WidgetMode.DETAILED) {
                    Spacer(GlanceModifier.height(6.dp))
                    Row(modifier = GlanceModifier.fillMaxWidth()) {
                        for (stat in status.topStats(3)) {
                            Text(
                                "${stat.type.shortName} ${stat.value}",
                                modifier = GlanceModifier.defaultWeight(),
                                style = TextStyle(color = ColorProvider(statColor(stat.type)), fontSize = 11.sp, fontWeight = FontWeight.Bold),
                            )
                        }
                    }
                }
            }
        }
    }

    companion object {
        val SMALL = DpSize(110.dp, 90.dp)
        val WIDE = DpSize(220.dp, 90.dp)
        val LARGE = DpSize(220.dp, 170.dp)
    }
}

class MonarchWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = MonarchWidget()
}

/** Pushes every progression change straight to placed widgets (near real-time HUD). */
@Singleton
class WidgetStatusListener @Inject constructor(
    @ApplicationContext private val context: Context,
) : StatusListener {
    override suspend fun onStatusChanged() {
        runCatching { MonarchWidget().updateAll(context) }
    }
}
