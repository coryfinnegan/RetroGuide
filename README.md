# Retro Guide

A TV player for [ErsatzTV](https://ersatztv.org), styled after the 1990s TV
Guide channel: a video window in the corner, a clock, and a scrolling grid of
channels against a blue gradient.

Two apps, one design, one server.

| | | |
|---|---|---|
| [**android/**](android) | Android TV / Google TV | Kotlin, Media3/ExoPlayer |
| [**roku/**](roku) | Roku / Roku TV | BrightScript, SceneGraph |
| [**crt/**](crt) | A CRT, fed from a PC | HTML/JS, hls.js, VCR styling |

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
  clock top-right, and a grid of channels against three half-hour columns drawn
  from XMLTV, wrapping around at both ends. The cursor moves the highlight; OK
  changes the channel. A programme covering more than one half hour is drawn as
  a single block rather than repeated per column, so a film reads as a film
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

`.claude/skills/` carries a skill per platform — `retroguide-android` and
`retroguide-roku` — covering the build and install loop, how to drive a real
device, and the traps each platform has already sprung. They load automatically
when Claude Code runs from this directory.

The Roku skill includes `rokudev.py`, which packages, sideloads, launches, sends
remote keys, takes screenshots and reads the BrightScript console. It takes the
device address and dev password from `ROKU_IP` and `ROKU_DEV_PASSWORD` so
neither ends up committed.

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
