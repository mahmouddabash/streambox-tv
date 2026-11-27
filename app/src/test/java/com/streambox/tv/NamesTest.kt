package com.streambox.tv

import com.streambox.tv.data.Names
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NamesTest {
    @Test
    fun qualityRank() {
        assertEquals(4, Names.rank("beIN Sports 1 4K"))
        assertEquals(3, Names.rank("MBC 1 FHD"))
        assertEquals(2, Names.rank("MBC 1 HD"))
        assertEquals(2, Names.rank("MBC 1"))
        assertEquals(1, Names.rank("MBC 1 SD"))
    }

    @Test
    fun sameChannelInEveryQualityGroupsTogether() {
        val g = Names.group("MBC 1 FHD")
        assertEquals("mbc 1", g)
        assertEquals(g, Names.group("MBC 1 HD"))
        assertEquals(g, Names.group("MBC 1 ᴴᴰ"))
        assertTrue(Names.group("MBC 2 HD") != g)
    }

    @Test
    fun soundAlikeWorksAcrossArabicAndLatin() {
        assertEquals("skr", Names.skeleton("Saqr"))
        assertEquals("skr", Names.skeleton("صقر"))
        assertEquals("mhmd", Names.skeleton("Mohamed"))
        assertEquals("mhmd", Names.skeleton("محمد"))
    }

    @Test
    fun subtitleShift() {
        val srt = "1\n00:00:01,500 --> 00:00:03,000\nHi\n"
        assertEquals("1\n00:00:03,000 --> 00:00:04,500\nHi\n", Names.shiftSrt(srt, 1500))
        assertEquals("1\n00:00:00,000 --> 00:00:01,000\nHi\n", Names.shiftSrt(srt, -2000))
        assertEquals("00:59:59,900", Names.shiftSrt("00:59:59.400", 500))
        assertEquals("01:00:00,100", Names.shiftSrt("00:59:59,600", 500))
    }
}
