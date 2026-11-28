package com.streambox.tv.data

import android.util.Base64
import java.security.MessageDigest

/** Keeps the built-in login out of plain text and checks the profile password without storing it. */
object Secret {
    /** No login is built in any more; only a sample provider address for new profiles. */
    const val BUILT_IN = false
    private val KEY = "StreamBox#2026~tv".toByteArray()

    /** Provider address filled in as a sample when adding a profile. */
    internal const val SAMPLE_SERVER = "OwAGFVtCbQIQQkQBHFURGUxhREtQ"
    /** Built-in TMDB key (scrambled); "" = none, then only a key typed in Settings is used. */
    internal const val TMDB_KEY = "MBJBUQdZIF5KFwpVUAYcTUVqFUYHUFQgDEwSAwUBA08="
    private const val PASSWORD_HASH = "a4f78b341e985db84d1dba705c6cfba390e141d17bb9605dbd34696713377cd1"

    fun reveal(s: String): String {
        val b = Base64.decode(s, Base64.NO_WRAP)
        return String(ByteArray(b.size) { i -> (b[i].toInt() xor KEY[i % KEY.size].toInt()).toByte() })
    }

    fun checkPassword(input: String): Boolean {
        val d = MessageDigest.getInstance("SHA-256").digest("streambox:${input.trim()}".toByteArray())
        return d.joinToString("") { "%02x".format(it) } == PASSWORD_HASH
    }
}
