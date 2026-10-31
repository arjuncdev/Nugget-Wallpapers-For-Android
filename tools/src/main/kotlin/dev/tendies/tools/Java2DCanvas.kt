package dev.tendies.tools

import dev.tendies.core.caml.PathData
import dev.tendies.core.render.Blend
import dev.tendies.core.render.Canvas2D
import dev.tendies.core.render.Img
import java.awt.AlphaComposite
import java.awt.Color
import java.awt.Composite
import java.awt.CompositeContext
import java.awt.BasicStroke
import java.awt.LinearGradientPaint
import java.awt.RadialGradientPaint
import java.awt.RenderingHints
import java.awt.Shape
import java.awt.Graphics2D
import java.awt.geom.AffineTransform
import java.awt.geom.Path2D
import java.awt.geom.Point2D
import java.awt.geom.Rectangle2D
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import java.awt.image.ColorModel
import java.awt.image.Raster
import java.awt.image.WritableRaster
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class JImg(val bi: BufferedImage) : Img {
    override val width: Int get() = bi.width
    override val height: Int get() = bi.height
}

/**
 * Reference backend: draws the scene into a BufferedImage with Java2D. Not meant for production (Android uses its own
 * backend) but implements every Canvas2D feature, so frames can be inspected and compared with the repo's previews.
 * Perspective rows of the 3x3 matrices are ignored (affine approximation).
 */
class Java2DCanvas(private val target: BufferedImage) : Canvas2D {
    private class Saved(val tf: AffineTransform, val clip: Shape?)
    private class Group(val img: BufferedImage, val parent: Graphics2D, val alpha: Double, val blend: Blend, val cm: DoubleArray?, val blur: Double)

    private var g: Graphics2D = makeG(target, AffineTransform(), null)
    private val stack = ArrayList<Saved>()
    private val groups = ArrayList<Group>()
    private val tintCache = HashMap<String, BufferedImage>()

    private fun makeG(img: BufferedImage, tf: AffineTransform, clip: Shape?): Graphics2D {
        val g2 = img.createGraphics()
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        g2.transform = tf
        g2.clip = clip
        return g2
    }

    fun finish() { g.dispose() }

    override fun save() { stack.add(Saved(AffineTransform(g.transform), g.clip)) }

    override fun restore() {
        val s = stack.removeAt(stack.size - 1)
        g.transform = s.tf
        g.clip = s.clip
    }

    override fun concat(m: DoubleArray) {
        val w = if (abs(m[8]) < 1e-12) 1.0 else m[8]
        g.transform(AffineTransform(m[0] / w, m[3] / w, m[1] / w, m[4] / w, m[2] / w, m[5] / w))
    }

    override fun clipRect(x: Double, y: Double, w: Double, h: Double) { g.clip(Rectangle2D.Double(x, y, w, h)) }

    override fun clipRoundRect(x: Double, y: Double, w: Double, h: Double, r: Double) {
        g.clip(RoundRectangle2D.Double(x, y, w, h, 2 * r, 2 * r))
    }

    private fun color(c: DoubleArray, alpha: Double): Color =
        Color(cl(c[0]), cl(c[1]), cl(c[2]), cl(c.getOrElse(3) { 1.0 } * alpha))

    private fun cl(v: Double): Float = min(1.0, max(0.0, v)).toFloat()

    // ------------------------------------------------------------------ groups

    override fun beginGroup(alpha: Double, blend: Blend, colorMatrix: DoubleArray?, blur: Double) {
        val img = BufferedImage(target.width, target.height, BufferedImage.TYPE_INT_ARGB)
        val ng = makeG(img, AffineTransform(g.transform), g.clip)
        groups.add(Group(img, g, alpha, blend, colorMatrix, blur))
        g = ng
    }

    override fun endGroup(mask: (() -> Unit)?) {
        val gr = groups.removeAt(groups.size - 1)
        if (mask != null) {
            val m = BufferedImage(target.width, target.height, BufferedImage.TYPE_INT_ARGB)
            val mg = makeG(m, AffineTransform(g.transform), g.clip)
            val groupG = g
            g = mg
            mask()
            mg.dispose()
            g = groupG
            val tf = g.transform; val clip = g.clip; val comp = g.composite
            g.transform = AffineTransform(); g.clip = null
            g.composite = AlphaComposite.DstIn
            g.drawImage(m, 0, 0, null)
            g.transform = tf; g.clip = clip; g.composite = comp
        }
        g.dispose()
        var img = gr.img
        if (gr.cm != null) applyColorMatrix(img, gr.cm)
        if (gr.blur > 0.5) img = boxBlur(img, gr.blur)
        val pg = gr.parent
        val tf = pg.transform; val clip = pg.clip; val comp = pg.composite
        pg.transform = AffineTransform(); pg.clip = null
        pg.composite = compositeFor(gr.blend, gr.alpha)
        pg.drawImage(img, 0, 0, null)
        pg.transform = tf; pg.clip = clip; pg.composite = comp
        g = pg
    }

