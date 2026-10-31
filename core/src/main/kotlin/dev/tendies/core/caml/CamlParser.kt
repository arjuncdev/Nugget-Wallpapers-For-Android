package dev.tendies.core.caml

import dev.tendies.core.fs.VfsUtil
import dev.tendies.core.plist.Plist
import dev.tendies.core.xml.XNode
import dev.tendies.core.xml.Xml

/**
 * Parser for Apple's Core Animation Markup Language (`main.caml`) as found in PosterBoard `.ca` packages.
 * The grammar implemented here was derived from the real files in the repository (see docs/ANALYSIS.md).
 */
object CamlParser {
    private class Ctx(val diag: Diagnostics) {
        val byId = HashMap<String, CamlLayer>()
        val byName = HashMap<String, MutableList<CamlLayer>>()
        var layers = 0
        var anims = 0
    }

    private val SILENT_ATTRS = hashSetOf(
        "allowsEdgeAntialiasing", "allowsGroupOpacity", "allowsGroupBlending", "allowsHitTesting", "contentsFormat",
        "cornerCurve", "mica_expanded", "micaID", "rasterizationScale", "shouldRasterize", "edgeAntialiasingMask",
        "object", "classIfAvailable", "id", "name", "type", "minificationFilterBias", "interpolations", "wrapped",
        "resizingMode", "tracking", "leading", "verticalAlignmentMode", "truncationMode", "preservesDepth",
        "shadowRadius", "shadowOffset", "shadowPathIsBounds", "scale", "samplingRate", "windowServerAware",
        "groupName", "bounds", "position"
    )

    fun parse(dir: String, main: ByteArray, index: ByteArray?, diag: Diagnostics): CamlDocument? {
        val xml = Xml.parse(main) ?: return null
        val layerEl = xml.children.firstOrNull { isLayerEl(it) } ?: return null
        val ctx = Ctx(diag)
        val root = parseLayer(layerEl, ctx)

        var w = 0.0
        var h = 0.0
        if (index != null) {
            try {
                val d = Plist.asDict(Plist.parse(index))
                w = Plist.asDouble(d["documentWidth"]) ?: 0.0
                h = Plist.asDouble(d["documentHeight"]) ?: 0.0
            } catch (e: Exception) {
                diag.warn("index.xml unreadable: ${e.message}")
            }
        }
        if (w <= 0.0 || h <= 0.0) { w = root.base.bw; h = root.base.bh }

        val states = LinkedHashMap<String, StateDef>()
        val transitions = ArrayList<TransitionDef>()
        layerEl.child("states")?.let { parseStates(it, states, ctx) }
        layerEl.child("stateTransitions")?.let { parseTransitions(it, transitions, ctx) }

        val groups = ArrayList<PropertyGroup>()
        val parallax = ArrayList<ParallaxGroup>()
        layerEl.child("style")?.let { parseStyle(it, groups, parallax) }

        return CamlDocument(
            dir, w, h, root, ctx.byId, ctx.byName, states, states.keys.toList(), transitions,
            groups, parallax, ctx.layers, ctx.anims
        )
    }

    // ---------------------------------------------------------------- layers

    private fun layerClass(el: XNode): String = when (el.name) {
        "mask", "object" -> el.attr("type") ?: "CALayer"
        else -> el.name
    }

    private fun isLayerEl(el: XNode): Boolean = el.name.endsWith("Layer") || el.name == "mask" || el.name == "object"

    private fun kindOf(cls: String): Kind = when (cls) {
        "CALayer", "LKLayer" -> Kind.LAYER
        "CATransformLayer" -> Kind.TRANSFORM
        "CAGradientLayer" -> Kind.GRADIENT
        "CAShapeLayer" -> Kind.SHAPE
        "CAEmitterLayer" -> Kind.EMITTER
        "CAReplicatorLayer" -> Kind.REPLICATOR
        "CATextLayer", "LKTextLayer" -> Kind.TEXT
        "CABackdropLayer" -> Kind.BACKDROP
        else -> Kind.OTHER
    }

