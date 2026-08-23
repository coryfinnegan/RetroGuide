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

**The first publish is private.** Make it public once, at
`https://github.com/users/<you>/packages`, and the Pi can then pull without
logging in at all.

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
