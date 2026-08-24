---
name: retroguide-android
description: Build, install, debug, and extend the Android TV / Google TV build of Retro Guide (the ErsatzTV player in android/). Use when working on the Kotlin app, the guide overlay, channel surfing, the banner, discovery or setup, or when installing to a Google TV over adb. Triggers on "the Android app", "Google TV", "the APK", "adb install", ExoPlayer/Media3, or a bug report about the TV app's guide, banner, remote keys, or playback that is not specific to Roku.
---

# Retro Guide — Android TV

The Kotlin app in `android/`. Media3/ExoPlayer, leanback launcher, plays ErsatzTV's
MPEG-TS directly. See `../retroguide-roku/SKILL.md` for the Roku port and the
repo README for what the two share.

## Build

Requires a JDK 17+ and Android SDK platform 34 / build-tools 34.

```bash
cd android && ./gradlew assembleDebug
```

`local.properties` is gitignored, so create it after cloning. **Use forward
slashes even on Windows** — it is a Java properties file, so `C:\Android\Sdk`
parses as `C:AndroidSdk` and the build fails with a confusing SDK-not-found:

```
sdk.dir=C:/Android/Sdk
```

The APK lands at `android/app/build/outputs/apk/debug/app-debug.apk`.

## Install

```bash
adb connect <tv-ip>:5555
adb install -r android/app/build/outputs/apk/debug/app-debug.apk
```

On Android 14 port 5555 stays closed unless **Wireless debugging** is on in
Developer options — USB debugging alone is not enough, and `adb connect` just
reports a refused connection.

**Check who is using the TV before you install or launch.** Somebody may be
watching something else:

```bash
adb -s <tv> shell dumpsys activity activities | grep -m1 topResumedActivity
```

Installing a background app does not disturb the foreground one, but launching
does. Never launch, `monkey`, or send keyevents onto a TV in use.

## Drive it for testing

```bash
adb -s <tv> shell monkey -p com.ersatz.retroguide -c android.intent.category.LEANBACK_LAUNCHER 1
adb -s <tv> shell input keyevent KEYCODE_DPAD_DOWN     # surf
adb -s <tv> exec-out screencap -p > shot.png           # look at it
adb -s <tv> logcat -d | grep -iE "ExoPlayer|MediaCodec|retroguide"
```

Read the screenshot. A test that only checks logs will happily "pass" while the
screen shows a launcher, and that has happened here: a `KEYCODE_BACK` sent to
set up a test exited the app, and the DPAD press that followed went to the
launcher rather than the player.

Tune to a channel appropriate for whoever might walk past — this is a family
TV, and blind channel-surfing tests have landed on horror.

## Things that already went wrong here

- **Parse XMLTV with `XmlPullParser`, never a regex.** A real lineup is ~4.5 MB
  and 10k programmes; a lazy `(.*?)` under DOT_MATCHES_ALL rescans from every
  failing position and pinned the CPU at 127% without finishing.
- **`Activity.onKeyDown` runs *before* the framework's focus search.** Handling
  DPAD there unconditionally replaces normal row-to-row movement instead of
  extending it. To wrap a list at its ends, read the focused position and
  return false unless you are actually at an edge.
- **A focus listener must repaint on unfocus too.** Painting only on focus
  leaves a trail of highlighted rows behind the cursor.
- **Rows are recycled** — repaint from `bindingAdapterPosition`, not the
  position captured when the listener was created.
- **BACK opens the guide and never exits**; HOME is the way out. Before that it
  fell through to `super` and dropped users on the launcher mid-programme.
- **Hold the screen awake** (`FLAG_KEEP_SCREEN_ON`). Watching TV involves no
  button presses, so the device counts it as idle and the screensaver comes up
  over the picture.
- **Settle before opening a stream.** ErsatzTV starts an ffmpeg per request, so
  one request per keypress leaves several starting and tearing down at once,
  and one that has not begun emitting answers with bytes ExoPlayer cannot
  sniff (`ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED`) on a channel that is fine.
  The banner moves immediately; the stream waits ~400-650ms.
- **Never rebind a focused list on a timer.** The clock tick called
  `notifyDataSetChanged()` on the guide every 30 seconds, which rebinds every
  row and takes focus off whatever the cursor was on. To the viewer the d-pad
  simply stopped working, and the next press jumped to the top of the list -
  a bug that only shows up if you sit in the guide for half a minute, which no
  quick test does. Rebind only when the content actually changed (here, when
  the half hour rolls over) and put focus back afterwards.
- **`KEYCODE_SETTINGS` never reaches the app.** Google TV opens its own system
  panel over the top. `KEYCODE_MENU` does arrive, and is free once BACK opens
  the guide.
- **Parse channel numbers as float, not int.** A multiplex uses sub-channel
  numbers like `23.1`, and `toIntOrNull()` returns null for those - they all
  landed in the `Int.MAX_VALUE` bucket at the bottom of the list, nowhere near
  the channel they belong to. Leave room in the number column for four
  characters too.
- **Register a `Player.Listener`.** Without one a failed open is a silent black
  screen with nothing in the log.

## Server side

Uses `/iptv/channels.m3u` (raw `.ts` URLs) and `/iptv/xmltv.xml`. Nothing needs
configuring on ErsatzTV. `<title>` in XMLTV is the *show* title, so a show with
no metadata shows its folder name here — that is an ErsatzTV data problem, not
an app bug; see the `ersatztv` skill.
