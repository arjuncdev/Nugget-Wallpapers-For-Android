package dev.tendies.core

import dev.tendies.core.anim.LogicalState
import dev.tendies.core.anim.Spring
import dev.tendies.core.caml.CamlParser
import dev.tendies.core.caml.Diagnostics
import dev.tendies.core.caml.Mat3
import dev.tendies.core.caml.PathData
import dev.tendies.core.caml.T3
import dev.tendies.core.caml.TimingFn
import dev.tendies.core.caml.Values
import dev.tendies.core.catalog.Catalog
import dev.tendies.core.catalog.Category
import dev.tendies.core.pkg.BatterTemplate
import dev.tendies.core.pkg.Support
import dev.tendies.core.pkg.TendiesArchive
import dev.tendies.core.pkg.VfsImageProvider
import dev.tendies.core.pkg.WallpaperType
import dev.tendies.core.plist.Plist
import dev.tendies.core.render.Blend
import dev.tendies.core.render.Canvas2D
import dev.tendies.core.render.DocInstance
import dev.tendies.core.render.Img
import dev.tendies.core.render.ImageProvider
import dev.tendies.core.render.LayerRole
import dev.tendies.core.render.RenderParams
import dev.tendies.core.render.Scene
import dev.tendies.core.render.SceneRenderer
import dev.tendies.core.util.Json
import dev.tendies.core.xml.Xml
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.math.min

/** Canvas2D that records geometry in device space and checks save/restore/group balance. */
class RecordingCanvas : Canvas2D {
    class R(val x: Double, val y: Double, val w: Double, val h: Double)

    private var ctm = Mat3.identity()
    private val stack = ArrayList<DoubleArray>()
    val fills = ArrayList<R>()
    var images = 0
    var gradients = 0
    var paths = 0
    var groups = 0
    var groupDepth = 0
    val stackDepth get() = stack.size

    private fun map(x: Double, y: Double): Pair<Double, Double> {
        val w = ctm[6] * x + ctm[7] * y + ctm[8]
        return Pair((ctm[0] * x + ctm[1] * y + ctm[2]) / w, (ctm[3] * x + ctm[4] * y + ctm[5]) / w)
    }

    override fun save() { stack.add(ctm.copyOf()) }
    override fun restore() { ctm = stack.removeAt(stack.size - 1) }
    override fun concat(m: DoubleArray) {
        for (v in m) assertFalse("NaN in matrix", v.isNaN())
        ctm = Mat3.mul(ctm, m)
    }
    override fun clipRect(x: Double, y: Double, w: Double, h: Double) {}
    override fun clipRoundRect(x: Double, y: Double, w: Double, h: Double, r: Double) {}
    override fun beginGroup(alpha: Double, blend: Blend, colorMatrix: DoubleArray?, blur: Double) { groups++; groupDepth++ }
    override fun endGroup(mask: (() -> Unit)?) { mask?.invoke(); groupDepth-- }
    override fun fillRoundRect(x: Double, y: Double, w: Double, h: Double, r: Double, rgba: DoubleArray, alpha: Double) {
        val (x0, y0) = map(x, y)
        val (x1, y1) = map(x + w, y + h)
        fills.add(R(min(x0, x1), min(y0, y1), abs(x1 - x0), abs(y1 - y0)))
    }
    override fun strokeRoundRect(x: Double, y: Double, w: Double, h: Double, r: Double, width: Double, rgba: DoubleArray, alpha: Double) {}
    override fun drawImage(img: Img, sx: Double, sy: Double, sw: Double, sh: Double, dx: Double, dy: Double, dw: Double, dh: Double, tint: DoubleArray?, alpha: Double, nearest: Boolean) { images++ }
    override fun fillGradient(x: Double, y: Double, w: Double, h: Double, r: Double, colors: List<DoubleArray>, stops: DoubleArray, gx0: Double, gy0: Double, gx1: Double, gy1: Double, radial: Boolean, alpha: Double) { gradients++ }
    override fun drawPath(path: PathData, fill: DoubleArray?, stroke: DoubleArray?, lineWidth: Double, alpha: Double) { paths++ }
}

class FakeImg(override val width: Int = 64, override val height: Int = 64) : Img

object FakeImages : ImageProvider {
    override fun image(docDir: String, path: String): Img? = FakeImg()
}

class EngineTests {
    private val samples = File(System.getProperty("tendies.samples") ?: "samples")

    private fun sample(name: String): File {
        val f = File(samples, name)
        assertTrue("missing sample file $f", f.exists())
        return f
    }

