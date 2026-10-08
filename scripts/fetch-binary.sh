#!/usr/bin/env bash
# Fetch a release's prebuilt libgaiadesk_embed.so files (arm64-v8a,
# armeabi-v7a, x86_64) into embed/src/main/jniLibs, verifying the SHA-256
# published with them. Then `./gradlew :embed:assembleRelease` builds the AAR.
#
# Usage: scripts/fetch-binary.sh <version>     e.g. scripts/fetch-binary.sh 0.1.0
set -euo pipefail

VERSION="${1:?usage: scripts/fetch-binary.sh <version>}"
BASE="https://github.com/Gaia-Desk/gaiadesk-embed-android/releases/download/v$VERSION"
DIR="$(cd "$(dirname "$0")/.." && pwd)/embed/src/main"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

curl -fsSL "$BASE/jniLibs.zip" -o "$TMP/jniLibs.zip"
curl -fsSL "$BASE/jniLibs.zip.sha256" -o "$TMP/sha256"
want="$(awk '{print $1}' "$TMP/sha256")"
got="$(shasum -a 256 "$TMP/jniLibs.zip" | awk '{print $1}')"
if [ "$want" != "$got" ]; then
  echo "checksum mismatch: expected $want, got $got" >&2
  exit 1
fi
rm -rf "$DIR/jniLibs"
unzip -q "$TMP/jniLibs.zip" -d "$DIR"
echo "jniLibs $VERSION -> $DIR/jniLibs"
