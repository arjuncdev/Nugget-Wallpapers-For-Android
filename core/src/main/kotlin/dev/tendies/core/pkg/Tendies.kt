package dev.tendies.core.pkg

import dev.tendies.core.anim.LogicalState
import dev.tendies.core.caml.CamlParser
import dev.tendies.core.caml.Diagnostics
import dev.tendies.core.fs.Vfs
import dev.tendies.core.fs.VfsUtil
import dev.tendies.core.fs.ZipVfs
import dev.tendies.core.plist.Plist
import dev.tendies.core.render.DocInstance
import dev.tendies.core.render.Img
import dev.tendies.core.render.ImageProvider
import dev.tendies.core.render.LayerRole
import dev.tendies.core.render.Scene
import java.io.File
import kotlin.math.abs

/** Value of `assets.lockAndHome.default.type` in `Wallpaper.plist` (counts from the 170 archives in the repo in docs/ANALYSIS.md). */
enum class WallpaperType {
    LAYERED_ANIMATION, STILL, LIVE, PARAMETERIZED_CA, LAYERED_STRIPE, BOKEH, UNKNOWN;

    companion object {
        fun of(s: String?): WallpaperType = when (s) {
            "LayeredAnimation" -> LAYERED_ANIMATION
            "Still" -> STILL
            "Live" -> LIVE
            "ParameterizedCA" -> PARAMETERIZED_CA
            "LayeredStripe" -> LAYERED_STRIPE
            "Bokeh" -> BOKEH
            else -> UNKNOWN
        }
    }
}

/** How well this port can reproduce a wallpaper type. */
enum class Support { FULL_ENGINE, STATIC_IMAGE, VIDEO, UNSUPPORTED }

class LayerPackage(val role: LayerRole, val dir: String)

/** One `*.wallpaper` directory (a wallpaper for a specific screen class) inside an archive. */
class WallpaperVariant(
    val vfs: Vfs,
    val dir: String,
    val descriptorId: String,
    val name: String,
    val screenClass: String?,
    val type: WallpaperType,
    val plist: Map<String, Any?>,
    val appearanceAware: Boolean,
    val layers: List<LayerPackage>,
    /** Still/Live: image file names relative to [dir]. */
    val stillLight: String?,
    val stillDark: String?,
    val videoLight: String?,
    val videoDark: String?,
    val wantsDeviceMotion: Boolean
) {
    val support: Support
        get() = when (type) {
            WallpaperType.LAYERED_ANIMATION, WallpaperType.PARAMETERIZED_CA -> Support.FULL_ENGINE
            WallpaperType.STILL -> Support.STATIC_IMAGE
            WallpaperType.LIVE -> Support.VIDEO
            else -> Support.UNSUPPORTED
        }

    /** Parses every layer package into a [Scene] (initially in the lock state, without animation). */
    fun loadScene(diag: Diagnostics = Diagnostics()): Scene {
        val docs = ArrayList<DocInstance>()
        for (lp in layers) {
            val main = vfs.readOrNull("${lp.dir}/main.caml")
            if (main == null) { diag.warn("missing ${lp.dir}/main.caml"); continue }
            val index = vfs.readOrNull("${lp.dir}/index.xml")
            val doc = try {
                CamlParser.parse(lp.dir, main, index, diag)
            } catch (e: Exception) {
                diag.warn("parse failed for ${lp.dir}: ${e.javaClass.simpleName}: ${e.message}"); null
            }
            if (doc == null) { diag.warn("unparseable ${lp.dir}/main.caml"); continue }
            docs.add(DocInstance(lp.role, doc))
        }
        val scene = Scene(docs)
        scene.setLogical(LogicalState.LOCK, 0.0, false)
        return scene
    }

    fun stillPath(dark: Boolean): String? = (if (dark && stillDark != null) stillDark else stillLight)?.let { "$dir/$it" }
    fun videoPath(dark: Boolean): String? = (if (dark && videoDark != null) videoDark else videoLight)?.let { "$dir/$it" }
}

