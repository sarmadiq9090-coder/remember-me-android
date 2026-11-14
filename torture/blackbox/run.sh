#!/usr/bin/env bash
# Runs one black-box session: torture/blackbox/run.sh <session-folder>
# The folder holds script.txt; the pictures and texts appear in <session-folder>/out/.
# BB_REPO: the app checkout to test (default: the checkout this script is in).
set -u

if [ $# -lt 1 ]; then
  echo "usage: run.sh <session-folder>   (the folder must contain script.txt)" >&2
  exit 2
fi

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SESSION="$(cd "$1" 2>/dev/null && pwd)" || { echo "no such folder: $1" >&2; exit 2; }
[ -f "$SESSION/script.txt" ] || { echo "no script.txt in $SESSION" >&2; exit 2; }
BB_REPO="${BB_REPO:-$(cd "$HERE/../.." && pwd)}"
[ -f "$BB_REPO/app/build.gradle.kts" ] || { echo "BB_REPO=$BB_REPO is not a Remember Me Android checkout" >&2; exit 2; }

# The driver's source travels with this script; copy it into BB_REPO if that checkout lacks it or has an older copy.
SRC="$HERE/../../app/src/test/java/com/fastkiwi/rememberme/torture/blackbox"
DST="$BB_REPO/app/src/test/java/com/fastkiwi/rememberme/torture/blackbox"
if [ "$(cd "$SRC" && pwd)" != "$(mkdir -p "$DST" && cd "$DST" && pwd)" ]; then
  for f in "$SRC"/*.kt; do
    if ! cmp -s "$f" "$DST/$(basename "$f")"; then cp "$f" "$DST/"; echo "(driver file $(basename "$f") copied into $BB_REPO)"; fi
  done
fi

source "/e/fast kiwi pro neq/rm-wt-env.sh"

if command -v cygpath >/dev/null 2>&1; then WIN_SESSION="$(cygpath -w "$SESSION")"; else WIN_SESSION="$SESSION"; fi
export BB_SESSION="$WIN_SESSION"
mkdir -p "$BB_REPO/torture/blackbox"
MARK="$BB_REPO/torture/blackbox/current-session.txt"
printf '%s\n' "$WIN_SESSION" > "$MARK"
trap 'rm -f "$MARK"' EXIT

echo "Session: $SESSION"
echo "App checkout: $BB_REPO"
LOG="$SESSION/gradle.log"
rm -rf "$SESSION/out"
# A run that hangs is stopped after BB_TIMEOUT seconds (default 30 minutes); the report so far is kept.
( cd "$BB_REPO" && timeout "${BB_TIMEOUT:-1800}" gradle --no-daemon --console=plain --max-workers=2 :app:testDebugUnitTest \
    --tests 'com.fastkiwi.rememberme.torture.blackbox.BlackBoxDriver*' --rerun ) > "$LOG" 2>&1
STATUS=$?

echo
if [ -f "$SESSION/out/report.txt" ]; then
  echo "Output files ($SESSION/out):"
  ( cd "$SESSION/out" && ls -1 )
  echo
  echo "===== report.txt ====="
  cat "$SESSION/out/report.txt"
else
  echo "The driver did not produce a report. Last lines of $LOG:"
  tail -n 40 "$LOG"
fi
if [ $STATUS -eq 124 ]; then
  echo
  echo "THE RUN WAS STOPPED after ${BB_TIMEOUT:-1800} s (it hung). The report above ends where it stopped."
elif [ $STATUS -ne 0 ]; then
  echo
  echo "(Gradle exit code $STATUS; see $LOG and $BB_REPO/app/build/reports/tests/testDebugUnitTest/index.html)"
fi
exit $STATUS
