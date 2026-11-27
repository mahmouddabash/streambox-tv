package com.streambox.tv

import com.streambox.tv.data.IntroFinder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.random.Random

class IntroFinderTest {
    private fun noise(n: Int, seed: Int): List<Long?> { val r = Random(seed); return List(n) { r.nextLong() } }
    private val intro = noise(20, 99) // 40 s of shared opening titles

    @Test
    fun findsSharedOpeningAtSameTime() {
        val a = noise(5, 1) + intro + noise(40, 2)
        val b = noise(5, 3) + intro + noise(40, 4)
        val f = IntroFinder.match(a, b, 2_000)!!
        assertEquals(10_000L, f.startMs)
        assertEquals(50_000L, f.endMs)
    }

    @Test
    fun toleratesSmallShiftAndOneOddFrame() {
        val damaged = intro.toMutableList().also { it[7] = 12345L }
        val a = noise(5, 1) + intro + noise(40, 2)
        val b = noise(6, 3) + damaged + noise(40, 4) // starts 2 s later
        val f = IntroFinder.match(a, b, 2_000)!!
        assertEquals(50_000L, f.endMs)
    }

    @Test
    fun nothingSharedOrOnlyBlackGivesNothing() {
        assertNull(IntroFinder.match(noise(60, 1), noise(60, 2), 2_000))
        val black = List<Long?>(30) { 0L }
        assertNull(IntroFinder.match(black + noise(30, 1), black + noise(30, 2), 2_000))
    }

    @Test
    fun differenceHash() {
        val flat = IntArray(72) { 100 }
        assertEquals(0L, IntroFinder.dHash(flat))
        val falling = IntArray(72) { 200 - (it % 9) * 10 }
        assertEquals(-1L, IntroFinder.dHash(falling))
    }
}
