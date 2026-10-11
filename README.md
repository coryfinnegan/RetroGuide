# Retro Guide

[![android](https://github.com/coryfinnegan/RetroGuide/actions/workflows/android.yml/badge.svg)](https://github.com/coryfinnegan/RetroGuide/actions/workflows/android.yml)
[![roku](https://github.com/coryfinnegan/RetroGuide/actions/workflows/roku.yml/badge.svg)](https://github.com/coryfinnegan/RetroGuide/actions/workflows/roku.yml)
[![crt](https://github.com/coryfinnegan/RetroGuide/actions/workflows/crt.yml/badge.svg)](https://github.com/coryfinnegan/RetroGuide/actions/workflows/crt.yml)

A TV player for [ErsatzTV](https://ersatztv.org), styled after the 1990s TV
Guide channel: an info panel beside the picture, a clock, and a grid of
channels in flat, embossed boxes.

Two apps, one design, one server.

| | | |
|---|---|---|
| [**android/**](android) | Android TV / Google TV | Kotlin, Media3/ExoPlayer |
| [**roku/**](roku) | Roku / Roku TV | BrightScript, SceneGraph |
| [**crt/**](crt) | A CRT, fed from a PC | HTML/JS, hls.js, VCR styling, a Windows tray app |

Each folder builds and installs independently — see the README inside it. The
CRT build is self-hosted rather than installed: `python crt/serve.py --open` on
the machine wired to the tube. Its look is borrowed from
[240-MP](https://github.com/anthonycaccese/240-MP).

## Shared behaviour

Neither app is hardcoded to a particular network. On first launch it sweeps the
local `/24` for an ErsatzTV server and confirms each hit by asking it for its
version, so an unrelated service on the same port can't be mistaken for one. You
can also type an address in by hand. The choice is saved, so it survives
reboots.

Both talk to the same two standard endpoints — `/iptv/channels.m3u` and
`/iptv/xmltv.xml` — so no ErsatzTV plugin or configuration is needed.

- **Live TV** with channel surfing and a banner showing the channel number,
  name, and what's on now
- **TV Guide overlay** — video window top-left showing what you are watching,
  clock top-right, and a grid of channels against ninety minutes of XMLTV,
  wrapping around at both ends. The cursor moves the highlight; OK changes the
  channel; left/right moves through time a half hour at a time
- **Accurate times** — each programme is drawn at its real start and stop
  rather than snapped to half-hour columns. Almost nothing on ErsatzTV starts
  on a half hour, so the columns showed one cartoon in four and every edge was
  wrong. Runs of shorts too brief to label become one block spanning them
- **Themes** — Classic, Retro 90s and Halloween, picked in settings. See
  [THEMES.md](THEMES.md) for where the artwork comes from
- **Resumes** the last channel watched, and reads the lineup on launch. The
  channel list is deliberately *not* re-read while the app runs — swapping
  channels underneath someone watching is worse than a stale list — so a
  channel added on the server appears at the next start, and the app says so
  once with a fingerprint of the lineup rather than by diffing lists
- **Settle before tuning** — surfing past ten channels asks the server for one
  stream rather than ten, since ErsatzTV starts an ffmpeg per request. The
  banner still moves with every press.

Back opens the guide on both platforms and never exits the app; Home is the way
out.

## Working on this repo with Claude Code

`.claude/skills/` carries a skill per platform — `retroguide-android`,
`retroguide-roku` and `retroguide-crt` — covering the build and install loop, how
to drive a real device, and the traps each platform has already sprung. They load
automatically when Claude Code runs from this directory.

The Roku skill includes `rokudev.py`, which packages, sideloads, launches, sends
remote keys, takes screenshots and reads the BrightScript console. It takes the
device address and dev password from `ROKU_IP` and `ROKU_DEV_PASSWORD` so
neither ends up committed.

The CRT skill includes `crtdev.py`, which lists the displays and their preferred
timings, launches the kiosk on the converter, sends keys and captures the
framebuffer. It also carries the warning that matters most on that platform: a
capture shows what the page drew, not what the tube shows, so it cannot settle a
question about geometry — only somebody looking at the tube can.

## Platform differences

|  | Android | Roku |
|---|---|---|
| Stream format | MPEG-TS (`.ts`) | HLS (`?mode=segmenter`) |
| Tune by number | Yes | No — no keypad on the remote |
| Screensaver | Held off explicitly | Suppressed by the OS during playback |

The CRT build plays HLS like the Roku, tunes by number like the Android build
since it has a keyboard, and adds an adjustable overscan no other platform
needs.

Roku won't play raw MPEG-TS, and ErsatzTV's per-channel `.m3u8` either redirects
back to the `.ts` or, with `mode=hls-direct`, returns a single segment as long as
the whole programme — which Roku accepts and then fails on. `mode=segmenter`
returns an ordinary live playlist of short segments and plays. No server-side
change is needed for either app.
