# Decisions: Remember Me for Android

Each decision was proposed by the lead session and reviewed on 2026-11-14 by a council of three:
**A** reliability of alarms, **B** privacy and security (OWASP MASVS-STORAGE/PRIVACY), **C** the owner's
advocate (a non-technical Arabic speaker who installs an APK and must never lose a reminder). Verdicts
are given as approve / amend; amendments are applied unless the text says otherwise. Research behind
them: [RESEARCH.md](RESEARCH.md).

## D-001 Data is the Windows app's own data.json
- **Decision**: the phone keeps one `data.json` in the Windows format (`version: 1`) in the app's private
  files folder (`files/data/`). Typed fields for what Android uses; every other field (notes, tags,
  checklists, attachments, Windows settings, fields a later version adds) is kept and written back
  unchanged. No Room, no database.
- **Write path** (council A, amended): not `AtomicFile` (it treats `<name>.bak` as its own backup and would
  restore it over `data.json`); the Windows scheme instead: copy to `data.json.bak` + fsync, write
  `data.json.tmp` + fsync, rename. A file that fails to load is kept as `data.json.damaged-<time>` and
  `.bak` is loaded. One store per process, one lock, so the screen, the notification buttons and the
  alarm receiver never write at once (council risk 3). No second process.
- **Verdict**: amend → applied. Tests: `DataTest`, `EngineTest.dataSurvivesAProcessRestart`.

## D-002 Nothing leaves the phone
- No `INTERNET` permission, no analytics, no crash reporting, no accounts. A test fails if the
  permission ever appears. Sound, alarms and notifications are all local.
- **Verdict**: approve.

## D-003 Moving data by file, in the Windows backup layout
- **Export**: one `.zip` (Storage Access Framework, the user picks where) holding
  `RememberMe-backup-<time>/data.json` and `backup.json` (`app: "Remember Me"`, `kind: manual`,
  `algorithm: sha256`, the hash of the exact UTF-8 bytes, no BOM). On Windows: extract, then
  Restore and choose the folder. Checked with the Windows app's own `readBackup()`
  (`tools/windows-validate.cjs`): verified.
- **Import**: that zip, a zip of a Windows backup folder, or a bare `data.json`. Only `data.json` and
  `backup.json` are read (nothing is written from the zip, so no zip-slip); a 20 MB cap; the checksum
  is verified when present. Attachments are Windows-only: the list is emptied and every reference
  removed from tasks and notes, or Windows' restore would refuse the file (council A). Import replaces
  the data; the current data is first kept as a **safety copy** (the newest 3, listed in Settings,
  one tap to restore) (council C). Settings says the file is **not encrypted** (council B).
- **Verdict**: amend → applied. Tests: `DataTest`.

## D-004 Alarms: one exact alarm for the next moment
- The engine computes the next moment (a due time, an early notice, or a "sound again" moment) and sets
  **one** `setAlarmClock()` alarm; without exact-alarm access it falls back to
  `setAndAllowWhileIdle()` and the main screen shows a red banner. Every run handles **every** due
  reminder, not only the one that woke it (council A), so a late or wiped alarm only delays.
- It re-plans after every data change, at every app start, when the alarm fires, and on
  `BOOT_COMPLETED`, `QUICKBOOT_POWERON` (council A), `MY_PACKAGE_REPLACED`, `TIME_SET`,
  `TIMEZONE_CHANGED`, `LOCALE_CHANGED` and the exact-alarm permission change.
- Manifest: `USE_EXACT_ALARM`, plus `SCHEDULE_EXACT_ALARM` with `maxSdkVersion="32"`. `USE_EXACT_ALARM`
  is granted at install and also keeps the app out of the "restricted" standby bucket (one alarm a
  day). **Google Play plan** (council A): Play accepts `USE_EXACT_ALARM` only for alarm/calendar apps;
  before any Play submission, either declare the app as a reminder/alarm app with that justification or
  switch to `SCHEDULE_EXACT_ALARM` only and lean on the allow-background screen.
- No foreground service (not needed to post from an alarm). No direct boot in v0.1.0: a reminder due
  before the first unlock after a reboot fires right after unlock.
- **Verdict**: amend → applied. Tests: `PlannerTest`, `EngineTest`.

