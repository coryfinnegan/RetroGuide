package com.ersatz.retroguide

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.net.URL

/**
 * Finds ErsatzTV on the local network.
 *
 * Typing an IP address with a d-pad is miserable, and ErsatzTV advertises
 * nothing over SSDP or mDNS, so the practical approach is to sweep the local
 * /24 for the port and then confirm each hit is really ErsatzTV by asking it
 * for its version. A bare open port is not proof - plenty of things listen on
 * odd ports - so the confirmation step is what makes this trustworthy.
 */
object Discovery {

    const val DEFAULT_PORT = 8409

    data class Found(val host: String, val version: String)

    /** Local IPv4 addresses of any up, non-loopback interface. */
    private fun localIPv4(): List<String> =
        runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .map { it.hostAddress ?: "" }
                .filter { it.isNotBlank() }
        }.getOrDefault(emptyList())

    /** "192.168.1.37" -> "192.168.1." */
    private fun prefixOf(ip: String): String? =
        ip.substringBeforeLast('.', "").takeIf { it.isNotBlank() }?.plus(".")

    suspend fun scan(
        port: Int = DEFAULT_PORT,
        onProgress: (Int, Int) -> Unit = { _, _ -> }
    ): List<Found> = coroutineScope {
        val prefixes = localIPv4().mapNotNull { prefixOf(it) }.distinct()
        if (prefixes.isEmpty()) return@coroutineScope emptyList()

        val targets = prefixes.flatMap { p -> (1..254).map { "$p$it" } }
        val done = java.util.concurrent.atomic.AtomicInteger(0)

        val results = targets.map { ip ->
            async(Dispatchers.IO) {
                val hit = withTimeoutOrNull(1200L) {
                    // cheap reachability first; most addresses fail here instantly
                    val open = runCatching {
                        Socket().use { s ->
                            s.connect(InetSocketAddress(ip, port), 600)
                            true
                        }
                    }.getOrDefault(false)
                    if (!open) null else verify("$ip:$port")
                }
                onProgress(done.incrementAndGet(), targets.size)
                hit
            }
        }.awaitAll()

        results.filterNotNull().distinctBy { it.host }
    }

    /** Confirm a host really is ErsatzTV by reading /api/version. */
    fun verify(hostPort: String): Found? = runCatching {
        val c = (URL("http://$hostPort/api/version").openConnection() as HttpURLConnection).apply {
            connectTimeout = 1500
            readTimeout = 1500
            requestMethod = "GET"
            setRequestProperty("User-Agent", "RetroGuide/1.0")
        }
        try {
            if (c.responseCode != 200) return null
            val body = c.inputStream.bufferedReader().readText().trim()
            // ErsatzTV answers with a bare version string such as "v25.1.0-win-x64"
            if (body.isNotEmpty() && body.length < 64 && !body.startsWith("<"))
                Found(hostPort, body) else null
        } finally {
            c.disconnect()
        }
    }.getOrNull()
}
