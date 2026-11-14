# Third-party software

Remember Me for Android is proprietary FastKiwi software (D-051). It ships the components below, each
under its own licence; full texts are in [LICENSES/](LICENSES/). All are free and open source. Nothing
here sends data anywhere: the app has no internet permission.

## Shipped in the APK

| Component | Version | Licence | Use |
|---|---|---|---|
| Noto Sans Arabic (Regular, Medium, Bold) | from Google Fonts, as used by Scan Kiwi | SIL Open Font License 1.1 ([text](LICENSES/OFL-1.1-NotoSansArabic.txt)) | The interface font (Arabic and Latin). |
| Kotlin standard library | 2.2.21 | Apache-2.0 | Language runtime. |
| kotlinx.coroutines (android) | 1.10.2 | Apache-2.0 | Background work. |
| kotlinx.serialization (JSON) | 1.9.0 | Apache-2.0 | Reading and writing data.json as a JSON tree. |
| Jetpack Compose (ui, foundation, material3, material-icons-extended) | BOM 2025.12.01 | Apache-2.0 | The interface and its icons (Material Symbols/Icons, Apache-2.0). |
| AndroidX activity-compose | 1.11.0 | Apache-2.0 | Activities hosting Compose. |
| AndroidX core-ktx | 1.17.0 | Apache-2.0 | Notifications, permissions, compat helpers. |
| AndroidX appcompat | 1.7.1 | Apache-2.0 | Per-app language on every Android version. |
| AndroidX lifecycle (viewmodel-compose, runtime-compose) | 2.9.4 | Apache-2.0 | Lifecycle-aware state. |
| Their transitive AndroidX dependencies (annotation, collection, profileinstaller, savedstate, emoji2, …) | as resolved by Gradle | Apache-2.0 | Pulled in by the libraries above. |

Apache-2.0 text: [LICENSES/Apache-2.0.txt](LICENSES/Apache-2.0.txt). The app's About screen names the
font and the libraries.

## Used only to build and test (not shipped)

| Component | Licence |
|---|---|
| Gradle 8.14.3, Android Gradle Plugin 8.13.2, Android SDK build tools 36 | Apache-2.0 (SDK under the Android SDK License) |
| Eclipse Temurin JDK 21 | GPL-2.0 with Classpath Exception |
| JUnit 4.13.2 | EPL-1.0 |
| ical4j 4.0.8 (checks the `.ics` export against RFC 5545) | BSD-3-Clause |
| CycloneDX Gradle plugin 3.6.1 (writes the SBOM) | Apache-2.0 |
| Robolectric 4.16.1 | MIT |
| AndroidX Test, Compose UI Test | Apache-2.0 |
| Node.js (to run `tools/windows-golden.cjs` and `tools/windows-validate.cjs` against the Windows app's code) | MIT |

## Design tools (not shipped; `design/`)

| Component | Licence |
|---|---|
| Noto Sans Arabic and Noto Sans (mockup fonts, `design/mockups/fonts/`) | SIL OFL 1.1 |
| Playwright (renders the mockups to PNG) | Apache-2.0 |
| @material/material-color-utilities 0.3.0 (builds the colour scheme; not kept in the repo) | Apache-2.0 |

## Code from the Windows app

The repeat rules (`core/Repeat.kt`), task actions (`core/Tasks.kt`), snooze presets (`core/Snooze.kt`)
and the backup format are ports of FastKiwi's own Remember Me for Windows; no third-party code.
