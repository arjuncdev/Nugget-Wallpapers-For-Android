package dev.tendies.app.render

import android.graphics.Bitmap
import android.graphics.BlendMode
import android.graphics.BlendModeColorFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import dev.tendies.core.caml.PathData
import dev.tendies.core.render.Blend
import dev.tendies.core.render.Canvas2D
import dev.tendies.core.render.Img
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * A decoded bitmap. [width]/[height] report the *logical* (original) pixel size even if the bitmap was
 * down-sampled while decoding, so contents-gravity maths in the engine stays correct.
 */
class AndroidImg(val bitmap: Bitmap, override val width: Int, override val height: Int) : Img

/**
 * [Canvas2D] on top of android.graphics.Canvas. Works with both software and hardware canvases:
 *  - groups use saveLayer (alpha / BlendMode / ColorMatrix applied when the layer is composited back);
 *  - blurred groups are rendered into a small offscreen bitmap and scaled up with filtering (Canvas has no
 *    portable layer blur before API 31, and this is cheap enough for battery-friendly wallpapers);
 *  - perspective 3x3 matrices are passed straight to Matrix.setValues.
 */
class AndroidCanvas : Canvas2D {
    private var cv: Canvas? = null
    private var surfaceW = 1
    private var surfaceH = 1

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val layerPaint = Paint()
    private val maskPaint = Paint().also { it.blendMode = BlendMode.DST_IN }
    private val scratchMatrix = Matrix()
    private val values = FloatArray(9)
    private val srcRect = Rect()
    private val dstRect = RectF()
    private val tmpPath = Path()
    private val tmpRect = RectF()

    private var ctm = Matrix()
    private val ctmStack = ArrayList<Matrix>()

    private class Group(
        val blur: Boolean, val parent: Canvas, val bitmap: Bitmap?, val k: Float, val inv: Matrix?,
        val alpha: Double, val blend: Blend, val cm: DoubleArray?
    )

