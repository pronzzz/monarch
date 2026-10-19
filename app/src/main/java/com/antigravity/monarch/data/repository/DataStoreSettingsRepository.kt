package com.antigravity.monarch.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.antigravity.monarch.domain.repository.AppSettings
import com.antigravity.monarch.domain.repository.SettingsRepository
import com.antigravity.monarch.domain.repository.WidgetMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.time.LocalTime
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DataStoreSettingsRepository @Inject constructor(
    private val store: DataStore<Preferences>,
) : SettingsRepository {

    private object Keys {
        val widgetMode = stringPreferencesKey("widget_mode")
        val remindersEnabled = booleanPreferencesKey("reminders_enabled")
        val reminderMinute = intPreferencesKey("reminder_minute")
        val calendarSync = booleanPreferencesKey("calendar_sync")
        val cloudAi = booleanPreferencesKey("cloud_ai")
        val hasSyncedHealth = booleanPreferencesKey("has_synced_health")
        val lastHealthSync = longPreferencesKey("last_health_sync")
        val lastSeenLevel = intPreferencesKey("last_seen_level")
        val email = stringPreferencesKey("signed_in_email")
        val displayName = stringPreferencesKey("signed_in_name")
    }

    override val settings: Flow<AppSettings> = store.data.map { it.toSettings() }

    override suspend fun current(): AppSettings = settings.first()

    override suspend fun update(transform: (AppSettings) -> AppSettings) {
        store.edit { prefs ->
            val next = transform(prefs.toSettings())
            prefs[Keys.widgetMode] = next.widgetMode.name
            prefs[Keys.remindersEnabled] = next.remindersEnabled
            prefs[Keys.reminderMinute] = next.reminderTime.hour * 60 + next.reminderTime.minute
            prefs[Keys.calendarSync] = next.calendarSyncEnabled
            prefs[Keys.cloudAi] = next.cloudAiEnabled
            prefs[Keys.hasSyncedHealth] = next.hasSyncedHealth
            prefs[Keys.lastHealthSync] = next.lastHealthSyncEpochMs
            prefs[Keys.lastSeenLevel] = next.lastSeenLevel
            if (next.signedInEmail != null) prefs[Keys.email] = next.signedInEmail else prefs.remove(Keys.email)
            if (next.signedInName != null) prefs[Keys.displayName] = next.signedInName else prefs.remove(Keys.displayName)
        }
    }

    private fun Preferences.toSettings(): AppSettings {
        val defaults = AppSettings()
        val minute = this[Keys.reminderMinute]
        return AppSettings(
            widgetMode = this[Keys.widgetMode]?.let { runCatching { WidgetMode.valueOf(it) }.getOrNull() } ?: defaults.widgetMode,
            remindersEnabled = this[Keys.remindersEnabled] ?: defaults.remindersEnabled,
            reminderTime = minute?.let { LocalTime.of(it / 60 % 24, it % 60) } ?: defaults.reminderTime,
            calendarSyncEnabled = this[Keys.calendarSync] ?: defaults.calendarSyncEnabled,
            cloudAiEnabled = this[Keys.cloudAi] ?: defaults.cloudAiEnabled,
            hasSyncedHealth = this[Keys.hasSyncedHealth] ?: defaults.hasSyncedHealth,
            lastHealthSyncEpochMs = this[Keys.lastHealthSync] ?: defaults.lastHealthSyncEpochMs,
            lastSeenLevel = this[Keys.lastSeenLevel] ?: defaults.lastSeenLevel,
            signedInEmail = this[Keys.email],
            signedInName = this[Keys.displayName],
        )
    }
}
