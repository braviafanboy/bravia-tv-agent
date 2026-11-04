# Changelog

The release workflow copies the section matching the tag into `agent.json`,
which Home Assistant shows as the update's release notes. Keep each section
under a `## <version>` heading, newest first.

## 0.4.2

- Every picture setting can now be changed from Home Assistant. Until now only
  11 could (picture mode, brightness, colour, contrast, hue, sharpness, auto
  local dimming, colour temperature, light sensor, colour space and HDR mode):
  the agent wrote with version 1.0 of the TV's API, which refuses every other
  setting, so changing for example Motionflow smoothness failed with "refused
  the picture setting change". Those settings now go through version 1.1.
  Verified on a BRAVIA 8 II: all 49 settings available at the time accept a
  write, contrast and black level visibly change the picture, and the TV's own
  picture menu shows the new values.
- Note: some apps ignore picture setting changes while they play. In Twitch
  the TV stored and showed the new value but the picture did not change; in
  YouTube it did.

## 0.4.1

- Pairing is local only. The agent no longer accepts pairing links: any app
  or web page on the TV could send one and take over the pairing, and when
  Home Assistant paired through the Sony cloud link the token passed through
  Sony's service and was written to the TV's system log (by the agent and by
  Sony's command processor). Home Assistant now always pairs by launching the
  agent and claiming a token in its 60-second window over TLS. Older
  integrations fall back to that automatically. To replace a token that may
  have been logged, hold LEFT on the agent's screen; Home Assistant pairs
  again within seconds.
- Living Decor theme list removed, together with Home Assistant's theme
  select. The TV only shows other apps the three most recently used themes,
  so the list could never offer the full catalogue. Living Decor itself can
  still be opened as an app from Home Assistant.
- Holding LEFT on the agent's screen clears its pairing again, as documented
  (it never worked: the key handler waited for a long-press flag Android only
  sets on repeats). Home Assistant pairs again on its own within seconds.
- RIGHT on the agent's screen no longer opens a pairing window. Home
  Assistant never needed it (it pairs on its own), and while the window was
  open any device on the network could take over the pairing. The screen
  now shows "Connected · pairing open, … s left" instead of hiding the
  connection while Home Assistant's own pairing window is open, and opening
  or closing that window no longer drops Home Assistant's connection.
- Clearer button help on the agent's screen: one line per remote button
  (Hold LEFT, BACK, and OK when a permission is missing) with what it
  does, below the status rows. The TV's address moved next to the version.

## 0.4.0

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
