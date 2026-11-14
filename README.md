# Remember Me for Android («ذكرني» لأندرويد)

Reminders that stay until you deal with them, on Android 8 and later. Arabic first, with English.
A port of FastKiwi's Remember Me for Windows: the same data file, the same repeat rules, the same
snooze choices, and backups that move between the phone and the PC.

Proprietary FastKiwi software (D-051). Local repository only; nothing is published.

- **Research**: [RESEARCH.md](RESEARCH.md) (the Windows app, Android limits, patterns)
- **Decisions**: [DECISIONS.md](DECISIONS.md) (council of three, D-001 … D-014)
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
- Overdue / Today / Upcoming / Done tabs, search, a 30-day trash, a home-screen widget and a
  "New reminder" launcher shortcut.
- «السماح بالعمل في الخلفية»: checks notifications, exact alarms, full-screen, battery and the phone
  maker's own settings, with a button for each.
- Export and import as a zip that Remember Me for Windows restores (and the other way round).
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
screenshots/    taken by AppUiTest (sample data only)
```

## Build and test (Git Bash on Windows)

The JDK 21, Android SDK 36 and Gradle 8.14.3 are reused read-only from `../kiwi-scan/.tools`; this
repository keeps its own Gradle caches in `.gradle-home/` (git-ignored). See `env.sh`.

```sh
./build.sh :app:testDebugUnitTest      # all unit and Robolectric tests (also writes screenshots/)
./build.sh :app:lintRelease            # must report "No issues found."
./build.sh :app:assembleRelease        # signed APK if keystore/keystore.properties exists
node tools/windows-golden.cjs          # regenerate the parity cases from ../Remember-Me
node tools/windows-validate.cjs        # restore the test export with the Windows app's own code
python tools/strings_gen.py app/src/main/res   # after editing the string table
```

Release signing reads `keystore/keystore.properties` (git-ignored, never committed or printed).

## Standards

| Standard | How |
|---|---|
| OWASP MASVS-STORAGE | Data only in app-private storage; no logs of content; no cloud backup (D-008); export only where the user chooses, with an "unencrypted" warning; nothing written from imported zips. |
| OWASP MASVS-PRIVACY | No internet permission (tested); no analytics or identifiers; lock-screen privacy option. |
| OWASP MASVS-PLATFORM | Only the system-broadcast receiver is exported; immutable PendingIntents; the reminder screen is not exported; editing from the lock screen asks to unlock. |
| WCAG 2.2 AA | Labels for every icon button and state (TalkBack); 48 dp targets; colour pairs ≥ 4.5:1; priority shown by an icon too; text in sp with scrolling screens for 200 % text; live region on the reminder title. |
| SemVer | 0.1.0; `versionCode` only grows. |
| ISO 8601 / RFC 3339 | All times stored as UTC ISO strings with milliseconds, as JavaScript writes them. |
| Unicode CLDR | Month and weekday names and Arabic plurals from CLDR; Western digits forced. |

## Tested and not tested

Tested on the JVM (Robolectric, Android 16 / API 36): repeat rules (Windows' own cases plus 600
parity cases computed by the Windows code), DST gaps and fall-back, time-zone moves, task actions and
stale taps, snooze presets, the planner (remind-again, early notices), the data file (round trip,
damage recovery), backups (checksum, Windows layout, hostile zips), alarms set as alarm clocks,
notifications with their buttons and re-posting, reboot re-arm, lock-screen privacy, Western digits,
the main screens in Arabic and English. An Android export restored by the Windows app's `readBackup()`.

**Not tested without a real phone**: alarms during deep Doze; OEM battery killers (Xiaomi, Samsung,
Huawei, Oppo, Vivo) and the maker deep links; the full-screen screen over the lock screen and turning
the screen on; the notification sound and vibration; the home-screen widget on a launcher; the
per-app language switch on Android 12 and older; a sideloaded APK keeping the full-screen permission
on Android 14+; the release build's R8-shrunk code at run time.
