package com.streambox.tv

import com.streambox.tv.data.M3uParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class M3uParserTest {

    private fun parse(text: String) = M3uParser.parse(text.byteInputStream())

    @Test
    fun parsesChannelsWithGroupsLogosAndNames() {
        val r = parse(
            """
            #EXTM3U url-tvg="http://epg.example/guide.xml"
            #EXTINF:-1 tvg-id="bbc1" tvg-logo="http://logo/bbc.png" group-title="UK",BBC One
            http://example.com/live/1.ts
            #EXTINF:-1 group-title="News",Al Jazeera, English
            http://example.com/live/2.m3u8
            """.trimIndent()
        )
        assertEquals(2, r.channels.size)
        assertEquals("BBC One", r.channels[0].name)
        assertEquals("http://logo/bbc.png", r.channels[0].logo)
        assertEquals("bbc1", r.channels[0].epgId)
        assertEquals("Al Jazeera, English", r.channels[1].name)
        assertEquals(listOf("UK", "News"), r.liveCats.values.map { it.name })
        assertEquals("http://epg.example/guide.xml", r.epgUrl)
    }

    @Test
    fun sendsVideoFilesToMovies() {
        val r = parse(
            """
            #EXTM3U
            #EXTINF:-1 group-title="Films",Some Movie (2024)
            http://example.com/movie/u/p/77.mkv
            #EXTINF:-1,Live Channel
            http://example.com/live/u/p/5.ts
            """.trimIndent()
        )
        assertEquals(1, r.movies.size)
        assertEquals(1, r.channels.size)
        assertEquals("Some Movie (2024)", r.movies[0].name)
    }

    @Test
    fun toleratesBomBlankLinesAndMissingHeader() {
        val r = parse("﻿\n\n#EXTINF:-1,One\nhttp://a/1.ts\n\nhttp://a/2.ts\n")
        assertEquals(2, r.channels.size)
        assertTrue(r.channels[1].name.isNotBlank())
    }
}
