# HA TV Agent

*Unofficial helper for the Bravia TV (gRPC) Home Assistant integration. Not affiliated with Sony or Home Assistant.*

A small Android TV app for Sony BRAVIA TVs. It reads what the TV keeps to
itself and hands it to the
[Bravia TV (gRPC)](https://github.com/braviafanboy/bravia-tv-grpc-homeassistant)
Home Assistant integration. It has no purpose on its own.

It needs Android 12 or later on the TV. Only a BRAVIA 8 II (Android 14) has
been tested.

## What it adds

- **Room sensors.** Ambient light in lux, the same reading the TV's auto
  brightness uses, and ambient temperature.
- **Now playing.** Title, artist, position and artwork from the foreground
  app, where the app publishes them.
- **Every picture setting.** All the settings in the TV's picture menu, read
  and, with an IP control key, changed from Home Assistant.
- **Remote and panel.** The remote's battery level and connection, a Find
  remote button that makes it beep, and whether the screen is actually lit.
- **On-screen messages.** Home Assistant can show a message on the TV screen,
  as a banner styled like the TV's own notifications.
- **Dashboards on the TV.** Home Assistant can put one of its dashboards on
  the screen, full screen to use with the remote or as a view-only panel over
  what is playing. It signs in with short-lived tokens from Home Assistant,
  kept in memory only; the TV must be able to reach Home Assistant.
- **Ambient brightness.** Optionally sets the picture Brightness from the room's
  light level, with a curve per picture mode (SDR only). Runs on the TV, so it
  keeps working while Home Assistant is down. Off by default.

The integration's [entity reference](https://github.com/braviafanboy/bravia-tv-grpc-homeassistant/blob/main/docs/entities.md)
lists every entity the agent provides.

## Install

You install it once with adb, Android's developer tool. The TV has no way to
fetch an app from outside Google Play, and its browser cannot download files.

1. **Turn on network debugging.** On the TV, go to Settings → System → About
   and select *Android TV OS build* seven times to unlock Developer options.
   Then turn on Settings → System → Developer options → *Network debugging*,
   and note the TV's IP address.
2. **Install from a computer** with the
   [Android platform tools](https://developer.android.com/tools/releases/platform-tools),
   using `bravia-agent.apk` from the
   [latest release](https://github.com/braviafanboy/bravia-tv-agent/releases):

   ```sh
   adb connect <tv-ip>
   adb install bravia-agent.apk
   ```

   Accept the *Allow debugging?* prompt on the TV.
3. **Pair it.** Within a few minutes Home Assistant shows a repair, *HA TV
   Agent is not paired*. Run it while you are at the TV: Home Assistant shows
   a six-digit code and opens the agent on the TV, which asks whether to
   allow the pairing and shows a code of its own. If the two are the same,
   choose **Allow** with the remote. The agent's entities then appear.
4. **Grant the optional permissions.** Now-playing needs *Notification
   access* and the remote's battery and Find remote need *Bluetooth*, and on-screen messages
   and dashboards need *Display over other apps*. Press **Agent setup**
   on the TV's device page in Home Assistant, or open the HA TV Agent app on
   the TV, and accept the prompts with the remote.

You can turn network debugging off afterwards.

## Updates

New versions appear as an update entity in Home Assistant, but nothing is
installed on its own. Each integration release offers the agent version it was
built and tested with, so the agent never gets ahead of the integration.
Installing downloads that release on the TV and asks for one confirmation on
screen. The first time, Android also asks for permission to install apps.
Updates keep the pairing. Checking for agent updates can be turned off in the
integration's options.

## Privacy and security

- The agent serves HTTPS only, on port 8585, with its own certificate. The
  integration pins that certificate before it pairs, so nothing else on the
  network can pose as the agent. If the certificate ever changes (the app was
  reinstalled), Home Assistant asks you before it pairs again.
- Paired or not, it answers nothing but its basic info and a request to pair
  unless the request carries the pairing token.
- Pairing is allowed by a person at the TV, and by nothing else. The agent's
  screen asks, naming the device that wants to pair and showing a six-digit
  code; Home Assistant shows its own. They are the same only when the request
  on the TV is the one Home Assistant made, to the agent itself and not to
  something answering in its place. Another device on the network can ask to
  pair, but the code it brings up is not the one you see in Home Assistant,
  and Deny is the default. The agent's screen shows who is paired; holding
  LEFT there removes the pairing.
- An update is only ever the agent's own app, from an https address, checked
  against its SHA-256, and Android asks you on screen before installing it.
- The IP control key, when set, stays in the app's private storage on the
  TV. The agent uses it only for calls to the TV itself.

The agent pairs with version 0.10.0 or later of the integration. An older
integration cannot pair with it.

## Development

See [docs/development.md](docs/development.md) for building, debugging and
releases, and [docs/contract.md](docs/contract.md) for the interface the
integration uses.

## License

[MIT](LICENSE), same as the integration.
