# Retro Guide

An Android TV / Google TV player for [ErsatzTV](https://ersatztv.org), styled after
the 1990s TV Guide channel: a video window in the corner, a clock, and a scrolling
grid of channels against a blue gradient.

Nothing is hardcoded to a particular network. On first launch the app scans your
LAN for an ErsatzTV server, and you can also type an address in by hand. The
choice is saved, so it survives reboots and reinstalls of the server.

## Features

- **Finds your server** — sweeps the local `/24` for port 8409 and confirms each
  hit by asking it for its version, so an unrelated service on the same port
  can't be mistaken for ErsatzTV
- **Live TV** — plays ErsatzTV's MPEG-TS and HLS streams via Media3/ExoPlayer
- **Channel surfing** — d-pad up/down, with a banner showing the channel number,
  name, and what's on now
- **TV Guide overlay** — video window top-left, clock top-right, and a grid of
  channels against three half-hour columns drawn from XMLTV
- **Direct tuning** — type a channel number on the remote
- **Resumes** the last channel watched
- **Refreshes on launch**, so channels added on the server just appear

## Building

Requires a JDK 17+ and the Android SDK (platform 34, build-tools 34). The Gradle
wrapper is committed, so no separate Gradle install is needed.

```bash
git clone https://github.com/coryfinnegan/RetroGuide.git
cd RetroGuide
echo "sdk.dir=/path/to/Android/Sdk" > local.properties   # forward slashes on Windows too
./gradlew assembleDebug
```

The APK lands at `app/build/outputs/apk/debug/app-debug.apk`.

> `local.properties` is gitignored because it contains an absolute path to your
> own SDK. Create it yourself after cloning.

## Installing on Google TV

Enable developer mode on the device: **Settings → System → About**, click
**Android TV OS build** seven times, then turn on **USB debugging** and
**Network debugging** under Developer options.

```bash
adb connect <tv-ip>:5555
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Accept the "Allow debugging?" prompt on the TV. The app appears on the Google TV
home row, since it declares `LEANBACK_LAUNCHER`.

Sideloading tools such as Downloader or Send Files to TV work equally well if
you'd rather not enable debugging.

## Remote controls

| Button | Action |
|---|---|
| Up / Down, Channel +/- | Change channel |
| OK / Guide | Open the TV Guide |
| Number keys | Tune directly to that channel |
| Info | Show the channel banner |
| Back | Close the guide; while watching, press twice to exit |
| Settings / Red | Change the server address |

## Notes

The app talks to two standard endpoints — `/iptv/channels.m3u` and
`/iptv/xmltv.xml` — so it needs no ErsatzTV plugin or configuration. ErsatzTV
listens on all interfaces by default; if the TV can't reach it, check the
firewall allows inbound TCP 8409.

The app holds the screen awake while it is in the foreground, so the device's
screensaver won't start over a programme you're watching. Since watching TV
involves no button presses, Android would otherwise treat it as idle time.

Debug builds are signed with the standard Android debug key, which is fine for
sideloading but not for distribution.
