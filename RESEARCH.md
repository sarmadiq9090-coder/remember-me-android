# Research: Remember Me for Android

Written 2026-11-14 by the Remember Me lead session, before building v0.1.0. Three parts: what the
Windows app does (read from its source, read only), what Android allows, and patterns from other free
reminder apps. Decisions that follow from it are in [DECISIONS.md](DECISIONS.md).

## 1. The Windows app (Remember-Me, v1.7.0 + Unreleased)

Sources: `ARCHITECTURE.md`, `src/core/tasks.js`, `src/core/repeat.js`, `src/data/backups.js`,
`src/data/attachments.js`, `ui/lib/alert-format.js`, `tests/scheduling.test.js`.

**What a reminder holds** (`data.json`, `version: 1`): `id`, `title` (1-200 chars), `body` (up to 20,000),
`priority` normal/medium/high, `category` personal/work/study, `dueAt` (when it fires; snooze moves it),
`scheduleAt` (series anchor), `repeat`, rule fields (`repeatDays` 0=Sunday…6, `repeatEvery` 2-365 days,
`repeatFrom` the anchor whose time of day every occurrence uses), `remindBefore` (5, 10, 15, 30, 60 or
1440 minutes: a quieter early notice), `tags` (up to 10), `checklist` (up to 50 steps), `snoozeCount`,
`snoozedAt`, `completedAt`, `deletedAt` (30-day trash), `sourceId` (on completed copies of a repeat),
`attachments`, `createdAt`, `updatedAt`. The file also holds `notes` (sticky notes), `attachments` and
`settings`. `validateState` normalizes odd values instead of rejecting the file; an unknown `repeat`
becomes `none`.

**Repeat rules** (`nextOccurrence`): daily, weekly, chosen weekdays, every N days, monthly, yearly.
Each occurrence is a calendar day plus the time of day of `repeatFrom`, so a DST gap pushes one
occurrence an hour later and the next is back on time. Monthly/yearly count from `repeatFrom`, so the
31st clamps to short months without drifting (Jan 31 → Feb 28 → Mar 31). After a long gap the next
occurrence is the first one after now (one alert, not one per missed day). There is **no hourly
repeat** on Windows.

**Completing**: a one-time task gets `completedAt`. A repeat moves `dueAt`/`scheduleAt` to the next
occurrence and leaves a completed copy (`repeat: none`, `sourceId`). Undo puts both back. `skip` moves a
repeat without a copy. Every alert action carries `expectedDueAt`; a tap for an occurrence that has
already moved on changes nothing.

**Snooze**: six presets in `settings.snoozePresets`: in N minutes (whole days keep the wall-clock
time), today at HH:MM (until an hour before), tomorrow at HH:MM, next week, pick. Defaults: 10 min,
1 hour, today 18:00, tomorrow 09:00, next week, pick.

**"Stays on screen until dealt with"**: a frameless always-on-top alert window lists every due reminder,
oldest first, with Done, Skip (repeats) and the snooze presets. One beep when a reminder becomes due;
`remindAgain` (off, 2, 5, 15 minutes) beeps again, at most six times. Early notices are shown once per
task and due time; a notice moment at or before the task's last change is skipped.

**Data file and backups**: `%APPDATA%\Remember Me\data.json`, written with a `.bak` copy, a temp file,
fsync and rename; a damaged file is kept as `data.json.damaged-<time>`. **Export** writes a folder
`RememberMe-backup-<ISO time with : and . as ->` with `data.json` (written last), attachments and
`backup.json` (`app`, `kind`, `createdAt`, `algorithm: sha256`, `files: { "data.json": hex, … }`).
**Restore** picks a folder, verifies checksums (a backup without `backup.json` is accepted as
unverified), requires every listed attachment to exist with its size, keeps PC-specific settings, and
saves a safety copy first. There is also CSV/iCalendar import and export (`src/data/interchange.js`).

**Hijri**: the Windows app **removed** its Hijri option (`settings.hijri` is deleted on load). Digits
are always Western 0-9 (D-071 in the brain). Arabic month names follow the region: «تشرين الثاني» in
Iraq and the Levant, «نوفمبر» elsewhere (`settings.arabicMonths`).

**Categories, sounds**: three fixed categories; one sound setting (on/off) plus `remindAgain`.

## 2. Android constraints

Fact sheet checked against official pages on 2026-11-14 by a research helper; sources listed at the end.
"Unconfirmed" marks what no official page states.

