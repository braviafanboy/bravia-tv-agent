# Agent plan

Written 2026-11-02 from the research behind bravia-tv-grpc-homeassistant
v0.2.0. Everything in "Sources" was probed on a live BRAVIA 8 II; everything
in "Design" is a proposal.

## Goals

1. Give the integration the readings it provably cannot get any other way:
   lux, temperature, now-playing metadata, panel state, remote battery,
   Living Decor theme ids.
2. Zero configuration on the Home Assistant side beyond installing the app on
   the TV.
3. Degrade gracefully: the integration must work exactly as today when the
   agent is absent, stopped or older than the contract it expects.
4. Stay local. Nothing leaves the LAN; no accounts; no cloud.

Non-goals: controlling the TV (the integration already has two write paths),
anything needing root or Sony signing, Google Play distribution, replacing
the integration's own discovery or pairing.

## Sources, permissions, and what the user must do

| Source | API | Permission | User action |
|---|---|---|---|
| Light, temperature | `SensorManager` (`TYPE_LIGHT`, `TYPE_AMBIENT_TEMPERATURE`; "Sony M6" sensors) | none | none |
| Display state, mode, HDR, brightness | `DisplayManager` + `Display.getState()`, `Display.getMode()`, `Display.getHdrCapabilities()` | none | none |
| Now playing | `MediaSessionManager.getActiveSessions` from a `NotificationListenerService` | notification access | enable once in Settings |
| HDMI inputs | `TvInputManager.getTvInputList`, `TvInputCallback.onInputStateChanged` | none | none |
| Audio route, volumes | `AudioManager` | none | none |
| Remote battery, bonded devices | `BluetoothAdapter.getBondedDevices`, battery via `BluetoothDevice` metadata / `BATTERY` profile | `BLUETOOTH_CONNECT` (runtime) | grant once |
| Living Decor themes | `content://com.sony.dtv.livingfit/quicklauncher/contents` (`?themeListType=all`) | none (exported) | none |
| Sony global settings | `content://com.sony.dtv.settingsprovider/global` | `com.sony.dtv.settingsprovider.permission.READ_SETTINGS` (normal) | none |
| Wi-Fi RSSI, link speed | `WifiManager.getConnectionInfo` | none for RSSI; location for SSID (not needed) | none |
| Foreground app (optional) | `UsageStatsManager` | usage access | enable once; duplicates gRPC, phase 3 at most |

**Settings dump: done (2026-11-02, `tools/settings-dump`).** The `global`
table has 51 keys, `system` one, `secure_global` is refused. Keys worth
reporting, with the values seen: `power.power_saving` (`-2`),
`power.idle_tv_standby.duration` (`0`), `power.auto_shut_off` (`0`),
`network.remotestart` (`invalid` here — the energy-mode gate had not
written it), `braviasync.cec` / `.autodevicesoff` / `.autotvon` /
`.devicekeycontrol` / `.remotecontrol` (`1`/`0`), `led.voice_led_enabled`,
`led.operational_response`, `parental.apprestriction`,
`parental.timerestriction` (+ `.repeat`, `.timeslot`, `.usagetimelimit`),
`shop.demomode`, `vendorprotocol.ssip` / `.sddp`, `auto_swupdate`,
`timers.clock_display`, `audio.bt_video_delay`,
`content_mode.hdmi_info_sync` (JSON per HDMI port). `audio.speakers` read
`tv_speakers` while the soundbar was the active output, so it is a stored
preference, not the live route — do not surface it as such. Whether any key
is writable from a normal app is still untested. Full dump in the research
repo.

Manifest requirements learned from the probe: `uses-permission` for
`com.sony.dtv.settingsprovider.permission.READ_SETTINGS`, and a
`<queries><provider android:authorities="com.sony.dtv.livingfit"/></queries>`
block, without which the Living Decor provider query returns a null cursor
(Android 11+ package visibility).

## Design

### App

- Plain Java, no Gradle (`app/build.sh` drives aapt2/javac/d8/apksigner),
  `minSdk 31`, `targetSdk 34` (the TV runs Android 14). Leanback launcher
  entry so it is visible and can show its status screen.
- One foreground service (`FOREGROUND_SERVICE_CONNECTED_DEVICE`, which on
  Android 14 also needs `CHANGE_WIFI_MULTICAST_STATE` as its companion
  permission) started at boot, after a reinstall (`MY_PACKAGE_REPLACED`) and
  from the launcher activity; `START_STICKY` so the system restarts it after a
  crash. If a restart lands in a context where Android refuses
  `startForeground()` the service keeps running un-promoted and reports
  `agent.foreground=false` in `/v1/info`; the next start from an allowed
  context promotes it. Android TV shows a persistent notification for it;
  that is acceptable and expected.
