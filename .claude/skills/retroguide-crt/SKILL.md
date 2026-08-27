---
name: retroguide-crt
description: Run, drive, debug and extend the CRT build of Retro Guide (the ErsatzTV player in crt/) — the HTML/JS page served by crt/serve.py and shown on a real CRT through an HDMI-to-AV converter. Use when working on the page, its VCR styling, overscan, aspect ratio, the kiosk launcher or the Docker image, and whenever a bug report is about the picture on the tube. Triggers on "the CRT", "the tube", "the converter", "kiosk", "overscan", 240-MP, hls.js, or a geometry complaint — black bars, squashed or stretched picture, video not filling the screen.
---

# Retro Guide — CRT

The page in `crt/`: plain HTML/JS, hls.js vendored, served by `crt/serve.py`, shown
full screen on a CRT wired to this PC through an HDMI-to-AV converter. Same design
and same two endpoints as the TV builds — see `../retroguide-android/SKILL.md` and
`../retroguide-roku/SKILL.md` — but it is self-hosted rather than installed, and it
is the only build with an adjustable overscan and a keypad.

## Running it

```bash
python crt/serve.py --open                       # server + a kiosk browser here
python crt/tools/kiosk.py --host <etv-host>      # put it on the converter
```

The converter is an ordinary extra monitor to Windows, so `kiosk.py` finds it by
EDID name, starts the server if needed, and opens a browser window there.

## Driving it for testing

`scripts/crtdev.py` is the counterpart to the Roku skill's `rokudev.py`:

```bash
python .claude/skills/retroguide-crt/scripts/crtdev.py list          # displays + preferred timing
python .claude/skills/retroguide-crt/scripts/crtdev.py open <host>   # launch on the converter
python .claude/skills/retroguide-crt/scripts/crtdev.py key s         # send a key
python .claude/skills/retroguide-crt/scripts/crtdev.py key Up 3      # surf
python .claude/skills/retroguide-crt/scripts/crtdev.py shot out.png  # capture the framebuffer
python .claude/skills/retroguide-crt/scripts/crtdev.py close
```

Tune to a channel appropriate for whoever might walk past before leaving it —
the tube is in a living room, and the lineup runs from kids' cartoons in the low
numbers to an adult tier in the 70s.

## The screenshot trap — read this before believing a capture

**`shot` photographs the framebuffer, not the picture on the tube.** Everything
between the framebuffer and the phosphor — the GPU scaler, the converter — can add
black bars, and by then they are ordinary black pixels in the signal. A capture
showing a perfectly full screen and a tube showing thick black bars are entirely
consistent with each other.

This has already cost a session. The page was made to fill its window, the capture
confirmed it edge to edge, it was reported as verified, and the bars were still
there when a human looked at the tube.

So: a screenshot can prove the *page* is drawing what you meant. It can prove a
toast appeared, the guide opened, a key registered. **It cannot answer a question
about geometry.** For that there is exactly one instrument, which is a person in
front of the tube. Ask them, and ask in a way that distinguishes the cases — still
bordered, or filling but distorted, and which way.

## Geometry, the whole chain

Four places in a row can shrink or squash the picture, and each leaves the same
complaint. Working from the server outwards:

1. **ErsatzTV transcodes everything to 1920x1080.** A 4:3 programme therefore
   arrives with black pillars already in the frame — about 250px a side.
2. **A 16:9 frame in the 4:3 stage** adds bars top and bottom as well, and the
   result is windowboxed: a small picture with black on all four sides. The video
   is `object-fit: cover` for this reason, cropping the frame back to 4:3, which
   removes 240px a side against the ~250 ErsatzTV added — so it costs no picture
   worth having. **A** cycles FILL / FIT / STRETCH for genuinely widescreen shows.
3. **The 4:3 stage in a non-4:3 framebuffer** letterboxes, since `fit()` normally
   scales both axes together. `screen=stretch` scales them independently instead,
   so the layout uses every pixel of the raster. **S** toggles it.
