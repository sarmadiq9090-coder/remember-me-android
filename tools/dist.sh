#!/usr/bin/env bash
# Copies the signed release APK to dist/ with its SHA-256, the Arabic guide and the screenshots.
# Run after: ./build.sh :app:assembleRelease
set -euo pipefail
cd "$(dirname "$0")/.."
source ./env.sh
version=$(grep -oE 'versionName = "[^"]+"' app/build.gradle.kts | cut -d'"' -f2)
apk=app/build/outputs/apk/release/app-release.apk
out=dist/RememberMe-$version.apk
mkdir -p dist
# Refuse anything not signed with the release key.
apksigner.bat verify "$apk" > /dev/null
apksigner.bat verify --print-certs "$apk" | grep -q "CN=FastKiwi" || { echo "not signed with the release key"; exit 1; }
cp "$apk" "$out"
size=$(stat -c %s "$out")
sha=$(sha256sum "$out" | cut -d' ' -f1)
cert=$(apksigner.bat verify --print-certs "$apk" | grep "SHA-256 digest" | head -1 | sed 's/.*: //')
printf '%s  %s\nsize: %s bytes\nsigning certificate SHA-256: %s\n' "$sha" "RememberMe-$version.apk" "$size" "$cert" > "dist/RememberMe-$version.sha256.txt"
cp "اقرأني.txt" dist/
rm -rf dist/screenshots && cp -r screenshots dist/screenshots
echo "$out"
echo "size $size bytes"
echo "sha256 $sha"
