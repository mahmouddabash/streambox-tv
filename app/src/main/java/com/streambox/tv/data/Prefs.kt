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

    private val DEFAULT_PROFILE: Profile
        get() = Profile(
            id = "default",
            name = "My IPTV",
            type = Profile.TYPE_XTREAM,
            server = Secret.reveal(Secret.DEFAULT_SERVER),
            username = Secret.reveal(Secret.DEFAULT_USER),
            password = Secret.reveal(Secret.DEFAULT_PASS)
        )

    /** The built-in profile: its login is hidden and it needs the profile password. */
    fun isProtected(p: Profile?) = p?.id == "default"

    /** Set once the profile password has been entered on this device. */
    var protectedUnlocked: Boolean
        get() = sp.getBoolean("protectedUnlocked", false)
        set(v) = sp.edit().putBoolean("protectedUnlocked", v).apply()

    /**
     * Adds the built-in playlist once per version; deleting it afterwards keeps it deleted.
     * When the built-in login changes, an existing default profile is updated in place.
     */
    private fun seedDefaultProfile(context: Context) {
        if (!Secret.BUILT_IN) return
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

    /** Decrypted profiles kept in memory, so the Keystore is only used when they change. */
    @Volatile
    private var profileCache: List<Profile>? = null

    /**
     * Profiles JSON. Logins are only ever stored encrypted; a plain-text copy left by an old build
     * is encrypted on first read and then deleted.
     */
    private fun profilesJson(): String {
        val enc = sp.getString("profiles_enc", null)
        if (enc != null) Vault.decrypt(enc)?.let { return it }
        val plain = sp.getString("profiles", null) ?: return "[]"
        Vault.encryptOrRepair(plain)?.let { sp.edit().putString("profiles_enc", it).remove("profiles").apply() }
            ?: sp.edit().remove("profiles").apply()
        return plain
    }

    /** True when the last save couldn't be encrypted, so logins are kept only until the app closes. */
    @Volatile
    var profilesNotSaved = false
        private set

    fun profiles(): List<Profile> {
        profileCache?.let { return it }
        val out = ArrayList<Profile>()
        try {
            val arr = JSONArray(profilesJson())
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
        profileCache = out
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
        profileCache = list
        val json = arr.toString()
        // Never written to disk unencrypted: if the TV can't encrypt, keep them in memory only.
        val enc = Vault.encryptOrRepair(json)
        profilesNotSaved = enc == null
        if (enc != null) sp.edit().putString("profiles_enc", enc).remove("profiles").apply()
        else sp.edit().remove("profiles").apply()
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

    /**
     * Accepts either TMDB credential: the 32-character API key, or the long "Read Access Token"
     * (whose "aud" field is that same API key).
     */
    private fun v3Key(s: String): String {
        val t = s.trim()
        if (!t.startsWith("eyJ") || t.count { it == '.' } != 2) return t
        return try {
            val body = String(android.util.Base64.decode(t.split('.')[1], android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING))
            JSONObject(body).optString("aud").ifBlank { t }
        } catch (_: Exception) {
            t
        }
    }

    /** A key typed in Settings (the built-in one is never shown). */
    val ownTmdbKey: String get() = sp.getString("tmdbKey", "") ?: ""

    /** The TMDB key typed in Settings, else the one built into the app. */
    var tmdbKey: String
        get() = v3Key(sp.getString("tmdbKey", "") ?: "").ifBlank {
            if (Secret.TMDB_KEY.isEmpty()) "" else try { Secret.reveal(Secret.TMDB_KEY) } catch (_: Exception) { "" }
        }
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

    /** Build number the user last saw, for the "What's new" message after an update. */
    var lastSeenBuild: Int
        get() = sp.getInt("lastSeenBuild", 0)
        set(v) = sp.edit().putInt("lastSeenBuild", v).apply()

    /** Preferred audio language (ISO code), "" = the stream's default. */
    var audioLanguage: String
        get() = sp.getString("audioLang", "") ?: ""
        set(v) = sp.edit().putString("audioLang", v).apply()

    /** Subtitles to switch on automatically (ISO code), "" = off. */
    var subtitleLanguage: String
        get() = sp.getString("subLang", "") ?: ""
        set(v) = sp.edit().putString("subLang", v).apply()

    // ---------- Channel manager (per provider profile) ----------

    fun hiddenChannels(pid: String): Set<String> = sp.getStringSet("hideCh_$pid", emptySet()) ?: emptySet()
    fun setChannelHidden(pid: String, id: String, hidden: Boolean) {
        val s = HashSet(hiddenChannels(pid))
        if (hidden) s.add(id) else s.remove(id)
        sp.edit().putStringSet("hideCh_$pid", s).apply()
    }

    /** Entries are "section:categoryId". */
    fun hiddenCategories(pid: String): Set<String> = sp.getStringSet("hideCat_$pid", emptySet()) ?: emptySet()
    fun setCategoryHidden(pid: String, key: String, hidden: Boolean) {
        val s = HashSet(hiddenCategories(pid))
        if (hidden) s.add(key) else s.remove(key)
        sp.edit().putStringSet("hideCat_$pid", s).apply()
    }

    fun channelNames(pid: String): Map<String, String> {
        val out = HashMap<String, String>()
        try {
            val o = JSONObject(sp.getString("rename_$pid", "{}") ?: "{}")
            for (k in o.keys()) out[k] = o.getString(k)
        } catch (_: Exception) {
        }
        return out
    }

    /** Blank [name] restores the provider's name. */
    fun setChannelName(pid: String, id: String, name: String) {
        val m = HashMap(channelNames(pid))
        if (name.isBlank()) m.remove(id) else m[id] = name.trim()
        sp.edit().putString("rename_$pid", JSONObject(m as Map<*, *>).toString()).apply()
    }

    fun categoryOrder(pid: String, section: String): List<String> = recentList("catOrder_${section}_$pid")
    fun setCategoryOrder(pid: String, section: String, ids: List<String>) = saveRecentList("catOrder_${section}_$pid", ids)

    /** 0 = provider order, 1 = A–Z, 2 = channel number */
    var channelSort: Int
        get() = sp.getInt("chSort", 0)
        set(v) = sp.edit().putInt("chSort", v).apply()

    fun clearChannelManager(pid: String) {
        val e = sp.edit().remove("hideCh_$pid").remove("hideCat_$pid").remove("rename_$pid")
        for (s in listOf("live", "vod", "series")) e.remove("catOrder_${s}_$pid")
        e.apply()
    }

    // ---------- Viewers (per provider profile) ----------

    fun viewers(pid: String): List<Viewer> {
        val out = ArrayList<Viewer>()
        try {
            val arr = JSONArray(sp.getString("viewers_$pid", "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(Viewer(o.getString("id"), o.optString("name"), o.optBoolean("kids"), o.optInt("av", i + 1)))
            }
        } catch (_: Exception) {
        }
        return out
    }

    fun saveViewers(pid: String, list: List<Viewer>) {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("id", it.id).put("name", it.name).put("kids", it.kids).put("av", it.avatar)) }
        sp.edit().putString("viewers_$pid", arr.toString()).apply()
    }

    fun currentViewer(pid: String): String = sp.getString("viewer_$pid", "") ?: ""
    fun setCurrentViewer(pid: String, id: String) = sp.edit().putString("viewer_$pid", id).apply()

    var mainViewerAvatar: Int
        get() = sp.getInt("mainAvatar", 0)
        set(v) = sp.edit().putInt("mainAvatar", v).apply()

    var mainViewerName: String
        get() = sp.getString("mainViewer", "Me") ?: "Me"
        set(v) = sp.edit().putString("mainViewer", v.ifBlank { "Me" }).apply()

    /** Categories ("section:id") the user allowed in Kids profiles. */
    fun kidsCategories(pid: String): Set<String> = sp.getStringSet("kidsCat_$pid", emptySet()) ?: emptySet()
    fun setKidsCategory(pid: String, key: String, allowed: Boolean) {
        val s = HashSet(kidsCategories(pid))
        if (allowed) s.add(key) else s.remove(key)
        sp.edit().putStringSet("kidsCat_$pid", s).apply()
    }

    // ---------- Backup ----------

    /** Keys that only make sense on this TV (device-bound encryption, one-time unlocks). */
    private val notBackedUp = setOf("profiles_enc", "profiles", "protectedUnlocked", "lastUpdateCheck", "lastSeenBuild")

    fun exportAll(): JSONObject {
        val prefs = JSONObject()
        for ((k, v) in sp.all) {
            if (k in notBackedUp || v == null) continue
            val e = JSONObject()
            when (v) {
                is String -> e.put("t", "s").put("v", v)
                is Int -> e.put("t", "i").put("v", v)
                is Long -> e.put("t", "l").put("v", v)
                is Boolean -> e.put("t", "b").put("v", v)
                is Float -> e.put("t", "f").put("v", v.toDouble())
                is Set<*> -> e.put("t", "set").put("v", JSONArray(v.map { it.toString() }))
                else -> continue
            }
            prefs.put(k, e)
        }
        val profiles = JSONArray()
        for (p in profiles()) {
            profiles.put(JSONObject().put("id", p.id).put("name", p.name).put("type", p.type)
                .put("server", p.server).put("username", p.username).put("password", p.password)
                .put("m3uUrl", p.m3uUrl).put("epgUrl", p.epgUrl))
        }
        return JSONObject().put("version", 1).put("prefs", prefs).put("profiles", profiles)
    }

    fun importAll(o: JSONObject) {
        val prefs = o.getJSONObject("prefs")
        val e = sp.edit()
        for (k in prefs.keys()) {
            if (k in notBackedUp) continue
            val item = prefs.getJSONObject(k)
            when (item.optString("t")) {
                "s" -> e.putString(k, item.getString("v"))
                "i" -> e.putInt(k, item.getInt("v"))
                "l" -> e.putLong(k, item.getLong("v"))
                "b" -> e.putBoolean(k, item.getBoolean("v"))
                "f" -> e.putFloat(k, item.getDouble("v").toFloat())
                "set" -> {
                    val arr = item.getJSONArray("v")
                    e.putStringSet(k, (0 until arr.length()).map { arr.getString(it) }.toSet())
                }
            }
        }
        e.commit()
        val arr = o.optJSONArray("profiles") ?: JSONArray()
        val list = ArrayList<Profile>()
        for (i in 0 until arr.length()) {
            val p = arr.getJSONObject(i)
            list.add(Profile(id = p.optString("id"), name = p.optString("name"), type = p.optString("type"),
                server = p.optString("server"), username = p.optString("username"), password = p.optString("password"),
                m3uUrl = p.optString("m3uUrl"), epgUrl = p.optString("epgUrl")))
        }
        if (list.isNotEmpty()) saveProfiles(list)
    }

    /** Drive for new downloads: 0 = TV storage, 1+ = USB drive / SD card. */
    var downloadStorage: Int
        get() = sp.getInt("downloadStorage", 0)
        set(v) = sp.edit().putInt("downloadStorage", v).apply()

    /** Always decode audio in software (car units / boxes with no sound on Dolby channels). */
    var softwareAudio: Boolean
        get() = sp.getBoolean("softwareAudio", false)
        set(v) = sp.edit().putBoolean("softwareAudio", v).apply()

    /**
     * Mix surround sound down to stereo. On by default on touch screens (car units, phones, tablets),
     * off on TVs, which usually feed a soundbar or receiver.
     */
    var stereoAudio: Boolean
        get() = if (sp.contains("stereoAudio")) sp.getBoolean("stereoAudio", false) else isTouchDevice
        set(v) = sp.edit().putBoolean("stereoAudio", v).apply()

    /** "tv" (remote control) or "touch" (car screen, phone, tablet), chosen on first start; "" = not yet. */
    var deviceMode: String
        get() = sp.getString("deviceMode", "") ?: ""
        set(v) = sp.edit().putString("deviceMode", v).apply()

    /** What the hardware looks like (touch screen and not a TV), used until a choice is made. */
    @Volatile
    var detectedTouch = false

    /** Touch layout and controls (back arrows, gestures, stereo sound by default…). */
    val isTouchDevice: Boolean
        get() = when (deviceMode) {
            "touch" -> true
            "tv" -> false
            else -> detectedTouch
        }

    /** Video fit chosen in the player: 0 Fit, 1 Stretch, 2 Zoom (remembered). */
    var videoFit: Int
        get() = sp.getInt("videoFit", 0)
        set(v) = sp.edit().putInt("videoFit", v).apply()

    /** Open straight into the last channel watched when the app starts. */
    var startLastChannel: Boolean
        get() = sp.getBoolean("startLastChannel", false)
        set(v) = sp.edit().putBoolean("startLastChannel", v).apply()

    /** Number buttons in the player: false = channel numbers, true = favourites (1 = first favourite). */
    var numberKeysFavourites: Boolean
        get() = sp.getBoolean("numKeysFav", false)
        set(v) = sp.edit().putBoolean("numKeysFav", v).apply()

    /** Start from the saved channel list at once and refresh it in the background. */
    var fastStart: Boolean
        get() = sp.getBoolean("fastStart", true)
        set(v) = sp.edit().putBoolean("fastStart", v).apply()

    /** Last known subscription details per provider profile, shown until the background refresh. */
    fun cachedAccount(profileId: String): AccountInfo? = try {
        sp.getString("acc_$profileId", null)?.let {
            val o = JSONObject(it)
            AccountInfo(o.optString("s"), o.optLong("e"), o.optString("m"), o.optString("a"))
        }
    } catch (_: Exception) {
        null
    }

    fun saveAccount(profileId: String, a: AccountInfo) {
        sp.edit().putString("acc_$profileId", JSONObject().put("s", a.status).put("e", a.expiryMs)
            .put("m", a.maxConnections).put("a", a.activeConnections).toString()).apply()
    }

    /** "Watch later" list per viewer: "m:<movieId>" / "s:<seriesId>", newest first. */
    fun watchLater(userKey: String): List<String> = try {
        val a = org.json.JSONArray(sp.getString("wl_$userKey", "[]"))
        (0 until a.length()).map { a.getString(it) }
    } catch (_: Exception) {
        emptyList()
    }

    fun setWatchLater(userKey: String, item: String, on: Boolean) {
        val l = watchLater(userKey).filterNot { it == item }
        val n = if (on) listOf(item) + l else l
        sp.edit().putString("wl_$userKey", org.json.JSONArray(n.take(200)).toString()).apply()
    }

    /** Favourite channels shown on the home-screen widget: "id\tname" lines. */
    var widgetChannels: String
        get() = sp.getString("widgetChannels", "") ?: ""
        set(v) = sp.edit().putString("widgetChannels", v).apply()

    /** Stream type that worked for a channel ("ts" / "m3u8"), when it differs from the usual one. */
    fun channelFormat(channelId: String): String = sp.getString("chfmt_$channelId", "") ?: ""

    fun setChannelFormat(channelId: String, ext: String) {
        sp.edit().apply { if (ext.isEmpty() || ext == streamFormat) remove("chfmt_$channelId") else putString("chfmt_$channelId", ext) }.apply()
    }

    /** Where a series' opening titles end (ms), set from the player; 0 = not set. */
    fun introEnd(seriesId: String): Long = sp.getLong("intro_$seriesId", 0L)

    fun setIntroEnd(seriesId: String, ms: Long) {
        sp.edit().apply { if (ms <= 0) remove("intro_$seriesId") else putLong("intro_$seriesId", ms) }.apply()
    }

    /** Skip a series' intro by itself once it has been marked (instead of showing the button). */
    var autoSkipIntro: Boolean
        get() = sp.getBoolean("autoSkipIntro", false)
        set(v) = sp.edit().putBoolean("autoSkipIntro", v).apply()

    /** Picture quality vs data: 0 = save data on mobile / hotspot, 1 = always best, 2 = always save data. */
    var dataSaver: Int
        get() = sp.getInt("dataSaver", 0)
        set(v) = sp.edit().putInt("dataSaver", v).apply()

    /** Titles a viewer hid ("m:<id>" / "s:<id>"): left out of every row, list and search. */
    fun hidden(userKey: String): Set<String> = sp.getStringSet("hid_$userKey", emptySet()) ?: emptySet()

    fun setHidden(userKey: String, item: String, on: Boolean) {
        val s = HashSet(hidden(userKey))
        if (on) s.add(item) else s.remove(item)
        sp.edit().putStringSet("hid_$userKey", s).apply()
    }

    /** Night mode: 0 off, 1 from 22:00 to 06:00, 2 always. */
    var nightMode: Int
        get() = sp.getInt("nightMode", 0)
        set(v) = sp.edit().putInt("nightMode", v).apply()

    /** Viewing time per viewer: total, live, movies, series (ms) and per-channel minutes. */
    fun addWatchTime(userKey: String, kind: String, channelId: String, ms: Long) {
        val o = try { JSONObject(sp.getString("stats_$userKey", "{}") ?: "{}") } catch (_: Exception) { JSONObject() }
        o.put("total", o.optLong("total") + ms)
        o.put(kind, o.optLong(kind) + ms)
        if (channelId.isNotEmpty()) {
            val ch = o.optJSONObject("ch") ?: JSONObject()
            ch.put(channelId, ch.optLong(channelId) + ms)
            o.put("ch", ch)
        }
        if (!o.has("since")) o.put("since", System.currentTimeMillis())
        sp.edit().putString("stats_$userKey", o.toString()).apply()
    }

    fun watchStats(userKey: String): JSONObject =
        try { JSONObject(sp.getString("stats_$userKey", "{}") ?: "{}") } catch (_: Exception) { JSONObject() }

    /** Sort order in the Movies / Series grids. */
    var sortMode: Int
        get() = sp.getInt("sortMode", 0)
        set(v) = sp.edit().putInt("sortMode", v).apply()

    /** DNS: 0 automatic (the TV's own, private DNS when it fails), 1 Cloudflare private DNS, 2 Google private DNS. */
    var dnsMode: Int
        get() = sp.getInt("dnsMode", 0)
        set(v) = sp.edit().putInt("dnsMode", v).apply()

    private fun monthKey(): String = java.text.SimpleDateFormat("yyyyMM", java.util.Locale.US).format(java.util.Date())

    /** Data used by StreamBox this month, split into Wi-Fi / Ethernet and mobile data / hotspot. */
    fun addDataUsed(bytes: Long, mobile: Boolean) {
        if (bytes <= 0) return
        val k = "data_${monthKey()}_" + if (mobile) "m" else "w"
        sp.edit().putLong(k, sp.getLong(k, 0L) + bytes).apply()
    }

    fun dataUsed(mobile: Boolean): Long = sp.getLong("data_${monthKey()}_" + if (mobile) "m" else "w", 0L)

    fun resetDataUsed() {
        sp.edit().remove("data_${monthKey()}_m").remove("data_${monthKey()}_w").apply()
    }

    /** Show one entry per channel with its qualities (4K / FHD / HD / SD) grouped. */
    var groupQualities: Boolean
        get() = sp.getBoolean("groupQualities", true)
        set(v) = sp.edit().putBoolean("groupQualities", v).apply()

    /** Preferred quality for grouped channels: 0 best, 1 up to Full HD, 2 up to HD, 3 SD (save data). */
    var preferredQuality: Int
        get() = sp.getInt("prefQuality", 0)
        set(v) = sp.edit().putInt("prefQuality", v).apply()

    /** A quality picked by hand for one channel group (channel id), overriding [preferredQuality]. */
    fun groupChoice(group: String): String = sp.getString("qg_$group", "") ?: ""

    fun setGroupChoice(group: String, channelId: String) {
        sp.edit().putString("qg_$group", channelId).apply()
    }

    /** Kids viewer limits: minutes per day (0 = none) and bedtime hour (−1 = none; ends 06:00). */
    fun kidsLimit(viewerId: String): Int = sp.getInt("kidLim_$viewerId", 0)
    fun setKidsLimit(viewerId: String, min: Int) = sp.edit().putInt("kidLim_$viewerId", min).apply()
    fun kidsBedtime(viewerId: String): Int = sp.getInt("kidBed_$viewerId", -1)
    fun setKidsBedtime(viewerId: String, hour: Int) = sp.edit().putInt("kidBed_$viewerId", hour).apply()

    private fun dayKey() = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).format(java.util.Date())
    fun kidsUsedMs(viewerId: String): Long = sp.getLong("kidUse_${viewerId}_${dayKey()}", 0L)
    fun addKidsUsed(viewerId: String, ms: Long) {
        val k = "kidUse_${viewerId}_${dayKey()}"
        sp.edit().putLong(k, sp.getLong(k, 0L) + ms).apply()
    }

    /** After a parent enters the PIN, watching may continue until this time. */
    var kidsUnlockedUntil: Long
        get() = sp.getLong("kidUnlock", 0L)
        set(v) = sp.edit().putLong("kidUnlock", v).apply()

    /** Match the TV's refresh rate to the video: 0 off, 1 movies and series, 2 always. */
    var matchFrameRate: Int
        get() = sp.getInt("matchFps", 1)
        set(v) = sp.edit().putInt("matchFps", v).apply()

    /** Keep loud and quiet channels/films at a similar volume (Android 9+). */
    var evenVolume: Boolean
        get() = sp.getBoolean("evenVolume", false)
        set(v) = sp.edit().putBoolean("evenVolume", v).apply()

    /** Pause live TV on channels without the provider's archive by saving it on the device. */
    var localPause: Boolean
        get() = sp.getBoolean("localPause", true)
        set(v) = sp.edit().putBoolean("localPause", v).apply()

    /** Monthly limit for mobile data / hotspot in GB (0 = none); alerts at 80 % and 100 %. */
    var dataQuotaGb: Int
        get() = sp.getInt("dataQuota", 0)
        set(v) = sp.edit().putInt("dataQuota", v).apply()

    /** Highest quota alert already shown this month (0, 80 or 100). */
    var quotaAlerted: Int
        get() = if (sp.getString("quotaMonth", "") == monthKey()) sp.getInt("quotaAlert", 0) else 0
        set(v) = sp.edit().putString("quotaMonth", monthKey()).putInt("quotaAlert", v).apply()

    /** Download the next N episodes while watching a series (0 = off; Wi-Fi only). */
    var autoDownloadNext: Int
        get() = sp.getInt("autoDlNext", 0)
        set(v) = sp.edit().putInt("autoDlNext", v).apply()

    /** Delete downloads this many days after they were watched to the end (0 = keep). */
    var cleanupWatchedDays: Int
        get() = sp.getInt("cleanupDays", 0)
        set(v) = sp.edit().putInt("cleanupDays", v).apply()

    /** Download speed limit in Mbps (0 = no limit). */
    var downloadLimitMbps: Int
        get() = sp.getInt("dlLimit", 0)
        set(v) = sp.edit().putInt("dlLimit", v).apply()

    /** When a film or episode was watched to the end (for cleanup). */
    fun watchedAt(key: String): Long = sp.getLong("watched_$key", 0L)
    fun setWatchedAt(key: String) = sp.edit().putLong("watched_$key", System.currentTimeMillis()).apply()

    /** Episodes seen last time for a followed series, and series that got new ones. */
    fun episodeCount(seriesId: String): Int = sp.getInt("epc_$seriesId", -1)
    fun setEpisodeCount(seriesId: String, n: Int) = sp.edit().putInt("epc_$seriesId", n).apply()
    var newEpisodeSeries: Set<String>
        get() = sp.getStringSet("newEpSeries", emptySet()) ?: emptySet()
        set(v) = sp.edit().putStringSet("newEpSeries", v).apply()
    var lastEpisodeCheck: Long
        get() = sp.getLong("lastEpCheck", 0L)
        set(v) = sp.edit().putLong("lastEpCheck", v).apply()

    /** Show one entry per film, with its 4K / FHD / HD copies inside. */
    var groupMovieCopies: Boolean
        get() = sp.getBoolean("groupMovies", true)
        set(v) = sp.edit().putBoolean("groupMovies", v).apply()

    /** True-black background (OLED screens). */
    var oledBlack: Boolean
        get() = sp.getBoolean("oledBlack", false)
        set(v) = sp.edit().putBoolean("oledBlack", v).apply()

    /** Light mode for slow devices: no fades or picture effects. */
    var lightMode: Boolean
        get() = sp.getBoolean("lightMode", false)
        set(v) = sp.edit().putBoolean("lightMode", v).apply()

    /** Text size: 0 small, 1 normal, 2 large, 3 extra large. */
    var textSize: Int
        get() = sp.getInt("textSize", 1)
        set(v) = sp.edit().putInt("textSize", v).apply()
    val textScale: Float get() = floatArrayOf(0.88f, 1f, 1.15f, 1.3f)[textSize.coerceIn(0, 3)]

    /** Poster size in grids: 0 small, 1 normal, 2 large. */
    var posterSize: Int
        get() = sp.getInt("posterSize", 1)
        set(v) = sp.edit().putInt("posterSize", v).apply()

    /** Wrong PIN attempts in a row, and until when PIN entry is locked. */
    var pinFails: Int
        get() = sp.getInt("pinFails", 0)
        set(v) = sp.edit().putInt("pinFails", v).apply()
    var pinLockedUntil: Long
        get() = sp.getLong("pinLocked", 0L)
        set(v) = sp.edit().putLong("pinLocked", v).apply()

    var downloadsJson: String
        get() = sp.getString("downloads", "[]") ?: "[]"
        set(v) = sp.edit().putString("downloads", v).apply()

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
