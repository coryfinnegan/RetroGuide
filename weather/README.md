# Weather Channel

A tray app that keeps ErsatzTV **channel 77 (Austin Weather)** current: it draws a
handful of pages in the style of a WeatherSTAR 4000 — the box the cable company kept in
a closet that generated The Weather Channel's local segments through the late 80s and
90s — renders them to a video, and swaps that video in under the running channel every
few minutes.

```
WeatherChannel.exe                     lives in the tray
WeatherChannel.exe --preview <dir>     render the pages to PNGs and exit
```

## What it does

1. `NwsClient` reads api.weather.gov — current conditions, today's narrative, the
   hourly forecast, the extended forecast and any active alerts. No API key; NWS
   asks only for a User-Agent that identifies the caller.
2. `PageBuilder` lays out five pages of HTML on a 640x480 canvas, the same canvas
   the CRT build is designed at.
3. `ChromeRenderer` screenshots each page with headless Chrome (or Edge).
4. `VideoEncoder` concatenates the PNGs into `D:\ETV\Weather\weather.mp4`,
   upscaled to 1440x1080 with nearest-neighbour so the pixels stay chunky.

`RenderService` is a `BackgroundService` that does the above on a timer;
`TrayContext` is the icon that turns it on and off.

## The look

What makes the pages read as that machine rather than as a generic dark UI:

- the **Star4000 typeface**, and a hard 3px black drop shadow on every glyph, which
  is what the original's character generator produced;
- a purple-to-orange background wash, with a gold header band cut away on a diagonal
  at its right end;
- page titles in yellow and the clock and date in white, both stacked on two lines;
- content on a translucent periwinkle panel;
- the **extended forecast as three bordered columns** — day name, icon, conditions,
  and a Lo/Hi pair on a deeper blue block.

The icons in `WeatherIcons.cs` are drawn as SVG rather than sourced: the originals are
8-bit sprites, and anything photographic looks wrong beside a bitmap font. A cloud is
four overlapping shapes drawn twice — a fat dark pass underneath, the fill on top — so
the silhouette gets an outline and the seams between the shapes do not.

The four `fonts/Star4000*.woff` faces come from the MIT-licensed
[ws4kp](https://github.com/netbymatt/ws4kp) project and are recreations of the
character generator's typeface. They are embedded in the assembly and inlined into the
page as data URIs, because the app publishes as a single file and the pages render from
a temp directory where a relative font path would not resolve. Without them Chrome
falls back to Consolas, which is what made the first version of these pages look like a
terminal.

## The tray menu

- **Enabled** — stop and start the refresh. The icon greys out while paused.
- **Render now** — also what double-clicking the icon does.
- **Refresh every** — 5 / 10 / 15 / 30 / 60 minutes.
- **Open log** — `%LOCALAPPDATA%\ErsatzTV\logs\weather.log`, one line per render.
- **Open output folder**
- **Start with Windows** — an `HKCU\...\Run` entry pointing at this exe.

Settings live in `%LOCALAPPDATA%\ErsatzTV\weather-channel.json` — location, channel
name, output path, loop length, ffmpeg path, safe area. The file is written on first
run, so every knob is visible without reading the source.

Windows 11 hides tray icons it has not seen before behind the chevron. That is the
shell, not this app; drag it out once and it stays.

## Overscan

`SafeAreaPercent` (default 8) is the percentage of each edge kept clear of content. A
tube throws away the outer edge of the picture and how much varies by set, so this is a
dial rather than a constant. The background still bleeds to the edge — what the CRT
eats is wash, not words. Broadcast title-safe is 10; raise it if the tube still clips,
lower it to use more of the screen. It is read per render, so a change takes effect on
the next pass with no rebuild.

`--preview <dir>` renders the pages straight to PNGs without touching `weather.mp4`,
which is how to look at a layout change while the channel is on the air.

## Two things that are load-bearing

**The render must always be exactly `LoopSeconds` long.** ErsatzTV stored the duration
when it scanned `weather.mp4` and builds the playout from it; render a different length
and every later item in the schedule drifts, silently. Note that it is the *total* that
is fixed, not the per-page time — pages are conditional on what the forecast API
returned, so a degraded response can legitimately produce four pages instead of five,
and the encoder pays for that in page duration. If you change `LoopSeconds`, rescan the
Weather library and rebuild channel 77.

**The file is replaced, never rescanned.** ErsatzTV re-reads the file from disk each
time it opens the item, so overwriting in place is enough — no scan, no playout
rebuild. But a half-written file would be read exactly as it stands, hence
encode-to-temp-then-move.

While somebody is watching the channel, ffmpeg holds `weather.mp4` open without
`FILE_SHARE_DELETE`, and the move fails with "access is denied" — the rename needs
delete access to the destination. It is not a permission problem. The encoder retries
for seven minutes; every couple of minutes the playout cuts to a commercial, ffmpeg
closes the file, and the swap lands.

## Known issue

The video's audio is digital silence, and channel 77's FFmpeg profile applies
`loudnorm`. Loudness-normalising pure silence produces NaN, which the AAC encoder
rejects — ErsatzTV logs `Input contains (near) NaN/+-Inf` and the transcode exits −22.
The session restarts itself, so it shows as a brief dropout rather than a dead channel.
Putting a real music bed in `MusicDirectory` fixes it; so would putting the channel on
an FFmpeg profile without loudness normalisation.

## Build

```
dotnet build -c Release
dotnet publish -c Release -r win-x64 --self-contained false -p:PublishSingleFile=true \
  -o "%LOCALAPPDATA%\Programs\ErsatzTV Weather Channel"
```

Published there rather than run from `bin/` so the "Start with Windows" path survives a
rebuild.

## History

This replaced a Windows scheduled task running a Python script. The task worked, but a
render can legitimately sit for minutes waiting for ErsatzTV to release the file — which
the Task Scheduler shows only as a long-running instance — and pausing it meant opening
`taskschd.msc`.