## D-005 A reminder stays until Done or Snooze
- Each due reminder: a high-importance, ongoing notification (category reminder) with «تم» and «أجّل»
  (the first snooze preset that applies now, 10 minutes by default; council C) and tapping it opens the
  reminder screen. The **full-screen intent** opens the same screen over the lock screen when Android
  allows it (`canUseFullScreenIntent()`); otherwise the heads-up notification remains and the
  allow-background screen offers the setting.
- Android 14 lets users swipe ongoing notifications away: a delete intent re-posts it **quietly** until
  it is dealt with. Overdue reminders are also in the app's «المتأخرة» tab and the home-screen widget.
- At most 8 single notifications plus a summary ("12 reminders waiting"), so a restored backup with
  many overdue items does not flood the shade.
- Every action carries the `expectedDueAt` it was shown; a stale tap changes nothing (council A).
- All PendingIntents are immutable; only the system-broadcast receiver is exported (council B).
- Sound: the default notification sound, usage "notification event", so Do Not Disturb is respected;
  the allow-background screen says when DND is on (council A). Channel ids are versioned
  (`reminders_v1`, `reminders_quiet_v1`, `notices_v1`) because Android never lets an app change a
  channel's sound later (council risk 5). Escalation is the Windows "remind again" setting (off, 2, 5,
  15 minutes, at most 6 times), not `FLAG_INSISTENT`.
