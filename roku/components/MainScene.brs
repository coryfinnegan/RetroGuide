' Retro Guide - a 1990s TV Guide channel for ErsatzTV, on Roku.
'
' Feature for feature with the Google TV build: LAN discovery, saved server,
' live playback, channel surfing with a banner, the guide with its picture
' window, an info panel and a single highlighted cell on a grid of real
' programme times, themes, wrap around scrolling, resume of the last channel,
' a settle delay so surfing does not stampede the server, and a watchdog for
' streams that stop without saying so.
'
' The one thing missing is tuning by typing a channel number, because no Roku
' remote has a keypad to type it on.

sub init()
    ' ErsatzTV starts an ffmpeg per request, so surfing with one request per
    ' keypress leaves a queue of them starting and tearing down at once.
    ' Waiting for the viewer to settle means one request per channel actually
    ' landed on - which is also how a real cable box behaves: the banner moves
    ' at once, the picture follows.
    m.TUNE_SETTLE = 0.4
    m.RETRY_DELAY = 1.2
    m.MAX_RETRIES = 2
    m.BANNER_SECS = 4
    ' Roku terminates the channel on exit (no supports_suspend in the manifest),
    ' so init runs fresh on every launch. Being left running is what goes
    ' stale: the guide covers a window around the moment it was read. Only the
    ' programme data is re-read - the channel list is deliberately left alone
    ' until a restart, so it cannot change underneath someone watching.
    m.GUIDE_REFRESH_SECS = 1800
    ' The guide is read only once the picture has been playing this long, so
    ' its download and parse never compete with the decoder starting up.
    m.GUIDE_AFTER_PLAY_SECS = 6
    ' A stream that reports playing while its position stands still for this
    ' long is dead; see onWatchdog.
    m.STALL_SECS = 12

    ' Grid geometry, in 1920x1080 pixels.
    m.GRID_X = 48
    m.GRID_W = 1824
    m.HEAD_Y = 408
    m.HEAD_H = 58
    m.ROWS_Y = 470
    m.ROW_H = 104
    m.VISIBLE_ROWS = 5
    m.CHAN_W = 328
    m.LINE_X = m.GRID_X + m.CHAN_W
    m.LINE_W = m.GRID_W - m.CHAN_W
    m.FOOT_Y = 998
    m.FOOT_H = 50
    m.BEV = 4
    ' The ninety minutes the grid covers, paged by half hours up to MAX_PAGES.
    m.WINDOW_SECS = 5400
    m.MAX_PAGES = 16
    ' A block narrower than this cannot carry a readable title.
    m.MIN_LABEL_PX = 120

    m.video = m.top.findNode("video")
    m.banner = m.top.findNode("banner")
    m.bannerPlate = m.top.findNode("bannerPlate")
    m.bannerNumber = m.top.findNode("bannerNumber")
    m.bannerName = m.top.findNode("bannerName")
    m.bannerNow = m.top.findNode("bannerNow")
    m.guide = m.top.findNode("guide")
    m.guideBg = m.top.findNode("guideBg")
    m.panelPlate = m.top.findNode("panelPlate")
    m.logoArt = m.top.findNode("logoArt")
    m.logoA = m.top.findNode("logoA")
    m.logoB = m.top.findNode("logoB")
    m.clockLabel = m.top.findNode("clock")
    m.panelRule = m.top.findNode("panelRule")
    m.infoTitle = m.top.findNode("infoTitle")
    m.infoTime = m.top.findNode("infoTime")
    m.infoChannel = m.top.findNode("infoChannel")
    m.infoNext = m.top.findNode("infoNext")
    m.windowFrame = m.top.findNode("windowFrame")
    m.grid = m.top.findNode("grid")
    m.footer = m.top.findNode("footer")
    m.deco = m.top.findNode("deco")
    m.status = m.top.findNode("status")
    m.setup = m.top.findNode("setup")
    m.setupStatus = m.top.findNode("setupStatus")
    m.setupList = m.top.findNode("setupList")
    m.settings = m.top.findNode("settings")
    m.settingsBg = m.top.findNode("settingsBg")
    m.settingsTitle = m.top.findNode("settingsTitle")
    m.settingsList = m.top.findNode("settingsList")
    m.settingsHint = m.top.findNode("settingsHint")

    m.channels = []
    m.guideData = {}
    m.current = 0
    m.guideOpen = false
    m.setupOpen = false
    m.autoRestarts = 0
    m.awaitingSetup = false
    m.retries = 0
    m.selRow = 0        ' the guide cursor: a row...
    m.selTime = 0       ' ...and a moment on it; the block under it is lit
    m.firstRow = 0      ' topmost visible row
    m.setupCursor = 0
    m.setupHosts = []
    m.setupItems = []
    m.page = 0          ' half hours the grid is paged forward
    m.paintedSlot = 0
    m.settingsOpen = false
    m.settingsCursor = 0
    m.guideRequested = false
    m.lastPos = -1
    m.playClock = CreateObject("roTimespan")
    m.advanceClock = CreateObject("roTimespan")

    m.themeKey = readRegistry("theme")
    if m.themeKey = "" then m.themeKey = "classic"
    applyTheme()

    m.video.observeField("state", "onVideoState")

    m.tuneTimer = newTimer(m.TUNE_SETTLE, false, "onTuneTimer")
    m.retryTimer = newTimer(m.RETRY_DELAY, false, "onRetryTimer")
    m.bannerTimer = newTimer(m.BANNER_SECS, false, "onBannerTimer")
    m.clockTimer = newTimer(30, true, "onClockTimer")
    m.clockTimer.control = "start"
    m.guideTimer = newTimer(m.GUIDE_REFRESH_SECS, true, "onGuideTimer")
    m.firstGuideTimer = newTimer(m.GUIDE_AFTER_PLAY_SECS, false, "onFirstGuideTimer")
    m.watchdog = newTimer(2, true, "onWatchdog")
    m.holdTimer = newTimer(0.8, false, "onHoldTimer")
    m.okHeld = false
    m.okArmed = false

    m.top.setFocus(true)

    host = readRegistry("host")
    if host = "" then
        openSetup()
    else
        m.host = host
        m.status.text = "TUNING…"
        loadLineup()
    end if
end sub

function newTimer(secs as Float, repeats as Boolean, handler as String) as Object
    t = CreateObject("roSGNode", "Timer")
    t.duration = secs
    t.repeat = repeats
    t.observeField("fire", handler)
    m.top.appendChild(t)
    return t
end function

' ------------------------------------------------------------------ registry

function readRegistry(key as String) as String
    sec = CreateObject("roRegistrySection", "RetroGuide")
    if sec.Exists(key) then return sec.Read(key)
    return ""
end function

