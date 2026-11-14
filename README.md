# Remember Me for Android («ذكرني» لأندرويد)

Reminders that stay until you deal with them, on Android 8 and later. Arabic first, with English.
A port of FastKiwi's Remember Me for Windows: the same data file, the same repeat rules, the same
snooze choices, and backups that move between the phone and the PC.

Proprietary FastKiwi software (D-051). Local repository only; nothing is published.

- **Research**: [RESEARCH.md](RESEARCH.md) (the Windows app, Android limits, patterns)
- **Decisions**: [DECISIONS.md](DECISIONS.md) (councils, D-001 … D-018)
- **Design**: [DESIGN-SPEC.md](DESIGN-SPEC.md) (design council, mockups in `design/shots/`)
- **Standards**: [STANDARDS.md](STANDARDS.md) (met, partial, gaps)
- **Third-party notices**: [THIRD_PARTY.md](THIRD_PARTY.md)
- **For the owner, in Arabic**: [اقرأني.txt](اقرأني.txt)
- **Changes**: [CHANGELOG.md](CHANGELOG.md)

## What it does (v0.1.0)

- Reminders with a title, a note, a date and time (or none), repeat (daily, weekly, chosen weekdays,
  every N days, every N hours, monthly, yearly), an early notice, priority and category.
- At the time: a high-priority notification with «تم» and «أجّل» that stays until one is pressed (it comes
  back quietly if swiped away), and a full-screen reminder screen over the lock screen where Android
  allows it, with Done, Skip (repeats) and the six snooze choices.
- Exact alarms that survive Doze, reboots, app updates, clock and time-zone changes.
- One list with Overdue, Today, Upcoming and a folded Done section (the time leads each row), search,
  a 30-day trash, a home-screen widget and a "New reminder" launcher shortcut.
- «السماح بالعمل في الخلفية»: checks notifications, exact alarms, full-screen, battery and the phone
  maker's own settings, with a button for each.
- Export and import as a zip that Remember Me for Windows restores (and the other way round), and an
  iCalendar (.ics) export for other calendars.
- No internet permission, no analytics, no account.

## Layout

```
app/src/main/java/com/fastkiwi/rememberme/
  core/    Repeat.kt (nextOccurrence, ported from Windows), Tasks.kt (save, complete, skip, snooze,
           trash), Snooze.kt (presets), Planner.kt (what to announce and when to wake), Model.kt (data.json)
  data/    Store.kt (durable file), Backup.kt (zip export/import), Prefs.kt (phone-only settings)
  alarm/   Engine.kt (carries out a plan, sets the alarm), Notifier.kt, Receivers.kt
  ui/      Compose screens, L10n.kt (language, dates, digits), Health.kt (permissions, makers)
  widget/  ReminderWidget.kt
  MainActivity.kt, AlertActivity.kt (the reminder screen), Repo.kt (the one store), RememberMeApp.kt
app/src/test/   JUnit and Robolectric tests; resources/windows-golden.json, windows-data.json
tools/          strings_gen.py (Arabic and English strings from one table),
                windows-golden.cjs, windows-validate.cjs (checks against the Windows app's own code)
screenshots/    taken by AppUiTest; screenshots/qc/ by QcScreenshotsTest (sample data only)
design/         the design council's mockups, palette and contrast tools
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

69 tests, all on the JVM (Robolectric, Android 16 / API 36): repeat rules (Windows' own cases plus 600
parity cases computed by the Windows code), DST gaps and fall-back, time-zone moves, task actions and
stale taps, snooze presets, the planner (remind-again, early notices), the data file (round trip,
damage recovery), backups (checksum, Windows layout, hostile zips), alarms set as alarm clocks,
notifications with their buttons and re-posting, reboot re-arm, lock-screen privacy, Western digits,
the main screens in Arabic and English, the design rules (one «حفظ», «تم» at the end in RTL, 48 dp
targets, row semantics), the `.ics` export expanded by an independent RFC 5545 library, and QC
screenshots of every screen in Arabic and English, light and dark, and at 200 % text. An Android export
is restored by the Windows app's `readBackup()`. An independent bug hunt found and fixed 7 S1/S2 defects.

**Not tested without a real phone**: alarms during deep Doze; OEM battery killers (Xiaomi, Samsung,
Huawei, Oppo, Vivo) and the maker deep links; the full-screen screen over the lock screen and turning
the screen on; the notification sound and vibration; the home-screen widget on a launcher; the
per-app language switch on Android 12 and older; a sideloaded APK keeping the full-screen permission
on Android 14+; the release build's R8-shrunk code at run time.
