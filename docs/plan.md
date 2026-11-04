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
5. The agent is an add-on to a TV paired over gRPC, never a substitute.
   gRPC is the primary control plane and must be present: the integration
   creates agent entities only inside a config entry whose gRPC setup
   succeeded, and looks for the agent only at that TV's address
   (decided 2026-11-04).

Non-goals: anything needing root or Sony signing, Google Play distribution,
replacing the integration's own discovery or pairing.

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
- An embedded HTTP server on port **8585** bound to all interfaces, JSON
  only (HTTPS since 0.2.0). Originally NanoHTTPD 2.3.1; replaced in 0.4.0 by
  the agent's own small server (see "HTTP server rewrite" below). SSE is a
  chunked response fed from a per-client queue, so a dead client never blocks
  or crashes anything else.
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
| R1 ✅ · R2 ✅ · R3 ✅ (agent 0.3.0 + integration, local; Normal auth Japan-only) | REST picture channel: agent-relayed reads (no auth), Normal-auth PIN registration, writes, integration entities — see *REST picture channel plan* | reads on the live TV; PIN flow with the user present; CPU unchanged |

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
  59 s; RIGHT removed in 0.4.1: Home Assistant opens the window itself, and a
  manual one only let another device take over a working pairing); pairing returns a 64-hex token; afterwards no header / wrong token →
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

## REST picture channel plan (2026-11-03)

Design rationale and every verified fact behind it: `bravia-research/docs/rest-ip-control.md`. Short form: the TV's REST IP-control API (the `webapi` package) is the only non-root route to the ~66 picture targets with no gRPC twin and the four Ambient Optimisation switches. Reads need no credential; writes need a Normal-auth cookie (one on-screen PIN, 14-day cookie, renewed by re-registering) or a PSK. The agent calls it on loopback and relays over its pinned link, so no TV credential ever reaches Home Assistant.

### R1 — agent: read tier (no auth, no user involvement)

