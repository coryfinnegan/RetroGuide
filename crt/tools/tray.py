#!/usr/bin/env python3
"""Retro Guide in the Windows notification area.

    pythonw crt/tools/tray.py          # no console window
    python  crt/tools/tray.py --debug  # keep one, and print what it does

Sits in the tray and serves the page. It deliberately does *not* open the
Retro Guide on its own, because the whole point of starting with Windows is to
be ready, not to seize a television every time the PC boots. Click the icon and
pick a display.

Pure ctypes and the standard library - no pip install, matching the rest of
crt/. The window it creates is hidden and exists only to receive the tray's
callback messages, which is how a tray icon works: the icon is not a window,
it is a message routed to one.
"""
import ctypes
import json
import os
import subprocess
import sys
import threading
import webbrowser
import winreg
from ctypes import wintypes

HERE = os.path.dirname(os.path.abspath(__file__))
CRT_DIR = os.path.dirname(HERE)
sys.path.insert(0, HERE)
sys.path.insert(0, CRT_DIR)
import kiosk                                          # noqa: E402
import serve                                          # noqa: E402

APP_NAME = "Retro Guide"
RUN_KEY = r"Software\Microsoft\Windows\CurrentVersion\Run"
RUN_VALUE = "RetroGuide"
SETTINGS = os.path.join(os.environ.get("APPDATA", HERE), "RetroGuide", "tray.json")

DEBUG = "--debug" in sys.argv


def log(*a):
    if DEBUG:
        print(*a, flush=True)


# --------------------------------------------------------------- settings

def load():
    try:
        with open(SETTINGS, encoding="utf-8") as f:
            return json.load(f)
    except (OSError, ValueError):
        return {}


def save(cfg):
    os.makedirs(os.path.dirname(SETTINGS), exist_ok=True)
    with open(SETTINGS, "w", encoding="utf-8") as f:
        json.dump(cfg, f, indent=2)


# ------------------------------------------------------------ start with Windows

def pythonw():
    """The windowless interpreter, so nothing flashes a console at logon."""
    exe = sys.executable
    w = os.path.join(os.path.dirname(exe), "pythonw.exe")
    return w if os.path.exists(w) else exe


def autostart_command():
    return '"%s" "%s"' % (pythonw(), os.path.join(HERE, "tray.py"))


def autostart_on():
    try:
        with winreg.OpenKey(winreg.HKEY_CURRENT_USER, RUN_KEY) as k:
            return winreg.QueryValueEx(k, RUN_VALUE)[0] == autostart_command()
    except OSError:
        return False


def set_autostart(on):
    # HKCU, not HKLM: this is one user's television, and it needs no elevation.
    with winreg.OpenKey(winreg.HKEY_CURRENT_USER, RUN_KEY, 0, winreg.KEY_SET_VALUE) as k:
        if on:
            winreg.SetValueEx(k, RUN_VALUE, 0, winreg.REG_SZ, autostart_command())
        else:
            try:
                winreg.DeleteValue(k, RUN_VALUE)
            except OSError:
                pass


# ------------------------------------------------------------------ win32

user32 = ctypes.windll.user32
kernel32 = ctypes.windll.kernel32
shell32 = ctypes.windll.shell32
gdi32 = ctypes.windll.gdi32

LRESULT = ctypes.c_ssize_t
HCURSOR = wintypes.HANDLE
WNDPROC = ctypes.WINFUNCTYPE(LRESULT, wintypes.HWND, wintypes.UINT,
                             wintypes.WPARAM, wintypes.LPARAM)

# Declare every prototype that touches a handle. Without argtypes, ctypes
# guesses, and a 64-bit HINSTANCE gets squeezed into a C int - which raises
# "OverflowError: int too long to convert" only when the module happens to load
# high. That made this work under python.exe and die under pythonw.exe, i.e.
# fine every time it was tested by hand and broken every time Windows started
# it at logon, which is the one path that matters here.
user32.DefWindowProcW.restype = LRESULT
user32.DefWindowProcW.argtypes = [wintypes.HWND, wintypes.UINT,
                                  wintypes.WPARAM, wintypes.LPARAM]
