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
  "endpoints": ["/v1/state", "/v1/events", "/v1/themes"]
}
```

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
    "artwork_url": "/v1/media/artwork"
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
    "remote": {"name": "BRAVIA remote", "battery_pct": 72, "connected": true},
    "devices": [
      {"name": "Bluetooth headphones", "type": "a2dp", "connected": false}
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
