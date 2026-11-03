# Agent contract v1 (draft)

JSON over plain HTTP on the LAN. All blocks are optional: a block the agent
cannot read (missing permission, unsupported on the model) is `null`, and the
integration creates no entities for it. Timestamps are ISO-8601 UTC. Numbers
are JSON numbers; enums are lower-case strings.

## `GET /v1/info`

```json
{
  "agent": {"version": "0.1.0", "contract": 1},
  "tv": {
    "model": "K-65XR8M2",
    "device_unique_id": "<40-hex, as advertised in _sonysmarthome._tcp>",
    "android_version": "14",
    "firmware": "114.603.100.1EUA"
  },
  "permissions": {
    "notification_access": true,
    "bluetooth_connect": false,
    "install_packages": true
  },
  "endpoints": ["/v1/state", "/v1/events", "/v1/themes", "/v1/media/artwork"],
  "pairing": {"paired": true, "paired_with": "Home Assistant", "window_open": false, "window_seconds_left": 0},
  "update": {"pending_version": null},
  "tls": {"fingerprint_sha256": "0123abcd…"}
}
```



## Transport security

The agent serves **HTTPS only** (port 8585) with a self-signed EC P-256
certificate generated in the Android keystore on first run (25-year validity,
`CN=BRAVIA agent`). Clients do not validate the name or chain; they **pin the
certificate's SHA-256 fingerprint**, which the handshake exposes and
`GET /v1/info.tls.fingerprint_sha256` repeats. The integration learns the
fingerprint during pairing and pins it on every later connection; a
different certificate (reinstall, factory reset) is treated like a rejected
token and triggers a new pairing. The mDNS TXT carries `tls=1`. Plain HTTP is
not served.

## Self-update

`POST /v1/update?url=<apk>&sha256=<hex>&version=<v>` (authenticated) stages an
update; `info.update.pending_version` shows it. The integration then launches
`braviaagent://update` (cloud deep link) or the app (local launch, which hands
over when an update is pending): the updater downloads the APK, verifies the
SHA-256, opens a package-installer session and shows Android's confirmation,
which the user accepts on screen. The first time, Android also asks to allow
the agent to install unknown apps (`permissions.install_packages`; the setup
flow and DOWN on the status screen open that setting). Updates must be signed
with the release key or Android refuses them; pairing and the certificate
survive an in-place update. The release manifest the integration reads is
`agent.json`: `{"version", "url", "sha256", "notes"}`.

## Authentication

Unpaired (fresh install), every endpoint answers any LAN client **that sends
no credentials**; a presented `Authorization` header is refused with 401, so
a client holding a stale token learns it is not paired. Once a token exists,
every request except `GET /v1/info` and `POST /v1/pair` must carry
`Authorization: Bearer <token>`; otherwise 401 `{"error":"unauthorized"}`.

- `POST /v1/pair?name=<client>` → `{"token": "<64 hex>"}` while the pairing
  window is open, else 403 `{"error":"pairing_closed"}`. The window lasts 60 s
  and opens when the status screen is launched by one of Sony's control
  services (the integration's local gRPC app launch; referrer
  `android-app://com.sony.dtv.devicecontrolservice`) or when the user presses
  RIGHT on it. Pairing closes the window and replaces any earlier token.
- Cloud delivery: launching the deep link `braviaagent://pair?token=<t>&name=<n>`
  (cloud `application.deep_link`) stores `<t>` directly, no window. Sony's
  DeviceControlService delivers it to the package's **main activity** with
  the URI attached (not to the `VIEW` handler), so the status screen adopts
  the token and finishes before drawing.
- `GET /v1/info.pairing` = `{"paired", "paired_with", "window_open",
  "window_seconds_left"}`; the mDNS TXT carries `p=0|1`.
- Holding LEFT on the status screen clears the token.

## `GET /v1/state`

```json
{
  "ts": "2026-11-02T21:00:00Z",
  "environment": {
    "illuminance_lx": 34.0,
    "temperature_c": 23.5
  },
  "display": {
    "on": true,
    "width": 3840,
    "height": 2160,
    "refresh_hz": 60.0,
    "hdr": "dolby_vision",
    "brightness": 0.40
  },
  "media": {
    "active": true,
    "app": "com.google.android.youtube.tv",
    "state": "playing",
    "title": "Big Buck Bunny",
    "artist": null,
    "album": null,
    "position_s": 54.0,
    "duration_s": 596.0,
    "artwork_url": "/v1/media/artwork?v=7"
  },
  "inputs": [
    {"id": "HW4", "label": "HDMI 3", "connected": true}
  ],
  "audio": {
    "route": "hdmi_earc",
    "volume": {"music": 18, "max": 100},
    "muted": false
  },
  "bluetooth": {
    "remote": {"name": "SONY TV VRC 001", "type": "hid", "connected": true, "battery_pct": 72},
    "devices": [
      {"name": "Bluetooth headphones", "type": "audio", "connected": false, "battery_pct": null}
    ]
  },
  "wifi": {"rssi_dbm": -63, "link_mbps": 648},
  "settings": {
    "power.power_saving": "off",
    "network.remotestart": "true"
  },
  "rest": {
    "available": true,
    "scheme": "https",
    "auth": "none",
    "write_authorized": false,
    "cookie_expires": null
  },
  "picture": {
    "targets": {
      "contrast": {"value": "90", "available": true, "candidates": [{"min": 0, "max": 100, "step": 1}]},
      "colorTemperature": {"value": "expert2", "available": true,
                           "candidates": [{"value": "cool"}, {"value": "neutral"}, {"value": "warm"}, {"value": "expert1"}, {"value": "expert2"}]},
      "autoLuminanceLevel": {"value": "off", "available": false, "candidates": [{"value": "on"}, {"value": "off"}]},
      "wideMode": {"value": "", "available": false, "candidates": []}
    }
  },
  "power_saving": "off"
}
```

