package dev.tendies.core.caml

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/** Column-vector 4x4 matrix stored row-major: p' = M * p. */
class Mat4(val m: DoubleArray) {
    operator fun times(o: Mat4): Mat4 {
        val r = DoubleArray(16)
        for (i in 0 until 4) for (j in 0 until 4) {
            var s = 0.0
            for (k in 0 until 4) s += m[i * 4 + k] * o.m[k * 4 + j]
            r[i * 4 + j] = s
        }
        return Mat4(r)
    }

    /** Homography for points on the z=0 plane, row-major [a b c; d e f; g h i]. */
    fun toMat3(): DoubleArray = doubleArrayOf(m[0], m[1], m[3], m[4], m[5], m[7], m[12], m[13], m[15])

    companion object {
        fun identity() = Mat4(doubleArrayOf(1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0))
        fun translate(x: Double, y: Double, z: Double = 0.0) =
            Mat4(doubleArrayOf(1.0, 0.0, 0.0, x, 0.0, 1.0, 0.0, y, 0.0, 0.0, 1.0, z, 0.0, 0.0, 0.0, 1.0))
        fun scale(x: Double, y: Double, z: Double = 1.0) =
            Mat4(doubleArrayOf(x, 0.0, 0.0, 0.0, 0.0, y, 0.0, 0.0, 0.0, 0.0, z, 0.0, 0.0, 0.0, 0.0, 1.0))
        fun rotZ(a: Double): Mat4 {
            val c = cos(a); val s = sin(a)
            return Mat4(doubleArrayOf(c, -s, 0.0, 0.0, s, c, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0))
        }
        fun rotY(a: Double): Mat4 {
            val c = cos(a); val s = sin(a)
            return Mat4(doubleArrayOf(c, 0.0, s, 0.0, 0.0, 1.0, 0.0, 0.0, -s, 0.0, c, 0.0, 0.0, 0.0, 0.0, 1.0))
        }
        fun rotX(a: Double): Mat4 {
            val c = cos(a); val s = sin(a)
            return Mat4(doubleArrayOf(1.0, 0.0, 0.0, 0.0, 0.0, c, -s, 0.0, 0.0, s, c, 0.0, 0.0, 0.0, 0.0, 1.0))
        }
        fun shearX(k: Double) = Mat4(doubleArrayOf(1.0, k, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0))
        fun perspective(m34: Double): Mat4 {
            val r = identity(); r.m[14] = m34; return r
        }
        fun axisAngle(a: Double, x: Double, y: Double, z: Double): Mat4 {
            val len = sqrt(x * x + y * y + z * z)
            if (len < 1e-12) return identity()
            val ux = x / len; val uy = y / len; val uz = z / len
            val c = cos(a); val s = sin(a); val t = 1 - c
            return Mat4(doubleArrayOf(
                t * ux * ux + c, t * ux * uy - s * uz, t * ux * uz + s * uy, 0.0,
                t * ux * uy + s * uz, t * uy * uy + c, t * uy * uz - s * ux, 0.0,
                t * ux * uz - s * uy, t * uy * uz + s * ux, t * uz * uz + c, 0.0,
                0.0, 0.0, 0.0, 1.0))
        }
        /** CATransform3D / `matrix(m11 m12 ... m44)`: row-vector layout (translation in m41,m42,m43). */
        fun fromCa16(v: DoubleArray): Mat4 {
            val r = DoubleArray(16)
            for (i in 0 until 4) for (j in 0 until 4) r[i * 4 + j] = v[j * 4 + i]
            return Mat4(r)
        }
        /** CSS-style matrix(a b c d e f). */
        fun affine(v: DoubleArray) = Mat4(doubleArrayOf(v[0], v[2], 0.0, v[4], v[1], v[3], 0.0, v[5], 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0))
    }
}

