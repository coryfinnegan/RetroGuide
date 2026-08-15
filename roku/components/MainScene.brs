' Retro Guide - a 1990s TV Guide channel for ErsatzTV, on Roku.
'
' Feature for feature with the Google TV build: LAN discovery, saved server,
' live playback, channel surfing with a banner, the guide overlay with its
' corner video window and half hour columns, wrap around scrolling, resume of
' the last channel, and a settle delay so surfing does not stampede the server.
'
' The one thing missing is tuning by typing a channel number, because no Roku
' remote has a keypad to type it on.

sub init()
    ' ErsatzTV starts an ffmpeg per request, so surfing with one request per
    ' keypress leaves a queue of them starting and tearing down at once.
    ' Waiting for the viewer to settle means one request per channel actually
    ' landed on - which is also how a real cable box behaves: the banner moves
    ' at once, the picture follows.
    m.TUNE_SETTLE = 0.65
    m.RETRY_DELAY = 1.2
    m.MAX_RETRIES = 2
    m.BANNER_SECS = 4
    m.ROW_H = 128
    m.VISIBLE_ROWS = 4

    m.video = m.top.findNode("video")
    m.banner = m.top.findNode("banner")
    m.bannerNumber = m.top.findNode("bannerNumber")
    m.bannerName = m.top.findNode("bannerName")
    m.bannerNow = m.top.findNode("bannerNow")
    m.guide = m.top.findNode("guide")
    m.guideBg = m.top.findNode("guideBg")
    m.rows = m.top.findNode("rows")
    m.clockLabel = m.top.findNode("clock")
    m.nowPlaying = m.top.findNode("nowPlaying")
    m.slotLabels = [m.top.findNode("slot0"), m.top.findNode("slot1"), m.top.findNode("slot2")]
    m.status = m.top.findNode("status")
    m.setup = m.top.findNode("setup")
    m.setupStatus = m.top.findNode("setupStatus")
    m.setupList = m.top.findNode("setupList")

    m.channels = []
    m.guideData = {}
    m.current = 0
    m.guideOpen = false
    m.setupOpen = false
    m.awaitingSetup = false
    m.retries = 0
    m.cursor = 0        ' focused row in the guide
    m.firstRow = 0      ' index of the topmost visible row
    m.setupCursor = 0
    m.setupHosts = []
    m.setupItems = []
    m.channelBeforeGuide = 0

    buildGuideBackground()
    buildRows()

    m.video.observeField("state", "onVideoState")

    m.tuneTimer = newTimer(m.TUNE_SETTLE, false, "onTuneTimer")
    m.retryTimer = newTimer(m.RETRY_DELAY, false, "onRetryTimer")
    m.bannerTimer = newTimer(m.BANNER_SECS, false, "onBannerTimer")
    m.clockTimer = newTimer(30, true, "onClockTimer")
    m.clockTimer.control = "start"

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
    m.loader.observeField("result", "onLineup")
    m.loader.control = "RUN"
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
    m.guideData = res.guide
    ? "[rg] channels="; m.channels.Count(); " guide keys="; m.guideData.Count()
    if m.channels.Count() > 0
        ? "[rg] first: #"; m.channels[0].number; " "; m.channels[0].name; " -> "; m.channels[0].url
    end if
    m.status.visible = false
    m.awaitingSetup = false

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
end sub

' ------------------------------------------------------------------ tuning

sub tune(index as Integer, announce = true as Boolean)
    if m.channels.Count() = 0 then return
    n = m.channels.Count()
    m.current = ((index mod n) + n) mod n
    ch = m.channels[m.current]

    writeRegistry("last_channel", ch.number)
    if announce then showBanner(ch)
    if m.guideOpen then
        paintRows()
        updateNowPlaying()
    end if

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
    m.video.content = content
    m.video.control = "play"
    ? "[rg] video.control="; m.video.control; " visible="; m.video.visible; " w="; m.video.width
end sub

sub onVideoState(event as Object)
    ? "[rg] video state="; event.getData(); " errCode="; m.video.errorCode; " errMsg="; m.video.errorMsg
    if event.getData() <> "error" then return

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
    p = nowOn(ch)
    if p <> invalid then line = hhmm(p.start) + "–" + hhmm(p.stop) + "  " + p.title
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

function nowOn(ch as Object) as Dynamic
    if ch = invalid or ch.id = "" then return invalid
    list = m.guideData[ch.id]
    if list = invalid then return invalid
    t = nowSeconds()
    for each p in list
        if t >= p.start and t < p.stop then return p
    end for
    return invalid
end function

