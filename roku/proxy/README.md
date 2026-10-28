# HLS discontinuity filter

ErsatzTV's segmenter restarts ffmpeg for every programme item and marks each
boundary with `EXT-X-DISCONTINUITY`. With this instance's transcode profile
nothing actually changes at those boundaries — every segment is H.264 High
1080p24 SAR 1:1 with AAC stereo and PTS runs continuously across the splices
(probed, 2026-10-27) — but the Roku firmware trusts the tag: when one enters
its read-ahead buffer it stalls and seeks backwards 5–11 s, replaying the
previous commercial before jumping forward again. That is the Ad Break
oscillation in RetroGuide #7.

`hls_proxy.py` serves the same session playlist with only the discontinuity
markers removed, so the Roku plays through item boundaries as ordinary
segment joins. Segments stream straight from ErsatzTV; only playlist text
passes through here. There is no ErsatzTV setting for this — the
`discont_start` flag is hardcoded (see `OutputFormatHls.cs` at v25.1.0 and
upstream) — so filtering at serve time is the fix.

The Roku app probes `GET /health` at launch and uses filtered URLs only when
the proxy answers; otherwise it plays the direct segmenter URLs. A stopped
proxy therefore degrades to today's behavior, never to a dead TV.

## Run

Python 3, standard library only:

```text
python hls_proxy.py --port 8899 --etv http://127.0.0.1:8409
```

It must run on the same machine as ErsatzTV (it fetches the session
playlists over localhost). Install as a Windows Startup shortcut to
`pythonw.exe <path>\hls_proxy.py --port 8899` so it is there whenever the
TV is on. Roku fetches `http://<server>:8899/hls/<channel>.m3u8`.

## Test

```text
python test_hls_proxy.py
```

13 checks over the playlist transform; also runs in CI (`roku` workflow).
On-device proof is a 10-minute watch of channel 64 with no `[rg] watchdog`
resync lines and no STALLED lines on the console.
