package com.antigravity.monarch.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.antigravity.monarch.data.sources.SourceApp
import com.antigravity.monarch.data.sources.SourceState
import com.antigravity.monarch.data.strava.StravaStatus
import com.antigravity.monarch.domain.repository.HealthAvailability
import com.antigravity.monarch.ui.components.KeyValue
import com.antigravity.monarch.ui.components.SecondaryButton
import com.antigravity.monarch.ui.components.SystemPanel
import com.antigravity.monarch.ui.theme.MonarchColors

/**
 * Where data comes from. Samsung Health, Zepp, Google Fit and Fitbit have no open API, so they
 * reach Monarch through Health Connect once sharing is turned on in each app. Strava can also be
 * connected directly. Workouts seen by several sources are only counted once.
 */
@Composable
fun SyncSourcesPanel(
    links: LinkState,
    strava: StravaStatus,
    onLinkHealth: () -> Unit,
    onOpenHealthSettings: () -> Unit,
    onOpenApp: (SourceApp) -> Unit,
    onConnectStrava: () -> Unit,
    onDisconnectStrava: () -> Unit,
) {
    SystemPanel(title = "Sync sources", modifier = Modifier.testTag("sync_sources")) {
        KeyValue(
            "Health Connect",
            when {
                links.healthLinked -> "Linked"
                links.health == HealthAvailability.AVAILABLE -> "Not linked"
                links.health == HealthAvailability.NEEDS_UPDATE -> "Update required"
                else -> "Unavailable"
            },
            valueColor = if (links.healthLinked) MonarchColors.Success else MonarchColors.TextDim,
        )
        if (links.health == HealthAvailability.AVAILABLE) {
            Spacer(Modifier.height(8.dp))
            Row {
                SecondaryButton(if (links.healthLinked) "Permissions" else "Link", onLinkHealth, Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                SecondaryButton("Manage apps", onOpenHealthSettings, Modifier.weight(1f))
            }
        }
        Spacer(Modifier.height(14.dp))
        Text(
            "Apps that share into Health Connect. Turn sharing on once inside each app and Monarch picks it up on the next sync.",
            style = MaterialTheme.typography.bodySmall,
            color = MonarchColors.TextDim,
        )
        Spacer(Modifier.height(6.dp))
        links.sources.filter { it.app != SourceApp.STRAVA || it.installed }.forEach { state ->
            SourceRow(state, healthLinked = links.healthLinked, onOpen = { onOpenApp(state.app) })
        }

        Spacer(Modifier.height(14.dp))
        Text("[ STRAVA DIRECT ]", style = MaterialTheme.typography.titleSmall, color = MonarchColors.Rim)
        Spacer(Modifier.height(6.dp))
        when (strava) {
            StravaStatus.NotConfigured -> Text(
                "No Strava API credentials in this build. Strava activities still arrive if Strava shares to Health Connect.",
                style = MaterialTheme.typography.bodySmall,
                color = MonarchColors.TextDim,
            )
            StravaStatus.Disconnected -> {
                Text("Pull runs and rides straight from your Strava account.", style = MaterialTheme.typography.bodySmall, color = MonarchColors.TextDim)
                Spacer(Modifier.height(8.dp))
                SecondaryButton("Connect Strava", onConnectStrava, Modifier.fillMaxWidth().testTag("connect_strava"))
            }
            is StravaStatus.Connected -> {
                KeyValue("Account", strava.athleteName ?: "Connected", valueColor = MonarchColors.Success)
                SecondaryButton("Disconnect Strava", onDisconnectStrava, Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun SourceRow(state: SourceState, healthLinked: Boolean, onOpen: () -> Unit) {
    var expanded by rememberSaveable(state.app) { mutableStateOf(false) }
    val (label, color) = when {
        state.sendingData -> "Sending data" to MonarchColors.Success
        !state.installed -> "Not installed" to MonarchColors.TextDim
        !healthLinked -> "Link Health Connect" to MonarchColors.Warning
        else -> "Turn on sharing" to MonarchColors.Warning
    }
    Column(
        Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded }
            .padding(vertical = 6.dp)
            .testTag("source_${state.app.name}"),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(state.app.displayName, style = MaterialTheme.typography.bodyLarge, color = MonarchColors.Text, modifier = Modifier.weight(1f))
            Text(label, style = MaterialTheme.typography.labelMedium, color = color)
            Text(if (expanded) "  ▾" else "  ▸", color = MonarchColors.TextDim)
        }
        if (expanded) {
            Spacer(Modifier.height(4.dp))
            Text(state.app.setupSteps, style = MaterialTheme.typography.bodySmall, color = MonarchColors.TextDim)
            TextButton(onClick = onOpen) {
                Text(if (state.installed) "Open ${state.app.displayName}" else "Get it on Google Play", color = MonarchColors.Rim)
            }
        }
    }
}
