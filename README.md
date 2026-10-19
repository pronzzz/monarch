# Monarch

A gamified fitness app for Android. Workouts, steps, sleep, meals and study turn into XP, five
RPG stats, levels 1–100, classes, quests and achievements. The look is a Solo-Leveling-style
"status window", and a home-screen widget works as a passive HUD.

Kotlin · Jetpack Compose · MVVM + Clean Architecture · Hilt · Room · DataStore · Health Connect ·
Glance · WorkManager. Offline-first: everything works without an account or network.

## Build & run

Requirements: JDK 17+, Android SDK with platform 37 installed.

```bash
./gradlew :app:installDebug
```

Optional secrets go in `local.properties` (never committed). Leaving them out disables that
feature at runtime; the build doesn't fail.

```properties
GEMINI_API_KEY=...          # cloud AI planner (falls back to the on-device planner without it)
GEMINI_MODEL=gemini-2.5-flash
GOOGLE_WEB_CLIENT_ID=...    # Google Sign-In (OAuth "Web" client; register the app SHA-1 on an Android client)
```

## Tests

```bash
./gradlew :app:testDebugUnitTest            # 84 JVM tests: engine math, services, LLM sanitising
./gradlew :app:connectedDebugAndroidTest    # 8 device tests: Room, calendar, DataStore, end-to-end UI
```

The end-to-end UI test drives the real app through onboarding, plan reveal, logging a workout,
the level-up overlay, achievements, class lock and the reset flow. It **wipes app data on the
device it runs on**.

## Architecture

```
domain/            pure Kotlin, no Android
  model/           stats, classes, profile, plan, quests, achievements
  engine/          LevelCurve, StatScaling, XpRules/XpAllocator, StreakCalculator, QuestEngine,
                   AchievementRules, DietCalculator, RuleBasedPlanGenerator, PlanAdaptationCalculator
  repository/      interfaces
  usecase/         ProgressionService, PlanService, HealthSyncService
data/              Room, DataStore, Health Connect, CalendarContract, Gemini client, Credential Manager
ui/                Compose screens + ViewModels, theme, components, navigation
widget/            Glance home-screen widget
work/              periodic sync worker, optional daily quest notice
```

**The XP ledger is the single source of truth.** Every award is a row keyed by an idempotency
key (`workout:42`, `steps:2026-10-20`, `quest:2026-10-20:move`). Level, stats and streak are
always derived from it and never stored, so they can't drift. Each mutation runs in one Room
transaction, which keeps concurrent logs from exceeding the daily caps (covered by a device test).

## Game rules (the math)

| System | Rule |
|---|---|
| Level curve | XP to reach level L = `50·(L−1)^1.8`. Level 2 = 50 XP, 10 ≈ 2.6k, 50 ≈ 55k, 100 ≈ 195k |
| Stat value | `10 + floor(√(statXP/10))`: each point costs more than the last |
| Workouts | 1 XP/min × intensity (0.8/1.0/1.25). Minutes past 60 count half, past 120 count zero, under 5 count zero. Split across stats by workout type |
| Steps | `60·(1−e^(−steps/10000))` Endurance XP (≈38 at 10k, never over 60) |
| Sleep | 7–9 h = 30 Health XP, 6–10 h = 20, 5–6 h = 10, otherwise 0 |
| Habits | 8 XP/meal (max 4), 2 XP/glass (max 10), 0.8 XP/min reading or study (max 120 min) |
| Anti-farming | Hard cap of 150 XP per stat per day, after class bonuses |
| Classes | Warrior/Runner/Scholar at Lv 10, Monk at Lv 20. +10% primary and +5% secondary stat XP. 7-day cooldown on switching |
| Streaks | A day is active at 20+ activity XP. Rest days are neutral. A missed training day uses a shield (1 earned per 7 active days, max 2). No XP is ever removed |
| Quests | 5 deterministic daily quests (steps, training/recovery, meals, water, mind) plus a perfect-day bonus |
| Plan adaptation | Over the last 14 days: ≥85% of planned sessions → progress, <50% → deload, else hold. Moved sessions still count |

## PRD coverage

| Phase | Status |
|---|---|
| 1 Foundation | ✅ Compose/Hilt/Room, Health Connect (steps, workouts, sleep, active calories), modular stat engine. Google Sign-In is implemented but needs `GOOGLE_WEB_CLIENT_ID`; guest mode is the default |
| 2 Onboarding & AI | ✅ All PRD fields plus training days and time. On-device planner always works; Gemini planner optional with strict JSON sanitising. Diet numbers are always deterministic. Disclaimers shown and acknowledged |
| 2 Calendar | ✅ via the device calendar (CalendarContract). Events land in the phone's Google calendar and Android syncs them to Google Calendar, so no OAuth server is needed. Falls back to a local "Monarch" calendar |
| 3 Gamification | ✅ XP rules, level curve, non-linear stats, classes, 18 achievements, daily caps, level-up animation |
| 4 Tracking | ✅ Auto sync (on open + hourly worker), manual logging with undo, type→stat mapping, daily summary, adaptive weekly plans |
| 5 Widget | ✅ Responsive sizes, minimal/detailed modes, live updates |
| 6 Retention | ✅ Streak shields, rest days, weekly recap, no XP loss, optional once-a-day quest notice |

## Known limitations

- Units are metric only.
- Google Sign-In and the Gemini planner are untested end-to-end because they need your credentials.
- Health Connect background reads (hourly worker while the app is closed) need the user to grant
  the optional background permission. Otherwise sync happens when the app opens.
- Database schema is version 1 (fresh `monarch.db`). Data from the previous prototype isn't migrated.
