package com.antigravity.monarch.domain.usecase

import com.antigravity.monarch.domain.engine.AchievementRules
import com.antigravity.monarch.domain.engine.LevelCurve
import com.antigravity.monarch.domain.engine.PlayerStatusBuilder
import com.antigravity.monarch.domain.engine.QuestEngine
import com.antigravity.monarch.domain.engine.StatScaling
import com.antigravity.monarch.domain.engine.StreakCalculator
import com.antigravity.monarch.domain.engine.XpAllocator
import com.antigravity.monarch.domain.engine.WorkoutDeduplicator
import com.antigravity.monarch.domain.engine.XpRules
import com.antigravity.monarch.domain.model.AchievementSnapshot
import com.antigravity.monarch.domain.model.AwardResult
import com.antigravity.monarch.domain.model.DailyHealthMetrics
import com.antigravity.monarch.domain.model.HabitEntry
import com.antigravity.monarch.domain.model.HabitType
import com.antigravity.monarch.domain.model.Intensity
import com.antigravity.monarch.domain.model.LevelUp
import com.antigravity.monarch.domain.model.PlayerClass
import com.antigravity.monarch.domain.model.PlayerProfile
import com.antigravity.monarch.domain.model.PlayerStatus
import com.antigravity.monarch.domain.model.Quest
import com.antigravity.monarch.domain.model.StatType
import com.antigravity.monarch.domain.model.WorkoutEntry
import com.antigravity.monarch.domain.model.WorkoutType
import com.antigravity.monarch.domain.model.XpEvent
import com.antigravity.monarch.domain.model.XpKind
import com.antigravity.monarch.domain.repository.AchievementRepository
import com.antigravity.monarch.domain.repository.ActivityRepository
import com.antigravity.monarch.domain.repository.HealthDaySnapshot
import com.antigravity.monarch.domain.repository.PlanRepository
import com.antigravity.monarch.domain.repository.ProfileRepository
import com.antigravity.monarch.domain.repository.SettingsRepository
import com.antigravity.monarch.domain.repository.StatusListener
import com.antigravity.monarch.domain.repository.TimeProvider
import com.antigravity.monarch.domain.repository.TransactionRunner
import com.antigravity.monarch.domain.repository.XpLedgerRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import javax.inject.Singleton

sealed interface ClassChangeResult {
    data object Success : ClassChangeResult
    data class Locked(val requiredLevel: Int) : ClassChangeResult
    data class Cooldown(val daysLeft: Long) : ClassChangeResult
    data object NoProfile : ClassChangeResult
}

/**
 * The gamification core: turns logged activity into ledger entries, enforces caps and
 * idempotency, then settles quests and achievements. Everything that mutates progression goes
 * through here, inside a single transaction.
 */