| Topic | What matters for this app |
|---|---|
| `POST_NOTIFICATIONS` (13+) | Off on a fresh install until the app asks and the user allows. The app must ask itself (it targets 36). A denial blocks every channel. |
| Exact alarms | `SCHEDULE_EXACT_ALARM` is user-granted special access, **denied by default on Android 14 for new installs** and after a backup restore. `USE_EXACT_ALARM` (API 33+) is granted at install and cannot be revoked, but Google Play allows it only for alarm/timer/calendar-type apps. Revoking exact-alarm access kills the app and cancels its alarms; granting it sends `SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED`. |
| `setAlarmClock()` | Fires normally in Doze (the system wakes shortly before); still needs exact-alarm permission. `setExactAndAllowWhileIdle()` works in Doze but at most once per ~9 minutes per app. |
| App Standby | The restricted bucket allows **one alarm per day**. Apps holding `USE_EXACT_ALARM`, set to "unrestricted", or with an active widget are exempt; `SCHEDULE_EXACT_ALARM` alone is not. |
| Full-screen intent (14+) | Play revokes `USE_FULL_SCREEN_INTENT` at install for apps that are not alarm or calling apps; a sideloaded APK very probably keeps it (unconfirmed). Check with `canUseFullScreenIntent()`, fix via `ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT`. With the screen on, Android shows a heads-up notification instead of the screen. |
| Ongoing notifications (14+) | Users can swipe ongoing notifications away on an unlocked phone (not on the lock screen, not "Clear all"). Persistence needs re-posting. |
| Foreground service | Not needed to post a reminder from an alarm. `goAsync()` keeps a receiver alive about 10 s. |
| Boot re-arm | `BOOT_COMPLETED`, `LOCKED_BOOT_COMPLETED`, `TIME_SET`, `TIMEZONE_CHANGED`, `LOCALE_CHANGED` reach manifest receivers. `MY_PACKAGE_REPLACED` works in practice (sent only to the updated app) but is not on the official list (unconfirmed). The app must have been opened once and not force-stopped. |
| Battery optimisation | `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` shows a one-tap system dialog; Play forbids it unless the core function breaks without it, and reminder apps are not clearly listed. `ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS` (the list) is always allowed. |
| OEM battery killers | Xiaomi (autostart, battery saver "no restrictions", lock in recents), Samsung (never-sleeping apps, Unrestricted), Huawei (app launch: manage manually), Oppo/Realme (allow background, auto start), Vivo (high background power). dontkillmyapp.com pages are old (no HyperOS or HarmonyOS). Deep links to maker screens come only from community code and break between versions; fall back to the app's details page. |
| Backup | `dataExtractionRules` (12+) and `fullBackupContent` (11 and lower) are both needed. To block cloud backup but keep phone-to-phone transfer: exclude everything under `<cloud-backup>` and leave `<device-transfer>` its own rules. A restore does not bring back alarms or the exact-alarm grant. |
| Time zones and DST | A one-time reminder is an instant; a repeat is rebuilt from a local date and time each time (the Windows rule). The app must re-plan on `TIME_SET` and `TIMEZONE_CHANGED`. |

Sources (read 2026-11-14): developer.android.com pages on notification permission, alarm scheduling,
Android 14 exact-alarm and behaviour changes, Doze and App Standby, app standby buckets, FGS types,
BroadcastReceiver, implicit-broadcast exceptions, direct boot, Settings reference, Auto Backup;
source.android.com FSI limits; Play Console help 13986130 and 13392821; dontkillmyapp.com xiaomi,
samsung, huawei, oppo, vivo; tasks.org troubleshooting notifications.

## 3. Patterns from free reminder apps (patterns only, no branding)

Evidence was thin (some pages missing); these are patterns, not feature lists.

- **Snooze**: relative presets (minutes, later today, tomorrow morning, next week, pick a time). The
  Windows app already has this exact set; Android reuses it.
- **Persistence**: on Android 14 apps re-post a swiped reminder or repeat it until done; alarm-style
  full-screen screens for important ones.
- **"Allow background" onboarding**: ask for notifications in context; one screen that checks each
  permission with a button per item; a maker-specific guide (dontkillmyapp); recheck when the app
  returns to the foreground.
- **Notification actions**: Done, Snooze (a default preset), open. Heads-up with emphasized buttons on
  Android 13+.

## 4. What this means for v0.1.0

Store the Windows `data.json` itself; port `nextOccurrence` exactly and prove it against the Windows
code; one exact alarm (`setAlarmClock`) for the next moment, re-planned after every change and every
system event; a due reminder as an ongoing high-priority notification that comes back if swiped, plus
the reminder screen; an "allow background" screen; zip export/import in the Windows folder layout.
Hijri is not shown, because the Windows app removed it.