/** 3x3 row-major helpers (a homography acting on [x y 1]^T). */
object Mat3 {
    fun identity() = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)
    fun mul(a: DoubleArray, b: DoubleArray): DoubleArray {
        val r = DoubleArray(9)
        for (i in 0 until 3) for (j in 0 until 3) {
            var s = 0.0
            for (k in 0 until 3) s += a[i * 3 + k] * b[k * 3 + j]
            r[i * 3 + j] = s
        }
        return r
    }
    fun translate(x: Double, y: Double) = doubleArrayOf(1.0, 0.0, x, 0.0, 1.0, y, 0.0, 0.0, 1.0)
    fun scale(x: Double, y: Double) = doubleArrayOf(x, 0.0, 0.0, 0.0, y, 0.0, 0.0, 0.0, 1.0)
    fun isIdentity(m: DoubleArray): Boolean =
        m[0] == 1.0 && m[1] == 0.0 && m[2] == 0.0 && m[3] == 0.0 && m[4] == 1.0 && m[5] == 0.0 && m[6] == 0.0 && m[7] == 0.0 && m[8] == 1.0
}

/**
 * Layer transforms are kept *decomposed* (translate / rotate / scale / shear / perspective) because
 * Core Animation animates them through virtual key paths such as `transform.rotation.z` and
 * `transform.scale.xy`, and because interpolating components is far better behaved than blending
 * raw matrices. Layout: [tx ty tz | rx ry rz | sx sy sz | shear | m34]. Angles in radians.
 * Recomposition order: T · P · Rz · Ry · Rx · Shear · S.
 */
object T3 {
    const val TX = 0
    const val TY = 1
    const val TZ = 2
    const val RX = 3
    const val RY = 4
    const val RZ = 5
    const val SX = 6
    const val SY = 7
    const val SZ = 8
    const val SHEAR = 9
    const val M34 = 10
    const val SIZE = 11

    fun identity() = doubleArrayOf(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 1.0, 1.0, 1.0, 0.0, 0.0)

    fun isIdentity(t: DoubleArray): Boolean {
        val id = identity()
        for (i in 0 until SIZE) if (t[i] != id[i]) return false
        return true
    }

    /** Fast planar path (no allocation of Mat4) when there is no 3D component. */
    fun toMat3(t: DoubleArray): DoubleArray {
        if (t[RX] == 0.0 && t[RY] == 0.0 && t[M34] == 0.0 && t[TZ] == 0.0) {
            val c = cos(t[RZ]); val s = sin(t[RZ])
            val sx = t[SX]; val sy = t[SY]; val sh = t[SHEAR]
            val a00 = c * sx
            val a01 = c * sh * sy - s * sy
            val a10 = s * sx
            val a11 = s * sh * sy + c * sy
            return doubleArrayOf(a00, a01, t[TX], a10, a11, t[TY], 0.0, 0.0, 1.0)
        }
        return toMat4(t).toMat3()
    }

    fun toMat4(t: DoubleArray): Mat4 {
        var m = Mat4.translate(t[TX], t[TY], t[TZ])
        if (t[M34] != 0.0) m = m * Mat4.perspective(t[M34])
        if (t[RZ] != 0.0) m = m * Mat4.rotZ(t[RZ])
        if (t[RY] != 0.0) m = m * Mat4.rotY(t[RY])
        if (t[RX] != 0.0) m = m * Mat4.rotX(t[RX])
        if (t[SHEAR] != 0.0) m = m * Mat4.shearX(t[SHEAR])
        return m * Mat4.scale(t[SX], t[SY], t[SZ])
    }

