# Torture suite — Remember Me Android 1.0.0

A release gate (FEATURES.md, "Gates", item 3): tests that try hard to break the app, so that real bugs are
found before a release. A failing torture test is a finding, not noise: it shows a bug, and stays failing until
the app is fixed. Nothing here changes app code.

## Run

From the worktree, in Git Bash:

```sh
torture/run.sh            # everything, heavy parts included (long)
torture/run.sh --light    # the light counts only (a few minutes)
torture/run.sh --tests 'com.fastkiwi.rememberme.torture.ChaosTortureTest'   # one class
torture/run.sh --seed 42  # replay every random test with seed 42
```

`run.sh` sources `/e/fast kiwi pro neq/rm-wt-env.sh`, runs

```sh
gradle --no-daemon --console=plain --max-workers=2 :app:testDebugUnitTest --tests 'com.fastkiwi.rememberme.torture.*' --rerun
```

with `TORTURE=1` (and `TORTURE_HEAVY=1` unless `--light`), then writes `torture/reports/summary-<time>.txt`
(tests, failures and the first lines of each failure, by class). The Gradle log goes to
`app/build/torture/gradle-<time>.log`.

Switches (environment variables, inherited by Gradle's test JVM; `-Dtorture=true` / `-Dtorture.heavy=true` /
`-Dtorture.seed=N` work too when set on the test JVM):

| Variable | Effect |
|---|---|
| `TORTURE=1` | Runs the torture tests. Without it every torture test is skipped, so the ordinary `testDebugUnitTest` is not slowed or blocked by them. |
| `TORTURE_HEAVY=1` | The heavy parts: 10,000 reminders through the engine on three SDKs, and larger random counts (see each suite). Implies `TORTURE=1`. |
| `TORTURE_SEED=<n>` | The seed of every random test. Each test prints its seed (`[torture] <name> seed=<n>`); the default seeds are fixed, so runs are reproducible. |
| `REMEMBER_ME_DIR` | The Windows app's checkout for the parity test (default: `../Remember-Me` next to the worktree). |
| `NODE_BIN` | The Node executable for the parity test (default: `node` on the PATH). |

`--rerun` is needed because Gradle's up-to-date check does not see environment variables.
Never run `gradle --stop` or kill Java processes: the Gradle cache is shared with other builds.

Robolectric classes run on SDK 26, 33 and 36 (`@Config(sdk = [26, 33, 36])`), and open no Compose screen.

## Suites

All classes are in `app/src/test/java/com/fastkiwi/rememberme/torture/` (the `torture.blackbox` package and
`torture/blackbox/` belong to the separate black-box driver and are not described here).

### 1. Stress
- `StressTortureTest` (Robolectric): 10,000 mixed reminders (repeats, notices, snoozes, done, trashed) through
  `Engine.sync` (heavy): one alarm per slot, the shown ones ring exactly once, the rest are counted in the
  summary; 500 reminders in the same minute: all marked, eight ring, and as each is done the next one reaches the
  screen; a storm of 200 runs from 8 threads (boot, time change, time zone, alarm, app start, data change) ends in
  the same state as one run; the number of NotificationManager calls per run (a binder call each on a phone).
  A loud announcement is counted as a `RingService` start (the app plays the tone itself, F-016), so a repeated
  sound is seen even when the notification on screen looks the same.
- `PlanStressTortureTest`: the pure planner walked through three days of wake-ups with 2,000 (heavy 10,000)
  reminders, with remind-again off and on: every occurrence loud once (or once per remind-again slot), every
  notice once, slots always the earliest moments ahead, a second run at the same moment silent.

### 2. Time
- `TimeRepeatTortureTest`: every repeat mode across the DST transitions of Berlin, New York, Baghdad (none),
  Tehran (historic, 2015–2021), Sydney, Lord Howe (30-minute DST) and Santiago (midnight change), against
  calendar arithmetic written in the test; 50 years of month ends and 29 February; the same 50 years through
  complete / snooze / edit and a data.json write and read between steps; catching up after gaps of up to
  3,000 days.
- `TimeEngineTortureTest` (Robolectric): random clock jumps of hours and days, forwards and back; the clock set
  back and forward again; a time-zone change while reminders are due; daily 02:30 across DST nights; undo after
  «تم».

### 3. Property (seeded)
- `TaskOpsPropertyTortureTest`: 1,000 (heavy 10,000) sequences of 60 random operations (add, edit with a stale or
  current editor, complete, undo, double tap, snooze, skip, trash, restore, purge, purge trash, reopen, time,
  write/read): no task lost, ids unique, one history copy per completed repeat, undo exact, stale taps change
  nothing, unknown fields survive, data.json round trip exact.