- An embedded HTTP server on port **8585** bound to all interfaces, plain
  HTTP, JSON only. NanoHTTPD 2.3.1 (one jar, no dependencies); SSE is a
  chunked response fed from a per-client queue by a writer thread, so a dead
  client never blocks or crashes anything else.
- mDNS service `_bravia-agent._tcp` with TXT `v=<contract>`,
  `id=<TV device unique id>` so the integration can match it to the right
  config entry without the user typing anything. The id is the same 40-hex
  value the TV advertises in `_sonysmarthome._tcp`, readable from the Sony
  settings provider or the integration can match on IP.
- Readers are independent and each contributes a sub-object; a reader that
  lacks its permission reports `null` for its block, never fails the document.
- Caching: sensors sampled continuously but published at most once per
  second; everything else on change plus a 30 s heartbeat.
- Status screen: shows the port, the token (phase 3), which permissions are
  granted, and the last document, so a user can see what HA will see.

### Transport

- `GET /v1/state` — the whole document (see `contract.md`). Cheap enough to
  poll every 10 s.
- `GET /v1/events` — Server-Sent Events: one `state` event per change,
  coalesced to at most 2/s, plus a heartbeat every 30 s. The integration
  prefers this and falls back to polling.
- `GET /v1/themes` — Living Decor theme list (rarely changes; polled hourly).
- `GET /v1/info` — app version, contract version, granted permissions, TV
  model / firmware (from `Build` and the settings provider).
- Authentication (phase 3) piggybacks on the integration's existing gRPC
  credentials instead of a token typed from the status screen. The agent
  cannot share or verify Sony's session keys, but only a holder of them can
  launch an app on the TV through the authenticated local write
  (`system_setting.application`). So: the integration launches the agent;
  the agent, seeing it was started by DeviceControlService rather than the
  launcher (`Activity.getReferrer()`), opens a short pairing window
  (~60 s) during which the first `POST /v1/pair` from the LAN is accepted
  and its bearer token stored; every later request must carry it. Two launch
  paths exist and differ only in whether they carry a URI: the **cloud**
  deep link (`application.deep_link`) attaches any URI to any package — no
  allow-list, DeviceControlService just builds the launch intent for the
  named package — so when the cloud is reachable the integration delivers
  the token directly (`braviaagent://pair?token=…`, no window needed); the
  **local** gRPC launch (`system_setting.application`) starts the app but
  the Seeds service passes `uri = null`, so it can only open the pairing
  window. Re-pairing is the same flow again. Until phase 3: LAN-only and
  unauthenticated like the TV's own REST port 80.

### Integration side (bravia-tv-grpc-homeassistant)

- Discovery: zeroconf on `_bravia-agent._tcp`; match `id` to the entry's
  `device_unique_id`, else match the advertised address to the entry's host.
  Also a periodic probe of `http://<tv host>:8585/v1/info` for networks where
  mDNS is blocked.
