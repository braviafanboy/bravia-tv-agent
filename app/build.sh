#!/bin/sh
# Build and sign the agent APK without Gradle (phase 0 tooling).
# Needs: a JDK, and the Android SDK platform and build-tools that
# toolchain.env names. Output: build/bravia-agent.apk
# No third-party libraries.
set -eu
HERE=$(cd "$(dirname "$0")" && pwd)
. "$HERE/toolchain.env"
SDK=${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}
BT=$SDK/build-tools/$ANDROID_BUILD_TOOLS
PLATFORM=$SDK/platforms/$ANDROID_PLATFORM
# Only the pinned versions: the newest ones on this machine are not the ones
# CI and the release use, and would build a different APK from the same source.
if [ ! -x "$BT/aapt2" ] || [ ! -f "$PLATFORM/android.jar" ]; then
  echo "error: this build needs Android build-tools $ANDROID_BUILD_TOOLS and platform $ANDROID_PLATFORM" >&2
  echo "       (pinned in app/toolchain.env), and they are not both in $SDK." >&2
  echo "       Get them with: sdkmanager \"build-tools;$ANDROID_BUILD_TOOLS\" \"platforms;$ANDROID_PLATFORM\"" >&2
  echo "       or point ANDROID_SDK_ROOT at the SDK that has them." >&2
  exit 1
fi
JAVA_HOME=${JAVA_HOME:-/opt/homebrew/opt/openjdk}
export PATH="$JAVA_HOME/bin:$BT:$SDK/platform-tools:$PATH"
JAVAC_MAJOR=$(javac -version 2>&1 | sed -n 's/^javac \([0-9][0-9]*\).*/\1/p')
if [ "$JAVAC_MAJOR" != "$JDK_VERSION" ]; then
  echo "note: compiling with JDK ${JAVAC_MAJOR:-(unknown)}. CI and releases use JDK $JDK_VERSION (app/toolchain.env)," >&2
  echo "      and javac's output differs between versions: the classes in this APK are" >&2
  echo "      not byte for byte those of a release." >&2
fi
OUT=$HERE/build
rm -rf "$OUT"; mkdir -p "$OUT/classes"

"$BT/aapt2" link -o "$OUT/base.apk" -I "$PLATFORM/android.jar" --manifest "$HERE/AndroidManifest.xml"
# Every javac warning stops the build (-Werror), except these categories:
#   options      about the JDK in use, not about the code
#   deprecation  the platform deprecates calls whose replacements need a newer
#                Android than minSdkVersion, so the code has to keep both
javac --release 17 -Xlint:all,-options,-deprecation -Werror \
  -d "$OUT/classes" -cp "$PLATFORM/android.jar" $(find "$HERE/src" -name '*.java' | LC_ALL=C sort)
"$BT/d8" --release --min-api 31 --lib "$PLATFORM/android.jar" --output "$OUT" $(find "$OUT/classes" -name '*.class' | LC_ALL=C sort)
cp "$OUT/base.apk" "$OUT/unsigned.apk"
(cd "$OUT" && zip -q unsigned.apk classes.dex)
"$BT/zipalign" -f 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"
# Signing: a release keystore when AGENT_KEYSTORE is set (CI, or a local
# release build), else a throwaway debug keystore. Every published build must
# use the same release key, or Android refuses in-place updates.
if [ -n "${AGENT_KEYSTORE:-}" ]; then
  KS=$AGENT_KEYSTORE; ALIAS=${AGENT_KEY_ALIAS:-bravia-agent}
  KSPASS=${AGENT_KEYSTORE_PASS:?must be set when AGENT_KEYSTORE is}; KEYPASS=${AGENT_KEY_PASS:-$KSPASS}
  [ -f "$KS" ] || { echo "error: AGENT_KEYSTORE is not a file: $KS" >&2; exit 1; }
else
  KS=$HERE/debug.keystore; KSPASS=android; ALIAS=debug; KEYPASS=android
  [ -f "$KS" ] || keytool -genkeypair -v -keystore "$KS" -storepass android -keypass android -alias debug \
    -keyalg RSA -keysize 2048 -validity 3650 -dname "CN=bravia-agent-debug" >/dev/null 2>&1
fi
# The passwords reach apksigner in its environment, not on its command line,
# where every process on the machine could read them. Its stderr is shown: the
# -J option only tells newer JDKs that apksigner's native library is expected,
# which they otherwise report in four warning lines on every run.
SIGN_KS_PASS=$KSPASS SIGN_KEY_PASS=$KEYPASS "$BT/apksigner" -J-enable-native-access=ALL-UNNAMED sign \
  --ks "$KS" --ks-pass env:SIGN_KS_PASS --ks-key-alias "$ALIAS" --key-pass env:SIGN_KEY_PASS \
  --out "$OUT/bravia-agent.apk" "$OUT/aligned.apk"
# Stops here if the signature does not verify; apksigner says why on stderr.
CERTS=$("$BT/apksigner" -J-enable-native-access=ALL-UNNAMED verify --print-certs "$OUT/bravia-agent.apk")
printf '%s\n' "$CERTS" | sed -n 's/^Signer #1 certificate SHA-256 digest: /signer certificate SHA-256: /p'
# The update manifest carries this hash and the agent checks its download
# against it, so an empty or mangled value must stop the build here.
if command -v sha256sum >/dev/null 2>&1; then
  SUM=$(sha256sum "$OUT/bravia-agent.apk")
else
  SUM=$(shasum -a 256 "$OUT/bravia-agent.apk")
fi
SUM=${SUM%% *}
case "$SUM" in *[!0-9a-f]*) SUM= ;; esac
if [ "${#SUM}" -ne 64 ]; then
  echo "error: could not compute the SHA-256 of $OUT/bravia-agent.apk" >&2
  exit 1
fi
printf '%s\n' "$SUM" > "$OUT/bravia-agent.apk.sha256"
echo "built $OUT/bravia-agent.apk"
