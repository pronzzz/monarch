package com.antigravity.monarch.data.calendar

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import com.antigravity.monarch.data.local.CalendarEventDao
import com.antigravity.monarch.data.local.CalendarEventEntity
import com.antigravity.monarch.domain.model.HEALTH_DISCLAIMER
import com.antigravity.monarch.domain.model.PlanDay
import com.antigravity.monarch.domain.model.WorkoutPlan
import com.antigravity.monarch.domain.repository.CalendarSync
import com.antigravity.monarch.domain.repository.CalendarSyncResult
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Writes planned sessions into the device calendar through CalendarContract.
 *
 * When the phone's Google account calendar is present, events land in it and Android's own
 * sync adapter pushes them to Google Calendar, so no OAuth client or server is needed. With
 * no Google account (e.g. an emulator) a local "Monarch" calendar is created instead.
 * Events Monarch created are tracked so a re-sync replaces them instead of duplicating.
 */
@Singleton
class DeviceCalendarSync @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: CalendarEventDao,
) : CalendarSync {

    override fun hasPermission(): Boolean = PERMISSIONS.all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    override suspend fun syncPlan(plan: WorkoutPlan, from: LocalDate, time: LocalTime, days: Int): CalendarSyncResult =
        withContext(Dispatchers.IO) {
            if (!hasPermission()) return@withContext CalendarSyncResult(0, 0, null, "Calendar permission not granted")
            try {
                val target = findWritableCalendar() ?: createLocalCalendar()
                    ?: return@withContext CalendarSyncResult(0, 0, null, "No writable calendar found")
                val removed = removeUpcomingInternal(from)
                val zone = ZoneId.systemDefault()
                var created = 0
                for (offset in 0 until days) {
                    val date = from.plusDays(offset.toLong())
                    val session = plan.dayFor(date.dayOfWeek) ?: continue
                    val start = date.atTime(time).atZone(zone)
                    if (start.toInstant().toEpochMilli() < System.currentTimeMillis()) continue
                    val values = ContentValues().apply {
                        put(CalendarContract.Events.CALENDAR_ID, target.id)
                        put(CalendarContract.Events.TITLE, "⚔ Monarch: ${session.title}")
                        put(CalendarContract.Events.DESCRIPTION, describe(session))
                        put(CalendarContract.Events.DTSTART, start.toInstant().toEpochMilli())
                        put(CalendarContract.Events.DTEND, start.plusMinutes(session.targetMinutes.toLong()).toInstant().toEpochMilli())
                        put(CalendarContract.Events.EVENT_TIMEZONE, zone.id)
                        put(CalendarContract.Events.HAS_ALARM, 0)
                    }
                    val uri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values) ?: continue
                    dao.insert(CalendarEventEntity(ContentUris.parseId(uri), target.id, date.toEpochDay()))
                    created++
                }
                CalendarSyncResult(created, removed, target.name)
            } catch (e: SecurityException) {
                CalendarSyncResult(0, 0, null, "Calendar permission not granted")
            } catch (e: Exception) {
                CalendarSyncResult(0, 0, null, e.message ?: "Calendar sync failed")
            }
        }

    override suspend fun removeUpcoming(from: LocalDate): Int = withContext(Dispatchers.IO) {
        if (!hasPermission()) 0 else removeUpcomingInternal(from)
    }

    private suspend fun removeUpcomingInternal(from: LocalDate): Int {
        var removed = 0
        for (event in dao.upcoming(from.toEpochDay())) {
            val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, event.eventId)
            removed += context.contentResolver.delete(uri, null, null)
            dao.delete(event.eventId)
        }
        return removed
    }

    private data class Target(val id: Long, val name: String)

    private fun findWritableCalendar(): Target? {
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.ACCOUNT_TYPE,
            CalendarContract.Calendars.IS_PRIMARY,
            CalendarContract.Calendars.ACCOUNT_NAME,
        )
        val selection = "${CalendarContract.Calendars.VISIBLE} = 1 AND ${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ?"
        val args = arrayOf(CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR.toString())
        val candidates = mutableListOf<Triple<Target, Boolean, Boolean>>()
        context.contentResolver.query(CalendarContract.Calendars.CONTENT_URI, projection, selection, args, null)?.use { c ->
            while (c.moveToNext()) {
                val target = Target(c.getLong(0), c.getString(1) ?: c.getString(4) ?: "Calendar")
                val isGoogle = c.getString(2) == GOOGLE_ACCOUNT_TYPE
                val isPrimary = c.getInt(3) == 1
                candidates += Triple(target, isGoogle, isPrimary)
            }
        }
        return candidates.sortedWith(compareByDescending<Triple<Target, Boolean, Boolean>> { it.second }.thenByDescending { it.third })
            .firstOrNull()?.first
    }

    private fun createLocalCalendar(): Target? {
        val uri = CalendarContract.Calendars.CONTENT_URI.buildUpon()
            .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
            .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, LOCAL_ACCOUNT)
            .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
            .build()
        val values = ContentValues().apply {
            put(CalendarContract.Calendars.ACCOUNT_NAME, LOCAL_ACCOUNT)
            put(CalendarContract.Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
            put(CalendarContract.Calendars.NAME, LOCAL_ACCOUNT)
            put(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, LOCAL_ACCOUNT)
            put(CalendarContract.Calendars.CALENDAR_COLOR, Color.rgb(0, 229, 255))
            put(CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL, CalendarContract.Calendars.CAL_ACCESS_OWNER)
            put(CalendarContract.Calendars.OWNER_ACCOUNT, LOCAL_ACCOUNT)
            put(CalendarContract.Calendars.VISIBLE, 1)
            put(CalendarContract.Calendars.SYNC_EVENTS, 1)
            put(CalendarContract.Calendars.CALENDAR_TIME_ZONE, ZoneId.systemDefault().id)
        }
        val created = context.contentResolver.insert(uri, values) ?: return null
        return Target(ContentUris.parseId(created), LOCAL_ACCOUNT)
    }

    private fun describe(day: PlanDay): String = buildString {
        appendLine("Quest from The System — ${day.workoutType.displayName}, ~${day.targetMinutes} min.")
        appendLine()
        for (exercise in day.exercises) {
            val dose = exercise.prescription()
            appendLine(if (dose.isBlank()) "• ${exercise.name}" else "• ${exercise.name}: $dose")
        }
        appendLine()
        append(HEALTH_DISCLAIMER)
    }

    companion object {
        val PERMISSIONS = arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
        private const val GOOGLE_ACCOUNT_TYPE = "com.google"
        private const val LOCAL_ACCOUNT = "Monarch"
    }
}
