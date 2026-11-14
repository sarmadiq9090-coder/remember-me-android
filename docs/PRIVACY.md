# Privacy — Remember Me for Android 1.0.0

*F-089. The same content is in the app: Settings › حول › الخصوصية. A unit test (`PrivacyDocTest`) checks that every
permission in `AndroidManifest.xml` has a line here and in the app.*

All your data stays on your phone. Remember Me has **no internet permission**: it sends nothing anywhere and
receives nothing, and it collects no statistics. There are no ads and no accounts.

## Every permission and why

| Permission | Why |
|---|---|
| `POST_NOTIFICATIONS` | To show a reminder when it is due, with «تم» (Done) and «أجّل» (Snooze). |
| `USE_EXACT_ALARM` | So a reminder arrives at the minute you chose, not later. |
| `SCHEDULE_EXACT_ALARM` | The same permission on Android 12 only, where it has another name (`maxSdkVersion 32`). |
| `USE_FULL_SCREEN_INTENT` | To show a reminder full screen while the phone is locked. You can turn it off in Settings. |
| `RECEIVE_BOOT_COMPLETED` | To set your reminders' alarms again after the phone restarts. |
| `VIBRATE` | So the phone vibrates with a reminder if you want. |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | So Android can ask you to allow unrestricted battery use and reminders are not delayed. Nothing happens without your yes. |
| `FOREGROUND_SERVICE` | To play the reminder's chosen tone in a short service (type `shortService`) that stops by itself after the ring length (2 minutes at most), or at once on «تم», «أجّل», a swipe of the notification or a volume key. Nothing keeps running in the background afterwards; no internet. |

## What we never do

- No internet: the app sends and receives nothing.
- No location: you choose the prayer city from a list, or type coordinates.
- No access to your files, except the one file or folder you pick yourself (a backup, a tone, a calendar file, the
  weekly backup folder).
- No microphone, camera, contacts or calendar permission.
- No statistics, ads or accounts. Nothing runs "in the cloud".

## Voice input

Remember Me does not record sound and has no microphone permission. When you tap the voice button, Android opens
your phone's own speech recognition service, which returns only the text to the app. That service belongs to your
phone's maker or to Google and may send your voice to its company if it cannot work offline; Remember Me asks it to
work offline when it can. The first use says this before anything starts.

## Copies and files

- A backup can include the tones you chose from your files (off by default; Windows ignores them).
- A backup is saved only where you choose. It is not encrypted unless you turn on «تشفير النسخة» (then a lost
  password cannot be recovered, and Remember Me for Windows cannot open it yet).
- The weekly automatic copy goes to a folder you choose. If that folder syncs to an account, the app of that account
  uploads it, not Remember Me.
- When encryption is on, the backup password is kept on the phone, wrapped by a key of the Android Keystore that
  cannot leave the phone, so the weekly copy can be encrypted without asking.
- The compatibility report is shown in full and is shared only if you share it yourself. It holds no titles, notes,
  times, lists, phone identifiers, accounts, location or time zone.
- The change history and the alert log stay on the phone and are not in backups.
- «حذف كل البيانات» removes everything the app keeps on the phone and cancels its alarms and notifications.
