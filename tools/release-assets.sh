#!/usr/bin/env bash
# Prepares the GitHub release assets in dist/release-<version>/ from the signed APK that tools/dist.sh put in dist/:
#   RememberMe-<version>.apk       the signed APK, copied byte for byte (never rebuilt)
#   RememberMe-<version>.cdx.json  the CycloneDX SBOM (./build.sh :app:releaseSbom)
#   SHA256SUMS.txt                 "<sha256>  <file>" for both (the in-app update check reads the APK line)
#   release-notes.md               Arabic, then English, from docs/release-notes/<version>-github.md
# Usage: bash tools/release-assets.sh <version>     (e.g. 1.0.5)
set -euo pipefail
cd "$(dirname "$0")/.."
V=${1:?usage: tools/release-assets.sh <version>}
[[ $V =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || { echo "not a SemVer version: $V"; exit 1; }
CERT=8d3b91dcacced148d11a51fb1333a509581ed2ce04ff362f2882c7cda6ae6e26   # every Remember Me build since 0.1.0
apk=dist/RememberMe-$V.apk sbom=dist/RememberMe-$V.cdx.json notes=docs/release-notes/$V-github.md
for f in "$apk" "$sbom" "$notes"; do [ -f "$f" ] || { echo "missing $f"; exit 1; }; done
grep -q "versionName = \"$V\"" app/build.gradle.kts || { echo "app/build.gradle.kts is not at versionName $V"; exit 1; }

# The signature and certificate (an update needs the same certificate). apksigner from env.sh's build-tools.
if [ -f ./env.sh ]; then source ./env.sh; fi
signer=$(command -v apksigner.bat || command -v apksigner || true)
[ -n "$signer" ] || { echo "apksigner not found (source env.sh)"; exit 1; }
"$signer" verify --print-certs "$apk" | grep -q "SHA-256 digest: $CERT" || { echo "$apk is not signed with the release key"; exit 1; }
# If tools/dist.sh recorded a SHA-256, the APK must still be that file.
if [ -f "dist/RememberMe-$V.sha256.txt" ]; then
  want=$(head -1 "dist/RememberMe-$V.sha256.txt" | cut -d' ' -f1)
  [ "$(sha256sum "$apk" | cut -d' ' -f1)" = "$want" ] || { echo "$apk differs from dist/RememberMe-$V.sha256.txt"; exit 1; }
fi

out=dist/release-$V
rm -rf "$out"; mkdir -p "$out"
cp "$apk" "$sbom" "$out/"
cp "$notes" "$out/release-notes.md"
# Plain "<hash>  <name>" lines (Git Bash's sha256sum marks binary mode with '*'; drop it).
( cd "$out" && sha256sum "RememberMe-$V.apk" "RememberMe-$V.cdx.json" | sed 's/ \*/  /' > SHA256SUMS.txt )
( cd "$out" && sha256sum -c SHA256SUMS.txt )
ls -l "$out"