kernel32.GetModuleHandleW.restype = wintypes.HMODULE
kernel32.GetModuleHandleW.argtypes = [wintypes.LPCWSTR]
user32.CreateWindowExW.restype = wintypes.HWND
user32.CreateWindowExW.argtypes = [
    wintypes.DWORD, wintypes.LPCWSTR, wintypes.LPCWSTR, wintypes.DWORD,
    ctypes.c_int, ctypes.c_int, ctypes.c_int, ctypes.c_int,
    wintypes.HWND, wintypes.HMENU, wintypes.HINSTANCE, wintypes.LPVOID]
user32.DestroyWindow.argtypes = [wintypes.HWND]
user32.SetForegroundWindow.argtypes = [wintypes.HWND]
user32.PostMessageW.argtypes = [wintypes.HWND, wintypes.UINT,
                                wintypes.WPARAM, wintypes.LPARAM]
user32.RegisterWindowMessageW.restype = wintypes.UINT
user32.RegisterWindowMessageW.argtypes = [wintypes.LPCWSTR]
user32.CreatePopupMenu.restype = wintypes.HMENU
user32.AppendMenuW.argtypes = [wintypes.HMENU, wintypes.UINT,
                               ctypes.c_size_t, wintypes.LPCWSTR]
user32.TrackPopupMenu.argtypes = [wintypes.HMENU, wintypes.UINT, ctypes.c_int,
                                  ctypes.c_int, ctypes.c_int, wintypes.HWND,
                                  wintypes.LPVOID]
user32.DestroyMenu.argtypes = [wintypes.HMENU]
user32.LoadImageW.restype = wintypes.HANDLE
user32.LoadImageW.argtypes = [wintypes.HINSTANCE, wintypes.LPCWSTR, wintypes.UINT,
                              ctypes.c_int, ctypes.c_int, wintypes.UINT]
user32.LoadIconW.restype = wintypes.HICON
user32.LoadIconW.argtypes = [wintypes.HINSTANCE, wintypes.LPCWSTR]
user32.GetCursorPos.argtypes = [ctypes.POINTER(wintypes.POINT)]
shell32.Shell_NotifyIconW.argtypes = [wintypes.DWORD, ctypes.c_void_p]

WM_DESTROY, WM_COMMAND, WM_CLOSE = 0x0002, 0x0111, 0x0010
WM_LBUTTONUP, WM_RBUTTONUP, WM_LBUTTONDBLCLK = 0x0202, 0x0205, 0x0203
WM_TRAY = 0x0400 + 1                                  # WM_APP + 1
NIM_ADD, NIM_MODIFY, NIM_DELETE = 0, 1, 2
NIF_MESSAGE, NIF_ICON, NIF_TIP = 0x01, 0x02, 0x04
MF_STRING, MF_GRAYED, MF_CHECKED, MF_POPUP, MF_SEPARATOR = 0x0, 0x1, 0x8, 0x10, 0x800
TPM_RIGHTBUTTON = 0x0002
IMAGE_ICON, LR_LOADFROMFILE, LR_DEFAULTSIZE = 1, 0x10, 0x40

# Command ids. Stable and public on purpose: a test can drive this app by
# posting WM_COMMAND straight at the window, with no mouse involved.
ID_QUIT = 1000
ID_AUTOSTART = 1001
ID_CLOSE_KIOSK = 1002
ID_OPEN_HERE = 1003
ID_SET_HOST = 1004
ID_MATCH_TIMING = 1005
ID_DISPLAY_BASE = 2000                                # + index into displays()


class NOTIFYICONDATA(ctypes.Structure):
    _fields_ = [
        ("cbSize", wintypes.DWORD),
        ("hWnd", wintypes.HWND),
        ("uID", wintypes.UINT),
        ("uFlags", wintypes.UINT),
        ("uCallbackMessage", wintypes.UINT),
        ("hIcon", wintypes.HANDLE),
        ("szTip", wintypes.WCHAR * 128),
        ("dwState", wintypes.DWORD),
        ("dwStateMask", wintypes.DWORD),
        ("szInfo", wintypes.WCHAR * 256),
        ("uVersion", wintypes.UINT),
        ("szInfoTitle", wintypes.WCHAR * 64),
        ("dwInfoFlags", wintypes.DWORD),
        ("guidItem", ctypes.c_byte * 16),
        ("hBalloonIcon", wintypes.HANDLE),
    ]


