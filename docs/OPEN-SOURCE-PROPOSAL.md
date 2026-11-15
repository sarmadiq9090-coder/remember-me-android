**Carried out on 2026-11-15** (owner «انشر», brain D-079): the root [LICENSE](../LICENSE), [NOTICE](../NOTICE) and
[REUSE.toml](../REUSE.toml) are now the licence; the audit is [PUBLISH-AUDIT.md](PUBLISH-AUDIT.md). This page is kept as
the record of the proposal; where the drafts below differ from the root files, the root files apply.

# Open-source proposal: Remember Me for Android (D-079)

The owner's PRD lists «مفتوح المصدر» among the app's principles. Brain D-051 made Remember Me closed
source, and this repository's D-014 applies it. Under D-079 the repository is made ready for an
open-source release, but **nothing is published and no licence is claimed until the owner says «انشر»**,
as D-076 required for Scan Kiwi. The Windows app stays under D-051.

## Proposed licence: Apache-2.0

Why (as in D-076): the same licence as AndroidX, Jetpack Compose and Kotlin, which the app already
ships; an explicit patent grant; no trademark grant, so "Remember Me" / «ذكرني» and the FastKiwi
names and icon stay the owner's (a `TRADEMARKS` note would say so).

## Files to add on «انشر» (not before)

Root files are not added now: GitHub and licence scanners read a root `LICENSE` or `REUSE.toml` as the
project's licence, which would be a claim the owner has not made.

### `LICENSE`

The unmodified text of the Apache License 2.0 (already in `LICENSES/Apache-2.0.txt` for the
third-party components).

### `NOTICE`

```
Remember Me for Android («ذكرني»)
Copyright 2026 FastKiwi

This product includes software developed by The Android Open Source Project and JetBrains
(AndroidX, Jetpack Compose, Kotlin, kotlinx), under the Apache License 2.0, and the Noto Sans
Arabic font under the SIL Open Font License 1.1. See THIRD_PARTY.md.
```

### `REUSE.toml`

No source file gets an SPDX header, ever: `REUSE.toml` covers files by path, so no file is edited
twice and no header can be wrong in the meantime.

```toml
version = 1

[[annotations]]
path = ["app/**", "tools/**", "docs/**", "design/mockups/*.html", "design/mockups/kit.*", "design/tools/**",
        "*.md", "*.kts", "*.properties", "env.sh", "build.sh", ".gitignore", ".gitattributes", "اقرأني.txt"]
precedence = "aggregate"
SPDX-FileCopyrightText = "2026 FastKiwi"
SPDX-License-Identifier = "Apache-2.0"

[[annotations]]
path = ["app/src/main/res/font/**", "design/mockups/fonts/**"]
precedence = "override"
SPDX-FileCopyrightText = "Google LLC and the Noto Project Authors"
SPDX-License-Identifier = "OFL-1.1"

[[annotations]]
path = ["screenshots/**", "design/shots/**", "app/src/main/res/drawable/**", "app/src/main/res/mipmap-anydpi-v26/**"]
precedence = "aggregate"
SPDX-FileCopyrightText = "2026 FastKiwi"
SPDX-License-Identifier = "Apache-2.0"
```

`reuse lint` must pass before publication.

## Pre-publication audit (blocking, as in D-076)

1. **Secrets:** `keystore/`, `keystore-backup/` and `dist/` are git-ignored and absent from every commit
   (`git log --all -- keystore keystore-backup` returns nothing). No passwords in history.
2. **Personal data:** no local paths of the owner's PC in docs that would be published (env.sh,
   README build notes), no e-mail addresses other than support@fastkiwi.app, no private quotes.
3. **Screenshots and fixtures:** synthetic data only (invented titles).
4. **Code ported from the Windows app,** with each port's source date:
   - `core/Repeat.kt` from `src/core/repeat.js`, `core/Tasks.kt` from `src/core/tasks.js`,
     `core/Snooze.kt` from `ui/lib/alert-format.js`, the backup layout from `src/data/attachments.js`
     and `src/data/backups.js`: published under MIT before 2026-11-09, so clean.
   - Any later port (for example the Quick Add parser if it changes after 2026-11-09) needs the
     owner's explicit approval, because it would publish proprietary Windows code (D-051).
5. **Third-party notices:** THIRD_PARTY.md and the SBOM are current.

## Order of steps on «انشر»

1. The audit above, written down with its results.
2. Add `LICENSE`, `NOTICE`, `REUSE.toml`; `reuse lint` passes.
3. Mark D-014 superseded in `DECISIONS.md`; update the README licence line; open `CONTRIBUTING.md` to
   issues (pull requests stay closed until the owner decides).
4. The repository, release and site steps of D-076, through the one GitHub writer (the manager).