    fun decodeSrc(src: String?): String? = src?.let { VfsUtil.percentDecode(it) }

    fun extractSrc(node: XNode?): String? {
        if (node == null) return null
        node.attr("src")?.let { return it }
        node.child("CGImage")?.attr("src")?.let { return it }
        for (c in node.children) {
            val s = extractSrc(c)
            if (s != null) return s
        }
        return null
    }

    private fun parseLayer(el: XNode, ctx: Ctx): CamlLayer {
        val cls = layerClass(el)
        val kind = kindOf(cls)
        val L = CamlLayer(kind, cls)
        ctx.layers++
        if (kind == Kind.OTHER) ctx.diag.tag("layer:$cls")
        when (kind) {
            Kind.GRADIENT -> L.gradient = GradientDef()
            Kind.SHAPE -> L.shape = ShapeDef()
            Kind.EMITTER -> L.emitter = EmitterDef()
            Kind.REPLICATOR -> L.replicator = ReplicatorDef()
            Kind.TEXT -> L.text = TextDef()
            else -> {}
        }
        L.id = el.attr("id")
        L.name = el.attr("name") ?: ""
        L.id?.let { ctx.byId[it] = L }
        if (L.name.isNotEmpty()) ctx.byName.getOrPut(L.name) { ArrayList() }.add(L)
        applyAttrs(L, el, ctx)

        for (ch in el.children) {
            when (ch.name) {
                "sublayers" -> for (sl in ch.children) {
                    if (isLayerEl(sl)) {
                        val child = parseLayer(sl, ctx)
                        L.sublayers.add(child)
                        if (child.base.z != 0.0 || child.hasZ) L.hasZ = true
                    } else ctx.diag.tag("sublayers/${sl.name}")
                }
                "animations" -> for (an in ch.children) {
                    // the CAPlayground/Mica tooling also emits animations as <p ...> (e.g. Super Mario Bros' sprite animation)
                    if (an.name == "animation" || an.name == "p" || (an.attr("type")?.endsWith("Animation") == true)) {
                        parseAnim(an)?.let {
                            L.animations.add(it); ctx.anims++
                            if (it.keyPath == "zPosition") L.hasZ = true
                        }
                    } else ctx.diag.tag("animations/${an.name}")
                }
                "contents" -> L.base.contents = decodeSrc(extractSrc(ch))
                "mask" -> L.mask = parseLayer(ch, ctx)
                "backgroundColor" -> L.base.bg.setFrom(childColor(ch))
                "borderColor" -> L.base.borderColor.setFrom(childColor(ch))
                "fillColor" -> { L.base.fill.setFrom(childColor(ch)); L.base.hasFill = true }
                "strokeColor" -> L.base.stroke.setFrom(childColor(ch))
                "contentsMultiplyColor" -> if (ch.attr("value") != null) { L.base.multiply.setFrom(childColor(ch)); L.base.hasMultiply = true }
                "foregroundColor" -> L.text?.color = Values.color(ch.attr("value"), ch.attr("opacity"))
                "compositingFilter" -> L.base.compositing = ch.attr("filter") ?: ch.attr("name")
                "filters" -> parseFilters(ch, L, ctx)
                "colors" -> L.gradient?.colors = ch.children.map { childColor(it) }
                "locations" -> L.gradient?.locations = ch.children.map { Values.num(it.attr("value"), 0.0) }.toDoubleArray()
                "type" -> {
                    val v = ch.attr("value")?.lowercase()
                    L.gradient?.radial = v == "radial"
                    L.gradient?.conic = v == "conic"
                }
                "emitterCells" -> parseCells(ch, L, ctx)
                "emitterBehaviors" -> L.emitter?.behaviors = ch.children.size
                "instanceColor" -> L.replicator?.color = Values.color(ch.attr("value"), ch.attr("opacity"))
                "states", "stateTransitions", "scriptComponents", "remoteEffects", "style", "interpolations",
                "scriptObject", "modules" -> {}
                else -> ctx.diag.tag(ch.name)
            }
        }
        return L
    }