Field notes:

- `media` is `null` until Notification access is granted to the agent
  (Settings → Apps → Special app access → Notification access, or press OK on
  the agent's status screen). With access, the block is always present:
  `state` is `none` and `active` false when no app holds a media session.
  `position_s` is extrapolated from the session's last update and refreshed
  once a minute while playing and while a client holds `/v1/events` (clients
  extrapolate from `ts`);
  `artwork_url` is set only while an image is available from
  `GET /v1/media/artwork` (404 otherwise; WebP, max 320 px, encoded on first
  fetch) and carries a `?v=<n>` revision that changes with every new image,
  so clients can cache on the full URL.
- `environment.temperature_c` is `null` only until the sensor has ever
  reported (it is on-change in 0.5 °C steps and sends no initial value); the
  last value survives agent restarts.
- `bluetooth` is `null` until BLUETOOTH_CONNECT is granted (the status screen
  asks on launch). `remote` is the first bonded HID device; `connected` comes
  from the system GATT / A2DP / Headset connection lists; `battery_pct` for
  the remote comes from the stack's battery broadcast when it reports one,
  else from one short GATT read at most every 6 hours over a link the stack
  already holds (never waking the remote); `null` until the first reading or
  for devices without the service.
- `display.on` is the panel state (`Display.STATE_ON`); it is false during
  Picture Off and standby. `hdr` is one of `none`, `hdr10`, `hlg`,
  `dolby_vision`, `hdr10_plus` when a mode reports an active HDR type, else
  `none`.
- `media.state` follows Android's `PlaybackState`: `playing`, `paused`,
  `stopped`, `buffering`, `none`. `position_s` is the position at `ts`; the
  integration extrapolates from `ts` while `playing`.
- `inputs[].id` is the TV input's hardware suffix (`HW2`…`HW5`); `label` is
  the TV's own label when available.
- `audio.route` is the active output device type as Android names it
  (`speaker`, `hdmi_arc`, `hdmi_earc`, `bt_a2dp`, `headphone`).
- `settings` contains only the keys the agent is built to report; values are
  the provider's raw strings.
- `rest`, `picture` and `power_saving` come from the TV's REST IP-control API
  (the `webapi` package), called by the agent on the TV itself (0.2.9+). They
  are `null` until the first read. The agent finds the endpoint by probing:
  HTTPS on 443, then plain HTTP (the scheme is a TV setting with a
  region-enforced default), on loopback first, then the TV's own LAN address;
  `rest.scheme` reports which one answered. `rest.available` is false when
  IP control does not answer (switched off, or not yet up after wake); then
  `picture` and `power_saving` are `null`. Reads need no credential.
  `auth` / `write_authorized` / `cookie_expires` stay `none` / `false` /
  `null` until the write tier exists.
- `picture.targets` holds every picture-quality target the TV reports for the
  current picture mode, unchanged: `value` is the TV's string, `available` its
  `isAvailable` (false e.g. for the three Ambient Optimisation sub-switches
  while `sensorAutoAdjustment` is off), `candidates` its candidate list in one
  of three shapes: a single `{min, max, step}` range, a list of `{value}`
  options, or empty. Empty goes with an empty `value` and `available: false`
  when the target does not apply to the current source: on an app,
  `wideMode`, `displayArea`, `hdmiSignalFormat`, `hdmiSignalFormatVrr` and
  `hdmiVideoRange` are like that (their options on an HDMI input are not yet
  observed). Values are per picture mode.
- `power_saving` is `system.getPowerSavingMode` (`off`, `low`, `high`).
- Read cadence: when the panel turns on, every 5 min while it is on (1 min
  while unavailable), and on `POST /v1/picture/refresh`. Nothing on the TV
  signals a picture-setting change to an ordinary app, so clients that see a
  picture mode, input or content mode change (gRPC push) should request a
  refresh.

## `POST /v1/picture/refresh`

Bearer-authenticated. Queues an immediate REST re-read (coalesced with one
already queued) and answers `{"queued": true}` at once; the result arrives as
a normal `state` event if anything changed. 405 for other methods.

## `GET /v1/events`

`text/event-stream`. Events:

- `state` — data is the full `/v1/state` document (coalesced, at most twice
  per second).
- `heartbeat` — empty data every 30 s.

## `GET /v1/themes`

```json
{
  "themes": [
    {"id": "theme://skyview", "title": "Dawn to Dusk",
     "description": "Feel the slow flow of time as the sky changes over the course of a day.",
     "thumbnail": "https://images.erabu.sony.tv/icons/….png"}
  ]
}
```

`id` is what the integration passes to `play_media` with media type `theme`.

## `GET /v1/media/artwork`

The current media session's artwork as `image/png` or `image/jpeg`, 404 when
none. Only referenced from `media.artwork_url`.

## Discovery

mDNS service type `_bravia-agent._tcp`, port as served, TXT records
`v=1` (contract major) and `id=<device_unique_id>`.

## Errors and versioning

- Unknown path: 404 with `{"error": "not_found"}`.
- A contract major bump changes the path prefix (`/v2/`); `/v1/` keeps
  serving for at least one agent release after that.
