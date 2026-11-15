# Contributing / المساهمة

## عربي

- **المشكلات (Issues) مرحّب بها**: الأخطاء والاقتراحات عبر قوالب المشكلات، والتذكيرات التي تأخرت على هاتف معيّن
  (أرفق «تقرير التوافق» من التطبيق، ولا يحوي بيانات شخصية)، والترجمة، ومشكلات إمكانية الوصول.
- **لا نقبل طلبات الدمج (Pull requests) حاليًا.** يكتب في المستودع مشرف واحد، فيبقى التاريخ بسيطًا ولا نحتاج اتفاقية مساهمين. يمكنك فتح مشكلة تصف التغيير المقترح.
- لا تكتب عناوين تذكيراتك أو بياناتك الشخصية؛ صفحات المشكلات عامة.
- الثغرات الأمنية: راجع `SECURITY.md`.
- ذكرني لويندوز تطبيق منفصل؛ هذا المستودع لتطبيق أندرويد وحده.

## English

- **Issues are welcome**: bugs and suggestions through the issue templates, reminders that arrived late on a
  particular phone (please attach the in-app compatibility report, which contains no personal data), translations
  and accessibility problems.
- **Pull requests are not accepted for now.** One maintainer writes to the repository, which keeps the history
  simple and avoids a contributor agreement. Please open an issue describing the change instead.
- Never post your reminder titles or personal data; issues are public.
- Security problems: see `SECURITY.md`.
- Remember Me for Windows is a separate app; this repository is the Android app only.

## For the maintainer

- Build and test: see [README.md](README.md). Every change keeps the full test suite and `lintRelease` clean, and
  the release gate in [docs/ROADMAP.md](docs/ROADMAP.md) §2.
- Texts: edit `tools/strings/*.py` (Arabic and English side by side), then
  `python tools/strings_gen.py app/src/main/res`.
- No analytics or advertising library, and no new permission or network use without a recorded decision
  (the update check of D-081 is the only network code, in the update folder; CI checks both).
- Data format: `data.json` is shared with the Windows app. A new field ships only after a Windows round trip keeps
  it (D-079 A2).
- A new dependency needs its SHA-256 in `gradle/verification-metadata.xml`, generated from an empty Gradle cache
  (`--write-verification-metadata sha256`) so parent POMs and BOMs are included, plus the Linux `aapt2` jar when
  the Android Gradle Plugin changes.

## Release format

- The Git tag is `vX.Y.Z` and equals `versionName` ([SemVer 2.0.0](https://semver.org/)).
- Assets: exactly one `RememberMe-X.Y.Z.apk`, plus `SHA256SUMS.txt` and the SBOM `RememberMe-X.Y.Z.cdx.json`.
  The in-app update check looks for these names. `tools/release-assets.sh X.Y.Z` prepares them in
  `dist/release-X.Y.Z/` from the signed APK.
- `versionCode` always increases, and the release signing key never changes (Android refuses updates signed with
  another key). Release APKs are signed locally, never in CI.
- Every release gets a `CHANGELOG.md` entry (Keep a Changelog 1.1.0) and notes in Arabic and English.