    private fun compositeFor(b: Blend, alpha: Double): Composite {
        val a = alpha.toFloat().coerceIn(0f, 1f)
        return when (b) {
            Blend.NORMAL -> AlphaComposite.SrcOver.derive(a)
            Blend.SRC_IN -> AlphaComposite.getInstance(AlphaComposite.SRC_IN, a)
            Blend.SRC_ATOP -> AlphaComposite.getInstance(AlphaComposite.SRC_ATOP, a)
            Blend.DST_IN -> AlphaComposite.getInstance(AlphaComposite.DST_IN, a)
            Blend.DST_OUT -> AlphaComposite.getInstance(AlphaComposite.DST_OUT, a)
            Blend.DST_ATOP -> AlphaComposite.getInstance(AlphaComposite.DST_ATOP, a)
            else -> BlendComposite(b, a)
        }
    }

    private fun applyColorMatrix(img: BufferedImage, m: DoubleArray) {
        val w = img.width; val h = img.height
        val px = IntArray(w * h)
        img.getRGB(0, 0, w, h, px, 0, w)
        for (i in px.indices) {
            val p = px[i]
            val a = (p ushr 24) / 255.0
            if (a == 0.0) continue
            val r = ((p shr 16) and 255) / 255.0
            val gg = ((p shr 8) and 255) / 255.0
            val b = (p and 255) / 255.0
            val nr = m[0] * r + m[1] * gg + m[2] * b + m[3] * a + m[4]
            val ng = m[5] * r + m[6] * gg + m[7] * b + m[8] * a + m[9]
            val nb = m[10] * r + m[11] * gg + m[12] * b + m[13] * a + m[14]
            val na = m[15] * r + m[16] * gg + m[17] * b + m[18] * a + m[19]
            px[i] = (to8(na) shl 24) or (to8(nr) shl 16) or (to8(ng) shl 8) or to8(nb)
        }
        img.setRGB(0, 0, w, h, px, 0, w)
    }

    private fun to8(v: Double): Int = (min(1.0, max(0.0, v)) * 255.0 + 0.5).toInt()

    private fun boxBlur(src: BufferedImage, radius: Double): BufferedImage {
        val sc = g.transform.scaleX
        val r = max(1, (radius * abs(sc) / 2.0).toInt())
        val w = src.width; val h = src.height
        var cur = IntArray(w * h)
        src.getRGB(0, 0, w, h, cur, 0, w)
        repeat(3) {
            cur = blurPass(cur, w, h, r, true)
            cur = blurPass(cur, w, h, r, false)
        }
        val out = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        out.setRGB(0, 0, w, h, cur, 0, w)
        return out
    }

    private fun blurPass(src: IntArray, w: Int, h: Int, r: Int, horizontal: Boolean): IntArray {
        val out = IntArray(src.size)
        val n = if (horizontal) w else h
        val lines = if (horizontal) h else w
        val win = 2 * r + 1
        for (l in 0 until lines) {
            var sa = 0L; var sr = 0L; var sg = 0L; var sb = 0L
            fun at(i: Int): Int {
                val c = min(n - 1, max(0, i))
                return if (horizontal) src[l * w + c] else src[c * w + l]
            }
            for (i in -r..r) {
                val p = at(i); val a = (p ushr 24)
                sa += a; sr += ((p shr 16) and 255) * a; sg += ((p shr 8) and 255) * a; sb += (p and 255) * a
            }
            for (i in 0 until n) {
                val aa = (sa / win).toInt()
                val rr = if (sa > 0) (sr / sa).toInt() else 0
                val gg = if (sa > 0) (sg / sa).toInt() else 0
                val bb = if (sa > 0) (sb / sa).toInt() else 0
                val v = (aa shl 24) or (rr shl 16) or (gg shl 8) or bb
                if (horizontal) out[l * w + i] = v else out[i * w + l] = v
                val rem = at(i - r); val add = at(i + r + 1)
                val ra = (rem ushr 24); val aa2 = (add ushr 24)
                sa += aa2 - ra
                sr += ((add shr 16) and 255) * aa2 - ((rem shr 16) and 255) * ra
                sg += ((add shr 8) and 255) * aa2 - ((rem shr 8) and 255) * ra
                sb += (add and 255) * aa2 - (rem and 255) * ra
            }
        }
        return out
    }