sub writeRegistry(key as String, value as String)
    sec = CreateObject("roRegistrySection", "RetroGuide")
    sec.Write(key, value)
    sec.Flush()
end sub

' ------------------------------------------------------------------ loading

sub loadLineup()
    m.loader = CreateObject("roSGNode", "LoaderTask")
    m.loader.host = m.host
    m.loader.mode = "channels"
    m.loader.observeField("result", "onLineup")
    m.loader.control = "RUN"
end sub

sub loadGuide()
    if m.guideLoader <> invalid and m.guideLoader.state = "run" then return
    m.guideLoader = CreateObject("roSGNode", "LoaderTask")
    m.guideLoader.host = m.host
    m.guideLoader.mode = "guide"
    m.guideLoader.observeField("result", "onGuide")
    m.guideLoader.control = "RUN"
end sub

sub onFirstGuideTimer()
    ? "[rg] reading the guide now"
    loadGuide()
end sub

sub onGuideTimer()
    loadGuide()
end sub

sub onGuide(event as Object)
    res = event.getData()
    if res.guide = invalid or res.guide.Count() = 0 then
        ? "[rg] guide empty or unreachable"
        return
    end if
    m.guideData = res.guide
    ? "[rg] guide ready: "; res.guide.Count(); " channels of listings in "; res.ms; " ms"
    if m.guideOpen then
        paintGrid()
        paintInfo()
    else if m.banner.visible and m.channels.Count() > 0 then
        showBanner(m.channels[m.current])
    end if
end sub

sub onLineup(event as Object)
    res = event.getData()
    if res.error <> "" or res.channels.Count() = 0 then
        ' A server that has moved is the common case, so offer the fix rather
        ' than just reporting the failure.
        m.status.visible = true
        m.status.text = "CANNOT REACH " + m.host + chr(10) + res.error + chr(10) + "Press OK to change the server address."
        m.awaitingSetup = true
        return
    end if

    m.channels = res.channels
    ? "[rg] channels="; m.channels.Count(); " in "; res.ms; " ms"
    m.status.visible = false
    m.awaitingSetup = false
    m.guideTimer.control = "start"
    m.watchdog.control = "start"
    reportLineupChange()

    ' Resume whatever was on last time.
    last = readRegistry("last_channel")
    idx = 0
    if last <> "" then
        for i = 0 to m.channels.Count() - 1
            if m.channels[i].number = last then
                idx = i
                exit for
            end if
        end for
    end if
    tune(idx)
    ' Opened while the lineup was still loading: show it now there is one.
    if m.guideOpen then openGuide()
end sub

' A fingerprint of the lineup, so a change is trivial to spot without
' comparing lists. Numbers and names both count, since a rename matters as
' much as an addition. Checked once per launch: the lineup is only re-read
' when the app starts, because swapping channels underneath someone who is
' watching is worse than showing a stale list until the next restart.
function lineupSignature() as String
    parts = ""
    for each ch in m.channels
        parts = parts + ch.number + ":" + ch.name + "|"
    end for
    bytes = CreateObject("roByteArray")
    bytes.FromAsciiString(parts)
    digest = CreateObject("roEVPDigest")
    digest.Setup("md5")
    return digest.Process(bytes)
end function

sub reportLineupChange()
    signature = lineupSignature()
    previous = readRegistry("lineup_sig")
    count = StrI(m.channels.Count()).Trim()
    if previous = "" then
        ? "[rg] lineup "; signature; " ("; count; " channels, first run)"
    else if previous <> signature then
        ? "[rg] lineup CHANGED "; previous; " -> "; signature; " ("; count; " channels)"
        m.lineupChanged = true
    else
        ? "[rg] lineup unchanged ("; count; " channels)"
    end if
    writeRegistry("lineup_sig", signature)
end sub

' ------------------------------------------------------------------ tuning

sub tune(index as Integer, announce = true as Boolean)
    if m.channels.Count() = 0 then return
    n = m.channels.Count()
    m.current = ((index mod n) + n) mod n
    ch = m.channels[m.current]

    writeRegistry("last_channel", ch.number)
    if announce then showBanner(ch)
    if m.guideOpen then paintGrid()

    ' The banner follows the remote immediately; the stream waits until the
    ' viewer stops surfing. Also cancels a pending retry for the channel we are
    ' leaving.
    m.retries = 0
    m.retryTimer.control = "stop"
    m.tuneTimer.control = "stop"
    m.tuneTimer.control = "start"
end sub

sub onTuneTimer()
    startStream()
end sub

sub onRetryTimer()
    startStream()
end sub

sub startStream()
    if m.channels.Count() = 0 then return
    ' Cancel a settle that has not fired yet - otherwise closing the guide
    ' restarts here and the pending timer restarts again a moment later.
    m.tuneTimer.control = "stop"
    ch = m.channels[m.current]

    ' Drop the previous stream before asking for the next one, so the server
    ' can retire its ffmpeg rather than holding both open.
    m.video.control = "stop"

    content = CreateObject("roSGNode", "ContentNode")
    content.url = ch.url
    content.streamformat = "hls"
    content.title = ch.name
    ? "[rg] tune #"; ch.number; " "; ch.name; " -> "; ch.url
    m.tuneClock = CreateObject("roTimespan")
    m.lastPos = -1
    m.advanceClock.Mark()
    m.video.content = content
    m.video.control = "play"
end sub

sub onVideoState(event as Object)
    state = event.getData()
    if state = "playing" and m.tuneClock <> invalid then
        ? "[rg] PLAYING after "; m.tuneClock.TotalMilliseconds(); " ms"
        m.tuneClock = invalid
        m.playClock.Mark()
        ' The first picture of the session: now the guide may be read.
        if not m.guideRequested then
            m.guideRequested = true
            m.firstGuideTimer.control = "start"
        end if
    end if
    ? "[rg] video state="; state; " errCode="; m.video.errorCode; " errMsg="; m.video.errorMsg

    ' A live channel has no end. Reaching one means the server retired the
    ' session while we were watching, and without this the picture simply
    ' stops and stays stopped. The cap is cleared by the watchdog only after a
    ' stretch of real playback - clearing it on every "playing" meant it could
    ' never actually stop a restart loop.
    if state = "finished" and m.autoRestarts < 3 then
        m.autoRestarts = m.autoRestarts + 1
        ? "[rg] stream ended on its own - reopening ("; m.autoRestarts; ")"
        startStream()
        return
    end if

    if state <> "error" then return

    ch = invalid
    if m.channels.Count() > 0 then ch = m.channels[m.current]
    ' These are nearly always transient - the server was still bringing the
    ' stream up - so ask again before giving the viewer an error to read.
    if m.retries < m.MAX_RETRIES then
        m.retries = m.retries + 1
        showBannerText(chNumber(ch), chName(ch), "TUNING…")
        m.retryTimer.control = "stop"
        m.retryTimer.control = "start"
    else
        msg = m.video.errorMsg
        if msg = invalid or msg = "" then msg = "no signal"
        showBannerText(chNumber(ch), chName(ch), "NO SIGNAL — " + msg)
    end if
