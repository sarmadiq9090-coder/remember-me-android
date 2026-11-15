#!/usr/bin/env bash
# Runs the torture suite (torture/README.md) and writes torture/reports/summary-<time>.txt.
# Usage: torture/run.sh [--light] [--tests '<pattern>'] [--seed <n>]
#   --light   skip the heavy parts (TORTURE_HEAVY unset): a few minutes instead of a long run
#   --tests   a narrower Gradle test pattern (default: every class under com.fastkiwi.rememberme.torture)
#   --seed    replay every random test with this seed (TORTURE_SEED)
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"
source "/e/fast kiwi pro neq/rm-wt-env.sh"
export TORTURE=1
export TORTURE_HEAVY=1
PATTERN='com.fastkiwi.rememberme.torture.*'
while [ $# -gt 0 ]; do
  case "$1" in
    --light) unset TORTURE_HEAVY; shift ;;
    --tests) PATTERN="$2"; shift 2 ;;
    --seed) export TORTURE_SEED="$2"; shift 2 ;;
    *) echo "unknown option $1"; exit 2 ;;
  esac
done
cd "$ROOT"
STAMP="$(date +%Y-%m-%d-%H%M)"
mkdir -p torture/reports app/build/torture
LOG="app/build/torture/gradle-$STAMP.log"
echo "torture: heavy=${TORTURE_HEAVY:-0} seed=${TORTURE_SEED:-default} tests=$PATTERN (log $LOG)"
# --rerun: the switches are environment variables, which Gradle's up-to-date check does not see.
timeout 7200 gradle --no-daemon --console=plain --max-workers=2 :app:testDebugUnitTest --tests "$PATTERN" --rerun > "$LOG" 2>&1
CODE=$?
SUMMARY="torture/reports/summary-$STAMP.txt"
{
  echo "Remember Me Android torture run $STAMP (heavy=${TORTURE_HEAVY:-0}, seed=${TORTURE_SEED:-default}, gradle exit $CODE)"
  echo "commit $(git rev-parse --short HEAD) on $(git rev-parse --abbrev-ref HEAD)"
  echo
} > "$SUMMARY"
node torture/tools/summarize.cjs app/build/test-results/testDebugUnitTest >> "$SUMMARY"
grep -h "^\[torture\]" app/build/test-results/testDebugUnitTest/TEST-com.fastkiwi.rememberme.torture.*.xml 2>/dev/null | sort -u | head -200 > "app/build/torture/printed-$STAMP.txt"
cat "$SUMMARY"
grep -E "^e: |What went wrong" -A3 "$LOG" | head -20
exit $CODE
