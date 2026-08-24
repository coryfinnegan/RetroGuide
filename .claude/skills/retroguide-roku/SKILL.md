---
name: retroguide-roku
description: Build, sideload, debug, and extend the Roku / Roku TV build of Retro Guide (the ErsatzTV player in roku/). Use when working on the BrightScript or SceneGraph code, the guide overlay, the corner preview window, HLS playback, or when sideloading to a Roku. Triggers on "the Roku app", "Roku TV", BrightScript, SceneGraph, "sideload", "dev channel", plugin_install, or a bug report about the TV app that is specific to Roku.
---

# Retro Guide — Roku

The BrightScript/SceneGraph app in `roku/`. Feature-for-feature with the Android
build (see `../retroguide-android/SKILL.md`) apart from tuning by number, which
no Roku remote can do. Nothing carries over between the two but the design.

## Device access

The helper needs the device address and dev password from the environment —
they are deliberately not in this repo:

```bash
export ROKU_IP=192.168.x.x
export ROKU_DEV_PASSWORD=...          # set when enabling developer mode
```

Ask the user for both rather than guessing. Enable developer mode from the
remote on the home screen: **Home ×3, Up ×2, Right, Left, Right, Left, Right**,
then set a password and let it reboot.

For key presses to work remotely, **Settings → System → Advanced system
settings → Control by mobile apps → Network access → Permissive**. Without it
every `keypress` returns 403 while queries and launches still succeed, which
looks like a broken script rather than a device setting.

## The helper

`scripts/rokudev.py` wraps everything (package, install, launch, keys,
screenshots, console):

```bash
python .claude/skills/retroguide-roku/scripts/rokudev.py deploy      # package + install + launch + console
python .claude/skills/retroguide-roku/scripts/rokudev.py log 30      # read the console
python .claude/skills/retroguide-roku/scripts/rokudev.py key Up 3    # ECP keys
python .claude/skills/retroguide-roku/scripts/rokudev.py shot out.jpg
```

**The console on port 8085 is the whole debugging story.** BrightScript prints
and crash backtraces go there and nowhere else; a crash mid-launch is otherwise
invisible. Connect before launching so nothing from startup is missed — that is
what `deploy` does. The app prints `[rg]` lines for the lineup, each tune, and
video state.

Roku's screenshot API does **not** reliably capture the video plane once it is
scaled into the guide's corner window — it comes back black or torn even while
playing. Trust `[rg] PLAYING` over a screenshot for the preview specifically.

## BrightScript traps that have bitten here

- **Identifiers are case-insensitive.** `PORT = 8409` and
  `port = CreateObject("roMessagePort")` are one variable; the second clobbered
  the first and crashed the app on launch with a bare Type Mismatch.
- **`const` is not BrightScript.** Put constants in `m` in `init()`.
- **A `Label` needs `wrap="true"`** or `numLines` does nothing and text clips.
- **Child order is z-order.** The guide's full-screen background painted over
  the video until the `Video` node was moved after it.
- **Escape `&` in XML** — an unescaped one makes the whole component fail to
  parse.
- **Network work belongs in a `Task` node**, never the render thread.
- **Sort channel numbers with `ToFloat`, not `ToInt`.** Sub-channel numbers like
  `23.1` are how a multiplex sits beside its parent; `ToInt` collapses them all
  to 23 and their order becomes whatever the M3U happened to list.

## Video, the part worth knowing

- **Roku will not play raw MPEG-TS.** Request the lineup normally and rewrite
  `/iptv/channel/N.ts` to `/iptv/channel/N.m3u8?mode=segmenter`.
  - plain `.m3u8` → 302 back to `.ts`
  - `?mode=hls-direct` → one segment as long as the whole programme; Roku
    accepts it and then fails with error **-3**, "an unexpected problem"
  - `?mode=segmenter` → an ordinary live playlist of 4s segments, and plays
  - No server-side change is needed; channels stay on TransportStreamHybrid so
    `.ts` keeps working for the Android app and TiviMate.
- **Resize the `Video` node only while stopped, then play.** Changing
  `width`/`height` on a playing node moves the window but leaves the video
  plane black; render-scaling does not help either. The documented examples set
  the size in XML markup, i.e. before playback. Opening and closing the guide
  therefore stops, resizes and restarts.
- **Channel change speed is a server setting, not an app one.** A player waits
  for ~3 target durations before starting, so with ErsatzTV producing one
  segment at a time every cold channel took a suspiciously exact ~13s. Raising
  `ffmpeg.segmenter.work_ahead_limit` brought that to 1.5-4.3s — see the
  `ersatztv` skill. If channel changes feel slow, measure before touching app
  code: a constant time points at the playlist, a variable one at transcoding.

## Guide behaviour

The corner window shows the channel being watched, and moving the cursor only
moves the highlight - the same as the Android build. Previewing each highlighted
channel was built and then removed: a Video node only survives a resize while
stopped, so every cursor move restarted the stream, which is too disruptive to
watch around. Only OK changes the channel.

## Packaging

`manifest`, `source/`, `components/` and `images/` must sit at the **root** of
the zip — an archive containing a single top-level folder will not install.
`roku/tools/package.py` does this correctly.

ECP key names are not the app's key names: the remote's OK button is
`Select` over ECP but arrives as `"OK"` in `onKeyEvent`.