end sub

' A dead stream does not always announce itself. Roku reports state=play with
' a frozen position when a stream dies under it - seen stuck at exactly
' pos=1187115 for minutes after ErsatzTV restarted - so neither the error retry
' nor the finished handler fires. Watch the clock instead, as the CRT build
' does, and reopen once it has stood still too long.
sub onWatchdog()
    state = m.video.state
    if state <> "playing" and state <> "buffering" then
        m.advanceClock.Mark()
        return
    end if
    vpos = m.video.position
    if vpos <> m.lastPos then
        m.lastPos = vpos
        m.advanceClock.Mark()
        if m.autoRestarts > 0 and m.playClock.TotalSeconds() > 30 then m.autoRestarts = 0
        return
    end if
    if m.advanceClock.TotalSeconds() < m.STALL_SECS then return
    ? "[rg] STALLED at pos="; vpos; " state="; state; " - reopening"
    m.advanceClock.Mark()
    startStream()
end sub

function chNumber(ch as Dynamic) as String
    if ch = invalid then return ""
    return ch.number
end function

function chName(ch as Dynamic) as String
    if ch = invalid then return "CHANNEL"
    return ch.name
end function

' ------------------------------------------------------------------ banner

sub showBanner(ch as Object)
    line = ""
    p = programmeAt(ch, nowSeconds())
    if p <> invalid then line = shortTime(p.start) + " – " + shortTime(p.stop) + "   " + p.title
    ' Say so once, on the first banner after the lineup changed.
    if m.lineupChanged = true then
        line = "CHANNEL LIST UPDATED - " + StrI(m.channels.Count()).Trim() + " CHANNELS"
        m.lineupChanged = false
    end if
    showBannerText(ch.number, ch.name, line)
end sub

sub showBannerText(number as String, name as String, line as String)
    m.bannerNumber.text = number
    m.bannerName.text = name
    m.bannerNow.text = line
    m.banner.visible = true
    m.bannerTimer.control = "stop"
    m.bannerTimer.control = "start"
end sub

sub onBannerTimer()
    m.banner.visible = false
end sub

function programmeAt(ch as Object, t as Integer) as Dynamic
    if ch = invalid or ch.id = "" then return invalid
    list = m.guideData[ch.id]
    if list = invalid then return invalid
    for each p in list
        if t >= p.start and t < p.stop then return p
    end for
    return invalid
end function

' ------------------------------------------------------------------ themes

' Guide looks, chosen in SETTINGS (the * button) and saved in the registry.
' Colours are 0xRRGGBBAA, the same palettes as GuideTheme.kt on Android. Art
' lives in images/themes and is sourced, not drawn - see THEMES.md.
function themeKeys() as Object
    return ["classic", "retro", "halloween"]
end function

function themeLabel(key as String) as String
    if key = "retro" then return "RETRO 90S"
    if key = "halloween" then return "HALLOWEEN"
    return "CLASSIC"
end function

function themeFor(key as String) as Object
    ' Indigo and amber, the look this app started with.
    t = {
        key: "classic"
        bg: "0x0C0C3AFF"
        panel: "0x24248CFF", rule: "0xFFC020FF"
        titleInk: "0xFFFFFFFF", infoInk: "0xDADAFFFF", accentInk: "0x66E0FFFF", clock: "0xFFC020FF"
        logoA: "RETRO", logoAInk: "0xFFC020FF", logoB: "GUIDE", logoBInk: "0xFFFFFFFF", logoArt: ""
        frame: "0xFFC020FF"
        head: "0x16166AFF", headInk: "0xFFC020FF"
        chan: "0x24248CFF", numInk: "0xFFFFFFFF", chanInk: "0xFFC020FF", tunedInk: "0x66E0FFFF"
        cell: "0x2C2CA0FF", cellInk: "0xFFFFFFFF"
        sel: "0xFFC020FF", selInk: "0x140A00FF"
        foot: "0x16166AFF", footInk: "0xDADAFFFF"
        font: "", deco: []
    }
    if key = "retro" then
        ' The Prevue Channel, 1993-99, colours sampled from a broadcast
        ' capture: navy plates with near-white bevels, white titles, the
        ' number in white over a yellow call sign, yellow times on a lighter
        ' blue time bar. WeatherSTAR typeface on the logo and clock.
        t.key = "retro"
        t.bg = "0x000000FF"
        t.panel = "0x323278FF" : t.rule = "0xF2DC4AFF"
        t.titleInk = "0xFFFFFFFF" : t.infoInk = "0xDCDCF5FF" : t.accentInk = "0xF2DC4AFF" : t.clock = "0xFFFFFFFF"
        t.logoAInk = "0xFFFFFFFF" : t.logoBInk = "0xF2DC4AFF" : t.logoArt = "pkg:/images/themes/tv.png"
        t.frame = "0xDCDCF5FF"
        t.head = "0x5A6EC8FF" : t.headInk = "0xF2DC4AFF"
        t.chan = "0x323278FF" : t.numInk = "0xFFFFFFFF" : t.chanInk = "0xF2DC4AFF" : t.tunedInk = "0x7FE0FFFF"
        t.cell = "0x323278FF" : t.cellInk = "0xFFFFFFFF"
        t.sel = "0xF2DC4AFF" : t.selInk = "0x14143CFF"
        t.foot = "0x323278FF" : t.footInk = "0xFFFFFFFF"
        t.font = "pkg:/fonts/star4000.ttf"
    else if key = "halloween" then
        ' Purple plates, a slime-green cursor and pumpkin-orange lettering,
        ' under a jack-o'-lantern logo, a moon with bats, and pumpkins on the
        ' edge of the hint bar.
        t.key = "halloween"
        t.bg = "0x0E0418FF"
        t.panel = "0x3A1258FF" : t.rule = "0x7CFF3AFF"
        t.titleInk = "0xFFFFFFFF" : t.infoInk = "0xE6D8FFFF" : t.accentInk = "0xFF9A2EFF" : t.clock = "0x9CFF57FF"
        t.logoAInk = "0xFF8A1CFF" : t.logoB = "guide" : t.logoBInk = "0x9CFF57FF"
        t.logoArt = "pkg:/images/themes/pumpkin.png"
        t.frame = "0x7CFF3AFF"
        t.head = "0x2A0C42FF" : t.headInk = "0xFF9A2EFF"
        t.chan = "0x2A0C42FF" : t.numInk = "0xFF9A2EFF" : t.chanInk = "0x9CFF57FF" : t.tunedInk = "0xFFFFFFFF"
        t.cell = "0x54207AFF" : t.cellInk = "0xFFFFFFFF"
        t.sel = "0x7CFF3AFF" : t.selInk = "0x12061CFF"
        t.foot = "0x2A0C42FF" : t.footInk = "0xE6D8FFFF"
        t.deco = [
            ' Moon and bats in the empty strip between logo and clock.
            { uri: "pkg:/images/themes/moon.png", x: 760, y: 52, w: 68, h: 68 }
            { uri: "pkg:/images/themes/bat.png", x: 690, y: 80, w: 48, h: 48 }
            { uri: "pkg:/images/themes/bat.png", x: 846, y: 60, w: 36, h: 36 }
            ' Pumpkins and a web on the ends of the hint bar.
            { uri: "pkg:/images/themes/pumpkin.png", x: 22, y: 990, w: 62, h: 62 }
            { uri: "pkg:/images/themes/pumpkin.png", x: 82, y: 1000, w: 50, h: 50 }
            { uri: "pkg:/images/themes/web.png", x: 1812, y: 986, w: 94, h: 94 }
        ]
    end if
    return t
