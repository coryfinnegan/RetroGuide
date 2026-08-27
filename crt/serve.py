#!/usr/bin/env python3
"""Serve the CRT front end, and find ErsatzTV on the LAN.

    python serve.py                 # http://localhost:8464
    python serve.py --port 9000
    python serve.py --open          # launch a browser in kiosk mode too

Only two jobs: hand out the static files, and sweep the local /24 for a
server, which a browser cannot do for itself. Everything else the page does
it does straight against ErsatzTV, which sends CORS headers on the endpoints
that matter.
"""
import argparse
import json
import os
import socket
import subprocess
import sys
import threading
import urllib.error
import urllib.request
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer

STATIC = os.path.join(os.path.dirname(os.path.abspath(__file__)), "static")
ETV_PORT = 8409


def local_ip():
    """This machine's LAN address, without needing anything installed."""
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(("8.8.8.8", 80))          # no packets are sent
        return s.getsockname()[0]
    except OSError:
        return ""
    finally:
        s.close()


def probe(ip, found):
    """ErsatzTV answers /api/version with a version string and nothing else."""
    try:
        with urllib.request.urlopen(
                "http://%s:%d/api/version" % (ip, ETV_PORT), timeout=2) as r:
            body = r.read(64).decode("utf-8", "replace").strip()
        if body.startswith("v"):
            found.append({"host": "%s:%d" % (ip, ETV_PORT), "version": body})
    except (urllib.error.URLError, OSError, ValueError):
        pass


def discover():
    ip = local_ip()
    if not ip:
        return []
    base = ip.rsplit(".", 1)[0] + "."
    found, threads = [], []
    for i in range(1, 255):
        t = threading.Thread(target=probe, args=(base + str(i), found), daemon=True)
        t.start()
        threads.append(t)
    for t in threads:
        t.join(timeout=3)
    return sorted(found, key=lambda f: [int(p) for p in f["host"].split(":")[0].split(".")])


class Handler(SimpleHTTPRequestHandler):
    def __init__(self, *a, **kw):
        super().__init__(*a, directory=STATIC, **kw)

    def do_GET(self):
        if self.path.startswith("/api/discover"):
            payload = json.dumps({"servers": discover()}).encode()
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(payload)))
            self.end_headers()
            self.wfile.write(payload)
            return
        super().do_GET()

    def log_message(self, fmt, *args):
        pass                                  # a quiet console is a usable console


def make_server(port=8464):
    """The server itself, so the tray app can run one in a thread."""
    return ThreadingHTTPServer(("0.0.0.0", port), Handler)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=8464)
    ap.add_argument("--open", action="store_true", help="open a kiosk browser")
    args = ap.parse_args()

    url = "http://localhost:%d/" % args.port
    server = make_server(args.port)
    print("Retro Guide (CRT)  ->  %s" % url)
    print("this machine: %s   ctrl-c to stop" % (local_ip() or "unknown"))

    if args.open:
        for exe in (r"C:\Program Files\Google\Chrome\Application\chrome.exe",
                    r"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe"):
            if os.path.exists(exe):
                subprocess.Popen([exe, "--kiosk", "--autoplay-policy=no-user-gesture-required", url])
                break
        else:
            print("no Chrome or Edge found; open %s yourself" % url)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\nbye")


if __name__ == "__main__":
    main()
