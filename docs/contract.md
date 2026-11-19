# Agent contract v1

JSON over HTTPS on the LAN, port 8585. All blocks are optional: a block the
agent cannot read (missing permission, unsupported on the model) is `null`,
and the integration creates no entities for it. Timestamps are ISO-8601 UTC.
Numbers are JSON numbers; enums are lower-case strings.

Version numbers below are the agent's. What "0.10.0" changed is marked as
such, because agents and integrations from before it are still in use.

## `GET /v1/info`

Answers without the token (and at `/`). With the token it adds the parts
marked below, which are not for strangers on the network.

```json
{
  "agent": {"version": "0.10.0", "contract": 1, "port": 8585, "foreground": true},
  "tls": {"fingerprint_sha256": "0123abcd…"},
  "tv": {
    "model": "K-65XR8M2",
    "device_unique_id": "<as advertised in _sonysmarthome._tcp>",
    "android_version": "14",
    "build": "<the TV's build string>"
  },
  "permissions": {
    "notification_access": true,
    "bluetooth_connect": false,
    "install_packages": true,
    "display_overlay": true,
    "post_notifications": true
  },
  "pairing": {"paired": true, "method": "compare", "paired_with": "Home Assistant", "paired_from": "192.0.2.10"},
  "update": {"pending_version": null},
  "endpoints": ["/v1/state", "/v1/events", "/v1/media/artwork", "/v1/setup"]
}
```

- With the token only (0.10.0): `tv.android_version`, `tv.build`,
  `pairing.paired_with` (the name the paired client gave) and
  `pairing.paired_from` (the address it paired from; `null` for a pairing made
  before 0.10.0). Before 0.10.0 everything was given to anyone, as were
  `pairing.window_open` and `pairing.window_seconds_left`, which no longer
  exist.
- `agent.foreground` is false while Android has refused to promote the
  service (after a restart from the background); it keeps running.
- `tv.device_unique_id` is the id in the TV's own `_sonysmarthome._tcp`
  announcement: 40 hex characters, or only the beginning of them when a long
  device name cut the announcement short (0.10.0; before, such a TV had
  `null`). Compare it with another id by its beginning.
- `pairing.method` is `compare` from 0.10.0 (see Authentication); absent on
  older agents, which pair through a window.
- `permissions.post_notifications` (0.10.0) is whether the fallback
  notification of `POST /v1/notify` can be shown; always true before
  Android 13.
- `endpoints` lists what this agent serves; the example is shortened.

## Transport security

The agent serves **HTTPS only** (port 8585) with a self-signed EC P-256
certificate generated in the Android keystore on first run (25-year validity,
`CN=HA TV Agent`; installs from before 0.3.0 keep their original `CN=BRAVIA agent` certificate). Clients do not validate the name or chain; they **pin the
certificate's SHA-256 fingerprint**, which the handshake exposes and
`GET /v1/info.tls.fingerprint_sha256` repeats. The integration reads the
fingerprint before pairing and pins it on every later connection. A different
certificate (the app was reinstalled, the TV reset, or something else answers
on the port) is never paired with on its own: the integration raises a repair,
and pairs again only when the user confirms it. (Integrations before 0.10.0
treated it like a rejected token and paired again at once.) The mDNS TXT
carries `tls=1`. Plain HTTP is not served.

## Self-update

`POST /v1/update?url=<apk>&sha256=<hex>&version=<v>` (token) stages an
update; `info.update.pending_version` shows it. All three are required from
0.10.0: an `https` URL, the 64-hex SHA-256 the download must have, and the
version it brings (digits and dots). Otherwise 400 `url_required`,
`sha256_required` or `version_required`. Answers
`{"pending": "<url>", "version": "<v>"}`. (Before 0.10.0 the hash and the
version were optional, and `http` was accepted.)

The integration then has the TV launch the app: the `braviaagent://update`
link through Sony's cloud, or the local app launch, which runs the update
whenever one is staged. The updater downloads the APK (at most 32 MiB),
verifies the SHA-256, opens a package-installer session for this package only
and shows Android's confirmation, which the user accepts on screen. The first
time, Android also asks to allow the agent to install unknown apps
(`permissions.install_packages`; the setup flow, and OK on the status screen
while it is missing, open that setting). Android refuses an APK that is not
signed with the release key or is older than the installed one; pairing and
the certificate survive an in-place update.

