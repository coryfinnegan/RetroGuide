package com.ersatz.retroguide

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
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

    private val handler = Handler(Looper.getMainLooper())
    private val hideBanner = Runnable { ui.banner.visibility = View.GONE }

    /** Digits typed on the remote, e.g. "6" then "6" tunes channel 66. */
    private val typed = StringBuilder()
    private val commitTyped = Runnable { tuneToNumber(typed.toString()); typed.clear() }

    /** The half-hour the guide columns were last drawn for. */
    private var paintedSlot = 0L

    private val tick = object : Runnable {
        override fun run() {
            ui.clock.text = SimpleDateFormat("h:mm a", Locale.US).format(Date())
            // Rebinding the rows takes focus off whatever row the cursor is on,
            // and doing that every 30 seconds made the guide look like it had
            // stopped scrolling - the next press would start again from the top.
            // The cells only change when the half hour rolls over, so only then.
            if (guideOpen && slots()[0] != paintedSlot) refreshGuideRows()
            handler.postDelayed(this, 30_000)
        }
    }

    /** Redraw the rows, putting the cursor back where the viewer left it. */
    private fun refreshGuideRows() {
        paintedSlot = slots()[0]
        val focused = ui.guideList.focusedChild
            ?.let { ui.guideList.getChildAdapterPosition(it) }
            ?.takeIf { it != RecyclerView.NO_POSITION }
        adapter?.notifyDataSetChanged()
        focused?.let { focusGuideRow(it) }
    }

    private var adapter: GuideAdapter? = null

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

        /** Matches @style/GuideCell; a merged cell takes this times its span. */
        const val CELL_WEIGHT = 2f
    }

    // ------------------------------------------------------------ settings

    private val settingsRows by lazy {
        listOf(ui.settingServer, ui.settingRestart, ui.settingClose, ui.settingBack)
    }
    private val settingsLabels = listOf(
        "CHANGE SERVER ADDRESS", "RESTART APP", "CLOSE APP", "BACK TO TV"
    )

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
            row.text = settingsLabels[i]
            row.setBackgroundColor(
                ContextCompat.getColor(
                    this, if (i == settingsCursor) R.color.guide_sel else R.color.guide_row_b
                )
            )
            row.setTextColor(
                ContextCompat.getColor(
                    this, if (i == settingsCursor) R.color.guide_cyan else R.color.guide_white
                )
            )
        }
    }

    private fun chooseSetting() {
        when (settingsCursor) {
            0 -> openSetup()
            1 -> {
                // A fresh process is the quickest cure for a wedged stream.
                val intent = packageManager.getLaunchIntentForPackage(packageName)
                intent?.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
                finishAffinity()
                startActivity(intent)
            }
            2 -> finishAffinity()
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

        ui.guideList.layoutManager = LinearLayoutManager(this)

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

    private fun load() {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val ch = Source.loadChannels()
                    // A missing guide should not stop playback - channels matter more.
                    val g = runCatching { Source.loadGuide() }.getOrDefault(emptyMap())
                    ch to g
                }
            }
            result.onSuccess { (ch, g) ->
                channels = ch; guide = g
                if (ch.isEmpty()) {
                    ui.status.text = "NO CHANNELS AT ${Source.host}"
                    return@onSuccess
                }
                ui.status.visibility = View.GONE
                reportLineupChange(ch)
                adapter = GuideAdapter().also { ui.guideList.adapter = it }
                startPlayer()
                val resume = Prefs.lastChannel(this@MainActivity)
                    ?.let { n -> ch.indexOfFirst { it.number == n } }
                    ?.takeIf { it >= 0 } ?: 0
                tune(resume)
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
        ui.fullPlayer.player = player
    }

    private fun tune(index: Int) {
        if (channels.isEmpty()) return
        current = ((index % channels.size) + channels.size) % channels.size
        val ch = channels[current]
        Prefs.setLastChannel(this, ch.number)
        showBanner(ch)
        adapter?.notifyDataSetChanged()
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

    private fun nowOn(ch: Channel): Programme? =
        ch.tvgId?.let { guide[it] }?.at(System.currentTimeMillis())

    private fun showBanner(ch: Channel) {
        val now = nowOn(ch)
        // Say so once, on the first banner after the lineup changed.
        if (lineupChanged) {
            lineupChanged = false
            showBannerText(ch.number, ch.name, "CHANNEL LIST UPDATED — ${channels.size} CHANNELS")
            return
        }
        showBannerText(ch.number, ch.name, now?.let {
            "${hhmm(it.start)}–${hhmm(it.stop)}  ${it.title}"
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

    /** Half-hour slot boundaries starting at the current half hour. */
    private fun slots(): List<Long> {
        val now = System.currentTimeMillis()
        val half = 30 * 60 * 1000L
        val base = now - (now % half)
        return listOf(base, base + half, base + 2 * half, base + 3 * half)
    }

    private fun openGuide() {
        guideOpen = true
        val s = slots()
        ui.slot0.text = hhmm(s[0]); ui.slot1.text = hhmm(s[1]); ui.slot2.text = hhmm(s[2])
        ui.clock.text = SimpleDateFormat("h:mm a", Locale.US).format(Date())
        channels.getOrNull(current)?.let { ch ->
            val p = nowOn(ch)
            ui.nowPlaying.text = "${ch.number}  ${ch.name}\n" + (p?.title ?: "")
        }
        // Move the video into the corner window, exactly like the original channel.
        ui.fullPlayer.player = null
        ui.windowPlayer.player = player
        ui.guideOverlay.visibility = View.VISIBLE
        adapter?.notifyDataSetChanged()
        paintedSlot = slots()[0]
        focusGuideRow(current)
    }

    private fun closeGuide() {
        guideOpen = false
        ui.guideOverlay.visibility = View.GONE
        ui.windowPlayer.player = null
        ui.fullPlayer.player = player
    }

    inner class GuideAdapter : RecyclerView.Adapter<GuideAdapter.VH>() {
        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val root: LinearLayout = v.findViewById(R.id.rowRoot)
            val no: TextView = v.findViewById(R.id.chNo)
            val name: TextView = v.findViewById(R.id.chName)
            val cells = listOf<TextView>(
                v.findViewById(R.id.cell0), v.findViewById(R.id.cell1), v.findViewById(R.id.cell2)
            )
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.row_channel, parent, false))

        override fun getItemCount() = channels.size

        /**
         * Fill a row's three half-hour cells, merging any that hold the same
         * programme.
         *
         * Each column used to ask independently what was on at that half hour,
         * so a two hour film answered for all three and read as three separate
         * half hour programmes. Merging keeps the columns on clean half hours -
         * the grid stays scannable, and most of a lineup is short enough to
         * occupy a single column anyway - while making length visible from the
         * width of the block.
         */
        private fun paintCells(h: VH, progs: List<Programme>, s: List<Long>) {
            val found = (0..2).map { progs.fromSlot(s[it], s[it + 1]) }
            var i = 0
            while (i <= 2) {
                val p = found[i]
                var span = 1
                while (i + span <= 2) {
                    val q = found[i + span]
                    if (p == null || q == null || q.start != p.start) break
                    span++
                }
                val cell = h.cells[i]
                cell.visibility = View.VISIBLE
                // A GONE view is left out of the weight sum, so the merged
                // cell simply takes the share of every column it covers.
                cell.layoutParams = (cell.layoutParams as LinearLayout.LayoutParams)
                    .apply { weight = CELL_WEIGHT * span }
                cell.text = p?.title ?: "—"
                for (k in i + 1 until i + span) h.cells[k].visibility = View.GONE
                i += span
            }
        }

        override fun onBindViewHolder(h: VH, position: Int) {
            val ch = channels[position]
            val s = slots()
            h.no.text = ch.number
            h.name.text = ch.name
            paintCells(h, ch.tvgId?.let { guide[it] } ?: emptyList(), s)
            h.name.setTextColor(
                ContextCompat.getColor(
                    this@MainActivity,
                    if (position == current) R.color.guide_cyan else R.color.guide_white
                )
            )
            h.root.setOnClickListener { tune(position); closeGuide() }
            // Rows are recycled, so repaint from the holder's live position
            // rather than the position captured when this listener was made.
            h.root.setOnFocusChangeListener { v, has ->
                paintRow(v, h.bindingAdapterPosition, has)
            }
            paintRow(h.root, position, h.root.hasFocus())
        }
    }

    /**
     * Colour one guide row.
     *
     * Losing focus previously left the row painted as selected, so scrolling
     * down the guide dragged a trail of highlighted rows behind it. Focus is
     * the only thing that draws the bright background now; the tuned channel
     * keeps a dimmer tint of its own plus the cyan name.
     */
    private fun paintRow(row: View, position: Int, focused: Boolean) {
        row.alpha = if (focused) 1f else 0.82f
        row.setBackgroundColor(
            ContextCompat.getColor(
                this, when {
                    focused -> R.color.guide_sel
                    position == current -> R.color.guide_onair
                    position % 2 == 0 -> R.color.guide_row_a
                    else -> R.color.guide_row_b
                }
            )
        )
    }

    /**
     * Wrap the guide cursor around at the ends of the list.
     *
     * Note this has to decide for itself whether we are at an edge. The
     * activity sees DPAD keys *before* the framework runs its focus search, so
     * consuming them unconditionally would replace normal row-to-row movement
     * rather than extending it. Returning false leaves the press to the
     * framework, which is the common case.
     */
    private fun wrapGuideFocus(delta: Int): Boolean {
        val count = channels.size
        if (count == 0) return false
        val focused = ui.guideList.focusedChild
        val pos = focused?.let { ui.guideList.getChildAdapterPosition(it) }
            ?: RecyclerView.NO_POSITION
        if (pos == RecyclerView.NO_POSITION) {
            // Nothing focused: recover to the channel being watched instead of
            // leaving the d-pad dead.
            focusGuideRow(current)
            return true
        }
        val atEdge = if (delta > 0) pos == count - 1 else pos == 0
        if (!atEdge) return false
        focusGuideRow(if (delta > 0) 0 else count - 1)
        return true
    }

    /** Scroll a row into view and put the cursor on it once it exists. */
    private fun focusGuideRow(position: Int) {
        val lm = ui.guideList.layoutManager as LinearLayoutManager
        lm.scrollToPositionWithOffset(position, 0)
        // The holder is only created after the scroll's layout pass, and on a
        // long jump not always by the first one.
        ui.guideList.post {
            val hit = ui.guideList.findViewHolderForAdapterPosition(position)
            if (hit != null) hit.itemView.requestFocus()
            else ui.guideList.post {
                ui.guideList.findViewHolderForAdapterPosition(position)
                    ?.itemView?.requestFocus()
            }
        }
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
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> chooseSetting()
                KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_SETTINGS,
                KeyEvent.KEYCODE_PROG_RED -> closeSettings()
            }
            return true
        }

        if (guideOpen) {
            when (keyCode) {
                KeyEvent.KEYCODE_BACK -> { closeGuide(); return true }
                KeyEvent.KEYCODE_MENU -> { closeGuide(); openSettings(); return true }
                // Only reached at the ends of the list - see wrapGuideFocus.
                KeyEvent.KEYCODE_DPAD_DOWN -> return wrapGuideFocus(1)
                KeyEvent.KEYCODE_DPAD_UP -> return wrapGuideFocus(-1)
                // Settings from inside the guide, where there is room to advertise it.
                KeyEvent.KEYCODE_SETTINGS, KeyEvent.KEYCODE_PROG_RED -> {
                    closeGuide(); openSettings(); return true
                }
            }
            return super.onKeyDown(keyCode, event)
        }
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP -> { tune(current - 1); return true }
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> { tune(current + 1); return true }
            // BACK opens the channel list, the way a cable box's guide button does.
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_GUIDE -> { openGuide(); return true }
            // MENU, not SETTINGS: Google TV swallows KEYCODE_SETTINGS for its
            // own panel, so the app never sees it.
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_SETTINGS,
            KeyEvent.KEYCODE_PROG_RED -> { openSettings(); return true }
            // OK is channel info, not the guide.
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_INFO -> {
                channels.getOrNull(current)?.let { showBanner(it) }; return true
            }
        }
        return super.onKeyDown(keyCode, event)
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
                    adapter?.notifyDataSetChanged()
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
