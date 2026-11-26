package com.streambox.tv.ui

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec
import java.io.File
import java.io.RandomAccessFile

/**
 * Reads a file that is still being written (a recording in progress, or live TV paused on the
 * device): at the end of the data it waits for more instead of ending playback.
 */
@UnstableApi
class GrowingFileDataSource(private val stillGrowing: (String) -> Boolean) : BaseDataSource(false) {
    private var raf: RandomAccessFile? = null
    private var uri: Uri? = null
    private var path = ""
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        path = dataSpec.uri.path ?: ""
        transferInitializing(dataSpec)
        raf = RandomAccessFile(File(path), "r").apply { seek(dataSpec.position) }
        opened = true
        transferStarted(dataSpec)
        return C.LENGTH_UNSET.toLong()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        val f = raf ?: return C.RESULT_END_OF_INPUT
        var waited = 0
        while (true) {
            val n = f.read(buffer, offset, length)
            if (n > 0) {
                bytesTransferred(n)
                return n
            }
            if (!stillGrowing(path) || waited > 20_000) return C.RESULT_END_OF_INPUT
            Thread.sleep(80)
            waited += 80
        }
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        try { raf?.close() } catch (_: Exception) {}
        raf = null
        if (opened) {
            opened = false
            transferEnded()
        }
    }
}
