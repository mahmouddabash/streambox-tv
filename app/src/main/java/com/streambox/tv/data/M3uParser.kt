package com.streambox.tv.data

import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader

class M3uResult {
    val liveCats = LinkedHashMap<String, Category>()
    val vodCats = LinkedHashMap<String, Category>()
    val channels = ArrayList<Channel>()
    val movies = ArrayList<Movie>()
    var epgUrl: String = ""
}

object M3uParser {
    private val ATTR = Regex("([a-zA-Z0-9_-]+)=\"([^\"]*)\"")
    private val VOD_EXT = listOf(".mp4", ".mkv", ".avi", ".mov", ".wmv", ".flv", ".mpg", ".mpeg", ".m4v", ".webm")

    fun parse(input: InputStream): M3uResult {
        val res = M3uResult()
        val reader = BufferedReader(InputStreamReader(input, Charsets.UTF_8))
        var attrs: Map<String, String> = emptyMap()
        var name = ""
        var group = ""
        var pending = false
        var n = 0

        for (raw in reader.lineSequence()) {
            val line = raw.trim().removePrefix("﻿")
            when {
                line.isEmpty() -> {
                }
                line.startsWith("#EXTM3U") -> {
                    val a = attributes(line)
                    res.epgUrl = (a["url-tvg"] ?: a["x-tvg-url"] ?: "").split(",").firstOrNull()?.trim() ?: ""
                }
                line.startsWith("#EXTINF") -> {
                    attrs = attributes(line)
                    val comma = lastCommaOutsideQuotes(line)
                    name = if (comma >= 0) line.substring(comma + 1).trim() else (attrs["tvg-name"] ?: "")
                    if (name.isEmpty()) name = attrs["tvg-name"] ?: "Unnamed"
                    group = attrs["group-title"] ?: ""
                    pending = true
                }
                line.startsWith("#EXTGRP:") -> {
                    if (group.isEmpty()) group = line.substring(8).trim()
                }
                line.startsWith("#") -> {
                }
                else -> {
                    if (!pending) {
                        attrs = emptyMap()
                        name = line.substringAfterLast('/')
                        group = ""
                    }
                    n++
                    val g = group.ifEmpty { "Uncategorized" }
                    val logo = attrs["tvg-logo"] ?: ""
                    if (isVod(line)) {
                        res.vodCats.getOrPut(g) { Category(g, g) }
                        val ext = line.substringBefore('?').substringAfterLast('.', "mp4")
                        res.movies.add(Movie("m$n", name, logo, g, line, "", ext))
                    } else {
                        res.liveCats.getOrPut(g) { Category(g, g) }
                        res.channels.add(
                            Channel(
                                id = "c$n",
                                num = res.channels.size + 1,
                                name = name,
                                logo = logo,
                                categoryId = g,
                                epgId = attrs["tvg-id"] ?: "",
                                url = line,
                                hasArchive = false,
                                archiveDays = 0
                            )
                        )
                    }
                    pending = false
                    group = ""
                }
            }
        }
        return res
    }

    private fun attributes(line: String): Map<String, String> {
        val m = HashMap<String, String>()
        for (match in ATTR.findAll(line)) m[match.groupValues[1].lowercase()] = match.groupValues[2]
        return m
    }

    private fun lastCommaOutsideQuotes(line: String): Int {
        var inQuotes = false
        var idx = -1
        for (i in line.indices) {
            val c = line[i]
            if (c == '"') inQuotes = !inQuotes
            else if (c == ',' && !inQuotes && idx < 0) idx = i
        }
        return idx
    }

    private fun isVod(url: String): Boolean {
        val path = url.substringBefore('?').lowercase()
        if (path.contains("/movie/") || path.contains("/series/")) return true
        return VOD_EXT.any { path.endsWith(it) }
    }
}
