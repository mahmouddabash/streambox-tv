package com.streambox.tv

import com.streambox.tv.data.TsIndex
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TsIndexTest {
    private fun tmp(bytes: ByteArray) = File.createTempFile("sbtest", ".ts").apply { writeBytes(bytes); deleteOnExit() }

    @Test
    fun recognisesTransportStreams() {
        val ts = ByteArray(188 * 3).also { it[0] = 0x47; it[188] = 0x47; it[376] = 0x47 }
        assertTrue(TsIndex.isTs(tmp(ts)))
        assertFalse(TsIndex.isTs(tmp("#EXTM3U\n".toByteArray())))
        assertFalse(TsIndex.isTs(File("/does/not/exist.ts")))
    }

    @Test
    fun brokenOrForeignFilesAreLeftAlone() {
        assertFalse(TsIndex.build(tmp(ByteArray(1000) { 1 })))
        val f = tmp(ByteArray(188 * 2).also { it[0] = 0x47; it[188] = 0x47 })
        assertFalse(TsIndex.hasIndex(f))
        // A TS file without any video should not crash or produce an index.
        assertFalse(TsIndex.build(f))
        assertFalse(TsIndex.hasIndex(f))
    }
}