/** An opened `.tendies` (or template-resolved `.batter`) archive. */
class TendiesArchive(val vfs: Vfs) : AutoCloseable {
    val variants: List<WallpaperVariant> = discover()

    private fun discover(): List<WallpaperVariant> {
        val out = ArrayList<WallpaperVariant>()
        for (path in vfs.paths) {
            if (!path.endsWith("/Wallpaper.plist")) continue
            val dir = path.substring(0, path.length - "/Wallpaper.plist".length)
            try {
                out.add(parseVariant(dir, path))
            } catch (e: Exception) {
                // a corrupt variant must not hide the others
            }
        }
        return out
    }

    private fun str(m: Map<String, Any?>, k: String): String? = (m[k] as? String)?.takeIf { it.isNotEmpty() }

    private fun parseVariant(dir: String, plistPath: String): WallpaperVariant {
        val pl = Plist.asDict(Plist.parse(vfs.read(plistPath)))
        val assets = Plist.asDict(pl["assets"])
        val slot = Plist.asDict(assets["lockAndHome"]).ifEmpty {
            assets.values.map { Plist.asDict(it) }.firstOrNull { it.isNotEmpty() } ?: emptyMap()
        }
        val def = Plist.asDict(slot["default"]).ifEmpty { slot.values.map { Plist.asDict(it) }.firstOrNull() ?: emptyMap() }
        val dark = Plist.asDict(slot["dark"])
        val type = WallpaperType.of(def["type"] as? String)

        val layers = ArrayList<LayerPackage>()
        fun add(role: LayerRole, name: String?) { if (name != null) layers.add(LayerPackage(role, "$dir/$name")) }
        when (type) {
            WallpaperType.LAYERED_ANIMATION -> {
                add(LayerRole.BACKGROUND, str(def, "backgroundAnimationFileName"))
                add(LayerRole.FLOATING, str(def, "floatingAnimationFileNameKey") ?: str(def, "floatingAnimationFileName"))
                add(LayerRole.FOREGROUND, str(def, "foregroundAnimationFileName"))
            }
            WallpaperType.PARAMETERIZED_CA -> add(LayerRole.SINGLE, str(def, "assetFileName"))
            else -> {}
        }

        val descriptorId = path2descriptor(dir)
        val fileName = dir.substringAfterLast('/')
        return WallpaperVariant(
            vfs = vfs, dir = dir, descriptorId = descriptorId,
            name = (pl["name"] as? String) ?: fileName,
            screenClass = pl["logicalScreenClass"] as? String,
            type = type, plist = pl,
            appearanceAware = Plist.asBool(pl["appearanceAware"]) ?: false,
            layers = layers,
            stillLight = str(def, "fullSizeImageFileName"), stillDark = str(dark, "fullSizeImageFileName"),
            videoLight = str(def, "videoFileName"), videoDark = str(dark, "videoFileName"),
            wantsDeviceMotion = Plist.asBool(pl["wantsDeviceMotion"]) ?: false
        )
    }

    private fun path2descriptor(dir: String): String {
        val parts = dir.split('/')
        val i = parts.indexOf("descriptors")
        return if (i >= 0 && i + 1 < parts.size) parts[i + 1] else ""
    }

    /** PosterBoard extension identifiers present in the archive (e.g. `com.apple.MercuryPoster`). */
    val providers: List<String> by lazy {
        val re = Regex("Extensions/([^/]+)/")
        vfs.paths.mapNotNull { re.find(it)?.groupValues?.get(1) }.distinct()
    }

    /** Writes the (possibly template-patched) archive as a new zip, e.g. to save a customised `.batter` as `.tendies`. */
    fun writeTo(out: File) {
        java.util.zip.ZipOutputStream(out.outputStream().buffered()).use { z ->
            for (path in vfs.paths) {
                val e = java.util.zip.ZipEntry(path)
                z.putNextEntry(e)
                z.write(vfs.read(path))
                z.closeEntry()
            }
        }
    }

    override fun close() = vfs.close()

    companion object {
        fun open(file: File): TendiesArchive = TendiesArchive(ZipVfs(file))
    }
}

