# BRAVIA TV agent

A small Android TV app that runs on a Sony BRAVIA and reports what the TV
cannot expose over its local gRPC control plane or the Sony cloud, to the
[bravia-tv-grpc-homeassistant](https://github.com/braviafanboy/bravia-tv-grpc-homeassistant)
integration. It has no purpose on its own: the integration defines the
contract, discovers the agent on the LAN, and turns its data into entities.

**Status: 0.2.3, phases 0–3 running on a BRAVIA 8 II and paired to the integration over pinned TLS; idle while the panel is off** (2026-11-02): foreground
service, HTTP server on port 8585 with `/v1/info`, `/v1/state`, `/v1/themes`,
`/v1/media/artwork` and the `/v1/events` stream, mDNS advert
`_bravia-agent._tcp`, readers for environment, display, HDMI inputs, audio,
Wi-Fi, the Sony settings keys, media sessions (now-playing with position and
artwork, after Notification access) and Bluetooth (remote connection and
battery over GATT, after BLUETOOTH_CONNECT), boot and reinstall start, crash
restart and a status screen; survives the TV's standby/wake cycle. Bearer-token
pairing through a 60 s window (opened by the integration's app launch or from
the status screen) or by token delivery over the Sony cloud deep link.
Integration-side support is the next step.
Built with `app/build.sh` (SDK tools only, no Gradle). See
[`docs/plan.md`](docs/plan.md) for the design and roadmap and
[`docs/contract.md`](docs/contract.md) for the JSON the integration consumes.

## What it adds

Verified readable on a BRAVIA 8 II (K-65XR8M2, firmware 114.603) without any
privileged permission:

| Data | Why the integration cannot get it otherwise |
|---|---|
| Ambient light (lux) and ambient temperature | Android sensors; no gRPC, cloud or REST path |
| Now playing: app, title, artist, playback state, position, duration | Media sessions; the cloud `playback` state is a bare enum with no metadata |
| Panel on/off, active mode, refresh rate, HDR format, brightness level | DisplayManager; covers Picture Off and standby in one signal |
| Remote-control battery level | The bundled remote is a Bluetooth HID device with the battery profile |
| HDMI input connection changes | TvInputManager callbacks; gRPC only has this by polling |
| Audio route and per-stream volumes | AudioManager |
| Living Decor theme list | The app's content provider; the TV never lists themes to the cloud, and the integration's `play_media` theme type needs the ids |
| Selected Sony global settings (power saving, idle standby, auto shut-off, remote start, BRAVIA Sync options, LED behaviour, parental restrictions) | Sony's settings provider; its read permission is normal-level |
| Wi-Fi signal strength and link speed | WifiManager |

What it cannot do, so nobody has to re-research it: read any DeviceControlService
trait (signature-protected: remote finder, parental control timers, voice
search results), read the sleep timer (cloud-only), or make the TV upload its
cloud state.

## How it fits

```
TV (Android)                         Home Assistant
┌──────────────────────┐             ┌──────────────────────────────┐
│ agent app            │  mDNS +     │ bravia_tv_grpc integration   │
│  sensors, sessions,  │  HTTP/JSON  │  existing gRPC + cloud paths │
│  display, providers  │ ───────────▶│  + agent entities            │
└──────────────────────┘             └──────────────────────────────┘
```

- One foreground service on the TV serving a JSON document over HTTPS on the
  LAN (self-signed certificate, fingerprint-pinned by the integration),
  advertised with mDNS; optional event stream for low-latency updates.
- The integration finds it automatically (mDNS, or a probe of the TV's known
  address), no second config entry.
- The contract is versioned on the integration side; the integration tolerates
  an older or missing agent by creating fewer entities, never by erroring.

## Install

Once, with adb (the TV has no built-in way to fetch and install an app, and
Android requires the install to be confirmed on screen regardless):

1. On the TV: Settings → System → About → select *Android TV OS build* seven
   times to unlock Developer options, then Settings → System → Developer
   options → *Network debugging* on. Note the TV's IP address.
2. From a computer with the Android platform tools:

   ```
   adb connect <tv-ip>
   adb install bravia-agent.apk
   ```

   Accept the *Allow debugging?* prompt on the TV the first time.
3. Nothing else is needed for the sensors: the integration finds the agent,
   starts it silently, pairs with it and creates the entities.
4. For now-playing and the remote's battery, grant *Notification access* and
   *Bluetooth* when the app asks. Reach the prompts with the **Agent setup**
   button in Home Assistant or by opening the app from the TV's launcher; the
   status screen also shows what the agent currently serves. With adb still
   connected: `adb shell cmd notification allow_listener
   com.braviafanboy.braviaagent/.MediaListenerService` and
   `adb shell pm grant com.braviafanboy.braviaagent android.permission.BLUETOOTH_CONNECT`.

Updating (`adb install -r`) keeps the pairing; uninstalling loses it and the
integration simply pairs again. Network debugging can be switched off after
installing. Not distributed through Google Play. Note for the record: Sony's
bundled browser cannot download files, so a browser-based install is not an
option on these TVs.

## License

MIT, same as the integration.