@Singleton
class ProgressionService @Inject constructor(
    private val profiles: ProfileRepository,
    private val ledger: XpLedgerRepository,
    private val activities: ActivityRepository,
    private val achievements: AchievementRepository,
    private val plans: PlanRepository,
    private val settings: SettingsRepository,
    private val tx: TransactionRunner,
    private val time: TimeProvider,
    private val listener: StatusListener,
) {

    // ---------------------------------------------------------------- reads

    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeStatus(today: LocalDate): Flow<PlayerStatus> =
        profiles.observeProfile().filterNotNull().flatMapLatest { profile ->
            combine(
                ledger.observeTotalXp(),
                ledger.observeStatXp(),
                ledger.observeStatXpForDay(today),
                ledger.observeActivityXpByDay(),
                ledger.observeEventsBetween(today, today),
            ) { total, statXp, todayXp, byDay, todayEvents ->
                PlayerStatusBuilder.build(
                    profile, total, statXp, todayXp, byDay, today,
                    todayBonusXp = todayEvents.filter { it.stat == null }.sumOf { it.amount },
                )
            }
        }

    suspend fun currentStatus(): PlayerStatus? {
        val profile = profiles.getProfile() ?: return null
        val today = time.today()
        return PlayerStatusBuilder.build(
            profile, ledger.totalXp(), ledger.statXp(), ledger.statXpForDay(today), ledger.activityXpByDay(), today,
        )
    }

    suspend fun questsFor(date: LocalDate): List<Quest> {
        val profile = profiles.getProfile() ?: return emptyList()
        val level = LevelCurve.levelFor(ledger.totalXp())
        return QuestEngine.questsFor(date, profile, plans.getPlan()?.workout, level, activities.dayTotals(date))
    }

    // ---------------------------------------------------------------- writes

    /** Workouts may be logged for today or yesterday only, to keep backfilling honest. */
    fun isLoggableDate(date: LocalDate): Boolean {
        val today = time.today()
        return !date.isAfter(today) && !date.isBefore(today.minusDays(1))
    }

    suspend fun logWorkout(
        type: WorkoutType,
        title: String,
        minutes: Int,
        intensity: Intensity,
        notes: String = "",
        date: LocalDate = time.today(),
    ): AwardResult {
        require(minutes in 1..XpRules.MAX_SESSION_MINUTES) { "Duration must be 1-${XpRules.MAX_SESSION_MINUTES} minutes" }
        require(isLoggableDate(date)) { "Workouts can only be logged for today or yesterday" }
        return mutate(date) { profile ->
            val entry = WorkoutEntry(
                type = type, title = title.trim().take(60).ifBlank { type.displayName }, durationMinutes = minutes,
                intensity = intensity, date = date, startedAtEpochMs = time.nowEpochMs(), notes = notes.trim().take(280),
            )
            val id = activities.insertWorkout(entry)
            val result = award("workout:$id", XpRules.workoutXp(type, minutes, intensity), date, entry.title, profile)
            activities.setWorkoutXp(id, result.statXp)
            result
        }
    }

    suspend fun logHabit(type: HabitType, quantity: Int, date: LocalDate = time.today()): AwardResult {
        require(quantity > 0) { "Quantity must be positive" }
        require(isLoggableDate(date)) { "Habits can only be logged for today or yesterday" }
        return mutate(date) { profile ->
            val before = activities.habitQuantity(type, date)
            val raw = XpRules.habitXp(type, quantity, before)
            val id = activities.insertHabit(HabitEntry(type = type, quantity = quantity, date = date, loggedAtEpochMs = time.nowEpochMs()))
            val result = award("habit:$id", raw, date, type.displayName, profile)
            activities.setHabitXp(id, result.statXp)
            result
        }
    }

    /**
     * Undo a manual log; its XP is removed, achievements already earned are kept. Synced
     * sessions can't be deleted here (they would be re-imported on the next sync).
     */
    suspend fun deleteWorkout(id: Long): Boolean = tx.run {
        val entry = activities.workoutById(id) ?: return@run false
        if (entry.source.isSynced) return@run false
        ledger.deleteEvents("workout:$id")
        activities.deleteWorkout(id)
        true
    }.also { if (it) listener.onStatusChanged() }

    suspend fun deleteHabit(id: Long): Boolean = tx.run {
        activities.habitById(id) ?: return@run false
        ledger.deleteEvents("habit:$id")
        activities.deleteHabit(id)
        true
    }.also { if (it) listener.onStatusChanged() }

    /**
     * Apply one day of synced data. Idempotent: steps and sleep replace their earlier award for
     * that day; workouts are de-duplicated by source id and by time overlap across sources.
     */
    suspend fun applyHealthDay(snapshot: HealthDaySnapshot): AwardResult = mutate(snapshot.date) { profile ->
        val date = snapshot.date
        var result = AwardResult()
        if (snapshot.metricsAvailable) {
            activities.upsertHealthMetrics(DailyHealthMetrics(date, snapshot.steps, snapshot.sleepMinutes, snapshot.activeCalories))
            result += award("steps:$date", XpRules.stepsXp(snapshot.steps), date, "Steps", profile, replace = true)
            result += award("sleep:$date", XpRules.sleepXp(snapshot.sleepMinutes), date, "Sleep", profile, replace = true)
        }
        val existing = activities.workoutsOn(date).toMutableList()
        for (w in WorkoutDeduplicator.dedupeBatch(snapshot.workouts)) {
            if (activities.workoutByExternalId(w.externalId) != null || WorkoutDeduplicator.isDuplicate(w, existing)) continue
            val entry = WorkoutEntry(
                type = w.type, title = w.title.take(60), durationMinutes = w.durationMinutes.coerceIn(0, XpRules.MAX_SESSION_MINUTES),
                intensity = Intensity.MODERATE, date = date, startedAtEpochMs = w.startEpochMs,
                source = w.source, externalId = w.externalId, originApp = w.originApp,
            )
            val id = activities.insertWorkout(entry)
            existing += entry.copy(id = id)
            val r = award("workout:$id", XpRules.workoutXp(w.type, w.durationMinutes, Intensity.MODERATE), date, w.title, profile)
            activities.setWorkoutXp(id, r.statXp)
            result += r
        }
        result
    }

    /** Settle quests/achievements without a new action (app open, after sync, day rollover). */
    suspend fun refresh(): AwardResult = mutate(time.today()) { AwardResult() }

    suspend fun chooseClass(playerClass: PlayerClass): ClassChangeResult {
        val profile = profiles.getProfile() ?: return ClassChangeResult.NoProfile
        val level = LevelCurve.levelFor(ledger.totalXp())
        if (!playerClass.isUnlockedAt(level)) return ClassChangeResult.Locked(playerClass.unlockLevel)
        val today = time.today()
        val last = profile.classChangedOn
        if (profile.playerClass != null && last != null) {
            val elapsed = ChronoUnit.DAYS.between(last, today)
            if (elapsed < PlayerClass.CHANGE_COOLDOWN_DAYS && profile.playerClass != playerClass) {
                return ClassChangeResult.Cooldown(PlayerClass.CHANGE_COOLDOWN_DAYS - elapsed)
            }
        }
        profiles.saveProfile(profile.copy(playerClass = playerClass, classChangedOn = today))
        listener.onStatusChanged()
        return ClassChangeResult.Success
    }

    // ---------------------------------------------------------------- internals

    private suspend fun mutate(date: LocalDate, action: suspend (PlayerProfile) -> AwardResult): AwardResult {
        val result = tx.run {
            val profile = profiles.getProfile() ?: error("Complete onboarding first")
            val levelBefore = LevelCurve.levelFor(ledger.totalXp())
            var result = action(profile)
            result += settle(date, profile)
            if (date != time.today()) result += settle(time.today(), profile)
            val levelAfter = LevelCurve.levelFor(ledger.totalXp())
            if (levelAfter > levelBefore) {
                val unlocked = PlayerClass.entries.filter { it.unlockLevel in (levelBefore + 1)..levelAfter }
                result = result.copy(levelUp = LevelUp(levelBefore, levelAfter, unlocked))
            }
            result
        }
        listener.onStatusChanged()
        return result
    }

    private suspend fun award(
        sourceKey: String,
        raw: Map<StatType, Int>,
        date: LocalDate,
        label: String,
        profile: PlayerProfile,
        replace: Boolean = false,
    ): AwardResult {
        if (replace) ledger.deleteEvents(sourceKey) else if (ledger.hasEvent(sourceKey)) return AwardResult()
        val allocation = XpAllocator.allocate(raw, ledger.statXpForDay(date), profile.playerClass)
        val now = time.nowEpochMs()
        ledger.addEvents(allocation.awarded.map { (stat, amount) -> XpEvent(sourceKey, stat, amount, date, XpKind.ACTIVITY, label, now) })
        return AwardResult(xpByStat = allocation.awarded, cappedXp = allocation.capped)
    }

    private suspend fun bonus(sourceKey: String, amount: Int, date: LocalDate, kind: XpKind, label: String): Boolean {
        if (amount <= 0 || ledger.hasEvent(sourceKey)) return false
        ledger.addEvents(listOf(XpEvent(sourceKey, null, amount, date, kind, label, time.nowEpochMs())))
        return true
    }

    /** Award newly completed quests, then cascade achievements until nothing new unlocks. */
    private suspend fun settle(date: LocalDate, profile: PlayerProfile): AwardResult {
        var result = AwardResult()
        val level = LevelCurve.levelFor(ledger.totalXp())
        val quests = QuestEngine.questsFor(date, profile, plans.getPlan()?.workout, level, activities.dayTotals(date))
        val completed = quests.filter { it.completed && bonus(QuestEngine.sourceKey(date, it.id), it.xpReward, date, XpKind.QUEST, "Quest: ${it.title}") }
        if (completed.isNotEmpty()) result = result.copy(questsCompleted = completed, bonusXp = completed.sumOf { it.xpReward })
        if (quests.isNotEmpty() && quests.all { it.completed } &&
            bonus(QuestEngine.perfectDayKey(date), QuestEngine.PERFECT_DAY_BONUS, date, XpKind.QUEST, "Perfect day")
        ) {
            result = result.copy(bonusXp = result.bonusXp + QuestEngine.PERFECT_DAY_BONUS)
        }

        repeat(MAX_ACHIEVEMENT_PASSES) {
            val unlocked = achievements.unlocked()
            val fresh = AchievementRules.newlyEarned(achievementSnapshot(profile), unlocked)
            if (fresh.isEmpty()) return result
            val now = time.nowEpochMs()
            for (def in fresh) {
                achievements.unlock(def, now)
                bonus("achievement:${def.name}", def.xpReward, time.today(), XpKind.ACHIEVEMENT, "Achievement: ${def.title}")
            }
            result = result.copy(achievements = result.achievements + fresh, bonusXp = result.bonusXp + fresh.sumOf { it.xpReward })
        }
        return result
    }

    private suspend fun achievementSnapshot(profile: PlayerProfile): AchievementSnapshot {
        val statXp = ledger.statXp()
        val streak = StreakCalculator.compute(
            PlayerStatusBuilder.activeDays(ledger.activityXpByDay()), profile.restDays, time.today(), profile.createdOn,
        )
        return AchievementSnapshot(
            level = LevelCurve.levelFor(ledger.totalXp()),
            statValues = StatType.entries.associateWith { StatScaling.valueFor(statXp[it] ?: 0) },
            longestStreak = streak.longest,
            totalWorkouts = activities.workoutCount(),
            maxDailySteps = activities.maxDailySteps(),
            totalMindMinutes = activities.totalMindMinutes(),
            hasSynced = settings.current().hasSyncedHealth,
            perfectQuestDays = ledger.countSourceKeysLike("quest:%:perfect"),
        )
    }

    private companion object {
        const val MAX_ACHIEVEMENT_PASSES = 6
    }
}
