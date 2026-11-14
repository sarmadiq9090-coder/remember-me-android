# Changelog

All notable changes to Remember Me for Android are listed here.
The format follows [Keep a Changelog 1.1.0](https://keepachangelog.com/en/1.1.0/), and versions follow
[Semantic Versioning 2.0.0](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

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