    /** `<backgroundColor type="_NSTaggedPointerColor"/>` and friends carry no value: that is a nil colour, i.e. transparent. */
    private fun childColor(ch: XNode): DoubleArray =
        if (ch.attr("value") == null) doubleArrayOf(0.0, 0.0, 0.0, 0.0) else Values.color(ch.attr("value"), ch.attr("opacity"))

    private fun DoubleArray.setFrom(v: DoubleArray) { for (i in 0 until minOf(size, v.size)) this[i] = v[i] }

    private fun applyAttrs(L: CamlLayer, el: XNode, ctx: Ctx) {
        val b = L.base
        var transformSeen = false
        var rotX: Double? = null
        var rotY: Double? = null
        var rotZ: Double? = null
        for ((k, v) in el.attrs) {
            when (k) {
                "bounds" -> {
                    val n = Values.nums(v)
                    if (n.size >= 4) { b.bx = n[0]; b.by = n[1]; b.bw = n[2]; b.bh = n[3] }
                    else if (n.size >= 2) { b.bw = n[0]; b.bh = n[1] }
                }
                "position" -> { val n = Values.nums(v); if (n.size >= 2) { b.px = n[0]; b.py = n[1] } }
                "anchorPoint" -> { val n = Values.nums(v); if (n.size >= 2) { b.ax = n[0]; b.ay = n[1] } }
                "zPosition" -> b.z = Values.num(v, 0.0)
                "opacity" -> b.opacity = Values.num(v, 1.0)
                "hidden" -> b.hidden = Values.bool(v)
                "cornerRadius" -> b.cornerRadius = Values.num(v, 0.0)
                "masksToBounds" -> b.masksToBounds = Values.bool(v)
                "geometryFlipped" -> b.geometryFlipped = Values.bool(v)
                "transform" -> {
                    transformSeen = true
                    System.arraycopy(T3.parse(v), 0, b.tr, 0, T3.SIZE)
                }
                "transform.rotation.x" -> rotX = Values.num(v, 0.0)
                "transform.rotation.y" -> rotY = Values.num(v, 0.0)
                "transform.rotation.z" -> rotZ = Values.num(v, 0.0)
                "sublayerTransform" -> System.arraycopy(T3.parse(v), 0, b.subTr, 0, T3.SIZE)
                "backgroundColor" -> b.bg.setFrom(Values.color(v))
                "borderColor" -> b.borderColor.setFrom(Values.color(v))
                "borderWidth" -> b.borderWidth = Values.num(v, 0.0)
                "contentsRect" -> { val n = Values.nums(v); if (n.size >= 4) b.crect.setFrom(n) }
                "contentsGravity" -> b.gravity = gravityOf(v)
                "contentsScale" -> b.contentsScale = Values.num(v, 1.0)
                "contentsMultiplyColor" -> { b.multiply.setFrom(Values.color(v)); b.hasMultiply = true }
                "magnificationFilter", "minificationFilter" -> if (v == "nearest") b.nearest = true
                "beginTime" -> b.beginTime = Values.num(v, 0.0)
                "timeOffset" -> b.timeOffset = Values.num(v, 0.0)
                "speed" -> b.speed = Values.num(v, 1.0)
                "compositingFilter" -> b.compositing = v
                "enabled" -> b.enabled = Values.bool(v, true)
                "nuggetId" -> L.nuggetId = v
                else -> if (!applyKindAttr(L, k, v) && k !in SILENT_ATTRS) ctx.diag.attr(L.className, k)
            }
        }
        if (!transformSeen) {
            if (rotX != null) b.tr[T3.RX] = rotX
            if (rotY != null) b.tr[T3.RY] = rotY
            if (rotZ != null) b.tr[T3.RZ] = rotZ
        }
    }

