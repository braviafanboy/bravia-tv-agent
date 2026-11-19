#!/bin/sh
# Puts the Android SDK packages that app/toolchain.env pins on a CI runner.
# Both workflows run this one script, so they cannot drift apart.
#
#   sh .github/scripts/android-sdk.sh [directory]     (default: $HOME/android-sdk)
#
# Google's command-line tools are downloaded and checked against the SHA-256
# below before anything in them is unpacked or run. sdkmanager then fetches
# the platform and the build-tools, and checks those against Google's package
# index itself. Needs curl, unzip, sha256sum and a JDK.
set -eu
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
. "$ROOT/app/toolchain.env"

# Command-line tools 12.0 for Linux. The SHA-256 was computed from the
# download; the file's size and SHA-1 are the ones Google's package index
# gives for it (https://dl.google.com/android/repository/repository2-3.xml,
# which has no SHA-256). To move to a newer version: take the zip's name from
# that index, download it, compare its SHA-1 with the index, and put the new
# name and sha256sum here.
TOOLS_ZIP=commandlinetools-linux-11076708_latest.zip
TOOLS_SHA256=2d2d50857e4eb553af5a6dc3ad507a17adf43d115264b1afc116f95c92e5e258

SDK=${1:-$HOME/android-sdk}
TMP=${RUNNER_TEMP:-${TMPDIR:-/tmp}}

curl -fsSL --retry 3 -o "$TMP/$TOOLS_ZIP" "https://dl.google.com/android/repository/$TOOLS_ZIP"
echo "$TOOLS_SHA256  $TMP/$TOOLS_ZIP" | sha256sum -c -
mkdir -p "$SDK/cmdline-tools"
unzip -q "$TMP/$TOOLS_ZIP" -d "$SDK/cmdline-tools"
mv "$SDK/cmdline-tools/cmdline-tools" "$SDK/cmdline-tools/latest"
SDKMANAGER=$SDK/cmdline-tools/latest/bin/sdkmanager

# Accept the SDK licences: a fixed number of answers from a file. ("yes |"
# never ends by itself, and fails the step in a shell that checks every
# command of a pipeline.)
i=0
while [ "$i" -lt 50 ]; do echo y; i=$((i + 1)); done > "$TMP/sdk-licence-answers"
"$SDKMANAGER" --sdk_root="$SDK" --licenses < "$TMP/sdk-licence-answers" > /dev/null
"$SDKMANAGER" --sdk_root="$SDK" "platforms;$ANDROID_PLATFORM" "build-tools;$ANDROID_BUILD_TOOLS" > /dev/null

if [ ! -x "$SDK/build-tools/$ANDROID_BUILD_TOOLS/aapt2" ] || [ ! -f "$SDK/platforms/$ANDROID_PLATFORM/android.jar" ]; then
  echo "error: sdkmanager did not leave build-tools $ANDROID_BUILD_TOOLS and platform $ANDROID_PLATFORM in $SDK" >&2
  exit 1
fi
echo "Android SDK in $SDK: build-tools $ANDROID_BUILD_TOOLS, platform $ANDROID_PLATFORM" \
  "($(grep '^Pkg.Revision' "$SDK/platforms/$ANDROID_PLATFORM/source.properties"))"
# Later steps of the job find the SDK through this.
if [ -n "${GITHUB_ENV:-}" ]; then
  echo "ANDROID_SDK_ROOT=$SDK" >> "$GITHUB_ENV"
fi
