# Changelog

All notable changes to Monarch. Versions follow [Semantic Versioning](https://semver.org).

## [1.0.0] — 2026-10-20

First public release. The prototype was rebuilt from the ground up against the product requirements.

### Gameplay
- Five stats (Strength, Agility, Endurance, Health, Intelligence) with non-linear growth.
- Levels 1–100 on a calibrated curve, with E→S ranks.
- XP from workouts, steps, sleep, meals, water, reading, study and meditation, with diminishing returns per session and a hard 150 XP per stat per day cap.
- Classes: Warrior, Runner, Scholar (level 10) and Monk (level 20), with focus-stat bonuses and a weekly switch cooldown.
- Five deterministic daily quests plus a perfect-day bonus, and 18 achievements.
- Streaks that respect rest days, with earnable streak shields and no XP loss, ever.
- Level-up animation on whichever screen you're on, daily summary and weekly recap.

### Plans
- Onboarding collects age, sex, height, weight, target weight, goal, intensity, training days and usual workout time.
- On-device weekly planner that always works offline, plus an optional Gemini planner with strict output sanitising. Nutrition numbers always come from the deterministic calculator.
- Plans adapt every week to how many planned sessions you actually completed.
- Calendar sync through the device calendar, which reaches Google Calendar when a Google account is present.

### Sync
- Health Connect: steps, exercise sessions, sleep and active calories. This covers Samsung Health, Zepp/Amazfit, Zepp Life, Google Fit and Fitbit once sharing is enabled in each app.
- Strava direct connection: OAuth with CSRF protection, automatic token refresh, revoke on disconnect, and Keystore-encrypted tokens.
- Cross-source de-duplication: the same session from two sources (e.g. a Zepp watch and Strava) counts once.
- Settings › Sync sources shows each app's status (Not installed / Turn on sharing / Sending data) with setup tips.
- Hourly background sync, plus a sync whenever the app opens.

### Widget
- Glance home-screen HUD with name, level, rank, class, XP bar and top three stats.
- Responsive sizes and minimal/detailed modes. Updates live after every change.

### Look & feel
- New minimal launcher icon: a geometric crown with a gold crest. Includes adaptive and themed (monochrome) variants and a notification icon.
- Dark "status window" design system.

### Engineering
- Kotlin 2.4, AGP 9.4, Jetpack Compose, Hilt, Room (schema v2 with migration), DataStore, WorkManager, Glance.
- Clean Architecture with a pure-Kotlin domain layer. The XP ledger is the single source of truth, and every mutation is transactional.
- R8-minified, signed release build (about 3.3 MB).
- 104 JVM tests and 10 instrumented tests, including an end-to-end UI journey.

### Security
- Removed two Google API keys that were hardcoded in the prototype. All secrets now come from `local.properties`.

[1.0.0]: https://github.com/pronzzz/monarch/releases/tag/v1.0.0
