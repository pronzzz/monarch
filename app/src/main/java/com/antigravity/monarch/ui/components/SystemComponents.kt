package com.antigravity.monarch.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.antigravity.monarch.domain.model.HEALTH_DISCLAIMER
import com.antigravity.monarch.domain.model.Quest
import com.antigravity.monarch.domain.model.StatSnapshot
import com.antigravity.monarch.ui.theme.MonarchColors
import com.antigravity.monarch.ui.theme.statColor

/** Bordered translucent panel with a subtle top glow: the "status window" look. */
@Composable
fun SystemPanel(
    modifier: Modifier = Modifier,
    title: String? = null,
    accent: Color = MonarchColors.Rim,
    contentPadding: Dp = 16.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Brush.verticalGradient(listOf(MonarchColors.PanelHigh, MonarchColors.Panel)))
            .border(1.dp, accent.copy(alpha = 0.45f), shape)
            .padding(contentPadding),
    ) {
        if (title != null) {
            SectionLabel(title, color = accent)
            Spacer(Modifier.height(10.dp))
        }
        content()
    }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier, color: Color = MonarchColors.Rim) {
    Text("[ ${text.uppercase()} ]", modifier = modifier, style = MaterialTheme.typography.titleSmall, color = color)
}

/** Animated progress bar used for XP, stats and quests. */
@Composable
fun GlowBar(
    progress: Float,
    modifier: Modifier = Modifier,
    color: Color = MonarchColors.Rim,
    height: Dp = 8.dp,
    secondary: Color = MonarchColors.Accent,
) {
    val animated by animateFloatAsState(progress.coerceIn(0f, 1f), animationSpec = tween(700), label = "bar")
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(height / 2))
            .background(MonarchColors.Track),
    ) {
        Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(animated)
                .background(Brush.horizontalGradient(listOf(secondary, color))),
        )
    }
}

@Composable
fun StatRow(stat: StatSnapshot, modifier: Modifier = Modifier) {
    val color = statColor(stat.type)
    Column(modifier.semantics(mergeDescendants = true) {
        contentDescription = "${stat.type.displayName} ${stat.value}, ${stat.todayXp} of ${stat.dailyCap} XP today"
    }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).background(color, CircleShape))
            Spacer(Modifier.width(8.dp))
            Text(stat.type.shortName, style = MaterialTheme.typography.labelLarge, color = color, modifier = Modifier.width(44.dp))
            Text(stat.type.displayName, style = MaterialTheme.typography.bodySmall, color = MonarchColors.TextDim, modifier = Modifier.weight(1f))
            if (stat.todayXp > 0) {
                Text(
                    if (stat.capReached) "CAP" else "+${stat.todayXp}",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (stat.capReached) MonarchColors.Warning else MonarchColors.Success,
                )
                Spacer(Modifier.width(10.dp))
            }
            Text(stat.value.toString(), style = MaterialTheme.typography.titleLarge, color = MonarchColors.Text)
        }
        Spacer(Modifier.height(4.dp))
        GlowBar(stat.progressToNext, color = color, secondary = color.copy(alpha = 0.5f), height = 4.dp)
    }
}

@Composable
fun QuestRow(quest: Quest, modifier: Modifier = Modifier) {
    Column(modifier.semantics(mergeDescendants = true) {}) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (quest.completed) "✔" else "◇",
                color = if (quest.completed) MonarchColors.Success else MonarchColors.Rim,
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(quest.title, style = MaterialTheme.typography.titleMedium, color = if (quest.completed) MonarchColors.TextDim else MonarchColors.Text)
                Text(quest.description, style = MaterialTheme.typography.bodySmall, color = MonarchColors.TextDim)
            }
            Text("+${quest.xpReward} XP", style = MaterialTheme.typography.labelMedium, color = MonarchColors.Gold)
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            GlowBar(quest.fraction, Modifier.weight(1f), color = if (quest.completed) MonarchColors.Success else MonarchColors.Rim, height = 5.dp)
            Spacer(Modifier.width(8.dp))
            Text(
                "${quest.progress.coerceAtMost(quest.target)}/${quest.target} ${quest.unit}",
                style = MaterialTheme.typography.labelSmall,
                color = MonarchColors.TextDim,
            )
        }
    }
}

@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, loading: Boolean = false) {
    Button(
        onClick = onClick,
        enabled = enabled && !loading,
        modifier = modifier.fillMaxWidth().height(52.dp),
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(containerColor = MonarchColors.Rim, contentColor = MonarchColors.Void),
    ) {
        if (loading) {
            CircularProgressIndicator(Modifier.size(20.dp), color = MonarchColors.Void, strokeWidth = 2.dp)
        } else {
            Text(text.uppercase(), style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(48.dp),
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = MonarchColors.Rim),
        border = androidx.compose.foundation.BorderStroke(1.dp, MonarchColors.RimDim),
    ) {
        Text(text.uppercase(), style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center)
    }
}

/** Single-select chips that wrap onto multiple lines. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun <T> ChoiceChips(
    options: List<T>,
    selected: Set<T>,
    label: (T) -> String,
    onToggle: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { option ->
            val isSelected = option in selected
            val shape = RoundedCornerShape(10.dp)
            Text(
                label(option),
                style = MaterialTheme.typography.labelMedium,
                color = if (isSelected) MonarchColors.Void else MonarchColors.Text,
                modifier = Modifier
                    .clip(shape)
                    .background(if (isSelected) MonarchColors.Rim else MonarchColors.PanelHigh)
                    .border(1.dp, if (isSelected) MonarchColors.Rim else MonarchColors.RimDim, shape)
                    .clickable(role = Role.Checkbox) { onToggle(option) }
                    .semantics { this.selected = isSelected }
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            )
        }
    }
}

/** Large selectable card (goals, classes). */
@Composable
fun OptionCard(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    trailing: String? = null,
) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (selected) MonarchColors.Rim.copy(alpha = 0.12f) else MonarchColors.PanelHigh)
            .border(if (selected) 2.dp else 1.dp, if (selected) MonarchColors.Rim else MonarchColors.RimDim, shape)
            .clickable(enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .semantics { this.selected = selected }
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = if (enabled) MonarchColors.Text else MonarchColors.TextDim)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MonarchColors.TextDim)
        }
        if (trailing != null) {
            Spacer(Modifier.width(8.dp))
            Text(trailing, style = MaterialTheme.typography.labelMedium, color = if (enabled) MonarchColors.Gold else MonarchColors.TextDim)
        }
    }
}

@Composable
fun DisclaimerText(modifier: Modifier = Modifier) {
    Text(
        "⚠ $HEALTH_DISCLAIMER",
        modifier = modifier,
        style = MaterialTheme.typography.bodySmall,
        color = MonarchColors.TextDim,
    )
}

@Composable
fun KeyValue(label: String, value: String, modifier: Modifier = Modifier, valueColor: Color = MonarchColors.Text) {
    Row(modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MonarchColors.TextDim, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.labelLarge, color = valueColor)
    }
}