class WNDCLASS(ctypes.Structure):
    _fields_ = [
        ("style", wintypes.UINT),
        ("lpfnWndProc", WNDPROC),
        ("cbClsExtra", ctypes.c_int),
        ("cbWndExtra", ctypes.c_int),
        ("hInstance", wintypes.HINSTANCE),
        ("hIcon", wintypes.HANDLE),
        ("hCursor", HCURSOR),
        ("hbrBackground", wintypes.HANDLE),
        ("lpszMenuName", wintypes.LPCWSTR),
        ("lpszClassName", wintypes.LPCWSTR),
    ]


# -------------------------------------------------------------------- icon

def write_icon(path):
    """A 32x32 television, drawn here so the repo carries no binaries.

    ICO is a BITMAPINFOHEADER with double the height - the image, then a 1bpp
    AND mask underneath - and rows run bottom-up. The mask is left empty
    because the 32-bit image carries its own alpha.
    """
    w = h = 32
    clear, navy, amber = (0, 0, 0, 0), (56, 16, 16, 255), (0, 176, 255, 255)
    px = [[clear] * w for _ in range(h)]

    def rect(x0, y0, x1, y1, c):
        for y in range(y0, y1 + 1):
            for x in range(x0, x1 + 1):
                if 0 <= x < w and 0 <= y < h:
                    px[y][x] = c

    for i in range(7):                                # rabbit ears, diverging
        rect(15 - i, 8 - i, 16 - i, 9 - i, navy)
        rect(16 + i, 8 - i, 17 + i, 9 - i, navy)
    rect(2, 10, 29, 30, navy)                         # cabinet
    rect(5, 13, 26, 27, amber)                        # screen
    rect(7, 17, 24, 18, navy)                         # two scan lines, no more:
    rect(7, 22, 24, 23, navy)                         # more and it reads as text

    rows = b""
    for y in range(h - 1, -1, -1):
        rows += b"".join(bytes(px[y][x]) for x in range(w))
    mask = b"\x00" * (4 * h)

    header = (b"\x28\x00\x00\x00" + (w).to_bytes(4, "little") +
              (h * 2).to_bytes(4, "little") + b"\x01\x00\x20\x00" +
              b"\x00" * 8 + b"\x00" * 16)
    image = header + rows + mask
    ico = (b"\x00\x00\x01\x00\x01\x00" + bytes([w, h, 0, 0]) + b"\x01\x00\x20\x00" +
           len(image).to_bytes(4, "little") + (22).to_bytes(4, "little") + image)
    with open(path, "wb") as f:
        f.write(ico)
    return path


def load_icon():
    path = os.path.join(os.environ.get("TEMP", HERE), "retroguide-tray.ico")
    try:
        write_icon(path)
        icon = user32.LoadImageW(None, path, IMAGE_ICON, 0, 0,
                                 LR_LOADFROMFILE | LR_DEFAULTSIZE)
        if icon:
            return icon
    except OSError as e:
        log("icon:", e)
    # IDI_APPLICATION is MAKEINTRESOURCE(32512): an ordinal pretending to be a
    # string pointer, so it has to be cast rather than passed as one.
    return user32.LoadIconW(None, ctypes.cast(32512, wintypes.LPCWSTR))


# --------------------------------------------------------------- the app

