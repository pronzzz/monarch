package com.antigravity.monarch.work

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.antigravity.monarch.MainActivity
import com.antigravity.monarch.R
import com.antigravity.monarch.domain.repository.ProfileRepository
import com.antigravity.monarch.domain.repository.SettingsRepository
import com.antigravity.monarch.domain.repository.StatusListener
import com.antigravity.monarch.domain.usecase.HealthSyncService
import com.antigravity.monarch.domain.usecase.PlanService
import com.antigravity.monarch.domain.usecase.ProgressionService
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** Periodic background sync: Health Connect -> XP, weekly plan rollover, quest settle, widget. */
@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val profiles: ProfileRepository,
    private val healthSync: HealthSyncService,
    private val plans: PlanService,
    private val progression: ProgressionService,
    private val listener: StatusListener,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        if (profiles.getProfile() == null) return Result.success()
        return try {
            healthSync.sync()
            plans.ensureCurrentPlan()
            progression.refresh()
            listener.onStatusChanged()
            Result.success()
        } catch (e: Exception) {
            if (runAttemptCount < 2) Result.retry() else Result.success()
        }
    }
}

/** Optional daily nudge, styled as a quest notice rather than a nag. */
@HiltWorker
class QuestReminderWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val settings: SettingsRepository,
    private val progression: ProgressionService,
    private val notifier: QuestNotifier,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        if (!settings.current().remindersEnabled) return Result.success()
        val quests = runCatching { progression.questsFor(java.time.LocalDate.now()) }.getOrDefault(emptyList())
        if (quests.isEmpty()) return Result.success()
        val open = quests.count { !it.completed }
        if (open > 0) notifier.notifyDailyQuests(open, quests.size)
        return Result.success()
    }
}

@Singleton
class QuestNotifier @Inject constructor(@ApplicationContext private val context: Context) {

    fun ensureChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "Daily quests", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "Optional once-a-day quest notice"
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    fun canNotify(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun notifyDailyQuests(open: Int, total: Int) {
        if (!canNotify()) return
        ensureChannel()
        val intent = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_monarch)
            .setContentTitle("[SYSTEM] Daily quests have arrived")
            .setContentText("$open of $total quests await, Player. Rest days count too.")
            .setContentIntent(intent)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
            // Permission revoked between the check and the post; nothing to do.
        }
    }

    companion object {
        const val CHANNEL_ID = "daily_quests"
        private const val NOTIFICATION_ID = 1001
    }
}

@Singleton
class WorkScheduler @Inject constructor(@ApplicationContext private val context: Context) {
    private val work get() = WorkManager.getInstance(context)

    fun schedulePeriodicSync() {
        val request = PeriodicWorkRequestBuilder<SyncWorker>(1, TimeUnit.HOURS).build()
        work.enqueueUniquePeriodicWork(SYNC_WORK, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    fun syncNow() {
        work.enqueueUniqueWork(SYNC_NOW_WORK, ExistingWorkPolicy.REPLACE, OneTimeWorkRequestBuilder<SyncWorker>().build())
    }

    fun scheduleReminder(enabled: Boolean, time: LocalTime) {
        if (!enabled) {
            work.cancelUniqueWork(REMINDER_WORK)
            return
        }
        val now = LocalDateTime.now()
        var next = now.toLocalDate().atTime(time)
        if (!next.isAfter(now)) next = next.plusDays(1)
        val request = PeriodicWorkRequestBuilder<QuestReminderWorker>(1, TimeUnit.DAYS)
            .setInitialDelay(Duration.between(now, next).toMinutes(), TimeUnit.MINUTES)
            .build()
        work.enqueueUniquePeriodicWork(REMINDER_WORK, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    fun cancelAll() {
        work.cancelUniqueWork(REMINDER_WORK)
    }

    companion object {
        const val SYNC_WORK = "monarch_sync"
        const val SYNC_NOW_WORK = "monarch_sync_now"
        const val REMINDER_WORK = "monarch_quest_reminder"
    }
}
