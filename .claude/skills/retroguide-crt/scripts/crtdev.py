#!/usr/bin/env python3
"""Drive the CRT build on the converter.

    crtdev.py list                 displays, EDID names, preferred timing
    crtdev.py open [host]          set up and launch the kiosk on the converter
    crtdev.py mode 1280x720        change the converter's mode, nothing else
    crtdev.py key <Key> [n]        send a key to the kiosk, e.g. s / Up / Enter
    crtdev.py shot [path]          capture the converter's framebuffer
    crtdev.py close                close the kiosk window

The counterpart to the Roku skill's rokudev.py. Everything about finding
displays and launching lives in crt/tools/kiosk.py and is imported rather than
repeated; what is here is what a test needs on top of that - focus, keys, and a
screenshot.

Read the screenshot caveat in SKILL.md before trusting `shot` for anything about
geometry. It photographs the framebuffer, not the picture on the tube.
"""
import ctypes
import os
import subprocess
import sys
import winreg
from ctypes import wintypes

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", "..", ".."))
sys.path.insert(0, os.path.join(ROOT, "crt", "tools"))
import kiosk                                          # noqa: E402

WINDOW_TITLE = "Retro Guide"

user32 = ctypes.windll.user32
kernel32 = ctypes.windll.kernel32


# ------------------------------------------------------------------- displays

def dtd(d):
    """Decode one EDID detailed timing descriptor.

    The active pixel counts are split across a shared byte, and the two nibbles
    are easy to swap: the horizontal upper bits are the HIGH nibble of byte 4,
    the vertical upper bits the HIGH nibble of byte 7. Getting the vertical one
    wrong reports a 1280x720 display as 1280x208, which reads as a broken
    monitor rather than a broken parser.
    """
    px = d[2] | ((d[4] & 0xF0) << 4)
    py = d[5] | ((d[7] & 0xF0) << 4)
    hb = d[3] | ((d[4] & 0x0F) << 8)
    vb = d[6] | ((d[7] & 0x0F) << 8)
    total = (px + hb) * (py + vb)
    hz = ((d[1] << 8 | d[0]) * 10000 / total) if total else 0
    return px, py, hz


def edids():
    """Each monitor's EDID name and preferred timing, from the registry."""
    raw = []

    def walk(path):
        try:
            key = winreg.OpenKey(winreg.HKEY_LOCAL_MACHINE, path)
        except OSError:
            return
        for i in range(winreg.QueryInfoKey(key)[0]):
            sub = path + "\\" + winreg.EnumKey(key, i)
            try:
                dk = winreg.OpenKey(winreg.HKEY_LOCAL_MACHINE, sub + "\\Device Parameters")
                raw.append(bytes(winreg.QueryValueEx(dk, "EDID")[0]))
            except OSError:
                walk(sub)

    walk("SYSTEM\\CurrentControlSet\\Enum\\DISPLAY")
    out = []
    for e in raw:
        if len(e) < 128:
            continue
        name = ""
        for off in (54, 72, 90, 108):
            d = e[off:off + 18]
            if d[0:3] == b"\x00\x00\x00" and d[3] == 0xFC:
                name = d[5:18].decode("ascii", "replace").strip().strip("\n")
        first = e[54:72]
        out.append((name or "?", dtd(first) if (first[0] or first[1]) else None))
    return out


def cmd_list():
    print("EDID:")
    for name, pref in edids():
        print("  %-16s %s" % (name, "preferred %dx%d @ %.0fHz" % pref if pref
                              else "no detailed timing"))
    print("displays:")
    for s in kiosk.displays():
        print("  %-14s %5dx%-5d at (%5d,%4d) %s  %s"
              % (s["name"], s["w"], s["h"], s["x"], s["y"],
                 "PRIMARY" if s["primary"] else "       ", s["desc"][:30]))
    return 0


def converter():
    class NoPreference:
        display = None
    return kiosk.pick_display(NoPreference(), kiosk.displays())


# ----------------------------------------------------------------- the window

def window_title(hwnd):
    n = user32.GetWindowTextLengthW(hwnd)
    if not n:
        return ""
    buf = ctypes.create_unicode_buffer(n + 1)
    user32.GetWindowTextW(hwnd, buf, n + 1)
    return buf.value


def find_window(title=WINDOW_TITLE):
    found = []
    proto = ctypes.WINFUNCTYPE(wintypes.BOOL, wintypes.HWND, wintypes.LPARAM)

    def visit(hwnd, _):
        if window_title(hwnd) == title and user32.IsWindowVisible(hwnd):
            found.append(hwnd)
        return True

    user32.EnumWindows(proto(visit), 0)
    return found[0] if found else None


