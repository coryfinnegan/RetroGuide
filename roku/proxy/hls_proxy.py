#!/usr/bin/env python3
"""Strip ErsatzTV's spurious HLS discontinuity tags for Roku.

ErsatzTV's segmenter restarts ffmpeg for every programme item and marks each
boundary with EXT-X-DISCONTINUITY (hardcoded `discont_start`; there is no
setting for it in v25.1.0 or upstream). But with this instance's transcode
profile the stream never actually changes at those boundaries: every segment
is H.264 High 1080p24 SAR 1:1 with AAC stereo, and PTS runs continuously
across the splices (verified by probing). The tag is a lie the tolerant
players ignore — and the Roku firmware does not: when a discontinuity enters
its read-ahead buffer it stalls and seeks backwards 5-11 seconds, replaying
the previous commercial before jumping forward again.

This proxy serves the same session playlist with only the discontinuity
markers removed, so the Roku plays through item boundaries as ordinary
segment joins. Segments themselves are untouched and stream straight from
ErsatzTV; only the playlist text passes through here.

URLs:
    GET /hls/<channel>.m3u8   filtered live playlist for a channel number
    GET /health               200 "ok" (the Roku app probes this at launch)

Install: run at logon next to ErsatzTV (it must reach ErsatzTV on
localhost), e.g. a Startup shortcut to:
    pythonw.exe C:\\path\\to\\hls_proxy.py --port 8899
The Roku app uses the proxy only when /health answers; otherwise it plays
the direct segmenter URL, so a stopped proxy degrades to today's behavior.
"""
import argparse
import re
import sys
import time
import urllib.error
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

SEGMENT_RE = re.compile(r"^live\d+\.ts$")
CHANNEL_RE = re.compile(r"^/hls/(\d+)\.m3u8$")
UPSTREAM_TIMEOUT = 5


def filter_playlist(text, segment_base):
    """Remove discontinuity markers; absolutize relative segment URLs.

    Everything else (media sequence, durations, program dates, versions)
    passes through byte-identical, so the timeline is unchanged.
    """
    out = []
    for line in text.splitlines():
        if line == "#EXT-X-DISCONTINUITY":
            continue
        if line.startswith("#EXT-X-DISCONTINUITY-SEQUENCE"):
            continue
        if SEGMENT_RE.match(line):
            out.append(segment_base + line)
        else:
            out.append(line)
    return "\n".join(out) + "\n"


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


_OPENER = urllib.request.build_opener(NoRedirect)
MAX_PLAYLIST_BYTES = 1024 * 1024


class SessionNotReady(Exception):
    pass


def fetch(url):
    """GET a small text resource. Redirects are an error, never followed.

    Following the session URL's cold-state 302 would download an infinite
    MPEG-TS stream, so any redirect (or non-200, or unreadable body) fails.
    """
    req = urllib.request.Request(url, headers={"User-Agent": "RetroGuide-HLS-Proxy/1.0"})
    try:
        with _OPENER.open(req, timeout=UPSTREAM_TIMEOUT) as resp:
            if resp.status != 200:
                raise SessionNotReady("upstream status %s" % resp.status)
            return resp.read(MAX_PLAYLIST_BYTES).decode("utf-8")
    except urllib.error.HTTPError as ex:
        raise SessionNotReady("upstream status %s" % ex.code)
    except (urllib.error.URLError, TimeoutError, ConnectionError) as ex:
        raise SessionNotReady("upstream unreachable (%s)" % ex)


def playlist_for_channel(etv_base, channel):
    """Fetch the live session playlist, or fail fast with SessionNotReady.

    A channel nobody is watching has no session yet: the session URL 302s
    back to the channel URL. Hitting the channel's segmenter URL once starts
    the transcode worker in the background; the caller gets a 503 now and the
    player's normal playlist refresh / app retry succeeds a few seconds
    later once segments exist. Never blocks waiting for the transcode.
    """
    session_url = "%s/iptv/session/%s/hls.m3u8" % (etv_base, channel)
    try:
        text = fetch(session_url)
    except SessionNotReady:
        text = None
    if text is not None and "live" in text and ".ts" in text:
        return text
    bootstrap_url = "%s/iptv/channel/%s.m3u8?mode=segmenter" % (etv_base, channel)
    try:
        fetch(bootstrap_url)
    except SessionNotReady as ex:
        raise SessionNotReady("could not start session for channel %s (%s)" % (channel, ex))
    raise SessionNotReady("transcoding channel %s, retry shortly" % channel)


class Handler(BaseHTTPRequestHandler):
    server_version = "RetroGuide-HLS-Proxy/1.0"

    def log_message(self, fmt, *args):
        sys.stderr.write("%s %s\n" % (time.strftime("%H:%M:%S"), fmt % args))

    def do_GET(self):
        if self.path == "/health":
            body = b"ok\n"
            self.send_response(200)
            self.send_header("Content-Type", "text/plain")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return
        m = CHANNEL_RE.match(self.path)
        if not m:
            self.send_error(404, "want /hls/<channel>.m3u8 or /health")
            return
        channel = m.group(1)
        try:
            text = playlist_for_channel(self.server.etv_base, channel)
        except SessionNotReady as ex:
            self.log_message("channel %s not ready: %s", channel, ex)
            self.send_error(503, str(ex))
            return
        except Exception as ex:
            self.log_message("channel %s error: %s", channel, ex)
            self.send_error(502, str(ex))
            return
        host = (self.headers.get("Host") or "").split(":")[0] or "127.0.0.1"
        etv_port = self.server.etv_port
        segment_base = "http://%s:%s/iptv/session/%s/" % (host, etv_port, channel)
        body = filter_playlist(text, segment_base).encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "application/vnd.apple.mpegurl")
        self.send_header("Cache-Control", "no-cache")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", type=int, default=8899)
    parser.add_argument("--etv", default="http://127.0.0.1:8409",
                        help="ErsatzTV base URL (default %(default)s)")
    args = parser.parse_args(argv)
    etv_base = args.etv.rstrip("/")
    etv_port = etv_base.rsplit(":", 1)[1].split("/")[0]
    server = ThreadingHTTPServer(("0.0.0.0", args.port), Handler)
    server.etv_base = etv_base
    server.etv_port = etv_port
    print("hls_proxy: serving on :%d, upstream %s" % (args.port, etv_base), flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
