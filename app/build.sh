#!/bin/sh
# Build and sign the agent APK without Gradle (phase 0 tooling).
# Needs: Android SDK build-tools + platform, a JDK, curl. Output: build/bravia-agent.apk
set -eu
HERE=$(cd "$(dirname "$0")" && pwd)
SDK=${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}
BT=$SDK/build-tools/$(ls "$SDK/build-tools" | sort -V | tail -1)
PLATFORM=$SDK/platforms/$(ls "$SDK/platforms" | sort -V | tail -1)
JAVA_HOME=${JAVA_HOME:-/opt/homebrew/opt/openjdk}
export PATH="$JAVA_HOME/bin:$BT:$SDK/platform-tools:$PATH"
OUT=$HERE/build
LIBS=$HERE/libs
NANO=$LIBS/nanohttpd-2.3.1.jar
mkdir -p "$LIBS"
[ -f "$NANO" ] || curl -fsSL -o "$NANO" \
  https://repo1.maven.org/maven2/org/nanohttpd/nanohttpd/2.3.1/nanohttpd-2.3.1.jar
rm -rf "$OUT"; mkdir -p "$OUT/classes"

aapt2 link -o "$OUT/base.apk" -I "$PLATFORM/android.jar" --manifest "$HERE/AndroidManifest.xml"
javac --release 17 -Xlint:-options -d "$OUT/classes" -cp "$PLATFORM/android.jar:$NANO" $(find "$HERE/src" -name '*.java')
d8 --release --min-api 31 --lib "$PLATFORM/android.jar" --output "$OUT" $(find "$OUT/classes" -name '*.class') "$NANO"
cp "$OUT/base.apk" "$OUT/unsigned.apk"
(cd "$OUT" && zip -q unsigned.apk classes.dex)
zipalign -f 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"
# Signing: a release keystore when AGENT_KEYSTORE is set (CI, or a local
# release build), else a throwaway debug keystore. Every published build must
# use the same release key, or Android refuses in-place updates.
if [ -n "${AGENT_KEYSTORE:-}" ]; then
  KS=$AGENT_KEYSTORE; KSPASS=${AGENT_KEYSTORE_PASS:?}; ALIAS=${AGENT_KEY_ALIAS:-bravia-agent}; KEYPASS=${AGENT_KEY_PASS:-$KSPASS}
else
  KS=$HERE/debug.keystore; KSPASS=android; ALIAS=debug; KEYPASS=android
  [ -f "$KS" ] || keytool -genkeypair -v -keystore "$KS" -storepass android -keypass android -alias debug \
    -keyalg RSA -keysize 2048 -validity 3650 -dname "CN=bravia-agent-debug" >/dev/null 2>&1
fi
apksigner sign --ks "$KS" --ks-pass "pass:$KSPASS" --ks-key-alias "$ALIAS" --key-pass "pass:$KEYPASS" --out "$OUT/bravia-agent.apk" "$OUT/aligned.apk" 2>/dev/null
shasum -a 256 "$OUT/bravia-agent.apk" 2>/dev/null | cut -d' ' -f1 > "$OUT/bravia-agent.apk.sha256" || sha256sum "$OUT/bravia-agent.apk" | cut -d' ' -f1 > "$OUT/bravia-agent.apk.sha256"
echo "built $OUT/bravia-agent.apk"