- **Lock screen privacy** (council B and C): a setting «إخفاء العنوان على شاشة القفل», off by default
  (the owner's choice: the title shows). Opening a task for editing from the lock screen asks to unlock.
- **Verdict**: amend → applied. Tests: `EngineTest`, `AppUiTest.alertScreenShowsTheQueueAndDoneCompletes`.

## D-006 «السماح بالعمل في الخلفية» screen
- Shown once at first start and from Settings; a red banner on the main screen while an essential item
  is off. Checks, each with a button: notifications (red), the reminder channel not muted (red), exact
  alarms (red), full-screen (recommended), no battery restriction (recommended), "pause app if unused"
  (advice), Do Not Disturb (advice, when on), and the phone maker's own steps in Arabic for Xiaomi,
  Samsung, Huawei/Honor, Oppo/Realme/OnePlus and Vivo with a best-effort deep link that falls back to
  the app's details page.
- The battery item uses the one-tap system dialog (`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`), because the
  owner installs the APK directly. **Before Google Play**: Play restricts that dialog; switch to the
  battery-optimisation list screen unless the reminder use is accepted.
- Council C asked for a screenshot per maker step: **not done** in v0.1.0 (no real phones of each maker
  to take them from); the steps are text.
- **Verdict**: amend → applied except the per-step screenshots.

## D-007 Arabic first
- Arabic UI by default whatever the phone's language; Settings offers العربية / English. Per-app
  language through AppCompat (`setApplicationLocales` + `locales_config`), so Android 13+ Settings shows
  the same choice (council). Layout mirrors from the locale. Noto Sans Arabic is bundled (OFL 1.1;
  named in About and THIRD_PARTY.md).
- Month names follow `settings.arabicMonths` from the shared data: «تشرين الثاني» (Iraq, Levant) or
  «نوفمبر»; "auto" follows the phone's region, as on Windows.
- **Verdict**: amend → applied.

## D-008 Backups by Android
- `allowBackup` on, with rules: nothing goes to Google's cloud backup; a phone-to-phone transfer carries
  `data.json` and the phone settings (Android 12+). On Android 11 and older nothing is included, so
  there is no phone-to-phone transfer there either (council). A monthly banner reminds the user to
  save a copy (council C).
- **Verdict**: amend → applied.

## D-009 Western digits, no Hijri
- Digits 0-9 everywhere: the locale carries `-u-nu-latn`, java.time formatters use ASCII digits, and
  numbers reach string resources as text, never through `%d` (council B/9). Tested in
  `EngineTest.arabicTextUsesWesternDigits`.
- No Hijri display: the Windows app removed its Hijri option, and the brief asked for it only if
  Windows has it.
- **Verdict**: amend (add a test) → applied.

## D-010 Version and identity
- SemVer `0.1.0` (pre-1.0: not yet tried on real phones), `versionCode 1`, which must only ever grow.
  Package `com.fastkiwi.rememberme`. Release builds only, signed with the release key, never the debug key.
- **Signing key custody** (council risk 1): if the key is lost, every update needs an uninstall, and an
  uninstall deletes the owner's data. **Decision (manager)**: the key stays local (git-ignored `keystore/`);
  no GitHub secret, because the repo is local and closed source. An encrypted backup for the owner's
  password manager is in the git-ignored `keystore-backup/`: a ZIP with AES-256 encryption (WinZip AE-2,
  opens in 7-Zip), made with Windows' built-in `tar` (libarchive) because 7-Zip is not installed on this
  PC and installing it needs the owner's approval. A test decrypt gave byte-identical files and a wrong
  passphrase was rejected. The passphrase is only in the git-ignored `keystore/README-local.txt`.
- **Verdict**: approve, with the custody note.

## D-011 Every N hours (decided by the manager, 2026-11-14)
- The task brief asks for repeats every N hours. The Windows app has no such repeat, and its
  `validateState` turns an unknown repeat into one-time, so such a reminder **becomes one-time on
  Windows** after a file move. The council voted to hide it until Windows supports `'hours'`.
- **Decision (manager)**: keep it on Android. The option exists (`repeat: "hours"`, `repeatHours` 1-23,
  elapsed hours, not wall-clock) and the editor says under it that Windows does not support it yet.
- **Before a Windows-format export**, the app lists every active reminder that repeats every few hours and
  says in Arabic that it will become one-time in Remember Me for Windows; the user saves anyway or
  cancels (`MainActivity.startExport`, tested by `AppUiTest.exportWarnsAboutHourlyRemindersFirst`). The
  `.ics` export needs no warning (RRULE `FREQ=HOURLY`).
- **Windows backlog**: support `'hours'` (`repeatHours` 1-23, elapsed hours from the current occurrence,
  the same rule as `core/Repeat.kt`) in the next Windows release; recorded in the Windows repo's
  `ROADMAP.md` on the local branch `claude/hourly-repeat-backlog` (not pushed).

## D-012 Time-zone moves follow the Windows rule
- A repeat's time of day comes from `repeatFrom`, an instant, read in the phone's current zone (exactly
  as on Windows), so both apps compute the same moment from one file. Consequence: a daily 09:00 made in
  Baghdad comes back at 10:00 after moving to Dubai (still 09:00 Baghdad time). One-time reminders are
  fixed instants. Pinned by `RepeatTest.timeZoneMoveFollowsTheWindowsRuleAndOneTimeIsAnInstant`.
- **Decision (manager)**: keep the Windows rule, so both apps agree.
- **Future item for both apps together**: store the wall-clock time of the series (for example
  `repeatTime: "09:00"`) so a traveller keeps 09:00 local. Changing only one app would make them disagree.

## D-013 Scope of v0.1.0
- In: reminders (add, edit, delete to a 30-day trash, restore), all Windows repeat modes plus hourly,
  early notices, priority, category, snooze presets (read from the shared data), Today / Upcoming /
  Overdue / Done, search (folds Arabic letter forms and diacritics), the reminder screen, a home-screen
  widget, a "New reminder" launcher shortcut, export/import, safety copies, theme, sound, "remind again".
- Kept but not shown: Windows sticky notes, tags, checklists (preserved in the file; a repeat's checklist
  is unticked for the next occurrence, as on Windows), attachments (Windows-only).
- Not in v0.1.0: editing snooze presets on the phone (edit them on Windows; they travel in the file),
  natural-language quick add, CSV/iCalendar, Windows' "only this time" edit of a repeat.

## D-015 Professional design through a design council
- The owner asked that it follow international standards and look professional. A council of five (an
  Android product designer, an Arabic RTL typography expert, an accessibility specialist, a one-handed
  everyday user, a devil's advocate) wrote [DESIGN-SPEC.md](DESIGN-SPEC.md) with 33 mockups in
  `design/shots/` and voted on 17 points (V1–V17, objections on record there). The house rules come from
  Scan Kiwi's spec. The main points: one list with sections instead of tabs (4/5), the time as each row's
  hero, one primary action at the bottom of each screen, a complete Material 3 scheme from the Windows
  brand green with the lavender bell as tertiary, `Icons.Rounded` only, stable components only.
- **Difference from Scan Kiwi:** digits stay Western in Arabic (brain D-012); the Arabic comma «، »
  is still the separator.
- QC against the spec: the screenshots in `screenshots/qc/` (Arabic and English, light and dark, 200 %
  text) plus tests for the button order, a single «حفظ», 48 dp targets and the row semantics.

## D-016 iCalendar export
- The manager asked for repeats modelled on RFC 5545 and an `.ics` export. Every repeat already maps to
  an RRULE, so the app exports `.ics` (VEVENT + VALARM, VTODO for untimed reminders, a VTIMEZONE with
  yearly rules). An independent RFC 5545 library (ical4j, tests only) expands each exported rule to
  the app's own occurrences across DST. Import of `.ics` is not in v0.1.0.

## D-017 The independent bug hunt
- A reviewer that did not write the code found seven S1/S2 defects. It wrote a failing test for each,
  then fixed it: a stale editor undoing a Done or a snooze, an editor showing another reminder's fields,
  the back stack lost on rotation, notification buttons ignored after a sub-millisecond snooze, an engine
  race that could sound a reminder twice, a failed write crashing the app, and a safety-copy restore with
  no way back. Three of its S3 findings were fixed afterwards: an early notice not withdrawn when the
  reminder moves later, the channel's lock-screen override, and the weekday fallback in local time. The
  rest are listed in the bug-hunt report (commit d880ed3) as known minor items.

## D-019 No store submission
- **Decision (manager)**: Google Play is not planned. The notes on `USE_EXACT_ALARM`, `USE_FULL_SCREEN_INTENT`
  and the battery-optimisation dialog (D-004, D-006) stay as the record of what a store submission would
  need.

## D-018 Standards record
- [STANDARDS.md](STANDARDS.md) lists each standard as met, partial or gap. It is honest about what
  needs a real phone.

## D-014 Licence
- Closed source under D-051: no open-source licence file. Third-party notices are kept in
  [THIRD_PARTY.md](THIRD_PARTY.md) and `LICENSES/`.

## D-020 One release 1.0.0 with every approved feature (brain D-079 amendment, 2026-11-15)
- **Decision (owner via the manager)**: «اريد فقط اصدار واحد فيه كل ميزات». One 1.0.0 (versionCode 3, same key)
  with every feature [docs/FEATURES.md](docs/FEATURES.md) approved; gates run once on the complete build
  (tests and lint, independent bug hunt, `torture/` suite, black-box user test, QC). No intermediate builds.
- **Alerts**: the due channels are silent; a short foreground service (`RingService`, `shortService`, the only
  new permission FOREGROUND_SERVICE) plays the chosen tone, at most 2 minutes; if it cannot start, the same
  notification goes on the fallback channel with the phone's own sound. «منبّه مهم» uses alarm audio and rings
  in silent/vibrate mode, never through Do Not Disturb unless the user's DND settings allow alarms (no DND
  access requested). Channel ids are fixed from 1.0.0 (`due_normal_v1`, `due_alarm_v1`, `reminders_v1`,
  `reminders_quiet_v1`, `notices_v1`, `summary_v1`); never a channel per tone.
- **Tones from files** are copied into `files/tones/<sha256>.<ext>` (5 MB, 10 at most) instead of keeping a
  persistable grant, so a moved or deleted file never silences a reminder.
- **Before the first unlock** (F-026) only due times and the style are kept in device-protected storage.
- **Hijri** uses Android's ICU Umm al-Qura calendar; tests compare every day of 1300–1600 AH with the JDK.
- **Encrypted backups** use the RMBK1 container (FEATURES.md F-086).
- **Design**: [DESIGN-SPEC.md](DESIGN-SPEC.md) 1.0.0 (design council v2): no NavigationBar, Quick Add from the
  FAB, Material Symbols Rounded vendored as drawables (material-icons-extended removed).
- **Open**: the council's 14-day owner soak on a 1.0.0-rc conflicts with "no intermediate builds"; raised with
  the manager (default: the soak becomes part of the owner's checklist after delivery).