end function

' The logo and clock face: the theme's typeface, or the system bold.
function brandFont(size as Integer) as Object
    f = CreateObject("roSGNode", "Font")
    if m.theme.font = "" then f.uri = "font:BoldSystemFontFile" else f.uri = m.theme.font
    f.size = size
    return f
end function

' The grid face: a bold sans, as cable boxes used.
function gridFont(size as Integer, bold = true as Boolean) as Object
    f = CreateObject("roSGNode", "Font")
    if bold then f.uri = "font:BoldSystemFontFile" else f.uri = "font:SystemFontFile"
    f.size = size
    return f
end function

sub applyTheme()
    m.theme = themeFor(m.themeKey)
    t = m.theme
    m.wordWidths = {}
    m.fonts = {
        cell: gridFont(32), head: gridFont(30), num: gridFont(38), name: gridFont(24)
        foot: gridFont(26)
    }

    m.guideBg.color = t.bg
    clearNode(m.panelPlate)
    plate(m.panelPlate, 48, 40, 1180, 349, t.panel)
    m.windowFrame.color = t.frame
    m.panelRule.color = t.rule

    m.logoArt.visible = t.logoArt <> ""
    if t.logoArt <> "" then m.logoArt.uri = t.logoArt
    logoX = 72
    if t.logoArt <> "" then logoX = 156
    m.logoA.text = t.logoA
    m.logoA.color = t.logoAInk
    m.logoA.font = brandFont(56)
    m.logoA.translation = [logoX, 58]
    m.logoB.text = t.logoB
    m.logoB.color = t.logoBInk
    m.logoB.font = brandFont(56)
    m.logoB.translation = [logoX + m.logoA.boundingRect().width + 16, 58]
    m.clockLabel.color = t.clock
    m.clockLabel.font = brandFont(56)

    m.infoTitle.color = t.titleInk
    m.infoTitle.font = gridFont(54)
    m.infoTime.color = t.accentInk
    m.infoTime.font = gridFont(36, false)
    m.infoChannel.color = t.infoInk
    m.infoChannel.font = gridFont(36, false)
    m.infoNext.color = t.infoInk
    m.infoNext.font = gridFont(32, false)

    clearNode(m.deco)
    for each d in t.deco
        p = CreateObject("roSGNode", "Poster")
        p.uri = d.uri
        p.translation = [d.x, d.y]
        p.width = d.w
        p.height = d.h
        m.deco.appendChild(p)
    end for

    clearNode(m.bannerPlate)
    plate(m.bannerPlate, 0, 0, 1500, 170, t.panel)
    m.bannerNumber.color = t.numInk
    m.bannerNumber.font = gridFont(64)
    m.bannerName.color = t.titleInk
    m.bannerName.font = gridFont(52)
    m.bannerNow.color = t.accentInk
    m.bannerNow.font = gridFont(36)

    m.settingsBg.color = t.bg
    m.settingsTitle.color = t.logoAInk
    m.settingsTitle.font = brandFont(64)
    m.settingsHint.color = t.infoInk
    m.settingsHint.font = gridFont(28)
    m.status.color = t.clock

    paintFooter()
    if m.guideOpen then
        paintGrid()
        paintInfo()
    end if
end sub

sub clearNode(node as Object)
    while node.getChildCount() > 0
        node.removeChildIndex(0)
    end while
end sub

' ---------------------------------------------------------------- plates

' The raised plate every surface is made of, from the Prevue Channel grid of
' 1993-99: one flat fill, a bright edge along the top and left, a dark one
' along the bottom and right. No gradients, no rounded corners.
sub plate(parent as Object, x as Integer, y as Integer, w as Integer, h as Integer, fill as String)
    b = m.BEV
    addRect(parent, x, y, w, h, fill)
    lo = shade(fill, 0, 0.6)
    hi = shade(fill, 255, 0.78)
    addRect(parent, x, y + h - b, w, b, lo)
    addRect(parent, x + w - b, y, b, h, lo)
    addRect(parent, x, y, w - b, b, hi)
    addRect(parent, x, y, b, h - b, hi)
end sub

sub addRect(parent as Object, x as Integer, y as Integer, w as Integer, h as Integer, color as String)
    r = CreateObject("roSGNode", "Rectangle")
    r.translation = [x, y]
    r.width = w
    r.height = h
    r.color = color
    parent.appendChild(r)
end sub

' "0xRRGGBBAA" moved f of the way toward grey level [to].
function shade(c as String, level as Integer, f as Float) as String
    if m.shades = invalid then m.shades = {}
    k = c + StrI(level) + Str(f)
    if m.shades[k] <> invalid then return m.shades[k]
    r = hexByte(Mid(c, 3, 2))
    g = hexByte(Mid(c, 5, 2))
    b = hexByte(Mid(c, 7, 2))
    out = "0x" + hex2(Int(r + (level - r) * f)) + hex2(Int(g + (level - g) * f)) + hex2(Int(b + (level - b) * f)) + "FF"
    m.shades[k] = out
    return out
end function

function hexByte(s as String) as Integer
    return Val(s, 16)
end function

function hex2(v as Integer) as String
    digits = "0123456789ABCDEF"
    return Mid(digits, Int(v / 16) + 1, 1) + Mid(digits, (v mod 16) + 1, 1)
end function

function addLabel(parent as Object, x as Integer, y as Integer, w as Integer, h as Integer, text as String, font as Object, color as String, align = "left" as String) as Object
    l = CreateObject("roSGNode", "Label")
    l.translation = [x, y]
    l.width = w
    if h > 0 then
        l.height = h
        l.vertAlign = "center"
    end if
    l.horizAlign = align
    l.font = font
    l.color = color
    l.text = text
    parent.appendChild(l)
    return l