    private fun gravityOf(v: String): Gravity = when (v) {
        "resizeAspect" -> Gravity.RESIZE_ASPECT
        "resizeAspectFill" -> Gravity.RESIZE_ASPECT_FILL
        "center" -> Gravity.CENTER
        "top" -> Gravity.TOP
        "bottom" -> Gravity.BOTTOM
        "left" -> Gravity.LEFT
        "right" -> Gravity.RIGHT
        "topLeft" -> Gravity.TOP_LEFT
        "topRight" -> Gravity.TOP_RIGHT
        "bottomLeft" -> Gravity.BOTTOM_LEFT
        "bottomRight" -> Gravity.BOTTOM_RIGHT
        else -> Gravity.RESIZE
    }

    /** Attributes that only make sense for a particular layer subclass. */
    private fun applyKindAttr(L: CamlLayer, k: String, v: String): Boolean {
        val b = L.base
        val g = L.gradient
        if (g != null) {
            when (k) {
                "startPoint" -> { val n = Values.nums(v); if (n.size >= 2) { g.startX = n[0]; g.startY = n[1] }; return true }
                "endPoint" -> { val n = Values.nums(v); if (n.size >= 2) { g.endX = n[0]; g.endY = n[1] }; return true }
                else -> {}
            }
        }
        val s = L.shape
        if (s != null) {
            when (k) {
                "path" -> { s.path = Values.path(v); b.path = s.path; return true }
                "fillColor" -> { b.fill.setFrom(Values.color(v)); b.hasFill = true; return true }
                "strokeColor" -> { b.stroke.setFrom(Values.color(v)); return true }
                "lineWidth" -> { b.lineWidth = Values.num(v, 1.0); return true }
                "lineCap" -> { s.lineCap = v; return true }
                "lineJoin" -> { s.lineJoin = v; return true }
                "fillRule", "miterLimit", "strokeStart", "strokeEnd", "lineDashPhase" -> return true
                else -> {}
            }
        }
        val e = L.emitter
        if (e != null) {
            when (k) {
                "emitterPosition" -> { val n = Values.nums(v); if (n.size >= 2) { e.posX = n[0]; e.posY = n[1] }; return true }
                "emitterSize" -> { val n = Values.nums(v); if (n.size >= 2) { e.sizeW = n[0]; e.sizeH = n[1] }; return true }
                "emitterShape" -> { e.shape = v; return true }
                "emitterMode" -> { e.mode = v; return true }
                "renderMode" -> { e.renderMode = v; return true }
                "seed" -> { e.seed = Values.num(v, 0.0).toLong(); return true }
                "birthRate" -> { e.birthRate = Values.num(v, 1.0); return true }
                "lifetime" -> { e.lifetime = Values.num(v, 1.0); return true }
                "velocity" -> { e.velocity = Values.num(v, 1.0); return true }
                "scale" -> { e.scale = Values.num(v, 1.0); return true }
                "spin" -> { e.spin = Values.num(v, 1.0); return true }
                "emitterZPosition", "emitterDepth", "spinBias", "velocityBias" -> return true
                else -> {}
            }
        }
        val r = L.replicator
        if (r != null) {
            when (k) {
                "instanceCount" -> { r.count = Values.num(v, 1.0).toInt().coerceIn(0, 512); return true }
                "instanceDelay" -> { r.delay = Values.num(v, 0.0); return true }
                "instanceTransform" -> { r.transform = T3.parse(v); return true }
                else -> {}
            }
        }
        val t = L.text
        if (t != null) {
            when (k) {
                "string" -> { t.string = v; return true }
                "fontSize" -> { t.fontSize = Values.num(v, 36.0); return true }
                "font" -> { t.fontName = v; return true }
                "foregroundColor" -> { t.color = Values.color(v); return true }
                "alignmentMode" -> { t.alignment = v; return true }
                "wrapped" -> { t.wrapped = Values.bool(v); return true }
                else -> {}
            }
        }
        return false
    }

