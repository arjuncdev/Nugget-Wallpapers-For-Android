package dev.tendies.tools

import dev.tendies.core.anim.LogicalState
import dev.tendies.core.caml.Diagnostics
import dev.tendies.core.catalog.Catalog
import dev.tendies.core.catalog.Category
import dev.tendies.core.caml.PathData
import dev.tendies.core.pkg.BatterTemplate
import dev.tendies.core.pkg.Support
import dev.tendies.core.pkg.TendiesArchive
import dev.tendies.core.pkg.VariantPicker
import dev.tendies.core.pkg.VfsImageProvider
import dev.tendies.core.pkg.WallpaperVariant
import dev.tendies.core.render.Blend
import dev.tendies.core.render.Canvas2D
import dev.tendies.core.render.Img
import dev.tendies.core.render.ImageProvider
import dev.tendies.core.render.LayerRole
import dev.tendies.core.render.RenderParams
import dev.tendies.core.render.SceneRenderer
import dev.tendies.core.fs.VfsUtil
import dev.tendies.core.fs.Vfs
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.File
import java.util.TreeMap
import javax.imageio.ImageIO

// ---------------------------------------------------------------------------------------------- helpers

object Decoder {
    fun decode(name: String, bytes: ByteArray): Img? {
        var bi: BufferedImage? = try { ImageIO.read(ByteArrayInputStream(bytes)) } catch (e: Exception) { null }
        if (bi == null) bi = viaExternal(name, bytes)
        if (bi == null) return null
        return JImg(toArgb(bi))
    }

    private fun toArgb(src: BufferedImage): BufferedImage {
        if (src.type == BufferedImage.TYPE_INT_ARGB) return src
        val out = BufferedImage(src.width, src.height, BufferedImage.TYPE_INT_ARGB)
        val g = out.createGraphics()
        g.drawImage(src, 0, 0, null)
        g.dispose()
        return out
    }

    /** HEIC / WebP / other formats the JDK cannot read: shell out to ImageMagick or ffmpeg if present (desktop-only helper). */
    private fun viaExternal(name: String, bytes: ByteArray): BufferedImage? {
        val ext = name.substringAfterLast('.', "bin")
        val tin = File.createTempFile("tin", ".$ext")
        val tout = File.createTempFile("tout", ".png")
        try {
            tin.writeBytes(bytes)
            val cmds = listOf(
                listOf("convert", tin.path + "[0]", "PNG32:" + tout.path),
                listOf("ffmpeg", "-y", "-loglevel", "error", "-i", tin.path, "-frames:v", "1", tout.path)
            )
            for (cmd in cmds) {
                try {
                    val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
                    p.inputStream.readBytes()
                    if (p.waitFor() == 0 && tout.length() > 0) return ImageIO.read(tout)
                } catch (e: Exception) { /* tool missing */ }
            }
        } finally {
            tin.delete(); tout.delete()
        }
        return null
    }
}

class StubImg(override val width: Int = 64, override val height: Int = 64) : Img

/** Existence-checking image provider for dry runs (no decoding, so probing 1.6 GB of archives stays fast). */
class StubImages(private val vfs: Vfs) : ImageProvider {
    val missing = TreeMap<String, Int>()
    override fun image(docDir: String, path: String): Img? {
        val full = VfsUtil.join(docDir, path)
        return if (vfs.exists(full)) StubImg() else { missing[full] = (missing[full] ?: 0) + 1; null }
    }
}

class NullCanvas : Canvas2D {
    var draws = 0
    override fun save() {}
    override fun restore() {}
    override fun concat(m: DoubleArray) {
        for (v in m) require(!v.isNaN()) { "NaN in matrix" }
    }
    override fun clipRect(x: Double, y: Double, w: Double, h: Double) {}
    override fun clipRoundRect(x: Double, y: Double, w: Double, h: Double, r: Double) {}
    override fun beginGroup(alpha: Double, blend: Blend, colorMatrix: DoubleArray?, blur: Double) {}
    override fun endGroup(mask: (() -> Unit)?) { mask?.invoke() }
    override fun fillRoundRect(x: Double, y: Double, w: Double, h: Double, r: Double, rgba: DoubleArray, alpha: Double) { draws++ }
    override fun strokeRoundRect(x: Double, y: Double, w: Double, h: Double, r: Double, width: Double, rgba: DoubleArray, alpha: Double) { draws++ }
    override fun drawImage(img: Img, sx: Double, sy: Double, sw: Double, sh: Double, dx: Double, dy: Double, dw: Double, dh: Double, tint: DoubleArray?, alpha: Double, nearest: Boolean) { draws++ }
    override fun fillGradient(x: Double, y: Double, w: Double, h: Double, r: Double, colors: List<DoubleArray>, stops: DoubleArray, gx0: Double, gy0: Double, gx1: Double, gy1: Double, radial: Boolean, alpha: Double) { draws++ }
    override fun drawPath(path: PathData, fill: DoubleArray?, stroke: DoubleArray?, lineWidth: Double, alpha: Double) { draws++ }
}

