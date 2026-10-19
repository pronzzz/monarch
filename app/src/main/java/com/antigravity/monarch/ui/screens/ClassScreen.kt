package com.antigravity.monarch.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.antigravity.monarch.domain.model.PlayerClass
import com.antigravity.monarch.domain.model.PlayerStatus
import com.antigravity.monarch.domain.repository.TimeProvider
import com.antigravity.monarch.domain.usecase.ClassChangeResult
import com.antigravity.monarch.domain.usecase.ProgressionService
import com.antigravity.monarch.ui.components.OptionCard
import com.antigravity.monarch.ui.components.SystemPanel
import com.antigravity.monarch.ui.theme.MonarchColors
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ClassViewModel @Inject constructor(
    private val progression: ProgressionService,
    time: TimeProvider,
) : ViewModel() {
    val status: StateFlow<PlayerStatus?> = progression.observeStatus(time.today()).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    private val messages = Channel<String>(Channel.BUFFERED)
    val messageFlow = messages.receiveAsFlow()

    fun choose(playerClass: PlayerClass) = viewModelScope.launch {
        messages.send(
            when (val r = progression.chooseClass(playerClass)) {
                ClassChangeResult.Success -> "Class set: ${playerClass.displayName}. Focus stats gain bonus XP."
                is ClassChangeResult.Locked -> "Unlocks at level ${r.requiredLevel}."
                is ClassChangeResult.Cooldown -> "You can change class again in ${r.daysLeft} day(s)."
                ClassChangeResult.NoProfile -> "Finish onboarding first."
            },
        )
    }
}

@Composable
fun ClassScreen(padding: PaddingValues, snackbar: SnackbarHostState, viewModel: ClassViewModel = hiltViewModel()) {
    val status by viewModel.status.collectAsStateWithLifecycle()
    MessageEffect(viewModel.messageFlow, snackbar)
    val level = status?.level ?: 1
    LazyColumn(
        modifier = Modifier.fillMaxSize().testTag("class_list"),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 12.dp, bottom = padding.calculateBottomPadding() + 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            SystemPanel(title = "Class system") {
                Text(
                    "Classes unlock at level milestones. Your class adds +${((PlayerClass.PRIMARY_BONUS - 1) * 100).toInt()}% XP to its primary stat and " +
                        "+${((PlayerClass.SECONDARY_BONUS - 1) * 100).toInt()}% to its secondary stat. Daily caps still apply. " +
                        "You can switch once every ${PlayerClass.CHANGE_COOLDOWN_DAYS} days.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MonarchColors.Text,
                )
            }
        }
        items(PlayerClass.entries) { pc ->
            val unlocked = pc.isUnlockedAt(level)
            OptionCard(
                title = pc.displayName,
                subtitle = "${pc.tagline}\nPrimary ${pc.primaryStat.displayName} · Secondary ${pc.secondaryStat.displayName}",
                selected = status?.playerClass == pc,
                onClick = { viewModel.choose(pc) },
                trailing = if (unlocked) (if (status?.playerClass == pc) "ACTIVE" else "SELECT") else "LV ${pc.unlockLevel}",
                modifier = Modifier.testTag("class_${pc.name}"),
            )
        }
    }
}
