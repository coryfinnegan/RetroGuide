# Retro Guide (CRT)

A self-hosted front end for [ErsatzTV](https://ersatztv.org) meant for a CRT,
styled after a VCR's on-screen display. Runs on the PC that feeds the tube —
HDMI out, converter, component in.

The look is borrowed from [240-MP](https://github.com/anthonycaccese/240-MP) by
Anthony Caccese, which does the same thing for a media library. This is not a
port of it: that project is C++/Qt/QML around MPV, and this is a page in a
browser, because the streams are already HLS and a browser plays HLS.

## Running it

Needs Python 3 and Chrome or Edge. Nothing to install.

```bash
python crt/serve.py --open
```

That serves the page on `http://localhost:8464/` and opens a kiosk browser at
it. Without `--open`, browse there yourself and press **F** for full screen.

On first run it sweeps the local network for ErsatzTV — the page cannot do that
itself, so the little server does it. Pick your server and it is remembered.
You can also pin it, which is worth doing in whatever shortcut you use to
launch this:

```
http://localhost:8464/?host=192.168.1.200:8409
```

## Controls

| Key | Action |
|---|---|
| ↑ / ↓ | Change channel — in the guide, move the highlight |
| Number keys | Tune directly (a PC has the keypad a TV remote doesn't) |
| Enter / G | Open the guide — in the guide, watch the highlighted channel |
| Esc | Close the guide |
| I | Show the channel banner |
| O | Adjust overscan |
| F | Full screen |

## Getting it onto the tube

Set the desktop to **640×480 or 800×600** and let the converter output 480i.
The UI is laid out at 640×480 and scaled as one piece, so it keeps 4:3 on a
CRT and letterboxes on anything else — a modern panel is fine for setting up.

**Set the overscan before anything else.** Press **O** and adjust until the
amber frame is just inside the visible picture. Every tube crops a different
amount, and the default 5% is only a guess; the setting is remembered.

The design assumes an interlaced display throughout: nothing thinner than 2px,
because a 1px horizontal line lands on one field and buzzes; no small or thin
type; flat blocks of colour rather than gradients, which band over composite.

For the full effect install the **VCR OSD Mono** font (by Riciery Leal) on the
PC — the page asks for it first and falls back to whatever monospace exists.
It is not bundled here, to keep the repo free of fonts it has no licence to
redistribute.

## Notes

Talks to the same two endpoints as the TV builds, `/iptv/channels.m3u` and
`/iptv/xmltv.xml`, and needs nothing configured on ErsatzTV — it already sends
the CORS headers a browser needs, so there is no proxy in the way of the video.

Streams are requested as `?mode=segmenter`: a browser will not play raw
MPEG-TS, and that is the one form ErsatzTV serves as an ordinary live playlist
of short segments. Playback is [hls.js](https://github.com/video-dev/hls.js)
(MIT), vendored under `static/vendor/` so this runs with no network but yours.

The channel list is read at startup and not again — a changed lineup appears at
the next start, and the banner says so once. Listings are re-read every half
hour, since they age out. Both match the Android and Roku builds.