def focus(hwnd):
    """Bring the kiosk forward - the only way that works from a script.

    SetForegroundWindow is refused for a process that does not already own the
    foreground, and refused *silently*: it returns false, the window never comes
    forward, and every key sent afterwards goes to whatever was focused instead.
    Nothing raises, so the test looks like it ran and the screenshot taken
    afterwards shows an unchanged screen - which is indistinguishable from a
    change that did not work. Attaching our input thread to the one that owns
    the foreground lifts the restriction; verifying afterwards catches the rest.
    """
    fg = user32.GetForegroundWindow()
    thread_fg = user32.GetWindowThreadProcessId(fg, None)
    thread_me = kernel32.GetCurrentThreadId()
    user32.AttachThreadInput(thread_me, thread_fg, True)
    user32.BringWindowToTop(hwnd)
    user32.SetForegroundWindow(hwnd)
    user32.AttachThreadInput(thread_me, thread_fg, False)
    kernel32.Sleep(400)
    if user32.GetForegroundWindow() != hwnd:
        # Name whatever is holding it. A full-screen game will not give the
        # foreground up at all, and that is not a bug to work around - it means
        # somebody is using this PC, and keys sent anyway would land in their
        # game rather than the kiosk.
        sys.exit("could not focus the kiosk: %r has the foreground and will not "
                 "give it up.\nKeys would have gone there instead, so nothing was "
                 "sent. Try again when that window is not in use."
                 % (window_title(user32.GetForegroundWindow()) or "another window"))


VK = {
    "up": 0x26, "down": 0x28, "left": 0x25, "right": 0x27,
    "enter": 0x0D, "return": 0x0D, "esc": 0x1B, "escape": 0x1B,
    "back": 0x08, "backspace": 0x08, "space": 0x20,
}


def cmd_key(name, times=1):
    hwnd = find_window()
    if not hwnd:
        sys.exit("no window called %r - run: crtdev.py open" % WINDOW_TITLE)
    focus(hwnd)
    vk = VK.get(name.lower())
    if vk is None:
        if len(name) != 1:
            sys.exit("unknown key %r - a single character, or one of: %s"
                     % (name, ", ".join(sorted(VK))))
        vk = user32.VkKeyScanW(ctypes.c_wchar(name)) & 0xFF
    for _ in range(int(times)):
        user32.keybd_event(vk, 0, 0, 0)
        user32.keybd_event(vk, 0, 2, 0)
        kernel32.Sleep(250)
    print("sent %s x%s" % (name, times))
    return 0


# ---------------------------------------------------------------- screenshots

def cmd_shot(path="crt.png"):
    target = converter()
    path = os.path.abspath(path)
    ps = ("Add-Type -AssemblyName System.Drawing; "
          "$b = New-Object System.Drawing.Bitmap {w},{h}; "
          "$g = [System.Drawing.Graphics]::FromImage($b); "
          "$g.CopyFromScreen({x},{y},0,0,(New-Object System.Drawing.Size {w},{h})); "
          "$b.Save({path},[System.Drawing.Imaging.ImageFormat]::Png); "
          "$g.Dispose(); $b.Dispose()").format(
              w=target["w"], h=target["h"], x=target["x"], y=target["y"],
              path="'" + path.replace("'", "''") + "'")
    result = subprocess.run(["powershell", "-NoProfile", "-Command", ps],
                            capture_output=True, text=True, timeout=60)
    if result.returncode != 0:
        sys.exit("capture failed: %s" % (result.stderr.strip() or result.stdout.strip()))
    print("%s  (%dx%d from %s)" % (path, target["w"], target["h"], target["name"]))
    return 0


# ------------------------------------------------------------------- the rest

def cmd_open(host=None):
    args = [sys.executable, os.path.join(ROOT, "crt", "tools", "kiosk.py")]
    if host:
        args += ["--host", host]
    return subprocess.call(args)


def cmd_mode(spec):
    width, height = (int(v) for v in spec.lower().split("x"))
    kiosk.set_mode(converter()["name"], width, height)
    return 0


def cmd_close():
    subprocess.run(["powershell", "-NoProfile", "-Command",
                    "Get-CimInstance Win32_Process -Filter \"Name='chrome.exe'\" | "
                    "Where-Object { $_.CommandLine -like '*retroguide-kiosk*' } | "
                    "ForEach-Object { Stop-Process -Id $_.ProcessId -Force "
                    "-ErrorAction SilentlyContinue }"],
                   capture_output=True, timeout=30)
    print("kiosk closed")
    return 0


COMMANDS = {
    "list": cmd_list, "open": cmd_open, "mode": cmd_mode,
    "key": cmd_key, "shot": cmd_shot, "close": cmd_close,
}


def main(argv):
    if len(argv) < 2 or argv[1] not in COMMANDS:
        print(__doc__)
        return 1
    return COMMANDS[argv[1]](*argv[2:]) or 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
