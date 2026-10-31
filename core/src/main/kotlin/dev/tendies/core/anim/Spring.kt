package dev.tendies.core.anim

import dev.tendies.core.caml.Anim
import java.util.Collections
import java.util.WeakHashMap
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Closed-form solution of the damped harmonic oscillator that CASpringAnimation uses.
 * The response is normalised: 0 at t=0, converging to 1; [v0] is the initial velocity in
 * "fraction of the total distance per second" (positive = towards the target).
 */
object Spring {
    private val settleCache: MutableMap<Anim, Double> = Collections.synchronizedMap(WeakHashMap())

    fun progress(t: Double, mass: Double, stiffness: Double, damping: Double, v0: Double): Double {
        if (t <= 0.0) return 0.0
        val m = if (mass <= 0.0) 1.0 else mass
        val k = max(stiffness, 1e-6)
        val c = max(damping, 0.0)
        val w0 = sqrt(k / m)
        val zeta = c / (2.0 * sqrt(k * m))
        return when {
            zeta < 1.0 - 1e-9 -> {
                val wd = w0 * sqrt(1.0 - zeta * zeta)
                val e = exp(-zeta * w0 * t)
                1.0 - e * (cos(wd * t) + ((zeta * w0 - v0) / wd) * sin(wd * t))
            }
            zeta <= 1.0 + 1e-9 -> {
                val e = exp(-w0 * t)
                1.0 + (-1.0 + (v0 - w0) * t) * e
            }
            else -> {
                val s = sqrt(zeta * zeta - 1.0)
                val r1 = -w0 * (zeta - s)
                val r2 = -w0 * (zeta + s)
                val a = (v0 + r2) / (r1 - r2)
                val b = -1.0 - a
                1.0 + a * exp(r1 * t) + b * exp(r2 * t)
            }
        }
    }

    /** Time after which the spring stays within 0.1% of its target (clamped to 0.1..10 s). */
    fun settle(a: Anim): Double {
        settleCache[a]?.let { return it }
        var last = 0.0
        var t = 0.0
        val dt = 1.0 / 240.0
        while (t < 10.0) {
            t += dt
            val p = progress(t, a.mass, a.stiffness, a.damping, a.velocity)
            if (kotlin.math.abs(p - 1.0) > 0.001) last = t
        }
        val r = min(10.0, max(0.1, last + dt))
        settleCache[a] = r
        return r
    }
}
