package dev.tendies.app.wallpaper

import android.app.WallpaperManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import dev.tendies.app.nuggetApp

/**
 * Applying a wallpaper on Android.
 *
 * Live wallpapers cannot be applied silently: [WallpaperManager.setWallpaperComponentWithFlags] needs the
 * system-only SET_WALLPAPER_COMPONENT permission, so the app hands the user to the system's live wallpaper
 * preview, where they confirm (and, on versions/OEMs that support it, choose home / lock / both).
 * Static images can be applied directly with [WallpaperManager.setBitmap] for a chosen target.
 */
object WallpaperSetter {
    fun componentName(context: Context) = ComponentName(context, TendiesWallpaperService::class.java)

    /** Remembers [libraryId] as the wallpaper to render and opens the system confirmation screen. */
    fun applyLive(context: Context, libraryId: String) {
        context.nuggetApp.prefs.activeId = libraryId
        if (isOurServiceActive(context)) return // already selected: the running service reloads on its own
        val intent = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER)
            .putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, componentName(context))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            context.startActivity(Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    /** True if the system's current (home) live wallpaper is this app's service. */
    fun isOurServiceActive(context: Context): Boolean {
        val info = WallpaperManager.getInstance(context).wallpaperInfo ?: return false
        return info.packageName == context.packageName
    }

    /** Sets a static bitmap. [which] is a combination of WallpaperManager.FLAG_SYSTEM / FLAG_LOCK. */
    fun applyStill(context: Context, bitmap: Bitmap, which: Int) {
        WallpaperManager.getInstance(context).setBitmap(bitmap, null, true, which)
    }
}
