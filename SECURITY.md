# Security

Remember Me for Android keeps everything on the phone. It has no internet permission, no account,
no analytics and no server.

## Reporting a problem

Please report a security or privacy problem privately to **support@fastkiwi.app**, with the app
version (Settings → About) and the steps to reproduce it. Please do not post it publicly first.
If the repository becomes public, GitHub's private vulnerability reporting will be the second way.

You will get an answer within 7 days. There is no bug bounty.

## In scope

- Anything that sends data off the phone or exposes it to other apps (exported components,
  PendingIntents, backups, files written to shared storage).
- Reading or changing reminders without the user's action.
- Importing a crafted backup file (zip or data.json) that crashes the app, writes outside the app's
  folder, or corrupts data.
- The lock screen showing a reminder's title while "hide the title on the lock screen" is on.

## Not in scope

- What Android, the phone maker or other apps do with notifications the user allowed.
- Physical access to an unlocked phone.
- A phone that is rooted or has a modified system.

The checks the app already meets are listed in [STANDARDS.md](STANDARDS.md) (OWASP MASVS v2).
