package dev.tendies.core.anim

import dev.tendies.core.caml.Anim
import dev.tendies.core.caml.T3
import dev.tendies.core.caml.Val
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Evaluates a parsed [Anim] at a given layer-local time. Stateless and allocation-light. */
object AnimEval {
    /** Length of one simple cycle in seconds. Springs settle on their own unless a duration is pinned. */
    fun simpleDuration(a: Anim): Double =
        if (a.isSpring && (a.autoRecalc || a.duration <= 0.0)) Spring.settle(a) else a.duration

    private fun cycleLen(a: Anim, dur: Double) = if (a.autoreverses) dur * 2.0 else dur

    private fun activeDuration(a: Anim, dur: Double): Double {
        val cl = cycleLen(a, dur)
        return when {
            a.repeatDuration > 0.0 -> a.repeatDuration
            a.repeatCount.isInfinite() -> Double.POSITIVE_INFINITY
            else -> cl * (if (a.repeatCount > 0.0) a.repeatCount else 1.0)
        }
    }

    /** Layer time at which a finite animation has completely finished (infinity for endless repeats). */
    fun endTime(a: Anim): Double {
        val dur = max(simpleDuration(a), 1e-9)
        val active = activeDuration(a, dur)
        if (active.isInfinite()) return Double.POSITIVE_INFINITY
        val sp = if (a.speed == 0.0) 1e-9 else a.speed
        return a.beginTime + (active - a.timeOffset) / sp
    }

    /**
     * Samples [a] at layer-local time [t]; null means "no effect right now" (before start / after end
     * without a matching fill mode). [fromOv]/[toOv] supply the endpoints for state-transition templates.
     */
    fun sample(a: Anim, t: Double, fromOv: Val? = null, toOv: Val? = null): Val? {
        val dur = max(simpleDuration(a), 1e-9)
        val s = (t - a.beginTime) * a.speed + a.timeOffset
        val cl = cycleLen(a, dur)
        val active = activeDuration(a, dur)

        val x: Double
        if (s < 0.0) {
            if (a.fillMode and 2 == 0) return null
            x = 0.0
        } else if (s >= active) {
            if (a.fillMode and 1 == 0) return null
            x = active
        } else {
            x = s
        }

        // position inside the current cycle
        val idx = floor(x / cl)
        var inCycle = x - idx * cl
        if (x >= active && x > 0.0 && inCycle < 1e-12) inCycle = cl
        val p = if (!a.autoreverses) inCycle / dur
        else if (inCycle <= dur) inCycle / dur
        else 2.0 - inCycle / dur
        val pc = min(1.0, max(0.0, p))

        if (a.isSpring) {
            val from = fromOv ?: a.from ?: a.values?.firstOrNull() ?: return null
            val to = toOv ?: a.to ?: a.values?.lastOrNull() ?: return null
            val u = min(inCycle, dur)
            val e = if (inCycle >= dur) 1.0 else Spring.progress(u, a.mass, a.stiffness, a.damping, a.velocity)
            return lerp(from, to, e)
        }
        if (a.isKeyframe && a.values != null && a.values.isNotEmpty()) {
            val e = a.timing?.eval(pc) ?: pc
            return keyframe(a, e)
        }
        // basic
        val from = fromOv ?: a.from ?: a.values?.firstOrNull()
        var to = toOv ?: a.to ?: a.values?.lastOrNull()
        if (to == null && a.by != null && from != null) to = add(from, a.by)
        if (from == null || to == null) return null
        val e = a.timing?.eval(pc) ?: pc
        return lerp(from, to, e)
    }

    fun add(a: Val, b: Val): Val {
        val x = a.n; val y = b.n
        if (x == null || y == null || x.size != y.size) return a
        return Val.of(DoubleArray(x.size) { x[it] + y[it] })
    }

