#!/usr/bin/env bash
# Usage: ./build.sh <gradle tasks...>   Log in .tools/build.log
source "$(dirname "$0")/env.sh"
cd "$RM"
mkdir -p .tools
timeout 3000 gradle --no-daemon --console=plain "$@" > .tools/build.log 2>&1
code=$?
echo "EXIT $code"
grep -E '^e: |error:|FAILURE|What went wrong' -A3 .tools/build.log | head -40
exit $code
