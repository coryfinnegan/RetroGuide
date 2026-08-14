package com.ersatz.retroguide

import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

data class Channel(
    val number: String,
    val name: String,
    val url: String,
    val logo: String?,
    val tvgId: String?
)

data class Programme(
    val channelId: String,
    val start: Long,
    val stop: Long,
    val title: String
)

/**
 * Everything this app knows about the server.
 *
 * ErsatzTV publishes a standard M3U and an XMLTV file, so there is no bespoke
 * API to talk to - the same two URLs any IPTV player would use.
 */
object Source {
    /** Set from saved preferences at startup; empty until the app is set up. */
    @Volatile var host: String = ""

    val m3uUrl get() = "http://$host/iptv/channels.m3u"
    val xmltvUrl get() = "http://$host/iptv/xmltv.xml"

    private fun fetch(url: String): String {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000
            readTimeout = 60000
            setRequestProperty("User-Agent", "RetroGuide/1.0")
        }
        try {
            return c.inputStream.bufferedReader().use(BufferedReader::readText)
        } finally {
            c.disconnect()
        }
    }

    private val EXTINF = Regex("""#EXTINF:.*?tvg-id="([^"]*)".*?tvg-chno="([^"]*)".*?tvg-logo="([^"]*)".*?,\s*(.+)""")

    /** Parse the M3U. Falls back to a looser match so a missing attribute never drops a channel. */
    fun loadChannels(): List<Channel> {
        val text = fetch(m3uUrl)
        val out = mutableListOf<Channel>()
        var pending: Triple<String?, String?, String?>? = null
        var pendingName: String? = null
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            when {
                line.startsWith("#EXTINF") -> {
                    val m = EXTINF.find(line)
                    if (m != null) {
                        pending = Triple(m.groupValues[1], m.groupValues[2], m.groupValues[3])
                        pendingName = m.groupValues[4].trim()
                    } else {
                        val id = Regex("""tvg-id="([^"]*)"""").find(line)?.groupValues?.get(1)
                        val no = Regex("""tvg-chno="([^"]*)"""").find(line)?.groupValues?.get(1)
                        val lg = Regex("""tvg-logo="([^"]*)"""").find(line)?.groupValues?.get(1)
                        pending = Triple(id, no, lg)
                        pendingName = line.substringAfterLast(',').trim()
                    }
                }
                line.isNotEmpty() && !line.startsWith("#") && pending != null -> {
                    val (id, no, logo) = pending!!
                    out += Channel(
                        number = no?.takeIf { it.isNotBlank() } ?: (out.size + 1).toString(),
                        name = pendingName ?: "Channel",
                        url = line,
                        logo = logo?.takeIf { it.isNotBlank() },
                        tvgId = id?.takeIf { it.isNotBlank() }
                    )
                    pending = null; pendingName = null
                }
            }
        }
        return out.sortedBy { it.number.toIntOrNull() ?: Int.MAX_VALUE }
    }

    // XMLTV times look like "20260810143000 -0500"
    private fun parseTime(s: String): Long {
        val trimmed = s.trim()
        val base = trimmed.take(14)
        val fmt = SimpleDateFormat("yyyyMMddHHmmss", Locale.US)
        val tz = trimmed.drop(14).trim()
        fmt.timeZone = if (tz.length >= 5) TimeZone.getTimeZone("GMT$tz") else TimeZone.getDefault()
        return try { fmt.parse(base)?.time ?: 0L } catch (e: Exception) { 0L }
    }

    /**
     * Pull the guide with a streaming pull parser.
     *
     * This was originally a regex over the whole document, which worked on small
     * guides and then fell off a cliff: a real lineup produced a 4.5 MB file with
     * 10,259 programmes, and a lazy `(.*?)` under DOT_MATCHES_ALL rescans toward
     * the end of the document from every position that fails to match. On a TV
     * box that pinned the CPU at 127% and never finished. XmlPullParser is in the
     * platform, reads straight from the socket, and is linear.
     *
     * Only a window around now is kept. The guide covers days; the UI shows the
     * current programme and the next few half hours, and holding all 10k on a
     * device with limited memory buys nothing.
     */
    fun loadGuide(): Map<String, List<Programme>> {
        val now = System.currentTimeMillis()
        val from = now - 3 * 3600_000L
        val to = now + 12 * 3600_000L

        val c = (URL(xmltvUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000
            readTimeout = 60000
            setRequestProperty("User-Agent", "RetroGuide/1.0")
        }
        val byChannel = HashMap<String, MutableList<Programme>>()
        try {
            val parser = android.util.Xml.newPullParser()
            parser.setInput(c.inputStream, null)
            var start = 0L
            var stop = 0L
            var channel: String? = null
            var inProgramme = false
            var title: String? = null

            var event = parser.eventType
            while (event != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                when (event) {
                    org.xmlpull.v1.XmlPullParser.START_TAG -> when (parser.name) {
                        "programme" -> {
                            inProgramme = true
                            title = null
                            start = parseTime(parser.getAttributeValue(null, "start") ?: "")
                            stop = parseTime(parser.getAttributeValue(null, "stop") ?: "")
                            channel = parser.getAttributeValue(null, "channel")
                        }
                        // take the first title only; XMLTV may repeat it per language
                        "title" -> if (inProgramme && title == null) title = parser.nextText()
                    }
                    org.xmlpull.v1.XmlPullParser.END_TAG -> if (parser.name == "programme") {
                        val ch = channel
                        val t = title
                        if (ch != null && !t.isNullOrBlank() && stop > from && start < to) {
                            byChannel.getOrPut(ch) { mutableListOf() } +=
                                Programme(ch, start, stop, t.trim())
                        }
                        inProgramme = false
                    }
                }
                event = parser.next()
            }
        } finally {
            c.disconnect()
        }
        byChannel.values.forEach { it.sortBy { p -> p.start } }
        return byChannel
    }
}

/** What is on a channel at a given moment, and what follows it. */
fun List<Programme>.at(t: Long): Programme? = firstOrNull { t >= it.start && t < it.stop }

fun List<Programme>.fromSlot(slotStart: Long, slotEnd: Long): Programme? =
    firstOrNull { it.start < slotEnd && it.stop > slotStart }

fun hhmm(t: Long): String = SimpleDateFormat("h:mm", Locale.US).format(Date(t))