/** What this port can do with an archive, in words a user can read. */
class Compat(val level: Level, val summary: String) {
    enum class Level { FULL, STILL, VIDEO, NONE }
}

object CompatChecker {
    fun check(a: TendiesArchive): Compat {
        val vs = a.variants
        if (vs.any { it.support == Support.FULL_ENGINE }) {
            val kinds = vs.filter { it.support == Support.FULL_ENGINE }.map { it.type }.toSet()
            val n = vs.count { it.support == Support.FULL_ENGINE }
            return Compat(Compat.Level.FULL, "Animated (${kinds.joinToString { it.name.lowercase().replace('_', ' ') }}), $n variant(s)")
        }
        if (vs.any { it.support == Support.STATIC_IMAGE }) return Compat(Compat.Level.STILL, "Static image wallpaper (light/dark)")
        if (vs.any { it.support == Support.VIDEO }) return Compat(Compat.Level.VIDEO, "Live-photo style video wallpaper")
        val p = a.providers.firstOrNull()
        val why = when {
            p == null -> "no PosterBoard wallpaper descriptor found"
            p.contains("MercuryPoster") -> "Apple's procedural Mercury poster (rendered by a native shader, no layer data)"
            p.contains("PhotosPoster") -> "Photos poster with depth matte (proprietary segmentation data)"
            else -> "unsupported PosterBoard provider ($p)"
        }
        val other = vs.firstOrNull()?.type
        return Compat(Compat.Level.NONE, if (other != null && vs.isNotEmpty()) "Not supported: ${other.name.lowercase().replace('_', ' ')} wallpaper (native Apple renderer)" else "Not supported: $why")
    }
}

/** Picks the variant of a multi-device archive that best matches the target screen. */
object VariantPicker {
    private val re = Regex("(\\d+)w-(\\d+)h@(\\d+)x~(\\w+)")

    fun best(variants: List<WallpaperVariant>, widthPx: Int, heightPx: Int): WallpaperVariant? {
        if (variants.isEmpty()) return null
        val target = minOf(widthPx, heightPx).toDouble() / maxOf(widthPx, heightPx)
        val tabletLike = target > 0.62
        return variants.minByOrNull { v ->
            val sc = v.screenClass
            val m = if (sc != null) re.find(sc) else null
            if (m == null) {
                0.05 // "any": device agnostic, always a decent choice
            } else {
                val w = m.groupValues[1].toDouble(); val h = m.groupValues[2].toDouble()
                val aspect = minOf(w, h) / maxOf(w, h)
                val ipad = m.groupValues[4] == "ipad"
                abs(aspect - target) + (if (ipad != tabletLike) 0.3 else 0.0)
            }
        }
    }
}

/**
 * [ImageProvider] over a [Vfs]. Decoding is delegated to the platform ([decode] gets the file name and bytes).
 * Decoded images are kept in a small byte-budgeted LRU cache.
 */
class VfsImageProvider(
    private val vfs: Vfs,
    private val maxBytes: Long = 96L * 1024 * 1024,
    private val decode: (String, ByteArray) -> Img?
) : ImageProvider {
    private val cache = object : LinkedHashMap<String, Img?>(64, 0.75f, true) {}
    private var bytes = 0L
    var decodeFailures = 0
        private set

    @Synchronized
    override fun image(docDir: String, path: String): Img? {
        val full = VfsUtil.join(docDir, path)
        if (cache.containsKey(full)) return cache[full]
        val data = vfs.readOrNull(full)
        val img = if (data == null) null else try { decode(full, data) } catch (e: Throwable) { null }
        if (data != null && img == null) decodeFailures++
        cache[full] = img
        if (img != null) bytes += img.width.toLong() * img.height * 4
        trim()
        return img
    }

    private fun trim() {
        if (bytes <= maxBytes) return
        val it = cache.entries.iterator()
        while (bytes > maxBytes && it.hasNext()) {
            val e = it.next()
            val v = e.value
            if (v != null) bytes -= v.width.toLong() * v.height * 4
            it.remove()
        }
    }
}