end function

' ------------------------------------------------------------------ the guide

sub openGuide()
    ? "[rg] guide OPEN"
    m.guideOpen = true
    m.page = 0
    m.selRow = m.current
    m.selTime = nowSeconds()
    m.firstRow = m.selRow - 1
    keepCursorOnScreen()
    updateClock()

    ' Move the video into the picture window. Roku only takes a new size on a
    ' stopped Video node, so this stops, resizes and restarts.
    m.video.translation = [1258, 46]
    m.video.width = 608
    m.video.height = 337
    startStream()
    m.guide.visible = true
    paintGrid()
    paintInfo()
end sub

sub closeGuide()
    ? "[rg] guide CLOSE"
    m.guideOpen = false
    m.guide.visible = false
    m.video.translation = [0, 0]
    m.video.width = 1920
    m.video.height = 1080
    startStream()
end sub

sub keepCursorOnScreen()
    n = m.VISIBLE_ROWS
    if m.selRow < m.firstRow then m.firstRow = m.selRow
    if m.selRow >= m.firstRow + n then m.firstRow = m.selRow - n + 1
    maxTop = m.channels.Count() - n
    if maxTop < 0 then maxTop = 0
    if m.firstRow > maxTop then m.firstRow = maxTop
    if m.firstRow < 0 then m.firstRow = 0
end sub

' Up and down: another channel at the same moment. Wraps at both ends.
sub moveRow(delta as Integer)
    n = m.channels.Count()
    if n = 0 then return
    m.selRow = ((m.selRow + delta) mod n + n) mod n
    keepCursorOnScreen()
    paintGrid()
    paintInfo()
end sub

' Left and right: the previous or next programme on this row, paging the grid
' half an hour when the cursor runs off either end.
sub moveTime(delta as Integer)
    w = guideWindow()
    bs = blocksFor(m.selRow, w)
    cur = blockAt(m.selRow, m.selTime, w)
    i = -1
    for k = 0 to bs.Count() - 1
        if cur <> invalid and bs[k].start = cur.start then i = k
    end for
    if delta < 0 then
        if i > 0 then
            m.selTime = bs[i - 1].start
        else if m.page > 0 then
            m.page = m.page - 1
            m.selTime = w.a - 1
        else
            return
        end if
    else
        if i >= 0 and i < bs.Count() - 1 then
            m.selTime = bs[i + 1].start
        else if m.page < m.MAX_PAGES then
            m.page = m.page + 1
            nw = guideWindow()
            m.selTime = nw.a
            if cur <> invalid and cur.stop > nw.a then m.selTime = cur.stop
        else
            return
        end if
    end if
    ' Paging back lands on the last block; keep the cursor time inside it.
    b = blockAt(m.selRow, m.selTime, guideWindow())
    if b <> invalid and (m.selTime < b.start or m.selTime >= b.stop) then m.selTime = b.start
    paintGrid()
    paintInfo()
end sub

' Fill the info panel from the highlighted programme.
sub paintInfo()
    if m.channels.Count() = 0 then return
    ch = m.channels[m.selRow]
    blk = blockAt(m.selRow, m.selTime, guideWindow())
    at = m.selTime
    if blk <> invalid and blk.start > at then at = blk.start
    p = programmeAt(ch, at)
    if p <> invalid then
        m.infoTitle.text = p.title
    else if blk <> invalid then
        m.infoTitle.text = blk.title
    else
        m.infoTitle.text = ch.name
    end if
    m.infoChannel.text = ch.number + "   " + UCase(ch.name)
    m.infoTime.text = ""
    m.infoNext.text = ""
    if p = invalid then return
    now = nowSeconds()
    span = shortTime(p.start) + " – " + shortTime(p.stop)
    if now >= p.start and now < p.stop then
        m.infoTime.text = span + "   ON NOW"
    else
        m.infoTime.text = dayName(p.start) + "  " + span
    end if
    list = m.guideData[ch.id]
    for each q in list
        if q.start >= p.stop then
            m.infoNext.text = "NEXT  " + shortTime(q.start) + "   " + q.title
            exit for
        end if
    end for
end sub

' The window the grid shows: ninety minutes from the current half hour, paged
' forward by whole half hours. The labels sit on half hours because that is how
' a person reads a clock; the programmes do not, and are drawn where they
' actually fall.
function guideWindow() as Object
    a = currentSlot() + m.page * 1800
    return { a: a, b: a + m.WINDOW_SECS, marks: [a, a + 1800, a + 3600] }
end function

function timeX(t as Integer, w as Object) as Integer
    return m.LINE_X + Int((t - w.a) * m.LINE_W / (w.b - w.a))
end function

' The blocks drawn for a row. A channel with nothing in the listings gets one
' block naming the channel, so the cursor still has somewhere to sit.
function blocksFor(row as Integer, w as Object) as Object
    ch = m.channels[row]
    list = invalid
    if ch.id <> "" then list = m.guideData[ch.id]
    out = []
    if list <> invalid then out = blocks(list, w.a, w.b)
    if out.Count() = 0 then out.Push({ start: w.a, stop: w.b, title: ch.name, before: false, after: false })
    return out
end function

' The block under t on a row, or the nearest one.
function blockAt(row as Integer, t as Integer, w as Object) as Dynamic
    if m.channels.Count() = 0 then return invalid
    best = invalid
    bestD = 999999999
    for each b in blocksFor(row, w)
        if t >= b.start and t < b.stop then return b
        d = Abs(b.start - t)
        if Abs(b.stop - t) < d then d = Abs(b.stop - t)
        if d < bestD then
            bestD = d
            best = b
        end if
    end for
    return best
end function

