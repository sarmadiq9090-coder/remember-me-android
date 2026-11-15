# Remember Me for Android («ذكرني» لأندرويد)

Reminders that stay until you deal with them, on Android 8 and later. Arabic first, with English.
A port of FastKiwi's Remember Me for Windows: the same data file, the same repeat rules, the same
snooze choices, and backups that move between the phone and the PC.

Proprietary FastKiwi software (D-051). Publishing under Apache-2.0 is proposed (D-079,
[docs/OPEN-SOURCE-PROPOSAL.md](docs/OPEN-SOURCE-PROPOSAL.md)) and needs the owner's approval; until then
no licence is granted. Local repository only; nothing is published. `LICENSES/` holds third-party texts.

- **Research**: [RESEARCH.md](RESEARCH.md) (the Windows app, Android limits, patterns)
- **Decisions**: [DECISIONS.md](DECISIONS.md) (councils, D-001 … D-020)
- **Design**: [DESIGN-SPEC.md](DESIGN-SPEC.md) 1.0.0 (design council v2, mockups in `design/v2/shots/`)
- **Standards**: [STANDARDS.md](STANDARDS.md) (met, partial, gaps)
- **Third-party notices**: [THIRD_PARTY.md](THIRD_PARTY.md)
- **For the owner, in Arabic**: [اقرأني.txt](اقرأني.txt)
- **Changes**: [CHANGELOG.md](CHANGELOG.md)
- **Roadmap to 1.0** (the owner's PRD with the council's amendments): [docs/ROADMAP.md](docs/ROADMAP.md)
- **Compatibility matrix** (only phones actually tried): [docs/COMPATIBILITY.md](docs/COMPATIBILITY.md)
- **Features** (council-approved, with status): [docs/FEATURES.md](docs/FEATURES.md); **privacy**: [docs/PRIVACY.md](docs/PRIVACY.md); **data format**: [docs/DATA-FORMAT.md](docs/DATA-FORMAT.md)
- **Security** and **contributing**: [SECURITY.md](SECURITY.md), [CONTRIBUTING.md](CONTRIBUTING.md)

## What it does (1.0.0)

Every feature the feature council approved ([docs/FEATURES.md](docs/FEATURES.md), 80 features); the user
guide is [docs/GUIDE-ar.md](docs/GUIDE-ar.md) / [docs/GUIDE-en.md](docs/GUIDE-en.md). In short:

