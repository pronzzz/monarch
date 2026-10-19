package com.antigravity.monarch

import android.content.ContentUris
import android.content.Context
import android.provider.CalendarContract
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import com.antigravity.monarch.data.calendar.DeviceCalendarSync
import com.antigravity.monarch.data.local.MonarchDatabase
import com.antigravity.monarch.data.repository.DataStoreSettingsRepository
import com.antigravity.monarch.domain.engine.PlanRequest
import com.antigravity.monarch.domain.engine.RuleBasedPlanGenerator
import com.antigravity.monarch.domain.model.FitnessGoal
import com.antigravity.monarch.domain.model.Gender
import com.antigravity.monarch.domain.model.Intensity
import com.antigravity.monarch.domain.model.PlanAdaptation
import com.antigravity.monarch.domain.model.PlayerProfile
import com.antigravity.monarch.domain.model.defaultWorkoutDays
import com.antigravity.monarch.domain.repository.WidgetMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.LocalDate
import java.time.LocalTime

@RunWith(AndroidJUnit4::class)
class PlatformIntegrationTest {

    @get:Rule
    val calendarPermissions: GrantPermissionRule = GrantPermissionRule.grant(*DeviceCalendarSync.PERMISSIONS)

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: MonarchDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, MonarchDatabase::class.java).build()
    }

    @After
    fun tearDown() = db.close()

    private fun countEvents(ids: List<Long>): Int = ids.count { id ->
        context.contentResolver.query(
            ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id),
            arrayOf(CalendarContract.Events._ID, CalendarContract.Events.DELETED), null, null, null,
        )?.use { c -> c.moveToFirst() && c.getInt(1) == 0 } ?: false
    }

    @Test
    fun calendarSyncCreatesReplacesAndRemovesEvents() = runBlocking {
        val sync = DeviceCalendarSync(context, db.calendarEventDao())
        assertTrue(sync.hasPermission())
        val tomorrow = LocalDate.now().plusDays(1)
        val profile = PlayerProfile(
            name = "Cal", age = 30, gender = Gender.OTHER, heightCm = 170, weightKg = 70.0, targetWeightKg = 68.0,
            goal = FitnessGoal.GENERAL_FITNESS, intensity = Intensity.MODERATE, workoutDays = defaultWorkoutDays(3), createdOn = tomorrow,
        )
        val plan = RuleBasedPlanGenerator.build(PlanRequest(profile, PlanAdaptation.NEUTRAL, tomorrow, 0)).workout

        val first = sync.syncPlan(plan, tomorrow, LocalTime.of(7, 30))
        assertTrue(first.error ?: "", first.ok)
        assertEquals(3, first.created) // three training days in any 7-day window
        val ids = db.calendarEventDao().upcoming(tomorrow.toEpochDay()).map { it.eventId }
        assertEquals(3, countEvents(ids))

        // Re-sync replaces rather than duplicating.
        val second = sync.syncPlan(plan, tomorrow, LocalTime.of(7, 30))
        assertEquals(3, second.created)
        assertEquals(3, second.removed)
        assertEquals(0, countEvents(ids))
        assertEquals(3, db.calendarEventDao().upcoming(tomorrow.toEpochDay()).size)

        assertEquals(3, sync.removeUpcoming(tomorrow))
        assertTrue(db.calendarEventDao().upcoming(tomorrow.toEpochDay()).isEmpty())
    }

    @Test
    fun settingsPersistAcrossInstances() = runBlocking {
        val file = File(context.cacheDir, "settings-test-${System.nanoTime()}.preferences_pb")
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        try {
            val store = PreferenceDataStoreFactory.create(scope = scope) { file }
            val repo = DataStoreSettingsRepository(store)
            assertEquals(WidgetMode.DETAILED, repo.current().widgetMode)
            assertNull(repo.current().signedInEmail)
            repo.update {
                it.copy(widgetMode = WidgetMode.MINIMAL, remindersEnabled = true, reminderTime = LocalTime.of(21, 15), lastSeenLevel = 7, signedInEmail = "p@example.com")
            }
            val s = repo.current()
            assertEquals(WidgetMode.MINIMAL, s.widgetMode)
            assertTrue(s.remindersEnabled)
            assertEquals(LocalTime.of(21, 15), s.reminderTime)
            assertEquals(7, s.lastSeenLevel)
            assertEquals("p@example.com", s.signedInEmail)
            repo.update { it.copy(signedInEmail = null) }
            assertNull(repo.current().signedInEmail)
        } finally {
            scope.cancel()
            file.delete()
        }
    }
}
