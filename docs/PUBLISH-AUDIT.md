# Pre-publication audit — Remember Me for Android (brain D-079, D-076 style)

**Verdict: nothing blocks publication.** No secret, no personal data, every Windows port comes from MIT-licensed
code dated before 2026-11-09, and every third-party component is compatible with Apache-2.0.

- Date: 2026-11-15. Read-only audit by the publication session after the owner's «انشر».
- Scope: branch `master` (what is pushed to `main`): 160 commits up to 6a729f7 (1.0.4) plus the publication commits,
  1,735 blobs in the history, 942 files in the tree. Other local branches (work branches, `v1.0.0`) are not pushed.
- Re-run before the push: the write plan (`ops/publish-remember-me-android.sh`, step 0) re-checks authors, ignored
  paths and key patterns on `master` after the 1.0.5 merge. The 1.0.5 branch must also be checked for new Windows
  ports (none are expected: its update and report features follow Scan Kiwi, which is Apache-2.0 FastKiwi code).

## 1. Secrets — none

- `.gitignore` covers `keystore/`, `*.jks`, `*.keystore`, `keystore.properties`, `keystore-backup/`, `dist/`,
  `local.properties`, `.tools/`, `.gradle-home/`. `git log master -- keystore keystore-backup dist local.properties
  '*.jks' '*.keystore' keystore.properties .tools .gradle-home` returns nothing: no ignored path was ever committed.
- Every blob in the history was scanned for private keys, GitHub/AWS/Google/Slack tokens, Kaggle keys, JKS/PKCS#12
  magic bytes and password assignments: 0 keys, 0 tokens, 0 keystores.
- The password hits are code, not values: `app/build.gradle.kts` reads `storePassword`/`keyPassword` property
  names from the git-ignored `keystore/keystore.properties`; `BackupCrypto`, `AutoBackup` and the settings screens
  take a user-typed `CharArray`. The only literal is the test fixture `"كلمة سر 123"` in `BackupCryptoTest`, which
  encrypts a synthetic backup and is not a credential.
- The release APK was not rebuilt; the keystore was not read, printed or copied.

## 2. Personal data — none

- Every commit is authored **and** committed by `Claude <noreply@anthropic.com>` (all 160 history commits and the publication commits).
- E-mail addresses in the history: `support@fastkiwi.app` (public support address) and invented calendar UIDs in ICS
  test fixtures (`abc@google.com`, `omar@google.com`, `birthday-sara@google.com`, a random-looking UID on a
  synthetic "Team standup" event). No owner e-mail.
- Phone-like strings: only `0770 123 4567`, `07701234567` and `964 770 555 1234` in
  `app/src/test/resources/quickadd-nodate.txt`, a corpus of texts that must not be read as dates. Invented.