- **Alerts**: a pinned notification with «تم», «أجّل 10 دقائق», «أجّل حتى…» and «تراجع»; the full-screen
  reminder screen; «تذكير عادي» (follows the phone) or «منبّه مهم» (alarm volume, rings in silent mode,
  never through Do Not Disturb unless the user's DND allows alarms); a tone per reminder, played by the app
  with a fallback chain so a reminder is never silent by accident; up to 3 early alerts; «أخفِ التفاصيل» on
  the lock screen; before-unlock rings after a restart; morning summary; read aloud.
- **Adding**: Quick Add (the Windows parser, with a preview and questions; nothing unseen is saved),
  templates, voice, share, a Quick Settings tile, widget «تم»; prayer-relative times and Hijri dates.
- **Organising**: one list (Overdue, Today, Upcoming, Done) with swipe, bulk selection, search, lists and tags;
  agenda, day, week and month views; steps; change history; a two-pane tablet layout.
- **Trust**: «صحة التذكيرات» (every check, its source, one fix; unknown never shown as ready), the alert test,
  the alert log, the compatibility report.
- **Data**: the Windows app's `data.json` (both directions tested), encrypted backups, a weekly automatic
  copy, calendar import and export, delete all, a privacy page. No analytics, no account; one added
  permission in 1.0.0 (FOREGROUND_SERVICE, the tone service). Since 1.0.5 (D-081) the internet is used only to
  check for and download updates from GitHub (verified: newer, SHA-256, same signing key; Android asks before
  installing), never for reminders or data about the person; it can be turned off. «أبلغنا عن مشكلة أو اقتراح»
  opens the person's own mail app or browser.

## Layout

```
app/src/main/java/com/fastkiwi/rememberme/
  core/      Repeat.kt (ported from Windows), Tasks.kt, Planner.kt (what to announce, two alarm slots),
             Model.kt (data.json), AlertExt.kt (the `android` object), AlertPolicy.kt (style, DND, sound),
             Organize.kt (tags, steps), Search.kt, Bulk.kt, Calendar.kt, Hijri.kt (ICU Umm al-Qura),
             Templates.kt, quickadd/ (the Windows parser + Android additions), prayer/ (times, cities)
  data/      Store.kt (durable file), Backup.kt, BackupCrypto.kt, Ics*.kt (calendar), Tones.kt, AlertLog.kt,
             ScheduleLog.kt, History.kt, AutoBackup.kt, DeleteAll.kt, Prefs*.kt (phone-only settings)
  alarm/     Engine.kt, Notifier.kt (channels, notifications), RingService.kt (the tone), Receivers.kt,
             DirectBoot.kt (before the unlock), Summary.kt (morning), TestAlarm.kt
  ui/        Home.kt, Alert.kt, Edit.kt, Settings.kt, HealthScreens.kt; home/, editor/, calendar/, health/,
             settings/, quick/; Theme.kt, Ms.kt (icons), L10n.kt, ScreenStack.kt, Haptics.kt
  widget/, tile/, MainActivity.kt, AlertActivity.kt, ShareActivity.kt, Repo.kt, RememberMeApp.kt
app/src/test/   JUnit, Robolectric and Compose UI tests; torture/ (the torture suite and the black-box driver)
tools/          strings_gen.py + strings/*.py (Arabic and English), windows-*.cjs and interchange.sh (checks
                with the Windows app's own code), vendor_symbols.py, make_tones.py, dist.sh
torture/        run.sh, reports/, blackbox/ (driver README and examples)
screenshots/    taken by AppUiTest; screenshots/qc/ by QcScreenshotsTest (sample data only)
design/         design council mockups (v1 and v2/shots)
```

## Build and test (Git Bash on Windows)

The JDK 21, Android SDK 36 and Gradle 8.14.3 are reused read-only from `../kiwi-scan/.tools`; this
repository keeps its own Gradle caches in `.gradle-home/` (git-ignored). See `env.sh`.

```sh
./build.sh :app:testDebugUnitTest      # all unit and Robolectric tests (also writes screenshots/)
./build.sh :app:lintRelease            # must report "No issues found."
./build.sh :app:assembleRelease        # signed APK if keystore/keystore.properties exists
./build.sh :app:releaseSbom            # dist/RememberMe-<version>.cdx.json (CycloneDX)
bash tools/dist.sh                     # dist/: the APK, its SHA-256, the Arabic guide, screenshots
node tools/windows-golden.cjs          # regenerate the parity cases from ../Remember-Me
node tools/windows-validate.cjs        # restore the test export with the Windows app's own code
python tools/strings_gen.py app/src/main/res   # after editing the string table
```

Release signing reads `keystore/keystore.properties` (git-ignored, never committed or printed).

## Standards

See [STANDARDS.md](STANDARDS.md): Android target API, 16 KB pages, notification channels, Material 3,
WCAG 2.2 AA / EN 301 549, OWASP MASVS v2, ISO/IEC 29100, ISO 8601, IANA, CLDR, BCP 47, RFC 5545, SemVer,
Keep a Changelog, SHA-256 and a CycloneDX SBOM, each marked met, partial or gap.

## Tested and not tested

1.0.0 passed six gates, all on the PC (Robolectric API 26, 29, 31, 33, 34, 36; "Robolectric API N", not
"Android N"):
1. **Tests and lint**: 766 unit, Robolectric and Compose UI tests, 0 failed; lint debug and release: no issues.
2. **An independent bug hunt** (it did not write the code): 13 confirmed defects (7 S1, 6 S2), each fixed with
   the failing test that found it.
3. **The torture suite** ([torture/README.md](torture/README.md)): 10,000 reminders, 500 in one minute, storms
   of runs from many threads, DST in every zone, 50 years of month ends, random clock jumps, 30,000 repeat
   cases against the Windows code, 6,000 calendar files read by RFC 5545, fuzzed backups, encrypted backups,
   calendar files, Quick Add and intents, process death and full disks, and a UI monkey. Every finding was fixed
   with a test, except one failure inside Robolectric's text magnifier (no app code; documented).
4. **A black-box user test**: a tester that never saw the code used the app through screenshots and the
   accessibility tree only ([torture/blackbox/](torture/blackbox/README.md)), 13 tasks as three people (busy
   employee; older user at the largest text; student mixing Arabic and English). Every U1 and U2 was fixed and
   re-tested ([docs/BLACKBOX-REPORT.md](docs/BLACKBOX-REPORT.md)).
5. **QC** against FEATURES, DESIGN-SPEC and STANDARDS ([docs/QC-1.0.0.md](docs/QC-1.0.0.md)): no Q1; every Q2
   fixed; 75 features met, 5 need the owner's phone.
6. **Release**: signed with the same key as 0.1.x (certificate SHA-256 checked by `tools/dist.sh`), 16 KB
   aligned, installs over 0.1.0 and 0.1.1 keeping all data (`UpgradeTest`), SHA-256 and a CycloneDX SBOM.

**Not tested without a real phone**: real sound and vibration; alarms after hours of deep Doze; OEM battery
killers (Xiaomi, Samsung, Huawei, OPPO, vivo) and their deep links; the full-screen screen over a real lock
screen; a locked restart; the widget on a launcher; the Quick Settings tile; Android 14's non-linear font
scaling; TalkBack itself. The owner's checklist in [docs/release-notes/1.0.0-ar.txt](docs/release-notes/1.0.0-ar.txt)
covers them, with 14 days of daily use.