' Rebuild the time bar and the visible rows.
sub paintGrid()
    if m.channels.Count() = 0 then return
    clearNode(m.grid)
    t = m.theme
    f = m.fonts
    w = guideWindow()
    m.paintedSlot = currentSlot()

    ' Time bar: the day over the channel column, half hours over the blocks.
    plate(m.grid, m.GRID_X, m.HEAD_Y, m.CHAN_W, m.HEAD_H, t.head)
    plate(m.grid, m.LINE_X, m.HEAD_Y, m.LINE_W, m.HEAD_H, t.head)
    addLabel(m.grid, m.GRID_X + 16, m.HEAD_Y, m.CHAN_W - 24, m.HEAD_H, dayName(w.a) + " " + monthDay(w.a), f.head, t.headInk)
    for i = 0 to 2
        x = timeX(w.marks[i], w)
        if i > 0 then
            addRect(m.grid, x - m.BEV, m.HEAD_Y + m.BEV, m.BEV, m.HEAD_H - 2 * m.BEV, shade(t.head, 0, 0.6))
            addRect(m.grid, x, m.HEAD_Y + m.BEV, m.BEV, m.HEAD_H - 2 * m.BEV, shade(t.head, 255, 0.78))
        end if
        addLabel(m.grid, x + 16, m.HEAD_Y, 400, m.HEAD_H, shortTime(w.marks[i]), f.head, t.headInk)
    end for

    for i = 0 to m.VISIBLE_ROWS - 1
        idx = m.firstRow + i
        if idx >= m.channels.Count() then exit for
        ch = m.channels[idx]
        y = m.ROWS_Y + i * m.ROW_H

        ' Channel box: number over call sign.
        plate(m.grid, m.GRID_X, y, m.CHAN_W, m.ROW_H, t.chan)
        numInk = t.numInk
        if idx = m.current then numInk = t.tunedInk
        addLabel(m.grid, m.GRID_X, y + 10, m.CHAN_W, 46, ch.number, f.num, numInk, "center")
        addLabel(m.grid, m.GRID_X + 12, y + 56, m.CHAN_W - 24, 36, UCase(ch.name), f.name, t.chanInk, "center")

        ' Programme blocks.
        sel = invalid
        if idx = m.selRow then sel = blockAt(idx, m.selTime, w)
        for each blk in blocksFor(idx, w)
            x0 = timeX(blk.start, w)
            x1 = timeX(blk.stop, w)
            if x1 - x0 >= 3 * m.BEV then
                lit = false
                if sel <> invalid then lit = blk.start = sel.start
                fill = t.cell
                ink = t.cellInk
                if lit then
                    fill = t.sel
                    ink = t.selInk
                end if
                plate(m.grid, x0, y, x1 - x0, m.ROW_H, fill)
                lx = x0 + 14
                rx = x1 - 14
                cy = y + Int(m.ROW_H / 2)
                if blk.before and rx - lx > 80 then
                    arrow(m.grid, lx, cy, true, ink)
                    lx = lx + 20
                end if
                if blk.after and rx - lx > 80 then
                    arrow(m.grid, rx - 14, cy, false, ink)
                    rx = rx - 22
                end if
                if rx - lx > 30 then
                    l = addLabel(m.grid, lx, y + 6, rx - lx, m.ROW_H - 12, blk.title, f.cell, ink)
                    ' Two lines, but never broken inside a word: a Label
                    ' wraps mid-word when a word is wider than the block
                    ' ("Loone / y Tunes"), so such titles get one cut line.
                    if widestWord(blk.title) <= rx - lx then
                        l.wrap = true
                        l.maxLines = 2
                    end if
                end if
            end if
        end for
    end for
end sub

' The width of the widest word of a title in the cell font, measured once per
' word with an offscreen label.
function widestWord(title as String) as Integer
    if m.measure = invalid then
        m.measure = CreateObject("roSGNode", "Label")
        m.wordWidths = {}
    end if
    m.measure.font = m.fonts.cell
    widest = 0
    for each wd in title.Split(" ")
        w = m.wordWidths[wd]
        if w = invalid then
            m.measure.text = wd
            w = m.measure.boundingRect().width
            m.wordWidths[wd] = w
        end if
        if w > widest then widest = w
    end for
    return widest
end function

sub arrow(parent as Object, x as Integer, cy as Integer, isLeft as Boolean, color as String)
    p = CreateObject("roSGNode", "Poster")
    if isLeft then p.uri = "pkg:/images/ui/tri_left.png" else p.uri = "pkg:/images/ui/tri_right.png"
    p.translation = [x, cy - 11]
    p.width = 14
    p.height = 22
    p.blendColor = color
    parent.appendChild(p)
end sub

' The key-hint bar, each key behind a dot in the remote's button colours.
' Arrows are drawn as pictures: the Roku system font has no arrow glyphs and
' renders "◀ ▶" as empty boxes.
sub paintFooter()
    clearNode(m.footer)
    t = m.theme
    plate(m.footer, m.GRID_X, m.FOOT_Y, m.GRID_W, m.FOOT_H, t.foot)
    hints = [
        { dot: "0x4CD964FF", keys: [], text: "OK  Watch" }
        { dot: "0xFFD60AFF", keys: ["left", "right"], text: "Time" }
        { dot: "0x0A84FFFF", keys: ["up", "down"], text: "Channels" }
        { dot: "0xFF453AFF", keys: [], text: "HOLD OK  Settings" }
        { dot: "0xB0B0B0FF", keys: [], text: "BACK  Close" }
    ]
    font = m.fonts.foot
    total = 0
    for each h in hints
        l = CreateObject("roSGNode", "Label")
        l.font = font
        l.color = t.footInk
        l.text = h.text
        h.label = l
        h.w = 30 + h.keys.Count() * 30 + l.boundingRect().width
        if h.keys.Count() > 0 then h.w = h.w + 6
        total = total + h.w
    end for
    gap = 56
    total = total + gap * (hints.Count() - 1)
    x = m.GRID_X + Int((m.GRID_W - total) / 2)
    cy = m.FOOT_Y + Int(m.FOOT_H / 2)
    for each h in hints
        d = CreateObject("roSGNode", "Poster")
        d.uri = "pkg:/images/ui/dot.png"
        d.width = 18
        d.height = 18
        d.blendColor = h.dot
        d.translation = [x, cy - 9]
        m.footer.appendChild(d)
        kx = x + 30
        for each k in h.keys
            keyArrow(m.footer, kx, cy, k, t.footInk)
            kx = kx + 30
        end for
        if h.keys.Count() > 0 then kx = kx + 6
        h.label.translation = [kx, m.FOOT_Y]
        h.label.height = m.FOOT_H
        h.label.vertAlign = "center"
        m.footer.appendChild(h.label)
        x = x + h.w + gap
    end for
end sub

' A remote key's arrow, centred on cy, as a tinted picture.
sub keyArrow(parent as Object, x as Integer, cy as Integer, dir as String, color as String)
    p = CreateObject("roSGNode", "Poster")
    p.uri = "pkg:/images/ui/tri_" + dir + ".png"
    if dir = "left" or dir = "right" then
        p.width = 14
        p.height = 22
        p.translation = [x + 4, cy - 11]
    else
        p.width = 22
        p.height = 14
        p.translation = [x, cy - 7]
    end if
    p.blendColor = color
    parent.appendChild(p)
end sub

