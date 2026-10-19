package com.antigravity.monarch.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.antigravity.monarch.domain.engine.LevelCurve
import com.antigravity.monarch.domain.model.PlayerClass
import com.antigravity.monarch.domain.repository.ProfileRepository
import com.antigravity.monarch.domain.repository.SettingsRepository
import com.antigravity.monarch.domain.repository.XpLedgerRepository
import com.antigravity.monarch.ui.components.LevelUpDialog
import com.antigravity.monarch.ui.onboarding.OnboardingScreen
import com.antigravity.monarch.ui.screens.ClassScreen
import com.antigravity.monarch.ui.screens.DailySummaryScreen
import com.antigravity.monarch.ui.screens.LogScreen
import com.antigravity.monarch.ui.screens.PlanScreen
import com.antigravity.monarch.ui.screens.RecordsScreen
import com.antigravity.monarch.ui.screens.SettingsScreen
import com.antigravity.monarch.ui.screens.StatusScreen
import com.antigravity.monarch.ui.screens.WeeklyRecapScreen
import com.antigravity.monarch.ui.theme.MonarchColors
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

object Routes {
    const val ONBOARDING = "onboarding"
    const val STATUS = "status"
    const val PLAN = "plan"
    const val LOG = "log"
    const val RECORDS = "records"
    const val SETTINGS = "settings"
    const val CLASSES = "classes"
    const val DAILY = "daily"
    const val WEEKLY = "weekly"
}

private data class Tab(val route: String, val label: String, val glyph: String)

private val TABS = listOf(
    Tab(Routes.STATUS, "Status", "◆"),
    Tab(Routes.PLAN, "Plan", "⚔"),
    Tab(Routes.LOG, "Log", "✚"),
    Tab(Routes.RECORDS, "Records", "★"),
)

private val TITLES = mapOf(
    Routes.STATUS to "STATUS WINDOW",
    Routes.PLAN to "QUEST PLAN",
    Routes.LOG to "LOG ACTIVITY",
    Routes.RECORDS to "RECORDS",
    Routes.SETTINGS to "SETTINGS",
    Routes.CLASSES to "CLASSES",
    Routes.DAILY to "DAILY SUMMARY",
    Routes.WEEKLY to "WEEKLY RECAP",
)

sealed interface StartState {
    data object Loading : StartState
    data class Ready(val onboarded: Boolean) : StartState
}

data class PendingLevelUp(val level: Int, val unlockedClasses: List<PlayerClass>)

