#!/bin/sh
# Build, sign and install the settings-dump probe without Gradle.
# Needs: a JDK, adb, and the Android SDK platform and build-tools that
# app/toolchain.env names (the ones the agent itself is built with).
set -eu
HERE=$(cd "$(dirname "$0")" && pwd)
. "$HERE/../../app/toolchain.env"
SDK=${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}
BT=$SDK/build-tools/$ANDROID_BUILD_TOOLS
PLATFORM=$SDK/platforms/$ANDROID_PLATFORM
if [ ! -x "$BT/aapt2" ] || [ ! -f "$PLATFORM/android.jar" ]; then
  echo "error: this build needs Android build-tools $ANDROID_BUILD_TOOLS and platform $ANDROID_PLATFORM" >&2
  echo "       (pinned in app/toolchain.env), and they are not both in $SDK." >&2
  echo "       Get them with: sdkmanager \"build-tools;$ANDROID_BUILD_TOOLS\" \"platforms;$ANDROID_PLATFORM\"" >&2
  echo "       or point ANDROID_SDK_ROOT at the SDK that has them." >&2
  exit 1
fi
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
