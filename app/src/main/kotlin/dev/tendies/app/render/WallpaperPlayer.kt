package dev.tendies.app.render

import android.app.ActivityManager
import android.content.Context
import android.graphics.Canvas
import android.os.SystemClock
import dev.tendies.core.anim.LogicalState
import dev.tendies.core.pkg.BatterChoices
import dev.tendies.core.pkg.BatterTemplate
import dev.tendies.core.pkg.Support
import dev.tendies.core.pkg.TendiesArchive
import dev.tendies.core.pkg.VariantPicker
import dev.tendies.core.pkg.VfsImageProvider
import dev.tendies.core.pkg.WallpaperVariant
import dev.tendies.core.render.RenderParams
import dev.tendies.core.render.Scene
import dev.tendies.core.render.SceneRenderer
import java.io.File
import kotlin.math.max

/** User-adjustable playback/positioning controls (shared by the preview and the live wallpaper service). */
class PlayerParams {
    /** 1 = "cover" fit (the minimum); larger values zoom in. */
    @Volatile var zoom = 1f
    /** -1..1: which part of the overflowing image is visible. */
    @Volatile var panX = 0f
    @Volatile var panY = 0f
    @Volatile var fps = 30
    @Volatile var tiltX = 0f
    @Volatile var tiltY = 0f
}

/**
 * Everything needed to play one wallpaper: the parsed scene, the engine renderer, an image cache and a clock.
 * Not thread-safe: use it from a single render thread.
 */
class WallpaperPlayer private constructor(
    private val archive: TendiesArchive,
    val variant: WallpaperVariant,
    val scene: Scene,
    private val provider: VfsImageProvider,
    private val renderer: SceneRenderer
) : AutoCloseable {
    val params = PlayerParams()
    private val canvas = AndroidCanvas()
    private var baseMs = SystemClock.uptimeMillis()
    private var pausedAtMs = -1L

    /** True when the wallpaper declares gyro parallax (so sensors are worth registering). */
    val wantsMotion: Boolean = variant.wantsDeviceMotion || scene.docs.any { it.doc.parallax.isNotEmpty() }
    val frameWarnings get() = renderer.unsupported

    fun nowSeconds(): Double {
        val t = if (pausedAtMs >= 0) pausedAtMs else SystemClock.uptimeMillis()
        return (t - baseMs) / 1000.0
    }

    val isPlaying: Boolean get() = pausedAtMs < 0

    fun setPlaying(play: Boolean) {
        if (play && pausedAtMs >= 0) {
            baseMs += SystemClock.uptimeMillis() - pausedAtMs
            pausedAtMs = -1
        } else if (!play && pausedAtMs < 0) {
            pausedAtMs = SystemClock.uptimeMillis()
        }
    }

    fun setLogical(state: LogicalState, animated: Boolean = true) {
        if (scene.logical != state) scene.setLogical(state, nowSeconds(), animated)
    }

    fun setDark(dark: Boolean) {
        if (scene.dark != dark) {
            scene.dark = dark
            scene.setLogical(scene.logical, nowSeconds(), false)
        }
    }

    fun setLandscape(landscape: Boolean) {
        val o = if (landscape) "LandscapeLeft" else "PortraitUp"
        if (scene.orientation != o) {
            scene.orientation = o
            scene.setLogical(scene.logical, nowSeconds(), false)
        }
    }

    /** Draws one frame into [c] (surface size [w] x [h] pixels). */
    fun draw(c: Canvas, w: Int, h: Int) {
        canvas.begin(c, w, h)
        val d = scene.docs.firstOrNull()?.doc
        var offX = 0.0
        var offY = 0.0
        if (d != null && d.width > 0.0 && d.height > 0.0) {
            val s = max(w / d.width, h / d.height) * params.zoom
            val overflowX = max(0.0, d.width * s - w)
            val overflowY = max(0.0, d.height * s - h)
            offX = -params.panX * overflowX / 2.0
            offY = -params.panY * overflowY / 2.0
        }
        val rp = RenderParams(
            w.toDouble(), h.toDouble(), userScale = params.zoom.toDouble(), offX = offX, offY = offY,
            tiltX = params.tiltX.toDouble(), tiltY = params.tiltY.toDouble()
        )
        renderer.draw(canvas, scene, nowSeconds(), rp)
    }

    /** Decodes the images needed for the first frames off-screen so the visible preview does not stutter. */
    fun prewarm(w: Int, h: Int) {
        try {
            val bw = max(48, w / 4)
            val bh = max(48, h / 4)
            val bmp = android.graphics.Bitmap.createBitmap(bw, bh, android.graphics.Bitmap.Config.ARGB_8888)
            draw(Canvas(bmp), bw, bh)
            bmp.recycle()
        } catch (e: Throwable) {
            // warming is best effort
        }
    }

    override fun close() {
        canvas.release()
        try { archive.close() } catch (e: Exception) { /* already closed */ }
    }

    companion object {
        private fun budget(context: Context): Long {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val mb = max(am.memoryClass, am.largeMemoryClass)
            return (mb * 0.30 * 1024 * 1024).toLong().coerceIn(48L * 1024 * 1024, 256L * 1024 * 1024)
        }

        /** Builds a player from an already opened archive, or null if it contains nothing this engine can render. */
        fun fromArchive(context: Context, archive: TendiesArchive, screenW: Int, screenH: Int, dark: Boolean): WallpaperPlayer? {
            val renderable = archive.variants.filter { it.support == Support.FULL_ENGINE }
            val v = VariantPicker.best(renderable, screenW, screenH) ?: return null
            val scene = v.loadScene()
            if (scene.docs.isEmpty()) return null
            scene.dark = dark
            scene.setLogical(LogicalState.LOCK, 0.0, false)
            val provider = VfsImageProvider(archive.vfs, budget(context)) { _, bytes -> BitmapDecoder.decode(bytes) }
            return WallpaperPlayer(archive, v, scene, provider, SceneRenderer(provider))
        }

        /** Opens a stored `.tendies` (or a `.batter` with its default options). */
        fun open(context: Context, file: File, isBatter: Boolean, screenW: Int, screenH: Int, dark: Boolean, choices: BatterChoices? = null): WallpaperPlayer? {
            return try {
                val archive = if (isBatter) {
                    val t = BatterTemplate.open(file)
                    t.apply(choices ?: t.defaults())
                } else TendiesArchive.open(file)
                fromArchive(context, archive, screenW, screenH, dark) ?: run { archive.close(); null }
            } catch (e: Exception) {
                null
            }
        }
    }
}
