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
            .bootstrapDnsHosts(ips.map { InetAddress.getByName(it) })
            .build()

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
    private object FallbackDns : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            val why = ArrayList<String>()
            fun attempt(label: String, block: () -> List<InetAddress>): List<InetAddress>? =
                try {
                    block().ifEmpty { why.add("$label: no address"); null }
                } catch (e: Exception) {
                    why.add("$label: ${e.javaClass.simpleName}")
                    null
                }

            attempt("system") { Dns.SYSTEM.lookup(hostname) }?.let { return it }
            for (server in listOf("8.8.8.8", "1.1.1.1", "9.9.9.9")) {
                attempt("udp $server") { udpLookup(hostname, server) }?.let { return it }
            }
            dohResolvers.forEachIndexed { i, resolver ->
                attempt("https ${i + 1}") { resolver.lookup(hostname) }?.let { return it }
            }
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