function fromSlot(ch as Object, slotStart as Integer, slotEnd as Integer) as Dynamic
    if ch = invalid or ch.id = "" then return invalid
    list = m.guideData[ch.id]
    if list = invalid then return invalid
    for each p in list
        if p.start < slotEnd and p.stop > slotStart then return p
    end for
    return invalid
end function

' ------------------------------------------------------------------ the guide

sub buildGuideBackground()
    ' No gradient node on Roku, so the indigo bed is drawn as bands stepping
    ' from the top colour down to the bottom one.
    BANDS = 24
    bandH = Int(1080 / BANDS)
    for i = 0 to BANDS - 1
        f = i / (BANDS - 1)
        band = CreateObject("roSGNode", "Rectangle")
        band.width = 1920
        band.height = bandH + 1
        band.translation = [0, i * bandH]
        band.color = mixColor(&h2A, &h2A, &h8C, &h0A, &h0A, &h32, f)
        m.guideBg.appendChild(band)
    end for
end sub

function mixColor(r1, g1, b1, r2, g2, b2, f as Float) as String
    r = Int(r1 + (r2 - r1) * f)
    g = Int(g1 + (g2 - g1) * f)
    b = Int(b1 + (b2 - b1) * f)
    return "0x" + hex2(r) + hex2(g) + hex2(b) + "FF"
end function

function hex2(v as Integer) as String
    digits = "0123456789ABCDEF"
    hi = Int(v / 16)
    lo = v mod 16
    return Mid(digits, hi + 1, 1) + Mid(digits, lo + 1, 1)
end function

' Rows keep direct references to their labels rather than looking them up by
' id, since every row would otherwise carry the same ids.
sub buildRows()
    m.rowNodes = []
    for i = 0 to m.VISIBLE_ROWS - 1
        group = CreateObject("roSGNode", "Group")
        group.translation = [0, i * m.ROW_H]

        bg = CreateObject("roSGNode", "Rectangle")
        bg.width = 1820
        bg.height = m.ROW_H - 4
        group.appendChild(bg)

        no = CreateObject("roSGNode", "Label")
        no.translation = [10, 34]
        no.width = 90
        no.horizAlign = "right"
        no.font = "font:LargeBoldSystemFont"
        no.color = "0xFFC020FF"
        group.appendChild(no)

        nm = CreateObject("roSGNode", "Label")
        nm.translation = [120, 34]
        nm.width = 370
        nm.font = "font:LargeBoldSystemFont"
        group.appendChild(nm)

        cells = []
        for c = 0 to 2
            cell = CreateObject("roSGNode", "Label")
            cell.translation = [500 + c * 445, 24]
            cell.width = 430
            cell.wrap = true
            cell.numLines = 2
            cell.font = "font:MediumSystemFont"
            cell.color = "0xF2F2FFFF"
            group.appendChild(cell)
            cells.Push(cell)
        end for

        m.rows.appendChild(group)
        m.rowNodes.Push({ group: group, bg: bg, no: no, nm: nm, cells: cells })
    end for
end sub

sub openGuide()
    m.guideOpen = true
    ' Browsing only previews. Leaving the guide with BACK puts this back on,
    ' so the cursor cannot change the channel by accident - only OK commits.
    m.channelBeforeGuide = m.current
    m.cursor = m.current
    m.firstRow = m.cursor - Int(m.VISIBLE_ROWS / 2)
    clampFirstRow()

    s = slots()
    for i = 0 to 2
        m.slotLabels[i].text = hhmm(s[i])
    end for
    updateClock()

    updateNowPlaying()

    ' Move the video into the corner window, exactly like the original channel.
    m.video.translation = [55, 50]
    m.video.scale = [1.0, 1.0]
    m.video.width = 610
    m.video.height = 343
    startStream()
    m.guide.visible = true
    paintRows()
end sub

' Undo the previewing done while browsing.
sub abandonPreview()
    if m.channelBeforeGuide = m.current then return
    m.current = m.channelBeforeGuide
    writeRegistry("last_channel", m.channels[m.current].number)
end sub

sub updateNowPlaying()
    if m.channels.Count() = 0 then return
    ch = m.channels[m.current]
    p = nowOn(ch)
    t = ""
    if p <> invalid then t = p.title
    m.nowPlaying.text = ch.number + "  " + ch.name + chr(10) + t
end sub

sub closeGuide()
    m.guideOpen = false
    m.guide.visible = false
    m.video.translation = [0, 0]
    m.video.scale = [1.0, 1.0]
    m.video.width = 1920
    m.video.height = 1080
    startStream()
end sub