    // ----------------------------------------------------------------------------- small parsers

    @Test
    fun jsonIsTolerant() {
        val v = Json.asObject(Json.parse("""{ // comment
            "a": [1, 2.5, "x\n", true, null,], /* block */ "b": {"c": -3e2,},
        }"""))
        assertEquals(5, Json.asList(v["a"]).size)
        assertEquals(1L, Json.asList(v["a"])[0])
        assertEquals(-300.0, (Json.asObject(v["b"])["c"] as Double), 1e-9)
    }

    @Test
    fun xmlPlistWithDoctypeAndInfinity() {
        val xml = """<?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
            <plist version="1.0"><dict><key>documentWidth</key><real>390</real><key>loopEnd</key><real>+infinity</real>
            <key>on</key><true/><key>name</key><string>a &amp; b</string></dict></plist>"""
        val d = Plist.asDict(Plist.parse(xml.toByteArray()))
        assertEquals(390.0, Plist.asDouble(d["documentWidth"])!!, 1e-9)
        assertTrue((d["loopEnd"] as Double).isInfinite())
        assertEquals(true, d["on"])
        assertEquals("a & b", d["name"])
    }

    @Test
    fun xmlReaderStopsAfterRootAndToleratesTrailingJunk() {
        // Waves_Bundle_Gyro.tendies really ships an unterminated comment after </caml>
        val root = Xml.parse("""<caml a="1"><CALayer name="x &lt; y"/></caml><!--MADE BY someone
            never closed""")
        assertNotNull(root)
        assertEquals("caml", root!!.name)
        assertEquals("x < y", root.children[0].attr("name"))
    }

    @Test
    fun cgPathStringsAndTiming() {
        val p = Values.path("10 20 m 30 40 50 60 70 80 c h")!!
        assertEquals("MCZ", String(p.cmds))
        assertEquals(8, p.pts.size)
        val e = TimingFn(0.42, 0.0, 0.58, 1.0)
        assertEquals(0.0, e.eval(0.0), 1e-9)
        assertEquals(1.0, e.eval(1.0), 1e-9)
        assertEquals(0.5, e.eval(0.5), 1e-3)
        var last = -1.0
        for (i in 0..100) { val y = e.eval(i / 100.0); assertTrue("monotonic", y >= last - 1e-9); last = y }
    }

    @Test
    fun transformStringsDecomposeAndRowVectorMatricesWork() {
        val t = T3.parse("translate(10, 20, 0) rotate(90deg) scale(2, 3, 1)")
        assertEquals(10.0, t[T3.TX], 1e-9)
        assertEquals(20.0, t[T3.TY], 1e-9)
        assertEquals(Math.PI / 2, t[T3.RZ], 1e-9)
        assertEquals(2.0, t[T3.SX], 1e-9)
        assertEquals(3.0, t[T3.SY], 1e-9)
        // a real `matrix(...)` value from a wallpaper: CATransform3D row-vector layout, translation in m41/m42
        val m = T3.parse("matrix(0.9659258263 0.2588190451 0 0 -0.2588190451 0.9659258263 0 0 0 0 1 0 58.9 649.7 0 1)")
        assertEquals(58.9, m[T3.TX], 1e-6)
        assertEquals(649.7, m[T3.TY], 1e-6)
        assertEquals(15.0, Math.toDegrees(m[T3.RZ]), 1e-4)
        // a decomposed transform survives the round trip through a 3x3
        val a = T3.toMat3(t)
        assertEquals(10.0, a[2], 1e-9)
        assertEquals(20.0, a[5], 1e-9)
    }

    @Test
    fun springIsNormalisedAndSettles() {
        assertEquals(0.0, Spring.progress(0.0, 2.0, 300.0, 50.0, 0.0), 1e-12)
        assertEquals(1.0, Spring.progress(8.0, 2.0, 300.0, 50.0, 0.0), 1e-3)   // near critical damping
        var over = false
        for (i in 1..400) if (Spring.progress(i / 100.0, 1.0, 200.0, 5.0, 0.0) > 1.01) over = true
        assertTrue("an under-damped spring overshoots", over)
        var monotonic = true
        var prev = 0.0
        for (i in 1..400) { val p = Spring.progress(i / 100.0, 1.0, 100.0, 80.0, 0.0); if (p < prev - 1e-9) monotonic = false; prev = p }
        assertTrue("an over-damped spring never overshoots or reverses", monotonic && prev <= 1.0 + 1e-9)
    }

    // ------------------------------------------------------------ the orientation rule (see docs/ANALYSIS.md)

