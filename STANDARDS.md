# Standards: what v0.1.0 meets, and the gaps

Checked on 2026-11-14 for Remember Me for Android 0.1.0. **Met** means checked by a test, a tool or the
build. **Partial** means part is done and the rest is listed. **Gap** means not done. Everything here was
checked on the JVM (Robolectric, Android 16 / API 36) and with build tools. **Nothing was checked on a
real phone yet** (see the end).

## Android platform and quality

| Requirement | Status | Evidence |
|---|---|---|
| Target API: current Google Play policy (API 35+ for new apps since Aug 2025) | **Met** | `targetSdk 36`, `compileSdk 36`, `minSdk 26` |
| 16 KB memory page size | **Met** | `zipalign -c -P 16` passes, and every `.so` (only AndroidX's `libandroidx.graphics.path`) has LOAD `p_align` 16384 |
| Edge-to-edge (enforced on Android 15+) | **Met** | `enableEdgeToEdge()`; content uses insets (`safeDrawingPadding`, `navigationBarsPadding`) |
| Per-app language (Android 13+) | **Met** | AppCompat `setApplicationLocales` + `locales_config.xml` |
| Notification channels with user control | **Met** | Fixed from 1.0.0: `due_normal_v1` and `due_alarm_v1` (high, no sound of their own: the app plays the chosen tone), `reminders_v1` (high, the phone's sound: fallback), `reminders_quiet_v1` (high, silent), `notices_v1` (default), `summary_v1` (low). Never a channel per tone; the app never overrides lock-screen visibility at channel level (`EngineTest`, `AlertPipelineTest`) |
| Notification importance used correctly | **Met** | Due reminders HIGH with category `reminder`; early notices DEFAULT; the summary is silent |
| Notification actions accessible | **Met** | Labelled actions «تم» / «أجّل 10 دقائق» (`EngineTest`); the content intent opens the reminder screen |
| Exact alarms, Doze, reboot | **Met (JVM)** | `setAlarmClock`, an inexact fallback with a red banner, re-arm on boot, update, time and zone changes, and permission change (`EngineTest`, `PlannerTest`) |
| Foreground service only where needed, with the right type (Android 14+) | **Met (JVM)** | One: `RingService`, `foregroundServiceType="shortService"`, permission `FOREGROUND_SERVICE` (no type-specific permission exists or is needed for shortService). Why: it plays the reminder's chosen tone and is the only way to know the sound started (FEATURES F-016). It stops by itself after the ring duration (30 s for a normal reminder, at most 2 minutes for «منبّه مهم»), at once on «تم», «أجّل», a swipe or a volume key, and on Android's own short-service timeout (`onTimeout`). Why not `mediaPlayback`: it needs `FOREGROUND_SERVICE_MEDIA_PLAYBACK` too, has no built-in time limit, and Android 15 forbids starting it from BOOT_COMPLETED, where a reminder missed while the phone was off must still ring. If the service cannot start, the reminder is reposted on the sounding fallback channel. The reason is shown on the in-app privacy page |
| Do Not Disturb respected | **Met (JVM)** | No `ACCESS_NOTIFICATION_POLICY`, no `setBypassDnd` (`AlertPipelineTest.theAppNeverAsksForDndAccess`); «منبّه مهم» rings in DND only where the user's DND settings let alarms through (`AlertPolicyTest`) |
| Predictive back (default with targetSdk 36) | **Partial** | `BackHandler` only; not tried on a device |
| Core app quality: permissions asked in context | **Partial** | Notifications are asked at first start with the permissions screen beside them; Android's guidance prefers asking at the first reminder |
| Core app quality: no ANRs, crash-free | **Gap** | Writes run off the main thread and failed writes cannot crash (bug hunt B6), but there has been no device run, no strict mode and no vitals |

## Material 3 and the design spec ([DESIGN-SPEC.md](DESIGN-SPEC.md))

| Requirement | Status | Evidence |
|---|---|---|
| A complete colour scheme from the brand (no baseline purple) | **Met** | Every role is filled in `ui/Theme.kt`; contrast in `design/tools/contrast-report.md` |
| One primary action per screen, in the thumb zone; fixed button order | **Met** | Home FAB centred at the bottom; editor «حفظ» once at the bottom (`AppUiTest`: exactly one node); reminder screen «تم» at the end (`theReminderScreenPutsDoneAtTheEnd`, RTL position checked) |
| Stable components only | **Met** | No experimental Expressive APIs |
| Bundled Noto Sans Arabic, no clipped dots | **Met** | `LineHeightStyle.Trim.None` on every style; no fixed text heights; screenshots in `screenshots/qc/` |
| One icon family with RTL mirroring | **Met** | `Icons.Rounded` / `Icons.AutoMirrored.Rounded` only |
| Motion (shared-axis transitions, row exit animation) and reduced motion | **Partial** | `LocalReducedMotion` is provided; screen changes are instant (no slide), and rows have no exit animation yet |
| QC screenshots | **Met** | 40 PNGs in `screenshots/qc/`: Arabic and English, light and dark, and Arabic at 200 % text, 8 screens each |

## Accessibility: WCAG 2.2 AA, as applied to mobile by EN 301 549

| Criterion | Status | Evidence |
|---|---|---|
| 1.4.3 / 1.4.11 contrast | **Met** | All 51 colour pairs pass the contrast script (≥ 4.5:1 for text, ≥ 3:1 for UI) |
| 1.4.4 resize text to 200 % | **Met (JVM)** | Text in sp; the reminder screen moves the snooze choices into the scroll at large text, so the time, the title and «تم» stay visible (`QcScreenshotsTest.arabicLargeText` asserts «تم» is on screen) |
| 1.4.1 nothing by colour alone | **Met** | Overdue: icon, section title and words; priority: flag icon and word; permission status: icon and word |
| 2.5.8 target size (48 dp, Android) | **Met** | Test fails if any clickable node on Home, the editor or the reminder screen is under 48 × 48 dp (it found the 40 dp icon buttons, now fixed) |
| 4.1.2 name, role, value (TalkBack) | **Met (JVM)** | Every icon button has a label and a tooltip; rows have a spoken summary and a «تم» custom action (tested); weekday toggles have names and state; headings are marked |
| 2.3.1 no flashing | **Met** | No flashing or looping animation |
| Tested with TalkBack on a phone | **Gap** | Only semantics tests on the JVM |

## Security and privacy: OWASP MASVS v2

| Control | Status | Evidence |
|---|---|---|
| MASVS-STORAGE-1/2: data in private storage only, no leak through backups or logs | **Met** | `files/data/data.json`; cloud backup excludes everything; phone-to-phone transfer only (D-008); no logging of content |
| MASVS-PRIVACY-1..4: minimal data, no tracking, user control | **Met** | No internet permission (tested), no analytics, no identifiers; lock-screen privacy setting; export only where the user chooses, with an "unencrypted" warning |
| MASVS-PLATFORM-1: exported components | **Met** | Only `SystemReceiver` (system broadcasts) is exported, plus AndroidX's profile installer behind the DUMP permission (tested) |
| MASVS-PLATFORM: PendingIntents | **Met** | All `FLAG_IMMUTABLE`, explicit components |
| MASVS-PLATFORM-3: UI on the lock screen | **Met** | The title is hidden on the lock screen with the privacy setting; editing asks to unlock |
| Imported files | **Met** | A zip is read in memory only (no zip-slip), 20 MB cap, checksum verified (`DataTest`) |
| MASVS-CODE: release build | **Met** | R8 minify and resource shrinking; not debuggable; signed with the release key only |
| MASVS-NETWORK / CRYPTO / AUTH | **n/a** | No network, no accounts, no secrets |
| MASVS-RESILIENCE | **n/a** | Out of scope for a free local app |

## Privacy by design: ISO/IEC 29100

No personal data leaves the phone and nothing is collected, so consent, disclosure and transfer do not
arise. **Data minimisation**: only what the user types. **Use limitation**: reminders only.
**Openness**: the About screen and اقرأني.txt state that the app works offline. **Individual
participation**: export and delete at any time. **Security**: private storage and no cloud backup.
**Accountability**: these documents plus DECISIONS.md.

## Dates, time zones and languages

| Standard | Status | Evidence |
|---|---|---|
| ISO 8601 / RFC 3339 in storage | **Met** | UTC with milliseconds, as JavaScript writes it (`Codec.iso`) |
| IANA time zones | **Met** | `ZoneId.systemDefault()`; re-planned on `TIMEZONE_CHANGED`; the `.ics` carries the TZID with a matching VTIMEZONE |
| Unicode CLDR formats | **Met** | Month and weekday names, Arabic plurals (zero/one/two/few/many/other), regional month names (Iraq/Levant vs others) |
| Digits | **Met** | Western 0-9 everywhere (brain D-012), `-u-nu-latn`; a test fails on any Arabic-Indic digit |
| BCP 47 language tags | **Met** | `ar-IQ-u-nu-latn`, `en-US` |
| UAX #9 bidirectional text | **Partial** | Compose's default bidi handling and the Arabic separator «، »; Latin runs inside Arabic sentences are not yet wrapped with `BidiFormatter` |
| Hijri calendar through ICU | **Not offered** | The Windows app removed its Hijri option (D-009); `android.icu` IslamicCalendar is the path if it comes back |

## Recurrence and interchange

| Standard | Status | Evidence |
|---|---|---|
| iCalendar RFC 5545 RRULE semantics | **Met** | Every repeat maps to an RRULE, including the Windows month-end rule (`BYMONTHDAY=28,29,30,31;BYSETPOS=-1`); an independent RFC 5545 library (ical4j, tests only) expands each exported rule to exactly the app's own occurrences, across DST (`IcsTest`) |
| `.ics` export | **Met** | Settings → «تصدير إلى تقويم (ics)»: VEVENT + VALARM (and VTODO for untimed), folded at 75 octets, escaped |
| `.ics` import | **Gap** | Not in v0.1.0 |
| The Windows app's format | **Met** | 600 next-occurrence cases computed by the Windows code match (`RepeatParityTest`); the Windows app's own `readBackup()` restores an Android export (`tools/windows-validate.cjs`) |

## Release engineering

| Standard | Status | Evidence |
|---|---|---|
| SemVer 2.0.0 | **Met** | 0.1.0, `versionCode 1` |
| Keep a Changelog | **Met** | [CHANGELOG.md](CHANGELOG.md) |
| SHA-256 for each APK | **Met** | `dist/RememberMe-<version>.sha256.txt` (with the signing certificate's SHA-256) |
| SBOM (CycloneDX) | **Met** | `dist/RememberMe-<version>.cdx.json` (CycloneDX 1.6, Maven components plus the fonts) |
| Reproducible build | **Gap** | Not checked |

## Not checked without a real phone

- Alarms during deep Doze and in the "restricted" standby bucket.
- Phone-maker battery killers (Xiaomi/HyperOS, Samsung, Huawei/HarmonyOS, Oppo, Vivo) and their deep links.
- The full-screen reminder over the lock screen, turning the screen on, and a sideloaded APK keeping
  the full-screen permission on Android 14+.
- Sound and vibration, Do Not Disturb behaviour.
- The home-screen widget on real launchers.
- TalkBack with a real screen reader, predictive back, and the release build's shrunk code at run time.
