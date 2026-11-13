package com.streambox.tv.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/** All persistent state: profiles, settings, favourites, locks, resume positions. */
object Prefs {
    private lateinit var sp: SharedPreferences

    const val FAV_LIVE = "live"
    const val FAV_VOD = "vod"
    const val FAV_SERIES = "series"

    fun init(context: Context) {
        sp = context.applicationContext.getSharedPreferences("streambox", Context.MODE_PRIVATE)
        seedDefaultProfile()
    }

    /** Adds the built-in playlist once on first launch; deleting it afterwards keeps it deleted. */
    private fun seedDefaultProfile() {
        if (sp.getBoolean("defaultSeeded", false)) return
        if (profiles().none { it.id == "default" }) {
            addProfile(
                Profile(
                    id = "default",
                    name = "My IPTV",
                    type = Profile.TYPE_XTREAM,
                    server = "https://6699.info",
                    username = "sa0165",
                    password = "01069979654"
                )
            )
        }
        sp.edit().putBoolean("defaultSeeded", true).apply()
    }

    // ---------- Profiles ----------

    fun profiles(): List<Profile> {
        val out = ArrayList<Profile>()
        try {
            val arr = JSONArray(sp.getString("profiles", "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(
                    Profile(
                        id = o.optString("id"),
                        name = o.optString("name"),
                        type = o.optString("type"),
                        server = o.optString("server"),
                        username = o.optString("username"),
                        password = o.optString("password"),
                        m3uUrl = o.optString("m3uUrl"),
                        epgUrl = o.optString("epgUrl")
                    )
                )
            }
        } catch (_: Exception) {
        }
        return out
    }

    private fun saveProfiles(list: List<Profile>) {
        val arr = JSONArray()
        for (p in list) {
            arr.put(
                JSONObject()
                    .put("id", p.id).put("name", p.name).put("type", p.type)
                    .put("server", p.server).put("username", p.username).put("password", p.password)
                    .put("m3uUrl", p.m3uUrl).put("epgUrl", p.epgUrl)
            )
        }
        sp.edit().putString("profiles", arr.toString()).apply()
    }

    fun addProfile(p: Profile) = saveProfiles(profiles() + p)

    fun deleteProfile(id: String) {
        saveProfiles(profiles().filter { it.id != id })
        val e = sp.edit()
        for (k in sp.all.keys) if (k.contains("_${id}_") || k.endsWith("_$id")) e.remove(k)
        e.apply()
    }

    // ---------- Settings ----------

    var streamFormat: String
        get() = sp.getString("streamFormat", "ts") ?: "ts"
        set(v) = sp.edit().putString("streamFormat", v).apply()

    var userAgent: String
        get() = sp.getString("userAgent", "") ?: ""
        set(v) = sp.edit().putString("userAgent", v).apply()

    var externalPlayer: Boolean
        get() = sp.getBoolean("externalPlayer", false)
        set(v) = sp.edit().putBoolean("externalPlayer", v).apply()

    var use24h: Boolean
        get() = sp.getBoolean("use24h", true)
        set(v) = sp.edit().putBoolean("use24h", v).apply()

    var previewEnabled: Boolean
        get() = sp.getBoolean("previewEnabled", true)
        set(v) = sp.edit().putBoolean("previewEnabled", v).apply()

    var cacheHours: Int
        get() = sp.getInt("cacheHours", 12)
        set(v) = sp.edit().putInt("cacheHours", v).apply()

    /** EPG shift in hours, for providers whose guide is off by a timezone. */
    var epgShiftHours: Int
        get() = sp.getInt("epgShift", 0)
        set(v) = sp.edit().putInt("epgShift", v).apply()

    var lastProfileId: String
        get() = sp.getString("lastProfile", "") ?: ""
        set(v) = sp.edit().putString("lastProfile", v).apply()

    var openLastProfile: Boolean
        get() = sp.getBoolean("openLastProfile", true)
        set(v) = sp.edit().putBoolean("openLastProfile", v).apply()

    var pin: String
        get() = sp.getString("pin", "") ?: ""
        set(v) = sp.edit().putString("pin", v).apply()

    val parentalEnabled: Boolean get() = pin.isNotEmpty()

    // ---------- Favourites ----------

    private fun strSet(key: String): Set<String> = HashSet(sp.getStringSet(key, emptySet()) ?: emptySet())

    fun favourites(profileId: String, type: String): Set<String> = strSet("fav_${type}_$profileId")

    fun isFavourite(profileId: String, type: String, id: String) = favourites(profileId, type).contains(id)

    /** Returns the new state. */
    fun toggleFavourite(profileId: String, type: String, id: String): Boolean {
        val s = HashSet(favourites(profileId, type))
        val now = if (s.contains(id)) { s.remove(id); false } else { s.add(id); true }
        sp.edit().putStringSet("fav_${type}_$profileId", s).apply()
        return now
    }

    // ---------- Locked categories ----------

    fun lockedCategories(profileId: String): Set<String> = strSet("lock_$profileId")

    fun toggleLocked(profileId: String, key: String): Boolean {
        val s = HashSet(lockedCategories(profileId))
        val now = if (s.contains(key)) { s.remove(key); false } else { s.add(key); true }
        sp.edit().putStringSet("lock_$profileId", s).apply()
        return now
    }

    // ---------- Recently watched channels ----------

    fun recentChannels(profileId: String): List<String> {
        val out = ArrayList<String>()
        try {
            val arr = JSONArray(sp.getString("recent_$profileId", "[]"))
            for (i in 0 until arr.length()) out.add(arr.getString(i))
        } catch (_: Exception) {
        }
        return out
    }

    fun addRecentChannel(profileId: String, channelId: String) {
        val list = ArrayList(recentChannels(profileId))
        list.remove(channelId)
        list.add(0, channelId)
        while (list.size > 30) list.removeAt(list.size - 1)
        sp.edit().putString("recent_$profileId", JSONArray(list).toString()).apply()
    }

    // ---------- Resume positions ----------

    fun position(key: String): Long = if (key.isEmpty()) 0L else sp.getLong("pos_$key", 0L)

    fun setPosition(key: String, ms: Long) {
        if (key.isEmpty()) return
        if (ms <= 0L) sp.edit().remove("pos_$key").apply()
        else sp.edit().putLong("pos_$key", ms).apply()
    }

    fun clearResumePositions() {
        val e = sp.edit()
        for (k in sp.all.keys) if (k.startsWith("pos_")) e.remove(k)
        e.apply()
    }
}
