package com.streambox.tv.data

/** Google Play edition: no built-in login. Every customer adds their own IPTV account. */
object Secret {
    const val BUILT_IN = false
    internal const val DEFAULT_SERVER = ""
    internal const val DEFAULT_USER = ""
    internal const val DEFAULT_PASS = ""

    fun reveal(s: String): String = s

    fun checkPassword(input: String): Boolean = false
}
