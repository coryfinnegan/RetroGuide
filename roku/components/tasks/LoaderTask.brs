' Fetches the channel lineup, or the guide - one or the other per run.
'
' ErsatzTV publishes a standard M3U and an XMLTV file, so there is no bespoke
' API here - the same two URLs any IPTV player would use. Runs on a task thread
' because both are slow and the render thread must not block.
'
' The two used to be one job, and the picture waited for both. The guide is a
' 7 MB file of ~19,000 programmes; reading it first kept every launch on
' TUNING, and on a TV's CPU it also competed with the decoder just as playback
' began. So the scene asks for channels (mode "channels"), starts the picture,
' and only then asks for the guide (mode "guide").

sub init()
    m.top.functionName = "run"
end sub

sub run()
    host = m.top.host
    out = { channels: [], guide: {}, error: "", ms: 0 }
    clock = CreateObject("roTimespan")

    if m.top.mode = "guide" then
        ? "[rg] guide task started"
        xml = httpGet("http://" + host + "/iptv/xmltv.xml")
        fetched = clock.TotalMilliseconds()
        if xml = invalid then
            ? "[rg] guide fetch failed after "; fetched; " ms"
        else
            ? "[rg] guide fetched "; xml.Len(); " chars in "; fetched; " ms"
        end if
        if xml <> invalid then out.guide = parseGuide(xml)
        out.ms = clock.TotalMilliseconds()
        ? "[rg] guide: fetch "; fetched; " ms, parse "; out.ms - fetched; " ms, "; out.guide.Count(); " channels"
        m.top.result = out
        return
    end if

    m3u = httpGet("http://" + host + "/iptv/channels.m3u")
    if m3u = invalid then
        out.error = "Cannot reach " + host
        m.top.result = out
        return
    end if
    out.channels = parseM3U(m3u)
    out.ms = clock.TotalMilliseconds()
    m.top.result = out
end sub

function httpGet(url as String) as Dynamic
    xfer = CreateObject("roUrlTransfer")
    xfer.SetUrl(url)
    xfer.AddHeader("User-Agent", "RetroGuide/1.0")
    xfer.SetRequest("GET")
    xfer.EnableEncodings(true)
    body = xfer.GetToString()
    if body = invalid or body = "" then return invalid
    return body
end function

' ---------------------------------------------------------------- M3U

function parseM3U(text as String) as Object
    q = chr(34)
    reId = CreateObject("roRegex", "tvg-id=" + q + "([^" + q + "]*)" + q, "")
    reNo = CreateObject("roRegex", "tvg-chno=" + q + "([^" + q + "]*)" + q, "")
    reLogo = CreateObject("roRegex", "tvg-logo=" + q + "([^" + q + "]*)" + q, "")
    reName = CreateObject("roRegex", ",([^,]*)$", "")
    reLine = CreateObject("roRegex", "\r?\n", "")

    out = []
    pending = invalid
    for each raw in reLine.Split(text)
        line = raw.Trim()
        if line.Left(7) = "#EXTINF" then
            pending = { id: "", number: "", logo: "", name: "Channel" }
            mm = reId.Match(line)
            if mm.Count() > 1 then pending.id = mm[1]
            mm = reNo.Match(line)
            if mm.Count() > 1 then pending.number = mm[1]
            mm = reLogo.Match(line)
            if mm.Count() > 1 then pending.logo = mm[1]
            mm = reName.Match(line)
            if mm.Count() > 1 then pending.name = mm[1].Trim()
        else if line <> "" and line.Left(1) <> "#" and pending <> invalid then
            ' Ask for the channel as segmented HLS. Roku will not play raw
            ' MPEG-TS at all, and plain .m3u8 hands back a single segment as
            ' long as the programme - which Roku accepts and then fails on with
            ' a bare "unexpected problem" (error -3). mode=segmenter is the one
            ' form that yields a normal live playlist of short segments, and it
            ' needs no server side configuration.
            if Right(line, 3) = ".ts" then
                pending.url = Left(line, line.Len() - 3) + ".m3u8?mode=segmenter"
            else
                pending.url = line
            end if
            if pending.number = "" then pending.number = StrI(out.Count() + 1).Trim()
            out.Push(pending)
            pending = invalid
        end if
    end for

    ' Sort by channel number, numerically - "10" must not land next to "1",
    ' and ToFloat rather than ToInt so a sub-channel like "23.1" sorts just
    ' after 23 instead of tying with it.
    for i = 1 to out.Count() - 1
        item = out[i]
        key = item.number.ToFloat()
        j = i - 1
        while j >= 0 and out[j].number.ToFloat() > key
            out[j + 1] = out[j]
            j = j - 1
        end while
        out[j + 1] = item
    end for
    return out
end function

' ---------------------------------------------------------------- XMLTV