    // ---------------------------------------------------------------- drawing

    override fun fillRoundRect(x: Double, y: Double, w: Double, h: Double, r: Double, rgba: DoubleArray, alpha: Double) {
        g.color = color(rgba, alpha)
        if (r > 0.0) g.fill(RoundRectangle2D.Double(x, y, w, h, 2 * r, 2 * r)) else g.fill(Rectangle2D.Double(x, y, w, h))
    }

    override fun strokeRoundRect(x: Double, y: Double, w: Double, h: Double, r: Double, width: Double, rgba: DoubleArray, alpha: Double) {
        g.color = color(rgba, alpha)
        g.stroke = BasicStroke(width.toFloat())
        val i = width / 2
        g.draw(RoundRectangle2D.Double(x + i, y + i, w - width, h - width, 2 * r, 2 * r))
    }

    override fun drawImage(
        img: Img, sx: Double, sy: Double, sw: Double, sh: Double,
        dx: Double, dy: Double, dw: Double, dh: Double,
        tint: DoubleArray?, alpha: Double, nearest: Boolean
    ) {
        if (img !is JImg || sw <= 0.0 || sh <= 0.0 || dw == 0.0 || dh == 0.0) return
        var bi = img.bi
        if (tint != null) bi = tinted(img, tint)
        val at = AffineTransform()
        at.translate(dx, dy)
        at.scale(dw / sw, dh / sh)
        at.translate(-sx, -sy)
        val oldClip = g.clip
        val oldComp = g.composite
        g.clip(Rectangle2D.Double(dx, dy, dw, dh))
        g.composite = AlphaComposite.SrcOver.derive(alpha.toFloat().coerceIn(0f, 1f))
        g.setRenderingHint(
            RenderingHints.KEY_INTERPOLATION,
            if (nearest) RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR else RenderingHints.VALUE_INTERPOLATION_BILINEAR
        )
        g.drawImage(bi, at, null)
        g.composite = oldComp
        g.clip = oldClip
    }

    private fun tinted(img: JImg, t: DoubleArray): BufferedImage {
        val key = System.identityHashCode(img.bi).toString() + ":" + (t[0] * 255).toInt() + "," + (t[1] * 255).toInt() + "," + (t[2] * 255).toInt()
        return tintCache.getOrPut(key) {
            val src = img.bi
            val out = BufferedImage(src.width, src.height, BufferedImage.TYPE_INT_ARGB)
            val px = IntArray(src.width * src.height)
            src.getRGB(0, 0, src.width, src.height, px, 0, src.width)
            for (i in px.indices) {
                val p = px[i]
                val r = (((p shr 16) and 255) * cl(t[0])).toInt()
                val gg = (((p shr 8) and 255) * cl(t[1])).toInt()
                val b = ((p and 255) * cl(t[2])).toInt()
                px[i] = (p and (255 shl 24)) or (r shl 16) or (gg shl 8) or b
            }
            out.setRGB(0, 0, src.width, src.height, px, 0, src.width)
            out
        }
    }

    override fun fillGradient(
        x: Double, y: Double, w: Double, h: Double, r: Double,
        colors: List<DoubleArray>, stops: DoubleArray,
        gx0: Double, gy0: Double, gx1: Double, gy1: Double, radial: Boolean, alpha: Double
    ) {
        val shape: Shape = if (r > 0.0) RoundRectangle2D.Double(x, y, w, h, 2 * r, 2 * r) else Rectangle2D.Double(x, y, w, h)
        if (colors.size == 1) { g.color = color(colors[0], alpha); g.fill(shape); return }
        val fr = FloatArray(stops.size)
        for (i in stops.indices) {
            var v = stops[i].toFloat().coerceIn(0f, 1f)
            if (i > 0 && v <= fr[i - 1]) v = fr[i - 1] + 1e-4f
            fr[i] = v
        }
        if (fr.last() > 1f) { val k = fr.last(); for (i in fr.indices) fr[i] = fr[i] / k }
        val cols = Array(colors.size) { color(colors[it], alpha) }
        if (!radial) {
            if (abs(gx1 - gx0) < 1e-9 && abs(gy1 - gy0) < 1e-9) { g.color = cols.last(); g.fill(shape); return }
            g.paint = LinearGradientPaint(Point2D.Double(gx0, gy0), Point2D.Double(gx1, gy1), fr, cols)
            g.fill(shape)
        } else {
            val rx = abs(gx1 - gx0); val ry = abs(gy1 - gy0)
            if (rx < 1e-6 || ry < 1e-6) return
            val old = g.transform
            g.translate(gx0, gy0)
            g.scale(rx, ry)
            val inv = AffineTransform()
            inv.scale(1 / rx, 1 / ry)
            inv.translate(-gx0, -gy0)
            g.paint = RadialGradientPaint(Point2D.Double(0.0, 0.0), 1f, fr, cols)
            g.fill(inv.createTransformedShape(shape))
            g.transform = old
        }
    }

