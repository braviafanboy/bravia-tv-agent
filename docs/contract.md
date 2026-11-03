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
    "usage_access": false
  },
  "endpoints": ["/v1/state", "/v1/events", "/v1/themes", "/v1/media/artwork"],
  "pairing": {"paired": true, "paired_with": "Home Assistant", "window_open": false, "window_seconds_left": 0}
}
```


## Authentication

Unpaired (fresh install), every endpoint answers any LAN client. Once a
token exists, every request except `GET /v1/info` and `POST /v1/pair` must
carry `Authorization: Bearer <token>`; otherwise 401 `{"error":"unauthorized"}`.

- `POST /v1/pair?name=<client>` → `{"token": "<64 hex>"}` while the pairing
  window is open, else 403 `{"error":"pairing_closed"}`. The window lasts 60 s
  and opens when the status screen is launched by one of Sony's control
  services (the integration's local gRPC app launch; referrer
  `android-app://com.sony.dtv.devicecontrolservice`) or when the user presses
  RIGHT on it. Pairing closes the window and replaces any earlier token.
- Cloud delivery: launching the deep link `braviaagent://pair?token=<t>&name=<n>`
  (cloud `application.deep_link`) stores `<t>` directly, no window.
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
  }
}
```

Field notes:

- `media` is `null` until Notification access is granted to the agent
  (Settings → Apps → Special app access → Notification access, or press OK on
  the agent's status screen). With access, the block is always present:
  `state` is `none` and `active` false when no app holds a media session.
  `position_s` is extrapolated from the session's last update and refreshed
  every 10 s while playing; `artwork_url` is set only while a PNG is available
  from `GET /v1/media/artwork` (404 otherwise, max 512 px) and carries a
  `?v=<n>` revision that changes with every new image, so clients can cache
  on the full URL.
- `bluetooth` is `null` until BLUETOOTH_CONNECT is granted (the status screen
  asks on launch). `remote` is the first bonded HID device; `connected` comes
  from the system GATT / A2DP / Headset connection lists; `battery_pct` for
  the remote is read from its GATT Battery Service every 15 min and on change,
  `null` until the first read or for devices without the service.
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