    fun lerp(a: Val, b: Val, f: Double): Val {
        val pa = a.path
        val pb = b.path
        if (pa != null && pb != null) {
            if (pa.cmds.contentEquals(pb.cmds) && pa.pts.size == pb.pts.size) {
                if (f == 0.0) return a
                if (f == 1.0) return b
                return Val.path(dev.tendies.core.caml.PathData(pa.cmds, DoubleArray(pa.pts.size) { pa.pts[it] + (pb.pts[it] - pa.pts[it]) * f }))
            }
            return if (f >= 1.0) b else a
        }
        val x = a.n
        val y = b.n
        if (x != null && y != null && x.size == y.size) {
            if (x.size == T3.SIZE) return Val.of(T3.lerp(x, y, f))
            if (f == 0.0) return a
            if (f == 1.0) return b
            return Val.of(DoubleArray(x.size) { x[it] + (y[it] - x[it]) * f })
        }
        return if (f >= 1.0) b else a
    }

    private fun distance(a: Val, b: Val): Double {
        val x = a.n; val y = b.n
        if (x == null || y == null || x.size != y.size) return 1.0
        var s = 0.0
        for (i in x.indices) { val d = y[i] - x[i]; s += d * d }
        return sqrt(s)
    }

    private fun evenTimes(vs: List<Val>, paced: Boolean): DoubleArray {
        val n = vs.size
        val kt = DoubleArray(n)
        if (n == 1) return kt
        if (!paced) {
            for (i in 0 until n) kt[i] = i.toDouble() / (n - 1)
            return kt
        }
        var total = 0.0
        val d = DoubleArray(n)
        for (i in 1 until n) { total += distance(vs[i - 1], vs[i]); d[i] = total }
        if (total <= 1e-12) {
            for (i in 0 until n) kt[i] = i.toDouble() / (n - 1)
        } else {
            for (i in 0 until n) kt[i] = d[i] / total
        }
        return kt
    }

    private fun keyframe(a: Anim, p: Double): Val? {
        val vs = a.values ?: return null
        val n = vs.size
        if (n == 0) return null
        if (n == 1) return vs[0]
        val mode = a.calcMode

        if (mode == "discrete") {
            val k = a.keyTimes ?: DoubleArray(n + 1) { it.toDouble() / n }
            var i = 0
            while (i < k.size - 1 && p >= k[i + 1]) i++
            return vs[min(i, n - 1)]
        }

        val paced = mode == "paced" || mode == "cubicPaced"
        val kt = if (!paced && a.keyTimes != null && a.keyTimes.size == n) a.keyTimes else evenTimes(vs, paced)
        if (p <= kt[0]) return vs[0]
        if (p >= kt[n - 1]) return vs[n - 1]
        var i = 0
        while (i < n - 2 && p >= kt[i + 1]) i++
        val seg = kt[i + 1] - kt[i]
        var f = if (seg <= 1e-12) 1.0 else (p - kt[i]) / seg
        val st = a.segTiming
        if (st != null && i < st.size) f = st[i].eval(f)
        return if (mode == "cubic" || mode == "cubicPaced") catmull(vs, i, f) else lerp(vs[i], vs[i + 1], f)
    }

    /** Uniform Catmull-Rom through the keyframe values (numeric only; others fall back to linear). */
    private fun catmull(vs: List<Val>, i: Int, t: Double): Val {
        val p1 = vs[i].n ?: return lerp(vs[i], vs[i + 1], t)
        val p2 = vs[i + 1].n ?: return lerp(vs[i], vs[i + 1], t)
        if (p1.size != p2.size || p1.size == T3.SIZE) return lerp(vs[i], vs[i + 1], t)
        val p0 = vs.getOrNull(i - 1)?.n?.takeIf { it.size == p1.size } ?: DoubleArray(p1.size) { 2 * p1[it] - p2[it] }
        val p3 = vs.getOrNull(i + 2)?.n?.takeIf { it.size == p1.size } ?: DoubleArray(p1.size) { 2 * p2[it] - p1[it] }
        val t2 = t * t
        val t3 = t2 * t
        return Val.of(DoubleArray(p1.size) {
            0.5 * ((2 * p1[it]) + (-p0[it] + p2[it]) * t + (2 * p0[it] - 5 * p1[it] + 4 * p2[it] - p3[it]) * t2 +
                (-p0[it] + 3 * p1[it] - 3 * p2[it] + p3[it]) * t3)
        })
    }
}
