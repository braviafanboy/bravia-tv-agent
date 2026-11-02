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

- Kotlin, single module, `minSdk 31`, `targetSdk 34` (the TV runs Android 14).
  Leanback launcher entry so it is visible and can show its status screen.
- One foreground service (`FOREGROUND_SERVICE_CONNECTED_DEVICE`) started at
  boot (`RECEIVE_BOOT_COMPLETED`) and from the launcher activity. Android TV
  shows a persistent notification for it; that is acceptable and expected.
- An embedded HTTP server on a fixed port (**proposal: 8585**) bound to all
  interfaces, plain HTTP, JSON only. Candidates: NanoHTTPD (tiny) or Ktor
  CIO (nicer streaming). Decide at phase 0.
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
| 0 | App skeleton: service, HTTP server, mDNS, `/v1/info`, lux + temperature in `/v1/state`, status screen | boot start, service longevity, that HA can reach the port |
| 1 | Display block, media sessions (notification access), HDMI inputs, audio route | now-playing quality across YouTube / Netflix / Spotify; SSE |
| 2 | Bluetooth (remote battery), Living Decor themes, Sony settings dump → chosen keys | provider stability across launcher versions |
| 3 | Bearer token, Wi-Fi block, hardening (reconnect, memory, doze on standby) | long-running stability over weeks |
| HA | Integration support lands alongside phase 0 (discovery + lux/temperature) and grows with each phase | — |

## Open questions

- Does the foreground service survive the TV's standby/wake cycles, and does
  the HTTP server keep its port after a network drop? (Phase 0 soak test.)
- Does the Living Decor provider also list downloaded/seasonal themes, and do
  the ids stay stable across Living Decor updates?
- Which Sony settings keys are writable from a normal app, if any?
- Media session artwork: is the bitmap available to a listener, and is it worth
  serving over the agent's HTTP as an image?
- Bluetooth battery for the remote: `BATTERY` profile metadata vs
  `BluetoothDevice.getBatteryLevel` (hidden API) — test on the real remote.
- Should the integration ever create entities from the agent when the gRPC
  pairing is absent? Proposal: no; the agent is an add-on to a paired TV.

## Out of scope, recorded so it is not re-asked

- Search results readback and UI actions (DCS trait, signature-gated).
- Forcing a Sony cloud state upload (no exported trigger).
- Remote finder, parental-control timers, notification trait (DCS).
- Raw keycode injection (needs the TvRemoteProvider role, system-only).
