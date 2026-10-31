package dev.tendies.core.render

import dev.tendies.core.caml.CamlLayer
import dev.tendies.core.caml.EmitterCell
import dev.tendies.core.caml.LayerProps
import dev.tendies.core.caml.Mat3
import dev.tendies.core.caml.Values
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Stateless CAEmitterLayer approximation: every particle's state is a closed-form function of
 * (cell, particle index, time) with deterministic pseudo-randomness, so frames can be rendered at any
 * time without simulation state (nothing to carry across WallpaperService visibility changes).
 */
internal class EmitterRenderer(private val images: ImageProvider) {
    private companion object {
        const val MAX_PARTICLES_PER_CELL = 1500
    }

    private fun mix(x0: Long): Long {
        var z = x0 + -7046029254386353131L
        z = (z xor (z ushr 30)) * -4658895280553007687L
        z = (z xor (z ushr 27)) * -7723592293110705685L
        return z xor (z ushr 31)
    }

    /** Deterministic uniform [0,1) from (seed, particle, channel). */
    private fun rnd(seed: Long, k: Long, ch: Int): Double {
        val h = mix(mix(seed * 31L + k) + ch)
        return (h ushr 11).toDouble() / (1L shl 53).toDouble()
    }

    private fun num(p: LayerProps, cell: EmitterCell, key: String, def: Double): Double {
        val o = p.extras["emitterCells.${cell.name}.$key"]
        if (o != null && o.isNotEmpty()) return o[0]
        val s = cell.attrs[key] ?: return def
        return Values.num(s, def)
    }

    fun draw(c: Canvas2D, L: CamlLayer, p: LayerProps, t: Double, up: Boolean, alpha: Double, docDir: String, note: (String) -> Unit) {
        val e = L.emitter ?: return
        if (e.cells.isEmpty()) return
        val additive = e.renderMode == "additive"
        if (additive) c.beginGroup(1.0, Blend.PLUS_LIGHTER, null, 0.0)
        for ((ci, cell) in e.cells.withIndex()) {
            if (cell.attrs["enabled"] == "0") continue
            val path = cell.contents ?: continue
            val img = images.image(docDir, path) ?: continue
            drawCell(c, L, p, ci, cell, img, t, up, alpha, e.renderMode == "oldestLast", note)
        }
        if (additive) c.endGroup(null)
    }

