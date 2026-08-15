' Finds ErsatzTV on the local network.
'
' Sweeps this device's own /24 for the ErsatzTV port and confirms each hit by
' asking it for its version, so an unrelated service on the same port cannot be
' mistaken for a server.

sub init()
    m.top.functionName = "run"
end sub

sub run()
    ETV_PORT = 8409    ' note: not PORT - identifiers are case insensitive here, so it
                       ' would be the same variable as the message port below
    BATCH = 32          ' concurrent requests; a streaming box is not a scanner
    WAIT_MS = 2500      ' generous for a LAN, and the whole sweep is 8 batches

    found = []
    base = subnetOf(localIp())
    if base = "" then
        m.top.found = { hosts: found, done: true }
        return
    end if

    msgPort = CreateObject("roMessagePort")
    host = 1
    while host <= 254
        live = {}
        upper = host + BATCH - 1
        if upper > 254 then upper = 254
        for i = host to upper
            ip = base + StrI(i).Trim()
            xfer = CreateObject("roUrlTransfer")
            xfer.SetPort(msgPort)
            xfer.SetUrl("http://" + ip + ":" + StrI(ETV_PORT).Trim() + "/api/version")
            if xfer.AsyncGetToString() then
                live[StrI(xfer.GetIdentity()).Trim()] = { ip: ip, xfer: xfer }
            end if
        end for

        clock = CreateObject("roTimespan")
        while live.Count() > 0 and clock.TotalMilliseconds() < WAIT_MS
            msg = wait(250, msgPort)
            if type(msg) = "roUrlEvent" then
                id = StrI(msg.GetSourceIdentity()).Trim()
                entry = live[id]
                if entry <> invalid then
                    live.Delete(id)
                    ' Only ErsatzTV answers this path with a version string.
                    if msg.GetResponseCode() = 200 and Instr(1, msg.GetString(), "v") = 1 then
                        found.Push(entry.ip + ":" + StrI(ETV_PORT).Trim())
                    end if
                end if
            end if
        end while

        for each id in live
            live[id].xfer.AsyncCancel()
        end for
        host = upper + 1
    end while

    m.top.found = { hosts: found, done: true }
end sub

function localIp() as String
    di = CreateObject("roDeviceInfo")
    addrs = di.GetIPAddrs()
    if addrs <> invalid then
        for each iface in addrs
            ip = addrs[iface]
            if ip <> invalid and ip <> "" and Left(ip, 4) <> "127." then return ip
        end for
    end if
    return ""
end function

function subnetOf(ip as String) as String
    if ip = "" then return ""
    parts = ip.Tokenize(".")
    if parts.Count() < 4 then return ""
    return parts[0] + "." + parts[1] + "." + parts[2] + "."
end function
