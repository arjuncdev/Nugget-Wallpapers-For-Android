package dev.tendies.app.ui

import android.app.WallpaperManager
import android.graphics.Bitmap
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.tendies.app.NuggetApp
import dev.tendies.app.data.LibraryRecord
import dev.tendies.app.data.WallpaperSettings
import dev.tendies.app.nuggetApp
import dev.tendies.app.render.AndroidImg
import dev.tendies.app.render.BitmapDecoder
import dev.tendies.app.render.WallpaperPlayer
import dev.tendies.app.render.WallpaperPreviewView
import dev.tendies.app.wallpaper.WallpaperSetter
import dev.tendies.core.anim.LogicalState
import dev.tendies.core.pkg.BatterChoices
import dev.tendies.core.pkg.BatterOption
import dev.tendies.core.pkg.BatterTemplate
import dev.tendies.core.pkg.CompatChecker
import dev.tendies.core.pkg.Support
import dev.tendies.core.pkg.TendiesArchive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max

/** Result of opening a library item for the detail screen. */
private class Loaded(val player: WallpaperPlayer?, val still: Bitmap?, val message: String, val notes: List<String>)

private fun systemOrChosenDark(appearance: Int, systemDark: Boolean) = when (appearance) { 1 -> false; 2 -> true; else -> systemDark }

private fun applySettings(p: WallpaperPlayer, s: WallpaperSettings, systemDark: Boolean) {
    p.params.zoom = max(1f, s.zoom)
    p.params.panX = s.panX
    p.params.panY = s.panY
    p.params.fps = s.fps
    p.setDark(systemOrChosenDark(s.appearance, systemDark))
}

/** Fallback when the layered engine cannot play the archive: a still image if there is one, otherwise the reason. */
private fun fallback(app: NuggetApp, rec: LibraryRecord, dark: Boolean): Loaded = try {
    val f = app.library.fileOf(rec)
    val archive = if (rec.isBatter) { val t = BatterTemplate.open(f); t.apply(t.defaults()) } else TendiesArchive.open(f)
    archive.use { a ->
        val v = a.variants.firstOrNull { it.support == Support.STATIC_IMAGE }
        val bytes = v?.stillPath(dark)?.let { a.vfs.readOrNull(it) }
        val img = bytes?.let { BitmapDecoder.decode(it, 4096) } as? AndroidImg
        if (img != null) Loaded(null, img.bitmap, "Static wallpaper", emptyList())
        else Loaded(null, null, CompatChecker.check(a).summary, emptyList())
    }
} catch (e: Exception) {
    Loaded(null, null, "Could not open this archive: " + (e.message ?: e.javaClass.simpleName), emptyList())
}

