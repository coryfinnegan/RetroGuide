#!/usr/bin/env python3
"""Sideload and drive the Roku dev channel.

    rokudev.py deploy [secs]     package, install, launch, print the console
    rokudev.py install           package and install only
    rokudev.py launch            launch the dev channel
    rokudev.py log [secs]        read the BrightScript console (port 8085)
    rokudev.py key <Key> [n]     send an ECP key, e.g. Up / Down / Select / Back
    rokudev.py shot [path]       capture the screen to a jpg
    rokudev.py info              device info, and whether keypress is permitted

Address and password come from the environment so they stay out of the repo:

    ROKU_IP              e.g. 192.168.1.50
    ROKU_DEV_PASSWORD    the password set when enabling developer mode
"""
import os
import socket
import subprocess
import sys
import time
import urllib.error
import urllib.request

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", "..", ".."))
ROKU_DIR = os.path.join(ROOT, "roku")
ZIP = os.path.join(ROKU_DIR, "out", "retroguide.zip")

IP = os.environ.get("ROKU_IP", "")
PASSWORD = os.environ.get("ROKU_DEV_PASSWORD", "")


def need_ip():
    if not IP:
        sys.exit("set ROKU_IP (and ROKU_DEV_PASSWORD for install/screenshots)")
    return IP


def need_password():
    if not PASSWORD:
        sys.exit("set ROKU_DEV_PASSWORD - the password set when enabling developer mode")
    return PASSWORD


# --------------------------------------------------------------- ECP (no auth)

def ecp(path, method="POST"):
    url = "http://%s:8060/%s" % (need_ip(), path.lstrip("/"))
    req = urllib.request.Request(url, data=b"" if method == "POST" else None)
    try:
        with urllib.request.urlopen(req, timeout=15) as r:
            return r.status, r.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as e:
        return e.code, ""
    except (urllib.error.URLError, OSError):
        sys.exit("no answer from %s - is the TV on and on this network?" % IP)


def cmd_key(name, times=1):
    for i in range(int(times)):
        code, _ = ecp("keypress/" + name)
        if code == 403:
            sys.exit("403 - enable Settings > System > Advanced system settings > "
                     "Control by mobile apps > Network access > Permissive")
        if i + 1 < int(times):
            time.sleep(0.6)
    print("sent %s x%s" % (name, times))


def cmd_launch():
    ecp("launch/dev")
    print("launched")


def cmd_info():
    _, body = ecp("query/device-info", method="GET")
    for tag in ("model-name", "software-version", "developer-enabled"):
        start = body.find("<%s>" % tag)
        if start >= 0:
            end = body.find("</%s>" % tag)
            print("  %-20s %s" % (tag, body[start + len(tag) + 2:end]))
    code, _ = ecp("keypress/Info")
    print("  %-20s %s" % ("keypress", "permitted" if code == 200 else "BLOCKED (%s)" % code))


# ------------------------------------------------- dev server (digest auth)

def digest_post(path, fields, out_path=None):
    """Multipart POST with digest auth, which is what the dev server wants."""
    url = "http://%s%s" % (need_ip(), path)
    curl = ["curl", "-s", "--max-time", "120", "--digest",
            "-u", "rokudev:%s" % need_password()]
    for key, value in fields:
        curl += ["-F", "%s=%s" % (key, value)]
    curl += [url, "-o", out_path or "-"]
    return subprocess.run(curl, capture_output=out_path is None, text=True)


def cmd_install():
    subprocess.run([sys.executable, os.path.join(ROKU_DIR, "tools", "package.py")],
                   check=True)
    res = digest_post("/plugin_install",
                      [("mysubmit", "Install"), ("archive", "@" + ZIP)])
    body = res.stdout or ""
    if not body.strip():
        sys.exit("no answer from %s - is the TV on, and ROKU_DEV_PASSWORD right?" % IP)
    for marker in ("Application Received", "Identical to previous version",
                   "Failed", "Error"):
        if marker in body:
            start = body.find(marker)
            print("server: " + body[start:start + 90].split("<")[0].strip())
            return
    print("server: unexpected response (dev mode on? password right?)")


def cmd_shot(path="roku.jpg"):
    digest_post("/plugin_inspect", [("mysubmit", "Screenshot"), ("archive", "")],
                out_path=os.devnull)
    digest_post("/pkgs/dev.jpg", [], out_path=path)
    print("%s  %d bytes" % (path, os.path.getsize(path)))


# ------------------------------------------------------------------ console

def cmd_log(seconds=30, launch_first=False):
    """Read the BrightScript console. Connect first, then launch, so nothing
    from startup is missed - a crash during launch is invisible otherwise."""
    try:
        sock = socket.create_connection((need_ip(), 8085), timeout=10)
    except OSError:
        sys.exit("cannot reach the console on %s:8085 - is the TV on, "
                 "and developer mode still enabled?" % IP)
    sock.settimeout(1.0)
    time.sleep(0.5)
    if launch_first:
        cmd_launch()
    buf = b""
    end = time.time() + float(seconds)
    while time.time() < end:
        try:
            chunk = sock.recv(65536)
            if not chunk:
                break
            buf += chunk
        except socket.timeout:
            continue
    sock.close()
    text = buf.decode("utf-8", "replace")
    print(text if text.strip() else "(console produced no output)")


def main():
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    cmd, args = sys.argv[1], sys.argv[2:]
    if cmd == "deploy":
        cmd_install()
        time.sleep(2)
        cmd_log(args[0] if args else 45, launch_first=True)
    elif cmd == "install":
        cmd_install()
    elif cmd == "launch":
        cmd_launch()
    elif cmd == "log":
        cmd_log(args[0] if args else 30)
    elif cmd == "key":
        cmd_key(args[0], args[1] if len(args) > 1 else 1)
    elif cmd == "shot":
        cmd_shot(args[0] if args else "roku.jpg")
    elif cmd == "info":
        cmd_info()
    else:
        sys.exit(__doc__)


if __name__ == "__main__":
    main()
