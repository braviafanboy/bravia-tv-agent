#!/bin/sh
# Build, sign and install the settings-dump probe without Gradle.
# Needs: Android SDK build-tools + a platform, a JDK, adb.
set -eu
HERE=$(cd "$(dirname "$0")" && pwd)
SDK=${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}
BT=$SDK/build-tools/$(ls "$SDK/build-tools" | sort -V | tail -1)
PLATFORM=$SDK/platforms/$(ls "$SDK/platforms" | sort -V | tail -1)
JAVA_HOME=${JAVA_HOME:-/opt/homebrew/opt/openjdk}
export PATH="$JAVA_HOME/bin:$BT:$SDK/platform-tools:$PATH"
OUT=$HERE/build
rm -rf "$OUT"; mkdir -p "$OUT/classes" "$OUT/res"

aapt2 compile -o "$OUT/res" --dir "$HERE/res" 2>/dev/null || true
aapt2 link -o "$OUT/base.apk" -I "$PLATFORM/android.jar" --manifest "$HERE/AndroidManifest.xml" \
  $( [ -d "$OUT/res" ] && ls "$OUT"/res/*.flat 2>/dev/null ) --java "$OUT/gen" || \
aapt2 link -o "$OUT/base.apk" -I "$PLATFORM/android.jar" --manifest "$HERE/AndroidManifest.xml"
javac --release 17 -d "$OUT/classes" -cp "$PLATFORM/android.jar" $(find "$HERE/src" -name '*.java')
d8 --release --min-api 30 --lib "$PLATFORM/android.jar" --output "$OUT" $(find "$OUT/classes" -name '*.class')
cp "$OUT/base.apk" "$OUT/unsigned.apk"
(cd "$OUT" && zip -q unsigned.apk classes.dex)
zipalign -f 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"
KS=$HERE/debug.keystore
[ -f "$KS" ] || keytool -genkeypair -v -keystore "$KS" -storepass android -keypass android -alias debug \
  -keyalg RSA -keysize 2048 -validity 3650 -dname "CN=debug" >/dev/null 2>&1
apksigner sign --ks "$KS" --ks-pass pass:android --key-pass pass:android --out "$OUT/settings-dump.apk" "$OUT/aligned.apk"
echo "built $OUT/settings-dump.apk"
