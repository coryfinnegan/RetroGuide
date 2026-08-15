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

    private val handler = Handler(Looper.getMainLooper())
    private val hideBanner = Runnable { ui.banner.visibility = View.GONE }

    /** Digits typed on the remote, e.g. "6" then "6" tunes channel 66. */
    private val typed = StringBuilder()
    private val commitTyped = Runnable { tuneToNumber(typed.toString()); typed.clear() }

    private val tick = object : Runnable {
        override fun run() {
            ui.clock.text = SimpleDateFormat("h:mm a", Locale.US).format(Date())
            if (guideOpen) adapter?.notifyDataSetChanged()
            handler.postDelayed(this, 30_000)
        }
    }

    private var adapter: GuideAdapter? = null

    /** True when the server was unreachable and OK should reopen setup. */
    private var awaitingSetup = false

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

    private fun startPlayer() {
        player = ExoPlayer.Builder(this).build().apply {
            playWhenReady = true
            repeatMode = Player.REPEAT_MODE_OFF
            // Without this a stream that fails to open just shows black, with
            // nothing on screen or in the log to say why.
            addListener(object : Player.Listener {
                override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                    val ch = channels.getOrNull(current)
                    showBannerText(
                        ch?.number ?: "",
                        ch?.name ?: "CHANNEL",
                        "NO SIGNAL — ${error.errorCodeName}"
                    )
                }
            })
        }
        ui.fullPlayer.player = player
    }

    private fun tune(index: Int) {
        if (channels.isEmpty()) return
        current = ((index % channels.size) + channels.size) % channels.size
        val ch = channels[current]
        player?.apply {
            setMediaItem(MediaItem.fromUri(ch.url))
            prepare()
            play()
        }
        Prefs.setLastChannel(this, ch.number)
        showBanner(ch)
        adapter?.notifyDataSetChanged()
    }

    private fun tuneToNumber(number: String) {
        val i = channels.indexOfFirst { it.number == number }
        if (i >= 0) tune(i) else showBannerText(number, "NO SUCH CHANNEL", "")
    }

    private fun nowOn(ch: Channel): Programme? =
        ch.tvgId?.let { guide[it] }?.at(System.currentTimeMillis())

    private fun showBanner(ch: Channel) {
        val now = nowOn(ch)
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

        override fun onBindViewHolder(h: VH, position: Int) {
            val ch = channels[position]
            val s = slots()
            h.no.text = ch.number
            h.name.text = ch.name
            val progs = ch.tvgId?.let { guide[it] } ?: emptyList()
            for (i in 0..2) {
                val p = progs.fromSlot(s[i], s[i + 1])
                h.cells[i].text = p?.title ?: "—"
            }
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
        val focused = ui.guideList.focusedChild ?: return false
        val pos = ui.guideList.getChildAdapterPosition(focused)
        if (pos == RecyclerView.NO_POSITION) return false
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
        if (guideOpen) {
            when (keyCode) {
                KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_MENU -> { closeGuide(); return true }
                // Only reached at the ends of the list - see wrapGuideFocus.
                KeyEvent.KEYCODE_DPAD_DOWN -> return wrapGuideFocus(1)
                KeyEvent.KEYCODE_DPAD_UP -> return wrapGuideFocus(-1)
                // Settings from inside the guide, where there is room to advertise it.
                KeyEvent.KEYCODE_SETTINGS, KeyEvent.KEYCODE_PROG_RED -> { openSetup(); return true }
            }
            return super.onKeyDown(keyCode, event)
        }
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP -> { tune(current - 1); return true }
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> { tune(current + 1); return true }
            // BACK opens the channel list, the way a cable box's guide button does.
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_GUIDE,
            KeyEvent.KEYCODE_MENU -> { openGuide(); return true }
            // OK is channel info, not the guide.
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_INFO -> {
                channels.getOrNull(current)?.let { showBanner(it) }; return true
            }
            KeyEvent.KEYCODE_SETTINGS, KeyEvent.KEYCODE_PROG_RED -> { openSetup(); return true }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onBackPressed() {
        // Reached only when BACK arrives outside the key path. Mirror it there
        // so the app never exits out from under a programme.
        if (guideOpen) closeGuide() else openGuide()
    }

    override fun onStop() {
        super.onStop()
        player?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        player?.release(); player = null
    }
}
