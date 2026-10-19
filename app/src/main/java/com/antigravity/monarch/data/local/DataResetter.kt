package com.antigravity.monarch.data.local

import com.antigravity.monarch.domain.repository.AppSettings
import com.antigravity.monarch.domain.repository.CalendarSync
import com.antigravity.monarch.domain.repository.SettingsRepository
import com.antigravity.monarch.domain.repository.StatusListener
import com.antigravity.monarch.domain.repository.TimeProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** "Start over": removes Monarch's calendar events, every table and all settings. */
@Singleton
class DataResetter @Inject constructor(
    private val db: MonarchDatabase,
    private val settings: SettingsRepository,
    private val calendar: CalendarSync,
    private val listener: StatusListener,
    private val time: TimeProvider,
) {
    suspend fun resetEverything() {
        runCatching { calendar.removeUpcoming(time.today()) }
        withContext(Dispatchers.IO) { db.clearAllTables() }
        settings.update { AppSettings() }
        listener.onStatusChanged()
    }
}
