# Privacy — Remember Me for Android 1.0.5

*F-089. The same content is in the app: Settings › حول › الخصوصية. A unit test (`PrivacyDocTest`) checks that every
permission in `AndroidManifest.xml` has a line here and in the app.*

All your data stays on your phone. **Remember Me connects to the internet only to check for and download updates
from GitHub. It sends no reminder and no data about you. You can turn this off in Settings.** (1.0.5, the owner's
decision D-081; until 1.0.4 the app had no internet permission at all.) It collects no statistics. There are no
ads and no accounts.

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
| `FOREGROUND_SERVICE` | To play the reminder's chosen tone in a short service (type `shortService`) that stops by itself after the ring length (2 minutes at most), or at once on «تم», «أجّل», a swipe of the notification or a volume key. Nothing keeps running in the background afterwards. |
| `INTERNET` | Only to check for and download Remember Me's own updates from GitHub (`api.github.com` and GitHub's download hosts, HTTPS only; `res/xml/network_security_config.xml`): at most once every 7 days when the app is opened, or when you tap «تحقق من التحديثات». No reminder, no data about you, no identifier is sent; the request carries a generic `RememberMe/<version>` user agent. GitHub sees your IP address, as with any website. You can turn the automatic check off. |
| `ACCESS_NETWORK_STATE` | To tell Wi-Fi from mobile data, so an update downloads over Wi-Fi only if you chose «Wi-Fi فقط» (on by default). |
| `REQUEST_INSTALL_PACKAGES` | So Android's own installer asks you to install a verified update (newer, SHA-256 equal to the published `SHA256SUMS.txt`, signed with the same key). Nothing is installed without your tap. |

The last three rows exist only in the `full` build (the GitHub release `RememberMe-X.Y.Z.apk`). The `fdroid` build
(`RememberMe-X.Y.Z-fdroid.apk`, flavor `fdroid`) has no self-updater and does **not** declare `INTERNET`,
`ACCESS_NETWORK_STATE` or `REQUEST_INSTALL_PACKAGES`: it cannot connect to any network.

## What we never do

- Nothing about you or your reminders goes online. The only connection is to GitHub, for the app's own updates.
- «أبلغنا عن مشكلة أو اقتراح» sends nothing by itself: it opens your mail app or browser with the text you wrote, and
  device information (app version, Android, model, language) only if you tick the box. Never a reminder.
- No location: you choose the prayer city from a list, or type coordinates.
- No access to your files, except the one file or folder you pick yourself (a backup, a tone, a calendar file, the
  weekly backup folder).
- No microphone, camera, contacts or calendar permission.
- No statistics, ads or accounts. Nothing runs "in the cloud".

## Voice input

Remember Me does not record sound and has no microphone permission. When you tap the voice button, Android opens
your phone's own speech recognition service, which returns only the text to the app. That service belongs to your
phone's maker or to Google and may send your voice to its company if it cannot work offline; Remember Me asks it to
work offline, on the phone itself, when it can. So voice input may go over the internet on that service's side;
everything else in the app never does. The first use, About and the privacy page say this. If the phone has no
speech service the voice button is hidden (1.0.1).

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
