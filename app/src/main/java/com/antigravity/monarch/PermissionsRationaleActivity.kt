package com.antigravity.monarch

import android.os.Bundle
import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.antigravity.monarch.ui.components.PrimaryButton
import com.antigravity.monarch.ui.components.SystemPanel
import com.antigravity.monarch.ui.theme.MonarchColors
import com.antigravity.monarch.ui.theme.MonarchTheme

/** Shown by Health Connect when the user asks why Monarch wants health data. */
class PermissionsRationaleActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Dark UI: always light system-bar icons, regardless of the device theme.
        enableEdgeToEdge(SystemBarStyle.dark(Color.TRANSPARENT), SystemBarStyle.dark(Color.TRANSPARENT))
        setContent {
            MonarchTheme {
                Column(
                    Modifier.fillMaxSize().background(MonarchColors.Void).safeDrawingPadding().padding(20.dp).verticalScroll(rememberScrollState()),
                ) {
                    SystemPanel(title = "Health data & privacy") {
                        Text(PRIVACY_TEXT, style = MaterialTheme.typography.bodyMedium, color = MonarchColors.Text)
                    }
                    Spacer(Modifier.height(16.dp))
                    PrimaryButton("Close", ::finish)
                }
            }
        }
    }

    companion object {
        const val PRIVACY_TEXT =
            "Monarch reads steps, exercise sessions, sleep sessions and active calories from Health Connect " +
                "to convert them into XP and stats.\n\n" +
                "• Access is read-only. Monarch never writes to or deletes your health data.\n" +
                "• Health data is processed and stored only on this device. It is never sold, shared, or used for ads.\n" +
                "• If you enable the optional cloud AI planner, only an anonymous profile (age, sex, height, weight, " +
                "goal and schedule) is sent to generate a workout plan. No Health Connect records are sent.\n" +
                "• You can revoke access at any time in Health Connect settings, and delete everything from " +
                "Monarch Settings › Reset all progress."
    }
}