    fun fromMat4(M: Mat4): DoubleArray {
        val a = M.m
        val w = a[15]
        val k = if (abs(w) < 1e-12) 1.0 else 1.0 / w
        val out = identity()
        out[TX] = a[3] * k; out[TY] = a[7] * k; out[TZ] = a[11] * k
        var c0 = doubleArrayOf(a[0] * k, a[4] * k, a[8] * k)
        var c1 = doubleArrayOf(a[1] * k, a[5] * k, a[9] * k)
        var c2 = doubleArrayOf(a[2] * k, a[6] * k, a[10] * k)
        var sx = len(c0)
        if (sx > 1e-12) c0 = div(c0, sx)
        var shear = dot(c0, c1)
        c1 = sub(c1, mul(c0, shear))
        var sy = len(c1)
        if (sy > 1e-12) { c1 = div(c1, sy); shear /= sy }
        val xz = dot(c0, c2); c2 = sub(c2, mul(c0, xz))
        val yz = dot(c1, c2); c2 = sub(c2, mul(c1, yz))
        var sz = len(c2)
        if (sz > 1e-12) c2 = div(c2, sz)
        // negative determinant => mirrored; fold the sign into the scale
        val cr = doubleArrayOf(c1[1] * c2[2] - c1[2] * c2[1], c1[2] * c2[0] - c1[0] * c2[2], c1[0] * c2[1] - c1[1] * c2[0])
        if (dot(c0, cr) < 0) { sx = -sx; c0 = mul(c0, -1.0); shear = -shear }
        val ry = asin((-c0[2]).coerceIn(-1.0, 1.0))
        val rx: Double
        val rz: Double
        if (abs(cos(ry)) > 1e-6) {
            rx = atan2(c1[2], c2[2]); rz = atan2(c0[1], c0[0])
        } else {
            rx = atan2(-c2[1], c1[1]); rz = 0.0
        }
        out[RX] = rx; out[RY] = ry; out[RZ] = rz
        out[SX] = sx; out[SY] = sy; out[SZ] = sz
        out[SHEAR] = shear
        return out
    }

    private val opRe = Regex("([A-Za-z0-9]+)\\(([^)]*)\\)")
    private fun rad(deg: Double) = deg * Math.PI / 180.0

    /** Parses CAML transform strings such as `translate(-168, -120, -999) rotate(25.05deg)` or `matrix(16 numbers)`. */
    fun parse(str: String?): DoubleArray {
        val s = str?.trim().orEmpty()
        if (s.isEmpty()) return identity()
        var m = Mat4.identity()
        var m34 = 0.0
        for (mt in opRe.findAll(s)) {
            val name = mt.groupValues[1]
            val args = Values.nums(mt.groupValues[2])
            fun arg(i: Int, d: Double) = if (i < args.size) args[i] else d
            when (name) {
                "translate" -> m = m * Mat4.translate(arg(0, 0.0), arg(1, 0.0), arg(2, 0.0))
                "scale" -> m = m * Mat4.scale(arg(0, 1.0), arg(1, arg(0, 1.0)), arg(2, 1.0))
                "rotate" ->
                    m = if (args.size >= 4) m * Mat4.axisAngle(rad(args[0]), args[1], args[2], args[3])
                    else m * Mat4.rotZ(rad(arg(0, 0.0)))
                "shearXY" -> m = m * Mat4.shearX(tan(rad(arg(0, 0.0))))
                "perspective" -> { val d = arg(0, 0.0); if (d != 0.0 && abs(d) < 1e20) m34 = -1.0 / d }
                "matrix" -> if (args.size == 16) m = m * Mat4.fromCa16(args) else if (args.size == 6) m = m * Mat4.affine(args)
            }
        }
        val t = fromMat4(m)
        t[M34] = m34
        return t
    }

    /** Component-wise blend with shortest-path angle interpolation. */
    fun lerp(a: DoubleArray, b: DoubleArray, f: Double): DoubleArray {
        val r = DoubleArray(SIZE)
        for (i in 0 until SIZE) {
            var x = a[i]; var y = b[i]
            if (i in RX..RZ) {
                val d = y - x
                if (d > Math.PI) y -= 2 * Math.PI else if (d < -Math.PI) y += 2 * Math.PI
            }
            r[i] = x + (y - x) * f
        }
        return r
    }

    private fun len(v: DoubleArray) = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
    private fun dot(a: DoubleArray, b: DoubleArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
    private fun div(a: DoubleArray, s: Double) = doubleArrayOf(a[0] / s, a[1] / s, a[2] / s)
    private fun mul(a: DoubleArray, s: Double) = doubleArrayOf(a[0] * s, a[1] * s, a[2] * s)
    private fun sub(a: DoubleArray, b: DoubleArray) = doubleArrayOf(a[0] - b[0], a[1] - b[1], a[2] - b[2])
}
