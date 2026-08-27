#!/usr/bin/env python3
"""Put Retro Guide on the CRT.

    python crt/tools/kiosk.py                     find the converter, open the kiosk on it
    python crt/tools/kiosk.py --host 192.168.1.200:8409
    python crt/tools/kiosk.py --set-mode 640x480  switch that display to 4:3 first
    python crt/tools/kiosk.py --list              just show the displays

An HDMI-to-AV converter appears to Windows as an ordinary second monitor, so
the whole job is: find which one it is, and open a full-screen browser at that
monitor's position on the virtual desktop.

Aspect ratio is the part worth getting right, and the answer is not the obvious
one. These converters take a 16:9 signal and squash it into a 4:3 picture, so a
1280x720 desktop reaches the tube horizontally compressed. The tempting fix is
to feed it a 4:3 mode - but 640x480 is not the converter's preferred timing,
and something upstream of the tube then scales 4:3 up to 16:9 with the aspect
preserved and puts black pillars in the signal. They are real pixels by the time
they leave the PC, so no screenshot of the framebuffer will show them; they are
only visible on the tube.

So do the opposite: give the converter its own preferred timing, and pass
screen=stretch so the page scales its axes independently and uses every pixel of
that 16:9 raster. The converter squashes the full raster into the full 4:3
picture, which turns the 4:3 layout back into 4:3 on the tube. Nothing in the
chain is left with an aspect mismatch to letterbox.
"""
import argparse
import ctypes
import os
import subprocess
import sys
import time
import urllib.error
import urllib.request
from ctypes import wintypes

CRT_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PORT = 8464
CHROME = [
    r"C:\Program Files\Google\Chrome\Application\chrome.exe",
    r"C:\Program Files (x86)\Google\Chrome\Application\chrome.exe",
    r"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe",
]


class DEVMODE(ctypes.Structure):
    _fields_ = [
        ("dmDeviceName", ctypes.c_char * 32),
        ("dmSpecVersion", wintypes.WORD), ("dmDriverVersion", wintypes.WORD),
        ("dmSize", wintypes.WORD), ("dmDriverExtra", wintypes.WORD),
        ("dmFields", wintypes.DWORD),
        ("dmPositionX", ctypes.c_long), ("dmPositionY", ctypes.c_long),
        ("dmDisplayOrientation", wintypes.DWORD), ("dmDisplayFixedOutput", wintypes.DWORD),
        ("dmColor", ctypes.c_short), ("dmDuplex", ctypes.c_short),
        ("dmYResolution", ctypes.c_short), ("dmTTOption", ctypes.c_short),
        ("dmCollate", ctypes.c_short),
        ("dmFormName", ctypes.c_char * 32),
        ("dmLogPixels", wintypes.WORD),
        ("dmBitsPerPel", wintypes.DWORD),
        ("dmPelsWidth", wintypes.DWORD), ("dmPelsHeight", wintypes.DWORD),
        ("dmDisplayFlags", wintypes.DWORD), ("dmDisplayFrequency", wintypes.DWORD),
        ("dmICMMethod", wintypes.DWORD), ("dmICMIntent", wintypes.DWORD),
        ("dmMediaType", wintypes.DWORD), ("dmDitherType", wintypes.DWORD),
        ("dmReserved1", wintypes.DWORD), ("dmReserved2", wintypes.DWORD),
        ("dmPanningWidth", wintypes.DWORD), ("dmPanningHeight", wintypes.DWORD),
    ]


class DISPLAY_DEVICE(ctypes.Structure):
    _fields_ = [
        ("cb", wintypes.DWORD),
        ("DeviceName", ctypes.c_char * 32),
        ("DeviceString", ctypes.c_char * 128),
        ("StateFlags", wintypes.DWORD),
        ("DeviceID", ctypes.c_char * 128),
        ("DeviceKey", ctypes.c_char * 128),
    ]


user32 = ctypes.windll.user32
ENUM_CURRENT_SETTINGS = -1
CDS_UPDATEREGISTRY = 0x01
DM_PELSWIDTH, DM_PELSHEIGHT = 0x80000, 0x100000


def displays():
    """Every attached display: name, hardware id, position and size."""
    out, i = [], 0
    while True:
        dd = DISPLAY_DEVICE()
        dd.cb = ctypes.sizeof(dd)
        if not user32.EnumDisplayDevicesA(None, i, ctypes.byref(dd), 0):
            break
        i += 1
        if not (dd.StateFlags & 0x01):          # not attached to the desktop
            continue
        dm = DEVMODE()
        dm.dmSize = ctypes.sizeof(dm)
        if not user32.EnumDisplaySettingsA(dd.DeviceName, ENUM_CURRENT_SETTINGS,
                                           ctypes.byref(dm)):
            continue
        out.append({
            "name": dd.DeviceName.decode(errors="replace"),
            "desc": dd.DeviceString.decode(errors="replace"),
            "id": dd.DeviceID.decode(errors="replace"),
            "x": dm.dmPositionX, "y": dm.dmPositionY,
            "w": dm.dmPelsWidth, "h": dm.dmPelsHeight,
            "primary": bool(dd.StateFlags & 0x04),
        })
    return out


def monitor_names():
    """EDID names, so the converter can be recognised rather than guessed at."""
    try:
        ps = ("Get-CimInstance -Namespace root\\wmi -ClassName WmiMonitorID | "
              "ForEach-Object { ($_.UserFriendlyName | Where-Object {$_ -gt 0} | "
              "ForEach-Object {[char]$_}) -join '' }")
        res = subprocess.run(["powershell", "-NoProfile", "-Command", ps],
                             capture_output=True, text=True, timeout=25)
        return [l.strip() for l in res.stdout.splitlines() if l.strip()]
    except Exception:
        return []