- **`RestClient`** (new). JSON-RPC `call(service, method, version, params)` to `127.0.0.1`. Scheme by probing: HTTPS 443 first, HTTP 80 if 443 is unavailable (the TV's default is region-enforced; EU sets serve HTTPS only and answer 403 on 80). The webapi certificate is self-signed by "Sony TV Root CA" and regenerated when the mode changes, so on loopback it is accepted without verification (loopback cannot be intercepted from the LAN); never used for any other host. 3 s timeouts, one background-priority worker, errors mapped to `Unavailable`, `AuthRequired` (401/403 JSON-RPC), `SchemeMismatch` (nginx 403 on 80).
- **`PictureReader`** (new reader). One `video.getPictureQualitySettings` v1.1 call with empty params returns all targets with value, availability and candidates; plus `system.getPowerSavingMode`. Runs on panel-on resume, every 5 min while the panel is on, on demand, and right after an agent write. Publishes only on change. Paused with the other readers when the panel is off.
- **State** (additive, contract stays v1):
  - `rest`: `available`, `scheme` (`https`/`http`/null), `auth` (`none`/`cookie`/`psk`), `write_authorized`, `cookie_expires`.
  - `picture.targets`: `{name: {value, available, candidates}}` exactly as the TV reports them.
  - `power_saving`: `off`/`low`/`high`.
- **Endpoint** `POST /v1/picture/refresh` (bearer). Listed in `info.endpoints`.
- **Verify live:** all 68 targets read, scheme probe on this HTTPS set, candidate shapes for enum targets (only numeric ranges are confirmed so far), idle CPU with the 5-min poll measured the same way as *Resource behaviour*.

**R1 findings (2026-11-03, agent 0.2.9, live on the BRAVIA 8 II):**

- Implemented as `RestClient` (probing JSON-RPC client) and `readers/PictureReader`, wired into the panel-gated reader lifecycle; `POST /v1/picture/refresh`; blocks `rest`, `picture`, `power_saving` (contract updated).
- The TV's communication mode had been switched to HTTP since the earlier HTTPS tests (443 closed, 80 answering), so the probe was exercised for real: HTTPS failed and it settled on `http://127.0.0.1` by itself. In HTTPS mode the earlier loopback probe showed `https://127.0.0.1` works, which is the first candidate.
- First read: 68 targets, `power_saving=off`, both calls plus the probe in ~13 ms. Nothing appeared on screen; YouTube stayed in front.
- Candidate shapes over all 68: 25 value lists (on/off pairs → switch, longer lists → select), 38 numeric ranges (→ number), 5 empty. The empty ones (`wideMode`, `displayArea`, the three HDMI signal targets) are empty and unavailable on an app source; their options on an HDMI input are still to be observed before R3 maps them.
- `autoLuminanceLevel`, `autoToneCurve`, `autoWhiteBalance` report `available: false` while `sensorAutoAdjustment` is off, as the design note predicted.
- Home Assistant (integration 0.3.0 on the VM) ignores the new blocks: agent online, entities updating, diagnostics list the new endpoint.
- Cost (YouTube playing): agent 10 ticks in 360 s = 0.028% of one core, same as before. The webapi is the expensive side: one full 68-target `getPictureQualitySettings` read costs it ~111 ms CPU and ~1 s wall time (20 reads from the LAN: +222 ticks in 19 s); its own idle rate is 0.017% of a core. A 5-min poll therefore averages ~0.04% of a core as a short burst. Decision 3 should weigh a 10–15 min poll, since gRPC-triggered refreshes cover the common changes. (The VM also runs the Bravia REST API custom integration, which polls the same server.)
- Not yet exercised: `POST /v1/picture/refresh` with a valid token (it needs the token Home Assistant holds; R3 calls it), and the HTTPS path with this build.

### R2 — agent: auth and write tier

- **`RestAuth`** (new), app-private prefs `rest`.
  - Normal auth: `accessControl.actRegister` with `clientid` = a per-install UUID, `nickname` "HA TV Agent", `level` `private`. Begin = call without credentials → 401 and the TV shows the PIN. Complete = same call with HTTP Basic `:<pin>` → `Set-Cookie: auth=…; Max-Age=1209600`; store cookie and expiry.
  - Renewal: re-register with the cookie weekly and whenever under 7 days remain; a 401 on a write triggers one renewal attempt, then reports `write_authorized=false`.
  - PSK fallback: stored only if the user supplies one; never both active.
  - PIN, cookie and PSK never appear in state, info, logs or the status screen.
- **Endpoints** (bearer): `POST /v1/rest/register` (begin), `POST /v1/rest/register?pin=NNNN` (complete), `DELETE /v1/rest/auth` (forget), `POST /v1/rest/psk`, `POST /v1/picture` (`{"target","value"}`, validated against candidates), `POST /v1/power_saving?mode=`.
- **Verify live, with the user present** (the PIN dialog covers the screen): register, cookie Max-Age, a visible write and restore, renewal, survival across `adb install -r`.
- Release as agent **0.3.0**.

**R2 findings (2026-11-03, agent 0.3.0, live, nothing shown on screen):**

- Implemented: `RestAuth` (Normal-auth registration, cookie with Max-Age, weekly renewal when < 7 days remain, PSK fallback, verification via `system.getSystemInformation`, app-private prefs), credential headers and auth-cookie capture in `RestClient` (a 401 is the API's challenge, never "wrong endpoint"), validated writes and power saving in `PictureReader`, the refresh settings (`poll_s`, `event_refresh`, `watch`) stored on the TV, an HDR-change trigger from `DisplayReader`, the endpoints in the contract, and adb-only maintenance commands via `Service.dump()` (DUMP permission, i.e. the adb shell; run on a worker because `dump()` is on the main thread).
- Verified with the pre-shared key over HTTP: no credential → `rest_unauthorized`; key verified; no-op writes (contrast 90 → 90, power saving off → off) accepted and `write_authorized` turned true; wrong key → verification false and writes refused; validation refused out-of-range, unknown and unavailable targets (`autoLuminanceLevel` while the master switch is off) and an invalid power-saving mode. The stored credential survives `adb install -r`. No secret appears in logcat. Key forgotten afterwards.
- Measured webapi cost: 60 s, no watch → 22 ticks / 120 s (0.18% of a core, two full reads as predicted); 10 s watching two targets → 28 ticks / 120 s (0.23%), so each watched target at 10 s adds ~0.025%, cheaper than estimated.
- Home Assistant (integration 0.3.0) unaffected: agent online, 11 endpoints, sensors updating.
- **PIN session with the user (2026-11-03):** no PIN appeared. The TV's PIN service (`com.sony.dtv.seconddispsetting`, `DeviceRegisterManager.startActRegister`) rejects every non-Japanese set up front (`!ProductInfo.isJpRegion()` → `REQUEST_TIMEOUT`, which the service cannot map, so the API answers `[1, "Internal Server Error"]`). **Normal auth is Japan-only; outside Japan the pre-shared key is the write credential.** The agent already reports this as 409 `normal_auth_not_supported`. The cookie code stays for Japanese sets but cannot be tested here.
- Visible write with the user watching, using the PSK: contrast 90 → 91 confirmed by the agent's re-read and by the TV, then restored to 90. Key forgotten afterwards.
- HTTPS mode (user switched IP control back, 2026-11-03): a fresh agent process probes `https://127.0.0.1` first and uses it (68 targets, PSK verified, no-op write accepted, nothing logged). Finding: in HTTPS mode only the *LAN* side of port 80 is refused (nginx 403); loopback HTTP keeps answering, so a process that cached `http://127.0.0.1` while the TV was in HTTP mode keeps working over loopback after the switch. Harmless (the key never leaves the TV). Consequence (agent 0.3.0): loopback HTTP is now tried **first**, HTTPS only as a fallback; no TLS handshake per call, and no mode detection needed on this model.
- **HDR test with the user (2026-11-03):** an HDR10 YouTube video switched the TV to its HDR setting set (brightness 0 → 50, gamma −3 → 0, `hdrToneMapping` becomes available), so an HDR refresh is worth having. But Android's display state never changed (`mIsHdrLayerPresent=false`, `HdrOutputType` invalid, same mode; `Display.isHdr()` is the panel's capability): Sony's video pipeline bypasses Android's display layers. The agent's HDR trigger could never fire and was removed. gRPC `playback_control.signal_info.video` reported SDR → **HDR10** live (the integration's video signal sensor), so the HDR trigger moves to the integration (R3).
- **HDMI test:** on an HDMI input (HDMI 3, no video signal) the five source-dependent targets report choices: `wideMode` (auto, full, horizontal_fit, vertical_fit, original), `displayArea` (range −5..5), `hdmiVideoRange` (auto, full, limited), `hdmiSignalFormatVrr` (off, on), `hdmiSignalFormat` (standard, enhanced1, enhanced2; unavailable without a signal). They carry a per-input `uri` (`extInput:hdmi?port=3`): per-HDMI-port settings for the current input.
- Not exercised: Normal auth (needs a Japanese set).

