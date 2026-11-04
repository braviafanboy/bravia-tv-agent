# Development

## Documents

- [`contract.md`](contract.md): the HTTP and JSON interface the integration
  consumes. The integration defines it; change both sides together.
- [`plan.md`](plan.md): design notes and measured findings from a BRAVIA 8 II,
  including what was tried and ruled out.

## Building

`app/build.sh` builds the APK with the Android SDK command-line tools only,
without Gradle. It needs build-tools and platform 34 and a JDK 17, and fetches
NanoHTTPD into `app/libs` on first run.

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
saving and the number of picture settings. Subcommands cover the REST key (`rest psk|verify|forget`),
picture settings (`picture refresh|get|set|config`) and power saving.

## CI and releases

- **CI** (`.github/workflows/ci.yml`) checks that every commit uses a GitHub
  no-reply address, and builds the APK with the debug key.
- **Releases** (`.github/workflows/release.yml`) run on a `v*` tag. The tag
  must match `versionName` in `app/AndroidManifest.xml`. The workflow builds
  with the release key from the repository's secrets, then publishes
  `bravia-agent.apk` and the `agent.json` update manifest that the
  integration's update entity reads. The release notes come from the matching
  `CHANGELOG.md` section. Every release must use the same key, or installed
  agents cannot update.

## Data sources

Verified readable on a BRAVIA 8 II (K-65XR8M2, firmware 114.603) without any
privileged permission:

| Data | Why the integration cannot get it otherwise |
|---|---|
| Ambient light (lux) and ambient temperature | Android sensors; no gRPC, cloud or REST path |
| Now playing: app, title, artist, play state, position, duration, artwork | Media sessions; the cloud `playback` state is a bare enum with no metadata |
| Panel on/off, active mode, refresh rate, HDR format, brightness level | DisplayManager; covers Picture Off and standby in one signal |
| Remote-control battery level | The bundled remote is a Bluetooth HID device with the battery profile |
| HDMI input connection changes | TvInputManager callbacks; gRPC only has this by polling |
| Audio route and per-stream volumes | AudioManager |
| Living Decor theme list | The app's content provider; the TV never lists themes to the cloud |
| Selected Sony global settings (power saving, idle standby, auto shut-off, remote start, BRAVIA Sync options, LED behaviour, parental restrictions) | Sony's settings provider; its read permission is normal-level |
| Wi-Fi signal strength and link speed | WifiManager |
| Picture settings | The TV's REST IP control, called on loopback so nothing extra is exposed on the network |

What it cannot do: read any DeviceControlService trait (signature-protected:
remote finder, parental control timers, voice search results), read the sleep
timer (cloud-only), or make the TV upload its cloud state. Ways of learning
more about what is playing that were tried and ruled out are listed at the end
of [`plan.md`](plan.md).