4. **The framebuffer into the HDMI signal.** Ask for a mode that is not the
   display's preferred timing and something upstream of the tube rescales it with
   the aspect preserved, writing pillars into the signal itself. This is the one
   no screenshot can see.

**The rule that falls out of all four:** give the converter the timing its EDID
asks for — `crtdev.py list` prints it — and let the page stretch to fill it. The
converter maps the whole input raster onto the whole 4:3 picture, so a 4:3 layout
filling a 16:9 raster arrives as 4:3 on the tube; the squash undoes the stretch.
Nothing in the chain is left with an aspect mismatch to letterbox.

**The obvious alternative is wrong**, and wrong in a way that looks right on
paper. The converter squashes 16:9 into 4:3, so handing it a 4:3 mode ought to fix
the geometry, and 640x480 is also exactly what the page is laid out at — nothing
scaled anywhere. It fails: 640x480 is not this converter's preferred timing, so
step 4 pillarboxes it, and step 4 is invisible to every check you can run from the
PC. Do not reach for it again.

## Things that already went wrong here

- **`SetForegroundWindow` is refused, silently, for a background process.** It
  returns false, the window never comes forward, and the keys go to whatever was
  focused instead. Nothing raises, so a test appears to run and the screenshot
  afterwards shows an unchanged screen — indistinguishable from a change that did
  not work. `crtdev.py` attaches its input thread to the one that owns the
  foreground first, and then *verifies* the window came forward. Do not send keys
  without checking that.
- **A full-screen game will not give the foreground up at all**, attach or no
  attach — Counter-Strike held it through a whole run here. That is not a bug to
  work around: it means somebody is using this PC, and keys forced through would
  land in their game. `crtdev.py` names the window that has the foreground and
  sends nothing. Wait, or ask.
- **`--kiosk` ignores `--window-position`** and opens on the primary display,
  which is useless when the point is the third one. `--app` lands where it is
  told; `--start-fullscreen` respects the position too and makes it genuinely full
  screen once there. Both are needed — an `--app` window on its own carries a slim
  title bar, and the taskbar sits over the bottom of it, so the page gets a short
  viewport, scales itself down to fit, and lands in a border all round.
- **Display positions move when you change modes.** DISPLAY3 was at (5120,0) and
  after a couple of mode changes was at (5120,717). Read the rect at the moment
  you need it; never cache one across a mode change or hardcode it into a capture.
- **Parse EDID detailed timings carefully.** Horizontal upper bits are the high
  nibble of byte 4, vertical upper bits the high nibble of byte 7. Swapping them
  reports the converter as 1280x208, which reads as broken hardware rather than a
  broken parser.
- **A fresh kiosk profile has nothing in localStorage**, so it stops at the setup
  screen with no keyboard attached and no saved geometry. `kiosk.py` pins both
  `?host=` and `?screen=` in the URL for that reason. Keep new settings pinnable.
- **Autoplay needs `--autoplay-policy=no-user-gesture-required`**, and
  `requestFullscreen()` needs a real user gesture, so the **F** key cannot be
  triggered on load — full screen has to come from the browser's own flags.

## Design rules for an interlaced display

Already baked into `style.css`, and worth not undoing: nothing thinner than 2px,
because a 1px horizontal line lands on one field and buzzes; no small or thin
type, which the shadow mask cannot resolve; flat blocks of colour rather than
gradients, which band over composite. The safe area is inset by `--overscan`, set
with **O** and remembered, because every tube crops a different amount and the 5%
default is only a guess.

## Server side

`/iptv/channels.m3u` and `/iptv/xmltv.xml`, with nothing to configure — ErsatzTV
already sends the CORS headers a browser needs, so no proxy sits in front of the
video. Streams are requested as `?mode=segmenter`: a browser will not play raw
MPEG-TS, and that is the one form ErsatzTV serves as an ordinary live playlist of
short segments — the same finding as the Roku build, for the same reason.

The channel list is read once at startup and not again, so a changed lineup shows
up at the next start; listings refresh every half hour. Both match the TV builds.