### R3 — integration

- **Client** (`grpc/agent.py`): register begin/complete, forget, set PSK, set picture target, refresh, set power saving.
- **Entities**, generated from candidates: numeric range → `number`, value list → `select`, on/off pair → `switch`. `brightness` and `pictureMode` are excluded (gRPC owns them, with push). All enabled by default (decision 2), entity category *config*; the note asks users to disable what they do not use. Unavailable when the target reports `isAvailable=false` or REST is unavailable. Power saving mode as a `select`.
- **Credential (decided 2026-11-03): write access is inferred from the pre-shared key in the integration's options.** An optional *REST pre-shared key* field (password selector) in the options flow, with the TV menu path in its description (Settings → Network → Home network → IP control → Authentication → Normal and Pre-Shared Key, then Pre-Shared Key). Stored in the config entry beside the gRPC keys and the agent token, redacted in diagnostics.
  - **Empty → read-only:** picture targets and power saving appear as sensors with the TV's current values.
  - **Set → writable:** the same settings appear as `number` / `switch` / `select`, power saving as a `select`.
  - Changing the field reloads the entry; setup removes the other platform's picture entities from the registry so no orphans remain.
  - The coordinator sends the key with `POST /v1/rest/psk` when the agent reports `rest.auth` other than `psk` (e.g. after an uninstall), and calls `DELETE /v1/rest/auth` when the field is cleared. The Normal-auth PIN flow is not offered in the UI (Japan-only); the agent endpoints stay.
- **Repairs:** `rest_unavailable` (IP control off or unreachable, with the menu path) and `rest_key_rejected` (a key is set but the TV refuses it; fixed by entering the current key in the options).
- **Refresh trigger:** picture values are per picture mode and per SDR/HDR set, so the coordinator asks the agent to refresh (debounced 1 s) when gRPC push reports a change of picture mode, input, content mode or video format (`playback_control.signal_info.video` `hdr_type`). The five HDMI-only targets are per-input (`uri`); show them as unavailable on app sources.
- **Diagnostics:** `rest` status only, no secrets. **Tests:** FakeAgent extensions, candidate→entity mapping, refresh trigger, unauthorised-write error, fix flow. README section.
- Release as integration **0.4.0**, after agent 0.3.0; an older agent simply yields no picture entities.

