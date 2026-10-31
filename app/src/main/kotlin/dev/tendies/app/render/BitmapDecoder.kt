package dev.tendies.app.render

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import dev.tendies.core.render.Img
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.roundToInt

/** Decodes PNG / JPEG / WebP / HEIC (API 28+) from memory. Very large images are down-sampled while decoding. */
object BitmapDecoder {
    fun decode(bytes: ByteArray, maxDim: Int = 2048): Img? {
        try {
            var lw = 0
            var lh = 0
            val src = ImageDecoder.createSource(ByteBuffer.wrap(bytes))
            val bmp = ImageDecoder.decodeBitmap(src) { decoder, info, _ ->
                lw = info.size.width
                lh = info.size.height
                // software bitmaps: needed so they can also be drawn into offscreen (blur) canvases
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val m = max(lw, lh)
                if (m > maxDim) {
                    val k = maxDim.toFloat() / m
                    decoder.setTargetSize(max(1, (lw * k).roundToInt()), max(1, (lh * k).roundToInt()))
                }
            }
            return AndroidImg(bmp, lw, lh)
        } catch (e: Throwable) {
            // fall through to BitmapFactory
        }
        return try {
            val o = BitmapFactory.Options()
            o.inPreferredConfig = Bitmap.Config.ARGB_8888
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o) ?: return null
            AndroidImg(bmp, bmp.width, bmp.height)
        } catch (e: Throwable) {
            null
        }
    }
}
