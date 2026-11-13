package com.streambox.tv.ui

import android.graphics.Bitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter

object Qr {
    /** Renders [text] as a black-on-white QR bitmap, or null if it cannot be encoded. */
    fun bitmap(text: String, size: Int = 480): Bitmap? = try {
        val hints = mapOf(EncodeHintType.MARGIN to 2)
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints)
        val w = matrix.width
        val h = matrix.height
        val pixels = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                pixels[y * w + x] = if (matrix.get(x, y)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
            }
        }
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565)
        bmp.setPixels(pixels, 0, w, 0, 0, w, h)
        bmp
    } catch (_: Exception) {
        null
    }
}