sub clampFirstRow()
    maxTop = m.channels.Count() - m.VISIBLE_ROWS
    if maxTop < 0 then maxTop = 0
    if m.firstRow > maxTop then m.firstRow = maxTop
    if m.firstRow < 0 then m.firstRow = 0
end sub

sub moveCursor(delta as Integer)
    n = m.channels.Count()
    if n = 0 then return
    ' Wrap at both ends, so holding down past the last channel returns to the
    ' first rather than stopping dead.
    m.cursor = ((m.cursor + delta) mod n + n) mod n
    if m.cursor < m.firstRow then m.firstRow = m.cursor
    if m.cursor > m.firstRow + m.VISIBLE_ROWS - 1 then m.firstRow = m.cursor - m.VISIBLE_ROWS + 1
    clampFirstRow()
    paintRows()
    ' The corner window follows the highlighted channel, so browsing the guide
    ' previews what you are about to pick. The settle delay means scrolling
    ' quickly still only opens one stream.
    tune(m.cursor, false)
end sub

sub paintRows()
    s = slots()
    for i = 0 to m.VISIBLE_ROWS - 1
        row = m.rowNodes[i]
        idx = m.firstRow + i
        if idx >= m.channels.Count() then
            row.group.visible = false
        else
            row.group.visible = true
            ch = m.channels[idx]
            row.no.text = ch.number
            row.nm.text = ch.name
            for c = 0 to 2
                p = fromSlot(ch, s[c], s[c + 1])
                t = "—"
                if p <> invalid then t = p.title
                row.cells[c].text = t
            end for

            ' Focus is the only thing that draws the bright background. The
            ' tuned channel keeps a dimmer tint of its own plus a cyan name, so
            ' moving the cursor never leaves a trail of highlighted rows.
            if idx = m.cursor then
                row.bg.color = "0x3A3AB0FF"
            else if idx = m.current then
                row.bg.color = "0x24249966"
            else if idx mod 2 = 0 then
                row.bg.color = "0x10105533"
            else
                row.bg.color = "0x20207022"
            end if
            if idx = m.current then
                row.nm.color = "0x66E0FFFF"
            else
                row.nm.color = "0xF2F2FFFF"
            end if
        end if
    end for
end sub

sub onClockTimer()
    updateClock()
    if m.guideOpen then paintRows()
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
    while m.setupList.getChildCount() > 0
        m.setupList.removeChildIndex(0)
    end while

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

' ------------------------------------------------------------------ remote

function onKeyEvent(key as String, press as Boolean) as Boolean
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

    if m.awaitingSetup and key = "OK" then
        openSetup()
        return true
    end if

    if m.guideOpen then
        if key = "back" then
            abandonPreview()
            closeGuide()
        else if key = "up" then
            moveCursor(-1)
        else if key = "down" then
            moveCursor(1)
        else if key = "OK" then
            closeGuide()
        else if key = "options" then
            openSetup()
        end if
        return true
    end if

    if key = "up" then
        tune(m.current - 1)
        return true
    else if key = "down" then
        tune(m.current + 1)
        return true
    else if key = "back" then
        ' BACK opens the channel list, the way a cable box's guide button does.
        ' It never exits - HOME is the way out, as on any TV app.
        openGuide()
        return true
    else if key = "OK" or key = "info" then
        if m.channels.Count() > 0 then showBanner(m.channels[m.current])
        return true
    else if key = "options" then
        openSetup()
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

' Half hour slot boundaries starting at the current half hour.
function slots() as Object
    half = 1800
    offset = localOffset()
    localNow = nowSeconds() + offset
    base = localNow - (localNow mod half) - offset
    return [base, base + half, base + 2 * half, base + 3 * half]
end function

function hhmm(t as Integer) as String
    parts = clockParts(t)
    return StrI(parts.h12).Trim() + ":" + twoDigits(parts.min)
end function

function hhmmAmPm(t as Integer) as String
    parts = clockParts(t)
    suffix = " AM"
    if parts.hour >= 12 then suffix = " PM"
    return StrI(parts.h12).Trim() + ":" + twoDigits(parts.min) + suffix
end function

function clockParts(t as Integer) as Object
    dt = CreateObject("roDateTime")
    dt.FromSeconds(t)
    dt.ToLocalTime()
    hour = dt.GetHours()
    h12 = hour mod 12
    if h12 = 0 then h12 = 12
    return { hour: hour, h12: h12, min: dt.GetMinutes() }
end function

function twoDigits(n as Integer) as String
    s = StrI(n).Trim()
    if n < 10 then s = "0" + s
    return s
end function