' Lay a channel's programmes onto [a, b) as blocks.
'
' This replaced three half hour columns that each showed whichever programme
' overlapped them first. Almost nothing on ErsatzTV starts on a half hour, so
' every block starts and stops where its programme does. Programmes too short
' to label at this scale are merged with their short neighbours into one block
' labelled with the title they share, or the first. [before] and [after] mark a
' block that runs off either edge, drawn as a triangle as Prevue did.
function blocks(list as Object, a as Integer, b as Integer) as Object
    minSecs = Int(m.MIN_LABEL_PX * (b - a) / m.LINE_W)
    out = []
    pend = invalid
    for each p in list
        if p.stop > a and p.start < b then
            st = p.start
            if st < a then st = a
            sp = p.stop
            if sp > b then sp = b
            isShort = sp - st < minSecs
            if pend <> invalid then
                if not isShort or st - pend.stop > 60 or pend.stop - pend.start >= 2 * minSecs then
                    out.Push(closeRun(pend))
                    pend = invalid
                end if
            end if
            if not isShort then
                out.Push({ start: st, stop: sp, title: gridTitle(p.title), before: p.start < a, after: p.stop > b })
            else
                if pend = invalid then pend = { start: st, stop: sp, titles: [], before: p.start < a, after: false }
                pend.titles.Push(gridTitle(p.title))
                pend.stop = sp
                pend.after = p.stop > b
            end if
        end if
    end for
    if pend <> invalid then out.Push(closeRun(pend))
    return out
end function

function closeRun(pend as Object) as Object
    return { start: pend.start, stop: pend.stop, title: commonTitle(pend.titles), before: pend.before, after: pend.after }
end function

' Leading episode numbering - "1943 - 24 - Merrie Melodies - ..." - is how
' these files sort on disk, and in a narrow block it is all that would show.
' The banner keeps the full title.
function gridTitle(title as String) as String
    if m.numbering = invalid then m.numbering = CreateObject("roRegex", "^(\s*\d+[a-z]?\s*[-.]\s*)+(?=\S)", "i")
    t = m.numbering.Replace(title, "")
    if t = "" then return title
    return t
end function

' "The Simpsons Tracy Ullman Shorts 40 - ..." and "...Shorts 04 - ..." read as
' "The Simpsons Tracy Ullman Shorts"; titles with nothing worth sharing fall
' back to the first.
function commonTitle(titles as Object) as String
    if titles.Count() = 1 then return titles[0]
    prefix = titles[0]
    for each t in titles
        while Len(prefix) > 0 and Left(t, Len(prefix)) <> prefix
            prefix = Left(prefix, Len(prefix) - 1)
        end while
    end for
    if m.trailing = invalid then m.trailing = CreateObject("roRegex", "[\s\-:#0-9]+$", "")
    prefix = m.trailing.Replace(prefix, "")
    if Len(prefix) >= 6 then return prefix
    return titles[0]
end function

sub onClockTimer()
    updateClock()
    ' The blocks only move when the half hour rolls over, so only redraw then.
    if m.guideOpen and currentSlot() <> m.paintedSlot then
        if m.selTime < guideWindow().a then m.selTime = guideWindow().a
        paintGrid()
        paintInfo()
    end if
end sub

sub updateClock()
    m.clockLabel.text = hhmmAmPm(nowSeconds())
end sub

' ------------------------------------------------------------------ setup

sub openSetup()
    m.setupOpen = true
    m.setup.visible = true
    m.guide.visible = false
    m.banner.visible = false
    m.status.visible = false
    m.setupHosts = []
    m.setupCursor = 0
    m.setupStatus.text = "Searching the local network for ErsatzTV…"
    paintSetup()

    m.discovery = CreateObject("roSGNode", "DiscoveryTask")
    m.discovery.observeField("found", "onDiscovered")
    m.discovery.control = "RUN"
end sub

sub onDiscovered(event as Object)
    res = event.getData()
    m.setupHosts = res.hosts
    if m.setupHosts.Count() = 0 then
        m.setupStatus.text = "No server found automatically. Enter the address by hand."
    else
        m.setupStatus.text = "Found " + StrI(m.setupHosts.Count()).Trim() + " server(s). Choose one, or enter an address by hand."
    end if
    paintSetup()
end sub

sub paintSetup()
    clearNode(m.setupList)

    items = []
    for each h in m.setupHosts
        items.Push(h)
    end for
    items.Push("Enter an address manually…")
    m.setupItems = items

    if m.setupCursor >= items.Count() then m.setupCursor = items.Count() - 1
    if m.setupCursor < 0 then m.setupCursor = 0

    for i = 0 to items.Count() - 1
        group = CreateObject("roSGNode", "Group")
        group.translation = [0, i * 90]
        bg = CreateObject("roSGNode", "Rectangle")
        bg.width = 1680
        bg.height = 80
        if i = m.setupCursor then
            bg.color = "0x3A3AB0FF"
        else
            bg.color = "0x14146699"
        end if
        group.appendChild(bg)
        label = CreateObject("roSGNode", "Label")
        label.translation = [30, 22]
        label.width = 1600
        label.font = "font:LargeBoldSystemFont"
        label.color = "0xF2F2FFFF"
        label.text = items[i]
        group.appendChild(label)
        m.setupList.appendChild(group)
    end for
end sub

sub chooseSetupItem()
    if m.setupItems.Count() = 0 then return
    if m.setupCursor = m.setupItems.Count() - 1 then
        askForHost()
    else
        applyHost(m.setupItems[m.setupCursor])
    end if
end sub

sub askForHost()
    kb = CreateObject("roSGNode", "StandardKeyboardDialog")
    kb.title = "ErsatzTV address"
    kb.buttons = ["OK", "Cancel"]
    existing = readRegistry("host")
    if existing = "" then existing = "192.168.1.100:8409"
    kb.text = existing
    kb.observeField("buttonSelected", "onKeyboardButton")
    m.keyboard = kb
    m.top.dialog = kb
end sub

sub onKeyboardButton(event as Object)
    idx = event.getData()
    kb = m.keyboard
    m.top.dialog = invalid
    if idx = 0 and kb <> invalid then
        typed = kb.text
        if typed <> invalid and typed.Trim() <> "" then applyHost(typed.Trim())
    end if
    m.keyboard = invalid
end sub

sub applyHost(hostText as String)
    h = hostText
    ' Accept a bare IP; the port is the same for everyone.
    if Instr(1, h, ":") = 0 then h = h + ":8409"
    m.host = h
    writeRegistry("host", h)
    m.setupOpen = false
    m.setup.visible = false
    m.status.visible = true
    m.status.text = "TUNING…"
    loadLineup()
end sub

' ------------------------------------------------------------------ settings

' Hold OK (or *, where the TV lets it through). Theme is changed in place with left/right and saved at once,
' so the guide behind the panel is already wearing it when the panel closes.
function settingsItems() as Object
    return ["THEME", "CHANGE SERVER ADDRESS", "BACK TO TV"]
end function

sub openSettings()
    m.settingsOpen = true
    m.settingsCursor = 0
    m.banner.visible = false
    m.settings.visible = true
    paintSettings()
end sub

