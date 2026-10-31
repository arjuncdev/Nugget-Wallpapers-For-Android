package dev.tendies.app.render

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.util.Log
import android.view.View

/** In-app preview: runs the exact same engine/renderer as the live wallpaper service, drawn from View.onDraw. */
class WallpaperPreviewView(context: Context) : View(context) {
    var player: WallpaperPlayer? = null
        set(value) {
            field = value
            invalidate()
        }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.BLACK)
        val p = player ?: return
        try {
            p.draw(canvas, width, height)
        } catch (e: Throwable) {
            Log.w("WallpaperPreview", "draw failed", e)
        }
        if (p.isPlaying) postInvalidateDelayed(1000L / p.params.fps.coerceIn(5, 60))
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        player = null
    }
}