private fun collect(args: List<String>): List<File> {
    val out = ArrayList<File>()
    for (a in args) {
        val f = File(a)
        if (f.isDirectory) f.walkTopDown().filter { it.isFile && it.extension.lowercase() in setOf("tendies", "batter", "passthm") }.sortedBy { it.path }.forEach { out.add(it) }
        else if (f.isFile) out.add(f)
    }
    return out
}

private fun openArchive(f: File): Pair<TendiesArchive, List<String>> {
    return when (f.extension.lowercase()) {
        "batter" -> {
            val t = BatterTemplate.open(f)
            val a = t.apply(t.defaults())
            a to t.warnings.toList()
        }
        else -> TendiesArchive.open(f) to emptyList()
    }
}

private fun <K : Comparable<K>> bump(m: MutableMap<K, Int>, k: K, by: Int = 1) { m[k] = (m[k] ?: 0) + by }

// ------------------------------------------------------------------------------------------------ probe

private fun probe(args: List<String>) {
    val files = collect(args)
    var ok = 0; var failed = 0; var skipped = 0; var unsupportedProviders = 0
    val typeCount = TreeMap<String, Int>()
    val supportCount = TreeMap<String, Int>()
    val unhandledTags = TreeMap<String, Int>(); val unhandledAttrs = TreeMap<String, Int>()
    val unsupportedArchives = TreeMap<String, MutableSet<String>>()
    val failures = ArrayList<String>(); val warnings = ArrayList<String>()
    var totalLayers = 0L; var totalAnims = 0L; var totalDocs = 0; var missingTotal = 0; var variantsTotal = 0
    var archivesWithMissing = 0

    for (f in files) {
        val label = f.parentFile.name + "/" + f.name
        if (f.extension.lowercase() == "passthm") { skipped++; println("[SKIP] $label (passcode keypad images; no Android equivalent)"); continue }
        try {
            val (arch, bw) = openArchive(f)
            arch.use {
                bw.forEach { w -> warnings.add("$label: $w") }
                val variants = arch.variants
                if (variants.isEmpty()) {
                    // different PosterBoard provider (Apple Mercury poster, Photos poster, ...): classified, not a parser failure
                    val c = dev.tendies.core.pkg.CompatChecker.check(arch)
                    unsupportedProviders++
                    println("[NONE] %-44s %s".format(label, c.summary))
                    return@use
                }
                variantsTotal += variants.size
                var docs = 0; var layers = 0; var anims = 0; var states = 0; var trans = 0
                var missing = 0
                val types = TreeSet<String>()
                val rt = TreeMap<String, Int>()
                for (v in variants.take(4)) {
                    types.add(v.type.name)
                    bump(typeCount, v.type.name)
                    bump(supportCount, v.support.name)
                    if (v.support != Support.FULL_ENGINE) continue
                    val diag = Diagnostics()
                    val scene = v.loadScene(diag)
                    if (scene.docs.isEmpty()) throw IllegalStateException("variant ${v.name}: no renderable documents (${diag.warnings.joinToString("; ")})")
                    for (d in scene.docs) { docs++; layers += d.doc.layerCount; anims += d.doc.animationCount; states += d.doc.states.size; trans += d.doc.transitions.size }
                    diag.unhandledTags.forEach { (k, c) -> bump(unhandledTags, k, c) }
                    diag.unhandledAttrs.forEach { (k, c) -> bump(unhandledAttrs, k, c) }
                    diag.warnings.forEach { w -> warnings.add("$label: $w") }
                    // dry-run render through every logical state to exercise the animation/state engine
                    val imgs = StubImages(arch.vfs)
                    val r = SceneRenderer(imgs)
                    val c = NullCanvas()
                    val rp = RenderParams(390.0, 844.0)
                    for (t in doubleArrayOf(0.0, 0.7, 3.0, 12.0)) r.draw(c, scene, t, rp)
                    scene.setLogical(LogicalState.HOME, 12.0, true)
                    for (t in doubleArrayOf(12.1, 12.6, 14.0)) r.draw(c, scene, t, rp)
                    scene.setLogical(LogicalState.SLEEP, 15.0, true)
                    for (t in doubleArrayOf(15.1, 16.5)) r.draw(c, scene, t, rp)
                    scene.setLogical(LogicalState.LOCK, 17.0, true)
                    for (t in doubleArrayOf(17.2, 19.0)) r.draw(c, scene, t, rp)
                    r.unsupported.forEach { (k, n) -> bump(rt, k, n); unsupportedArchives.getOrPut(k) { TreeSet() }.add(label) }
                    missing += imgs.missing.size
                }
                totalDocs += docs; totalLayers += layers.toLong(); totalAnims += anims.toLong(); missingTotal += missing
                if (missing > 0) archivesWithMissing++
                ok++
                println("[ OK ] %-44s variants=%d %-34s docs=%d layers=%d anims=%d states=%d trans=%d missingImg=%d unsupported=%s"
                    .format(label, variants.size, types.joinToString("+"), docs, layers, anims, states, trans, missing, rt.keys.joinToString(",")))
            }
        } catch (e: Throwable) {
            failed++
            val where = e.stackTrace.firstOrNull()?.let { "${it.fileName}:${it.lineNumber}" } ?: "?"
            failures.add("$label: ${e.javaClass.simpleName}: ${e.message} @ $where")
            println("[FAIL] $label: ${e.javaClass.simpleName}: ${e.message} @ $where")
        }
    }

    println("\n================ SUMMARY ================")
    println("archives=%d ok=%d notRenderable(other provider)=%d failed=%d skipped(passthm)=%d variants=%d".format(files.size, ok, unsupportedProviders, failed, skipped, variantsTotal))
    println("documents=%d layers=%d animations=%d  archivesWithMissingImages=%d (distinct missing files: %d)".format(totalDocs, totalLayers, totalAnims, archivesWithMissing, missingTotal))
    println("variant types: $typeCount")
    println("support classes: $supportCount")
    println("unhandled CAML tags (count): $unhandledTags")
    println("unhandled CAML attributes (count): $unhandledAttrs")
    println("render-time unsupported/approximated (archives): " + unsupportedArchives.entries.joinToString { "${it.key}=${it.value.size}" })
    if (failures.isNotEmpty()) { println("FAILURES:"); failures.forEach { println("  $it") } }
    if (warnings.isNotEmpty()) { println("WARNINGS (first 30):"); warnings.take(30).forEach { println("  $it") } }
}