    private fun parseFilters(el: XNode, L: CamlLayer, ctx: Ctx) {
        for (f in el.children) {
            if (f.name != "CAFilter") { ctx.diag.tag("filters/${f.name}"); continue }
            val type = f.attr("filter") ?: continue
            val d = FilterDef(type, f.attr("name") ?: type)
            for ((k, v) in f.attrs) {
                when {
                    k == "enabled" -> d.enabled = Values.bool(v, true)
                    k.startsWith("input") -> d.inputs[k] = Values.nums(v)
                }
            }
            L.filters.add(d)
        }
    }

    private fun parseCells(el: XNode, L: CamlLayer, ctx: Ctx) {
        val e = L.emitter ?: return
        for (c in el.children) {
            if (c.name != "CAEmitterCell") { ctx.diag.tag("emitterCells/${c.name}"); continue }
            val src = decodeSrc(extractSrc(c.child("contents")))
            e.cells.add(EmitterCell(c.attr("name") ?: "", c.attrs, src))
        }
    }

    // ------------------------------------------------------------- animations

    private fun parseVal(el: XNode): Val? {
        val src = extractSrc(el)
        if (src != null) return Val.str(decodeSrc(src)!!)
        val t = el.attr("type") ?: el.name
        val value = el.attr("value")
        return when (t) {
            "string" -> Val.str(value ?: el.textTrim())
            "CATransform3D" -> Val.of(T3.parse(value))
            "CAColorMatrix" -> Val.of(Values.nums(value))
            "CGColor" -> Val.of(Values.color(value, el.attr("opacity")))
            "CGPath" -> Values.path(value)?.let { Val.path(it) }
            "CGImage", "CAMeshTransform", "NSArray", "NSDictionary", "CAFilter", "object", "null" -> null
            else -> {
                val n = Values.nums(value)
                if (n.isEmpty()) null else Val.of(n)
            }
        }
    }

    private fun parseAnim(el: XNode): Anim? {
        val type = el.attr("type") ?: "CABasicAnimation"
        val kp = el.attr("keyPath") ?: ""
        val rcAttr = el.attr("repeatCount")
        val repeatCount = when {
            rcAttr == null -> 1.0
            rcAttr.lowercase().startsWith("inf") -> Double.POSITIVE_INFINITY
            else -> Values.num(rcAttr, 1.0)
        }
        val fill = when (el.attr("fillMode")) { "forwards" -> 1; "backwards" -> 2; "both" -> 3; else -> 0 }
        val keyTimes = el.child("keyTimes")?.children?.map { Values.num(it.attr("value"), 0.0) }?.toDoubleArray()
        val values = el.child("values")?.children?.mapNotNull { parseVal(it) }
        val segTiming = el.child("timingFunctions")?.children?.mapNotNull {
            Values.timing(it.attr("value") ?: it.textTrim())
        }
        fun side(n: String): Val? = el.child(n)?.let { parseVal(it) }
        return Anim(
            type = type, keyPath = kp, key = el.attr("key"),
            duration = Values.num(el.attr("duration"), 0.0),
            beginTime = Values.num(el.attr("beginTime"), 0.0),
            speed = Values.num(el.attr("speed"), 1.0),
            timeOffset = Values.num(el.attr("timeOffset"), 0.0),
            repeatCount = repeatCount,
            repeatDuration = Values.num(el.attr("repeatDuration"), 0.0),
            autoreverses = Values.bool(el.attr("autoreverses")),
            fillMode = fill,
            additive = Values.bool(el.attr("additive")),
            calcMode = el.attr("calculationMode") ?: "linear",
            keyTimes = keyTimes, values = values,
            timing = Values.timing(el.attr("timingFunction")),
            segTiming = segTiming,
            from = side("fromValue"), to = side("toValue"), by = side("byValue"),
            damping = Values.num(el.attr("damping"), 10.0),
            mass = Values.num(el.attr("mass"), 1.0),
            stiffness = Values.num(el.attr("stiffness"), 100.0),
            velocity = Values.num(el.attr("velocity"), 0.0),
            autoRecalc = Values.bool(el.attr("mica_autorecalculatesDuration"))
        )
    }

