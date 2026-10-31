package dev.tendies.core.render

import kotlin.math.cos
import kotlin.math.sin

/** 4x5 row-major colour matrices (RGBA in 0..1, translation column also in 0..1). */
object ColorMatrices {
    fun identity() = doubleArrayOf(
        1.0, 0.0, 0.0, 0.0, 0.0,
        0.0, 1.0, 0.0, 0.0, 0.0,
        0.0, 0.0, 1.0, 0.0, 0.0,
        0.0, 0.0, 0.0, 1.0, 0.0
    )

    /** Matrix that applies [a] first and then [b]. */
    fun then(a: DoubleArray, b: DoubleArray): DoubleArray {
        val r = DoubleArray(20)
        for (i in 0 until 4) {
            for (j in 0 until 4) {
                var s = 0.0
                for (k in 0 until 4) s += b[i * 5 + k] * a[k * 5 + j]
                r[i * 5 + j] = s
            }
            var t = b[i * 5 + 4]
            for (k in 0 until 4) t += b[i * 5 + k] * a[k * 5 + 4]
            r[i * 5 + 4] = t
        }
        return r
    }

    fun isIdentity(m: DoubleArray): Boolean {
        val id = identity()
        for (i in 0 until 20) if (kotlin.math.abs(m[i] - id[i]) > 1e-9) return false
        return true
    }

    fun brightness(amount: Double): DoubleArray {
        val m = identity(); m[4] = amount; m[9] = amount; m[14] = amount; return m
    }

    fun saturate(s: Double): DoubleArray {
        val lr = 0.2126; val lg = 0.7152; val lb = 0.0722
        return doubleArrayOf(
            lr * (1 - s) + s, lg * (1 - s), lb * (1 - s), 0.0, 0.0,
            lr * (1 - s), lg * (1 - s) + s, lb * (1 - s), 0.0, 0.0,
            lr * (1 - s), lg * (1 - s), lb * (1 - s) + s, 0.0, 0.0,
            0.0, 0.0, 0.0, 1.0, 0.0
        )
    }

    fun contrast(c: Double): DoubleArray {
        val t = 0.5 * (1 - c)
        return doubleArrayOf(
            c, 0.0, 0.0, 0.0, t,
            0.0, c, 0.0, 0.0, t,
            0.0, 0.0, c, 0.0, t,
            0.0, 0.0, 0.0, 1.0, 0.0
        )
    }

    fun invert() = doubleArrayOf(
        -1.0, 0.0, 0.0, 0.0, 1.0,
        0.0, -1.0, 0.0, 0.0, 1.0,
        0.0, 0.0, -1.0, 0.0, 1.0,
        0.0, 0.0, 0.0, 1.0, 0.0
    )

    fun hueRotate(a: Double): DoubleArray {
        val c = cos(a); val s = sin(a)
        return doubleArrayOf(
            0.213 + c * 0.787 - s * 0.213, 0.715 - c * 0.715 - s * 0.715, 0.072 - c * 0.072 + s * 0.928, 0.0, 0.0,
            0.213 - c * 0.213 + s * 0.143, 0.715 + c * 0.285 + s * 0.140, 0.072 - c * 0.072 - s * 0.283, 0.0, 0.0,
            0.213 - c * 0.213 - s * 0.787, 0.715 - c * 0.715 + s * 0.715, 0.072 + c * 0.928 + s * 0.072, 0.0, 0.0,
            0.0, 0.0, 0.0, 1.0, 0.0
        )
    }

    fun multiply(rgba: DoubleArray): DoubleArray {
        val m = identity()
        m[0] = rgba.getOrElse(0) { 1.0 }; m[6] = rgba.getOrElse(1) { 1.0 }
        m[12] = rgba.getOrElse(2) { 1.0 }; m[18] = rgba.getOrElse(3) { 1.0 }
        return m
    }

    fun add(rgba: DoubleArray): DoubleArray {
        val m = identity()
        m[4] = rgba.getOrElse(0) { 0.0 }; m[9] = rgba.getOrElse(1) { 0.0 }
        m[14] = rgba.getOrElse(2) { 0.0 }; m[19] = rgba.getOrElse(3) { 0.0 }
        return m
    }
}