@HiltViewModel
class RootViewModel @Inject constructor(
    profiles: ProfileRepository,
    ledger: XpLedgerRepository,
    private val settings: SettingsRepository,
) : ViewModel() {
    val start: StateFlow<StartState> = profiles.observeProfile()
        .map<_, StartState> { StartState.Ready(onboarded = it != null) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, StartState.Loading)

    /**
     * A level reached but not yet celebrated. Lives at the app shell so the moment plays on
     * whichever screen caused it, and also after background syncs (on next open).
     */
    val levelUp: StateFlow<PendingLevelUp?> = combine(profiles.observeProfile(), ledger.observeTotalXp(), settings.settings) { profile, xp, s ->
        val level = LevelCurve.levelFor(xp)
        if (profile == null || level <= s.lastSeenLevel) {
            null
        } else {
            PendingLevelUp(level, PlayerClass.entries.filter { it.unlockLevel in (s.lastSeenLevel + 1)..level })
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun acknowledge(level: Int) = viewModelScope.launch {
        settings.update { it.copy(lastSeenLevel = maxOf(it.lastSeenLevel, level)) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MonarchApp(root: RootViewModel = hiltViewModel()) {
    val start by root.start.collectAsStateWithLifecycle()
    // Decide the start route once. Later profile changes (onboarding saving it, a reset) must
    // not swap the graph's start destination underneath the user; those flows navigate explicitly.
    var startRoute by rememberSaveable { mutableStateOf<String?>(null) }
    val ready = start as? StartState.Ready
    if (startRoute == null && ready != null) startRoute = if (ready.onboarded) Routes.STATUS else Routes.ONBOARDING
    val initialRoute = startRoute
    if (initialRoute == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = MonarchColors.Rim) }
        return
    }
    val nav = rememberNavController()
    val snackbar = remember { SnackbarHostState() }
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route
    val isTab = TABS.any { it.route == route }
    val levelUp by root.levelUp.collectAsStateWithLifecycle()
    levelUp?.takeIf { route != null && route != Routes.ONBOARDING }?.let { pending ->
        LevelUpDialog(pending.level, pending.unlockedClasses) { root.acknowledge(pending.level) }
    }

    Scaffold(
        containerColor = MonarchColors.Void,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            if (route != null && route != Routes.ONBOARDING) {
                TopAppBar(
                    title = { Text(TITLES[route].orEmpty(), style = MaterialTheme.typography.titleSmall, color = MonarchColors.Rim) },
                    navigationIcon = {
                        if (!isTab) {
                            IconButton(onClick = { nav.popBackStack() }, modifier = Modifier.semantics { contentDescription = "Back" }) {
                                Text("‹", style = MaterialTheme.typography.headlineSmall, color = MonarchColors.Rim)
                            }
                        }
                    },
                    actions = {
                        if (isTab) {
                            IconButton(
                                onClick = { nav.navigate(Routes.SETTINGS) },
                                modifier = Modifier.testTag("open_settings").semantics { contentDescription = "Settings" },
                            ) {
                                Text("⚙", style = MaterialTheme.typography.titleLarge, color = MonarchColors.TextDim)
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MonarchColors.Void),
                )
            }
        },
        bottomBar = {
            if (isTab) {
                NavigationBar(containerColor = MonarchColors.Panel) {
                    TABS.forEach { tab ->
                        NavigationBarItem(
                            selected = route == tab.route,
                            onClick = { nav.navigateTab(tab.route) },
                            icon = { Text(tab.glyph, style = MaterialTheme.typography.titleLarge) },
                            label = { Text(tab.label, style = MaterialTheme.typography.labelSmall) },
                            modifier = Modifier.testTag("tab_${tab.route}"),
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = MonarchColors.Rim,
                                selectedTextColor = MonarchColors.Rim,
                                indicatorColor = MonarchColors.Rim.copy(alpha = 0.15f),
                                unselectedIconColor = MonarchColors.TextDim,
                                unselectedTextColor = MonarchColors.TextDim,
                            ),
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(nav, startDestination = initialRoute) {
            composable(Routes.ONBOARDING) {
                OnboardingScreen(onFinished = {
                    nav.navigate(Routes.STATUS) { popUpTo(Routes.ONBOARDING) { inclusive = true } }
                })
            }
            composable(Routes.STATUS) {
                StatusScreen(
                    padding, snackbar,
                    onOpenClasses = { nav.navigate(Routes.CLASSES) },
                    onOpenDaily = { nav.navigate(Routes.DAILY) },
                    onOpenWeekly = { nav.navigate(Routes.WEEKLY) },
                )
            }
            composable(Routes.PLAN) { PlanScreen(padding, snackbar) }
            composable(Routes.LOG) { LogScreen(padding, snackbar) }
            composable(Routes.RECORDS) {
                RecordsScreen(padding, onOpenWeekly = { nav.navigate(Routes.WEEKLY) }, onOpenDaily = { nav.navigate(Routes.DAILY) })
            }
            composable(Routes.SETTINGS) {
                SettingsScreen(padding, snackbar, onReset = {
                    nav.navigate(Routes.ONBOARDING) { popUpTo(0) { inclusive = true } }
                })
            }
            composable(Routes.CLASSES) { ClassScreen(padding, snackbar) }
            composable(Routes.DAILY) { DailySummaryScreen(padding) }
            composable(Routes.WEEKLY) { WeeklyRecapScreen(padding) }
        }
    }
}

private fun NavHostController.navigateTab(route: String) = navigate(route) {
    popUpTo(graph.findStartDestination().id) { saveState = true }
    launchSingleTop = true
    restoreState = true
}
