package com.antigravity.monarch.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.room.Room
import com.antigravity.monarch.data.ai.GeminiClient
import com.antigravity.monarch.data.ai.LlmClient
import com.antigravity.monarch.data.ai.SelectingPlanGenerator
import com.antigravity.monarch.data.calendar.DeviceCalendarSync
import com.antigravity.monarch.data.health.HealthConnectDataSource
import com.antigravity.monarch.data.local.AchievementDao
import com.antigravity.monarch.data.local.ActivityDao
import com.antigravity.monarch.data.local.CalendarEventDao
import com.antigravity.monarch.data.local.MonarchDatabase
import com.antigravity.monarch.data.local.PlanDao
import com.antigravity.monarch.data.local.ProfileDao
import com.antigravity.monarch.data.local.XpEventDao
import com.antigravity.monarch.data.repository.DataStoreSettingsRepository
import com.antigravity.monarch.data.repository.RoomAchievementRepository
import com.antigravity.monarch.data.repository.RoomActivityRepository
import com.antigravity.monarch.data.repository.RoomPlanRepository
import com.antigravity.monarch.data.repository.RoomProfileRepository
import com.antigravity.monarch.data.repository.RoomTransactionRunner
import com.antigravity.monarch.data.repository.RoomXpLedgerRepository
import com.antigravity.monarch.domain.engine.PlanGenerator
import com.antigravity.monarch.domain.repository.AchievementRepository
import com.antigravity.monarch.domain.repository.ActivityRepository
import com.antigravity.monarch.domain.repository.CalendarSync
import com.antigravity.monarch.domain.repository.HealthDataSource
import com.antigravity.monarch.domain.repository.PlanRepository
import com.antigravity.monarch.domain.repository.ProfileRepository
import com.antigravity.monarch.domain.repository.SettingsRepository
import com.antigravity.monarch.domain.repository.StatusListener
import com.antigravity.monarch.domain.repository.TimeProvider
import com.antigravity.monarch.domain.repository.TransactionRunner
import com.antigravity.monarch.domain.repository.XpLedgerRepository
import com.antigravity.monarch.widget.WidgetStatusListener
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import com.antigravity.monarch.data.net.Http
import com.antigravity.monarch.data.net.UrlConnectionHttp
import com.antigravity.monarch.data.strava.EncryptedStravaTokenStore
import com.antigravity.monarch.data.strava.StravaClient
import com.antigravity.monarch.data.strava.StravaConfig
import com.antigravity.monarch.data.strava.StravaTokenStore
import com.antigravity.monarch.domain.repository.WorkoutFeed
import kotlinx.serialization.json.Json
import java.time.LocalDate
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DataModule {

    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): MonarchDatabase =
        Room.databaseBuilder(context, MonarchDatabase::class.java, MonarchDatabase.NAME)
            .addMigrations(*MonarchDatabase.MIGRATIONS)
            .build()

    @Provides fun profileDao(db: MonarchDatabase): ProfileDao = db.profileDao()
    @Provides fun xpEventDao(db: MonarchDatabase): XpEventDao = db.xpEventDao()
    @Provides fun activityDao(db: MonarchDatabase): ActivityDao = db.activityDao()
    @Provides fun achievementDao(db: MonarchDatabase): AchievementDao = db.achievementDao()
    @Provides fun planDao(db: MonarchDatabase): PlanDao = db.planDao()
    @Provides fun calendarEventDao(db: MonarchDatabase): CalendarEventDao = db.calendarEventDao()

    @Provides
    @Singleton
    fun json(): Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Provides
    @Singleton
    fun preferences(@ApplicationContext context: Context): DataStore<Preferences> =
        PreferenceDataStoreFactory.create { context.preferencesDataStoreFile("monarch_settings") }

    @Provides
    fun time(): TimeProvider = SystemTimeProvider

    @Provides
    fun stravaConfig(): StravaConfig = StravaConfig.fromBuildConfig()
}

object SystemTimeProvider : TimeProvider {
    override fun today(): LocalDate = LocalDate.now()
    override fun nowEpochMs(): Long = System.currentTimeMillis()
}

@Module
@InstallIn(SingletonComponent::class)
abstract class BindingsModule {
    @Binds abstract fun profiles(impl: RoomProfileRepository): ProfileRepository
    @Binds abstract fun ledger(impl: RoomXpLedgerRepository): XpLedgerRepository
    @Binds abstract fun activities(impl: RoomActivityRepository): ActivityRepository
    @Binds abstract fun achievements(impl: RoomAchievementRepository): AchievementRepository
    @Binds abstract fun plans(impl: RoomPlanRepository): PlanRepository
    @Binds abstract fun settings(impl: DataStoreSettingsRepository): SettingsRepository
    @Binds abstract fun transactions(impl: RoomTransactionRunner): TransactionRunner
    @Binds abstract fun health(impl: HealthConnectDataSource): HealthDataSource
    @Binds abstract fun calendar(impl: DeviceCalendarSync): CalendarSync
    @Binds abstract fun llm(impl: GeminiClient): LlmClient
    @Binds abstract fun planGenerator(impl: SelectingPlanGenerator): PlanGenerator
    @Binds abstract fun statusListener(impl: WidgetStatusListener): StatusListener
    @Binds abstract fun http(impl: UrlConnectionHttp): Http
    @Binds abstract fun stravaTokens(impl: EncryptedStravaTokenStore): StravaTokenStore

    /** API-based workout sources merged into every sync. */
    @Binds @IntoSet abstract fun stravaFeed(impl: StravaClient): WorkoutFeed
}
