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
  "endpoints": ["/v1/state", "/v1/events", "/v1/media/artwork"],
  "pairing": {"paired": true, "paired_with": "Home Assistant", "window_open": false, "window_seconds_left": 0},
  "update": {"pending_version": null},
  "tls": {"fingerprint_sha256": "0123abcd…"}
}
```



## Transport security

The agent serves **HTTPS only** (port 8585) with a self-signed EC P-256
certificate generated in the Android keystore on first run (25-year validity,
`CN=HA TV Agent`; installs from before 0.3.0 keep their original `CN=BRAVIA agent` certificate). Clients do not validate the name or chain; they **pin the
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
flow, and OK on the status screen while it is missing, open that setting). Updates must be signed
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
  `android-app://com.sony.dtv.devicecontrolservice`). There is no manual way
  to open it on the TV (RIGHT on the status screen did until 0.4.1); for
  development, `dumpsys activity service …/.AgentService pairing window`.
  Pairing closes the window and replaces any earlier token. Opening or
  closing the window leaves open event streams alone; a changed or cleared
  token ends them.
- Cloud delivery of a token (`braviaagent://pair?token=…` through the
  cloud `application.deep_link`) was removed in 0.4.1. It needed the same LAN
  path to verify the token, so it never helped when local pairing failed; the
  token went through Sony's service and into the TV's system log (Sony's
  command processor logs whole commands); and the link handler accepted
  links from any app or web page, so anything on the TV could take over the
  pairing. Agents from 0.4.1 ignore pair links; integrations up to 0.4.1
  that still send one fall back to the local launch.
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
    "poll_s": 60,
    "event_refresh": true,
    "watch": []
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
  `GET /v1/media/artwork` (404 otherwise; JPEG at the app's full resolution, max
  1024 px, encoded on first fetch) and carries a `?v=<n>` revision that changes with every new image,
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
  are `null` until the first read. The agent calls the API on loopback over
  plain HTTP: the TV's HTTP/HTTPS setting only governs network clients, and
  loopback HTTP answers in both modes, so normally `rest.scheme` is `http`
  whatever the TV is set to. HTTPS on loopback and the TV's own LAN address are
  fallbacks for firmware that locks loopback too. The traffic never leaves the
  TV. `rest.available` is false when
  IP control does not answer (Remote device settings → Control remotely off, or not yet up after wake); then
  `picture` and `power_saving` are `null`. Reads need no credential.
  `auth` is the stored write credential: `none` or `psk` (the TV's IP-control
  pre-shared key). `write_authorized` is true while that key last worked
  (verification or a write). `poll_s`, `event_refresh` and `watch` are the
  refresh settings set with `POST /v1/picture/config`. (Agent 0.3.0 also had a
  Normal-auth cookie, `auth: cookie` and `cookie_expires`; removed in 0.4.0, as
  the TV's PIN service only works on Japanese sets.)
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
- Read cadence: when the panel turns on, then every `poll_s` while it is on
  (1 min while unavailable). Below 60 s each tick reads only the `watch`
  targets (one call each) and a full read happens once a minute, unless 20 or
  more targets are watched, when every tick is a full read (cheaper); at 60 s
  and above every tick is a full read. The agent re-reads after its own
  writes. Nothing on the TV signals a picture-setting change to an ordinary
  app, and Android's display API does not see HDR playback on these sets, so
  clients with `event_refresh` on should call `POST /v1/picture/refresh` when
  gRPC reports a change of picture mode, input, content mode or video format
  (`playback_control.signal_info.video`, e.g. SDR → HDR10). Nothing runs while
  the panel is off.

## `POST /v1/picture/refresh`

Bearer-authenticated. Queues an immediate REST re-read (coalesced with one
already queued) and answers `{"queued": true}` at once; the result arrives as
a normal `state` event if anything changed. 405 for other methods.

## `POST /v1/picture/config`

Bearer. Body `{"poll_s": 60, "event_refresh": true, "watch": ["contrast"]}`;
every key optional (an absent key keeps the stored value). `poll_s` is clamped
to 10–3600. Stored on the TV, so it survives restarts and standby; reported
back in `rest`. Clients re-send it on every connection. Answers the effective
settings.

## `POST /v1/picture`

Bearer. Body `{"target": "contrast", "value": "91"}`. Validated against the
last read: unknown target 404 `unknown_target`, `available: false` 409
`target_unavailable`, value outside the candidates 400 `invalid_value`. Then
written with the stored pre-shared key. No key, or rejected: 403 `rest_unauthorized` (and
`write_authorized` turns false). IP control not answering: 503
`rest_unavailable`. Success `{"ok": true}`, followed by a re-read.

## `POST /v1/power_saving?mode=off|low|high`

Bearer. Same error handling as `POST /v1/picture`.

## `POST /v1/rest/psk`

Bearer. Body `{"psk": "..."}`: stores the IP-control pre-shared key and
verifies it with a read that requires it. Answers
`{"write_authorized": true|false}`.

## `DELETE /v1/rest/auth`

Bearer. Forgets the stored credential.

## `POST /v1/remote/find`

Makes the bundled remote beep, as the TV's own Find remote does: a Bluetooth
LE write to Sony's remote finder service on the paired "SONY TV VRC 001"
(details in RemoteFinder.java). Needs BLUETOOTH_CONNECT and the TV on. Waits
until the beep command is written or fails (at most ~15 s) and answers
`{"state": ...}`: `ringing`, `found`, `unsupported` (no finder service on the
remote), `no_remote`, `no_permission` or `failed`. The state block
`remote_finder` (`{"state", "reason", "at"}`) follows the beep: the remote
reports `02 01 00` when it starts and `02 01 01` when a button silences it
(found); `02` in the last byte would be a timeout.

## `GET /v1/events`

`text/event-stream`. Events:

- `state` — data is the full `/v1/state` document (coalesced, at most twice
  per second).
- `heartbeat` — empty data every 30 s.

## `GET /v1/themes` (removed in 0.4.1)

Returned Living Decor's theme list. Removed with the integration's theme
support: the TV only lists the three most recently used themes to other
apps. Agents from 0.4.1 answer 404; integrations up to 0.4.1 ignore that.

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
