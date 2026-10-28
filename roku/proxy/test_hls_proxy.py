#!/usr/bin/env python3
"""Unit tests for hls_proxy.filter_playlist — stdlib only, no framework.

Run:  python test_hls_proxy.py
Exit code is 0 when every check passes, 1 otherwise.
"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from hls_proxy import filter_playlist  # noqa: E402

# A real /iptv/session/64/hls.m3u8 window captured 2026-10-27: 10 segments,
# two item boundaries, monotonic sequence numbers and program dates.
SAMPLE = """#EXTM3U
#EXT-X-VERSION:6
#EXT-X-TARGETDURATION:4
#EXT-X-MEDIA-SEQUENCE:366
#EXT-X-DISCONTINUITY-SEQUENCE:54
#EXT-X-INDEPENDENT-SEGMENTS
#EXTINF:4.000000,
#EXT-X-PROGRAM-DATE-TIME:2026-10-27T19:36:30.856-0500
live000366.ts
#EXTINF:0.416667,
#EXT-X-PROGRAM-DATE-TIME:2026-10-27T19:36:34.856-0500
live000367.ts
#EXT-X-DISCONTINUITY
#EXTINF:4.000000,
#EXT-X-PROGRAM-DATE-TIME:2026-10-27T19:36:35.272-0500
live000368.ts
#EXTINF:4.000000,
#EXT-X-PROGRAM-DATE-TIME:2026-10-27T19:36:39.272-0500
live000369.ts
#EXTINF:4.000000,
#EXT-X-PROGRAM-DATE-TIME:2026-10-27T19:36:43.272-0500
live000370.ts
#EXTINF:3.166667,
#EXT-X-PROGRAM-DATE-TIME:2026-10-27T19:36:47.272-0500
live000371.ts
#EXT-X-DISCONTINUITY
#EXTINF:4.000000,
#EXT-X-PROGRAM-DATE-TIME:2026-10-27T19:36:50.439-0500
live000372.ts
#EXTINF:4.000000,
#EXT-X-PROGRAM-DATE-TIME:2026-10-27T19:36:54.439-0500
live000373.ts
#EXTINF:4.000000,
#EXT-X-PROGRAM-DATE-TIME:2026-10-27T19:36:58.439-0500
live000374.ts
#EXTINF:2.666667,
#EXT-X-PROGRAM-DATE-TIME:2026-10-27T19:37:02.439-0500
live000375.ts
"""

BASE = "http://192.168.1.200:8409/iptv/session/64/"

FAILURES = []


def check(name, condition, detail=""):
    if condition:
        print("ok - %s" % name)
    else:
        print("FAIL - %s %s" % (name, detail))
        FAILURES.append(name)


def main():
    out = filter_playlist(SAMPLE, BASE)
    lines = out.splitlines()

    check("no discontinuity tags remain",
          not any(l == "#EXT-X-DISCONTINUITY" for l in lines))
    check("no discontinuity-sequence line remains",
          not any(l.startswith("#EXT-X-DISCONTINUITY-SEQUENCE") for l in lines))
    check("media sequence untouched",
          "#EXT-X-MEDIA-SEQUENCE:366" in lines)
    check("target duration untouched",
          "#EXT-X-TARGETDURATION:4" in lines)
    check("independent-segments kept",
          "#EXT-X-INDEPENDENT-SEGMENTS" in lines)
    check("all ten segments present",
          sum(1 for l in lines if l.endswith(".ts")) == 10,
          "got %d" % sum(1 for l in lines if l.endswith(".ts")))
    check("segments absolutized to the session",
          all((BASE + "live%06d.ts" % n) in lines for n in range(366, 376)))
    check("durations pass through byte-identical",
          out.count("#EXTINF:4.000000,") == SAMPLE.count("#EXTINF:4.000000,")
          and "#EXTINF:0.416667," in lines
          and "#EXTINF:3.166667," in lines
          and "#EXTINF:2.666667," in lines)
    check("program dates pass through byte-identical",
          out.count("#EXT-X-PROGRAM-DATE-TIME:") == 10
          and "#EXT-X-PROGRAM-DATE-TIME:2026-10-27T19:36:35.272-0500" in lines)
    check("segment order preserved",
          [l for l in lines if l.endswith(".ts")]
          == [BASE + "live%06d.ts" % n for n in range(366, 376)])

    # A window with no splices must come back with only the URLs rewritten.
    plain = ("#EXTM3U\n#EXT-X-VERSION:6\n#EXT-X-TARGETDURATION:4\n"
             "#EXT-X-MEDIA-SEQUENCE:10\n#EXT-X-INDEPENDENT-SEGMENTS\n"
             "#EXTINF:4.000000,\n#EXT-X-PROGRAM-DATE-TIME:2026-10-27T19:36:30.856-0500\n"
             "live000010.ts\n")
    out2 = filter_playlist(plain, BASE)
    check("splice-free window keeps every line",
          out2.splitlines() == [
              "#EXTM3U", "#EXT-X-VERSION:6", "#EXT-X-TARGETDURATION:4",
              "#EXT-X-MEDIA-SEQUENCE:10", "#EXT-X-INDEPENDENT-SEGMENTS",
              "#EXTINF:4.000000,",
              "#EXT-X-PROGRAM-DATE-TIME:2026-10-27T19:36:30.856-0500",
              BASE + "live000010.ts"])

    # Absolute segment URLs (should never happen from ErsatzTV) are left alone.
    abs_in = plain.replace("live000010.ts",
                           "http://x/y/live000010.ts")
    check("absolute URLs untouched",
          "http://x/y/live000010.ts" in filter_playlist(abs_in, BASE))

    print("%d/%d checks passed"
          % (13 - len(FAILURES), 13))
    return 1 if FAILURES else 0


if __name__ == "__main__":
    sys.exit(main())
