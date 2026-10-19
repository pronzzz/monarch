package com.antigravity.monarch

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import com.antigravity.monarch.data.strava.StravaAuthResult
import com.antigravity.monarch.data.strava.StravaClient
import com.antigravity.monarch.ui.navigation.MonarchApp
import com.antigravity.monarch.ui.theme.MonarchTheme
import com.antigravity.monarch.work.WorkScheduler
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var strava: StravaClient
    @Inject lateinit var scheduler: WorkScheduler

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Dark UI: always light system-bar icons, regardless of the device theme.
        enableEdgeToEdge(SystemBarStyle.dark(Color.TRANSPARENT), SystemBarStyle.dark(Color.TRANSPARENT))
        setContent {
            MonarchTheme {
                MonarchApp()
            }
        }
        if (savedInstanceState == null) handleDeepLink(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleDeepLink(intent)
    }

    /** Strava redirects to monarch://localhost/strava?code=...&state=... after authorization. */
    private fun handleDeepLink(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme != "monarch" || data.host != "localhost" || data.path != "/strava") return
        setIntent(Intent(intent).setData(null))
        lifecycleScope.launch {
            val message = when (val result = strava.handleRedirect(data.encodedQuery)) {
                is StravaAuthResult.Connected -> {
                    scheduler.syncNow()
                    "Strava connected${result.athleteName?.let { " as $it" }.orEmpty()}. Syncing activities…"
                }
                is StravaAuthResult.Failed -> result.message
            }
            Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
        }
    }
}
