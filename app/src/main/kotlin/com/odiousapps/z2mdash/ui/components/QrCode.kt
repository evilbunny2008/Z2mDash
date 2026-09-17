package com.odiousapps.z2mdash.ui.components

import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter

/**
 * Returns null (rather than throwing) on any encoding failure - the
 * credential-share UI falls back to the plain-text code display in
 * that case, so a QR generation hiccup shouldn't block sharing entirely.
 *
 * CPU-bound, not network I/O, but still worth calling from a background
 * dispatcher (Dispatchers.Default) rather than directly during
 * composition, to avoid any chance of jank on the main thread.
 */
fun generateQrCodeBitmap(content: String, sizePx: Int = 512): ImageBitmap? {
    return try {
        val writer = QRCodeWriter()
        val bitMatrix = writer.encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx)
        val pixels = IntArray(sizePx * sizePx)
        for (y in 0 until sizePx) {
            for (x in 0 until sizePx) {
                pixels[y * sizePx + x] =
                    if (bitMatrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE
            }
        }
        Bitmap.createBitmap(pixels, sizePx, sizePx, Bitmap.Config.RGB_565).asImageBitmap()
    } catch (_: Exception) {
        null
    }
}