    // ----------------------------------------------------------------- states

    private fun parseStates(el: XNode, out: MutableMap<String, StateDef>, ctx: Ctx) {
        for (s in el.childrenNamed("LKState")) {
            val name = s.attr("name") ?: continue
            val def = StateDef(name)
            val elements = s.child("elements")
            for (e in elements?.children.orEmpty()) {
                val tid = e.attr("targetId") ?: ""
                when (e.name) {
                    "LKStateSetValue" -> {
                        val kp = e.attr("keyPath") ?: continue
                        val v = e.child("value")?.let { parseVal(it) } ?: continue
                        def.sets.add(StateSetValue(tid, kp, v))
                    }
                    "LKStateAddAnimation" -> e.child("animation")?.let { a ->
                        parseAnim(a)?.let { def.addAnims.add(StateAddAnim(tid, it)); ctx.anims++ }
                    }
                    "LKStateRemoveAnimation" -> def.removeAnims.add(StateRemoveAnim(tid, e.attr("key") ?: ""))
                    "LKStateAddElement" -> e.child("object")?.let { o -> def.addElements.add(StateAddElement(tid, parseLayer(o, ctx))) }
                    "LKStateRemoveElement" -> def.removeElements.add(StateRemoveElement(tid, e.attr("objectId")))
                    else -> ctx.diag.tag("state/${e.name}")
                }
            }
            out[name] = def
        }
    }

    private fun parseTransitions(el: XNode, out: MutableList<TransitionDef>, ctx: Ctx) {
        for (t in el.childrenNamed("LKStateTransition")) {
            val elems = ArrayList<TransitionElem>()
            for (e in t.child("elements")?.children.orEmpty()) {
                if (e.name != "LKStateTransitionElement") { ctx.diag.tag("transition/${e.name}"); continue }
                val a = e.child("animation")?.let { parseAnim(it) }
                elems.add(TransitionElem(e.attr("targetId") ?: "", e.attr("key") ?: a?.keyPath ?: "", a))
            }
            out.add(TransitionDef(t.attr("fromState") ?: "*", t.attr("toState") ?: "*", elems))
        }
    }

    // ------------------------------------------------- Apple parameterisation

    private fun dictOf(n: XNode): Map<String, String> {
        val m = HashMap<String, String>()
        for (c in n.children) m[c.name] = c.attr("value") ?: c.textTrim()
        return m
    }

    private fun parseStyle(el: XNode, groups: MutableList<PropertyGroup>, par: MutableList<ParallaxGroup>) {
        for (g in el.childrenNamed("wallpaperPropertyGroups")) {
            for (d in g.childrenNamed("NSDictionary")) {
                val m = dictOf(d)
                val ln = m["layerName"] ?: continue
                val kp = m["keyPath"] ?: continue
                groups.add(PropertyGroup(ln, kp, m["title"],
                    m["v_home"]?.toDoubleOrNull(), m["v_lock"]?.toDoubleOrNull(), m["v_sleep"]?.toDoubleOrNull(), m["view"]))
            }
        }
        for (g in el.childrenNamed("wallpaperParallaxGroups")) {
            for (d in g.childrenNamed("NSDictionary")) {
                val m = dictOf(d)
                val ln = m["layerName"] ?: continue
                val kp = m["keyPath"] ?: continue
                par.add(ParallaxGroup(ln, kp, m["axis"] ?: "x",
                    m["mapMinTo"]?.toDoubleOrNull() ?: 0.0, m["mapMaxTo"]?.toDoubleOrNull() ?: 0.0, m["view"]))
            }
        }
    }
}
