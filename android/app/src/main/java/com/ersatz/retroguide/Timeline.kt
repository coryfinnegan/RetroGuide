package com.ersatz.retroguide

import android.content.Context
import android.graphics.Canvas
import android.graphics.Path
import android.graphics.Paint
import android.graphics.Typeface
import android.text.TextPaint
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * The span of time the guide shows: ninety minutes from the current half hour,
 * paged forward by whole half hours. The labels sit on half hours because that
 * is how a person reads a clock; the programmes do not, and are drawn where
 * they actually fall.
 */
data class GuideWindow(val a: Long, val b: Long) {
    val marks: List<Long> get() = listOf(a, a + HALF, a + 2 * HALF)
    fun frac(t: Long): Float = (t - a).toFloat() / (b - a)

    companion object {
        const val HALF = 30 * 60_000L
        const val SPAN = 90 * 60_000L

        fun at(now: Long, page: Int): GuideWindow {
            // Local half hours, so a zone a quarter hour off UTC still reads :00 and :30.
            val off = TimeZone.getDefault().getOffset(now)
            val a = (now + off) / HALF * HALF - off + page * HALF
            return GuideWindow(a, a + SPAN)
        }
    }
}

/**
 * One drawn block: a programme, or a run of programmes too short to label.
 * [before] and [after] say it carries on past the window's left or right
 * edge - the Prevue grid marked those with a triangle.
 */
data class Block(
    val start: Long, val stop: Long, val title: String,
    val before: Boolean = false, val after: Boolean = false,
)

/**
 * Lay a channel's programmes onto [a, b) by their real times.
 *
 * This replaced three half hour columns that each showed whichever programme
 * overlapped them first. Almost nothing on ErsatzTV starts on a half hour -
 * 0.07% of the listings when this was written - so a channel running four
 * cartoons in a half hour showed one of them, and every edge was wrong. Here
 * every block starts and stops where its programme does.
 *
 * Programmes too short to label (a Looney Tunes short, a two minute weather
 * loop) are merged with their short neighbours into one block that starts
 * where the first begins and stops where the last ends. The block is labelled with the title they share if
 * they share one, otherwise the first - the one on soonest. A run is closed at
 * twice the label width, so an hour of shorts reads as a few labelled blocks
 * rather than one naming only the first.
 */
fun blocks(progs: List<Programme>, a: Long, b: Long, minMs: Long): List<Block> {
    val out = mutableListOf<Block>()
    var runStart = 0L
    var runStop = 0L
    var runBefore = false
    var runAfter = false
    val titles = mutableListOf<String>()
    fun flush() {
        if (titles.isEmpty()) return
        out += Block(runStart, runStop, commonTitle(titles), runBefore, runAfter)
        titles.clear()
    }
    for (p in progs) {
        if (p.stop <= a || p.start >= b) continue
        val start = maxOf(p.start, a)
        val stop = minOf(p.stop, b)
        val short = stop - start < minMs
        if (titles.isNotEmpty() &&
            (!short || start - runStop > 60_000 || runStop - runStart >= 2 * minMs)) flush()
        if (!short) {
            out += Block(start, stop, gridTitle(p.title), (p.start < a), (p.stop > b))
            continue
        }
        if (titles.isEmpty()) { runStart = start; runBefore = p.start < a }
        titles += gridTitle(p.title)
        runStop = stop
        runAfter = p.stop > b
    }
    flush()
    return out
}

private val NUMBERING = Regex("""^(\s*\d+[a-z]?\s*[-.]\s*)+(?=\S)""", RegexOption.IGNORE_CASE)

/**
 * Leading episode numbering - "1943 - 24 - Merrie Melodies - ..." - is how
 * these files sort on disk, and in a narrow block it is all that would show.
 * The banner keeps the full title.
 */
fun gridTitle(t: String): String = t.replace(NUMBERING, "").ifEmpty { t }

/**
 * "The Simpsons Tracy Ullman Shorts 40 - ..." and "...Shorts 04 - ..." read as
 * "The Simpsons Tracy Ullman Shorts"; titles with nothing worth sharing fall
 * back to the first.
 */
fun commonTitle(titles: List<String>): String {
    if (titles.size == 1) return titles[0]
    var prefix = titles[0]
    for (t in titles) while (!t.startsWith(prefix)) prefix = prefix.dropLast(1)
    prefix = prefix.trimEnd { it.isWhitespace() || it.isDigit() || it in "-:#" }
    return if (prefix.length >= 6) prefix else titles[0]
}

/**
 * The truncation mark for a typeface. The star4000 CG font has no visible
 * "…", so a cut title in that face simply stopped mid-word.
 */
