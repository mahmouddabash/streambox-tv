package com.streambox.tv.data

import android.util.Base64
import java.security.MessageDigest

/** Keeps the built-in login out of plain text and checks the profile password without storing it. */
object Secret {
    /** This (sideload) build ships the owner's built-in login; the Play build doesn't. */
    const val BUILT_IN = true
    private val KEY = "StreamBox#2026~tv".toByteArray()

    internal const val DEFAULT_SERVER = "OwAGFVtCbQIQQkQBHFURGUxhREtQ"
    internal const val DEFAULT_USER = "IBVCU1Ff"
    internal const val DEFAULT_PASS = "Y0VCU1hUdVZOFgY="
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