sub closeSettings()
    m.settingsOpen = false
    m.settings.visible = false
end sub

sub paintSettings()
    clearNode(m.settingsList)
    items = settingsItems()
    t = m.theme
    for i = 0 to items.Count() - 1
        fill = t.cell
        ink = t.cellInk
        if i = m.settingsCursor then
            fill = t.sel
            ink = t.selInk
        end if
        plate(m.settingsList, 0, i * 120, 1280, 104, fill)
        text = items[i]
        if i = 0 then text = "THEME:  " + themeLabel(m.themeKey)
        addLabel(m.settingsList, 0, i * 120, 1280, 104, text, gridFont(44), ink, "center")
        if i = 0 then
            keyArrow(m.settingsList, 180, 52, "left", ink)
            keyArrow(m.settingsList, 1086, 52, "right", ink)
        end if
    end for
end sub

sub cycleTheme(delta as Integer)
    keys = themeKeys()
    i = 0
    for k = 0 to keys.Count() - 1
        if keys[k] = m.themeKey then i = k
    end for
    n = keys.Count()
    m.themeKey = keys[((i + delta) mod n + n) mod n]
    writeRegistry("theme", m.themeKey)
    applyTheme()
    paintSettings()
end sub

function onSettingsKey(key as String) as Boolean
    n = settingsItems().Count()
    if key = "up" then
        m.settingsCursor = (m.settingsCursor - 1 + n) mod n
        paintSettings()
    else if key = "down" then
        m.settingsCursor = (m.settingsCursor + 1) mod n
        paintSettings()
    else if (key = "left" or key = "right") and m.settingsCursor = 0 then
        if key = "left" then cycleTheme(-1) else cycleTheme(1)
    else if key = "OK" then
        if m.settingsCursor = 0 then
            cycleTheme(1)
        else if m.settingsCursor = 1 then
            closeSettings()
            openSetup()
        else
            closeSettings()
        end if
    else if key = "back" or key = "options" then
        closeSettings()
    end if
    return true
end function

' ------------------------------------------------------------------ remote

' OK does double duty: a press does the usual thing, a hold opens SETTINGS.
' The * button cannot be relied on for settings - on a Roku TV it opens the
' TV's own picture panel while video plays, and the app never hears it. So
' OK is acted on at release, and a timer started at press decides whether it
' was held.
sub onHoldTimer()
    m.okHeld = true
    if not m.settingsOpen then openSettings()
end sub

sub onShortOk()
    if m.channels.Count() = 0 then return
    if m.guideOpen then
        if m.selRow <> m.current then tune(m.selRow)
        closeGuide()
    else
        showBanner(m.channels[m.current])
    end if
end sub

function onKeyEvent(key as String, press as Boolean) as Boolean
    if key = "OK" and not m.setupOpen and not m.awaitingSetup and (not m.settingsOpen or not press) then
        if press then
            m.okArmed = true
            m.okHeld = false
            m.holdTimer.control = "stop"
            m.holdTimer.control = "start"
        else
            ' Only a press that began here counts: OK on "BACK TO TV" closes
            ' settings on the press, and its release must not act again.
            m.holdTimer.control = "stop"
            if m.okArmed and not m.okHeld and not m.settingsOpen then onShortOk()
            m.okArmed = false
            m.okHeld = false
        end if
        return true
    end if
    if not press then return false

    if m.setupOpen then
        if key = "up" then
            m.setupCursor = m.setupCursor - 1
            paintSetup()
            return true
        else if key = "down" then
            m.setupCursor = m.setupCursor + 1
            paintSetup()
            return true
        else if key = "OK" then
            chooseSetupItem()
            return true
        end if
        ' Nothing to go back to until a server is chosen.
        return true
    end if

    if m.settingsOpen then return onSettingsKey(key)

    if m.awaitingSetup and key = "OK" then
        openSetup()
        return true
    end if

    if m.guideOpen then
        if key = "back" then
            closeGuide()
        else if key = "up" then
            moveRow(-1)
        else if key = "down" then
            moveRow(1)
        else if key = "rewind" then
            moveRow(-m.VISIBLE_ROWS)
        else if key = "fastforward" then
            moveRow(m.VISIBLE_ROWS)
        else if key = "right" then
            moveTime(1)
        else if key = "left" then
            moveTime(-1)
        else if key = "options" then
            openSettings()
        end if
        return true
    end if

    if m.channels.Count() = 0 then
        if key = "options" then openSettings()
        return key <> "back"
    end if

    if key = "up" then
        tune(m.current - 1)
        return true
    else if key = "down" then
        tune(m.current + 1)
        return true
    else if key = "back" then
        ' BACK opens the guide, the way a cable box's guide button does.
        ' It never exits - HOME is the way out, as on any TV app.
        openGuide()
        return true
    else if key = "info" then
        showBanner(m.channels[m.current])
        return true
    else if key = "options" then
        openSettings()
        return true
    end if
    return false
end function

' ------------------------------------------------------------------ time

function nowSeconds() as Integer
    dt = CreateObject("roDateTime")
    return dt.AsSeconds()
end function

function localOffset() as Integer
    utc = CreateObject("roDateTime")
    loc = CreateObject("roDateTime")
    loc.ToLocalTime()
    return loc.AsSeconds() - utc.AsSeconds()
end function

' The start of the current local half hour.
function currentSlot() as Integer
    half = 1800
    offset = localOffset()
    localNow = nowSeconds() + offset
    return localNow - (localNow mod half) - offset
end function

function localTime(t as Integer) as Object
    dt = CreateObject("roDateTime")
    dt.FromSeconds(t)
    dt.ToLocalTime()
    return dt
end function

' Guide-style times: "7:30p".
function shortTime(t as Integer) as String
    dt = localTime(t)
    hour = dt.GetHours()
    h12 = hour mod 12
    if h12 = 0 then h12 = 12
    suffix = "a"
    if hour >= 12 then suffix = "p"
    return StrI(h12).Trim() + ":" + twoDigits(dt.GetMinutes()) + suffix
end function

function hhmmAmPm(t as Integer) as String
    dt = localTime(t)
    hour = dt.GetHours()
    h12 = hour mod 12
    if h12 = 0 then h12 = 12
    suffix = " AM"
    if hour >= 12 then suffix = " PM"
    return StrI(h12).Trim() + ":" + twoDigits(dt.GetMinutes()) + suffix
end function

function dayName(t as Integer) as String
    return Left(localTime(t).GetWeekday(), 3)
end function

function monthDay(t as Integer) as String
    dt = localTime(t)
    return StrI(dt.GetMonth()).Trim() + "/" + StrI(dt.GetDayOfMonth()).Trim()
end function

function twoDigits(n as Integer) as String
    s = StrI(n).Trim()
    if n < 10 then s = "0" + s
    return s
end function
