package dev.tendies.core.render

import dev.tendies.core.anim.AnimEval
import dev.tendies.core.caml.CamlLayer
import dev.tendies.core.caml.FilterDef
import dev.tendies.core.caml.Gravity
import dev.tendies.core.caml.Kind
import dev.tendies.core.caml.LayerProps
import dev.tendies.core.caml.Mat3
import dev.tendies.core.caml.Mat4
import dev.tendies.core.caml.T3
import dev.tendies.core.caml.Val
import java.util.TreeMap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Draws a [Scene] at time `t` onto any [Canvas2D].
 *
 * Coordinate model (verified against real files + their preview videos, see docs/ANALYSIS.md): the render context is
 * raw Core Animation, i.e. **y-up** (origin bottom-left); `geometryFlipped="1"` switches a layer's sublayer space to
 * y-down (182 of 213 document roots do this, e.g. Night City's background does not and is authored y-up).
 * Orientation is cumulative down the tree, so each layer carries an `up` flag; y-up layers are converted into a
 * screen-aligned y-down "interior" space (images are never mirrored) by flipping positions, anchor points,
 * rotation/shear sign, paths, gradient points and contents rects.
 */
class SceneRenderer(private val images: ImageProvider) {
    /** Layers actually drawn in the last frame (diagnostics / profiling). */
    var drawnLayers = 0
        private set

    /** Features hit at render time that are only approximated or skipped (name -> count). */
    val unsupported = TreeMap<String, Int>()
    var missingImages = 0
        private set

    private val emitters = EmitterRenderer(images)

    private class Ctx(val inst: DocInstance, val now: Double, val rp: RenderParams)

    private fun note(feature: String) { unsupported[feature] = (unsupported[feature] ?: 0) + 1 }

    fun draw(c: Canvas2D, scene: Scene, t: Double, rp: RenderParams) {
        drawnLayers = 0
        for (d in scene.docs) {
            if (rp.roles != null && d.role !in rp.roles) continue
            val doc = d.doc
            if (doc.width <= 0.0 || doc.height <= 0.0) continue
            // every layer package is cover-fitted on its own: real wallpapers mix e.g. a 3176x3176 background with a 428x926 foreground
            val s = max(rp.outW / doc.width, rp.outH / doc.height) * rp.userScale
            val tx = (rp.outW - doc.width * s) / 2.0 + rp.offX
            val ty = (rp.outH - doc.height * s) / 2.0 + rp.offY
            c.save()
            c.concat(doubleArrayOf(s, 0.0, tx, 0.0, s, ty, 0.0, 0.0, 1.0))
            drawLayer(c, doc.root, t, Ctx(d, t, rp), true, doc.height, null)
            c.restore()
        }
    }

    // ------------------------------------------------------------------ layers

    private fun conj(t: DoubleArray) = doubleArrayOf(t[0], -t[1], t[2], -t[3], t[4], -t[5], t[6], t[7], t[8], -t[9], t[10])

    /** Parent-interior -> layer-interior (top-left origin, y-down) matrix (planar fast path). */
    private fun layerMatrix(p: LayerProps, up: Boolean, parentH: Double): DoubleArray {
        val cy = if (up) parentH - p.py else p.py
        val ay = if (up) 1.0 - p.ay else p.ay
        val tr = if (up) conj(p.tr) else p.tr
        var m = Mat3.translate(p.px, cy)
        if (!T3.isIdentity(tr)) m = Mat3.mul(m, T3.toMat3(tr))
        return Mat3.mul(m, Mat3.translate(-p.ax * p.bw, -ay * p.bh))
    }

    /** Full 3D version (zPosition + 3D rotations) used inside CATransformLayer chains. */
    private fun local4(p: LayerProps, up: Boolean, parentH: Double): Mat4 {
        val cy = if (up) parentH - p.py else p.py
        val ay = if (up) 1.0 - p.ay else p.ay
        val tr = if (up) conj(p.tr) else p.tr
        var m = Mat4.translate(p.px, cy, p.z)
        if (!T3.isIdentity(tr)) m = m * T3.toMat4(tr)
        return m * Mat4.translate(-p.ax * p.bw, -ay * p.bh, 0.0)
    }

    private fun isPlanar(t: DoubleArray) = t[T3.RX] == 0.0 && t[T3.RY] == 0.0 && t[T3.M34] == 0.0 && t[T3.TZ] == 0.0

    /** sublayerTransform about the layer's anchor, expressed in the children's interior space. */
    private fun sub4(p: LayerProps, up: Boolean, childUp: Boolean): Mat4 {
        val pivotY = if (up) (1.0 - p.ay) * p.bh else p.ay * p.bh
        val st = if (childUp) conj(p.subTr) else p.subTr
        return Mat4.translate(p.ax * p.bw, pivotY, 0.0) * T3.toMat4(st) * Mat4.translate(-p.ax * p.bw, -pivotY, 0.0)
    }

    private fun applyParallax(L: CamlLayer, p: LayerProps, ctx: Ctx) {
        val groups = ctx.inst.doc.parallax
        if (groups.isEmpty()) return
        for (g in groups) {
            if (g.layerName != L.name) continue
            val tilt = if (g.axis.lowercase() == "x") ctx.rp.tiltX else ctx.rp.tiltY
            val v = g.mapMinTo + (g.mapMaxTo - g.mapMinTo) * ((tilt + 1.0) / 2.0)
            // verified on MOJO Carrot: the (min,max) midpoints equal the authored positions, so these are absolute values
            p.apply(g.keyPath, Val.num(v), false)
        }
    }

    private fun drawLayer(c: Canvas2D, L: CamlLayer, tp: Double, ctx: Ctx, up: Boolean, parentH: Double, parent4: Mat4?) {
        val p = L.pres
        p.copyFrom(L.base)
        val inst = ctx.inst
        inst.states.apply(L, p, ctx.now)
        val ov = inst.overrides[L]
        if (ov != null) for ((kp, v) in ov) p.set(kp, doubleArrayOf(v))
        applyParallax(L, p, ctx)

        // `timeOffset` animations run in the parent's time because they change this layer's own clock
        for (a in L.animations) {
            if (a.keyPath == "timeOffset") AnimEval.sample(a, tp)?.let { p.apply("timeOffset", it, a.additive) }
        }
        val tl = (tp - p.beginTime) * p.speed + p.timeOffset

        val removals = inst.states.hasRemovals(L)
        for (a in L.animations) {
            if (a.keyPath.isEmpty() || a.keyPath == "timeOffset") continue
            if (removals && inst.states.isKeyRemoved(L, a.key ?: "animation")) continue
            val v = AnimEval.sample(a, tl) ?: continue
            if (!p.apply(a.keyPath, v, a.additive) && !a.keyPath.startsWith("meshTransform") && a.keyPath != "sublayers") {
                note("animKeyPath:" + a.keyPath)
            }
        }
        inst.states.dynamicAnims(L)?.forEach { d ->
            AnimEval.sample(d.anim, ctx.now - d.epoch)?.let { p.apply(d.anim.keyPath, it, d.anim.additive) }
        }

        if (p.hidden) return
        val opacity = min(1.0, p.opacity)
        if (opacity <= 0.001) return
        val w = p.bw
        val h = p.bh
        val childUp = up xor p.geometryFlipped

        if (L.kind == Kind.TRANSFORM) {
            // CATransformLayer: draws nothing itself; sublayers keep their 3D transforms relative to it
            var acc = (parent4 ?: Mat4.identity()) * local4(p, up, parentH)
            if (!T3.isIdentity(p.subTr)) acc = acc * sub4(p, up, childUp)
            drawnLayers++
            drawChildren3D(c, L, p, tl, ctx, childUp, acc)
            return
        }
        if (L.sublayers.isEmpty() && L.kind != Kind.EMITTER && (w <= 0.0 || h <= 0.0)) return

        val blend = Blend.of(p.compositing)
        val cm = filterMatrix(L, p)
        val blur = blurRadius(L, p)
        val mask = L.mask
        val hasSubs = L.sublayers.isNotEmpty()
        val grouped = (opacity < 0.999 && hasSubs) || mask != null || blend != Blend.NORMAL || cm != null || blur > 0.0
        val alpha = if (grouped) 1.0 else opacity

        drawnLayers++
        c.save()
        if (parent4 != null) c.concat((parent4 * local4(p, up, parentH)).toMat3()) else c.concat(layerMatrix(p, up, parentH))
        if (grouped) c.beginGroup(opacity, blend, cm, blur)

        val clip = p.masksToBounds
        if (clip) {
            c.save()
            if (p.cornerRadius > 0.0) c.clipRoundRect(0.0, 0.0, w, h, p.cornerRadius) else c.clipRect(0.0, 0.0, w, h)
        }

        if (p.bg[3] > 0.0) c.fillRoundRect(0.0, 0.0, w, h, p.cornerRadius, p.bg, alpha)
        val cpath = p.contents
        if (cpath != null) {
            val img = images.image(inst.doc.dir, cpath)
            if (img != null) drawContents(c, img, p, up, alpha) else missingImages++
        }
        when (L.kind) {
            Kind.GRADIENT -> drawGradient(c, L, p, up, alpha)
            Kind.SHAPE -> drawShape(c, L, p, up, alpha)
            Kind.EMITTER -> emitters.draw(c, L, p, tl, up, alpha, inst.doc.dir) { note(it) }
            Kind.TEXT -> if ((L.text?.string ?: "").isNotEmpty()) note("CATextLayer")
            Kind.BACKDROP -> if (p.enabled) note("CABackdropLayer")
            else -> {}
        }

        if (hasSubs) drawChildren(c, L, p, tl, ctx, up, childUp)
        if (clip) c.restore()

        if (p.borderWidth > 0.0 && p.borderColor[3] > 0.0) {
            c.strokeRoundRect(0.0, 0.0, w, h, p.cornerRadius, p.borderWidth, p.borderColor, alpha)
        }

        if (grouped) {
            if (mask != null) c.endGroup { drawLayer(c, mask, tl, ctx, childUp, h, null) } else c.endGroup(null)
        }
        c.restore()
    }

    /** Sublayers of a non-transform layer: flattened into the layer's plane (2D), unless sublayerTransform is genuinely 3D. */
    private fun drawChildren(c: Canvas2D, L: CamlLayer, p: LayerProps, tl: Double, ctx: Ctx, up: Boolean, childUp: Boolean) {
        val w = p.bw
        val h = p.bh
        c.save()
        // bounds.origin shifts the visible window of the sublayer space; in a y-up space the y shift has the opposite sign
        if (p.bx != 0.0 || p.by != 0.0) c.concat(Mat3.translate(-p.bx, if (childUp) p.by else -p.by))

        val r = L.replicator
        if (L.kind != Kind.REPLICATOR && !T3.isIdentity(p.subTr) && !isPlanar(p.subTr)) {
            drawChildren3D(c, L, p, tl, ctx, childUp, sub4(p, up, childUp))
            c.restore()
            return
        }
        if (!T3.isIdentity(p.subTr)) {
            val pivotY = if (up) (1.0 - p.ay) * h else p.ay * h
            val st = if (childUp) conj(p.subTr) else p.subTr
            var m = Mat3.translate(p.ax * w, pivotY)
            m = Mat3.mul(m, T3.toMat3(st))
            m = Mat3.mul(m, Mat3.translate(-p.ax * w, -pivotY))
            c.concat(m)
        }
        val subs: List<CamlLayer> = if (L.hasZ) {
            L.sortedSubs ?: L.sublayers.sortedBy { it.base.z }.also { L.sortedSubs = it }
        } else L.sublayers

        if (L.kind == Kind.REPLICATOR && r != null) {
            val tr = if (childUp) conj(r.transform) else r.transform
            var inst = Mat3.translate(w / 2, h / 2)
            inst = Mat3.mul(inst, T3.toMat3(tr))
            inst = Mat3.mul(inst, Mat3.translate(-w / 2, -h / 2))
            var acc = Mat3.identity()
            for (i in 0 until r.count) {
                c.save()
                c.concat(acc)
                for (s in subs) drawLayer(c, s, tl - i * r.delay, ctx, childUp, h, null)
                c.restore()
                acc = Mat3.mul(acc, inst)
            }
        } else {
            for (s in subs) drawLayer(c, s, tl, ctx, childUp, h, null)
        }
        c.restore()
    }

    /** 3D context: sublayers are positioned with full 4x4 chains and painted far-to-near (painter's algorithm). */
    private fun drawChildren3D(c: Canvas2D, L: CamlLayer, p: LayerProps, tl: Double, ctx: Ctx, childUp: Boolean, acc: Mat4) {
        val subs = L.sublayers
        if (subs.isEmpty()) return
        val h = p.bh
        val ordered: List<CamlLayer> = if (subs.size > 1) {
            val z = HashMap<CamlLayer, Double>(subs.size * 2)
            for (ch in subs) {
                val b = ch.base
                val m = acc * local4(b, childUp, h)
                z[ch] = m.m[8] * (b.bw / 2) + m.m[9] * (b.bh / 2) + m.m[11]
            }
            subs.sortedBy { z[it] ?: 0.0 }
        } else subs
        for (ch in ordered) drawLayer(c, ch, tl, ctx, childUp, h, acc)
    }

    // ---------------------------------------------------------------- contents

    private fun drawContents(c: Canvas2D, img: Img, p: LayerProps, up: Boolean, alpha: Double) {
        val iw = img.width.toDouble()
        val ih = img.height.toDouble()
        val cr = p.crect
        val sx = cr[0] * iw
        val sh = cr[3] * ih
        val sy = if (up) (1.0 - cr[1] - cr[3]) * ih else cr[1] * ih
        val sw = cr[2] * iw
        if (sw <= 0.0 || sh <= 0.0) return
        val w = p.bw
        val h = p.bh
        val tint = if (p.hasMultiply) p.multiply else null
        when (p.gravity) {
            Gravity.RESIZE -> c.drawImage(img, sx, sy, sw, sh, 0.0, 0.0, w, h, tint, alpha, p.nearest)
            Gravity.RESIZE_ASPECT -> {
                val s = min(w / sw, h / sh)
                val dw = sw * s; val dh = sh * s
                c.drawImage(img, sx, sy, sw, sh, (w - dw) / 2, (h - dh) / 2, dw, dh, tint, alpha, p.nearest)
            }
            Gravity.RESIZE_ASPECT_FILL -> {
                val s = max(w / sw, h / sh)
                val dw = sw * s; val dh = sh * s
                c.save()
                c.clipRect(0.0, 0.0, w, h)
                c.drawImage(img, sx, sy, sw, sh, (w - dw) / 2, (h - dh) / 2, dw, dh, tint, alpha, p.nearest)
                c.restore()
            }
            else -> {
                val k = if (p.contentsScale > 0.0) p.contentsScale else 1.0
                val dw = sw / k; val dh = sh / k
                val g = p.gravity
                val leftSide = g == Gravity.LEFT || g == Gravity.TOP_LEFT || g == Gravity.BOTTOM_LEFT
                val rightSide = g == Gravity.RIGHT || g == Gravity.TOP_RIGHT || g == Gravity.BOTTOM_RIGHT
                val topSide = g == Gravity.TOP || g == Gravity.TOP_LEFT || g == Gravity.TOP_RIGHT
                val bottomSide = g == Gravity.BOTTOM || g == Gravity.BOTTOM_LEFT || g == Gravity.BOTTOM_RIGHT
                // gravity names are relative to the layer's own orientation, so swap top/bottom for y-up layers
                val atTop = if (up) bottomSide else topSide
                val atBottom = if (up) topSide else bottomSide
                val dx = if (leftSide) 0.0 else if (rightSide) w - dw else (w - dw) / 2
                val dy = if (atTop) 0.0 else if (atBottom) h - dh else (h - dh) / 2
                c.drawImage(img, sx, sy, sw, sh, dx, dy, dw, dh, tint, alpha, p.nearest)
            }
        }
    }

    private fun drawGradient(c: Canvas2D, L: CamlLayer, p: LayerProps, up: Boolean, alpha: Double) {
        val g = L.gradient ?: return
        if (g.colors.isEmpty()) return
        if (g.conic) { note("CAGradientLayer.conic"); return }
        val w = p.bw
        val h = p.bh
        val n = g.colors.size
        val loc = g.locations
        val stops = DoubleArray(n)
        for (i in 0 until n) {
            val v = if (loc != null && loc.size == n) loc[i] else if (n == 1) 0.0 else i.toDouble() / (n - 1)
            stops[i] = if (i > 0) max(v, stops[i - 1]) else v
        }
        val sy = if (up) 1.0 - g.startY else g.startY
        val ey = if (up) 1.0 - g.endY else g.endY
        c.fillGradient(0.0, 0.0, w, h, p.cornerRadius, g.colors, stops, g.startX * w, sy * h, g.endX * w, ey * h, g.radial, alpha)
    }

    private fun drawShape(c: Canvas2D, L: CamlLayer, p: LayerProps, up: Boolean, alpha: Double) {
        val path = p.path ?: L.shape?.path ?: return
        val fill = if (p.hasFill && p.fill[3] > 0.0) p.fill else null
        val stroke = if (p.stroke[3] > 0.0 && p.lineWidth > 0.0) p.stroke else null
        if (fill == null && stroke == null) return
        if (up) {
            c.save()
            c.concat(Mat3.mul(Mat3.translate(0.0, p.bh), Mat3.scale(1.0, -1.0)))
        }
        c.drawPath(path, fill, stroke, p.lineWidth, alpha)
        if (up) c.restore()
    }

    // ----------------------------------------------------------------- filters

    private fun filterInput(p: LayerProps, f: FilterDef, name: String): DoubleArray? =
        p.extras["filters.${f.name}.$name"] ?: f.inputs[name]

    private fun filterEnabled(p: LayerProps, f: FilterDef): Boolean {
        val e = p.extras["filters.${f.name}.enabled"]
        return if (e != null && e.isNotEmpty()) e[0] != 0.0 else f.enabled
    }

    /** Combined 4x5 colour matrix of all enabled colour filters on the layer (null if none). */
    private fun filterMatrix(L: CamlLayer, p: LayerProps): DoubleArray? {
        if (L.filters.isEmpty()) return null
        var m: DoubleArray? = null
        for (f in L.filters) {
            if (!filterEnabled(p, f)) continue
            val one: DoubleArray? = when (f.type) {
                "colorMatrix", "vibrantColorMatrix" -> filterInput(p, f, "inputColorMatrix")?.takeIf { it.size >= 20 }
                "colorBrightness" -> filterInput(p, f, "inputAmount")?.firstOrNull()?.let { ColorMatrices.brightness(it) }
                "colorSaturate" -> filterInput(p, f, "inputAmount")?.firstOrNull()?.let { ColorMatrices.saturate(it) }
                "colorContrast" -> filterInput(p, f, "inputAmount")?.firstOrNull()?.let { ColorMatrices.contrast(it) }
                "colorInvert" -> ColorMatrices.invert()
                "colorHueRotate" -> filterInput(p, f, "inputAngle")?.firstOrNull()?.let { ColorMatrices.hueRotate(it) }
                "multiplyColor" -> filterInput(p, f, "inputColor")?.let { ColorMatrices.multiply(it) }
                "colorAdd" -> filterInput(p, f, "inputColor")?.let { ColorMatrices.add(it) }
                "gaussianBlur" -> null
                else -> { note("CAFilter:" + f.type); null }
            }
            if (one != null && !ColorMatrices.isIdentity(one)) m = if (m == null) one else ColorMatrices.then(m, one)
        }
        return m
    }

    private fun blurRadius(L: CamlLayer, p: LayerProps): Double {
        if (L.filters.isEmpty()) return 0.0
        var r = 0.0
        for (f in L.filters) {
            if (f.type != "gaussianBlur" || !filterEnabled(p, f)) continue
            r = max(r, filterInput(p, f, "inputRadius")?.firstOrNull() ?: 0.0)
        }
        return r
    }
}

