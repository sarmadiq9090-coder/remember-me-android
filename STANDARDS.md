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
| Notification actions accessible | **Met** | Labelled actions «تم» / «أجّل 10 دقائق» / «أجّل حتى…», also on the lock-screen public version (`EngineTest`, `AlertPipelineTest`); the content intent opens the reminder screen |
| Exact alarms, Doze, reboot | **Met (JVM)** | Two slots: `setAlarmClock` for due times, `setExactAndAllowWhileIdle` for notices and "sound again", a non-waking housekeeping window; inexact fallback with a red banner; re-arm on boot, update, time and zone changes and permission change; before-unlock rings after a restart (direct boot, times only). `EngineTest`, `EngineReliabilityTest`, `AlertPipelineTest` on Robolectric API 26, 29, 31, 33, 34, 36; torture: 10,000 reminders, clock jumps, storms |
| Foreground service only where needed, with the right type (Android 14+) | **Met (JVM)** | One: `RingService`, `foregroundServiceType="shortService"`, permission `FOREGROUND_SERVICE` (no type-specific permission exists or is needed for shortService). Why: it plays the reminder's chosen tone and is the only way to know the sound started (FEATURES F-016). It stops by itself after the ring duration (30 s for a normal reminder, at most 2 minutes for «منبّه مهم»), at once on «تم», «أجّل», a swipe or a volume key, and on Android's own short-service timeout (`onTimeout`). Why not `mediaPlayback`: it needs `FOREGROUND_SERVICE_MEDIA_PLAYBACK` too, has no built-in time limit, and Android 15 forbids starting it from BOOT_COMPLETED, where a reminder missed while the phone was off must still ring. If the service cannot start, the reminder is reposted on the sounding fallback channel. The reason is shown on the in-app privacy page |
| Do Not Disturb respected | **Met (JVM)** | No `ACCESS_NOTIFICATION_POLICY`, no `setBypassDnd` (`AlertPipelineTest.theAppNeverAsksForDndAccess`); «منبّه مهم» rings in DND only where the user's DND settings let alarms through (`AlertPolicyTest`) |
| Predictive back (default with targetSdk 36) | **Partial** | See the motion row; not tried on a device |
| Core app quality: permissions asked in context | **Partial** | Notifications are asked at first start with the permissions screen beside them; Android's guidance prefers asking at the first reminder |
| Core app quality: no ANRs, crash-free | **Gap** | Writes run off the main thread and failed writes cannot crash (bug hunt B6), but there has been no device run, no strict mode and no vitals |

## Material 3 and the design spec ([DESIGN-SPEC.md](DESIGN-SPEC.md))

| Requirement | Status | Evidence |
|---|---|---|
| A complete colour scheme from the brand (no baseline purple) | **Met** | Every role is filled in `ui/Theme.kt`; contrast in `design/tools/contrast-report.md` |
| One primary action per screen, in the thumb zone; fixed button order | **Met** | Home FAB centred at the bottom; editor «حفظ» once at the bottom (`AppUiTest`: exactly one node); reminder screen «تم» at the end (`theReminderScreenPutsDoneAtTheEnd`, RTL position checked) |
| Stable components only | **Met** | No experimental Expressive APIs |
| Bundled Noto Sans Arabic, no clipped dots | **Met** | `LineHeightStyle.Trim.None` on every style; no fixed text heights; screenshots in `screenshots/qc/` |
| One icon family with RTL mirroring | **Met** | Material Symbols Rounded vendored as `res/drawable/ms_*.xml` (Apache-2.0); mirroring set per DESIGN-SPEC §3.4 only on symbols that show direction; the icons library was removed |
| Motion (shared-axis transitions, row exit animation) and reduced motion | **Partial** | `LocalReducedMotion` is provided; screen changes are instant (no slide), and rows have no exit animation yet |
| QC screenshots | **Met** | `screenshots/qc/`: every 1.0.0 screen and sheet in Arabic and English, light and dark, and at 200 % text (`QcScreenshotsTest`; sheets and dialogs captured as their own windows) |

## Accessibility: WCAG 2.2 AA, as applied to mobile by EN 301 549

| Criterion | Status | Evidence |
|---|---|---|
| 1.4.3 / 1.4.11 contrast | **Met** | All 51 colour pairs pass the contrast script (≥ 4.5:1 for text, ≥ 3:1 for UI) |
| 1.4.4 resize text to 200 % | **Met (JVM)** | Text in sp; at large text the reminder screen puts the title first and keeps «تم» fully visible above «أجّل» (`AlertUiTest`, `BlackBoxFixesUiTest`); sheets pin their buttons; segment rows become dialog rows; checked again by the black-box tester at font scale 2.0. Robolectric scales text linearly, not with Android 14's non-linear curve |
| 1.4.1 nothing by colour alone | **Met** | Overdue: icon, section title and words; priority: flag icon and word; permission status: icon and word |
| 2.5.8 target size (48 dp, Android) | **Met** | Test fails if any clickable node on Home, the editor or the reminder screen is under 48 × 48 dp (it found the 40 dp icon buttons, now fixed) |
| 4.1.2 name, role, value (TalkBack) | **Met (JVM)** | Every icon button has a label and a tooltip; rows have a spoken summary and custom actions «تم», «أجّل», «تحديد»; steps are checkboxes with state; the black-box driver reads the merged tree as TalkBack does (it found the unnamed FAB, now fixed) |
| 2.3.1 no flashing | **Met** | No flashing or looping animation |
| Tested with TalkBack on a phone | **Gap** | Only semantics tests on the JVM |