- `RepeatParityTortureTest`: 6,000 (heavy 30,000) random cases in 20 zones, answered by the Windows app's own
  `src/core/repeat.js` through `torture/tools/repeat-parity.cjs` (required read-only; nothing is installed). A
  difference where Node's and Java's time-zone data disagree is listed apart as "tzdata".
- `IcsPropertyTortureTest`: 1,200 (heavy 6,000) random reminders exported to .ics, parsed and expanded by ical4j:
  occurrences, titles, alarms; then imported back with `IcsImport` (F-085 round trip). Plus control characters
  in a title. Failures are grouped by kind (`[dtstart-off-rule]`, `[occurrences]`, `[import-next]`…).
- `IcsTimezoneTortureTest`: the exported VTIMEZONE of every zone with DST (heavy: every zone), read by the letter
  of RFC 5545 (onsets = DTSTART in TZOFFSETFROM + RRULE expanded by ical4j's `Recur`), against Java's offsets every
  week and an hour either side of each transition for ten years; and `IcsImport` reading every exported zone back.
  (ical4j's own `TimeZone`/`ZoneRulesBuilder` read an onset's local time as UTC, so they are not used as the oracle.)
- `EnginePropertyTortureTest` (Robolectric, SDK 33 and 36): 60 (heavy 500) sequences per SDK, different on each, of
  40 random engine runs (alarm, boot, time, zone, start), «تم», «أجّل», swipes and new reminders, with the clock
  moving to the planned wake-up, late, or in jumps: no occurrence rings twice, the newest eight due are on screen
  (minus swipes past the re-post limit), the alarm clock is the next due time.

### 4. Fuzz
- `BackupFuzzTortureTest`: random, truncated, mutated and spliced backup zips and data.json; hostile shapes
  (newer version, wrong types, deep JSON, 200,000 tasks, zip bomb, path traversal, 20,000 entries…); a backup
  that is read must also be writable by the store. Encrypted backups: wrong password, truncation, bit flips,
  appended bytes, a huge length field → WrongPassword or Damaged only.
- `IcsFuzzTortureTest`: 5,000 (heavy 40,000) mutations of Google, Outlook, Apple and this app's own files; hostile
  files (nesting 10,000 deep, 60,000 events, a 5 MB line, 100,000 folds, 300 time zones); nothing dropped silently.
- `QuickAddFuzzTortureTest`: 20,000 (heavy 200,000) mixed Arabic / English / emoji / RTL-mark strings on the strict
  parser: no crash, never a past time; the no-date corpus with everyday decorations still holds no date. Every
  decorated text that does read as a date is also read by the Windows app's own `parseWhen()`
  (`torture/tools/quickadd-windows.cjs`), so the report says which are Android-only and which both apps share.
- `ReceiverFuzzTortureTest` (Robolectric): notification actions and receiver intents with missing, garbage and
  wrong-typed extras, unknown actions and stale occurrences: no crash on any thread, no change to data.json.

### 5. Chaos
- `StoreChaosTortureTest`: every on-disk state a process death between the write steps can leave (.bak, partial
  or complete .tmp, deleted data.json), torn files, both copies damaged, two damages in one second.
- `ChaosTortureTest` (Robolectric): disk full (writes throw), notifications and exact alarms revoked mid-flow,
  actions on deleted or done reminders, double taps (also from 4 threads), a stale tap, an alarm with no data file,
  an imported id the engine logs cannot hold, the direct-boot path (no title in device-protected storage, a locked
  restart rings once and the unlock does not ring again).

### 6. Performance
- `PerfTortureTest`: plan 10,000, search 5,000, backup export/read and data.json decode of 5,000, .ics export and
  import of 5,000. Every line says "JVM, not a phone" and goes to `torture/reports/perf-<date>.txt`, with the
  FEATURES.md gate where there is one. Ceilings are generous (5× the gate). The heavy stress run adds the time of
  one `Engine.sync` with 10,000 reminders under Robolectric.

### 7. UI monkey (later)
Placeholder. After the UI is merged: seeded random Compose input on Robolectric (SDK 33 and 36 only; Compose on
SDK 26/27 never idles under Robolectric), 10,000 events per screen, no crash, no data change without a save.
A real `adb monkey` needs a connected phone and is not claimed.

## Reports

`torture/reports/` holds the summaries and perf lines of runs (`summary-*.txt`, `perf-*.txt`, `stress-*.txt`,
`time-*.txt`, `parity-*.txt`, `ics-*.txt`, `quickadd-*.txt`). `torture/tools/summarize.cjs` rebuilds a summary
from `app/build/test-results/testDebugUnitTest`.
