package com.streambox.tv

import com.streambox.tv.data.Tmdb
import org.junit.Assert.assertEquals
import org.junit.Test

class TitleCleanerTest {
    @Test
    fun removesProviderDecorationsAndKeepsYear() {
        assertEquals("The Matrix" to "1999", Tmdb.cleanTitle("EN - The Matrix (1999) [4K]"))
        assertEquals("Dune Part Two" to "2024", Tmdb.cleanTitle("Dune Part Two 2024 FHD"))
        assertEquals("Inception" to "", Tmdb.cleanTitle("Inception"))
    }
}
