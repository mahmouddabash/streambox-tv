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
        seedDefaultProfile(context)
    }

    private val DEFAULT_PROFILE = Profile(
        id = "default",
        name = "My IPTV",
        type = Profile.TYPE_XTREAM,
        server = "http://mhav1.com:2095",
        username = "sa0602",
        password = "01069979654"
    )

    /**
     * Adds the built-in playlist once per version; deleting it afterwards keeps it deleted.
     * When the built-in login changes, an existing default profile is updated in place.
     */
    private fun seedDefaultProfile(context: Context) {
        val version = 2
        if (sp.getInt("defaultSeedVersion", if (sp.getBoolean("defaultSeeded", false)) 1 else 0) >= version) return
        val list = profiles()
        if (list.any { it.id == DEFAULT_PROFILE.id }) {
            // Cached channels/movies belong to the old server.
            java.io.File(context.cacheDir, "data_${DEFAULT_PROFILE.id}").deleteRecursively()
            saveProfiles(list.map {
                if (it.id == DEFAULT_PROFILE.id)
                    it.copy(type = DEFAULT_PROFILE.type, server = DEFAULT_PROFILE.server,
                        username = DEFAULT_PROFILE.username, password = DEFAULT_PROFILE.password)
                else it
            })
        } else {
            addProfile(DEFAULT_PROFILE)
        }
        sp.edit().putBoolean("defaultSeeded", true).putInt("defaultSeedVersion", version).apply()
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

    /** Screen the user was on when they left the app: home, live, catchup, vod or series. */
    var lastScreen: String
        get() = sp.getString("lastScreen", SCREEN_HOME) ?: SCREEN_HOME
        set(v) = sp.edit().putString("lastScreen", v).apply()

    const val SCREEN_HOME = "home"
    const val SCREEN_LIVE = "live"
    const val SCREEN_CATCHUP = "catchup"

    /** Last category per profile and screen. */
    fun lastCategory(profileId: String, screen: String): String = sp.getString("lastCat_${screen}_$profileId", "") ?: ""

    fun setLastCategory(profileId: String, screen: String, catId: String) =
        sp.edit().putString("lastCat_${screen}_$profileId", catId).apply()

    /** Last opened item per profile and screen (movie / series id). */
    fun lastItem(profileId: String, screen: String): String = sp.getString("lastItem_${screen}_$profileId", "") ?: ""

    fun setLastItem(profileId: String, screen: String, id: String) =
        sp.edit().putString("lastItem_${screen}_$profileId", id).apply()

    /** Picture enhancement preset id (see PictureMode). */
    var pictureMode: String
        get() = sp.getString("pictureMode", "enhanced") ?: "enhanced"
        set(v) = sp.edit().putString("pictureMode", v).apply()

    /** true = always play the highest-quality version of adaptive streams. */
    var maxQuality: Boolean
        get() = sp.getBoolean("maxQuality", false)
        set(v) = sp.edit().putBoolean("maxQuality", v).apply()

    var remindersJson: String
        get() = sp.getString("reminders", "[]") ?: "[]"
        set(v) = sp.edit().putString("reminders", v).apply()

    var recordingsJson: String
        get() = sp.getString("recordings", "[]") ?: "[]"
        set(v) = sp.edit().putString("recordings", v).apply()

    /** Sleep timer end time (ms), 0 = off. */
    var sleepAtMs: Long
        get() = sp.getLong("sleepAt", 0L)
        set(v) = sp.edit().putLong("sleepAt", v).apply()

    var autoPip: Boolean
        get() = sp.getBoolean("autoPip", true)
        set(v) = sp.edit().putBoolean("autoPip", v).apply()

    /** Index into the recording folders (0 = internal storage, 1+ = USB / SD card). */
    var recordingStorage: Int
        get() = sp.getInt("recStorage", 0)
        set(v) = sp.edit().putInt("recStorage", v).apply()

    var tmdbKey: String
        get() = sp.getString("tmdbKey", "") ?: ""
        set(v) = sp.edit().putString("tmdbKey", v.trim()).apply()

    var openSubtitlesKey: String
        get() = sp.getString("osKey", "") ?: ""
        set(v) = sp.edit().putString("osKey", v.trim()).apply()

    /** Comma-separated ISO codes for downloaded subtitles, e.g. "ar,en". */
    var subtitleLanguages: String
        get() = sp.getString("subLangs", "ar,en") ?: "ar,en"
        set(v) = sp.edit().putString("subLangs", v.replace(" ", "").lowercase()).apply()

    /** 0 small, 1 normal, 2 large, 3 extra large */
    var subtitleSize: Int
        get() = sp.getInt("subSize", 1)
        set(v) = sp.edit().putInt("subSize", v).apply()

    /** 0 white, 1 yellow, 2 cyan */
    var subtitleColor: Int
        get() = sp.getInt("subColor", 0)
        set(v) = sp.edit().putInt("subColor", v).apply()

    /** 0 outline, 1 dark box, 2 drop shadow */
    var subtitleBackground: Int
        get() = sp.getInt("subBg", 0)
        set(v) = sp.edit().putInt("subBg", v).apply()

    /** "en" or "ar" */
    var language: String
        get() = sp.getString("language", "en") ?: "en"
        set(v) = sp.edit().putString("language", v).apply()

    /** Accent colour theme id (see Tr.accents). */
    var accent: String
        get() = sp.getString("accent", "violet") ?: "violet"
        set(v) = sp.edit().putString("accent", v).apply()

    var lastUpdateCheck: Long
        get() = sp.getLong("lastUpdateCheck", 0L)
        set(v) = sp.edit().putLong("lastUpdateCheck", v).apply()

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

    fun setPosition(key: String, ms: Long, durationMs: Long = 0L) {
        if (key.isEmpty()) return
        if (ms <= 0L) sp.edit().remove("pos_$key").remove("posAt_$key").remove("dur_$key").apply()
        else {
            val e = sp.edit().putLong("pos_$key", ms).putLong("posAt_$key", System.currentTimeMillis())
            if (durationMs > 0) e.putLong("dur_$key", durationMs)
            e.apply()
        }
    }

    /** Length of the video saved with its resume position, or 0 when unknown. */
    fun duration(key: String): Long = if (key.isEmpty()) 0L else sp.getLong("dur_$key", 0L)

    /** IDs of items (keys "prefix + id") with a resume position, most recently watched first. */
    fun resumeIds(prefix: String): List<String> {
        val all = sp.all
        val full = "pos_$prefix"
        return all.keys.filter { it.startsWith(full) }
            .sortedByDescending { (all["posAt_" + it.removePrefix("pos_")] as? Long) ?: 0L }
            .map { it.removePrefix(full) }
    }

    /** Remembers which series an episode resume key belongs to (for the home Continue watching row). */
    fun setEpisodeSeries(key: String, seriesId: String) {
        if (sp.getString("epSeries_$key", null) != seriesId) sp.edit().putString("epSeries_$key", seriesId).apply()
    }

    fun episodeSeries(key: String): String = sp.getString("epSeries_$key", "") ?: ""

    // ---------- Generic recent lists ----------

    fun recentList(name: String): List<String> {
        val out = ArrayList<String>()
        try {
            val arr = JSONArray(sp.getString(name, "[]"))
            for (i in 0 until arr.length()) out.add(arr.getString(i))
        } catch (_: Exception) {
        }
        return out
    }

    fun saveRecentList(name: String, ids: List<String>) {
        sp.edit().putString(name, JSONArray(ids).toString()).apply()
    }

    fun pushRecent(name: String, id: String, max: Int = 30) {
        val list = ArrayList(recentList(name))
        list.remove(id)
        list.add(0, id)
        while (list.size > max) list.removeAt(list.size - 1)
        sp.edit().putString(name, JSONArray(list).toString()).apply()
    }

    fun clearResumePositions() {
        val e = sp.edit()
        for (k in sp.all.keys) if (k.startsWith("pos_") || k.startsWith("posAt_") || k.startsWith("dur_")) e.remove(k)
        e.apply()
    }
}
