#!/usr/bin/env bash
# Copies the signed release APK to dist/ with its SHA-256, the Arabic guide and the screenshots.
# Run after: ./build.sh :app:assembleFullRelease :app:assembleFdroidRelease
set -euo pipefail
cd "$(dirname "$0")/.."
source ./env.sh
version=$(grep -oE 'versionName = "[^"]+"' app/build.gradle.kts | cut -d'"' -f2)
apk=app/build/outputs/apk/full/release/app-full-release.apk
fdroid_apk=app/build/outputs/apk/fdroid/release/app-fdroid-release.apk
out=dist/RememberMe-$version.apk
mkdir -p dist
# Refuse anything not signed with the release key.
apksigner.bat verify "$apk" > /dev/null
# The release certificate of every Remember Me build since 0.1.0 (an update needs the same one).
apksigner.bat verify --print-certs "$apk" | grep -q "SHA-256 digest: 8d3b91dcacced148d11a51fb1333a509581ed2ce04ff362f2882c7cda6ae6e26" || { echo "not signed with the release key"; exit 1; }
cp "$apk" "$out"
# The F-Droid flavor (no self-updater, no network permissions): same application id and the same release key.
apksigner.bat verify --print-certs "$fdroid_apk" | grep -q "SHA-256 digest: 8d3b91dcacced148d11a51fb1333a509581ed2ce04ff362f2882c7cda6ae6e26" || { echo "fdroid APK not signed with the release key"; exit 1; }
fdroid_out=dist/RememberMe-$version-fdroid.apk
cp "$fdroid_apk" "$fdroid_out"
size=$(stat -c %s "$out")
sha=$(sha256sum "$out" | cut -d' ' -f1)
cert=$(apksigner.bat verify --print-certs "$apk" | grep "SHA-256 digest" | head -1 | sed 's/.*: //')
printf '%s  %s\nsize: %s bytes\nsigning certificate SHA-256: %s\n' "$sha" "RememberMe-$version.apk" "$size" "$cert" > "dist/RememberMe-$version.sha256.txt"
printf '%s  %s\n' "$(sha256sum "$fdroid_out" | cut -d' ' -f1)" "RememberMe-$version-fdroid.apk" >> "dist/RememberMe-$version.sha256.txt"
cp "اقرأني.txt" dist/
cp docs/GUIDE-ar.md dist/GUIDE-ar.md
cp "docs/release-notes/$version-ar.txt" "dist/ما-الجديد-$version.txt"
# The SBOM (CycloneDX) of what ships in the APK: ./build.sh :app:releaseSbom writes it into dist/.
[ -f "dist/RememberMe-$version.cdx.json" ] || { echo "no SBOM: run ./build.sh :app:releaseSbom"; exit 1; }
rm -rf dist/screenshots && cp -r screenshots dist/screenshots
echo "$out"
echo "size $size bytes"
echo "sha256 $sha"
