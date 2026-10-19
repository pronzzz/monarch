package com.antigravity.monarch.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.antigravity.monarch.domain.model.AwardResult
import com.antigravity.monarch.domain.model.PlayerClass
import com.antigravity.monarch.domain.model.Rank
import com.antigravity.monarch.ui.theme.MonarchColors

/** Full-screen "LEVEL UP" moment with a pulsing ring and a spring-scaled level number. */
@Composable
fun LevelUpDialog(level: Int, unlockedClasses: List<PlayerClass>, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val scale = remember { Animatable(0.3f) }
        val fade = remember { Animatable(0f) }
        LaunchedEffect(Unit) {
            fade.animateTo(1f, tween(300))
            scale.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow))
        }
        val pulse = rememberInfiniteTransition(label = "pulse")
        val ring by pulse.animateFloat(0.85f, 1.15f, infiniteRepeatable(tween(1100, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "ring")

        SystemPanel(
            modifier = Modifier.padding(24.dp).alpha(fade.value).testTag("level_up_dialog"),
            accent = MonarchColors.Gold,
        ) {
            Text("[ SYSTEM MESSAGE ]", style = MaterialTheme.typography.titleSmall, color = MonarchColors.Gold, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
            Spacer(Modifier.height(16.dp))
            Box(Modifier.fillMaxWidth().height(180.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.size(170.dp).scale(ring)) {
                    drawCircle(
                        brush = Brush.radialGradient(listOf(MonarchColors.Gold.copy(alpha = 0.35f), MonarchColors.Void.copy(alpha = 0f))),
                        radius = size.minDimension / 2,
                    )
                    drawCircle(color = MonarchColors.Rim, radius = size.minDimension / 2.3f, style = Stroke(width = 3f), center = Offset(size.width / 2, size.height / 2))
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.scale(scale.value)) {
                    Text("LEVEL UP", style = MaterialTheme.typography.titleLarge, color = MonarchColors.Gold)
                    Text(level.toString(), style = MaterialTheme.typography.displaySmall, color = MonarchColors.Text)
                    Text("${Rank.forLevel(level)}-RANK", style = MaterialTheme.typography.labelMedium, color = MonarchColors.Rim)
                }
            }
            Text(
                "Your power grows, Player. Keep going at a pace you can sustain.",
                style = MaterialTheme.typography.bodyMedium,
                color = MonarchColors.Text,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
            )
            if (unlockedClasses.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text(
                    "New class available: ${unlockedClasses.joinToString { it.displayName }}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MonarchColors.Rim,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                )
            }
            Spacer(Modifier.height(20.dp))
            PrimaryButton("Continue", onDismiss)
        }
    }
}

/** One-line summary of what an action earned, for snackbars. */
fun AwardResult.describe(): String {
    val parts = mutableListOf<String>()
    if (totalXp > 0) parts += "+$totalXp XP"
    if (xpByStat.isNotEmpty()) parts += xpByStat.entries.sortedByDescending { it.value }.joinToString(" ") { "${it.key.shortName}+${it.value}" }
    if (questsCompleted.isNotEmpty()) parts += "Quest cleared: ${questsCompleted.joinToString { it.title }}"
    if (achievements.isNotEmpty()) parts += "Achievement: ${achievements.joinToString { it.title }}"
    if (cappedXp > 0) parts += "daily cap reached (+$cappedXp not counted)"
    return if (parts.isEmpty()) "Logged. No XP this time (daily limit reached)." else parts.joinToString(" · ")
}