- No new config entry; an option toggle "Use agent" default on.
- Entities (all with `entity_category` where appropriate):
  - `sensor` illuminance (lx), temperature (°C), remote battery (%), Wi-Fi
    signal (dBm), media position/duration as attributes on the media player.
  - `binary_sensor` panel on.
  - media player: `media_title`, `media_artist`, `app_name`, playback state
    from the session (replacing the optimistic cloud `playback`), position
    and duration, `media_image_url` if the session carries artwork.
  - `select` Living Decor theme, options from `/v1/themes`, driving the
    existing cloud `select_media` command.
  - Diagnostic sensors for display mode / HDR format and audio route.
  - Settings keys as diagnostic sensors or switches **only after** the dump
    shows which ones are meaningful and whether writes are possible (the
    provider's write permission is a separate question).
- Coordinator: SSE client task with reconnect/backoff; on failure, 10 s poll;
  entities go unavailable after 60 s without data.

### Contract versioning

- `contract.md` is the source of truth and lives in the integration repo
  once the integration implements it; this repo keeps a copy.
- `v` in the mDNS TXT and `/v1/info`. Additive changes keep the major; a
  removed or renamed field bumps it and the integration keeps reading the old
  major.

## Roadmap

| Phase | Deliverable | Verifies |
|---|---|---|
| 0 ✅ | App skeleton: service, HTTP server, mDNS, `/v1/info`, lux + temperature in `/v1/state`, status screen | boot start, service longevity, that HA can reach the port |
| 1 ✅ | Display block, media sessions (notification access), HDMI inputs, audio route | now-playing quality across YouTube / Netflix / Spotify; SSE |
| 2 ✅ | Bluetooth (remote battery), Living Decor themes, Sony settings dump → chosen keys | provider stability across launcher versions |
| 3 ✅ (soak pending) | Bearer token, Wi-Fi block, hardening (reconnect, memory, doze on standby) | long-running stability over weeks |
| HA ✅ (unit-tested; live VM check pending) | Integration support: probe on the TV's host, pairing (cloud deep link / local launch), SSE, sensors, now-playing, theme select — commit in bravia-tv-grpc-homeassistant main | — |

## Phase 0 findings (2026-11-02, BRAVIA 8 II, Android 14)

- Standby does not kill the service: same PID before, during and after a
  sleep/wake cycle. The TV is simply unreachable from the LAN while asleep
  (HTTP connect fails, as the gRPC port does), and answers again within 10 s
  of waking. The integration should treat the agent as unavailable whenever
  the TV is off and resume on power-on; no relaunch is needed.
- The first "standby killed it" observation was two bugs of our own: the SSE
  listener wrote to the pipe of a client that had already disconnected and
  the wrapped `IOException` crashed the main thread; the sticky restart 1 s
  later, with the screen off, was refused `startForeground()` and crashed
  again, which put the service on a 28-minute restart back-off. Fixed by
  moving all SSE writes onto the writer thread and by tolerating a refused
  promotion.
- An induced crash (`am crash`) with the TV awake restarts the service within
  5 s and the promotion succeeds.
- `device_unique_id` resolves from the TV's own `_sonysmarthome._tcp` advert
  a few seconds after start; the mDNS advert is re-registered with `id=` once
  it is known.
- Readable without any privileged permission: light and temperature sensors,
  DisplayManager state, HDMI inputs, audio, Wi-Fi RSSI/link speed, the Sony
  settings keys, the Living Decor theme list (needs a `<queries>` entry for
  the provider under Android 11+ package visibility).

## Phase 1–2 findings (2026-11-02)

- Media sessions: `MediaSessionManager.getActiveSessions` works once the
  agent's (empty) `NotificationListenerService` is granted Notification
  access; `cmd notification allow_listener <pkg>/.MediaListenerService` grants
  it from adb. YouTube TV reports title, artist (channel), duration, position
  and 512 px artwork. Leaving the app drops the session within seconds.
- Bluetooth: the SDK has no public battery or `isConnected` API. Connection
  state: `BluetoothManager.getConnectionState(device, GATT)` is system-wide for
  LE devices (the remote, "SONY TV VRC 001"), A2DP/Headset proxies for
  classic audio. Battery: the remote exposes the standard GATT Battery
  Service; a second GATT client over the stack's existing LE link reads
  0x2A19 fine (30 % on first read) and can subscribe to notifications. The
  `BATTERY_LEVEL_CHANGED` broadcast is kept as a bonus source.
- Both permissions are reported in `/v1/info.permissions`; the status screen
  requests BLUETOOTH_CONNECT on launch and opens Notification access on OK.

## Phase 3 findings (2026-11-02)

- Verified live: unpaired → open; `POST /v1/pair` outside the window → 403;
  RIGHT on the status screen opens the window (`info.pairing.window_open`,
  59 s); pairing returns a 64-hex token; afterwards no header / wrong token →
  401, right token → 200 on `/v1/state` and `/v1/events`, `/v1/info` stays
  public, a second pair → 403; mDNS TXT gains `p=1`.
- Cloud delivery verified: `application.deep_link` with
  `braviaagent://pair?token=…&name=ha-cloud` launches the status screen with
  referrer `android-app://com.sony.dtv.devicecontrolservice` and the token is
  adopted; the previous token stops working at once.
- The same referrer is what a local gRPC `system_setting.application` launch
  produces (both go through DeviceControlService), so the window rule is
  "referrer host starts with `com.sony.`".
- Cloud `application set <package>` is validated against the TV's advertised
  app enum, which did not yet include the agent (the TV refreshes that list on
  its own schedule); the deep link has no such check.

## Live integration findings (2026-11-03)

- First end-to-end run on the HA test VM: automatic pairing through the
  cloud deep link, sensors, panel/remote binary sensors, theme list and
  now-playing with artwork all arrived; no integration errors.
- Event stream bug: NanoHTTPD gzips `text/*` responses for clients that send
  `Accept-Encoding: gzip` (aiohttp does), and a gzipped SSE stream sits in the
  deflater, so HA saw position updates only every ~80 s (its 75 s idle
  reconnect). Fixed in 0.1.1 by disabling gzip for every response; the
  integration also asks for identity encoding.
- A token change now interrupts open event streams so the old client
  re-authenticates immediately instead of at its next reconnect.
- Replacing the token while HA is live makes HA re-pair; if its cloud deep
  link attempt fails it falls back to the local app launch, which brings the
  status screen to the front and stops whatever was playing. Keep that
  fallback, but it is why re-pairing should stay rare.

## TLS (2026-11-03, agent 0.2.0)

- Self-signed EC P-256 key pair in the Android keystore; NanoHTTPD
  `makeSecure` with a KeyManagerFactory over that keystore. TLS 1.3 and 1.2
  verified from a Mac. RSA keystore keys do **not** work for this: Conscrypt
  signs the handshake with a raw NONEwith* operation and the keystore
  answers "Incompatible padding mode"; EC with `DIGEST_NONE` allowed works.
- The integration pins the SHA-256 of the DER certificate, learned from the
  handshake during pairing and stored next to the token. A mismatch (new
  install) clears both and re-pairs. Name, chain and validity are ignored,
  so IP changes and clock skew cannot break it.
- Reinstall and factory reset lose the key together with the token, so the
  only visible effect is a new pairing. Verified live: uninstall → install →
  the integration saw the new certificate, re-paired through the cloud deep
  link in ~10 s and pinned the new one, with YouTube untouched in front.
- Two pitfalls found on the way: Sony's deep link lands on the package's
  main activity (not the VIEW handler), so the status screen must adopt
  pair links itself and finish before drawing; and an unpaired agent must
  refuse a presented token (401) rather than accept anything, or a client
  with a stale token believes it is paired.
- A fresh install is "stopped" until launched: no broadcast starts the
  service, so the user opens the app once (README) or the integration's app
  launch does it.

## Resource behaviour (0.2.3, measured 2026-11-03)

The TV is sensitive to apps that spike CPU or I/O during video. Measured on
the BRAVIA 8 II with YouTube playing and the integration's event stream
connected: **0.0–0.5 % of one core** (`top`, 10 s windows; ~0.9 s of CPU over
3 min), **17 MB PSS**, no wakelocks, no disk writes after pairing, all
elevated-priority threads are ART internals. Measures taken in 0.2.3:

- Both sensors at their slowest rate (1 Hz) instead of the 5 Hz default.
  The temperature sensor is on-change in 0.5 °C steps and sends no initial
  value on a plain subscription, but **a rate change makes the HAL emit the
  current value**: with no value known the agent subscribes at the fastest
  rate for 2 s, then drops to 1 Hz, and the reading arrived within seconds
  (verified). The last value is also persisted (at most every 10 min and on
  pause, never per event) and seeded at start.
- Measured again with the TV on its home screen and the stream connected:
  **1 tick (10 ms) of CPU in 60 s, ~0.02 %** of one core; during video with
  the stream connected 13–32 ticks per 60 s (0.2–0.5 %), of which the main
  thread, GC and JIT are the only visible consumers.
- Audio: device callback plus the system volume/mute broadcasts, 5 min
  fallback poll (was 5 s). Wi-Fi: default-network callback plus the RSSI
  broadcast, 10 min fallback (was 30 s). Sony settings: content observer
  plus 15 min fallback (was 60 s). Bluetooth: ACL/battery broadcasts plus a
  10 min fallback (was 60 s). (0.2.4)
- Now-playing position ticks only while a client holds the event stream,
  once a minute (was 10 s): clients extrapolate from position + timestamp,
  the tick corrects drift; a new subscriber restarts them.
- Artwork is encoded lazily on the first fetch of a revision, as WebP at
  320 px (~13 KB, was PNG 512 px ~270 KB encoded eagerly per track), and
  tracked by bitmap identity rather than a pixel comparison.
- All readers pause while the panel is off (standby, Picture Off) and resume
  on wake; only the display listener, HDMI inputs and the identity lookup
  stay registered.
- HTTP request threads, event writers and the settings reader run at
  background priority.
- Remote battery: one short GATT read every 6 h over the stack's existing
  link, no held client (0.2.2).

## Open questions

- Does the HTTP server keep its port after a Wi-Fi drop or IP change, and does
  the mDNS advert come back? (Phase 3 soak test; the standby question is
  answered below.)
- Does the Living Decor provider also list downloaded/seasonal themes, and do
  the ids stay stable across Living Decor updates?
- Which Sony settings keys are writable from a normal app, if any?
- Should the integration ever create entities from the agent when the gRPC
  pairing is absent? Proposal: no; the agent is an add-on to a paired TV.

## Out of scope, recorded so it is not re-asked

- Search results readback and UI actions (DCS trait, signature-gated).
- Forcing a Sony cloud state upload (no exported trigger).
- Remote finder, parental-control timers, notification trait (DCS).
- Raw keycode injection (needs the TvRemoteProvider role, system-only).
