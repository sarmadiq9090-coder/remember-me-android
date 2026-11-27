#!/usr/bin/env bash
# F-091: Android → Windows → Android round trip with the Windows app's own code (read only).
# Usage: tools/interchange.sh   (from the repo root; needs Node and the sibling Remember-Me folder)
set -e
cd "$(dirname "$0")/.."
source ./env.sh 2>/dev/null || source "/e/fast kiwi pro neq/rm-wt-env.sh"
gradle --no-daemon --console=plain -q :app:testFullDebugUnitTest --tests 'com.fastkiwi.rememberme.data.InterchangeTest.phase1*'
node tools/windows-roundtrip.cjs
gradle --no-daemon --console=plain -q :app:testFullDebugUnitTest --tests 'com.fastkiwi.rememberme.data.InterchangeTest.phase2*'
grep -q 'skipped="0"' app/build/test-results/testFullDebugUnitTest/TEST-com.fastkiwi.rememberme.data.InterchangeTest.xml && echo "INTERCHANGE OK"
