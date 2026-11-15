# Security policy / سياسة الأمان

## الإبلاغ عن ثغرة (عربي)

نرجو **عدم** نشر الثغرات في مشكلات (Issues) عامة.

1. استعمل الإبلاغ الخاص عن الثغرات في GitHub:
   <https://github.com/sarmadiq9090-coder/remember-me-android/security/advisories/new>
2. إن تعذّر ذلك: راسلنا على `support@fastkiwi.app` بعنوان يبدأ بـ `[SECURITY]`، مع إصدار التطبيق (الإعدادات ◂ حول) وخطوات إعادة المشكلة.

نردّ خلال 7 أيام، ونتفق معك على موعد النشر (إفصاح منسّق، عادةً خلال 90 يومًا من الإبلاغ أو بعد صدور الإصلاح).
لا ترسل تذكيراتك الحقيقية أو بيانات شخصية؛ نسخة احتياطية تجريبية تكفي. لا توجد مكافآت مالية.

## Reporting a vulnerability (English)

Please do **not** open a public issue for security problems.

1. Use GitHub private vulnerability reporting:
   <https://github.com/sarmadiq9090-coder/remember-me-android/security/advisories/new>
2. If that is not possible, e-mail `support@fastkiwi.app` with a subject starting `[SECURITY]`, the app version
   (Settings ▸ About) and the steps to reproduce it.

We reply within 7 days and agree a disclosure date with you (coordinated disclosure, normally within 90 days of
the report or once a fix is released). Please do not send your real reminders or personal data; a sample backup is
enough. There is no bug bounty.

## Scope

- The Android app in this repository, latest release.
- Of special interest:
  - anything that sends data off the phone or exposes it to other apps (exported components, PendingIntents,
    backups, files written to shared storage);
  - the update check (since 1.0.5): anything that could make the app install an APK that is not the same
    release's file, not matching its `SHA256SUMS.txt`, or not signed with the app's certificate, or that makes it
    contact a host other than GitHub;
  - reading or changing reminders without the user's action;
  - a crafted backup (zip, encrypted backup or `data.json`) or calendar file that crashes the app, writes outside
    the app's folder or corrupts data;
  - the lock screen showing a reminder's title while "hide details on the lock screen" is on;
  - supply-chain issues in the build.

## Not in scope

- What Android, the phone maker or other apps do with notifications the user allowed.
- The phone's own speech recognition service used by voice input (see docs/PRIVACY.md).
- Physical access to an unlocked phone, or a rooted or modified system.
- Remember Me for Windows, which is a separate app (report it through its own channels).

## Supported versions

Only the latest release receives fixes. Updates are signed with the same key (certificate SHA-256 in the README).
The checks the app already meets are listed in [STANDARDS.md](STANDARDS.md) (OWASP MASVS v2).
