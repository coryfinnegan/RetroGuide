package com.ersatz.retroguide

import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.res.ResourcesCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.ersatz.retroguide.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.lifecycle.lifecycleScope
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var ui: ActivityMainBinding
    private var player: ExoPlayer? = null

    private var channels: List<Channel> = emptyList()
    private var guide: Map<String, List<Programme>> = emptyMap()
    private var current = 0
    private var guideOpen = false
    private var settingsOpen = false
    private var settingsCursor = 0

    /** Half hours the guide is paged forward. */
    private var page = 0
    private var theme = GuideTheme.CLASSIC
    /** The theme's logo and clock typeface, or null for bold monospace. */
    private var face: Typeface? = null

    /**
     * The guide cursor: a row, and a moment in time on it. The highlighted
     * block is whichever one covers that moment, so moving up and down keeps
     * the cursor in the same part of the schedule, as a cable box does.
     */
    private var selRow = 0
    private var selTime = 0L
    /** First row on screen. */
    private var gridTop = 0

    private val handler = Handler(Looper.getMainLooper())
    private val hideBanner = Runnable { ui.banner.visibility = View.GONE }

    /** Digits typed on the remote, e.g. "6" then "6" tunes channel 66. */
    private val typed = StringBuilder()
    private val commitTyped = Runnable { tuneToNumber(typed.toString()); typed.clear() }

    /** The start of the window the grid was last drawn for. */
    private var paintedSlot = 0L

    private val tick = object : Runnable {
        override fun run() {
            paintClock()
            // The blocks only move when the half hour rolls over, so only
            // redraw the grid then. The cursor is never moved by a redraw.
            if (guideOpen && window().a != paintedSlot) {
                selTime = maxOf(selTime, window().a)
                paintGrid()
            }
            handler.postDelayed(this, 30_000)
        }
    }

    /** True when the server was unreachable and OK should reopen setup. */
    private var awaitingSetup = false

    /** Set at launch when the channel list differs from the last run. */
    private var lineupChanged = false

    /** onStart fires straight after onCreate; the first one has nothing to do. */
    private var started = false
    private var leftAt = 0L

    /** Opens the stream for whatever channel the viewer has settled on. */
    private val openStream = Runnable { startStream() }
    private var retries = 0

    private companion object {
        /**
         * How long to let the channel number settle before opening a stream.
         *
         * ErsatzTV starts an ffmpeg process per request, so surfing with one
         * request per keypress leaves a queue of them starting and tearing
         * down at once, and a stream that has not begun emitting yet answers
         * with bytes that are not a valid container. Waiting for the viewer to
         * stop pressing means one request per channel actually landed on,
         * which is also how a real cable box behaves - the banner moves at
         * once, the picture follows.
         */
        const val TUNE_SETTLE_MS = 400L
        const val RETRY_MS = 1200L
        const val MAX_RETRIES = 2

        /**
         * Away longer than this and the lineup and guide are re-read on return.
         * Shorter than this only the stream is reopened.
         */
        const val STALE_AFTER_MS = 5 * 60_000L

        /** How far forward the guide can be paged, in half hours. */
        const val MAX_PAGES = 16
    }

    // ------------------------------------------------------------ settings

    private val settingsRows by lazy {
        listOf(ui.settingTheme, ui.settingServer, ui.settingRestart, ui.settingClose, ui.settingBack)
    }
    private fun settingsLabel(i: Int) = when (i) {
        0 -> "THEME   ◀ ${theme.label} ▶"
        1 -> "CHANGE SERVER ADDRESS"
        2 -> "RESTART APP"
        3 -> "CLOSE APP"
        else -> "BACK TO TV"
    }

    private fun openSettings() {
        settingsOpen = true
        settingsCursor = 0
        ui.settingsOverlay.visibility = View.VISIBLE
        ui.banner.visibility = View.GONE
        paintSettings()
    }

    private fun closeSettings() {
        settingsOpen = false
        ui.settingsOverlay.visibility = View.GONE
    }

    private fun paintSettings() {
        settingsRows.forEachIndexed { i, row ->
            val on = i == settingsCursor
            row.text = settingsLabel(i)
            row.typeface = GRID_FACE
            row.background = plate(if (on) theme.sel else theme.cell)
            row.setTextColor(if (on) theme.selInk else theme.cellInk)
        }
    }

    /** Step through the themes, save the choice, and repaint in it at once. */
    private fun cycleTheme(step: Int) {
        val all = GuideTheme.ALL
        val i = all.indexOf(theme).coerceAtLeast(0)
        theme = all[(i + step + all.size) % all.size]
        Prefs.setTheme(this, theme.key)
        applyTheme()
        paintSettings()
    }

    /** An embossed plate, the shape every guide surface is made of. */
    private fun plate(fill: Int) = Emboss.Plate(fill, 2 * resources.displayMetrics.density)

    /**
     * Dress the screen in the current theme: surfaces, colours, logo, the
     * typefaces, and the theme's art.
     */
    private fun applyTheme() {
        face = if (theme.font != 0) ResourcesCompat.getFont(this, theme.font) else null
        val brand = face ?: Typeface.MONOSPACE
        val dp = resources.displayMetrics.density
        ui.guideOverlay.setBackgroundColor(theme.bg)
        ui.settingsOverlay.setBackgroundColor(theme.bg)

        ui.infoPanel.background = plate(theme.panel)
        ui.panelRule.setBackgroundColor(theme.rule)
        ui.windowFrame.background = GradientDrawable().apply {
            setColor(0xFF000000.toInt()); setStroke((3 * dp).toInt(), theme.frame)
        }
        ui.logoArt.visibility = if (theme.logoArt != 0) View.VISIBLE else View.GONE
        if (theme.logoArt != 0) ui.logoArt.setImageResource(theme.logoArt)
        ui.logoA.text = theme.logoA
        ui.logoB.text = theme.logoB
        ui.logoA.setTextColor(theme.logoAInk)
        ui.logoB.setTextColor(theme.logoBInk)
        ui.clock.setTextColor(theme.clock)
        listOf(ui.logoA, ui.logoB, ui.clock).forEach { it.setTypeface(brand, Typeface.BOLD) }

        ui.infoTitle.setTextColor(theme.titleInk)
        ui.infoTime.setTextColor(theme.accentInk)
        ui.infoChannel.setTextColor(theme.infoInk)
        ui.infoNext.setTextColor(theme.infoInk)
        ui.infoTitle.typeface = GRID_FACE
        listOf(ui.infoTime, ui.infoChannel, ui.infoNext)
            .forEach { it.typeface = Typeface.create("sans-serif-condensed", Typeface.NORMAL) }

        ui.guideFooter.background = plate(theme.foot)
        ui.guideFooter.setTextColor(theme.footInk)
        ui.guideFooter.typeface = GRID_FACE
        ui.guideFooter.text = footerHints()

        ui.banner.background = plate(theme.panel)
        ui.bannerNumber.setTextColor(theme.numInk)
        ui.bannerName.setTextColor(theme.titleInk)
        ui.bannerNow.setTextColor(theme.accentInk)
        listOf(ui.bannerNumber, ui.bannerName).forEach { it.setTypeface(GRID_FACE, Typeface.BOLD) }
        ui.bannerNow.typeface = GRID_FACE

        ui.settingsTitle.setTextColor(theme.logoAInk)
        ui.settingsTitle.setTypeface(brand, Typeface.BOLD)
        ui.settingsHint.setTextColor(theme.infoInk)
        ui.settingsHint.typeface = GRID_FACE
        ui.status.setTextColor(theme.clock)
        ui.status.typeface = GRID_FACE

        ui.decoLayer.removeAllViews()
        for (d in theme.deco) {
            ui.decoLayer.addView(ImageView(this).apply {
                setImageResource(d.drawable)
                scaleType = ImageView.ScaleType.FIT_CENTER
            }, FrameLayout.LayoutParams((d.sizeDp * dp).toInt(), (d.sizeDp * dp).toInt(), d.gravity).apply {
                setMargins((d.xDp * dp).toInt(), (d.yDp * dp).toInt(), (d.xDp * dp).toInt(), (d.yDp * dp).toInt())
            })
        }
        if (guideOpen) { paintGrid(); paintInfo() }
    }

    /** The key-hint bar, each key behind a dot in a remote's button colours. */
    private fun footerHints(): CharSequence {
        val hints = listOf(
            0xFF4CD964.toInt() to "OK  Watch",
            0xFFFFD60A.toInt() to "◀ ▶  Time",
            0xFF0A84FF.toInt() to "▲ ▼  Channels",
            0xFFFF453A.toInt() to "HOLD OK  Settings",
            0xFFB0B0B0.toInt() to "BACK  Close",
        )
        val sb = SpannableStringBuilder()
        hints.forEachIndexed { i, (dot, text) ->
            if (i > 0) sb.append("      ")
            val s = sb.length
            sb.append("● ")
            sb.setSpan(ForegroundColorSpan(dot), s, s + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.append(text)
        }
        return sb
    }

    private fun chooseSetting() {
        when (settingsCursor) {
            0 -> cycleTheme(1)
            1 -> openSetup()
            2 -> {
                // A fresh process is the quickest cure for a wedged stream.
                val intent = packageManager.getLaunchIntentForPackage(packageName)
                intent?.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
                finishAffinity()
                startActivity(intent)
            }
            3 -> finishAffinity()
            else -> closeSettings()
        }
    }

    private fun openSetup() {
        startActivity(Intent(this, SetupActivity::class.java))
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = ActivityMainBinding.inflate(layoutInflater)
        setContentView(ui.root)

        // Watching TV involves no button presses, so the device counts it as
        // idle and starts the screensaver over the picture. Nothing else here
        // holds the screen awake; a player has to say so itself.
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        theme = GuideTheme.of(Prefs.theme(this))
        applyTheme()

        // No server configured yet - send the user to setup rather than failing.
        if (!Prefs.apply(this)) {
            startActivity(Intent(this, SetupActivity::class.java))
            finish()
            return
        }

        ui.status.text = "TUNING…"
        load()
        handler.post(tick)
    }

    /**
     * Channels first, picture next, guide last.
     *
     * The guide is a 7 MB download that takes the TV seconds to read, and it
     * used to be read before anything played, so every launch sat on TUNING.
     * Nothing about playback needs it - the banner and the guide fill in when
     * it lands.
     */
    private fun load() {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { Source.loadChannels() } }
            result.onSuccess { ch ->
                channels = ch
                if (ch.isEmpty()) {
                    ui.status.text = "NO CHANNELS AT ${Source.host}"
                    return@onSuccess
                }
                ui.status.visibility = View.GONE
                reportLineupChange(ch)
                startPlayer()
                val resume = Prefs.lastChannel(this@MainActivity)
                    ?.let { n -> ch.indexOfFirst { it.number == n } }
                    ?.takeIf { it >= 0 } ?: 0
                tune(resume)
                // Opened while the lineup was still loading: put the cursor
                // on the channel now that there is one.
                if (guideOpen) openGuide()
                loadGuide()
            }.onFailure {
                // A server that has moved is the common case here, so offer the
                // fix rather than just reporting the failure.
                ui.status.visibility = View.VISIBLE
                ui.status.text = "CANNOT REACH ${Source.host}\n" +
                        (it.message ?: "") + "\n\nPress OK to change the server address."
                awaitingSetup = true
            }
        }
    }

    /** Read the listings in the background and repaint whatever shows them. */
    private fun loadGuide() {
        lifecycleScope.launch {
            // A missing guide should not stop playback - channels matter more.
            val g = withContext(Dispatchers.IO) {
                runCatching { Source.loadGuide() }.getOrDefault(emptyMap())
            }
            if (g.isEmpty()) return@launch
            guide = g
            if (guideOpen) { paintGrid(); paintInfo() }
            else channels.getOrNull(current)?.let { ch ->
                if (ui.banner.visibility == View.VISIBLE) showBanner(ch)
            }
        }
    }

    /**
     * Notice a lineup change once per launch, rather than diffing lists. The
     * channel list is deliberately only read at startup - swapping channels
     * underneath someone who is watching is worse than a stale list.
     */
    private fun reportLineupChange(channels: List<Channel>) {
        val signature = Prefs.signatureOf(channels)
        val previous = Prefs.lineupSignature(this)
        lineupChanged = previous != null && previous != signature
        Prefs.setLineupSignature(this, signature)
    }

    private fun startPlayer() {
        player = ExoPlayer.Builder(this).build().apply {
            playWhenReady = true
            repeatMode = Player.REPEAT_MODE_OFF
            // Without this a stream that fails to open just shows black, with
            // nothing on screen or in the log to say why.
            addListener(object : Player.Listener {
                override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                    val ch = channels.getOrNull(current)
                    // These are nearly always transient - the server was still
                    // bringing the stream up - so ask again before giving the
                    // viewer an error to read.
                    if (retries < MAX_RETRIES) {
                        retries++
                        showBannerText(ch?.number ?: "", ch?.name ?: "CHANNEL", "TUNING…")
                        handler.removeCallbacks(openStream)
                        handler.postDelayed(openStream, RETRY_MS)
                    } else {
                        showBannerText(
                            ch?.number ?: "",
                            ch?.name ?: "CHANNEL",
                            "NO SIGNAL — ${error.errorCodeName}"
                        )
                    }
                }
            })
        }
        // The guide may already be open if it was asked for while the lineup
        // loaded; the picture belongs in its corner window then, not behind it.
        if (guideOpen) ui.windowPlayer.player = player else ui.fullPlayer.player = player
    }

    private fun tune(index: Int) {
        if (channels.isEmpty()) return
        current = ((index % channels.size) + channels.size) % channels.size
        val ch = channels[current]
        Prefs.setLastChannel(this, ch.number)
        showBanner(ch)
        if (guideOpen) paintGrid()
        // The banner follows the remote immediately; the stream waits until
        // the viewer stops surfing. Also cancels a pending retry for the
        // channel we are leaving.
        retries = 0
        handler.removeCallbacks(openStream)
        handler.postDelayed(openStream, TUNE_SETTLE_MS)
    }

    private fun startStream() {
        val ch = channels.getOrNull(current) ?: return
        player?.apply {
            // Drop the previous stream before asking for the next one, so the
            // server can retire its ffmpeg rather than holding both open.
            stop()
            clearMediaItems()
            setMediaItem(MediaItem.fromUri(ch.url))
            prepare()
            play()
        }
    }

    private fun tuneToNumber(number: String) {
        val i = channels.indexOfFirst { it.number == number }
        if (i >= 0) tune(i) else showBannerText(number, "NO SUCH CHANNEL", "")
    }

    private fun programmeAt(ch: Channel, t: Long): Programme? =
        ch.tvgId?.let { guide[it] }?.at(t)

    private fun nowOn(ch: Channel): Programme? = programmeAt(ch, System.currentTimeMillis())

    private fun showBanner(ch: Channel) {
        val now = nowOn(ch)
        // Say so once, on the first banner after the lineup changed.
        if (lineupChanged) {
            lineupChanged = false
            showBannerText(ch.number, ch.name, "CHANNEL LIST UPDATED — ${channels.size} CHANNELS")
            return
        }
        showBannerText(ch.number, ch.name, now?.let {
            "${shortTime(it.start)} – ${shortTime(it.stop)}   ${it.title}"
        } ?: "")
    }

    private fun showBannerText(number: String, name: String, sub: String) {
        ui.bannerNumber.text = number
        ui.bannerName.text = name
        ui.bannerNow.text = sub
        ui.banner.visibility = View.VISIBLE
        handler.removeCallbacks(hideBanner)
        handler.postDelayed(hideBanner, 4000)
    }

    // ---------------------------------------------------------------- guide

    /** Ninety minutes from the current half hour, paged forward by [page]. */
    private fun window() = GuideWindow.at(System.currentTimeMillis(), page)

    private fun paintClock() {
        ui.clock.text = SimpleDateFormat("h:mm a", Locale.US).format(Date())
    }

    private fun openGuide() {
        guideOpen = true
        page = 0
        selRow = current
        selTime = System.currentTimeMillis()
        paintClock()
        // Move the video into the corner window, exactly like the original channel.
        ui.fullPlayer.player = null
        ui.windowPlayer.player = player
        ui.guideOverlay.visibility = View.VISIBLE
        // Rows that fit are only known once the grid has been laid out.
        ui.guideGrid.post {
            gridTop = selRow - 1
            keepCursorOnScreen()
            paintGrid()
            paintInfo()
        }
        paintGrid()
        paintInfo()
    }

    private fun closeGuide() {
        guideOpen = false
        ui.guideOverlay.visibility = View.GONE
        ui.windowPlayer.player = null
        ui.fullPlayer.player = player
    }

    private fun paintGrid() {
        paintedSlot = GuideWindow.at(System.currentTimeMillis(), 0).a
        ui.guideGrid.set(channels, guide, window(), gridTop, selRow, selTime, current, theme)
    }

    /** Scroll by whole rows so the cursor row is on screen. */
    private fun keepCursorOnScreen() {
        val n = ui.guideGrid.visibleRows()
        if (selRow < gridTop) gridTop = selRow
        if (selRow >= gridTop + n) gridTop = selRow - n + 1
        gridTop = gridTop.coerceIn(0, maxOf(0, channels.size - n))
    }

    /** Fill the info panel from the highlighted programme. */
    private fun paintInfo() {
        val ch = channels.getOrNull(selRow) ?: return
        val blk = ui.guideGrid.blockAt(selRow, selTime, window())
        val at = blk?.let { maxOf(selTime, it.start) } ?: selTime
        val p = programmeAt(ch, at)
        ui.infoTitle.text = p?.title ?: blk?.title ?: ch.name
        ui.infoChannel.text = "${ch.number}   ${ch.name.uppercase(Locale.US)}"
        val now = System.currentTimeMillis()
        ui.infoTime.text = when {
            p == null -> ""
            now in p.start until p.stop -> "${shortTime(p.start)} – ${shortTime(p.stop)}   ON NOW"
            else -> SimpleDateFormat("EEE", Locale.US).format(Date(p.start)) +
                    "  ${shortTime(p.start)} – ${shortTime(p.stop)}"
        }
        val next = p?.let { cur -> ch.tvgId?.let { guide[it] }?.firstOrNull { it.start >= cur.stop } }
        ui.infoNext.text = next?.let { "NEXT  ${shortTime(it.start)}   ${it.title}" } ?: ""
    }

    /** Up and down: another channel, same moment. Wraps at the ends. */
    private fun moveRow(delta: Int) {
        if (channels.isEmpty()) return
        selRow = (selRow + delta + channels.size) % channels.size
        keepCursorOnScreen()
        paintGrid(); paintInfo()
    }

    /**
     * Left and right: the previous or next programme on this row, paging the
     * grid half an hour when the cursor runs off either end.
     */
    private fun moveTime(delta: Int) {
        val win = window()
        val bs = ui.guideGrid.blocksFor(selRow, win)
        val cur = ui.guideGrid.blockAt(selRow, selTime, win)
        val i = bs.indexOf(cur)
        if (delta < 0) {
            if (i > 0) selTime = bs[i - 1].start
            else if (page > 0) { page--; selTime = win.a - 1 }
            else return
        } else {
            if (i in 0 until bs.size - 1) selTime = bs[i + 1].start
            else if (page < MAX_PAGES) { page++; selTime = maxOf(cur?.stop ?: win.b, window().a) }
            else return
        }
        // Paging back lands on the last block; keep the cursor time inside it.
        ui.guideGrid.blockAt(selRow, selTime, window())?.let { b ->
            if (selTime < b.start || selTime >= b.stop) selTime = b.start
        }
        paintGrid(); paintInfo()
    }

    // ---------------------------------------------------------------- remote

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        // Digits tune directly, whether or not the guide is open.
        if (keyCode in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9) {
            typed.append(keyCode - KeyEvent.KEYCODE_0)
            showBannerText(typed.toString(), "…", "")
            handler.removeCallbacks(commitTyped)
            handler.postDelayed(commitTyped, 1500)
            return true
        }
        if (awaitingSetup) {
            if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
                openSetup(); return true
            }
        }
        if (settingsOpen) {
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> {
                    settingsCursor = (settingsCursor - 1 + settingsRows.size) % settingsRows.size
                    paintSettings()
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    settingsCursor = (settingsCursor + 1) % settingsRows.size
                    paintSettings()
                }
                KeyEvent.KEYCODE_DPAD_LEFT -> if (settingsCursor == 0) cycleTheme(-1)
                KeyEvent.KEYCODE_DPAD_RIGHT -> if (settingsCursor == 0) cycleTheme(1)
                // Repeats are the tail of the hold that opened settings.
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER ->
                    if (event.repeatCount == 0 && !okHeld) chooseSetting()
                KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_SETTINGS,
                KeyEvent.KEYCODE_PROG_RED -> closeSettings()
            }
            return true
        }

        if (guideOpen) {
            when (keyCode) {
                KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_GUIDE -> closeGuide()
                KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_SETTINGS,
                KeyEvent.KEYCODE_PROG_RED -> { closeGuide(); openSettings() }
                KeyEvent.KEYCODE_DPAD_UP -> moveRow(-1)
                KeyEvent.KEYCODE_DPAD_DOWN -> moveRow(1)
                KeyEvent.KEYCODE_CHANNEL_UP -> moveRow(-ui.guideGrid.visibleRows())
                KeyEvent.KEYCODE_CHANNEL_DOWN -> moveRow(ui.guideGrid.visibleRows())
                KeyEvent.KEYCODE_DPAD_LEFT -> moveTime(-1)
                KeyEvent.KEYCODE_DPAD_RIGHT -> moveTime(1)
                // Acted on when released - see onKeyUp - so a hold can mean settings.
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> trackOk(event)
                else -> return super.onKeyDown(keyCode, event)
            }
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            trackOk(event); return true
        }
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP -> { tune(current - 1); return true }
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> { tune(current + 1); return true }
            // BACK opens the guide, the way a cable box's guide button does.
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_GUIDE -> { openGuide(); return true }
            // MENU, not SETTINGS: Google TV swallows KEYCODE_SETTINGS for its
            // own panel, so the app never sees it.
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_SETTINGS,
            KeyEvent.KEYCODE_PROG_RED -> { openSettings(); return true }
            KeyEvent.KEYCODE_INFO -> { channels.getOrNull(current)?.let { showBanner(it) }; return true }
        }
        return super.onKeyDown(keyCode, event)
    }

    /**
     * OK does double duty, because the Google TV remote has no menu button:
     * a press does the usual thing, a hold opens SETTINGS. So the press is
     * only acted on when it is released, and only if it was not held.
     */
    private var okHeld = false

    private fun trackOk(event: KeyEvent) {
        if (event.repeatCount == 0) { okHeld = false; event.startTracking() }
    }

    override fun onKeyLongPress(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode != KeyEvent.KEYCODE_DPAD_CENTER && keyCode != KeyEvent.KEYCODE_ENTER) {
            return super.onKeyLongPress(keyCode, event)
        }
        okHeld = true
        if (!settingsOpen) {
            if (guideOpen) closeGuide()
            openSettings()
        }
        return true
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode != KeyEvent.KEYCODE_DPAD_CENTER && keyCode != KeyEvent.KEYCODE_ENTER) {
            return super.onKeyUp(keyCode, event)
        }
        if (okHeld) { okHeld = false; return true }
        if (!event.isTracking || event.isCanceled || settingsOpen || awaitingSetup) return true
        if (guideOpen) {
            if (selRow != current) tune(selRow)
            closeGuide()
        } else {
            // OK is channel info, not the guide.
            channels.getOrNull(current)?.let { showBanner(it) }
        }
        return true
    }

    override fun onBackPressed() {
        // Reached only when BACK arrives outside the key path. Mirror it there
        // so the app never exits out from under a programme.
        if (guideOpen) closeGuide() else openGuide()
    }

    /**
     * Android keeps the activity alive when the app is "closed", so onCreate
     * does not run again on reopening. Without this the player sat on a stream
     * the server retired hours ago - a frozen frame - and the guide still held
     * programmes for a window that had long since passed, which is why it read
     * as empty while changing channel worked fine.
     */
    override fun onStart() {
        super.onStart()
        if (!started) {
            started = true
            return
        }
        if (channels.isEmpty()) {
            load()
        } else if (System.currentTimeMillis() - leftAt > STALE_AFTER_MS) {
            refreshOnReturn()
        } else {
            startStream()
        }
    }

    /** Re-read the lineup and guide, then reopen the stream. */
    private fun refreshOnReturn() {
        val playing = channels.getOrNull(current)?.number
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val ch = Source.loadChannels()
                    val g = runCatching { Source.loadGuide() }.getOrDefault(emptyMap())
                    ch to g
                }
            }
            result.onSuccess { (ch, g) ->
                if (ch.isNotEmpty()) {
                    channels = ch
                    guide = g
                    // Follow the channel by number: its index moves when
                    // channels are added above it.
                    playing?.let { n -> ch.indexOfFirst { it.number == n } }
                        ?.takeIf { it >= 0 }
                        ?.let { current = it }
                    reportLineupChange(ch)
                    if (guideOpen) { paintGrid(); paintInfo() }
                }
                startStream()
            }.onFailure {
                // Whatever went wrong, at least get the picture back.
                startStream()
            }
        }
    }

    override fun onStop() {
        super.onStop()
        leftAt = System.currentTimeMillis()
        player?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        player?.release(); player = null
    }
}
