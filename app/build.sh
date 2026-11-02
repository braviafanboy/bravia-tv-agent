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
KS=$HERE/debug.keystore
[ -f "$KS" ] || keytool -genkeypair -v -keystore "$KS" -storepass android -keypass android -alias debug \
  -keyalg RSA -keysize 2048 -validity 3650 -dname "CN=bravia-agent-debug" >/dev/null 2>&1
apksigner sign --ks "$KS" --ks-pass pass:android --key-pass pass:android --out "$OUT/bravia-agent.apk" "$OUT/aligned.apk" 2>/dev/null
echo "built $OUT/bravia-agent.apk"