@Composable
fun DetailScreen(vm: AppViewModel, id: String, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val app = ctx.nuggetApp
    val scope = rememberCoroutineScope()
    val rec = remember(id) { app.library.find(id) }
    if (rec == null) {
        Column(Modifier.fillMaxSize().statusBarsPadding().padding(16.dp)) {
            Text("This wallpaper is no longer in the library.")
            Button(onClick = onBack) { Text("Back") }
        }
        return
    }

    val dm = ctx.resources.displayMetrics
    val sw = dm.widthPixels
    val sh = dm.heightPixels
    val systemDark = isSystemInDarkTheme()
    val soundPlayer = remember { dev.tendies.app.wallpaper.UnlockSoundPlayer(ctx.applicationContext) }
    DisposableEffect(Unit) { onDispose { soundPlayer.release() } }
    var settings by remember(id) { mutableStateOf(app.prefs.settings(id)) }
    var logical by remember(id) { mutableStateOf(LogicalState.LOCK) }
    var playing by remember(id) { mutableStateOf(true) }
    var reloadKey by remember(id) { mutableIntStateOf(0) }
    var loaded by remember(id) { mutableStateOf<Loaded?>(null) }
    var applied by remember(id) { mutableStateOf<BatterChoices?>(null) }

    // ---- template (.batter) editing state
    val template = remember(id) { if (rec.isBatter) runCatching { BatterTemplate.open(app.library.fileOf(rec)) }.getOrNull() else null }
    val picks = remember(id) { mutableStateMapOf<Int, Int>() }
    val toggles = remember(id) { mutableStateMapOf<Int, Boolean>() }
    val numbers = remember(id) { mutableStateMapOf<Int, Float>() }
    val images = remember(id) { mutableStateMapOf<Int, ByteArray>() }
    var pendingReplace by remember(id) { mutableIntStateOf(-1) }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null && pendingReplace >= 0) {
            val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            if (bytes != null) images[pendingReplace] = bytes
        }
    }

    fun buildChoices(): BatterChoices {
        val c = template!!.defaults()
        picks.forEach { (k, v) -> c.picks[k] = listOf(v) }
        toggles.forEach { (k, v) -> c.toggles[k] = v }
        numbers.forEach { (k, v) -> c.numbers[k] = v.toDouble() }
        images.forEach { (k, v) -> c.images[k] = v }
        return c
    }

    fun save(s: WallpaperSettings) {
        settings = s
        app.prefs.saveSettings(id, s)
    }

    LaunchedEffect(id, reloadKey) {
        loaded = null
        val dark = systemOrChosenDark(settings.appearance, systemDark)
        val choices = applied
        loaded = withContext(Dispatchers.Default) {
            val p = WallpaperPlayer.open(ctx.applicationContext, app.library.fileOf(rec), rec.isBatter, sw, sh, dark, choices)
            if (p != null) {
                p.prewarm(sw, sh)
                Loaded(p, null, "", p.frameWarnings.keys.toList())
            } else fallback(app, rec, dark)
        }
    }

    val cur = loaded?.player
    DisposableEffect(cur) { onDispose { cur?.close() } }
    SideEffect { if (cur != null) applySettings(cur, settings, systemDark) }

    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 4.dp)) {
            TextButton(onClick = onBack) { Text("← Back") }
            Text(rec.name, style = MaterialTheme.typography.titleLarge, maxLines = 1, modifier = Modifier.weight(1f))
        }
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.fillMaxWidth().padding(vertical = 6.dp), contentAlignment = Alignment.Center) {
                val l = loaded
                val frame = Modifier.fillMaxWidth(0.62f).aspectRatio(sw.toFloat() / sh).clip(RoundedCornerShape(22.dp))
                when {
                    l == null -> CircularProgressIndicator()
                    cur != null -> AndroidView(factory = { WallpaperPreviewView(it) }, update = { it.player = cur }, onRelease = { it.player = null }, modifier = frame)
                    l.still != null -> Image(bitmap = l.still.asImageBitmap(), contentDescription = rec.name, contentScale = ContentScale.Crop, modifier = frame)
                    else -> Text(l.message, style = MaterialTheme.typography.bodyMedium)
                }
            }
            rec.description?.let { if (it.isNotBlank()) Text(it, style = MaterialTheme.typography.bodyMedium) }
            rec.author?.let { Text("By $it", style = MaterialTheme.typography.labelMedium) }

            val l = loaded
            if (cur != null) {
                Text("Situation", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(LogicalState.LOCK to "Lock", LogicalState.HOME to "Home", LogicalState.SLEEP to "Sleep").forEach { (s, label) ->
                        FilterChip(
                            selected = logical == s,
                            onClick = {
                                val prev = logical
                                logical = s
                                cur.setLogical(s, true)
                                if (settings.playSound && (rec.name.contains("chest", ignoreCase = true) || rec.id.contains("chest", ignoreCase = true))) {
                                    if (s == LogicalState.HOME) {
                                        soundPlayer.playChestOpen()
                                    } else if (prev == LogicalState.HOME && (s == LogicalState.LOCK || s == LogicalState.SLEEP)) {
                                        soundPlayer.playChestClose()
                                    }
                                }
                            },
                            label = { Text(label) }
                        )
                    }
                }
                Text("Appearance", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(0 to "System", 1 to "Light", 2 to "Dark").forEach { (v, label) ->
                        FilterChip(selected = settings.appearance == v, onClick = { save(settings.copy(appearance = v)) }, label = { Text(label) })
                    }
                }
                Text("Zoom ×%.2f".format(settings.zoom), style = MaterialTheme.typography.titleSmall)
                Slider(value = settings.zoom, onValueChange = { save(settings.copy(zoom = it)) }, valueRange = 1f..3f)
                Text("Horizontal position", style = MaterialTheme.typography.titleSmall)
                Slider(value = settings.panX, onValueChange = { save(settings.copy(panX = it)) }, valueRange = -1f..1f)
                Text("Vertical position", style = MaterialTheme.typography.titleSmall)
                Slider(value = settings.panY, onValueChange = { save(settings.copy(panY = it)) }, valueRange = -1f..1f)
                Text("Frame rate cap (lower = better battery)", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(15, 30, 60).forEach { f ->
                        FilterChip(selected = settings.fps == f, onClick = { save(settings.copy(fps = f)) }, label = { Text("$f fps") })
                    }
                }
                if (cur.wantsMotion) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Tilt parallax (uses the accelerometer)", modifier = Modifier.weight(1f))
                        Switch(checked = settings.parallax, onCheckedChange = { save(settings.copy(parallax = it)) })
                    }
                }
                if (rec.name.contains("chest", ignoreCase = true) || rec.id.contains("chest", ignoreCase = true)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Minecraft chest sounds (open & close)", modifier = Modifier.weight(1f))
                        Switch(checked = settings.playSound, onCheckedChange = { save(settings.copy(playSound = it)) })
                    }
                }
                OutlinedButton(onClick = { playing = !playing; cur.setPlaying(playing) }) { Text(if (playing) "Pause preview" else "Play preview") }
                if (l != null && l.notes.isNotEmpty()) {
                    Text("Approximated or skipped in this wallpaper: " + l.notes.joinToString(), style = MaterialTheme.typography.labelSmall)
                }
                Button(onClick = { WallpaperSetter.applyLive(ctx, id) }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (app.prefs.activeId == id && WallpaperSetter.isOurServiceActive(ctx)) "Active live wallpaper ✓" else "Set as live wallpaper")
                }
                Text(
                    "Android asks you to confirm in the system preview; there you can usually pick home, lock or both.",
                    style = MaterialTheme.typography.labelSmall
                )
            } else if (l?.still != null) {
                val bmp = l.still!!
                Text("Static wallpaper: apply the image to…", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        "Home" to WallpaperManager.FLAG_SYSTEM,
                        "Lock" to WallpaperManager.FLAG_LOCK,
                        "Both" to (WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK)
                    ).forEach { (label, flag) ->
                        Button(onClick = {
                            scope.launch {
                                val ok = withContext(Dispatchers.IO) { runCatching { WallpaperSetter.applyStill(ctx, bmp, flag) }.isSuccess }
                                Toast.makeText(ctx, if (ok) "Wallpaper set" else "Could not set wallpaper", Toast.LENGTH_SHORT).show()
                            }
                        }) { Text(label) }
                    }
                }
            } else if (l != null) {
                Text("Not playable on Android: " + l.message, style = MaterialTheme.typography.bodyMedium)
            }

            // ---- template options
            if (template != null) {
                Spacer(Modifier.height(6.dp))
                Text("Template options", style = MaterialTheme.typography.titleMedium)
                template.options.forEach { o -> TemplateOption(o, picks, toggles, numbers, images.keys.contains(o.index)) { pendingReplace = o.index; imagePicker.launch("image/*") } }
                Button(onClick = { applied = buildChoices(); reloadKey++ }, modifier = Modifier.fillMaxWidth()) { Text("Apply options to preview") }
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            val rec2 = withContext(Dispatchers.IO) {
                                template.apply(buildChoices()).use { a ->
                                    val f = app.library.uniqueFile(rec.name + " (custom).tendies")
                                    a.writeTo(f)
                                    val r = LibraryRecord("custom-" + f.name, rec.name + " (custom)", f.name, "tendies", "template:" + rec.id, rec.category, rec.author, rec.description, rec.previewUrl)
                                    app.library.add(r)
                                    r
                                }
                            }
                            vm.refreshLibrary()
                            Toast.makeText(ctx, "Saved '" + rec2.name + "' to your library", Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Save as a new wallpaper") }
            }

            Spacer(Modifier.height(8.dp))
            TextButton(onClick = { vm.remove(id); onBack() }) { Text("Delete from library") }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun TemplateOption(
    o: BatterOption,
    picks: MutableMap<Int, Int>, toggles: MutableMap<Int, Boolean>, numbers: MutableMap<Int, Float>,
    hasImage: Boolean, onPickImage: () -> Unit
) {
    when (o.type) {
        "remove" -> Row(verticalAlignment = Alignment.CenterVertically) {
            Text(o.label, modifier = Modifier.weight(1f))
            Switch(checked = toggles[o.index] ?: ((o.defaultValue as? Boolean) ?: false), onCheckedChange = { toggles[o.index] = it })
        }
        "set" -> if (o.setterType == "toggle") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.label, modifier = Modifier.weight(1f))
                Switch(
                    checked = (numbers[o.index] ?: ((o.defaultValue as? Number)?.toFloat() ?: 0f)) != 0f,
                    onCheckedChange = { numbers[o.index] = if (it) 1f else 0f }
                )
            }
        } else {
            val lo = (o.min ?: 0.0).toFloat()
            val hi = (o.max ?: 1.0).toFloat().let { if (it <= lo) lo + 1f else it }
            val v = (numbers[o.index] ?: ((o.defaultValue as? Number)?.toFloat() ?: lo)).coerceIn(lo, hi)
            Column {
                Text("%s: %.2f".format(o.label, v))
                Slider(value = v, onValueChange = { numbers[o.index] = it }, valueRange = lo..hi)
            }
        }
        "replace" -> OutlinedButton(onClick = onPickImage, modifier = Modifier.fillMaxWidth()) {
            Text((o.raw["button_label"] as? String ?: "Choose image") + if (hasImage) " ✓" else "")
        }
        "picker" -> Column {
            Text(o.label)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                o.choices.forEachIndexed { i, (label, _) ->
                    FilterChip(selected = (picks[o.index] ?: 0) == i, onClick = { picks[o.index] = i }, label = { Text(label) })
                }
            }
        }
    }
}