def pick_display(args, screens):
    if args.display:
        for s in screens:
            if s["name"].endswith(args.display) or s["name"] == args.display:
                return s
        sys.exit("no display called %s" % args.display)
    # The converter is the odd one out: not primary, and usually the smallest.
    names = " ".join(monitor_names()).upper()
    external = [s for s in screens if not s["primary"]]
    if "MACROSILICON" in names and external:
        return min(external, key=lambda s: s["w"] * s["h"])
    if external:
        return min(external, key=lambda s: s["w"] * s["h"])
    return screens[0]


def set_mode(dev, w, h):
    dm = DEVMODE()
    dm.dmSize = ctypes.sizeof(dm)
    if not user32.EnumDisplaySettingsA(dev.encode(), ENUM_CURRENT_SETTINGS, ctypes.byref(dm)):
        sys.exit("could not read current mode for %s" % dev)
    dm.dmPelsWidth, dm.dmPelsHeight = w, h
    dm.dmFields = DM_PELSWIDTH | DM_PELSHEIGHT
    rc = user32.ChangeDisplaySettingsExA(dev.encode(), ctypes.byref(dm), None,
                                         CDS_UPDATEREGISTRY, None)
    if rc != 0:
        sys.exit("the display refused %dx%d (code %d) - see --list for what it accepts" % (w, h, rc))
    print("set %s to %dx%d" % (dev, w, h))
    time.sleep(2)                                # let the mode settle


def server_running():
    try:
        urllib.request.urlopen("http://localhost:%d/" % PORT, timeout=3).read(64)
        return True
    except (urllib.error.URLError, OSError):
        return False


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--host", help="ErsatzTV address, pinned so setup is skipped")
    ap.add_argument("--display", help=r"e.g. DISPLAY3; default is the converter")
    ap.add_argument("--set-mode", help="e.g. 1280x720 - usually the converter's own timing")
    ap.add_argument("--letterbox", action="store_true",
                    help="do not stretch to fill the raster (for a real monitor)")
    ap.add_argument("--list", action="store_true")
    args = ap.parse_args()

    screens = displays()
    if args.list:
        names = monitor_names()
        print("monitors reported by EDID: %s" % ", ".join(names) if names else "")
        for s in screens:
            print("  %-14s %5dx%-5d at (%5d,%4d) %s  %s"
                  % (s["name"], s["w"], s["h"], s["x"], s["y"],
                     "PRIMARY" if s["primary"] else "       ", s["desc"][:30]))
        return 0

    target = pick_display(args, screens)
    if args.set_mode:
        w, h = (int(v) for v in args.set_mode.lower().split("x"))
        set_mode(target["name"], w, h)
        target = next(s for s in displays() if s["name"] == target["name"])

    if args.letterbox and abs(target["w"] / target["h"] - 4 / 3) > 0.02:
        print("note: %s is %dx%d, which is not 4:3, and --letterbox means the page"
              % (target["name"], target["w"], target["h"]))
        print("      will bar the sides rather than stretch. On a converter those")
        print("      bars reach the tube. Drop --letterbox unless this is a monitor.")

    if not server_running():
        subprocess.Popen([sys.executable, os.path.join(CRT_DIR, "serve.py")],
                         cwd=CRT_DIR, creationflags=0x08000000)   # no console window
        for _ in range(20):
            if server_running():
                break
            time.sleep(0.5)
    print("server: http://localhost:%d/" % PORT)

    # screen=stretch by default: this launcher exists to drive a converter, and
    # a fresh kiosk profile has nothing saved to fall back on.
    q = []
    if args.host:
        q.append("host=" + args.host)
    q.append("screen=" + ("letterbox" if args.letterbox else "stretch"))
    url = "http://localhost:%d/?%s" % (PORT, "&".join(q))

    exe = next((c for c in CHROME if os.path.exists(c)), None)
    if not exe:
        sys.exit("no Chrome or Edge found - open %s on that display yourself" % url)
    # --app, not --kiosk: kiosk ignores --window-position and opens full screen
    # on the primary display, which is no use when the point is the third one.
    # --start-fullscreen does respect the position, so the window goes to the
    # converter AND is genuinely full screen there. Both halves are needed: an
    # --app window still carries a slim title bar, and at 640x480 the taskbar
    # sits over the bottom of it, so the page gets a 640x449 viewport, scales
    # itself down to fit, and leaves a black border all the way round.
    subprocess.Popen([
        exe, "--app=" + url,
        "--window-position=%d,%d" % (target["x"], target["y"]),
        "--window-size=%d,%d" % (target["w"], target["h"]),
        "--start-fullscreen",
        "--autoplay-policy=no-user-gesture-required",
        "--disable-features=TranslateUI",
        # its own profile, so the kiosk never inherits or disturbs your session
        "--user-data-dir=" + os.path.join(os.environ.get("TEMP", "."), "retroguide-kiosk"),
    ])
    print("kiosk opened on %s (%dx%d at %d,%d)"
          % (target["name"], target["w"], target["h"], target["x"], target["y"]))
    print("on the CRT: press O to set overscan, G for the guide, Alt+F4 to quit")
    return 0


if __name__ == "__main__":
    sys.exit(main())
