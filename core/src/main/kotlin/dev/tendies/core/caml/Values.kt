package dev.tendies.core.caml

import kotlin.math.abs

/** Cubic-bezier easing, same definition as CAMediaTimingFunction(controlPoints:). */
class TimingFn(val x1: Double, val y1: Double, val x2: Double, val y2: Double) {
    private val linear = x1 == y1 && x2 == y2

    fun eval(t: Double): Double {
        if (linear) return t
        if (t <= 0.0) return 0.0
        if (t >= 1.0) return 1.0
        var s = t
        for (k in 0 until 8) {
            val x = bez(s, x1, x2) - t
            if (abs(x) < 1e-6) return bez(s, y1, y2)
            val dx = dbez(s, x1, x2)
            if (abs(dx) < 1e-6) break
            s -= x / dx
        }
        var lo = 0.0
        var hi = 1.0
        s = t
        for (k in 0 until 40) {
            val x = bez(s, x1, x2)
            if (abs(x - t) < 1e-7) break
            if (x < t) lo = s else hi = s
            s = (lo + hi) / 2
        }
        return bez(s, y1, y2)
    }

    private fun bez(s: Double, a: Double, b: Double): Double {
        val u = 1 - s
        return 3 * u * u * s * a + 3 * u * s * s * b + s * s * s
    }

    private fun dbez(s: Double, a: Double, b: Double): Double {
        val u = 1 - s
        return 3 * u * u * a + 6 * u * s * (b - a) + 3 * s * s * (1 - b)
    }

    companion object {
        val LINEAR = TimingFn(0.0, 0.0, 1.0, 1.0)
        val EASE_IN = TimingFn(0.42, 0.0, 1.0, 1.0)
        val EASE_OUT = TimingFn(0.0, 0.0, 0.58, 1.0)
        val EASE_IN_OUT = TimingFn(0.42, 0.0, 0.58, 1.0)
        val DEFAULT = TimingFn(0.25, 0.1, 0.25, 1.0)
    }
}

/** CGPath subset: M(oveto) L(ine) C(ubic) Q(uad) Z(close). Points are packed in [pts]. */
class PathData(val cmds: CharArray, val pts: DoubleArray)

object Values {
    private val numRe = Regex("[+-]?(?:\\d+\\.?\\d*|\\.\\d+)(?:[eE][+-]?\\d+)?|[+-]?inf(?:inity)?", RegexOption.IGNORE_CASE)

    /** Extracts every number in [s] (tolerates units such as `deg`, commas, parentheses). */
    fun nums(s: String?): DoubleArray {
        if (s == null || s.isEmpty()) return DoubleArray(0)
        val out = ArrayList<Double>(8)
        for (m in numRe.findAll(s)) {
            val t = m.value
            val l = t.lowercase()
            out.add(
                if (l.endsWith("inf") || l.endsWith("infinity")) {
                    if (l.startsWith("-")) Double.NEGATIVE_INFINITY else Double.POSITIVE_INFINITY
                } else t.toDouble()
            )
        }
        return out.toDoubleArray()
    }

    fun num(s: String?, def: Double): Double {
        if (s == null) return def
        val a = nums(s)
        return if (a.isEmpty()) def else a[0]
    }

    fun bool(s: String?, def: Boolean = false): Boolean = when (s?.trim()?.lowercase()) {
        "1", "true", "yes" -> true
        "0", "false", "no" -> false
        else -> def
    }

    /** "r g b" or "r g b a" in 0..1, optionally with a separate `opacity` multiplier. */
    fun color(value: String?, opacity: String? = null): DoubleArray {
        val n = nums(value)
        val out = doubleArrayOf(0.0, 0.0, 0.0, 1.0)
        when {
            n.size >= 3 -> { out[0] = n[0]; out[1] = n[1]; out[2] = n[2]; if (n.size >= 4) out[3] = n[3] }
            n.size == 2 -> { out[0] = n[0]; out[1] = n[0]; out[2] = n[0]; out[3] = n[1] }
            n.size == 1 -> { out[0] = n[0]; out[1] = n[0]; out[2] = n[0] }
        }
        if (opacity != null) out[3] *= num(opacity, 1.0)
        return out
    }

    fun timing(s: String?): TimingFn? {
        val t = s?.trim() ?: return null
        if (t.isEmpty()) return null
        when (t.lowercase().replace("-", "").replace("_", "")) {
            "linear" -> return TimingFn.LINEAR
            "easein" -> return TimingFn.EASE_IN
            "easeout" -> return TimingFn.EASE_OUT
            "easeineaseout", "easeinout" -> return TimingFn.EASE_IN_OUT
            "default" -> return TimingFn.DEFAULT
        }
        val n = nums(t)
        return if (n.size >= 4) TimingFn(n[0], n[1], n[2], n[3]) else null
    }

    /** Parses the postfix CGPath string used by CAML, e.g. `28.6 -131.3 m 44.8 -123.4 29.8 -157.6 14.6 -141.3 c h`. */
    fun path(s: String?): PathData? {
        if (s == null || s.isBlank()) return null
        val cmds = ArrayList<Char>()
        val pts = ArrayList<Double>()
        val pending = ArrayList<Double>()
        for (tok in s.trim().split(Regex("\\s+"))) {
            if (tok.isEmpty()) continue
            val c = tok[0]
            val isOp = c.isLetter() && tok.lowercase() != "inf" && tok.lowercase() != "infinity" && tok.lowercase() != "nan"
            if (isOp) {
                val arity = when (c.lowercaseChar()) { 'm' -> 2; 'l' -> 2; 'c' -> 6; 'q' -> 4; else -> 0 }
                val op = when (c.lowercaseChar()) { 'm' -> 'M'; 'l' -> 'L'; 'c' -> 'C'; 'q' -> 'Q'; 'h', 'z' -> 'Z'; else -> '?' }
                if (op == '?') { pending.clear(); continue }
                if (arity == 0) {
                    cmds.add('Z')
                } else {
                    var k = 0
                    while (k + arity <= pending.size) {
                        cmds.add(if (op == 'M' && k > 0) 'L' else op)
                        for (q in 0 until arity) pts.add(pending[k + q])
                        k += arity
                    }
                }
                pending.clear()
            } else {
                val v = nums(tok)
                if (v.isNotEmpty()) pending.add(v[0])
            }
        }
        if (cmds.isEmpty()) return null
        return PathData(cmds.toCharArray(), pts.toDoubleArray())
    }
}
