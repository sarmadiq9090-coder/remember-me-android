# Data format — Remember Me for Android 1.0.0

*F-090. The shared file, the backup files, and what Android adds. A unit test (`DataFormatDocTest`) checks that the
member table below lists exactly the members the code writes (`core/AlertExt.kt`).*

## 1. The shared file: `data.json`

The phone keeps one file, `files/data/data.json`, in the **same format as Remember Me for Windows** (`version: 1`):

```json
{
  "version": 1,
  "tasks": [ { "id": "…", "title": "…", "dueAt": "2026-11-15T09:30:00.000Z", "repeat": "none", … } ],
  "notes": [],
  "attachments": [],
  "settings": { "theme": "system", "sound": true, "remindAgain": 0, "weekStart": 6, "snoozePresets": [ … ] }
}
```

- Times are UTC ISO strings with milliseconds (`toISOString()`).
- A task's fields are the Windows fields (`id`, `title`, `body`, `priority` `normal|medium|high`, `category`
  `personal|work|study`, `dueAt`, `scheduleAt`, `repeat`, `repeatDays`, `repeatEvery`, `repeatHours`, `repeatFrom`,
  `remindBefore`, `snoozeCount`, `snoozedAt`, `completedAt`, `deletedAt`, `sourceId`, `createdAt`, `updatedAt`).
  Every other key of a task (for example `tags`, `checklist`, `attachments`, anything newer) is kept and written back
  unchanged.
- `tags` (F-062): up to 10 strings of up to 30 characters, Windows' `normalizeTags` rules.
- `checklist` (F-070): up to 50 steps `{ "id", "text", "done" }`, Windows' `normalizeChecklist` rules.
- Writes are durable: the old file is copied to `data.json.bak`, the new one is written to `data.json.tmp`, synced
  and renamed. A file that cannot be read is kept as `data.json.damaged-<time>`.

### Shared settings Android writes

Only values Windows accepts: `theme`, `sound`, `weekStart` (6, 0 or 1), `arabicMonths`, `remindAgain` (0, 2, 5 or
15 only) and `snoozePresets` (always six valid presets of the kinds `in` `{minutes 1…43200}`, `today` `{time ≥ 01:00}`,
`tomorrow` `{time}`, `week`, `pick`). Android **never** writes `settings.hijri` or `settings.digits` (Windows deletes
them).

## 2. Task `android` members

Android-only choices of a reminder live under one key, `android`, an object. Windows keeps it untouched and ignores
it. An absent key or member means the default. Unknown members are kept (forward compatibility).

| Member | Values | Meaning |
|---|---|---|
| `v` | `1` | version of this object |
| `tone` | `{"kind": "default" \| "system" \| "bundled" \| "file" \| "silent", "ref": "…", "title": "…"}` | the reminder's tone (F-014); `system` = content URI + title, `bundled` = `chime-1..3`, `file` = SHA-256 of the copy in `files/tones/` |
| `style` | `"normal"` \| `"alarm"` | alert type; absent = by priority (high → alarm) |
| `fullScreen` | `"on"` \| `"off"` | absent = the phone's full-screen setting |
| `pin` | `"on"` \| `"off"` | absent = the phone's pin setting |
| `vibrate` | `true` \| `false` | absent = the phone's vibration setting |
| `alerts` | `[1440, 60]` | early alerts in minutes (5, 10, 15, 30, 60, 1440), at most 3; `remindBefore` holds the nearest |
| `speak` | `true` \| `false` | read the title aloud (F-031) |
| `prayer` | `{"name": "fajr\|dhuhr\|asr\|maghrib\|isha", "offset": 10}` | prayer-relative time (F-050); the task is also a valid Windows daily task |
| `hijri` | `{"month": 9, "day": 1}` | yearly Hijri repeat (F-051); the task is also a valid Windows yearly task on the Gregorian date |

What Windows does with them is listed before every Windows-format export (F-092): every-few-hours repeats become
one-time, prayer-relative reminders a fixed daily time, Hijri ones a Gregorian yearly date, several early alerts the
nearest one; tone, alert type, full screen, pin, vibration and read-aloud are kept but unused.

## 3. Phone-only settings (never in `data.json`)

SharedPreferences file `device`: language, lock-screen details, default tones (`normalTone`, `importantTone`),
`importantStyle`, `fullScreenMode`, `pinDefault`, `vibrateDefault`, `vibrationPattern`, `ringSeconds`, `speakDefault`,
`hijriDisplay`, `hijriAdjust` (−2…+2), `prayerMethod`, `prayerAsr`, `prayerCity` (a city's English name from the
bundled list, or `geo:<lat>,<lon>,<zone>`), `summaryMinutes` (−1 = off), `historyOn`, `autoCompleteSteps`, `swipeOn`,
`encryptBackups`, the wrapped backup password, `autoBackupUri`, `lastAutoBackup`, `autoBackupFailedAt`, and the
engine's own bookkeeping. Lists and saved filters: file `filters`. Logs: `alert_log`, `schedule_log`. Before the first
unlock after a restart: device-protected file `boot_times` (only due times and whether each is an important alarm).

## 4. Backup files

**Plain backup (`.zip`)** — the Windows backup folder layout, so Windows can restore it after extracting:

```
RememberMe-backup-<time>/backup.json   {"app": "Remember Me", "kind": "manual", "createdAt", "algorithm": "sha256",
                                        "files": {"data.json": "<sha256 of the exact bytes>"}, "source": "android"}
RememberMe-backup-<time>/data.json     the data, attachments removed (Windows-only files are not on the phone)
RememberMe-backup-<time>/tones/<sha256>.<ext>   only with «تضمين النغمات المختارة من ملفاتي» (off by default):
                                        the tone copies in use, listed in backup.json "files"; Windows ignores them
```

Restore accepts this zip, a zip of a Windows backup folder, or a bare `data.json`; the checksum is verified when
`backup.json` is present, and nothing is replaced before the summary is confirmed (a safety copy of the current data
is kept first).

**Encrypted backup (`.rmbk`, F-086)** — the bytes of the plain zip sealed with a password (format in
`data/BackupCrypto.kt`): magic `RMBK1`, version 1, PBKDF2-HMAC-SHA256 (600,000 iterations, 16-byte salt) and
AES-256-GCM with the 62-byte header as associated data. Windows cannot open it yet.

**Weekly automatic copy (F-087)** — `RememberMe-auto-<yyyyMMdd-HHmmss>.zip` (or `.rmbk` when encryption is on) in the
folder the user picked; the newest 5 are kept.

**Safety copies** — `files/data/safety/before-<reason>-<time>.json`, the newest 3 per reason.

**Calendar (`.ics`)** — export and import (F-085) of a subset of RFC 5545; read-only, not a backup.
