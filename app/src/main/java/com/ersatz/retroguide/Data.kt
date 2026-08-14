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
     * Pull the guide. Parsed with regex rather than a pull parser on purpose:
     * the file is one flat list of <programme> elements, and this keeps the whole
     * app dependency-free apart from the player.
     */
    fun loadGuide(): Map<String, List<Programme>> {
        val text = fetch(xmltvUrl)
        val rx = Regex(
            """<programme start="([^"]+)"\s+stop="([^"]+)"\s+channel="([^"]+)"\s*>(.*?)</programme>""",
            RegexOption.DOT_MATCHES_ALL
        )
        val titleRx = Regex("""<title[^>]*>(.*?)</title>""", RegexOption.DOT_MATCHES_ALL)
        val byChannel = HashMap<String, MutableList<Programme>>()
        for (m in rx.findAll(text)) {
            val title = titleRx.find(m.groupValues[4])?.groupValues?.get(1)
                ?.replace("&amp;", "&")?.replace("&lt;", "<")?.replace("&gt;", ">")
                ?.replace("&quot;", "\"")?.replace("&apos;", "'")?.trim() ?: continue
            val ch = m.groupValues[3]
            byChannel.getOrPut(ch) { mutableListOf() } += Programme(
                ch, parseTime(m.groupValues[1]), parseTime(m.groupValues[2]), title
            )
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