A staged update is run once. If anything fails (download, checksum, the
installer, the user declining) it is no longer staged, and it lapses on its
own 15 minutes after staging. An update is staged only through
`POST /v1/update`: the updater takes nothing from the link or intent that
starts it. (Until 0.9.1 a `?url=` in the link replaced the staged update, any
app on the TV could send one, and a failed update stayed staged.)

The release manifest the integration reads is `agent.json`:
`{"version", "url", "sha256", "notes"}`.

## Authentication

Every request except `GET /v1/info` and `POST /v1/pair` must carry
`Authorization: Bearer <token>`; otherwise 401 `{"error":"unauthorized"}`,
and the connection is closed. This holds whether or not the agent is paired:
an agent nobody has paired with answers nothing else. (Until 0.9.1 an unpaired
agent answered every request that came without credentials.)

### Pairing (0.10.0)

A person at the TV allows a pairing, after comparing a six-digit code that
the agent shows with the one the client shows. The steps:

1. `POST /v1/pair?name=<client>&commit=<64 hex>` asks. `commit` is the
   SHA-256 of 32 random bytes the client keeps to itself for now. 202
   `{"request": "<32 hex>", "nonce": "<64 hex>", "accepted": false,
   "expires_in": 30}`; `nonce` is 32 random bytes of the agent's. Nothing
   shows on the TV. Without a well-formed `commit`: 400
   `{"error":"commit_required"}`.
2. `POST /v1/pair?request=<id>&reveal=<64 hex>` reveals the client's bytes.
   The agent checks them against `commit` (a mismatch ends the request, 403)
   and answers 202 `{"request", "accepted": false, "expires_in"}`. Both sides
   now compute the code, and the client shows it to its user.
3. The agent's screen, whenever it is open while such a request waits, asks
   the viewer to allow or deny it, naming the client and the address it asked
   from and showing the code. The client may bring the screen up (the
   integration: its local gRPC app launch); opening the app by hand does the
   same. Deny has the focus, and BACK denies.
4. `POST /v1/pair?request=<id>` collects: 200 `{"token": "<64 hex>"}` once
   allowed, 202 `{"request", "accepted", "expires_in"}` until then, 403
   `{"error":"pairing_denied"}` when the viewer denied it, 403
   `{"error":"pairing_closed"}` when there is no such request (any more).

