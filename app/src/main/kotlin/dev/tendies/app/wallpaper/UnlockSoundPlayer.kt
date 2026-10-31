package dev.tendies.app.wallpaper

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import android.util.Log
import dev.tendies.app.R

/**
 * Plays the Minecraft chest open/close sound on unlock/lock (respecting system ringer mode / silent mode).
 */
class UnlockSoundPlayer(private val context: Context) {
    private var soundPool: SoundPool? = null
    private var chestOpenId = 0
    private var chestCloseId = 0
    private var isOpenLoaded = false
    private var isCloseLoaded = false
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    init {
        try {
            val attrs = AudioAttributes.Builder()
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .build()
            val pool = SoundPool.Builder()
                .setMaxStreams(2)
                .setAudioAttributes(attrs)
                .build()
            pool.setOnLoadCompleteListener { _, sampleId, status ->
                if (status == 0) {
                    if (sampleId == chestOpenId) isOpenLoaded = true
                    if (sampleId == chestCloseId) isCloseLoaded = true
                }
            }
            soundPool = pool
            chestOpenId = pool.load(context, R.raw.chest_open, 1)
            chestCloseId = pool.load(context, R.raw.chest_close, 1)
        } catch (e: Throwable) {
            Log.w("UnlockSoundPlayer", "Failed to init SoundPool", e)
        }
    }

    fun playChestOpen() {
        val sp = soundPool ?: return
        if (!isOpenLoaded || chestOpenId == 0) return
        val ringer = audioManager?.ringerMode ?: AudioManager.RINGER_MODE_NORMAL
        if (ringer != AudioManager.RINGER_MODE_NORMAL) {
            // Respect silent and vibrate modes
            return
        }
        try {
            sp.play(chestOpenId, 1.0f, 1.0f, 1, 0, 1.0f)
        } catch (e: Throwable) {
            Log.w("UnlockSoundPlayer", "Failed to play chest open sound", e)
        }
    }

    fun playChestClose() {
        val sp = soundPool ?: return
        if (!isCloseLoaded || chestCloseId == 0) return
        val ringer = audioManager?.ringerMode ?: AudioManager.RINGER_MODE_NORMAL
        if (ringer != AudioManager.RINGER_MODE_NORMAL) {
            // Respect silent and vibrate modes
            return
        }
        try {
            sp.play(chestCloseId, 1.0f, 1.0f, 1, 0, 1.0f)
        } catch (e: Throwable) {
            Log.w("UnlockSoundPlayer", "Failed to play chest close sound", e)
        }
    }

    fun release() {
        try {
            soundPool?.release()
        } catch (_: Throwable) {}
        soundPool = null
        isOpenLoaded = false
        isCloseLoaded = false
    }
}