    private fun drawCell(
        c: Canvas2D, L: CamlLayer, p: LayerProps, ci: Int, cell: EmitterCell, img: Img,
        t: Double, up: Boolean, alpha: Double, oldestLast: Boolean, note: (String) -> Unit
    ) {
        val e = L.emitter!!
        val rate = num(p, cell, "birthRate", 1.0) * e.birthRate
        if (rate <= 0.0) return
        val life0 = num(p, cell, "lifetime", 1.0)
        val lifeR = num(p, cell, "lifetimeRange", 0.0)
        val maxLife = (life0 + lifeR) * e.lifetime
        if (maxLife <= 0.0) return
        val begin = num(p, cell, "beginTime", 0.0)
        if (cell.attrs["contentsFrameCount"] != null) note("emitter.spriteSheet")

        val h = p.bh
        val seed = e.seed * 1000003L + ci * 7919L + 17L

        var kMax = floor((t - begin) * rate).toLong()
        if (kMax < 0) return
        var kMin = max(0L, floor((t - begin - maxLife) * rate).toLong())
        var step = 1L
        val count = kMax - kMin + 1
        if (count > MAX_PARTICLES_PER_CELL) { step = (count + MAX_PARTICLES_PER_CELL - 1) / MAX_PARTICLES_PER_CELL; note("emitter.capped") }

        val vel0 = num(p, cell, "velocity", 0.0)
        val velR = num(p, cell, "velocityRange", 0.0)
        val lon = num(p, cell, "emissionLongitude", 0.0)
        val lat = num(p, cell, "emissionLatitude", 0.0)
        val range = num(p, cell, "emissionRange", 0.0)
        val ax = num(p, cell, "xAcceleration", 0.0)
        val ayRaw = num(p, cell, "yAcceleration", 0.0)
        val ay = if (up) -ayRaw else ayRaw
        val s0 = num(p, cell, "scale", 1.0)
        val sR = num(p, cell, "scaleRange", 0.0)
        val sSpeed = num(p, cell, "scaleSpeed", 0.0)
        val spin0 = num(p, cell, "spin", 0.0)
        val spinR = num(p, cell, "spinRange", 0.0)
        val rot0 = num(p, cell, "rotation", 0.0)
        val rotR = num(p, cell, "rotationRange", 0.0)
        val col = Values.color(cell.attrs["color"])
        val rRange = num(p, cell, "redRange", 0.0); val gRange = num(p, cell, "greenRange", 0.0)
        val bRange = num(p, cell, "blueRange", 0.0); val aRange = num(p, cell, "alphaRange", 0.0)
        val rSpeed = num(p, cell, "redSpeed", 0.0); val gSpeed = num(p, cell, "greenSpeed", 0.0)
        val bSpeed = num(p, cell, "blueSpeed", 0.0); val aSpeed = num(p, cell, "alphaSpeed", 0.0)
        val cScale = max(num(p, cell, "contentsScale", 1.0), 1e-6)
        val cr = Values.nums(cell.attrs["contentsRect"]).let { if (it.size >= 4) it else doubleArrayOf(0.0, 0.0, 1.0, 1.0) }
        val iw = img.width.toDouble(); val ih = img.height.toDouble()
        val sx = cr[0] * iw; val sw = cr[2] * iw; val sh = cr[3] * ih
        val sy = if (up) (1.0 - cr[1] - cr[3]) * ih else cr[1] * ih
        val baseW = sw / cScale
        val baseH = sh / cScale

        val ks = ArrayList<Long>()
        var k = kMin
        while (k <= kMax) { ks.add(k); k += step }
        if (!oldestLast) ks.reverse() // newest first = oldest drawn last? CA 'oldestLast' draws oldest on top
        else ks.reverse()

        for (kk in ks) {
            val tb = begin + kk / rate
            val age = t - tb
            if (age < 0.0) continue
            val life = (life0 + (rnd(seed, kk, 1) * 2 - 1) * lifeR) * e.lifetime
            if (age > life) continue

            // origin
            var ox = e.posX
            var oy = e.posY
            when (e.shape) {
                "rectangle", "cuboid", "sphere", "circle" -> when (e.mode) {
                    "outline" -> {
                        val u = rnd(seed, kk, 2) * 2.0 * (e.sizeW + e.sizeH)
                        if (u < e.sizeW) { ox += u - e.sizeW / 2; oy -= e.sizeH / 2 }
                        else if (u < e.sizeW + e.sizeH) { ox += e.sizeW / 2; oy += (u - e.sizeW) - e.sizeH / 2 }
                        else if (u < 2 * e.sizeW + e.sizeH) { ox += e.sizeW / 2 - (u - e.sizeW - e.sizeH); oy += e.sizeH / 2 }
                        else { ox -= e.sizeW / 2; oy += e.sizeH / 2 - (u - 2 * e.sizeW - e.sizeH) }
                    }
                    "points" -> {
                        val corner = floor(rnd(seed, kk, 2) * 4).toInt()
                        ox += if (corner % 2 == 0) -e.sizeW / 2 else e.sizeW / 2
                        oy += if (corner < 2) -e.sizeH / 2 else e.sizeH / 2
                    }
                    else -> {
                        ox += (rnd(seed, kk, 2) - 0.5) * e.sizeW
                        oy += (rnd(seed, kk, 3) - 0.5) * e.sizeH
                    }
                }
                "line" -> ox += (rnd(seed, kk, 2) - 0.5) * e.sizeW
                else -> {}
            }

            val theta = lon + (rnd(seed, kk, 4) * 2 - 1) * range
            val v = (vel0 + (rnd(seed, kk, 5) * 2 - 1) * velR) * e.velocity
            val vx = v * cos(theta) * cos(lat)
            val vyRaw = v * sin(theta) * cos(lat)
            val vy = if (up) -vyRaw else vyRaw
            val x = ox + vx * age + 0.5 * ax * age * age
            val yLayer = if (up) h - oy else oy
            val y = yLayer + vy * age + 0.5 * ay * age * age

            val sc = max(0.0, (s0 + (rnd(seed, kk, 6) * 2 - 1) * sR + sSpeed * age) * e.scale)
            if (sc <= 0.0) continue
            val spin = (spin0 + (rnd(seed, kk, 7) * 2 - 1) * spinR) * e.spin
            var rot = rot0 + (rnd(seed, kk, 8) * 2 - 1) * rotR + spin * age
            if (up) rot = -rot

            val r = col[0] + (rnd(seed, kk, 9) * 2 - 1) * rRange + rSpeed * age
            val g = col[1] + (rnd(seed, kk, 10) * 2 - 1) * gRange + gSpeed * age
            val b = col[2] + (rnd(seed, kk, 11) * 2 - 1) * bRange + bSpeed * age
            val a = col[3] + (rnd(seed, kk, 12) * 2 - 1) * aRange + aSpeed * age
            val pa = min(1.0, max(0.0, a)) * alpha
            if (pa <= 0.002) continue
            val tint = if (r < 0.999 || g < 0.999 || b < 0.999) doubleArrayOf(min(1.0, max(0.0, r)), min(1.0, max(0.0, g)), min(1.0, max(0.0, b)), 1.0) else null

            val dw = baseW * sc
            val dh = baseH * sc
            c.save()
            var m = Mat3.translate(x, y)
            if (rot != 0.0) {
                val cs = cos(rot); val sn = sin(rot)
                m = Mat3.mul(m, doubleArrayOf(cs, -sn, 0.0, sn, cs, 0.0, 0.0, 0.0, 1.0))
            }
            c.concat(m)
            c.drawImage(img, sx, sy, sw, sh, -dw / 2, -dh / 2, dw, dh, tint, pa, false)
            c.restore()
        }
    }
}