**R3 findings (2026-11-03, integration on the VM, agent 0.3.0 on the TV):**

- Implemented: client calls (`picture/config`, `picture/refresh`, `picture`, `power_saving`, `rest/psk`, `rest/auth`), `picture.py` (kind from candidates with HDMI-only fallbacks, labels), options (key, poll interval, event refresh), per-connection sync (settings + watch list from enabled entities, key handed over once per coordinator lifetime and whenever the agent lacks it, forgotten when cleared), re-sync once picture entities exist, debounced gRPC trigger (picture mode, input, content mode, `signal_info.video` `hdr_type`; resolution alone does not trigger), translated write errors, repairs (`rest_key_rejected`, `rest_unavailable` after 3 min), registry cleanup on mode switch, diagnostics (`rest`, target list; key redacted). 148 tests.
- Live: read-only mode created 67 sensors (66 targets + power saving) with the TV's values; HDMI-only targets unavailable on an app. Entering the key via the options flow reloaded into 38 numbers, 23 selects (incl. power saving), 6 switches, sensors removed; agent `auth=psk`, verified. Same-value writes through `number.set_value` / `select.select_option` reached the TV (agent log). Wrong key → `rest_key_rejected` repair and writes refused; correct key → repair cleared. Watch list 66 after the re-sync.
- HDR trigger, live with the user: the first version missed HDR10 because the TV reports "no video signal" between contents and the trigger ignored changes from an empty value (gamma arrived 47.5 s later via the poll). The TV uses its SDR picture values whenever nothing HDR plays (HDR10 → no signal switched gamma back to −3), so no signal now counts as SDR and the trigger fires on a change of dynamic-range class (same pattern as the audio sensor treating an empty audio signal as PCM). A second bug: the signal value is a JSON-encoded string, read raw, so the trigger never fired. Fixed and verified live with the user (2026-11-03): SDR → HDR10: signal 23:54:53.25, agent `refresh requested` 53.87, read done 55.22 (5 settings changed), HA updated 56.29 (3.0 s); HDR10 → launcher: 2.6 s. The launcher reports no signal and the TV puts its SDR values on it within 0.5 s. The TV applies HDR values ~2 s after reporting HDR10, so HDR-triggered re-reads wait 3 s (others 1 s). Confirmed live with the 3 s wait: HDR10 seen 23:58:20.55, refresh requested 22.67, read done 23.93 (5 settings changed), HA updated 24.86 (~4.3 s, correct HDR values); the TV's lag varies (immediate this time, ~2 s before), the wait covers both. Agent logs `picture: refresh requested` and `picture: N settings changed` for this kind of check.
- `rest_unavailable`, live (2026-11-04): the switch that turns the REST API off is **Remote device settings → Control remotely** (with it off the API does not answer at all; gRPC is unaffected). The *IP control* screen only holds Authentication, Pre-Shared Key, Communication mode and *Simple IP control* (the separate port-20060 protocol). Entities went unavailable within ~1 min; the repair appeared at 5.5 min instead of 3 because the grace was only re-checked on the agent's next state document; integration now schedules its own re-check.

**Agent 0.4.0 (2026-11-04):** Normal (PIN) authentication removed at the user's request: Japan-only on the TV, never reachable from the integration, untested. The pre-shared key is the only write credential; the R2 notes above describe the removed PIN flow for the record.

**Artwork survey (2026-11-04, live, temporary probe since removed):** what each app puts in its media session.

| App | Title | Artwork | Art URI |
|---|---|---|---|
| Spotify | full metadata (22 keys) | 300×300 bitmap | https Spotify CDN, the same 300×300 JPEG (63 KB) |
| YouTube | title, channel | 640×360 bitmap | none (no video id either) |
| YouTube Music (plays in the YouTube app) | title, artist, album | 544×306 bitmap | none |
| Disney+ | title, duration | none | none |
| Apple TV | duration only | none | none |
| Sony Pictures Core | duration, empty ids | none | none |
| Netflix, Twitch | metadata null | none | none |
| Prime Video | catalog id only | none | none |
| Kick, BBC iPlayer (HbbTV in the tuner) | no media session | none | none |

