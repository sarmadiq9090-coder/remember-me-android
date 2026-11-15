# Changelog

All notable changes to Remember Me for Android are listed here.
The format follows [Keep a Changelog 1.1.0](https://keepachangelog.com/en/1.1.0/), and versions follow
[Semantic Versioning 2.0.0](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [1.0.4] - 2026-11-15

The owner on 1.0.3's sheet: «الازرار يجب ان تكون افضل ومرتبه اكثر اجعلها تبدو احترافيه».

### Changed

- «تذكير جديد» is a full-screen Material 3 window instead of a bottom sheet: a top bar with «×» (asks
  «لم يُحفظ هذا التذكير بعد. تجاهله؟» when there is text; Back too), the title and «حفظ», which the keyboard can no
  longer cover; the field with the mic inside and the title it will save under it; then 56 dp list rows with
  light dividers: «الموعد» (the moment read from the text or picked, shown once, its own ✕), «التصنيف»,
  «الأهمية», «من قالب», «خيارات أكثر». The preview card (which repeated the date) and the chips are gone. All of
  1.0.3's behaviour stays: the text sync, a past time, saving without a date, voice, the draft question.

## [1.0.3] - 2026-11-15

The owner asked for a time picker in Quick Add («اضف موعد تذكير في هذه شاشه»).

### Added

- Quick Add: a «الموعد» chip, first in the row, opens the Material 3 date then time pickers (today and the next
  full hour by default; 12/24 h as the phone). It shows the moment («اليوم، 5:30 م»), has its own ✕, and TalkBack
  reads «الموعد: …» or «الموعد: غير محدد». It follows the time read from the text until the person picks one,
  which then wins. A time that has passed offers «غدًا في الوقت نفسه». The pickers use Western digits and stop
  growing at 130 % text, where their fixed cells would overlap.

## [1.0.2] - 2026-11-15

Voice input again, from the owner's phone: Google's «ميزة "البحث الصوتي" غير متاحة» for Arabic (Iraq).

### Fixed

- The phone itself is set to Arabic (Iraq), and 1.0.1 tried the phone's language first, offline. Now the app
  first asks the speech service which languages it supports (the standard language-details broadcast, and on
  Android 13+ the service's own list, including what is on the phone) and picks one it has: the phone's, then
  ar-SA, ar-EG, ar-AE, ar. ar-IQ is used only when the service lists it. Without an answer it starts with ar-SA.
  Offline is asked only for a language that is on the phone.
- Quick Add's «خيارات أكثر» then Back no longer drops the typed reminder without a question.

## [1.0.1] - 2026-11-15

Fixes voice input, found on the owner's phone. Installs over 1.0.0, 0.1.1 and 0.1.0 and keeps all data.

### Fixed

- Voice input no longer leaves you at Google's «ميزة "البحث الصوتي" غير متاحة» dialog. The app asked for
  Arabic (Iraq), a region that came only from the month-name setting; it now asks for the phone's own
  language, then plain Arabic, then Arabic (Saudi Arabia), then the service's own language, keeps the one that
  works and skips one that failed. The mic shows only when the phone has a speech service. After an error, a
  cancel or no text, an Arabic message shows and the cursor returns to the field with the keyboard open.

### Changed

- Privacy: About, the first-use note, the privacy page and the guide say plainly that voice input goes
  through the phone's speech service (usually Google's), which may work over the internet, and that the app
  asks it to work offline when it can. No permission was added (still no microphone or internet permission).

## [1.0.0] - 2026-11-15

One release with every feature the feature council approved (docs/FEATURES.md, 80 features). Installs over
0.1.0 and 0.1.1 and keeps all data (a safety copy is kept first). Still to be tried on the owner's phone.

### Added

- Alerts: «نوع التنبيه» (normal reminder that follows the phone's sound mode, or «منبّه مهم» that rings at
  alarm volume even in silent mode); never bypasses Do Not Disturb; a tone per reminder and two defaults
  (phone tones, three Remember Me tones, a file copied into the app, or silent); the app plays the tone in a
  short foreground service with a fallback chain, so a reminder is never silent by accident; ring duration;
  vibration patterns; full-screen mode; pinned notifications that come back up to 5 times; «أخفِ التفاصيل» on
  the lock screen with «تم» and «أجّل» kept; «أجّل حتى…» and «تراجع» on the notification; up to 3 early alerts;
  before-unlock reminders after a restart; "arrived late" notes; morning summary; read aloud (optional).
- «صحة التذكيرات» (grouped checks, the source of each problem, one fix per row, unknown never shown as
  ready), the alert test with three delays, the alert log, the compatibility report, DND help.
- Quick Add (the Windows parser ported, with a preview and questions; numeric dates, every N hours and prayer
  times added), templates, voice input, share to the app, a Quick Settings tile, widget «تم».
- Prayer-relative reminders (10 methods, cities, no location permission) and the Hijri calendar (Umm al-Qura)
  with Hijri yearly repeats.
- Home: swipe actions with undo, bulk selection, quick-filter chips for lists, search across tags and states,
  agenda/day/week/month views, a two-pane tablet layout. Tags, lists and saved filters; steps (checklist) with
  auto-complete; change history (optional).
- Data: password-encrypted backups, a weekly automatic copy to a folder, calendar (.ics) import, delete all,
  tone copies in backups (optional), a privacy page and docs/DATA-FORMAT.md.
- Design 1.0.0 (DESIGN-SPEC v2): Material Symbols Rounded icons, the new editor, sheets, reminder screen dock
  with a 1-second tap guard; Arabic and English user guides; «ما الجديد».

### Changed

- New notification channels (fixed ids) and one notification id per reminder; 0.1.x notifications are replaced
  on the first run after the update.
- High priority rings as «منبّه مهم» by default (Settings or «ما الجديد» turn it back to normal).
- Permissions: FOREGROUND_SERVICE added (the tone service); nothing else.
- APK 2.13 MB → about 2.8 MB: Quick Add's parser and corpus rules, prayer times and their city list, the
  calendar views, the new screens and the vendored icons (the icons library was removed).

### Fixed

- Every S1/S2 found by the independent bug hunt, the torture suite and the black-box user test (see
  docs/QC-1.0.0.md and DECISIONS.md), each with a test that prevents it coming back.

## [0.1.1] - 2026-11-14

### Added

- Before saving a backup for the Windows app, reminders that repeat every few hours are listed with a
  warning in Arabic: Remember Me for Windows does not support that repeat yet, so they become one-time
  there. Save anyway, or cancel.

## [0.1.0] - 2026-11-14

First build, for the owner only. Not yet tried on a real phone.

### Added

- Reminders with title, note, time (or none), repeat (daily, weekly, chosen weekdays, every N days,
  every N hours, monthly, yearly), early notice, priority and category, in the Windows app's data format.
- Exact alarms (alarm clock) re-armed after a reboot, an app update, a clock change and a time-zone change.
- A due reminder stays as a notification with «تم» and «أجّل 10 دقائق» until it is dealt with (it comes
  back quietly if swiped away), plus a full-screen reminder screen with Done, Skip and six snooze choices.
- Home as one list: Overdue, Today, Upcoming, and a folded Done section, with the time as each row's
  main element; search that ignores diacritics and hamza forms; a 30-day trash; a themed home-screen
  widget; a "New reminder" launcher shortcut.
- «السماح بالعمل في الخلفية»: essential, recommended and tips, each with one button, plus the phone
  maker's own steps.
- Export and import as a zip that Remember Me for Windows restores; up to 3 safety copies before an import.
- iCalendar (.ics) export for other calendar apps.
- Arabic by default and English; Western digits; light and dark; per-app language.
- No internet permission.

### Security

- Only the system-broadcast receiver is exported; all PendingIntents are immutable; nothing goes to
  cloud backup; imported zips are read in memory with a size cap and checksum.
