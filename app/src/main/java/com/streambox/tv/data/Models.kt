package com.streambox.tv.data

data class Profile(
    val id: String,
    val name: String,
    val type: String,
    val server: String = "",
    val username: String = "",
    val password: String = "",
    val m3uUrl: String = "",
    val epgUrl: String = ""
) {
    val isXtream: Boolean get() = type == TYPE_XTREAM

    companion object {
        const val TYPE_XTREAM = "xtream"
        const val TYPE_M3U = "m3u"
    }
}

data class AccountInfo(
    val status: String = "",
    val expiryMs: Long = 0L,
    val maxConnections: String = "",
    val activeConnections: String = ""
)

data class Category(val id: String, val name: String)

data class Channel(
    val id: String,
    val num: Int,
    val name: String,
    val logo: String,
    val categoryId: String,
    val epgId: String,
    /** Direct URL for M3U playlists. Empty for Xtream (built on demand). */
    val url: String,
    val hasArchive: Boolean,
    val archiveDays: Int
)

data class Movie(
    val id: String,
    val name: String,
    val poster: String,
    val categoryId: String,
    val url: String,
    val rating: String,
    val ext: String
)

data class Series(
    val id: String,
    val name: String,
    val poster: String,
    val categoryId: String,
    val plot: String,
    val genre: String,
    val cast: String,
    val releaseDate: String,
    val rating: String
)

data class Episode(
    val id: String,
    val title: String,
    val season: Int,
    val num: Int,
    val url: String,
    val plot: String,
    val duration: String
)

data class Programme(
    val title: String,
    val desc: String,
    val startMs: Long,
    val endMs: Long,
    /** Start time exactly as the server sent it ("yyyy-MM-dd HH:mm:ss"), used for catch-up URLs. */
    val startRaw: String,
    val hasArchive: Boolean
)

data class MovieInfo(
    val plot: String = "",
    val cast: String = "",
    val director: String = "",
    val genre: String = "",
    val releaseDate: String = "",
    val duration: String = "",
    val rating: String = "",
    val backdrop: String = ""
)

/** One entry of the playback queue handed to the player. */
data class PlayItem(
    /** Unique key used to store the resume position. Empty = don't remember. */
    val key: String,
    val title: String,
    val url: String,
    val isLive: Boolean,
    val channelId: String = ""
)
