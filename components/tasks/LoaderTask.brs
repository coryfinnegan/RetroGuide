' Fetches the channel lineup and the guide.
'
' ErsatzTV publishes a standard M3U and an XMLTV file, so there is no bespoke
' API here - the same two URLs any IPTV player would use. Runs on a task thread
' because both are slow and the render thread must not block.

sub init()
    m.top.functionName = "run"
end sub

sub run()
    host = m.top.host
    out = { channels: [], guide: {}, error: "" }

    ' mode=hls-direct is the important part on Roku. The default M3U hands out
    ' raw MPEG-TS URLs, and .m3u8 for a channel just redirects back to the .ts;
    ' only this mode returns a real playlist (application/vnd.apple.mpegurl),
    ' which is the one thing Roku's video node will accept for live.
    m3u = httpGet("http://" + host + "/iptv/channels.m3u?mode=hls-direct")
    if m3u = invalid then
        out.error = "Cannot reach " + host
        m.top.result = out
        return
    end if
    out.channels = parseM3U(m3u)

    ' A missing guide must not stop playback - channels matter more.
    xml = httpGet("http://" + host + "/iptv/xmltv.xml")
    if xml <> invalid then out.guide = parseGuide(xml)

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
            pending.url = line
            if pending.number = "" then pending.number = StrI(out.Count() + 1).Trim()
            out.Push(pending)
            pending = invalid
        end if
    end for

    ' Sort by channel number, numerically - "10" must not land next to "1".
    for i = 1 to out.Count() - 1
        item = out[i]
        key = item.number.ToInt()
        j = i - 1
        while j >= 0 and out[j].number.ToInt() > key
            out[j + 1] = out[j]
            j = j - 1
        end while
        out[j + 1] = item
    end for
    return out
end function

' ---------------------------------------------------------------- XMLTV

' Only a window around now is kept. The guide covers days, the UI shows what is
' on now and the next few half hours, and a real lineup here is thousands of
' programmes - holding all of them on a streaming box buys nothing.
function parseGuide(text as String) as Object
    byChannel = {}
    root = CreateObject("roXMLElement")
    if not root.Parse(text) then return byChannel

    now = nowSeconds()
    fromT = now - 3 * 3600
    toT = now + 12 * 3600

    for each prog in root.GetNamedElements("programme")
        attrs = prog.GetAttributes()
        if attrs <> invalid and attrs.channel <> invalid then
            startT = xmltvToEpoch(attrs.start)
            stopT = xmltvToEpoch(attrs.stop)
            if stopT > fromT and startT < toT then
                title = ""
                titles = prog.GetNamedElements("title")
                if titles.Count() > 0 then title = titles[0].GetText()
                if title <> "" then
                    ch = attrs.channel
                    if byChannel[ch] = invalid then byChannel[ch] = []
                    byChannel[ch].Push({ start: startT, stop: stopT, title: title })
                end if
            end if
        end if
    end for

    for each ch in byChannel
        list = byChannel[ch]
        for i = 1 to list.Count() - 1
            item = list[i]
            j = i - 1
            while j >= 0 and list[j].start > item.start
                list[j + 1] = list[j]
                j = j - 1
            end while
            list[j + 1] = item
        end for
    end for
    return byChannel
end function

' XMLTV stamps look like "20260814200644 -0500". roDateTime only reads ISO8601
' in UTC, so the conversion is done by hand.
function xmltvToEpoch(s as Dynamic) as Integer
    if s = invalid then return 0
    t = s.Trim()
    if t.Len() < 14 then return 0
    y = Mid(t, 1, 4).ToInt()
    mo = Mid(t, 5, 2).ToInt()
    d = Mid(t, 7, 2).ToInt()
    h = Mid(t, 9, 2).ToInt()
    mi = Mid(t, 11, 2).ToInt()
    sec = Mid(t, 13, 2).ToInt()

    offset = 0
    if t.Len() >= 20 then
        tz = Mid(t, 16, 5)
        sign = 1
        if Left(tz, 1) = "-" then sign = -1
        offset = sign * (Mid(tz, 2, 2).ToInt() * 3600 + Mid(tz, 4, 2).ToInt() * 60)
    end if
    return daysFromCivil(y, mo, d) * 86400 + h * 3600 + mi * 60 + sec - offset
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