- No `C:\Users\…` path and no OneDrive path anywhere in the history. `env.sh` holds the build PC's `E:\fast kiwi pro
  neq\…` toolchain paths, which name no person (as in Scan Kiwi).
- The owner's first name appeared only in the draft NOTICE inside `docs/OPEN-SOURCE-PROPOSAL.md`; it is removed
  from the tree (it remains in that file's history and is already public in the GitHub account name). The root
  NOTICE says "FastKiwi", as Scan Kiwi does.
- `docs/PRD-owner-2026-11-14.md` is the owner's product requirements document: product content only, no personal
  data. Advisory, not blocking.
- Images: 451 PNGs in the tree (screenshots, design shots). Their chunks are only IHDR, sRGB, sBIT, IDAT and IEND:
  no text, EXIF or GPS chunks. All show sample data (invented titles such as "Call the electrician").
- Fixtures: `torture/blackbox/fixtures/backup-sample.zip` holds 3 invented reminders («دفع الإيجار»,
  «الاتصال بالكهربائي», «شراء هدية»); `tone.wav` is a 1-second synthetic tone.

## 3. Code ported from Remember Me for Windows — all from MIT code before 2026-11-09

Windows licence history: MIT from the first public release (8a64fbb, 2026-10-24) until the licence commit 05e7da6
("Remember Me becomes proprietary (D-051); versions <= 1.7.0 stay MIT", authored 2026-11-10). The last `main`
commit before 2026-11-09 is **0ff9f3a** (2026-11-08, LICENSE = MIT). Every Windows source file below is
**byte-identical** between 0ff9f3a and today's `origin/main` (0841976), so each port is of MIT-licensed code; no
Windows commit after 2026-11-09 touched a ported file. (Post-2026-11-09 Windows commits only touched the licence,
packaging, locales and the About/"More from FastKiwi" screen, none of which was ported.)

| Android file (first commit) | Windows source | Last Windows commit to that file | Date |
|---|---|---|---|
| `core/Repeat.kt` (f6e48b2, 2026-11-14) | `src/core/repeat.js` (`nextOccurrence`) | 0c81e17 | 2026-10-25 |
| `core/Tasks.kt` (f6e48b2) | `src/core/tasks.js` | e4a247d | 2026-11-06 |
| `core/Organize.kt` (d4be11c, 2026-11-15) | `src/core/tasks.js` (`normalizeTags`, `normalizeChecklist`, folding) | e4a247d | 2026-11-06 |
| `core/Snooze.kt` (f6e48b2) | `ui/lib/alert-format.js` | bcc8f93 | 2026-10-30 |
| `core/Model.kt`, `data/Store.kt` (f6e48b2) | `src/core/store.js` (`validateState`, file scheme) | 08c0eb1 | 2026-11-06 |
| `data/Backup.kt` (f6e48b2) | `src/data/backups.js`, `src/data/attachments.js` (backup layout) | 843d0e4 / 0c81e17 | 2026-11-03 / 2026-10-25 |
| `data/IcsImport.kt` (9258cdd, 2026-11-15) | `src/data/interchange.js` (`importTasks` behaviour) | 22052ca | 2026-10-25 |
| `core/Planner.kt` (f6e48b2) | `src/core/repeat-beep.js` (`createRepeatBeep` limit) | 7757e11 | 2026-10-25 |
| `core/quickadd/QuickAdd.kt`, `ArabicDates.kt`, `EnglishDates.kt`, `Normalize.kt` (e559555, 2026-11-15) | `src/core/natural-dates.js`, `natural-dates-ar.js`, `natural-dates-en.js` | 84f82fa | 2026-11-06 |
| Test data `windows-golden.json`, `quickadd-golden.json` and `RepeatTest` cases | output of `repeat.js` / `parseWhen()`; cases of `tests/scheduling.test.js`, `tests/natural-dates.test.js` | 0c81e17 / 84f82fa | 2026-10-25 / 2026-11-06 |
| App icon (`ic_launcher_*`, `ic_brand_mark`) | brand v2 icon | 1e56a0b | 2026-11-06 |

**Post-2026-11-09 ports: none.** The publication was not stopped. `tools/*.cjs` and `torture/tools/*.cjs` only
`require()` the Windows code from a local checkout at test time; they contain no Windows code. FastKiwi is the
copyright holder of both apps, so it may license these ports under Apache-2.0 (THIRD_PARTY.md says where they come
from). The icon and names are trademarks, not licensed (NOTICE, README).

## 4. Third-party licences — compatible, NOTICE complete

- Shipped (the 1.0.4 SBOM, 120 components): 117 Apache-2.0 (AndroidX, Jetpack Compose, Kotlin, kotlinx) and the
  three Noto Sans Arabic fonts under OFL-1.1. OFL-1.1 allows bundling the font with any software; the font stays
  under OFL (`LICENSES/OFL-1.1.txt`, REUSE override).
- Vendored icons: 94 Material Symbols Rounded (`ms_*.xml`) and 4 Material Icons paths (`ic_add`, `ic_check`,
  `ic_snooze`, `ic_warning`), Apache-2.0, Google. The four Material Icons were not listed before; now they are in
  THIRD_PARTY.md, NOTICE and REUSE.toml.
- Build and test only (not shipped): Gradle and the wrapper, AGP, CycloneDX plugin, AndroidX Test, Compose UI test
  (Apache-2.0); Robolectric, Node.js (MIT); ical4j (BSD-3-Clause); JUnit 4 (EPL-1.0); Temurin (GPL-2.0 with
  Classpath Exception); design-only Noto Sans and Playwright. None is distributed in the APK.
- NOTICE names FastKiwi, AndroidX/Compose/Kotlin, Material Symbols and Icons, and Noto Sans Arabic (OFL-1.1), and
  points to THIRD_PARTY.md. `reuse lint`: compliant, 942/942 files, licences Apache-2.0 and OFL-1.1.

## 5. Build supply chain

- Gradle wrapper 8.14.3 added (`gradlew` mode 100755 in git; wrapper jar and distribution SHA-256 identical to Scan
  Kiwi's, which `gradle/actions/wrapper-validation` accepts).
- `gradle/verification-metadata.xml` (SHA-256, `verify-metadata` on, no trusted-artifact exceptions) was generated
  with `--write-verification-metadata sha256` from an **empty** `GRADLE_USER_HOME` on a fresh clone, running
  `:app:assembleRelease :app:lintRelease :app:releaseSbom` and one test class; the Linux and macOS `aapt2` jars were
  added by hand (Linux hash re-checked against Google Maven).
- **Cold-cache verification:** a second fresh clone of the publication commit with another empty `GRADLE_USER_HOME`
  ran the CI tasks `:app:testDebugUnitTest :app:assembleRelease :app:lintRelease :app:releaseSbom` with
  verification on (Windows build PC; CI runs the same on Linux with the added `aapt2` jar).
- **Result: BUILD SUCCESSFUL from the cold cache at 2e30fe0, no dependency-verification failure;** 818 tests, 0
  failed, 0 errors (116 skipped: the opt-in torture suite and the Windows interchange phase); `lintRelease`: "No
  issues found."; unsigned release APK and SBOM built.