fun ellipsisFor(face: Typeface?): String = if (face != null) "..." else "…"

/** [s] cut to fit [width] on one line, with an ellipsis when anything was cut. */
fun fitOneLine(s: String, paint: Paint, width: Float, face: Typeface? = null): String {
    if (paint.measureText(s) <= width) return s
    val dots = ellipsisFor(face)
    val room = width - paint.measureText(dots)
    var end = s.length
    while (end > 0 && paint.measureText(s, 0, end) > room) end--
    return s.substring(0, end).trimEnd() + dots
}

/** Guide-style times: "7:30p". */
fun shortTime(t: Long): String =
    SimpleDateFormat("h:mma", Locale.US).format(Date(t)).lowercase(Locale.US).dropLast(1)

/** The face every guide grid uses: a condensed bold sans, as cable boxes did. */
val GRID_FACE: Typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)

private fun Context.sp(v: Float) =
    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

private fun Context.dp(v: Float) =
    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)

/**
 * The whole programme grid - time bar, channel boxes and programme blocks -
 * drawn in one view.
 *
 * It holds no focus of its own. The activity owns the cursor (a row and a
 * moment in time) and hands it in with [set]; the block under that moment on
 * that row is the one highlighted, the way a cable box moves a single
 * highlighted cell rather than lighting a whole row.
 */
