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

## Docker

The image is the page and its little server — **not** the browser. The browser
stays on the machine wired to the tube, because that is the thing that has to
reach the display, and getting a container to draw on a Pi's screen is far more
trouble than it is worth.

```bash
docker run -d --name retroguide-crt --network host --restart unless-stopped   ghcr.io/coryfinnegan/retroguide-crt:latest
```

or `docker compose up -d` with the compose file here.

**Use `--network host`.** The LAN sweep only works if the container is on the
real network; on a bridge it scans Docker's own subnet and finds nothing. If
you must use a bridge — Docker Desktop on Windows or macOS — map `-p 8464:8464`
and type the ErsatzTV address by hand instead of discovering it.

### Publishing it

`.github/workflows/crt-image.yml` builds for amd64, arm64 and arm/v7 and pushes
to this repository's own registry. It is manual on purpose: run it from the
**Actions** tab, or push a `v*` tag. `GITHUB_TOKEN` already has the rights, so
there is no secret to set up.

**The package does not exist until something publishes to it** — there is
nothing to create beforehand. Run the workflow and it appears.

Published from this public repo it came out public already, so the Pi pulls
without logging in and no visibility step was needed. Worth checking rather
than assuming, since a bare manifest request answers 401 even for a public
image — every pull needs a token, and for a public one anybody can get it:

```bash
IMG=coryfinnegan/retroguide-crt
TOKEN=$(curl -s "https://ghcr.io/token?scope=repository:$IMG:pull&service=ghcr.io" | jq -r .token)
curl -s -H "Authorization: Bearer $TOKEN" "https://ghcr.io/v2/$IMG/manifests/latest" | head
```

If it ever does come out private, the visibility switch is on the package page
under **Packages** on your profile.

To push from this PC instead of a runner:

```bash
echo $TOKEN | docker login ghcr.io -u <you> --password-stdin   # PAT, write:packages
docker buildx build --platform linux/amd64,linux/arm64,linux/arm/v7   -t ghcr.io/<you>/retroguide-crt:latest --push crt/
```

Building arm on an x86 PC works through QEMU, which is slow — a few minutes.
The runner does the same thing without tying up this machine.

### The kiosk half, on the Pi

The container serves; Chromium displays. On Raspberry Pi OS:

```bash
sudo apt install -y chromium-browser
chromium-browser --kiosk --autoplay-policy=no-user-gesture-required   "http://localhost:8464/?host=192.168.1.200:8409"
```

To have it come up on boot, as a user service (`~/.config/systemd/user/retroguide.service`):

```ini
[Unit]
Description=Retro Guide kiosk
After=graphical-session.target

[Service]
ExecStart=/usr/bin/chromium-browser --kiosk --noerrdialogs --disable-infobars   --autoplay-policy=no-user-gesture-required   "http://localhost:8464/?host=192.168.1.200:8409"
Restart=always

[Install]
WantedBy=graphical-session.target
```

`systemctl --user enable --now retroguide`. Pinning `?host=` matters here: a
fresh browser profile has nothing saved and would otherwise stop at setup with
no keyboard attached.

A Pi 4 or 5 decodes 1080p H.264 in hardware and will be comfortable. Older
boards will struggle with 1080p — worth pointing the ffmpeg profile at 720p if
so, which is an ErsatzTV setting rather than anything here.

## Controls

| Key | Action |
|---|---|
| ↑ / ↓ | Change channel — in the guide, move the highlight |
| Number keys | Tune directly (a PC has the keypad a TV remote doesn't) |
| Enter / G | Open the guide — in the guide, watch the highlighted channel |
| Esc | Close the guide |
| I | Show the channel banner |
| A | Picture size — FILL / FIT / STRETCH |
| S | Screen — stretch to fill the raster, or letterbox |
| O | Adjust overscan |
| F | Full screen |

## Getting it onto the tube

An HDMI-to-AV converter shows up as an ordinary extra monitor, so the job is to
open the page full screen at that monitor's place on the virtual desktop:

```bash
python crt/tools/kiosk.py --list                          # which display is it?
python crt/tools/kiosk.py --host 192.168.1.200:8409       # open it there
python crt/tools/kiosk.py --set-mode 1280x720 --host ...  # its preferred timing
```

It finds the converter by its EDID name, starts the server if it is not already
running, and opens a browser window sized to that display. Three things learned
doing this:

- **`--kiosk` ignores `--window-position`.** Chrome opens it full screen on the
  primary display instead, which is no use when the whole point is the third
  one. An `--app` window lands where it is told, and `--start-fullscreen` — which
  does respect the position — makes it full screen once it gets there. Both are
  needed: an `--app` window on its own still carries a slim title bar, and at
  640x480 the taskbar covers the bottom of it, so the page ends up with a
  640x449 viewport, scales itself down to fit, and sits in a black border.
- **Stretch into the converter's own timing; do not hand it 4:3.** These boxes
  take a 16:9 signal and squash it into a 4:3 picture, so a 720p desktop reaches
  the tube horizontally compressed. The obvious fix — give it a 4:3 mode like
  640x480 — is wrong, and wrong in a way that is hard to see: 640x480 is not this
  converter's preferred timing (its EDID asks for 1280x720), so something
  upstream scales 4:3 up to 16:9 with the aspect preserved and writes black
  pillars into the signal. **Those bars are real pixels by the time they leave
  the PC, so a screenshot of the framebuffer shows a full screen and the tube
  still shows bars.** Only the tube can tell you.

  So do the opposite. Run the converter at its preferred timing and let the page
  scale its axes independently (`screen=stretch`, which `kiosk.py` pins) so every
  pixel of that 16:9 raster is used. The converter maps the whole raster onto the
  whole 4:3 picture, and the squash turns the 4:3 layout back into 4:3 on the
  tube. Nothing anywhere in the chain is left with an aspect mismatch to
  letterbox. **S** toggles it if a display ever wants the honest letterbox back.
- **The picture inside the picture.** ErsatzTV transcodes to 1920x1080 whatever
  the source was, so a 4:3 programme arrives with black pillars burnt into the
  frame. Fitting that 16:9 frame into the 4:3 page adds bars top and bottom too,
  and the result is windowboxed — a small picture with black on all four sides.
  The page therefore crops the frame back to 4:3 by default, which takes off
  almost exactly the pillars ErsatzTV put on. **A** cycles FILL / FIT / STRETCH
  for the occasional programme that really is widescreen.

Set the converter to whatever its EDID asks for — `--list` shows what it is on
now, and `--set-mode` changes it. The UI is laid out at 640×480 and scaled as one
piece: stretched to fill the raster on the converter, letterboxed on an ordinary
monitor, which is fine for setting up.

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
