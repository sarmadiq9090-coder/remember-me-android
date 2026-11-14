# Contributing

Remember Me for Android is FastKiwi software. Publishing it as open source is proposed
([docs/OPEN-SOURCE-PROPOSAL.md](docs/OPEN-SOURCE-PROPOSAL.md), D-079) and needs the owner's approval.

- **Issues** are welcome once the repository is public: bugs, reminders that arrived late on a
  particular phone (please attach the in-app «تقرير التوافق», which contains no personal data),
  translations and accessibility problems.
- **Pull requests are not accepted for now.** One maintainer writes to the repository, which keeps
  the history simple and avoids a contributor agreement.
- Security problems: see [SECURITY.md](SECURITY.md).

## For the maintainer

- Build and test: see [README.md](README.md). Every change keeps the full test suite and
  `lintRelease` clean, and the release gate in [docs/ROADMAP.md](docs/ROADMAP.md) §2.
- Texts: edit `tools/strings_gen.py` (Arabic and English side by side), then
  `python tools/strings_gen.py app/src/main/res`.
- Never add the internet permission, an analytics or advertising library, or a new permission
  without a recorded decision.
- Data format: `data.json` is shared with the Windows app. A new field ships only after a Windows
  round trip keeps it (D-079 A2).