class Tray:
    def __init__(self):
        self.cfg = load()
        self.screens = []
        self.kiosks = []
        self.server = None
        self.hwnd = None
        self.icon = None
        self.taskbar_created = user32.RegisterWindowMessageW("TaskbarCreated")
        self.proc = WNDPROC(self.wndproc)              # keep a reference alive

    # ---------------------------------------------------------- the server

    def start_server(self):
        try:
            self.server = serve.make_server(kiosk.PORT)
        except OSError as e:
            # Something already has 8464 - most likely a serve.py left running,
            # which serves the same page, so this is not worth failing over.
            log("server:", e)
            return
        threading.Thread(target=self.server.serve_forever, daemon=True).start()
        log("serving on", kiosk.PORT)

    # ------------------------------------------------------------- actions

    def open_on(self, index):
        self.screens = kiosk.displays()
        if not (0 <= index < len(self.screens)):
            return
        target = self.screens[index]
        self.cfg["display"] = target["name"]
        save(self.cfg)

        if self.cfg.get("match_timing", True):
            want = preferred_timing(target)
            if want and (target["w"], target["h"]) != want:
                try:
                    kiosk.set_mode(target["name"], *want)
                    target = next(s for s in kiosk.displays()
                                  if s["name"] == target["name"])
                except (kiosk.DisplayError, StopIteration) as e:
                    log("mode:", e)

        proc = kiosk.open_kiosk(target, self.cfg.get("host"))
        if proc:
            self.kiosks.append(proc)
        log("opened on", target["name"], proc and proc.pid)

    def close_kiosk(self):
        for p in self.kiosks:
            if p.poll() is None:
                p.terminate()
        self.kiosks = [p for p in self.kiosks if p.poll() is None]
        # Also catch one opened by kiosk.py directly, so the menu item means
        # what it says rather than "close only the ones I happen to own".
        subprocess.run(["powershell", "-NoProfile", "-Command",
                        "Get-CimInstance Win32_Process -Filter \"Name='chrome.exe'\" | "
                        "Where-Object { $_.CommandLine -like '*retroguide-kiosk*' } | "
                        "ForEach-Object { Stop-Process -Id $_.ProcessId -Force "
                        "-ErrorAction SilentlyContinue }"],
                       capture_output=True, timeout=30,
                       creationflags=0x08000000)
        log("closed")

    def set_host(self):
        """Ask for the ErsatzTV address, offering whatever is on the network."""
        current = self.cfg.get("host", "")
        if not current:
            found = serve.discover()
            current = found[0]["host"] if found else "192.168.1.100:%d" % serve.ETV_PORT
        try:
            import tkinter
            from tkinter import simpledialog
            root = tkinter.Tk()
            root.withdraw()
            answer = simpledialog.askstring(APP_NAME, "ErsatzTV address:",
                                            initialvalue=current, parent=root)
            root.destroy()
        except Exception as e:                         # no tkinter in this build
            log("dialog:", e)
            return
        if answer:
            answer = answer.strip()
            self.cfg["host"] = answer if ":" in answer else "%s:%d" % (answer, serve.ETV_PORT)
            save(self.cfg)

    # ---------------------------------------------------------------- menu

    def menu(self):
        self.screens = kiosk.displays()
        chosen = self.cfg.get("display")

        displays_menu = user32.CreatePopupMenu()
        for i, s in enumerate(self.screens):
            label = "%s  %dx%d%s" % (describe(s), s["w"], s["h"],
                                     "  (primary)" if s["primary"] else "")
            flags = MF_STRING | (MF_CHECKED if s["name"] == chosen else 0)
            user32.AppendMenuW(displays_menu, flags, ID_DISPLAY_BASE + i, label)

        m = user32.CreatePopupMenu()
        user32.AppendMenuW(m, MF_POPUP, displays_menu, "Open Retro Guide on")
        user32.AppendMenuW(m, MF_STRING | (0 if self.kiosks else MF_GRAYED),
                           ID_CLOSE_KIOSK, "Close Retro Guide")
        user32.AppendMenuW(m, MF_SEPARATOR, 0, None)
        user32.AppendMenuW(m, MF_STRING | MF_GRAYED, 0,
                           "ErsatzTV:  " + (self.cfg.get("host") or "not set"))
        user32.AppendMenuW(m, MF_STRING, ID_SET_HOST, "Set ErsatzTV address...")
        user32.AppendMenuW(m, MF_STRING, ID_OPEN_HERE, "Open the page in a browser")
        user32.AppendMenuW(m, MF_SEPARATOR, 0, None)
        user32.AppendMenuW(m, MF_STRING | (MF_CHECKED if self.cfg.get("match_timing", True) else 0),
                           ID_MATCH_TIMING, "Match the display's preferred timing")
        user32.AppendMenuW(m, MF_STRING | (MF_CHECKED if autostart_on() else 0),
                           ID_AUTOSTART, "Start with Windows")
        user32.AppendMenuW(m, MF_SEPARATOR, 0, None)
        user32.AppendMenuW(m, MF_STRING, ID_QUIT, "Exit")

        pt = wintypes.POINT()
        user32.GetCursorPos(ctypes.byref(pt))
        # The window must be foreground or the menu never dismisses, and the
        # WM_NULL afterwards is the documented cure for it sticking around.
        user32.SetForegroundWindow(self.hwnd)
        user32.TrackPopupMenu(m, TPM_RIGHTBUTTON, pt.x, pt.y, 0, self.hwnd, None)
        user32.PostMessageW(self.hwnd, 0, 0, 0)
        user32.DestroyMenu(m)

    # ------------------------------------------------------------- plumbing

    def command(self, cid):
        if cid == ID_QUIT:
            user32.DestroyWindow(self.hwnd)
        elif cid == ID_CLOSE_KIOSK:
            self.close_kiosk()
        elif cid == ID_AUTOSTART:
            set_autostart(not autostart_on())
        elif cid == ID_MATCH_TIMING:
            self.cfg["match_timing"] = not self.cfg.get("match_timing", True)
            save(self.cfg)
        elif cid == ID_SET_HOST:
            self.set_host()
        elif cid == ID_OPEN_HERE:
            webbrowser.open(kiosk.page_url(self.cfg.get("host"), letterbox=True))
        elif cid >= ID_DISPLAY_BASE:
            self.open_on(cid - ID_DISPLAY_BASE)

    def wndproc(self, hwnd, msg, wparam, lparam):
        if msg == WM_TRAY:
            if lparam in (WM_RBUTTONUP, WM_LBUTTONUP):
                self.menu()
            elif lparam == WM_LBUTTONDBLCLK:
                self.open_default()
            return 0
        if msg == WM_COMMAND:
            self.command(wparam & 0xFFFF)
            return 0
        if msg == self.taskbar_created:
            self.add_icon()                            # Explorer restarted
            return 0
        if msg in (WM_DESTROY, WM_CLOSE):
            self.remove_icon()
            user32.PostQuitMessage(0)
            return 0
        return user32.DefWindowProcW(hwnd, msg, wparam, lparam)

    def open_default(self):
        chosen = self.cfg.get("display")
        self.screens = kiosk.displays()
        for i, s in enumerate(self.screens):
            if s["name"] == chosen:
                return self.open_on(i)
        self.menu()

    # ----------------------------------------------------------- the icon

    def nid(self):
        n = NOTIFYICONDATA()
        n.cbSize = ctypes.sizeof(NOTIFYICONDATA)
        n.hWnd = self.hwnd
        n.uID = 1
        n.uFlags = NIF_MESSAGE | NIF_ICON | NIF_TIP
        n.uCallbackMessage = WM_TRAY
        n.hIcon = self.icon
        n.szTip = APP_NAME
        return n

    def add_icon(self):
        shell32.Shell_NotifyIconW(NIM_ADD, ctypes.byref(self.nid()))

    def remove_icon(self):
        shell32.Shell_NotifyIconW(NIM_DELETE, ctypes.byref(self.nid()))

    # ----------------------------------------------------------------- run

    def run(self):
        cls = WNDCLASS()
        cls.lpfnWndProc = self.proc
        cls.hInstance = kernel32.GetModuleHandleW(None)
        cls.lpszClassName = "RetroGuideTray"
        if not user32.RegisterClassW(ctypes.byref(cls)):
            sys.exit("could not register the window class")
        self.hwnd = user32.CreateWindowExW(0, "RetroGuideTray", APP_NAME,
                                           0, 0, 0, 0, 0, None, None,
                                           cls.hInstance, None)
        if not self.hwnd:
            sys.exit("could not create the message window")
        self.icon = load_icon()
        self.start_server()
        self.add_icon()
        log("tray running, hwnd", self.hwnd)

        msg = wintypes.MSG()
        while user32.GetMessageW(ctypes.byref(msg), None, 0, 0) > 0:
            user32.TranslateMessage(ctypes.byref(msg))
            user32.DispatchMessageW(ctypes.byref(msg))
        return 0


# ------------------------------------------------------------------ helpers

def describe(screen):
    """What to call a display in the menu: its monitor's name, then the device."""
    short = screen["name"].split("\\")[-1]             # \\.\DISPLAY3 -> DISPLAY3
    edid = kiosk.monitor_edid(screen["name"])
    return "%s - %s" % (edid["name"], short) if edid else short


def preferred_timing(screen):
    """The mode this display's own EDID asks for, or None if it does not say."""
    edid = kiosk.monitor_edid(screen["name"])
    pref = edid and edid.get("preferred")
    return (pref[0], pref[1]) if pref else None


if __name__ == "__main__":
    sys.exit(Tray().run())