    override fun drawPath(path: PathData, fill: DoubleArray?, stroke: DoubleArray?, lineWidth: Double, alpha: Double) {
        val p = Path2D.Double(Path2D.WIND_NON_ZERO)
        var k = 0
        val pts = path.pts
        for (c in path.cmds) {
            when (c) {
                'M' -> { p.moveTo(pts[k], pts[k + 1]); k += 2 }
                'L' -> { p.lineTo(pts[k], pts[k + 1]); k += 2 }
                'C' -> { p.curveTo(pts[k], pts[k + 1], pts[k + 2], pts[k + 3], pts[k + 4], pts[k + 5]); k += 6 }
                'Q' -> { p.quadTo(pts[k], pts[k + 1], pts[k + 2], pts[k + 3]); k += 4 }
                'Z' -> p.closePath()
            }
        }
        if (fill != null) { g.color = color(fill, alpha); g.fill(p) }
        if (stroke != null) { g.color = color(stroke, alpha); g.stroke = BasicStroke(lineWidth.toFloat()); g.draw(p) }
    }
}

/** Per-pixel implementation of the separable blend modes on non-premultiplied ARGB (W3C compositing formulas). */
class BlendComposite(private val mode: Blend, private val alpha: Float) : Composite {
    override fun createContext(srcCM: ColorModel, dstCM: ColorModel, hints: RenderingHints?): CompositeContext =
        object : CompositeContext {
            override fun dispose() {}
            override fun compose(src: Raster, dstIn: Raster, dstOut: WritableRaster) {
                val w = min(src.width, dstIn.width)
                val h = min(src.height, dstIn.height)
                val s = IntArray(w * 4)
                val d = IntArray(w * 4)
                for (y in 0 until h) {
                    src.getPixels(src.minX, src.minY + y, w, 1, s)
                    dstIn.getPixels(dstIn.minX, dstIn.minY + y, w, 1, d)
                    for (x in 0 until w) {
                        val i = x * 4
                        val sa = s[i + 3] / 255f * alpha
                        val da = d[i + 3] / 255f
                        if (mode == Blend.PLUS_LIGHTER) {
                            val ra = min(1f, sa + da)
                            for (c in 0 until 3) d[i + c] = (min(1f, s[i + c] / 255f * sa + d[i + c] / 255f * da) * 255f + 0.5f).toInt()
                            d[i + 3] = (ra * 255f + 0.5f).toInt()
                            continue
                        }
                        val ra = sa + da * (1 - sa)
                        if (ra <= 0f) { d[i] = 0; d[i + 1] = 0; d[i + 2] = 0; d[i + 3] = 0; continue }
                        for (c in 0 until 3) {
                            val cs = s[i + c] / 255f
                            val cb = d[i + c] / 255f
                            val bl = blend(cb, cs)
                            val co = sa * (1 - da) * cs + sa * da * bl + (1 - sa) * da * cb
                            d[i + c] = (min(1f, max(0f, co / ra)) * 255f + 0.5f).toInt()
                        }
                        d[i + 3] = (ra * 255f + 0.5f).toInt()
                    }
                    dstOut.setPixels(dstOut.minX, dstOut.minY + y, w, 1, d)
                }
            }
        }

    private fun blend(cb: Float, cs: Float): Float = when (mode) {
        Blend.MULTIPLY -> cb * cs
        Blend.SCREEN -> cb + cs - cb * cs
        Blend.OVERLAY -> if (cb <= 0.5f) 2 * cs * cb else 1 - 2 * (1 - cs) * (1 - cb)
        Blend.DARKEN -> min(cb, cs)
        Blend.LIGHTEN -> max(cb, cs)
        Blend.DIFFERENCE -> abs(cb - cs)
        Blend.LINEAR_BURN -> max(0f, cb + cs - 1)
        else -> cs
    }
}
