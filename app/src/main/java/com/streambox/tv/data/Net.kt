package com.streambox.tv.data

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
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

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
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
