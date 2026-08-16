# Retro Guide (Roku)

A Roku / Roku TV player for [ErsatzTV](https://ersatztv.org), styled after the
1990s TV Guide channel: a video window in the corner, a clock, and a scrolling
grid of channels against a blue gradient.

This is a port of the [Android TV version](https://github.com/coryfinnegan/RetroGuide),
feature for feature. Nothing is hardcoded to a particular network — on first
launch the app scans your LAN for an ErsatzTV server, and you can also type an
address in by hand. The choice is saved to the registry, so it survives reboots.

## Features

- **Finds your server** — sweeps the local `/24` for port 8409 and confirms each
  hit by asking it for its version, so an unrelated service on the same port
  can't be mistaken for ErsatzTV
- **Live TV** — plays ErsatzTV's HLS streams
- **Channel surfing** — up/down, with a banner showing the channel number,
  name, and what's on now
- **TV Guide overlay** — video window top-left, clock top-right, and a grid of
  channels against three half-hour columns drawn from XMLTV
- **Wrap-around** — past the last channel returns to the first
- **Resumes** the last channel watched
- **Refreshes on launch**, so channels added on the server just appear

There is no tune-by-number, because no Roku remote has a keypad to type one on.
That is the only difference from the Android build.

## Streaming mode

Roku will not play raw MPEG-TS, and ErsatzTV's per-channel `.m3u8` just
redirects back to the `.ts`. The app therefore asks for the lineup as

```
/iptv/channels.m3u?mode=hls-direct
```

which is the one mode that returns a real playlist
(`application/vnd.apple.mpegurl`). No server-side configuration is needed.

## Enabling developer mode

On the Roku remote, from the home screen, press:

**Home ×3, Up ×2, Right, Left, Right, Left, Right**

The Developer Settings screen appears. Enable developer mode, set a password,
and let the device reboot. Note the IP address it shows you.

## Installing

```bash
python tools/package.py
```

That writes `out/retroguide.zip`. Upload it at `http://<roku-ip>/` in a browser
— log in as user `rokudev` with the password you set — or from the command line:

```bash
curl -s --digest -u rokudev:<password> -F mysubmit=Install -F archive=@out/retroguide.zip http://<roku-ip>/plugin_install
```

The app appears on the home screen. A Roku holds one sideloaded app at a time,
and it is replaced by the next install.

## Remote controls

| Button | Action |
|---|---|
| Up / Down | Change channel — in the guide, move the highlight |
| Back | Open the guide — in the guide, leave it without changing channel |
| OK | Show the channel banner — in the guide, switch to the highlighted channel |
| Options (`*`) | Change the server address |

Back never leaves the app — use Home to exit, as with any TV app.

The guide's corner window shows the channel you are watching, and moving the
cursor only moves the highlight — the same as the Android build. Previewing
each highlighted channel meant restarting the stream on every keypress, which
is too disruptive to watch around. Only OK changes the channel.

## Notes

The app talks to two standard endpoints — `/iptv/channels.m3u` and
`/iptv/xmltv.xml` — so it needs no ErsatzTV plugin or configuration. ErsatzTV
listens on all interfaces by default; if the Roku can't reach it, check the
firewall allows inbound TCP 8409.

Roku suppresses the screensaver during video playback, so unlike the Android
build there is nothing to do about it here.

A sideloaded app is unsigned and expires from the device on a factory reset.
Packaging for permanent installation requires a Roku developer account.