Encoder benchmark on the TV (median of 3, background priority): JPEG q90 4–7 ms CPU vs lossy WebP 41–79 ms, lossless WebP 23–118 ms (but 180–220 KB), PNG 41–169 ms. Decision: full-size bitmap as JPEG q90, cap 1024 px; no URI pass-through (no app gains resolution from it), no CDN URL rewriting.

### Deferred

- Direct REST client in the integration for agent-less setups (same entities, Normal auth with the cookie in the config entry).
- Agent-side luminance LUT control loop (parked idea; would reuse the R2 write path with smoothing and hysteresis).
- Wake-on-LAN mode, LED, reboot: check which need a credential once R2 exists.
- Control-plane benchmarking and preferential routing (idea, after REST): several commands exist on more than one plane — brightness and picture mode (gRPC, REST, cloud), volume and mute (gRPC, REST), power (gRPC, REST, cloud), input and app launch (gRPC, REST). Measure per command both the acknowledgement latency and the time until the change is confirmed in state, then route to the fastest plane with the others as fallbacks. Weigh against what each plane costs the TV (e.g. the webapi read cost above) and keep one owner per setting so planes do not fight.

### Decisions needed before R3

1. ~~Controls present before authorisation~~ → superseded: no key = read-only sensors, key = writable controls (2026-11-03).
2. ~~The default-enabled target set~~ → **decided 2026-11-03: every picture entity enabled by default**, with a note (README and the options description) to disable whatever is not useful: people try the settings and keep what helps. Cost: none extra at the default 60 s poll (one full read per tick regardless); below 60 s the agent switches to a full read per tick once 20+ targets are watched, so everything enabled at 10 s costs ≈1.1% of a core, the same as a 10 s full read.
3. ~~Poll cadence~~ → **two integration options with sane defaults** (2026-11-03), replacing the earlier Lazy/Eager idea:
   - **Picture settings poll interval**, default 60 s, bounded 10 s – 60 min. Sent to the agent with `POST /v1/picture/config` `{"poll_s": N, "event_refresh": bool, "watch": [targets]}`; the agent clamps it, stores it in its prefs (survives restarts, standby) and reports it back in `rest`. The option text says shorter intervals load the TV more. Re-sent on every connection (idempotent; restores it after an uninstall wipes the prefs).
   - **Refresh on TV events**, default on: the integration requests `POST /v1/picture/refresh` (debounced 1 s) on gRPC picture mode / input / content mode changes, including video format changes (HDR on/off) seen on gRPC. Rare events, one full read each. (The agent cannot self-trigger on HDR: Android does not see it.)
   - Agent-internal, not a setting: intervals under 60 s read only the watched targets (enabled entities) each tick, one call each, plus a full read every 60 s; at 60 s and above each tick is one full read. The agent always re-reads after its own writes. Nothing runs while the panel is off.
   - Measured cost (webapi, 2026-11-03, 20 reads each): full read 110 ms CPU / 944 ms wall; one target 5–6.5 ms CPU / ~45 ms wall; target lists in one call are rejected (`Illegal Argument`). So 60 s full ≈ 0.18% of a core; 10 s with the 13 default-enabled targets ≈ 0.8% (≈25× the agent's own footprint — measure before recommending short intervals); 10 s full ≈ 1.1% (never done).
   - Agent side in R2, integration options and gRPC trigger in R3.

## Transport direction (2026-11-04)

Decided: **keep the listening HTTPS server and rewrite it** (done in 0.4.0,
below). It fixes the actual problem, an unmaintained dependency, with no
change to the contract, pairing or the integration, keeps pinned TLS end to
end on every install, and keeps working where the TV may not reach Home
Assistant (IoT network segments).

Considered and kept on file:

- **Agent as a client of Home Assistant's WebSocket API** (integration-registered
  commands, token minted at pairing for a dedicated non-admin user; the
  companion app's local push and HASS.Agent are the precedents). Removes the
  listening port, but puts a broad Home Assistant credential on the TV (no
  scoped tokens exist, so a compromised TV could control the home), requires
  TV-to-HA reachability, sends the token in clear on plain-HTTP installs, and
  needs a two-sided staged migration. Bootstrap would be a local pairing
  webhook during the agent's window, Home Assistant's mDNS advert, or a
  manually entered address. Never the Sony cloud: nothing about the user's
  Home Assistant is to pass through Sony's service. Worth revisiting if the
  listening port becomes a real problem (for example Sony firmware limiting
  inbound connections).
- **Webhooks with long polling**: spike and measured findings on branch
  spike/webhooks. Same speed as the server; same drawbacks as above.
- **A reverse tunnel carrying the existing pinned-TLS contract**: keeps end
  to end encryption but needs its own unauthenticated endpoint and
  TLS-in-WebSocket code.

## HTTP server rewrite (0.4.0, measured 2026-11-04)

NanoHTTPD (2.3.1, 2016, unmaintained) is replaced by `http/` in the agent:
a strict HTTP/1.1 subset on Android's TLS server sockets. Blocking sockets,
one pooled thread per open connection (idle threads exit after 60 s), at most
8 connections (Home Assistant holds the event stream and a few short
requests), keep-alive with a 20 s idle timeout, and per request a 5 s read
timeout and a 10 s overall deadline against slow senders. Limits: request line
2 KB, headers 8 KB and 50 lines, body 64 KB, bodies only by Content-Length
(chunked uploads refused), no compression. The event stream is written on its
connection's own thread (no pipe and second thread as before). `app/test.sh`
runs 56 checks on a desktop JVM, also in CI.

| BRAVIA 8 II, idle, HA's event stream connected | NanoHTTPD | New server |
|---|---|---|
| CPU over 60 s | 3 ticks (30 ms) | 2 ticks (20 ms) |
| Threads in the process | 23 | 23 |
| PSS | 8.7 MB | 8.5 MB |
| `/v1/info` on a new TLS connection | 49–60 ms | 31–35 ms |
| `/v1/info` on an open connection | 12–17 ms | 7–9 ms |

Verified live: Home Assistant reconnected with the pinned certificate within
5 s of the install and every agent entity refreshed; picture writes through
Home Assistant 58–107 ms; unauthorised requests 401, closed pairing 403, a
70 KB body 413, a chunked upload 501, a 9 KB header 431, HTTP/2 505, garbage
and random bytes 400, plain HTTP refused; the server kept serving throughout.
Not exercised live: a full re-pairing (the pairing handler is unchanged).

## Open questions

- Does the HTTP server keep its port after a Wi-Fi drop or IP change, and does
  the mDNS advert come back? (Phase 3 soak test; the standby question is
  answered below.)
- Does the Living Decor provider also list downloaded/seasonal themes, and do
  the ids stay stable across Living Decor updates? Findings 2026-11-04 (Living
  Decor = `com.sony.dtv.livingfit` v2.37.22, factory image, never updated):
  - The provider feeds the TV's **quick-menu Living Decor row, not the
    catalogue**: every query returns the same 3 rows (`hMax=3`; here 2 from
    `history`, 1 `default`), whatever `themeListType` (`all`, `default`,
    `history`, `download`), `hMax` or `screenId` is passed. So `/v1/themes`
    and the integration's theme select only ever offer recent themes plus a
    default, not everything installed.
  - The app downloads themes (download handling, an all-themes screen).
    Built-in themes have ids in its code (`alphaclock`, `rainforest`,
    `genart`, `googlephotos`), but the listed ids (`skyview`,
    `naturalandscape`, `monochrome`) appear nowhere in the APK: they come from
    Sony's downloaded catalogue, so their stability depends on Sony's server,
    not on app updates. The same ids drive the cloud `select_media`
    capability, which suggests Sony treats them as stable. BRAVIA Connect's
    Flutter library contains no theme ids.
  - Checked on the TV the same day: Living Decor's "See all" shows **14
    themes** (Dawn to Dusk, Nature Scenery, Monochrome, Sensorial Gateway,
    Rainforest Clock, Art Gallery, Singulart, two Flow Machines, an animated
    room, an animated Earth view, Earth from space, a starfield, aibo). The
    provider's three rows are the **three most recently used** themes:
    selecting Sensorial Gateway put `theme://sensorialgateway` first and
    dropped Nature Scenery. Stepping through every theme (2026-11-04, UK
    BRAVIA 8 II) gave all 14 ids. Ids may contain `/` and `-` (a first pass
    with a narrower pattern truncated four of them):

    | Id | Title |
    |---|---|
    | `skyview` | Dawn to Dusk |
    | `naturalandscape` | Nature Scenery |
    | `monochrome` | Monochrome |
    | `sensorialgateway` | Sensorial Gateway |
    | `rainforest-clock` | Rainforest Clock |
    | `artgallery` | Art Gallery |
    | `singulart` | Singulart ("distribution will conclude in mid-January 2027") |
    | `flowmachines` | Colour Stream #001 |
    | `flowmachines2` | Colour Stream #002 |
    | `sakurachill` | Sakura Chill Beats |
    | `spacenomad/002` | SPACE NOMAD #002 |
    | `spacescenery/photo` | Space Scenery #001 |
    | `spacescenery/video` | Space Scenery #002 |
    | `aibo-w-bg` | My story with aibo |

    A theme that has not finished downloading may not reach the recent list
    until it has played once.
    Titles and descriptions are localised by the TV; the ids are not. The
    catalogue changes over time (Singulart announces its own end), so a
    remembered id can disappear; thumbnails come from
    `images.erabu.sony.tv`.
  - The full catalogue is not readable: it sits in Living Decor's Sony Select
    provider (`com.sony.dtv.livingfit.provider`, not exported; adb gets
    "not exported"), and the exported provider (`com.sony.dtv.livingfit`)
    serves only `quicklauncher/contents`. Living Decor also exposes a
    `MediaSelectorTraitService` to Sony's DeviceControlService (signature
    bind), presumably what the cloud `select_media` reaches.
  - **Themes can be shown locally**: `am start -a android.intent.action.VIEW
    -d theme://monochrome` switched the TV to Monochrome (Living Decor's
    MainActivity handles the `theme` scheme) and updated its history. An
    agent-side launch would remove the internet requirement for themes;
    untested whether Android lets the agent start it from the background.
  - Options for the integration's theme select: remember every theme id the
    agent has ever reported, so the list grows to everything used at least
    once; and/or switch themes locally through the agent. Ids: snapshot
    recorded here; re-check after a firmware or Living Decor update.
