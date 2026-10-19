package com.antigravity.monarch.data.sources

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import com.antigravity.monarch.data.health.HealthConnectDataSource
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Whether a source app is installed, and whether its data is actually reaching Health Connect. */
data class SourceState(val app: SourceApp, val installed: Boolean, val sendingData: Boolean)

@Singleton
class SourceStatusReader @Inject constructor(
    @ApplicationContext private val context: Context,
    private val health: HealthConnectDataSource,
) {
    suspend fun read(): List<SourceState> {
        val origins = runCatching { health.recentOrigins() }.getOrDefault(emptySet())
        return SourceApp.entries.map { app -> SourceState(app, isInstalled(app.packageName), app.packageName in origins) }
    }

    private fun isInstalled(packageName: String): Boolean = try {
        context.packageManager.getPackageInfo(packageName, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    /** Opens the app if installed, otherwise its Play Store page. */
    fun openIntent(app: SourceApp): Intent =
        context.packageManager.getLaunchIntentForPackage(app.packageName)
            ?: Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=${app.packageName}"))

    /** Health Connect's own screen, where per-app sharing can be reviewed. */
    fun healthConnectSettingsIntent(): Intent = Intent("androidx.health.ACTION_HEALTH_CONNECT_SETTINGS")
}