    private fun synthetic(flipped: Int): ByteArray = """<?xml version="1.0"?>
        <caml xmlns="http://www.apple.com/CoreAnimation/1.0">
        <CALayer geometryFlipped="$flipped" bounds="0 0 100 200" position="50 100">
          <sublayers><CALayer name="box" bounds="0 0 10 10" position="50 20" backgroundColor="1 0 0"/></sublayers>
        </CALayer></caml>""".toByteArray()

    private fun boxTop(flipped: Int): Double {
        val doc = CamlParser.parse("t.ca", synthetic(flipped), null, Diagnostics())!!
        val scene = Scene(listOf(DocInstance(LayerRole.SINGLE, doc)))
        val rc = RecordingCanvas()
        SceneRenderer(FakeImages).draw(rc, scene, 0.0, RenderParams(100.0, 200.0))
        return rc.fills.single().y
    }

    @Test
    fun geometryFlippedZeroMeansYUpAndOneMeansYDown() {
        // Verified against Night City's preview video: flipped=0 positions are measured from the BOTTOM.
        assertEquals(175.0, boxTop(0), 1e-6)   // centre at y=20 from the bottom -> top edge at 200-20-5
        assertEquals(15.0, boxTop(1), 1e-6)    // y-down: centre at y=20 from the top -> top edge at 15
    }

    // ------------------------------------------------------------------------------- real archives

    @Test
    fun nightCityIsDiscoveredAsThreeLayerPackages() {
        TendiesArchive.open(sample("Night_City.tendies")).use { a ->
            val v = a.variants.single()
            assertEquals(WallpaperType.LAYERED_ANIMATION, v.type)
            assertEquals(Support.FULL_ENGINE, v.support)
            assertEquals(listOf(LayerRole.BACKGROUND, LayerRole.FLOATING, LayerRole.FOREGROUND), v.layers.map { it.role })
            assertTrue(v.screenClass!!.startsWith("390w-844h"))
            val diag = Diagnostics()
            val scene = v.loadScene(diag)
            assertEquals(3, scene.docs.size)
            assertTrue(diag.warnings.toString(), diag.warnings.isEmpty())
            val bg = scene.docs[0].doc
            assertTrue(bg.layerCount > 100)
            assertTrue(bg.states.keys.containsAll(listOf("Locked", "Unlock", "Sleep")))
        }
    }

    @Test
    fun everySampleRendersThroughAllStatesWithBalancedCanvasAndNoMissingImages() {
        val files = samples.listFiles { f -> f.extension == "tendies" || f.extension == "batter" }!!.sortedBy { it.name }
        assertTrue(files.size >= 4)
        for (f in files) {
            val archive = if (f.extension == "batter") BatterTemplate.open(f).let { it.apply(it.defaults()) } else TendiesArchive.open(f)
            archive.use { a ->
                val full = a.variants.filter { it.support == Support.FULL_ENGINE }
                assertTrue("${f.name}: no renderable variant", full.isNotEmpty())
                for (v in full.take(2)) {
                    val scene = v.loadScene()
                    assertFalse("${f.name}: empty scene", scene.docs.isEmpty())
                    val provider = VfsImageProvider(a.vfs) { _, _ -> FakeImg() }
                    val r = SceneRenderer(provider)
                    val rc = RecordingCanvas()
                    val rp = RenderParams(390.0, 844.0)
                    for (t in doubleArrayOf(0.0, 0.7, 3.0, 12.0)) r.draw(rc, scene, t, rp)
                    scene.setLogical(LogicalState.HOME, 12.0, true)
                    for (t in doubleArrayOf(12.1, 12.6, 14.0)) r.draw(rc, scene, t, rp)
                    scene.setLogical(LogicalState.SLEEP, 15.0, true)
                    for (t in doubleArrayOf(15.1, 16.5)) r.draw(rc, scene, t, rp)
                    scene.dark = true
                    scene.setLogical(LogicalState.LOCK, 17.0, true)
                    for (t in doubleArrayOf(17.2, 19.0)) r.draw(rc, scene, t, rp)
                    assertEquals("${f.name}: unbalanced groups", 0, rc.groupDepth)
                    assertEquals("${f.name}: unbalanced save/restore", 0, rc.stackDepth)
                    assertEquals("${f.name}: referenced images missing from the archive", 0, r.missingImages)
                    assertTrue("${f.name}: nothing drawn", rc.fills.size + rc.images + rc.gradients + rc.paths > 0)
                }
            }
        }
    }

