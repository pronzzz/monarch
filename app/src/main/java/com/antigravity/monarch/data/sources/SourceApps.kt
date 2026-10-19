package com.antigravity.monarch.data.sources

/**
 * Apps that commonly feed Monarch. Most have no open API, so they reach Monarch through
 * Health Connect: the player turns on sharing in that app once, and Monarch reads it from there.
 * Strava additionally has a direct API connection (see [com.antigravity.monarch.data.strava]).
 */
enum class SourceApp(
    val displayName: String,
    val packageName: String,
    /** Where the player turns on Health Connect sharing. Menu names vary between app versions. */
    val setupSteps: String,
) {
    SAMSUNG_HEALTH(
        "Samsung Health",
        "com.sec.android.app.shealth",
        "In Samsung Health, open Settings › Health Connect and allow Steps, Exercise and Sleep to be shared.",
    ),
    ZEPP(
        "Zepp (Amazfit)",
        "com.huami.watch.hmwatchmanager",
        "In Zepp, open Profile and look for Health Connect under connected accounts or third-party apps, then allow Steps, Workouts and Sleep. Some older Amazfit models don't support it.",
    ),
    ZEPP_LIFE(
        "Zepp Life (Mi Band)",
        "com.xiaomi.hm.health",
        "In Zepp Life, open Profile › connected accounts › Health Connect and allow Steps, Workouts and Sleep.",
    ),
    STRAVA(
        "Strava",
        "com.strava",
        "Connect Strava directly below (recommended), or allow Exercise sharing in Strava's Health Connect settings.",
    ),
    GOOGLE_FIT("Google Fit", "com.google.android.apps.fitness", "In Google Fit, open Profile › Settings and turn on Health Connect sync."),
    FITBIT("Fitbit", "com.fitbit.FitbitMobile", "In Fitbit, open your profile and turn on Health Connect sharing."),
    ;

    companion object {
        fun forPackage(packageName: String?): SourceApp? = entries.firstOrNull { it.packageName == packageName }

        /** Friendly name for a Health Connect data origin, falling back to the package name. */
        fun labelFor(packageName: String?): String? = packageName?.let { forPackage(it)?.displayName ?: it }
    }
}
