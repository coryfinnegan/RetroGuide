' Retro Guide - entry point.
'
' Nothing interesting happens here: build the scene, hand it the screen, and
' pump messages until the user leaves. All behaviour lives in MainScene.

sub Main()
    screen = CreateObject("roSGScreen")
    port = CreateObject("roMessagePort")
    screen.setMessagePort(port)
    screen.CreateScene("MainScene")
    screen.show()

    while true
        msg = wait(0, port)
        if type(msg) = "roSGScreenEvent" then
            if msg.isScreenClosed() then return
        end if
    end while
end sub