private typealias TreeSet<T> = java.util.TreeSet<T>

// ----------------------------------------------------------------------------------------------- render

private fun flag(args: List<String>, name: String): String? {
    val i = args.indexOf(name)
    return if (i >= 0 && i + 1 < args.size) args[i + 1] else null
}

private fun render(args: List<String>) {
    if (args.size < 2) { println("usage: render <archive> <outDir> [--w N --h N --t 0,1,2 --variant I --logical home@2,sleep@5 --state Name@t --roles bg,float,fg --tilt x,y]"); return }
    val file = File(args[0])
    val outDir = File(args[1]).also { it.mkdirs() }
    val w = flag(args, "--w")?.toInt() ?: 390
    val h = flag(args, "--h")?.toInt() ?: 844
    val times = (flag(args, "--t") ?: "0").split(',').map { it.trim().toDouble() }.sorted()
    val events = ArrayList<Triple<Double, String, Boolean>>() // time, name/logical, isLogical
    flag(args, "--logical")?.split(',')?.forEach { val (n, t) = it.split('@'); events.add(Triple(t.toDouble(), n, true)) }
    flag(args, "--state")?.split(',')?.forEach { val (n, t) = it.split('@'); events.add(Triple(t.toDouble(), n.replace('_', ' '), false)) }
    events.sortBy { it.first }
    val roles = flag(args, "--roles")?.split(',')?.map {
        when (it.trim().lowercase()) { "bg", "background" -> LayerRole.BACKGROUND; "float", "floating" -> LayerRole.FLOATING; "fg", "foreground" -> LayerRole.FOREGROUND; else -> LayerRole.SINGLE }
    }?.toSet()
    val tilt = flag(args, "--tilt")?.split(',')?.map { it.toDouble() }

    val (arch, _) = openArchive(file)
    arch.use {
        val renderable = arch.variants.filter { it.support == Support.FULL_ENGINE }
        if (renderable.isEmpty()) { println("no renderable variants; types=${arch.variants.map { it.type }}"); return }
        val vi = flag(args, "--variant")?.toInt()
        val v: WallpaperVariant = if (vi != null) renderable[vi] else VariantPicker.best(renderable, w, h)!!
        println("variant: ${v.dir.substringAfterLast('/')} class=${v.screenClass} type=${v.type} layers=${v.layers.map { it.role }}")
        val diag = Diagnostics()
        val scene = v.loadScene(diag)
        if (args.contains("--dark")) { scene.dark = true; scene.setLogical(LogicalState.LOCK, 0.0, false) }
        println("scene ${scene.width}x${scene.height} docs=${scene.docs.map { "${it.role}:${it.doc.layerCount}L/${it.doc.animationCount}A/${it.doc.states.keys}" }}")
        val provider = VfsImageProvider(arch.vfs) { n, b -> Decoder.decode(n, b) }
        val renderer = SceneRenderer(provider)
        val stem = file.nameWithoutExtension.replace(' ', '_')
        var evi = 0
        for (t in times) {
            while (evi < events.size && events[evi].first <= t) {
                val e = events[evi++]
                if (e.third) scene.setLogical(when (e.second.lowercase()) { "home" -> LogicalState.HOME; "sleep" -> LogicalState.SLEEP; else -> LogicalState.LOCK }, e.first, true)
                else scene.setStateByName(e.second, e.first, true)
            }
            val bi = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
            val g0 = bi.createGraphics(); g0.color = java.awt.Color.BLACK; g0.fillRect(0, 0, w, h); g0.dispose()
            val cv = Java2DCanvas(bi)
            val rp = RenderParams(w.toDouble(), h.toDouble(), tiltX = tilt?.getOrNull(0) ?: 0.0, tiltY = tilt?.getOrNull(1) ?: 0.0, roles = roles)
            val t0 = System.nanoTime()
            renderer.draw(cv, scene, t, rp)
            cv.finish()
            val ms = (System.nanoTime() - t0) / 1e6
            val out = File(outDir, "%s_t%05.2f.png".format(stem, t))
            ImageIO.write(bi, "png", out)
            println("t=%.2f layersDrawn=%d %.0fms -> %s".format(t, renderer.drawnLayers, ms, out.path))
        }
        println("unsupported/approximated at runtime: ${renderer.unsupported}  missingImages=${renderer.missingImages}  decodeFailures=${provider.decodeFailures}")
        println("parse diagnostics: tags=${diag.unhandledTags} attrs=${diag.unhandledAttrs} warnings=${diag.warnings}")
    }
}

