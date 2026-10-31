package dev.tendies.core.render

import dev.tendies.core.caml.PathData

/** Decoded bitmap handle owned by the platform backend (Android Bitmap / Java2D BufferedImage). */
interface Img {
    val width: Int
    val height: Int
}

/** Supplies decoded images for a `.ca` package: [docDir] is the package directory, [path] is relative (e.g. `assets/a.png`). */
interface ImageProvider {
    fun image(docDir: String, path: String): Img?
}

/** Blend / compositing modes seen in the real files (`compositingFilter`), including Porter-Duff operators. */
enum class Blend {
    NORMAL, MULTIPLY, SCREEN, OVERLAY, DARKEN, LIGHTEN, DIFFERENCE, COLOR, LINEAR_BURN,
    PLUS_LIGHTER, SRC_IN, SRC_ATOP, DST_IN, DST_OUT, DST_ATOP;

    companion object {
        fun of(name: String?): Blend = when (name) {
            null, "", "normalBlendMode", "sourceOver" -> NORMAL
            "multiplyBlendMode" -> MULTIPLY
            "screenBlendMode" -> SCREEN
            "overlayBlendMode" -> OVERLAY
            "darkenBlendMode" -> DARKEN
            "lightenBlendMode" -> LIGHTEN
            "differenceBlendMode" -> DIFFERENCE
            "colorBlendMode" -> COLOR
            "linearBurnBlendMode" -> LINEAR_BURN
            "plusL", "plusLighter" -> PLUS_LIGHTER
            "sourceIn" -> SRC_IN
            "sourceAtop" -> SRC_ATOP
            "destIn" -> DST_IN
            "destOut" -> DST_OUT
            "destAtop" -> DST_ATOP
            else -> NORMAL
        }
    }
}

/**
 * Immediate-mode 2D drawing surface. The renderer only ever talks to this interface, so the exact same
 * scene code drives the Android `Canvas` backend and the Java2D backend used for desktop verification.
 * All coordinates are in the current local space (y grows downwards). Matrices are row-major 3x3 homographies.
 */
interface Canvas2D {
    fun save()
    fun restore()
    fun concat(m: DoubleArray)
    fun clipRect(x: Double, y: Double, w: Double, h: Double)
    fun clipRoundRect(x: Double, y: Double, w: Double, h: Double, r: Double)

    /** Starts an isolated group composited back with [alpha]/[blend]/[colorMatrix] (4x5 row-major, translation in 0..1) / [blur] radius. */
    fun beginGroup(alpha: Double, blend: Blend, colorMatrix: DoubleArray?, blur: Double)

    /** Ends the group; if [mask] is given it is drawn into the group as an alpha mask (DST_IN) first. */
    fun endGroup(mask: (() -> Unit)? = null)

    fun fillRoundRect(x: Double, y: Double, w: Double, h: Double, r: Double, rgba: DoubleArray, alpha: Double)
    fun strokeRoundRect(x: Double, y: Double, w: Double, h: Double, r: Double, width: Double, rgba: DoubleArray, alpha: Double)

    fun drawImage(
        img: Img, sx: Double, sy: Double, sw: Double, sh: Double,
        dx: Double, dy: Double, dw: Double, dh: Double,
        tint: DoubleArray?, alpha: Double, nearest: Boolean
    )

    /** Linear gradient from (gx0,gy0) to (gx1,gy1), or elliptical radial gradient centred at (gx0,gy0) with radii |gx1-gx0|,|gy1-gy0|. */
    fun fillGradient(
        x: Double, y: Double, w: Double, h: Double, r: Double,
        colors: List<DoubleArray>, stops: DoubleArray,
        gx0: Double, gy0: Double, gx1: Double, gy1: Double, radial: Boolean, alpha: Double
    )

    fun drawPath(path: PathData, fill: DoubleArray?, stroke: DoubleArray?, lineWidth: Double, alpha: Double)
}