' Only a window around now is kept: three hours back, twelve ahead.
'
' This was a roXMLElement parse of the whole document followed by a
' BrightScript walk of every element. Two faster-looking replacements failed
' outright on the TV: roRegex.MatchAll over the 7 MB string, and Instr/Mid
' hopping through it, never finished - character offsets into a string that
' size are not cheap. ErsatzTV writes every programme the same way -
'   <programme start="YYYYMMDDHHMMSS -0500" stop="YYYYMMDDHHMMSS -0500" channel="...">
' - so the document is split once on that opening and each short piece read
' at fixed offsets. Only programmes inside the window go on to have their
' channel and title read. Stamps are compared as strings, which orders correctly while the
' offset is constant, and converted to epoch only once inside the window. A
' programme laid out any other way is skipped rather than misread.
function parseGuide(text as String) as Object
    byChannel = {}
    q = chr(34)
    stopTag = q + " stop=" + q
    ' One native pass cuts the document into one short string per programme.
    ' Indexing into the 7 MB original instead (Instr/Mid at a high offset)
    ' walks the string from its start each time and never finished on the TV.
    parts = text.Split("<programme start=" + q)
    if parts.Count() < 2 then return byChannel

    tz = Mid(parts[1], 16, 5)
    offset = tzSeconds(tz)
    now = nowSeconds()
    fromS = stampOf(now - 3 * 3600 + offset)
    toS = stampOf(now + 12 * 3600 + offset)

    for i = 1 to parts.Count() - 1
        c = parts[i]
        ' c: YYYYMMDDHHMMSS -0500" stop="YYYYMMDDHHMMSS -0500" channel="ID"><title lang="en">TITLE</title>...
        if Mid(c, 21, 8) = stopTag then
            startS = Left(c, 14)
            stopS = Mid(c, 29, 14)
            if stopS > fromS and startS < toS then
                chs = Instr(43, c, "channel=" + q)
                ts = Instr(43, c, "<title")
                if chs > 0 and ts > 0 then
                    chs = chs + 9
                    che = Instr(chs, c, q)
                    gt = Instr(ts, c, ">")
                    te = Instr(gt, c, "<")
                    title = unescape(Mid(c, gt + 1, te - gt - 1))
                    if title <> "" then
                        ch = Mid(c, chs, che - chs)
                        list = byChannel[ch]
                        if list = invalid then
                            list = []
                            byChannel[ch] = list
                        end if
                        list.Push({ start: xmltvToEpoch(startS, tz), stop: xmltvToEpoch(stopS, tz), title: title })
                    end if
                end if
            end if
        end if
    end for
    ' The file lists each channel's programmes in time order already, so
    ' there is nothing to sort.
    return byChannel
end function

function unescape(s as String) as String
    if Instr(1, s, "&") = 0 then return s
    if m.ent = invalid then m.ent = CreateObject("roRegex", "&#(\d+);", "")
    t = s
    for each mm in m.ent.MatchAll(t)
        t = t.Replace(mm[0], chr(mm[1].ToInt()))
    end for
    t = t.Replace("&quot;", chr(34)).Replace("&apos;", "'").Replace("&lt;", "<").Replace("&gt;", ">")
    return t.Replace("&amp;", "&")
end function

function tzSeconds(tz as String) as Integer
    sign = 1
    if Left(tz, 1) = "-" then sign = -1
    return sign * (Mid(tz, 2, 2).ToInt() * 3600 + Mid(tz, 4, 2).ToInt() * 60)
end function

' Epoch seconds (already shifted to local) as an XMLTV stamp.
function stampOf(t as Integer) as String
    dt = CreateObject("roDateTime")
    dt.FromSeconds(t)
    return StrI(dt.GetYear()).Trim() + two(dt.GetMonth()) + two(dt.GetDayOfMonth()) + two(dt.GetHours()) + two(dt.GetMinutes()) + two(dt.GetSeconds())
end function

function two(n as Integer) as String
    if n < 10 then return "0" + StrI(n).Trim()
    return StrI(n).Trim()
end function

' XMLTV stamps look like "20260814200644" with an offset like "-0500".
' roDateTime only reads ISO8601 in UTC, so the conversion is done by hand.
function xmltvToEpoch(t as String, tz as String) as Integer
    y = Mid(t, 1, 4).ToInt()
    mo = Mid(t, 5, 2).ToInt()
    d = Mid(t, 7, 2).ToInt()
    h = Mid(t, 9, 2).ToInt()
    mi = Mid(t, 11, 2).ToInt()
    sec = Mid(t, 13, 2).ToInt()
    return daysFromCivil(y, mo, d) * 86400 + h * 3600 + mi * 60 + sec - tzSeconds(tz)
end function

function daysFromCivil(y as Integer, m as Integer, d as Integer) as Integer
    yy = y
    if m <= 2 then yy = yy - 1
    era = Int(yy / 400)
    yoe = yy - era * 400
    mp = (m + 9) mod 12
    doy = Int((153 * mp + 2) / 5) + d - 1
    doe = yoe * 365 + Int(yoe / 4) - Int(yoe / 100) + doy
    return era * 146097 + doe - 719468
end function

function nowSeconds() as Integer
    dt = CreateObject("roDateTime")
    return dt.AsSeconds()
end function