    private val groups = ArrayList<Group>()
    private val bitmapPool = HashMap<Long, Bitmap>()
    private val pathCache = object : LinkedHashMap<PathData, Path>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<PathData, Path>?): Boolean = size > 48
    }

    /** Must be called once per frame before the engine draws. [w]/[h] are the surface size in pixels. */
    fun begin(canvas: Canvas, w: Int, h: Int) {
        cv = canvas
        surfaceW = max(1, w)
        surfaceH = max(1, h)
        ctm = Matrix()
        ctmStack.clear()
        groups.clear()
    }

    fun release() { bitmapPool.values.forEach { it.recycle() }; bitmapPool.clear(); pathCache.clear() }

    private val c: Canvas get() = cv!!

    // ---------------------------------------------------------------- state

    override fun save() { ctmStack.add(Matrix(ctm)); c.save() }

    override fun restore() {
        if (ctmStack.isNotEmpty()) ctm = ctmStack.removeAt(ctmStack.size - 1)
        c.restore()
    }

    override fun concat(m: DoubleArray) {
        for (i in 0 until 9) values[i] = m[i].toFloat()
        scratchMatrix.setValues(values)
        c.concat(scratchMatrix)
        ctm.preConcat(scratchMatrix)
    }

    override fun clipRect(x: Double, y: Double, w: Double, h: Double) {
        c.clipRect(x.toFloat(), y.toFloat(), (x + w).toFloat(), (y + h).toFloat())
    }

    override fun clipRoundRect(x: Double, y: Double, w: Double, h: Double, r: Double) {
        tmpPath.reset()
        tmpRect.set(x.toFloat(), y.toFloat(), (x + w).toFloat(), (y + h).toFloat())
        tmpPath.addRoundRect(tmpRect, r.toFloat(), r.toFloat(), Path.Direction.CW)
        c.clipPath(tmpPath)
    }

    // ----------------------------------------------------------------- groups

    private fun blendMode(b: Blend): BlendMode = when (b) {
        Blend.NORMAL -> BlendMode.SRC_OVER
        Blend.MULTIPLY -> BlendMode.MULTIPLY
        Blend.SCREEN -> BlendMode.SCREEN
        Blend.OVERLAY -> BlendMode.OVERLAY
        Blend.DARKEN -> BlendMode.DARKEN
        Blend.LIGHTEN -> BlendMode.LIGHTEN
        Blend.DIFFERENCE -> BlendMode.DIFFERENCE
        Blend.COLOR -> BlendMode.COLOR
        Blend.LINEAR_BURN -> BlendMode.MULTIPLY // no linear-burn in android.graphics.BlendMode: closest darkening mode
        Blend.PLUS_LIGHTER -> BlendMode.PLUS
        Blend.SRC_IN -> BlendMode.SRC_IN
        Blend.SRC_ATOP -> BlendMode.SRC_ATOP
        Blend.DST_IN -> BlendMode.DST_IN
        Blend.DST_OUT -> BlendMode.DST_OUT
        Blend.DST_ATOP -> BlendMode.DST_ATOP
    }

    private fun colorFilterFor(cm: DoubleArray?): ColorMatrixColorFilter? {
        if (cm == null || cm.size < 20) return null
        val f = FloatArray(20)
        for (i in 0 until 20) f[i] = cm[i].toFloat()
        // Android's translation column is in 0..255 units, Core Animation's in 0..1
        f[4] *= 255f; f[9] *= 255f; f[14] *= 255f; f[19] *= 255f
        return ColorMatrixColorFilter(ColorMatrix(f))
    }

    private fun configureLayerPaint(alpha: Double, blend: Blend, cm: DoubleArray?) {
        layerPaint.reset()
        layerPaint.alpha = (min(1.0, max(0.0, alpha)) * 255.0 + 0.5).toInt()
        layerPaint.blendMode = blendMode(blend)
        layerPaint.colorFilter = colorFilterFor(cm)
        layerPaint.isFilterBitmap = true
    }

    private fun scaleOf(m: Matrix): Float {
        m.getValues(values)
        val sx = sqrt(values[0] * values[0] + values[3] * values[3])
        val sy = sqrt(values[1] * values[1] + values[4] * values[4])
        return max(1e-4f, (sx + sy) / 2f)
    }

    override fun beginGroup(alpha: Double, blend: Blend, colorMatrix: DoubleArray?, blur: Double) {
        if (blur > 1.0) {
            val dev = blur.toFloat() * scaleOf(ctm)
            val k = min(24f, max(1f, floor(dev / 2.5f)))
            val ow = max(1, ceil(surfaceW / k).toInt())
            val oh = max(1, ceil(surfaceH / k).toInt())
            val key = ow.toLong() shl 32 or oh.toLong()
            val bmp = bitmapPool.remove(key) ?: Bitmap.createBitmap(ow, oh, Bitmap.Config.ARGB_8888)
            bmp.eraseColor(Color.TRANSPARENT)
            val inv = Matrix()
            val ok = ctm.invert(inv)
            val oc = Canvas(bmp)
            oc.scale(1f / k, 1f / k)
            oc.concat(ctm)
            groups.add(Group(true, c, bmp, k, if (ok) inv else null, alpha, blend, colorMatrix))
            cv = oc
        } else {
            configureLayerPaint(alpha, blend, colorMatrix)
            c.saveLayer(null, layerPaint)
            groups.add(Group(false, c, null, 1f, null, alpha, blend, colorMatrix))
        }
    }

    override fun endGroup(mask: (() -> Unit)?) {
        val g = groups.removeAt(groups.size - 1)
        if (mask != null) {
            c.saveLayer(null, maskPaint)
            mask()
            c.restore()
        }
        if (!g.blur) {
            c.restore() // composites the layer using the paint captured in beginGroup
            return
        }
        val bmp = g.bitmap!!
        val parent = g.parent
        cv = parent
        configureLayerPaint(g.alpha, g.blend, g.cm)
        if (g.inv != null) {
            parent.save()
            parent.concat(g.inv)
            parent.scale(g.k, g.k)
            parent.drawBitmap(bmp, 0f, 0f, layerPaint)
            parent.restore()
        }
        bitmapPool[bmp.width.toLong() shl 32 or bmp.height.toLong()] = bmp
    }

    // ---------------------------------------------------------------- drawing

    private fun argb(c: DoubleArray, alpha: Double): Int {
        fun ch(v: Double) = (min(1.0, max(0.0, v)) * 255.0 + 0.5).toInt()
        return Color.argb(ch(c.getOrElse(3) { 1.0 } * alpha), ch(c[0]), ch(c[1]), ch(c[2]))
    }

    override fun fillRoundRect(x: Double, y: Double, w: Double, h: Double, r: Double, rgba: DoubleArray, alpha: Double) {
        paint.reset(); paint.isAntiAlias = true
        paint.style = Paint.Style.FILL
        paint.color = argb(rgba, alpha)
        if (r > 0.0) c.drawRoundRect(x.toFloat(), y.toFloat(), (x + w).toFloat(), (y + h).toFloat(), r.toFloat(), r.toFloat(), paint)
        else c.drawRect(x.toFloat(), y.toFloat(), (x + w).toFloat(), (y + h).toFloat(), paint)
    }

    override fun strokeRoundRect(x: Double, y: Double, w: Double, h: Double, r: Double, width: Double, rgba: DoubleArray, alpha: Double) {
        paint.reset(); paint.isAntiAlias = true
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = width.toFloat()
        paint.color = argb(rgba, alpha)
        val i = (width / 2).toFloat()
        c.drawRoundRect(x.toFloat() + i, y.toFloat() + i, (x + w).toFloat() - i, (y + h).toFloat() - i, r.toFloat(), r.toFloat(), paint)
    }

    override fun drawImage(
        img: Img, sx: Double, sy: Double, sw: Double, sh: Double,
        dx: Double, dy: Double, dw: Double, dh: Double,
        tint: DoubleArray?, alpha: Double, nearest: Boolean
    ) {
        val ai = img as? AndroidImg ?: return
        if (sw <= 0.0 || sh <= 0.0 || dw == 0.0 || dh == 0.0) return
        val bmp = ai.bitmap
        if (bmp.isRecycled) return
        val kx = bmp.width.toDouble() / ai.width
        val ky = bmp.height.toDouble() / ai.height
        // source rectangle in bitmap pixels, clamped to the bitmap (adjusting the destination proportionally)
        var l = sx * kx; var t = sy * ky; var r = (sx + sw) * kx; var b = (sy + sh) * ky
        var dl = dx; var dt = dy; var dr = dx + dw; var db = dy + dh
        val fx = (dr - dl) / (r - l)
        val fy = (db - dt) / (b - t)
        if (l < 0) { dl -= l * fx; l = 0.0 }
        if (t < 0) { dt -= t * fy; t = 0.0 }
        if (r > bmp.width) { dr -= (r - bmp.width) * fx; r = bmp.width.toDouble() }
        if (b > bmp.height) { db -= (b - bmp.height) * fy; b = bmp.height.toDouble() }
        if (r <= l || b <= t) return
        srcRect.set(floor(l).toInt(), floor(t).toInt(), ceil(r).toInt(), ceil(b).toInt())
        dstRect.set(dl.toFloat(), dt.toFloat(), dr.toFloat(), db.toFloat())
        paint.reset()
        paint.isAntiAlias = false
        paint.isFilterBitmap = !nearest
        paint.alpha = (min(1.0, max(0.0, alpha)) * 255.0 + 0.5).toInt()
        if (tint != null) {
            paint.colorFilter = BlendModeColorFilter(argb(doubleArrayOf(tint[0], tint[1], tint[2], 1.0), 1.0), BlendMode.MODULATE)
        }
        c.drawBitmap(bmp, srcRect, dstRect, paint)
    }

    override fun fillGradient(
        x: Double, y: Double, w: Double, h: Double, r: Double,
        colors: List<DoubleArray>, stops: DoubleArray,
        gx0: Double, gy0: Double, gx1: Double, gy1: Double, radial: Boolean, alpha: Double
    ) {
        paint.reset(); paint.isAntiAlias = true
        paint.style = Paint.Style.FILL
        if (colors.size == 1) {
            paint.color = argb(colors[0], alpha)
        } else {
            val cols = IntArray(colors.size) { argb(colors[it], alpha) }
            val pos = FloatArray(stops.size) { min(1.0, max(0.0, stops[it])).toFloat() }
            if (!radial) {
                if (abs(gx1 - gx0) < 1e-9 && abs(gy1 - gy0) < 1e-9) { paint.color = cols.last() }
                else paint.shader = LinearGradient(gx0.toFloat(), gy0.toFloat(), gx1.toFloat(), gy1.toFloat(), cols, pos, Shader.TileMode.CLAMP)
            } else {
                val rx = abs(gx1 - gx0); val ry = abs(gy1 - gy0)
                if (rx < 1e-6 || ry < 1e-6) return
                val sh = RadialGradient(0f, 0f, 1f, cols, pos, Shader.TileMode.CLAMP)
                val m = Matrix()
                m.setScale(rx.toFloat(), ry.toFloat())
                m.postTranslate(gx0.toFloat(), gy0.toFloat())
                sh.setLocalMatrix(m)
                paint.shader = sh
            }
        }
        if (r > 0.0) c.drawRoundRect(x.toFloat(), y.toFloat(), (x + w).toFloat(), (y + h).toFloat(), r.toFloat(), r.toFloat(), paint)
        else c.drawRect(x.toFloat(), y.toFloat(), (x + w).toFloat(), (y + h).toFloat(), paint)
    }

    private fun pathFor(p: PathData): Path = pathCache.getOrPut(p) {
        val out = Path()
        var k = 0
        val pts = p.pts
        for (cmd in p.cmds) {
            when (cmd) {
                'M' -> { out.moveTo(pts[k].toFloat(), pts[k + 1].toFloat()); k += 2 }
                'L' -> { out.lineTo(pts[k].toFloat(), pts[k + 1].toFloat()); k += 2 }
                'C' -> { out.cubicTo(pts[k].toFloat(), pts[k + 1].toFloat(), pts[k + 2].toFloat(), pts[k + 3].toFloat(), pts[k + 4].toFloat(), pts[k + 5].toFloat()); k += 6 }
                'Q' -> { out.quadTo(pts[k].toFloat(), pts[k + 1].toFloat(), pts[k + 2].toFloat(), pts[k + 3].toFloat()); k += 4 }
                'Z' -> out.close()
            }
        }
        out
    }

    override fun drawPath(path: PathData, fill: DoubleArray?, stroke: DoubleArray?, lineWidth: Double, alpha: Double) {
        val p = pathFor(path)
        if (fill != null) {
            paint.reset(); paint.isAntiAlias = true
            paint.style = Paint.Style.FILL
            paint.color = argb(fill, alpha)
            c.drawPath(p, paint)
        }
        if (stroke != null) {
            paint.reset(); paint.isAntiAlias = true
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = lineWidth.toFloat()
            paint.color = argb(stroke, alpha)
            c.drawPath(p, paint)
        }
    }
}