The code is the first four bytes of

    SHA-256("bravia-agent pairing 1" || 0x00 || client bytes || agent bytes
            || SHA-256 of the agent's DER certificate)

as a big-endian number, modulo 1,000,000, written with six digits (shown as
`123 456`). The agent uses its own certificate; the client uses the one it
saw on the connection, which it must have pinned before step 1. Test values
are in `PairingTest.codes` and the integration's `tests/test_agent.py`.

What the code is for:

- Another client's request has other bytes, so the TV shows a code that the
  user's client is not showing, and the viewer denies it.
- Something between the client and the agent, answering the client with a
  certificate of its own and passing the pairing on, makes the client compute
  from that certificate: again two different codes.
- Neither side can steer the code towards the other's. The client is bound
  by `commit` before it learns `nonce`, and the agent (or whatever answers in
  its place) must send `nonce` before it learns the client's bytes. That is
  why the bytes are revealed in a second step.

A wrong guess is right once in a million, and each try needs a person to look
at it.

- One request at a time. While one waits, another `POST /v1/pair?name=` gets
  409 `{"error":"pairing_busy", "retry_after": <seconds>}`.
- A request lapses 30 s after it was made unless it is on screen by then;
  from the moment it is, the viewer has 120 s; once allowed, it can be
  collected for 15 s. The address of a request that lapsed unanswered or was
  denied gets 409 for 60 s, so that one address cannot keep the place taken.
  (Several addresses taking turns can. That keeps others from pairing and
  gets them nothing.)
- The token in use stays valid until a new one has been collected, which
  replaces it. A changed or cleared token ends open event streams.
- Nothing a client sends and no launch allows a request; only the viewer
  does. A launch by the TV's control service
  (`com.sony.dtv.devicecontrolservice`, a system app, as the system recorded
  the caller; a referrer carried in the launching intent is ignored) runs a
  staged update if there is one, else opens the setup flow if
  `POST /v1/setup` announced it, else leaves the screen open for a waiting
  pairing request, else closes it again.
- The status screen shows who is paired (name and address) and any request
  that is waiting. Holding LEFT there clears the token.
- For development, `dumpsys activity service …/.AgentService pairing
  accept|deny` answers the waiting request as the viewer would.
- The mDNS TXT carries `p=0|1` (paired).

### Pairing until 0.9.1

A launch of the status screen by a package whose name began `com.sony.`
opened a 60 s window, during which `POST /v1/pair?name=<client>` answered
`{"token": "<64 hex>"}` to whoever asked first, and 403
`{"error":"pairing_closed"}` otherwise. The integration still pairs with such
agents this way, without the user (it asks, gets 403, launches the agent, and
asks again until the token comes). An integration from before 0.10.0 cannot
pair with an agent from 0.10.0: it sends no `commit`.

Why it changed: `/v1/info` told anyone when the window was open, so anything
on the network could ask before the integration did and take the pairing; and
the "launched by a Sony service" test read a referrer that the launching app
can write itself. Making the launch itself the confirmation (and nothing
else) would still have let any launch of the agent through the TV confirm
whatever request was waiting, so the answer is the viewer's.

Cloud delivery of a token (`braviaagent://pair?token=…` through the cloud
`application.deep_link`) was removed in 0.4.1. It needed the same LAN path to
verify the token, so it never helped when local pairing failed; the token went
through Sony's service and into the TV's system log (Sony's command processor
logs whole commands); and the link handler accepted links from any app or web
page, so anything on the TV could take over the pairing. Agents from 0.4.1
ignore pair links.

## `POST /v1/setup` (0.10.0)

Token. Announces that the next launch of the agent by the TV, within 30 s,
is to open the permission screens, and answers `{"ok": true}`. The
integration calls it before a local app launch when it has no cloud link to
send `braviaagent://setup` with. Older agents answer 404 or 405; there a bare
launch by the TV opens the pairing window instead.

## `GET /v1/state`

```json
{
  "ts": "2026-11-02T21:00:00Z",
  "revision": 412,
  "environment": {
    "illuminance_lx": 34.0,
    "temperature_c": 23.5
  },
  "display": {
    "on": true,
    "state": "on",
    "width": 3840,
    "height": 2160,
    "refresh_hz": 60.0,
    "hdr_supported": ["dolby_vision", "hdr10", "hlg"],
    "is_hdr": true
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
    {"id": "HW4", "input_id": "<the TV's full input id>", "label": "HDMI 3", "type": "hdmi", "connected": true, "standby": false}
  ],
  "audio": {
    "outputs": [{"type": "hdmi_earc", "name": "<product name>"}],
    "volume": {"music": 18, "max": 100},
    "muted": false,
    "mode": 0
  },
  "bluetooth": {
    "remote": {"name": "SONY TV VRC 001", "type": "hid", "connected": true, "battery_pct": 72},
    "devices": [
      {"name": "Bluetooth headphones", "type": "audio", "connected": false, "battery_pct": null}
    ]
  },
  "wifi": {"rssi_dbm": -63, "link_mbps": 648, "frequency_mhz": 5500},
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

Further blocks appear once their feature has something to say: `ambient`,
`remote_finder` and `dashboard`, described with their endpoints below.

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
- `revision` counts changes to the document since the agent started.
- `display.on` is the panel state (`Display.STATE_ON`); it is false during
  Picture Off and standby, and `state` is Android's name for it (`on`, `off`,
  `doze`, …). `hdr_supported` lists the HDR formats the panel can show and
  `is_hdr` is that capability, not what is playing: Sony's video pipeline
  bypasses Android's display layers, so the live format has to come from gRPC
  (`playback_control.signal_info.video`).
- `media.state` follows Android's `PlaybackState`: `playing`, `paused`,
  `stopped`, `buffering`, `none`, and more rarely `fast_forwarding`,
  `rewinding`, `connecting`, `skipping`, `error`, `unknown`. `position_s` is
  the position at `ts`; the integration extrapolates from `ts` while
  `playing`.
- `inputs[].id` is the TV input's hardware suffix (`HW2`…`HW5`) and
  `input_id` the whole id; `label` is the TV's own label when available;
  `type` is `hdmi`, `tuner`, `composite`, `component`, `display_port`, `vga`
  or `other`; `standby`
  is a connected device that is asleep.
- `audio.outputs` lists the output devices Android reports, by type
  (`speaker`, `hdmi`, `hdmi_arc`, `hdmi_earc`, `bt_a2dp`, `headphone`,
  `spdif`) and product name; `mode` is `AudioManager`'s mode number.
- `settings` contains only the keys the agent is built to report
  (`power.power_saving`, `power.idle_tv_standby.duration`,
  `power.auto_shut_off`, `network.remotestart`); values are the provider's raw
  strings.
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
every key optional (an absent key keeps the stored value), but a JSON body
there must be: 400 `json_body_required` without one. `poll_s` is clamped to
10–3600. Stored on the TV, so it survives restarts and standby; reported back
in `rest`. Clients re-send it on every connection. Answers the effective
settings.

## `POST /v1/picture`

Bearer. Body `{"target": "contrast", "value": "91"}`. Validated against the
last read: unknown target 404 `unknown_target`, `available: false` 409
`target_unavailable`, value outside the candidates 400 `invalid_value`. Then
written with the stored pre-shared key. No key, or rejected: 403 `rest_unauthorized` (and
`write_authorized` turns false). IP control not answering: 503
`rest_unavailable`. A body without both keys: 400 `target_and_value_required`.
Any other refusal by the TV: 500 `rest_error`. Success `{"ok": true}`,
followed by a re-read.

## `POST /v1/picture/batch`

Bearer. Body `{"settings": [{"target": "contrast", "value": "91"}, …]}`: many
targets at once, for restoring a picture profile (0.7.1). Each target that
differs from the TV's current value is written on its own (the TV fails a
multi-setting call), with one retry; targets already at their value are
skipped. Values are not checked against the candidates first: the TV decides.
Answers `{"applied": <n>, "failed": <n>, "results": [{"target", "ok",
"value"}]}` from a read-back, where `value` is what the TV now has. 400
`settings_required` without the list.

## `POST /v1/power_saving?mode=off|low|high`

Bearer. Same error handling as `POST /v1/picture`; 400 `mode_required`
without the parameter.

## `POST /v1/rest/psk`

Bearer. Body `{"psk": "..."}`: stores the IP-control pre-shared key and
verifies it with a read that requires it. Answers
`{"write_authorized": true|false}`.

## `DELETE /v1/rest/auth`

Bearer. Forgets the stored credential.

## Ambient brightness (`/v1/ambient`)

The agent can set the TV's Brightness slider from the room's light level, per
picture mode, SDR only. It runs on the TV; Home Assistant edits and mirrors it.
Off by default. State block `ambient` (also `GET /v1/ambient`):

```json
{"enabled": true, "learning": false,
 "state": "active", "reason": null,
 "picture_mode": "professional", "brightness": 24, "target": 24, "lux": 31,
 "own_curve": false, "anchors_lux": [0, 10, 25, 60, 150, 500],
 "curve": [14, 14, 17, 24, 40, 40],
 "curves": {"default": [14, 14, 17, 24, 40, 40], "modes": {}}}
```

- `state`: `off`, `active`, `paused` or `unavailable`. `reason` when not
  plainly active: `screen_off`, `hdr`, `manual`, `tv_auto_luminance`,
  `no_key`, `ip_control_unreachable`, `warming_up`, `no_light_sensor`.
- `curve`: the current picture mode's six slider values (0..50) at
  `anchors_lux`, interpolated in log-lux; the mode's own curve if it has one,
  otherwise the default. The default is built on first use from the user's
  brightness, shaped like Sony's own auto-luminance curve.
- `lux`: the room level last acted on (a low percentile of the sensor over
  45 s, so bright scenes don't count).

All POSTs return the new block:

- `POST /v1/ambient/config` `{"enabled"?, "learning"?}`.
- `POST /v1/ambient/curve` `{"mode"?, "anchors": [6]}`: set a mode's curve
  (no mode: the current picture mode). Kept non-decreasing.
- `POST /v1/ambient/reset` `{"mode"?}`: drop a mode's own curve.
- `POST /v1/ambient/curves` `{"default"?, "modes"?}`: replace all curves.
  All or nothing: a curve that is not six values leaves every curve as it
  was (0.10.0).
- `POST /v1/ambient/context` `{"signal": "sdr" | "hdr10" | "dolby_vision" | ...}`:
  the video's dynamic range class from Home Assistant's gRPC signal. The agent
  also treats HDR tone mapping being available as HDR, for when Home
  Assistant is down. The hint counts while a client holds `/v1/events` and for
  two minutes after; then it is dropped and the agent's own reading decides,
  so that a last word of "HDR" does not keep ambient brightness paused while
  Home Assistant is away (0.10.0). Clients send it on every change and on
  every connection.

Every POST needs a JSON body (400 `json_body_required`); a bad curve is 400
`invalid_curve`.

## `POST /v1/remote/find`

Makes the bundled remote beep, as the TV's own Find remote does: a Bluetooth
LE write to Sony's remote finder service on the paired "SONY TV VRC 001"
(details in RemoteFinder.java). Needs BLUETOOTH_CONNECT and the TV on. Waits
until the beep command is written or fails (at most ~15 s) and answers
`{"state": ...}`: `ringing`, `found`, `unsupported` (no finder service on the
remote), `no_remote`, `no_permission` or `failed` (and `connecting` if it is
still trying when the wait ends). The state block
`remote_finder` (`{"state", "reason", "at"}`) follows the beep: the remote
reports `02 01 00` when it starts and `02 01 01` when a button silences it
(found); `02` in the last byte would be a timeout. Its `state` returns to
`idle` when the remote stops by itself.

## `POST /v1/notify`

Shows a message on the TV screen as an overlay banner, over whatever is playing.
Body `{"message", "title"?, "icon"?, "duration_s"?}`; one of `message` and
`title` is enough (400 `message_required` with neither). `duration_s` defaults
to 8 and is capped at 120. `icon` is a friendly key
(`message`, `alert`, `info`, `home`, `cast`, `speaker`, `remote`, or `none`)
mapped to a native Sony SystemUI drawable, with a framework fallback. Needs the
SYSTEM_ALERT_WINDOW ("Display over other apps") permission; answers
`{"state": "shown" | "notification" | "failed", "overlay": <bool>}`. `shown` =
the overlay banner; `notification` = overlay not permitted, posted to the panel
instead, which from Android 13 needs the notification permission the setup
flow asks for (`permissions.post_notifications`). The banner is suppressed on
secure system screens (e.g. Settings), which hide non-system overlays.

## Dashboards (`/v1/dashboard`)

Shows a Home Assistant dashboard on the TV (added in 0.9.0). One at a time; a
new one replaces the one showing.

- `POST /v1/dashboard` body `{"url", "mode"?, "position"?, "size"?,
  "duration_s"?, "kiosk"?, "token", "expires_in"}`. `url` is an absolute
  `http(s)` Home Assistant URL (e.g. `http://192.168.1.5:8123/lovelace/0`); the
  agent adds `external_auth=1`. `mode` is `full` (default: full screen,
  interactive; the remote's arrows move between controls, OK presses, Back
  steps back in the page history and then asks before closing) or `overlay` (view-only window over what is playing; the remote keeps
  controlling the app behind). `position` (overlay only) is `full`, `left`,
  `right` (default), `top_left`, `top_right`, `bottom_left` or `bottom_right`;
  `size` is 20–100 (default 40): the width in percent of the screen for a side
  panel, width and height for a corner. `duration_s` is the time on screen,
  capped at 3600; omitted or 0 means until closed for `full` and 30 s for an
  overlay (an overlay can't be dismissed with the remote). The frontend is
  always put in kiosk mode, which hides its sidebar and editing controls.
  `kiosk` also hides the dashboard's title bar (the agent measures where it
  ends and moves the page up behind the frame's top edge; left visible if it
  can't be found). Omitted: on for `overlay`, off for `full`. `token` and
  `expires_in` (seconds) are a Home Assistant access token. Answers
  `{"state": "shown" | "no_permission" | "failed"}`; `no_permission` = no
  "Display over other apps" permission, which both modes need (Android lets a
  background app start an activity only with it). 400 with `invalid_url`,
  `invalid_mode`, `invalid_position`, `invalid_size` or `token_required`.
- `POST /v1/dashboard/token` body `{"token", "expires_in"}`: a fresh token.
- `DELETE /v1/dashboard`: close it.
- `GET /v1/dashboard`: the `dashboard` state block.

State block `dashboard`: `{"showing", "mode": "full" | "overlay" | null,
"loaded", "token_needed"}`. `loaded` turns true once the frontend has rendered.
`token_needed` turns true when the page asks for a token and the agent's is
missing, expiring within a minute, or was rejected; the client answers with
`POST /v1/dashboard/token` within 30 s, or the page's request fails.

How the page signs in: the agent's WebView exposes the Home Assistant
frontend's native-app interface (`window.externalApp`: `getExternalAuth`,
`revokeExternalAuth`, `externalBus`), as the official Android app does. Tokens
are held in memory only, never stored, and dropped when the dashboard closes.
Main-frame navigation is locked to the URL's origin, the interface answers only
while the page is on that origin, and replies go to the main frame only. The
TV must be able to reach Home Assistant at `url` (an isolated IoT network needs
a rule for it). A dashboard closes when the panel turns off, and the full-screen
one also when another app comes forward.

From 0.10.0: a token is kept only while a dashboard is showing (one posted
with none showing is dropped); the token is handed to the page only if the
main frame is still on the Home Assistant origin at that moment, not just
when the page asked; `revokeExternalAuth` is honoured only from that origin;
and if the page's renderer dies (a crash, or Android reclaiming its memory)
the dashboard closes and the agent carries on. Before, a dead renderer took
the whole agent down with it.

## `POST /v1/media/command`

Sends a transport command to the currently active media session (the one
`/v1/state` reports as now playing), so the integration can control playback on
the local session instead of over the Sony cloud. Body
`{"action", "position_ms"?}` where `action` is `play`, `pause`, `stop`, `next`,
`previous` or `seek` (`position_ms` is the absolute target for `seek`). Answers
`{"ok": <bool>}`: `ok` is false when there is no controllable session (e.g. live
TV or an input that publishes none), and the integration then falls back to the
cloud. 400 `action_required` without an action. Added in 0.7.1; older agents
answer 405 (an unknown path with a method other than GET) and the integration
falls back.

## `GET /v1/events`

`text/event-stream`. Events:

- `state` — data is the full `/v1/state` document (coalesced, at most twice
  per second).
- `heartbeat` — data `{}`, sent when 30 s have passed without a `state`
  event.

## `GET /v1/themes` (removed in 0.4.1)

Returned Living Decor's theme list. Removed with the integration's theme
support: the TV only lists the three most recently used themes to other
apps. Agents from 0.4.1 answer 404; integrations up to 0.4.1 ignore that.

## `GET /v1/media/artwork`

The current media session's artwork as `image/jpeg`, 404 `no_artwork` when
there is none. Only referenced from `media.artwork_url`.

## Discovery

mDNS service type `_bravia-agent._tcp`, port as served, TXT records
`v=1` (contract major), `tls=1`, `p=0|1` (paired) and
`id=<device_unique_id>` once the agent knows it (see `tv.device_unique_id`).

## Errors, limits and versioning

- Without the token: 401 `{"error": "unauthorized"}` for everything but
  `/v1/info` and `/v1/pair`, before the path or method is looked at.
- Unknown path: 404 `{"error": "not_found"}` for GET, 405
  `{"error": "method_not_allowed"}` for any other method; 405 also for a known
  path with the wrong method.
- A request may be at most: a 2 KiB request line (414), 8 KiB or 50 header
  lines (431), a 64 KiB body (413). Chunked request bodies are not accepted
  (501). The whole request must arrive within 10 s.
- Connections: 24 at once, of which one client address may hold 8 (0.10.0;
  before, 8 in all, which one client could fill). A new connection must
  finish its TLS handshake within 5 s and start its first request within 5 s
  more; a kept-alive connection may idle for 20 s. An answer to a request
  without the token closes the connection, so only the token's holder keeps
  one open. A connection over a limit is closed without an answer.
- A contract major bump changes the path prefix (`/v2/`); `/v1/` keeps
  serving for at least one agent release after that.
