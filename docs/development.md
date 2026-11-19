# Development

## Documents

- [`contract.md`](contract.md): the HTTP and JSON interface the integration
  consumes. The integration defines it; change both sides together.
- [`plan.md`](plan.md): the design history. Measured findings from a
  BRAVIA 8 II and what was tried and ruled out; not kept in step with the app.

## Building

`app/build.sh` builds the APK with the Android SDK command-line tools only,
without Gradle. The agent uses no third-party libraries; its HTTP server is
its own (`http/`).

The build needs a JDK and exactly the SDK platform and build-tools that
`app/toolchain.env` names. If they are not installed it stops and says how to
get them; it never takes another version. CI and the release workflow read
the same file, so every build is made with the same SDK packages. They
compile with the JDK named there (17). A newer JDK works for a local build
(`JAVA_HOME`), with a note that its classes are not byte for byte those of a
release. `ANDROID_SDK_ROOT` says where the SDK is (default
`~/Library/Android/sdk`) and `JAVA_HOME` where the JDK is (default
Homebrew's).

Every compiler warning stops the build, deprecations excepted (the platform
deprecates calls whose replacements need a newer Android than the oldest the
app runs on).

After a build, `python3 tools/apicheck.py` lists every use of an Android API
that is newer than `minSdkVersion` and fails on any that is not recorded in
`app/api-allowed.txt`. The sources are compiled against a newer platform than
the oldest Android the app installs on, and the compiler accepts everything
in it; a call that an older TV lacks only fails on that TV. Guard such a call
with `Build.VERSION.SDK_INT` (`Compat` holds the ones there are), then add the
line the check prints, with how it is guarded. CI runs the check.

`app/test.sh` runs the unit tests on a desktop JVM, with no Android SDK
needed, and CI runs it too. It compiles the packages that use no Android
class (`http/`, `core/`, `ambient/`, and two classes of `dashboard/`) and
runs every class under `app/test` whose name ends in `Test`, so a new test
class needs no registering. They cover the HTTP layer and its limits, the
pairing rules, what is public, what counts as an update, the TV id, ambient
brightness and dashboard requests.

Code that needs Android (the screens, the readers, the service, the HTTP
routes) has no automated tests. Put logic worth testing into the Android-free
packages and keep the Android class a thin caller: `Pairing` over
`core/PairingState` is the pattern.

```sh
app/build.sh                       # debug-signed: app/build/bravia-agent.apk
adb install -r app/build/bravia-agent.apk
```

Without `AGENT_KEYSTORE` the build is signed with a throwaway debug key.
Android refuses to update an app signed with a different key, so a debug
build cannot replace a release build without uninstalling it, which loses the
pairing. To build with the release key:

```sh
AGENT_KEYSTORE=path/to/release.keystore AGENT_KEYSTORE_PASS=… app/build.sh
```

## Debugging on the TV

The service answers diagnostic commands through `dumpsys` over adb:

```sh
adb shell dumpsys activity service com.braviafanboy.braviaagent/.AgentService
```

With no arguments it prints the version, pairing, REST channel status, power
saving and the number of picture settings. Subcommands cover the REST key
(`rest psk|verify|forget`), picture settings (`picture refresh|get|set|config`),
power saving, ambient brightness (`ambient on|off|learn on|learn off|reset`),
now playing (`media`), `remote find`, `notify <text>`, and pairing:
`pairing accept` and `pairing deny` answer a waiting pairing request as the
viewer would on screen, and `pairing forget` clears the token.

The log (`adb logcat -s BraviaAgent`) says for every launch of the status
screen whether it came from the TV's control service. Updates and the setup
flow depend on that (pairing does not: its question shows however the screen
was opened); if they never start on a model, look there for the package that
launched it.

## CI and releases

Both workflows pin every action to a commit, install the SDK packages from
`app/toolchain.env` with `.github/scripts/android-sdk.sh` (which checks
Google's command-line tools against a SHA-256 before running them), and
build with `app/build.sh`.

- **CI** (`.github/workflows/ci.yml`) checks every commit of a push for the
  project's identity (a GitHub no-reply address, and the project's name on
  the project's address; it fails when it cannot read the commits rather than
  passing), builds the APK with the debug key, runs the unit tests, builds
  `tools/settings-dump`, and runs the API-level check.
- **Releases** (`.github/workflows/release.yml`) run on a `v*` tag, in two
  jobs, so that the signing key and the right to publish are never in the
  same place.
  - *Build* holds the signing key and a token that can only read. It checks
    that the tag matches `versionName` in `app/AndroidManifest.xml`, that
    `versionCode` is above every earlier release's, and that `CHANGELOG.md`
    has a section headed exactly `## <version>` (rename `## Unreleased`);
    runs the unit tests; builds and signs; checks that the APK is signed by
    the certificate in `app/release-signer.sha256`; runs the API-level check;
    and writes `agent.json`, the update manifest the integration reads, with
    the release notes from the changelog section.
  - *Publish* has a token that can create the release, no secret, and runs
    nothing from the repository: it takes the three files from the build job,
    checks that they agree, and publishes them with `gh release create`.
- Every release must be signed with the same key, or installed agents cannot
  update. The key is in the secrets `AGENT_KEYSTORE_B64` and
  `AGENT_KEYSTORE_PASS`, which the build job reads in the `release`
  environment. Keep them as secrets of that environment, with a required
  reviewer and a deployment rule for `v*` tags, so that no other workflow or
  branch can read them; those are repository settings, which the workflow
  can neither set nor check.

## Data sources

Verified readable on a BRAVIA 8 II (K-65XR8M2, firmware 114.603) without any
privileged permission:

| Data | Why the integration cannot get it otherwise |
|---|---|
| Ambient light (lux) and ambient temperature | Android sensors; no gRPC, cloud or REST path |
| Now playing: app, title, artist, play state, position, duration, artwork | Media sessions; the cloud `playback` state is a bare enum with no metadata |
| Panel on/off, active mode, refresh rate, the HDR formats the panel supports | DisplayManager; covers Picture Off and standby in one signal. It does not see what is playing: the live HDR format comes from gRPC |
| Remote-control battery level | The bundled remote is a Bluetooth HID device with the battery profile |
| HDMI input connection changes | TvInputManager callbacks; gRPC only has this by polling |
| Audio outputs, media volume and mute | AudioManager |
| Selected Sony global settings (power saving, idle standby, auto shut-off, remote start, BRAVIA Sync options, LED behaviour, parental restrictions) | Sony's settings provider; its read permission is normal-level |
| Wi-Fi signal strength and link speed | WifiManager |
| Picture settings | The TV's REST IP control, called on loopback so nothing extra is exposed on the network |

What it cannot do: read any DeviceControlService trait (signature-protected:
parental control timers, voice search results; Find remote is done over
Bluetooth instead), read the sleep timer (cloud-only), or make the TV upload
its cloud state. Ways of learning
more about what is playing that were tried and ruled out are listed at the end
of [`plan.md`](plan.md).