    @Test
    fun mojoParallaxRangesAreAbsoluteValues() {
        TendiesArchive.open(sample("MOJOCARROT.tendies")).use { a ->
            val v = a.variants.first { it.support == Support.FULL_ENGINE }
            val scene = v.loadScene()
            val doc = scene.docs[0].doc
            val g = doc.parallax.first { it.keyPath == "position.x" }
            val layer = doc.byName[g.layerName]!!.first()
            SceneRenderer(FakeImages).draw(RecordingCanvas(), scene, 0.5, RenderParams(390.0, 844.0, tiltX = 0.0, tiltY = 0.0))
            assertEquals((g.mapMinTo + g.mapMaxTo) / 2, layer.pres.px, 1e-6)  // neutral tilt = middle of the authored range
            SceneRenderer(FakeImages).draw(RecordingCanvas(), scene, 0.5, RenderParams(390.0, 844.0, tiltX = 1.0))
            assertEquals(g.mapMaxTo, layer.pres.px, 1e-6)
        }
    }

    @Test
    fun batterSetOptionPatchesTheNuggetIdElementInsideTheArchiveCopy() {
        BatterTemplate.open(sample("LavaLamp.batter")).use { t ->
            val opt = t.options.first { it.type == "set" && it.identifier == "HomeZoom" }
            val c = t.defaults()
            c.numbers[opt.index] = 2.0
            val a = t.apply(c)
            val text = String(a.vfs.read(opt.files[0]), Charsets.UTF_8)
            val tag = Regex("<[^<>]*nuggetId=\"HomeZoom\"[^<>]*>").find(text)!!.value
            assertTrue(tag, Regex("value=\"2(\\.0)?\"").containsMatchIn(tag))
            assertTrue(a.variants.isNotEmpty())
            assertFalse(a.variants.first().loadScene().docs.isEmpty())
        }
    }

    @Test
    fun catalogManifestParsesAndUrlsAreEncoded() {
        val entries = Catalog.parse(Category.TEMPLATE, sample("wallpapers-template.json").readText())
        assertEquals(3, entries.size)
        assertTrue(entries.any { it.name.contains("Lava", ignoreCase = true) })
        assertTrue(entries.all { it.extension == "batter" })
        assertEquals(Catalog.RAW_BASE + "wallpapers/custom/Rolling%20Hills.tendies", Catalog.rawUrl("wallpapers/custom/Rolling Hills.tendies"))
        // the real apple manifest hosts oversized files on another server with absolute URLs
        assertEquals("https://atums.world/raw/iPhone12.tendies", Catalog.rawUrl("https://atums.world/raw/iPhone12.tendies"))
        val ext = Catalog.parse(Category.APPLE, """[{"id":15,"name":"iPhone 12","url":"https://atums.world/raw/iPhone12.tendies","preview":"previews/apple/images/iPhone12.png"}]""")
        assertTrue(ext.single().isExternal)
        assertEquals("iPhone12.tendies", ext.single().fileName)
    }

    @Test
    fun simpleMinecraftChestParsesContentsAndAnimatesLid() {
        val f = File(samples, "SimpleMinecraftChest.tendies")
        if (!f.exists()) return
        TendiesArchive.open(f).use { a ->
            val v = a.variants.first { it.support == Support.FULL_ENGINE }
            val scene = v.loadScene()
            val floatDoc = scene.docs.first { it.role == LayerRole.FLOATING }.doc
            val topChest = floatDoc.byName["Top_chest.png"]?.first()
            assertNotNull(topChest)
            assertEquals("assets/Top_chest.png", topChest?.base?.contents)

            val rc = RecordingCanvas()
            val r = SceneRenderer(FakeImages)
            val rp = RenderParams(390.0, 844.0)

            // In Locked state, lid is closed at y = 334
            scene.setLogical(LogicalState.LOCK, 0.0, false)
            r.draw(rc, scene, 0.0, rp)
            assertEquals(334.0, topChest!!.pres.py, 1e-3)

            // Switch to Home (Unlock) state with animation at t = 1.0
            scene.setLogical(LogicalState.HOME, 1.0, true)

            // Sample during transition (t = 1.2s): should be between 334.0 and 669.0
            r.draw(rc, scene, 1.2, rp)
            val midY = topChest.pres.py
            assertTrue("lid should be animating between closed (334) and open (669), got $midY", midY > 334.01 && midY < 668.99)

            // After settle time (t = 3.0s): should settle at target 669.0
            r.draw(rc, scene, 3.0, rp)
            assertEquals(669.0, topChest.pres.py, 1e-1)
        }
    }
}
