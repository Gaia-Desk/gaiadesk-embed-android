#!/usr/bin/env bash
# Assemble a release of net.gaiadesk:embed-android:
#
#   1. the .so files: from <jniLibs.zip> (GaiaDesk's scripts/build-embed-android.sh,
#      or the farm's embed-android.yml; verified against the .sha256 beside it)
#      or already in embed/src/main/jniLibs; all three ABIs, the right
#      architecture each, no test-source build, no build machine path;
#   2. the JVM tests, then the publication into embed/build/staging-repo (AAR,
#      sources jar, javadoc jar, POM, Gradle module; signed when
#      ORG_GRADLE_PROJECT_signingInMemoryKey is set);
#   3. dist/ (the GitHub release assets):
#        embed-android-<v>.aar, -sources.jar, -javadoc.jar, .pom
#        jniLibs.zip + .sha256                 (scripts/fetch-binary.sh)
#        embed-android-<v>-maven.zip           (the Maven layout: a Central Portal
#                                               manual-upload bundle when signed)
#        SHA256SUMS
#
# Publishes nothing: it prints the commands. Usage:
#   scripts/release.sh <version> [<path/to/jniLibs.zip>]
set -euo pipefail
# No `producer | grep -q` below: grep -q exits at the first match, the producer then dies of
# SIGPIPE and pipefail turns the match into a failure (or, inside `if`, into a silent pass).

VERSION="${1:?usage: scripts/release.sh <version> [jniLibs.zip]}"
ZIP="${2:-}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
JNI="$ROOT/embed/src/main/jniLibs"
DIST="$ROOT/dist"
STAGE="$ROOT/embed/build/staging-repo"
ART=embed-android
fail() { echo "release.sh: $*" >&2; exit 1; }

grep -q "^version = \"$VERSION\"\$" "$ROOT/embed/build.gradle.kts" \
  || fail "embed/build.gradle.kts has version $(sed -nE 's/^version = "(.*)"/\1/p' "$ROOT/embed/build.gradle.kts"), not $VERSION"
grep -q "^## $VERSION\$" "$ROOT/CHANGELOG.md" || fail "CHANGELOG.md has no '## $VERSION' section"

if [ -n "$ZIP" ]; then
  [ -f "$ZIP" ] || fail "no such file: $ZIP"
  [ -f "$ZIP.sha256" ] || fail "no $ZIP.sha256 beside it"
  want="$(awk '{print $1}' "$ZIP.sha256")"
  got="$(shasum -a 256 "$ZIP" | awk '{print $1}')"
  [ "$want" = "$got" ] || fail "sha256 of $ZIP is $got, $ZIP.sha256 says $want"
  rm -rf "$JNI"
  unzip -q "$ZIP" -d "$ROOT/embed/src/main"
fi

for abi in "arm64-v8a:ELF 64-bit.*aarch64" "armeabi-v7a:ELF 32-bit.*ARM, EABI5" "x86_64:ELF 64-bit.*x86-64"; do
  dir="${abi%%:*}" arch="${abi#*:}"
  so="$JNI/$dir/libgaiadesk_embed.so"
  [ -f "$so" ] || fail "missing $so (all three ABIs ship)"
  file -b "$so" | grep "$arch" >/dev/null || fail "$so is not a $dir library: $(file -b "$so")"
  if strings -a "$so" | grep -E "NOT-FOR-RELEASE|GAIADESK-EMBED-TEST-SOURCE-BUILD" >/dev/null; then fail "$so is a test-source build"; fi
  if strings -a "$so" | grep -F "$HOME" >/dev/null; then fail "$so carries $HOME (a --debug build?)"; fi
done

rm -rf "$STAGE"
(cd "$ROOT" && ./gradlew --no-configuration-cache :embed:testDebugUnitTest :embed:publishAllPublicationsToStagingRepository)

M="$STAGE/net/gaiadesk/$ART/$VERSION"
for f in "$ART-$VERSION.aar" "$ART-$VERSION-sources.jar" "$ART-$VERSION-javadoc.jar" "$ART-$VERSION.pom"; do
  [ -f "$M/$f" ] || fail "the publication has no $f"
done
for abi in arm64-v8a armeabi-v7a x86_64; do
  unzip -l "$M/$ART-$VERSION.aar" | grep "jni/$abi/libgaiadesk_embed.so" >/dev/null || fail "the AAR has no jni/$abi/libgaiadesk_embed.so"
done
SIGNED=no
[ -f "$M/$ART-$VERSION.aar.asc" ] && SIGNED=yes

rm -rf "$DIST" && mkdir -p "$DIST"
cp "$M/$ART-$VERSION.aar" "$M/$ART-$VERSION-sources.jar" "$M/$ART-$VERSION-javadoc.jar" "$M/$ART-$VERSION.pom" "$DIST/"
(cd "$ROOT/embed/src/main" && zip -qr "$DIST/jniLibs.zip" jniLibs -x '*.DS_Store')
(cd "$DIST" && shasum -a 256 jniLibs.zip > jniLibs.zip.sha256)
# Central Portal's bundle layout: the repository path from the group down, no maven-metadata.
(cd "$STAGE" && zip -qr "$DIST/$ART-$VERSION-maven.zip" "net/gaiadesk/$ART/$VERSION")
(cd "$DIST" && shasum -a 256 *.aar *.jar *.pom *.zip > SHA256SUMS)

echo
ls -l "$DIST"
echo "signed: $SIGNED"
cat <<NEXT

Ready. Next:
  git tag v$VERSION && git push origin main v$VERSION                       # gh/git auth
  gh release create v$VERSION --repo Gaia-Desk/gaiadesk-embed-android --title "embed-android $VERSION" \\
    --notes-file <(awk '/^## $VERSION\$/{f=1;next} /^## /{f=0} f' CHANGELOG.md) dist/*
  # Maven Central (needs the verified net.gaiadesk namespace, a Central Portal user token and a GPG key
  # whose public half is on keyserver.ubuntu.com):
  export ORG_GRADLE_PROJECT_mavenCentralUsername=… ORG_GRADLE_PROJECT_mavenCentralPassword=…
  export ORG_GRADLE_PROJECT_signingInMemoryKey="\$(gpg --export-secret-keys --armor <key id>)"
  export ORG_GRADLE_PROJECT_signingInMemoryKeyPassword=…
  ./gradlew --no-configuration-cache :embed:publishAllPublicationsToMavenCentralRepository
  # then central.sonatype.com → Deployments → net.gaiadesk embed-android $VERSION → Publish
  # (or, signed: upload dist/$ART-$VERSION-maven.zip there by hand)
NEXT