class GuideGridView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private var channels: List<Channel> = emptyList()
    private var guide: Map<String, List<Programme>> = emptyMap()
    private var window = GuideWindow.at(System.currentTimeMillis(), 0)
    private var top = 0
    private var selRow = 0
    private var selTime = 0L
    private var tuned = 0
    private var theme = GuideTheme.CLASSIC

    private val headH = context.dp(30f)
    /** Two lines of title, as the Prevue grid wrapped them. */
    private val rowMin = context.dp(50f)
    /** Bevel thickness: about one scan line of the 1990s grid at this size. */
    private val bev = context.dp(2f)
    private val pad = context.dp(7f)
    /** A block narrower than this cannot carry a readable title. */
    private val minLabelPx = context.dp(64f)

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val cellText = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = GRID_FACE; textSize = context.sp(15f)
    }
    private val headText = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = GRID_FACE; textSize = context.sp(15f)
    }
    private val numText = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = GRID_FACE; textSize = context.sp(17f); textAlign = Paint.Align.CENTER
    }
    private val nameText = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = GRID_FACE; textSize = context.sp(13f); textAlign = Paint.Align.CENTER
    }
    private val tri = Path()

    fun set(
        channels: List<Channel>, guide: Map<String, List<Programme>>, window: GuideWindow,
        top: Int, selRow: Int, selTime: Long, tuned: Int, theme: GuideTheme,
    ) {
        this.channels = channels; this.guide = guide; this.window = window
        this.top = top; this.selRow = selRow; this.selTime = selTime
        this.tuned = tuned; this.theme = theme
        invalidate()
    }

    /** Whole rows that fit; the activity scrolls by these. */
    fun visibleRows(): Int =
        if (height <= 0) 1 else ((height - headH) / rowMin).toInt().coerceAtLeast(1)

    private val chanW get() = width * 0.18f
    private val cellsW get() = width - chanW

    /**
     * The blocks drawn for a row. A channel with nothing in the listings gets
     * one block naming the channel, so the cursor still has somewhere to sit.
     */
    fun blocksFor(row: Int, win: GuideWindow = window): List<Block> {
        val ch = channels.getOrNull(row) ?: return emptyList()
        val progs = ch.tvgId?.let { guide[it] } ?: emptyList()
        val w = if (cellsW > 0) cellsW else 1f
        val minMs = (minLabelPx / w * (win.b - win.a)).toLong()
        return blocks(progs, win.a, win.b, minMs).ifEmpty { listOf(Block(win.a, win.b, ch.name)) }
    }

    /** The block under [t] on [row], or the nearest one. */
    fun blockAt(row: Int, t: Long, win: GuideWindow = window): Block? {
        val bs = blocksFor(row, win)
        return bs.firstOrNull { t >= it.start && t < it.stop }
            ?: bs.minByOrNull { minOf(Math.abs(it.start - t), Math.abs(it.stop - t)) }
    }

    /**
     * A title in at most two lines, breaking between words. A first word too
     * wide for the block puts the whole title on one cut line instead.
     */
    private fun wrap(title: String, room: Float, lines: Int): List<String> {
        if (lines < 2 || cellText.measureText(title) <= room) return listOf(fitOneLine(title, cellText, room))
        val words = title.split(' ')
        var first = ""
        var i = 0
        while (i < words.size) {
            val next = if (first.isEmpty()) words[i] else "$first ${words[i]}"
            if (cellText.measureText(next) > room) break
            first = next; i++
        }
        if (first.isEmpty()) return listOf(fitOneLine(title, cellText, room))
        val rest = words.drop(i).joinToString(" ")
        return if (rest.isEmpty()) listOf(first) else listOf(first, fitOneLine(rest, cellText, room))
    }

    /** A small solid triangle pointing left or right, centred on [cy]. */
    private fun arrow(c: Canvas, x: Float, cy: Float, left: Boolean, color: Int) {
        val w = context.dp(6f)
        val h = context.dp(5.5f)
        tri.reset()
        if (left) { tri.moveTo(x, cy); tri.lineTo(x + w, cy - h); tri.lineTo(x + w, cy + h) }
        else { tri.moveTo(x + w, cy); tri.lineTo(x, cy - h); tri.lineTo(x, cy + h) }
        tri.close()
        fill.color = color
        c.drawPath(tri, fill)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        if (w <= 0f || channels.isEmpty()) return
        val cw = chanW
        val gw = cellsW

        // Time bar: the day in the channel column, half hours over the blocks.
        Emboss.draw(canvas, 0f, 0f, cw, headH, theme.head, bev)
        Emboss.draw(canvas, cw, 0f, w, headH, theme.head, bev)
        headText.color = theme.headInk
        val base = headH / 2 - (headText.descent() + headText.ascent()) / 2
        val day = SimpleDateFormat("EEE M/d", Locale.US).format(Date(window.a))
        canvas.drawText(day, pad, base, headText)
        for (t in window.marks) {
            val x = cw + window.frac(t) * gw
            if (t != window.a) {
                fill.color = Emboss.lo(theme.head); canvas.drawRect(x - bev, bev, x, headH - bev, fill)
                fill.color = Emboss.hi(theme.head); canvas.drawRect(x, bev, x + bev, headH - bev, fill)
            }
            canvas.drawText(shortTime(t), x + pad, base, headText)
        }

        val n = visibleRows()
        val rowH = (height - headH) / n
        val lineH = cellText.fontSpacing
        val lines = if (rowH - 2 * bev >= lineH * 2 + context.dp(4f)) 2 else 1
        for (i in 0 until n) {
            val row = top + i
            val ch = channels.getOrNull(row) ?: break
            val y0 = headH + i * rowH
            val y1 = y0 + rowH

            // Channel box: number over call sign.
            Emboss.draw(canvas, 0f, y0, cw, y1, theme.chan, bev)
            val mid = cw / 2
            numText.color = if (row == tuned) theme.tunedInk else theme.numInk
            nameText.color = theme.chanInk
            val nh = numText.descent() - numText.ascent()
            val mh = nameText.descent() - nameText.ascent()
            val ty = y0 + (rowH - nh - mh) / 2
            canvas.drawText(ch.number, mid, ty - numText.ascent(), numText)
            canvas.drawText(
                fitOneLine(ch.name.uppercase(Locale.US), nameText, cw - pad * 2),
                mid, ty + nh - nameText.ascent(), nameText
            )

            // Programme blocks: titles top-left, up to two lines.
            val sel = if (row == selRow) blockAt(row, selTime) else null
            for (blk in blocksFor(row)) {
                val x0 = cw + window.frac(blk.start) * gw
                val x1 = cw + window.frac(blk.stop) * gw
                if (x1 - x0 < bev * 3) continue
                val on = blk == sel
                val ink = if (on) theme.selInk else theme.cellInk
                Emboss.draw(canvas, x0, y0, x1, y1, if (on) theme.sel else theme.cell, bev)
                val text = lines.let { l ->
                    val used = if (l == 2) lineH * 2 else lineH
                    y0 + (rowH - used) / 2 - cellText.ascent()
                }
                var left = x0 + pad
                var right = x1 - pad
                val cy = text + (cellText.ascent() + cellText.descent()) / 2
                if (blk.before && right - left > context.dp(40f)) {
                    arrow(canvas, left - context.dp(2f), cy, true, ink); left += context.dp(8f)
                }
                if (blk.after && right - left > context.dp(40f)) {
                    arrow(canvas, right - context.dp(5f), cy, false, ink); right -= context.dp(9f)
                }
                val room = right - left
                if (room > cellText.textSize) {
                    cellText.color = ink
                    wrap(blk.title, room, lines).forEachIndexed { k, s ->
                        canvas.drawText(s, left, text + k * lineH, cellText)
                    }
                }
            }
        }
    }
}