- Which Sony settings keys are writable from a normal app, if any?

## Out of scope, recorded so it is not re-asked

- **Now-playing enrichment from online databases** (TMDB, IMDb, Rotten Tomatoes …), considered 2026-11-04: not worth building unless a way to identify what is playing in most apps exists, and none does. Most apps withhold titles from their media session (see the artwork survey); no video app posts notifications; Android TV Watch Next is readable (READ_TV_LISTINGS is a runtime permission; filtered queries are refused, so the whole table is read) and carries good titles and posters for Netflix, Apple TV, Disney+ and Twitch, but apps write their row only when playback stops: tested 2026-11-04, Netflix's row stayed unchanged through 5 minutes of playback and appeared on leaving the app, so it always describes the previous title; YouTube and Spotify publish no Watch Next rows; the media sessions hold nothing beyond what the agent reads (all fields dumped 2026-11-04: extras, queue, queue title, playback-state extras, custom actions, every metadata key): Netflix none at all, Apple TV duration only, Disney+ title plus an "S1:E1 Episode" subtitle (already shown as the artist line), YouTube title, channel and thumbnail with no video id, Prime Video only its GTI (also in the session extras as `com.amazon.media.MEDIA_ID`), which the public primevideo.com/detail/<gti> page resolves to a title and full-size poster (`og:image`), a scrape that could break; Google's Engage (the successor feed) is gated by the signature permission `com.google.android.engage.REQUEST_ENGAGE_DATA` and has no public read API; Sony's cloud playback state is a bare enum; screenshots of protected video are black; other apps' audio cannot be captured; accessibility screen-reading is invasive and fragile; the streaming services have no account APIs. If it ever returns: TMDB (free key, attribution) looked up in Home Assistant, matched on title + duration, off by default. Related: Prime Video's media id is its GTI (`amzn1.dv.gti.…`), which resolves on primevideo.com/detail/<gti> and in `app.primevideo.com` deep links; passing media ids through (Prime GTI, Spotify `spotify:track:…`) for automations stays a small optional idea.

- Search results readback and UI actions (DCS trait, signature-gated).
- Forcing a Sony cloud state upload (no exported trigger).
- Remote finder, parental-control timers, notification trait (DCS).
- Raw keycode injection (needs the TvRemoteProvider role, system-only).