// --------------------------------------------------------------------------------------- catalog / batter

private fun catalogCheck(args: List<String>) {
    val root = File(args.firstOrNull() ?: ".")
    var total = 0; var missingFiles = 0; var missingPreviews = 0
    for (cat in Category.values()) {
        val mf = File(root, cat.manifest)
        val entries = Catalog.parse(cat, mf.readText())
        val external = entries.count { it.isExternal }
        val miss = entries.filter { !it.isExternal && !File(root, it.path).exists() }
        val missP = entries.filter { it.previewPath.isNotEmpty() && !File(root, it.previewPath).exists() }
        total += entries.size; missingFiles += miss.size; missingPreviews += missP.size
        println("%-18s entries=%3d hostedExternally=%d archivesMissingLocally=%2d previewsMissingLocally=%d extensions=%s".format(
            cat.manifest, entries.size, external, miss.size, missP.size, entries.groupBy { it.extension }.mapValues { it.value.size }))
        if (miss.isNotEmpty()) println("   not in the cloned repo (e.g. git-ignored/oversized): " + miss.take(6).joinToString { it.fileName })
        if (entries.isNotEmpty()) println("   sample url: " + Catalog.rawUrl(entries[0].path))
    }
    println("TOTAL entries=$total archivesMissingLocally=$missingFiles previewsMissingLocally=$missingPreviews")
}

private fun batterInfo(args: List<String>) {
    for (f in collect(args)) {
        val t = BatterTemplate.open(f)
        println("== ${f.name}: '${t.title}' by ${t.author} — ${t.description}")
        t.options.forEach { o -> println("   [${o.index}] ${o.type.padEnd(8)} ${o.label}  files=${o.files.size} key=${o.key} id=${o.identifier} default=${o.defaultValue}") }
        val a = t.apply(t.defaults())
        println("   applied defaults: variants=${a.variants.map { it.dir.substringAfterLast('/') + ":" + it.type }}  warnings=${t.warnings}")
        for (v in a.variants.take(2)) {
            val d = Diagnostics()
            val s = v.loadScene(d)
            println("   scene ${s.width}x${s.height} docs=${s.docs.map { it.role.name + ":" + it.doc.layerCount + "L" }} warnings=${d.warnings}")
        }
    }
}

fun main(args: Array<String>) {
    System.setProperty("java.awt.headless", "true")
    val rest = args.drop(1)
    when (args.firstOrNull()) {
        "probe" -> probe(rest)
        "render" -> render(rest)
        "catalog" -> catalogCheck(rest)
        "batter" -> batterInfo(rest)
        else -> println("commands: probe <files|dirs...> | render <archive> <outDir> [flags] | catalog <repoRoot> | batter <files|dirs...>")
    }
}
