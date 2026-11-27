package com.streambox.tv.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.dnsoverhttps.DnsOverHttps
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.IDN
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/** HTTP error with its status code, so callers can react to 403 etc. */
class HttpException(val code: Int) : IOException("Server returned HTTP $code")

object Net {
    const val DEFAULT_UA = "StreamBoxTV/1.0 (Android)"

    /** Player identities that IPTV panels commonly accept; tried in order when the default is refused. */
    val FALLBACK_UAS = listOf(
        "VLC/3.0.20 LibVLC/3.0.20",
        "okhttp/4.12.0",
        "IPTVSmartersPro",
        "Dalvik/2.1.0 (Linux; U; Android 12; Android TV Build/STT1.220621.001)",
        "Mozilla/5.0 (Linux; Android 12; Android TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36"
    )

    fun userAgent(): String = Prefs.userAgent.ifBlank { DEFAULT_UA }

    /** Plain client for DNS-over-HTTPS lookups and connectivity checks (must not use [FallbackDns] itself). */
    private val bootstrap: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .build()
    }

    private fun doh(url: String, vararg ips: String): Dns =
        DnsOverHttps.Builder()
            .client(bootstrap)
            .url(url.toHttpUrl())
            .apply { if (ips.isNotEmpty()) bootstrapDnsHosts(ips.map { InetAddress.getByName(it) }) }
            .build()

    /** A DNS choice in Network Center. [doh]: private DNS address; [udp]: plain DNS server. */
    data class DnsChoice(val name: String, val doh: String = "", val ips: List<String> = emptyList(), val udp: String = "")

    const val DNS_CUSTOM_IP = 9
    const val DNS_CUSTOM_DOH = 10
    const val DNS_SYSTEM_ONLY = 11

    /** Order matters: the index is what's saved (0–2 kept from earlier versions). */
    val dnsChoices = listOf(
        DnsChoice("Automatic (the TV's own, private DNS if it fails)"),
        DnsChoice("Cloudflare private DNS (1.1.1.1)", "https://cloudflare-dns.com/dns-query", listOf("1.1.1.1", "1.0.0.1")),
        DnsChoice("Google private DNS (8.8.8.8)", "https://dns.google/dns-query", listOf("8.8.8.8", "8.8.4.4")),
        DnsChoice("Quad9 private DNS (9.9.9.9), blocks harmful sites", "https://dns.quad9.net/dns-query", listOf("9.9.9.9", "149.112.112.112")),
        DnsChoice("AdGuard private DNS, blocks ads and trackers", "https://dns.adguard-dns.com/dns-query", listOf("94.140.14.14", "94.140.15.15")),
        DnsChoice("OpenDNS private DNS (Cisco)", "https://doh.opendns.com/dns-query", listOf("208.67.222.222", "208.67.220.220")),
        DnsChoice("Cloudflare Family, blocks adult and harmful sites", "https://family.cloudflare-dns.com/dns-query", listOf("1.1.1.3", "1.0.0.3")),
        DnsChoice("AdGuard Family, blocks adult sites and ads", "https://family.adguard-dns.com/dns-query", listOf("94.140.14.15", "94.140.15.16")),
        DnsChoice("Plain DNS 1.1.1.1 / 8.8.8.8 (not private)", udp = "1.1.1.1"),
        DnsChoice("Custom DNS server (IP address)…"),
        DnsChoice("Custom private DNS address (https://…)…"),
        DnsChoice("The device's own DNS only")
    )

    private val dohCache = java.util.concurrent.ConcurrentHashMap<String, Dns>()

    private fun dohFor(url: String, ips: List<String>): Dns = dohCache.getOrPut(url + ips) { doh(url, *ips.toTypedArray()) }

    /** One lookup of [host] using DNS choice [index] only (for "Find the fastest DNS"); ms or null. */
    fun testDns(index: Int, host: String): Long? {
        val t = System.nanoTime()
        return try {
            val r = lookupWith(index, host) ?: return null
            if (r.isEmpty()) null else (System.nanoTime() - t) / 1_000_000
        } catch (_: Exception) {
            null
        }
    }

    /** Lookup with a fixed DNS choice; null for "automatic". */
    private fun lookupWith(index: Int, host: String): List<InetAddress>? {
        val c = dnsChoices.getOrNull(index) ?: return null
        return when {
            index == DNS_SYSTEM_ONLY -> Dns.SYSTEM.lookup(host)
            index == DNS_CUSTOM_IP -> Prefs.customDns.takeIf { it.isNotBlank() }?.let { udpLookup(host, it) }
            index == DNS_CUSTOM_DOH -> Prefs.customDoh.takeIf { it.startsWith("https://") }?.let { dohFor(it, emptyList()).lookup(host) }
            c.doh.isNotEmpty() -> dohFor(c.doh, c.ips).lookup(host)
            c.udp.isNotEmpty() -> try { udpLookup(host, c.udp) } catch (_: Exception) { udpLookup(host, "8.8.8.8") }
            else -> null
        }
    }

    private val dohResolvers: List<Dns> by lazy {
        listOf(
            doh("https://cloudflare-dns.com/dns-query", "1.1.1.1", "1.0.0.1"),
            doh("https://dns.google/dns-query", "8.8.8.8", "8.8.4.4")
        )
    }

    private fun u16(b: ByteArray, i: Int): Int = ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF)

    private fun skipName(b: ByteArray, start: Int): Int {
        var pos = start
        while (true) {
            val len = b[pos].toInt() and 0xFF
            if (len == 0) return pos + 1
            if (len and 0xC0 == 0xC0) return pos + 2
            pos += len + 1
        }
    }

    /** Minimal plain DNS A-record query over UDP straight to a public resolver (what `nslookup host 8.8.8.8` does). */
    private fun udpLookup(hostname: String, server: String): List<InetAddress> {
        val id = (System.nanoTime() and 0xFFFF).toInt()
        val q = ByteArrayOutputStream()
        q.write(id shr 8); q.write(id and 0xFF)
        q.write(byteArrayOf(1, 0, 0, 1, 0, 0, 0, 0, 0, 0))
        for (label in IDN.toASCII(hostname).trimEnd('.').split('.')) {
            val bytes = label.toByteArray(Charsets.US_ASCII)
            if (bytes.isEmpty() || bytes.size > 63) throw UnknownHostException(hostname)
            q.write(bytes.size); q.write(bytes)
        }
        q.write(byteArrayOf(0, 0, 1, 0, 1))
        val query = q.toByteArray()

        DatagramSocket().use { socket ->
            socket.soTimeout = 3000
            socket.send(DatagramPacket(query, query.size, InetAddress.getByName(server), 53))
            val buf = ByteArray(1500)
            val packet = DatagramPacket(buf, buf.size)
            socket.receive(packet)
            val b = buf.copyOf(packet.length)
            if (u16(b, 0) != id) throw IOException("mismatched reply")
            val rcode = b[3].toInt() and 0x0F
            if (rcode != 0) throw IOException("rcode $rcode")
            var pos = 12
            repeat(u16(b, 4)) { pos = skipName(b, pos) + 4 }
            val out = ArrayList<InetAddress>()
            repeat(u16(b, 6)) {
                pos = skipName(b, pos)
                val type = u16(b, pos)
                val len = u16(b, pos + 8)
                pos += 10
                if (type == 1 && len == 4) out.add(InetAddress.getByAddress(hostname, b.copyOfRange(pos, pos + 4)))
                pos += len
            }
            return out
        }
    }

    /**
     * System DNS first. If the TV's/router's resolver can't find the host, ask public resolvers
     * directly over UDP, then over HTTPS. The error lists why each step failed.
     */
    /** Forget remembered addresses (after changing the DNS setting). */
    fun clearDnsCache() = FallbackDns.clear()

    /** Looks up [host] with the current DNS setting: time taken and the addresses found, or null. */
    fun dnsLookup(host: String): Pair<Long, List<String>>? = try {
        FallbackDns.clear()
        val t = System.nanoTime()
        val list = FallbackDns.lookup(host)
        ((System.nanoTime() - t) / 1_000_000) to list.mapNotNull { it.hostAddress }
    } catch (_: Exception) {
        null
    }

    /** Looks up [host] with the device's own DNS only (to compare with StreamBox's choice). */
    fun systemLookup(host: String): List<String>? = try {
        Dns.SYSTEM.lookup(host).mapNotNull { it.hostAddress }
    } catch (_: Exception) {
        null
    }

    /** How long one lookup of [host] takes with the current DNS setting, or null when it fails. */
    fun dnsLookupMs(host: String): Long? = try {
        FallbackDns.clear()
        val t = System.nanoTime()
        FallbackDns.lookup(host)
        (System.nanoTime() - t) / 1_000_000
    } catch (_: Exception) {
        null
    }

    /** Is the active connection Wi-Fi/Ethernet (false) or mobile data / a phone hotspot (true)? Checked at most once a minute. */
    @Volatile private var meteredAt = 0L
    @Volatile private var meteredNow = false
    fun isMobileData(context: android.content.Context): Boolean {
        val now = System.currentTimeMillis()
        if (now - meteredAt > 60_000L) {
            meteredNow = try {
                (context.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager).isActiveNetworkMetered
            } catch (_: Exception) {
                false
            }
            meteredAt = now
        }
        return meteredNow
    }

    private object FallbackDns : Dns {
        fun clear() = cache.clear()

        private const val TTL = 10 * 60_000L
        private val cache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, List<InetAddress>>>()
        /** When the TV's own DNS failed recently we skip it, instead of waiting for it to time out on every request. */
        @Volatile
        private var systemFailedAt = 0L

        override fun lookup(hostname: String): List<InetAddress> {
            val now = System.currentTimeMillis()
            cache[hostname]?.let { (at, list) -> if (now - at < TTL) return list }
            val result = resolve(hostname)
            cache[hostname] = now to result
            return result
        }

        private fun resolve(hostname: String): List<InetAddress> {
            // Literal IP addresses need no lookup.
            if (hostname.isNotEmpty() && (hostname[0].isDigit() || hostname.contains(':'))) {
                try { return Dns.SYSTEM.lookup(hostname) } catch (_: Exception) { }
            }
            val why = ArrayList<String>()
            // Private DNS chosen in Network Center: ask it first, fall back to the usual order.
            if (Prefs.dnsMode == DNS_SYSTEM_ONLY) return Dns.SYSTEM.lookup(hostname)
            if (Prefs.dnsMode > 0) {
                try {
                    lookupWith(Prefs.dnsMode, hostname)?.let { if (it.isNotEmpty()) return it }
                } catch (e: Exception) {
                    why.add("chosen dns: ${e.javaClass.simpleName}")
                }
            }
            val skipSystem = System.currentTimeMillis() - systemFailedAt < TTL
            fun attempt(label: String, block: () -> List<InetAddress>): List<InetAddress>? =
                try {
                    block().ifEmpty { why.add("$label: no address"); null }
                } catch (e: Exception) {
                    why.add("$label: ${e.javaClass.simpleName}")
                    null
                }

            if (!skipSystem) {
                attempt("system") { Dns.SYSTEM.lookup(hostname) }?.let { return it }
                systemFailedAt = System.currentTimeMillis()
            }
            for (server in listOf("8.8.8.8", "1.1.1.1", "9.9.9.9")) {
                attempt("udp $server") { udpLookup(hostname, server) }?.let { return it }
            }
            dohResolvers.forEachIndexed { i, resolver ->
                attempt("https ${i + 1}") { resolver.lookup(hostname) }?.let { return it }
            }
            if (skipSystem) attempt("system") { Dns.SYSTEM.lookup(hostname) }?.let { systemFailedAt = 0L; return it }
            throw UnknownHostException("Unable to resolve host \"$hostname\" [${why.joinToString("; ")}]")
        }
    }

    private fun online(): Boolean =
        listOf("https://www.gstatic.com/generate_204", "https://1.1.1.1/").any { url ->
            try {
                bootstrap.newCall(Request.Builder().url(url).head().build()).execute().use { true }
            } catch (_: Exception) {
                false
            }
        }

    /** Turns a connection failure into a message that says whether the TV or the server is at fault. */
    suspend fun explain(e: Throwable): String = withContext(Dispatchers.IO) {
        val raw = e.message ?: "network error"
        val unreachable = e is UnknownHostException || e is SocketTimeoutException || e is ConnectException
        when {
            !unreachable -> raw
            online() -> "the TV is online but cannot reach this server. It may be blocked on this network, or the server is down. ($raw)"
            else -> "the TV has no internet connection. Check its Wi-Fi or cable. ($raw)"
        }
    }

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .dns(FallbackDns)
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().header("User-Agent", userAgent()).build())
            }
            .build()
    }

    @Throws(IOException::class)
    fun getString(url: String): String {
        client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
            if (!resp.isSuccessful) throw HttpException(resp.code)
            return resp.body?.string() ?: ""
        }
    }

    /** Downloads [url] into [target] (via a temp file so a failed download never corrupts the cache). */
    @Throws(IOException::class)
    fun download(url: String, target: File) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
            if (!resp.isSuccessful) throw HttpException(resp.code)
            val body = resp.body ?: throw IOException("Empty response")
            tmp.outputStream().use { out -> body.byteStream().copyTo(out) }
        }
        if (target.exists()) target.delete()
        if (!tmp.renameTo(target)) throw IOException("Could not write cache file")
    }
}
