package dev.tendies.app.data

import android.content.Context
import android.content.SharedPreferences

/** Per-wallpaper user controls. */
data class WallpaperSettings(
    val zoom: Float = 1f,
    val panX: Float = 0f,
    val panY: Float = 0f,
    val fps: Int = 30,
    /** 0 = follow system, 1 = light, 2 = dark */
    val appearance: Int = 0,
    val parallax: Boolean = true,
    val playSound: Boolean = true
) {
    fun encode() = "$zoom|$panX|$panY|$fps|$appearance|$parallax|$playSound"

    companion object {
        fun decode(s: String?): WallpaperSettings {
            if (s == null) return WallpaperSettings()
            val p = s.split('|')
            return try {
                val sound = if (p.size >= 7) p[6].toBoolean() else true
                WallpaperSettings(p[0].toFloat(), p[1].toFloat(), p[2].toFloat(), p[3].toInt(), p[4].toInt(), p[5].toBoolean(), sound)
            } catch (e: Exception) {
                WallpaperSettings()
            }
        }
    }
}

class Prefs(context: Context) {
    private val sp: SharedPreferences = context.getSharedPreferences("tendies", Context.MODE_PRIVATE)

    /** Library id the live wallpaper service renders. */
    var activeId: String?
        get() = sp.getString("active", null)
        set(v) { sp.edit().putString("active", v).apply() }

    fun settings(id: String): WallpaperSettings = WallpaperSettings.decode(sp.getString("s:$id", null))

    fun saveSettings(id: String, s: WallpaperSettings) {
        sp.edit().putString("s:$id", s.encode()).apply()
    }

    fun register(l: SharedPreferences.OnSharedPreferenceChangeListener) = sp.registerOnSharedPreferenceChangeListener(l)
    fun unregister(l: SharedPreferences.OnSharedPreferenceChangeListener) = sp.unregisterOnSharedPreferenceChangeListener(l)
}
