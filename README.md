# Retro Guide

A TV player for [ErsatzTV](https://ersatztv.org), styled after the 1990s TV
Guide channel: a video window in the corner, a clock, and a scrolling grid of
channels against a blue gradient.

Two apps, one design, one server.

| | | |
|---|---|---|
| [**android/**](android) | Android TV / Google TV | Kotlin, Media3/ExoPlayer |
| [**roku/**](roku) | Roku / Roku TV | BrightScript, SceneGraph |

Each folder builds and installs independently — see the README inside it.

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
- **TV Guide overlay** — video window top-left, clock top-right, and a grid of
  channels against three half-hour columns drawn from XMLTV, wrapping around at
  both ends
- **Resumes** the last channel watched, and refreshes the lineup on launch
- **Settle before tuning** — surfing past ten channels asks the server for one
  stream rather than ten, since ErsatzTV starts an ffmpeg per request. The
  banner still moves with every press.

Back opens the guide on both platforms and never exits the app; Home is the way
out.

## Platform differences

|  | Android | Roku |
|---|---|---|
| Stream format | MPEG-TS (`.ts`) | HLS (`?mode=segmenter`) |
| Tune by number | Yes | No — no keypad on the remote |
| Screensaver | Held off explicitly | Suppressed by the OS during playback |
| Guide window | Shows the channel you're watching | Previews the highlighted channel |

Roku won't play raw MPEG-TS, and ErsatzTV's per-channel `.m3u8` either redirects
back to the `.ts` or, with `mode=hls-direct`, returns a single segment as long as
the whole programme — which Roku accepts and then fails on. `mode=segmenter`
returns an ordinary live playlist of short segments and plays. No server-side
change is needed for either app.