## Security and privacy: OWASP MASVS v2

| Control | Status | Evidence |
|---|---|---|
| MASVS-STORAGE-1/2: data in private storage only, no leak through backups or logs | **Met** | `files/data/data.json`; cloud backup excludes everything; phone-to-phone transfer only (D-008); no logging of content |
| MASVS-PRIVACY-1..4: minimal data, no tracking, user control | **Met** | Internet for updates only, GitHub hosts only over HTTPS (D-081, 1.0.5; network_security_config and tests), no analytics, no identifiers; «أخفِ التفاصيل» per reminder and per phone (kept even when Android shows all content); logs (schedule, alerts) hold no content; the compatibility report is shown in full before the user shares it; delete-all; a privacy page lists every permission |
| MASVS-PLATFORM-1: exported components | **Met** | `SystemReceiver` and `LockedReceiver` (system broadcasts only), `ShareActivity` (text only, 2,000 characters, never saves without the preview), the Quick Settings tile (behind `BIND_QUICK_SETTINGS_TILE`), plus AndroidX's profile installer behind DUMP (tested); fuzzed intents change nothing (torture) |
| MASVS-PLATFORM: PendingIntents | **Met** | All `FLAG_IMMUTABLE`, explicit components |
| MASVS-PLATFORM-3: UI on the lock screen | **Met** | The title is hidden on the lock screen with the privacy setting; editing asks to unlock |
| Imported files | **Met** | Backups read in memory only (no zip-slip), 20 MB cap, checksums verified, tone copies checked against their SHA-256 names; encrypted backups (PBKDF2-HMAC-SHA256 600k, AES-256-GCM, header authenticated); `.ics` 5 MB / 5,000 parts; fuzzed (3,000 / 5,000 / 20,000 inputs, torture) |
| MASVS-CODE: release build | **Met** | R8 minify and resource shrinking; not debuggable; signed with the release key only |
| MASVS-CRYPTO | **Met** | Platform crypto only for the optional backup password (above); the weekly copy's password is wrapped by a non-exportable Android Keystore AES-GCM key |
| MASVS-NETWORK / AUTH | **n/a** | No network, no accounts |
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
| UAX #9 bidirectional text | **Met (JVM)** | User text inside interface sentences is isolated with `BidiFormatter` (`L10n.isolate`; `QcBidiTest`); the Arabic separator «، »; ranges with «إلى» |
| Hijri calendar through ICU | **Met (JVM)** | `android.icu` IslamicCalendar, Umm al-Qura; every day of 1300–1600 AH equals the JDK's HijrahChronology (`HijriTest`); a phone setting only (never written to the shared file) |

## Recurrence and interchange

| Standard | Status | Evidence |
|---|---|---|
| iCalendar RFC 5545 RRULE semantics | **Met** | Every repeat maps to an RRULE, including the Windows month-end rule (`BYMONTHDAY=28,29,30,31;BYSETPOS=-1`); an independent RFC 5545 library (ical4j, tests only) expands each exported rule to exactly the app's own occurrences, across DST (`IcsTest`) |
| `.ics` export | **Met** | VEVENT + one VALARM per early alert (and VTODO for untimed), folded at 75 octets, escaped; VTIMEZONE exact for every java.time zone (518 checked, 0 wrong); hourly repeats in UTC |
| `.ics` import | **Met** | Settings → «استيراد تقويم (.ics)»: RFC 5545 subset with a report in the app language of everything skipped or changed; our own export imports back with the same occurrences and alerts (`IcsImportTest`, torture `IcsPropertyTortureTest`: 6,000 reminders, 0 differences against an RFC 5545 reading) |
| The Windows app's format | **Met** | Repeat parity with the Windows code (600 cases in the suite, 30,000 in torture; 2 Windows-side DST bugs found and noted); Quick Add parity 2,196 cases; both directions: Android export → Windows read/edit/save → Android keeps the `android` object, tags and steps (`tools/interchange.sh`) |

## Release engineering

| Standard | Status | Evidence |
|---|---|---|
| SemVer 2.0.0 | **Met** | 1.0.0, `versionCode 3` (0.1.0 = 1, 0.1.1 = 2), same signing certificate |
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
