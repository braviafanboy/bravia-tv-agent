# Changelog

The release workflow copies the section matching the tag into `agent.json`,
which Home Assistant shows as the update's release notes. Keep each section
under a `## <version>` heading, newest first.

## 0.3.1

- New built-in web server, replacing the unmaintained NanoHTTPD library. It
  serves exactly the requests Home Assistant makes and refuses anything else,
  with strict size and time limits and a cap on open connections. Requests
  answer faster (about 7 ms instead of 13 ms on an open connection), and the
  agent no longer bundles any third-party code. Nothing changes for Home
  Assistant or the pairing.
- Sharper now-playing artwork: sent at the app's full resolution (YouTube
  640×360 instead of 320×180, Spotify album art 300×300) as JPEG, which costs
  the TV about a tenth of the CPU of the previous WebP encoding.
- Removed Sony's PIN-based Normal authentication for the TV's IP control. The
  TV's PIN service refuses every set outside Japan, and the pre-shared key
  entered in Home Assistant works everywhere, so the key is now the only
  write credential. Nothing to do: Home Assistant already uses the key.

## 0.3.0

- Renamed to **HA TV Agent** so it cannot be mistaken for a Sony app; the
  status screen says it is an unofficial helper for the Home Assistant
  integration. Package name, links and pairing are unchanged, so it updates
  in place.
- Picture settings: the agent reads every picture-quality target the TV
  reports (68 on a BRAVIA 8 II: contrast, colour, colour temperature, local
  dimming, Motionflow, the Ambient Optimisation switches and more) and the
  power-saving mode from the TV's REST IP-control API, and publishes them in
  new `rest`, `picture` and `power_saving` state blocks. Reads need no
  credential; it works out by itself whether the TV speaks HTTPS or HTTP.
- Writing them: Normal authentication (the TV shows a PIN once; the session
  lasts 14 days and the agent renews it weekly) or a pre-shared key. Writes
  are validated against what the TV offers. The credential never leaves the
  TV.
- Refresh settings stored on the TV and set by Home Assistant: poll interval
  (default 60 s) and refresh on TV events (default on); short intervals read
  only the settings Home Assistant shows.
- Simpler status screen: a short plain-language list of what works and what
  is missing, instead of the raw state; OK grants whatever is missing.
- The Agent setup link closes the agent again once nothing is left to grant
  (and right away when nothing was missing), instead of leaving the status
  screen in front.
- adb-only maintenance commands
  (`adb shell dumpsys activity service com.braviafanboy.braviaagent/.AgentService`).

## 0.2.8

- Self-update from Home Assistant: the integration stages a release, the
  agent downloads it, verifies its SHA-256 and asks for one confirmation on
  the TV. Pairing and the certificate survive the update.
- Setup flow also requests permission to install unknown apps.
- Release builds are signed with the project's release key; a tag-triggered
  workflow publishes the APK, its checksum and the `agent.json` manifest.

## 0.2.7

- Test release for the self-update path.

## 0.2.6

- First build signed with the release key (an update from an earlier,
  debug-signed install needs one uninstall; the integration re-pairs).

## 0.2.5

- Launches by the integration open the pairing window without drawing a
  screen; a toast says so.
- `braviaagent://setup` opens the permission prompts for Notification access
  and Bluetooth.

## 0.2.4

- Slower fallback polls everywhere events exist: audio 5 min, Wi-Fi 10 min
  (with the RSSI broadcast), Bluetooth 10 min, settings 15 min.
- Now-playing position refreshed once a minute while a client listens.

## 0.2.3

- Sensors at 1 Hz; temperature seeded from the last reading and coaxed out
  of the HAL at start.
- Event-driven audio, Wi-Fi and settings readers with slow fallbacks.
- Artwork encoded lazily as WebP at 320 px, keyed on the bitmap.
- All readers pause while the panel is off; server threads run at background
  priority.

## 0.2.2

- Remote battery read rarely (every 6 h) over the stack's existing link, no
  held Bluetooth connection.

## 0.2.1

- Cloud-delivered pair links are adopted on the main activity without
  drawing; an unpaired agent refuses presented tokens.

## 0.2.0

- HTTPS only, with a self-signed EC certificate generated in the Android
  keystore; the integration pins its fingerprint.

## 0.1.3

- Artwork cache keyed on the bitmap, revision carried in `artwork_url`.

## 0.1.2

- Media sessions attach only after a successful query (fixes a blank
  now-playing right after an install).

## 0.1.1

- No gzip: the event stream was being buffered into bursts.
- Open event streams are closed when the token changes.

## 0.1.0

- First build: foreground service, HTTPS/JSON server with an event stream,
  mDNS advert, readers for environment, display, HDMI inputs, audio, Wi-Fi
  and Sony settings, media sessions, Bluetooth, bearer-token pairing.
