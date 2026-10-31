package dev.tendies.app.wallpaper

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import android.os.SystemClock
import android.service.wallpaper.WallpaperService
import android.util.Log
import android.view.Display
import android.view.SurfaceHolder
import dev.tendies.app.data.WallpaperSettings
import dev.tendies.app.nuggetApp
import dev.tendies.app.render.WallpaperPlayer
import dev.tendies.core.anim.LogicalState
import kotlin.math.max

/**
 * Live wallpaper that plays the library item chosen in the app.
 *
 * Battery behaviour: nothing runs while the wallpaper is not visible or the screen is off; frames are timer-paced at the
 * user's FPS cap (default 30) instead of free-running at the display refresh rate; sensors are only registered for
 * wallpapers that declare gyro parallax, and only while visible.
 */
class TendiesWallpaperService : WallpaperService() {

    override fun onCreateEngine(): Engine = TendiesEngine()

    private inner class TendiesEngine : Engine(), SharedPreferences.OnSharedPreferenceChangeListener, SensorEventListener {
        private val thread = HandlerThread("tendies-render").also { it.start() }
        private val handler = Handler(thread.looper)

        // only touched on the render thread
        private var player: WallpaperPlayer? = null
        private var loadedId: String? = null
        private var loadedSettings: WallpaperSettings? = null
        private var running = false

        @Volatile private var width = 0
        @Volatile private var height = 0
        @Volatile private var surfaceReady = false
        @Volatile private var visible = false
        @Volatile private var screenOn = true

        private var keyguardManager: KeyguardManager? = null
        private var powerManager: PowerManager? = null
        private var displayManager: DisplayManager? = null
        private var lockedStateListener: Any? = null
        private var wasLocked = true
        private var lastUnlockSoundMs = 0L
        private var lastLockSoundMs = 0L
        private var soundPlayer: UnlockSoundPlayer? = null

        private var sensorManager: SensorManager? = null
        private var sensorOn = false
        private var smoothX = 0f
        private var smoothY = 0f
        private var receiverRegistered = false

        private val displayListener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) {}
            override fun onDisplayRemoved(displayId: Int) {}
            override fun onDisplayChanged(displayId: Int) {
                if (displayId == Display.DEFAULT_DISPLAY) {
                    val d = displayManager?.getDisplay(displayId) ?: return
                    if (d.state == Display.STATE_OFF || d.state == Display.STATE_DOZE || d.state == Display.STATE_DOZE_SUSPEND) {
                        screenOn = false
                        handler.post {
                            onLocked()
                            stopLoop()
                        }
                    } else if (d.state == Display.STATE_ON) {
                        screenOn = true
                        handler.post {
                            val km = keyguardManager
                            val locked = km?.isKeyguardLocked ?: false
                            if (wasLocked && !locked) {
                                onUnlocked()
                            }
                        }
                    }
                }
            }
        }

        private val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    Intent.ACTION_SCREEN_OFF -> {
                        screenOn = false
                        handler.post {
                            onLocked()
                            stopLoop()
                        }
                    }
                    Intent.ACTION_SCREEN_ON -> {
                        screenOn = true
                        handler.post {
                            val km = keyguardManager ?: getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
                            val locked = km?.isKeyguardLocked ?: false
                            if (!locked) {
                                onUnlocked()
                            } else {
                                onLocked()
                                startLoop()
                            }
                        }
                    }
                    Intent.ACTION_USER_PRESENT -> {
                        handler.post {
                            onUnlocked()
                        }
                    }
                }
            }
        }

        private fun isChestWallpaper(): Boolean {
            val id = loadedId ?: return false
            if (id.contains("chest", ignoreCase = true)) return true
            val rec = applicationContext.nuggetApp.library.find(id)
            return rec?.name?.contains("chest", ignoreCase = true) == true
        }

        private fun maybePlayUnlockSound() {
            val s = loadedSettings ?: return
            if (!s.playSound) return
            if (!isChestWallpaper()) return
            val now = SystemClock.uptimeMillis()
            if (now - lastUnlockSoundMs < 400L) return
            lastUnlockSoundMs = now
            soundPlayer?.playChestOpen()
        }

        private fun maybePlayLockSound() {
            val s = loadedSettings ?: return
            if (!s.playSound) return
            if (!isChestWallpaper()) return
            val now = SystemClock.uptimeMillis()
            if (now - lastLockSoundMs < 400L) return
            lastLockSoundMs = now
            soundPlayer?.playChestClose()
        }

        private fun onUnlocked() {
            val shouldPlaySound = wasLocked
            wasLocked = false
            screenOn = true
            startLoop()
            val p = player
            if (p != null) {
                p.setLogical(LogicalState.HOME, animated = true)
                drawFrame()
            }
            if (shouldPlaySound) {
                maybePlayUnlockSound()
            }
        }

        private fun onLocked() {
            val shouldPlaySound = !wasLocked
            wasLocked = true
            val p = player
            if (p != null) {
                p.setLogical(LogicalState.LOCK, animated = false)
                drawFrame()
            }
            if (shouldPlaySound) {
                maybePlayLockSound()
            }
        }

        private fun checkKeyguardState() {
            val km = keyguardManager ?: return
            val locked = km.isKeyguardLocked
            if (wasLocked && !locked) {
                onUnlocked()
            } else if (!wasLocked && locked) {
                onLocked()
            }
        }

        private val frame = object : Runnable {
            override fun run() {
                if (!running) return
                val t0 = SystemClock.uptimeMillis()
                val pm = powerManager
                if (pm != null && !pm.isInteractive) {
                    screenOn = false
                    onLocked()
                    stopLoop()
                    return
                }
                checkKeyguardState()
                drawFrame()
                val fps = (player?.params?.fps ?: 30).coerceIn(5, 60)
                val spent = SystemClock.uptimeMillis() - t0
                handler.postDelayed(this, max(1L, 1000L / fps - spent))
            }
        }

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            setTouchEventsEnabled(false)
            applicationContext.nuggetApp.prefs.register(this)
            soundPlayer = UnlockSoundPlayer(applicationContext)
            keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
            powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
            displayManager = getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
            try {
                displayManager?.registerDisplayListener(displayListener, handler)
            } catch (e: Throwable) {
                Log.w(TAG, "Failed to register DisplayListener", e)
            }
            wasLocked = keyguardManager?.isKeyguardLocked ?: true

            if (Build.VERSION.SDK_INT >= 33) {
                val listener = KeyguardManager.KeyguardLockedStateListener { isLocked ->
                    handler.post {
                        if (isLocked) {
                            onLocked()
                        } else {
                            onUnlocked()
                        }
                    }
                }
                try {
                    keyguardManager?.addKeyguardLockedStateListener(mainExecutor, listener)
                    lockedStateListener = listener
                } catch (e: Throwable) {
                    Log.w(TAG, "Failed to register KeyguardLockedStateListener", e)
                }
            }

            val f = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
            }
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(receiver, f, Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(receiver, f)
            }
            receiverRegistered = true
            sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        }

        override fun onDestroy() {
            super.onDestroy()
            applicationContext.nuggetApp.prefs.unregister(this)
            if (receiverRegistered) {
                try { unregisterReceiver(receiver) } catch (_: Throwable) {}
                receiverRegistered = false
            }
            try {
                displayManager?.unregisterDisplayListener(displayListener)
            } catch (_: Throwable) {}
            displayManager = null
            powerManager = null

            if (Build.VERSION.SDK_INT >= 33) {
                (lockedStateListener as? KeyguardManager.KeyguardLockedStateListener)?.let {
                    try { keyguardManager?.removeKeyguardLockedStateListener(it) } catch (_: Throwable) {}
                }
                lockedStateListener = null
            }
            setSensor(false)
            soundPlayer?.release()
            soundPlayer = null
            handler.post {
                stopLoop()
                player?.close()
                player = null
                thread.quitSafely()
            }
        }

        override fun onCommand(action: String?, x: Int, y: Int, z: Int, extras: Bundle?, resultRequested: Boolean): Bundle? {
            when (action) {
                "android.wallpaper.keyguard_going_away" -> {
                    handler.post { onUnlocked() }
                }
                "android.wallpaper.going_to_sleep" -> {
                    handler.post { onLocked() }
                }
            }
            return super.onCommand(action, x, y, z, extras, resultRequested)
        }

        override fun onOffsetsChanged(
            xOffset: Float,
            yOffset: Float,
            xOffsetStep: Float,
            yOffsetStep: Float,
            xPixelOffset: Int,
            yPixelOffset: Int
        ) {
            super.onOffsetsChanged(xOffset, yOffset, xOffsetStep, yOffsetStep, xPixelOffset, yPixelOffset)
            handler.post { checkKeyguardState() }
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {
            super.onSurfaceChanged(holder, format, w, h)
            width = w
            height = h
            surfaceReady = true
            handler.post {
                ensurePlayer(false)
                drawFrame()
                if (visible) startLoop()
            }
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            surfaceReady = false
            handler.post { stopLoop() }
            super.onSurfaceDestroyed(holder)
        }

        override fun onVisibilityChanged(v: Boolean) {
            visible = v
            if (v) {
                screenOn = true
                handler.post {
                    ensurePlayer(false)
                    val km = keyguardManager
                    val locked = km?.isKeyguardLocked ?: false
                    if (wasLocked && !locked) {
                        onUnlocked()
                    } else if (!wasLocked && locked) {
                        onLocked()
                    } else {
                        player?.setLogical(if (locked) LogicalState.LOCK else LogicalState.HOME, false)
                    }
                    startLoop()
                    setSensor(player?.wantsMotion == true && loadedSettings?.parallax != false)
                }
            } else {
                val pm = powerManager ?: getSystemService(Context.POWER_SERVICE) as? PowerManager
                val km = keyguardManager ?: getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
                val dm = displayManager ?: getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
                val dState = dm?.getDisplay(Display.DEFAULT_DISPLAY)?.state ?: Display.STATE_ON
                val isScreenOff = (pm?.isInteractive == false) ||
                        (km?.isKeyguardLocked == true) ||
                        (dState == Display.STATE_OFF || dState == Display.STATE_DOZE || dState == Display.STATE_DOZE_SUSPEND)

                if (isScreenOff) {
                    screenOn = false
                    handler.post {
                        onLocked()
                        stopLoop()
                    }
                } else {
                    // Check shortly after in case power manager updates to non-interactive
                    // right after hiding the wallpaper surface
                    handler.postDelayed({
                        if (!visible) {
                            val p = powerManager ?: getSystemService(Context.POWER_SERVICE) as? PowerManager
                            val d = displayManager?.getDisplay(Display.DEFAULT_DISPLAY)
                            if (p?.isInteractive == false || d?.state == Display.STATE_OFF || d?.state == Display.STATE_DOZE) {
                                screenOn = false
                                onLocked()
                                stopLoop()
                            }
                        }
                    }, 50L)
                    handler.post { stopLoop() }
                }
                setSensor(false)
            }
        }

        override fun onSharedPreferenceChanged(sp: SharedPreferences, key: String?) {
            if (key == "active" || (key != null && key.startsWith("s:"))) handler.post { ensurePlayer(false) }
        }

        private fun systemDark(): Boolean =
            (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

        // ---------------------------------------------------------- loading

        private fun ensurePlayer(force: Boolean) {
            val app = applicationContext.nuggetApp
            val id = app.prefs.activeId
            val settings = if (id != null) app.prefs.settings(id) else WallpaperSettings()
            if (!force && id == loadedId && player != null) {
                loadedSettings = settings
                return
            }
            if (id == loadedId && !force && player == null && id == null) return
            player?.close()
            player = null
            loadedId = id
            loadedSettings = settings
            if (id == null || width == 0) return
            val rec = app.library.find(id) ?: return
            val dark = when (settings.appearance) { 1 -> false; 2 -> true; else -> systemDark() }
            player = WallpaperPlayer.open(applicationContext, app.library.fileOf(rec), rec.isBatter, width, height, dark)
            if (player == null) Log.w(TAG, "could not open ${rec.fileName}")
            val km = keyguardManager
            val locked = km?.isKeyguardLocked ?: false
            val initialState = if (locked || wasLocked) LogicalState.LOCK else LogicalState.HOME
            player?.setLogical(initialState, false)
        }

        private fun applySettings(p: WallpaperPlayer) {
            val s = loadedSettings ?: return
            p.params.zoom = max(1f, s.zoom)
            p.params.panX = s.panX
            p.params.panY = s.panY
            p.params.fps = s.fps
            p.setDark(when (s.appearance) { 1 -> false; 2 -> true; else -> systemDark() })
            p.setLandscape(width > height)
            if (!s.parallax) { p.params.tiltX = 0f; p.params.tiltY = 0f }
        }

        // ------------------------------------------------------------ loop

        private fun startLoop() {
            if (running || !surfaceReady || !visible) return
            running = true
            handler.post(frame)
        }

        private fun stopLoop() {
            running = false
            handler.removeCallbacks(frame)
        }

        private fun drawFrame() {
            val holder = surfaceHolder ?: return
            var c: Canvas? = try {
                if (Build.VERSION.SDK_INT >= 26) holder.surface.lockHardwareCanvas() else holder.lockCanvas()
            } catch (e: Exception) {
                try { holder.lockCanvas() } catch (e2: Exception) { null }
            }
            if (c == null) return
            try {
                c.drawColor(Color.BLACK)
                val p = player
                if (p != null && width > 0) {
                    applySettings(p)
                    p.draw(c, width, height)
                }
            } catch (e: Throwable) {
                Log.w(TAG, "frame failed", e)
            } finally {
                try { holder.unlockCanvasAndPost(c) } catch (e: Exception) { /* surface went away */ }
            }
        }

        // ---------------------------------------------------------- sensors

        private fun setSensor(on: Boolean) {
            val sm = sensorManager ?: return
            if (on == sensorOn) return
            if (on) {
                val s = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return
                sm.registerListener(this, s, SensorManager.SENSOR_DELAY_UI)
            } else {
                sm.unregisterListener(this)
            }
            sensorOn = on
        }

        override fun onSensorChanged(e: SensorEvent) {
            val g = SensorManager.GRAVITY_EARTH
            val tx = (-e.values[0] / g * 2f).coerceIn(-1f, 1f)
            val ty = ((e.values[2] - 0.7f * g) / (0.5f * g)).coerceIn(-1f, 1f) // neutral = held at roughly 45 degrees
            smoothX += 0.12f * (tx - smoothX)
            smoothY += 0.12f * (ty - smoothY)
            player?.params?.tiltX = smoothX
            player?.params?.tiltY = smoothY
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    private companion object {
        const val TAG = "TendiesWallpaper"
    }
}
