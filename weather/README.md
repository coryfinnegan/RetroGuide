# Weather Channel

A tray app that keeps ErsatzTV **channel 77 (Austin Weather)** current: it draws a
handful of pages in the style of a 1990s cable weather channel, renders them to a
video, and swaps that video in under the running channel every few minutes.

```
WeatherChannel.exe          no arguments; it lives in the tray
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

## The tray menu

- **Enabled** — stop and start the refresh. The icon greys out while paused.
- **Render now** — also what double-clicking the icon does.
- **Refresh every** — 5 / 10 / 15 / 30 / 60 minutes.
- **Open log** — `%LOCALAPPDATA%\ErsatzTV\logs\weather.log`, one line per render.
- **Open output folder**
- **Start with Windows** — an `HKCU\...\Run` entry pointing at this exe.

Settings live in `%LOCALAPPDATA%\ErsatzTV\weather-channel.json` — location,
channel name, output path, seconds per page, ffmpeg path. The file is written on
first run, so every knob is visible without reading the source.

Windows 11 hides tray icons it has not seen before behind the chevron. That is
the shell, not this app; drag it out once and it stays.

## Two things that are load-bearing

**The render must always be exactly the same length.** ErsatzTV stored the
duration when it scanned `weather.mp4` and builds the playout from it. Render a
different length — change `PageSeconds`, or add a page — and every later item in
the schedule drifts, silently. After changing either, rescan the Weather library
and rebuild channel 77.

**The file is replaced, never rescanned.** ErsatzTV re-reads the file from disk
each time it opens the item, so overwriting in place is enough — no scan, no
playout rebuild. But a half-written file would be read exactly as it stands,
hence encode-to-temp-then-move.

While somebody is watching the channel, ffmpeg holds `weather.mp4` open without
`FILE_SHARE_DELETE`, and the move fails with "access is denied" — the rename
needs delete access to the destination. It is not a permission problem. The
encoder retries for seven minutes; every couple of minutes the playout cuts to a
commercial, ffmpeg closes the file, and the swap lands.

## Build

```
dotnet build -c Release
dotnet publish -c Release -r win-x64 --self-contained false -p:PublishSingleFile=true \
  -o "%LOCALAPPDATA%\Programs\ErsatzTV Weather Channel"
```

Published there rather than run from `bin/` so the "Start with Windows" path
survives a rebuild.

## History

This replaced a Windows scheduled task running a Python script. The task worked,
but a render can legitimately sit for minutes waiting for ErsatzTV to release the
file — which the Task Scheduler shows only as a long-running instance — and
pausing it meant opening `taskschd.msc`.
