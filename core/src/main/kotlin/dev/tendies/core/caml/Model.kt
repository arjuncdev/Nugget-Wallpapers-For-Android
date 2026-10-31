package dev.tendies.core.caml

import java.util.TreeMap

enum class Kind { LAYER, TRANSFORM, GRADIENT, SHAPE, EMITTER, REPLICATOR, TEXT, BACKDROP, OTHER }

enum class Gravity { RESIZE, RESIZE_ASPECT, RESIZE_ASPECT_FILL, CENTER, TOP, BOTTOM, LEFT, RIGHT, TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }

/** Animatable value: numbers (scalar, point, colour, decomposed transform, 4x5 colour matrix) or a string (image path). */
class Val(val n: DoubleArray?, val s: String?, val path: PathData? = null) {
    companion object {
        fun num(v: Double) = Val(doubleArrayOf(v), null)
        fun of(a: DoubleArray) = Val(a, null)
        fun str(s: String) = Val(null, s)
        fun path(p: PathData) = Val(null, null, p)
    }
}

/**
 * All properties that CAML can set, animate or override through states. Held as plain fields (fast, no
 * hashing in the hot path) with a key-path based accessor for the animation/state machinery.
 */
class LayerProps {
    var px = 0.0; var py = 0.0
    var bx = 0.0; var by = 0.0; var bw = 0.0; var bh = 0.0
    var ax = 0.5; var ay = 0.5
    var z = 0.0
    var opacity = 1.0
    var hidden = false
    var cornerRadius = 0.0
    var masksToBounds = false
    var geometryFlipped = false
    val bg = doubleArrayOf(0.0, 0.0, 0.0, 0.0)
    var borderWidth = 0.0
    val borderColor = doubleArrayOf(0.0, 0.0, 0.0, 0.0)
    val fill = doubleArrayOf(0.0, 0.0, 0.0, 1.0)      // CAShapeLayer.fillColor (default opaque black)
    var hasFill = true
    val stroke = doubleArrayOf(0.0, 0.0, 0.0, 0.0)    // CAShapeLayer.strokeColor
    var lineWidth = 1.0
    val crect = doubleArrayOf(0.0, 0.0, 1.0, 1.0)
    var contents: String? = null
    var contentsScale = 1.0
    var gravity = Gravity.RESIZE
    var nearest = false
    var hasMultiply = false
    val multiply = doubleArrayOf(1.0, 1.0, 1.0, 1.0)
    val tr = T3.identity()
    val subTr = T3.identity()
    var timeOffset = 0.0
    var speed = 1.0
    var beginTime = 0.0
    var compositing: String? = null
    var enabled = true
    /** Current CGPath of a shape layer (animatable; null = use the layer's static path). */
    var path: PathData? = null
    /** Numeric properties with no dedicated field: `filters.<name>.<input>`, `emitterCells.<cell>.<param>`, ... */
    val extras = HashMap<String, DoubleArray>()

    fun copyFrom(o: LayerProps) {
        px = o.px; py = o.py; bx = o.bx; by = o.by; bw = o.bw; bh = o.bh; ax = o.ax; ay = o.ay
        z = o.z; opacity = o.opacity; hidden = o.hidden; cornerRadius = o.cornerRadius
        masksToBounds = o.masksToBounds; geometryFlipped = o.geometryFlipped
        System.arraycopy(o.bg, 0, bg, 0, 4)
        borderWidth = o.borderWidth
        System.arraycopy(o.borderColor, 0, borderColor, 0, 4)
        System.arraycopy(o.fill, 0, fill, 0, 4); hasFill = o.hasFill
        System.arraycopy(o.stroke, 0, stroke, 0, 4); lineWidth = o.lineWidth
        System.arraycopy(o.crect, 0, crect, 0, 4)
        contents = o.contents; contentsScale = o.contentsScale; gravity = o.gravity; nearest = o.nearest
        hasMultiply = o.hasMultiply
        System.arraycopy(o.multiply, 0, multiply, 0, 4)
        System.arraycopy(o.tr, 0, tr, 0, T3.SIZE)
        System.arraycopy(o.subTr, 0, subTr, 0, T3.SIZE)
        timeOffset = o.timeOffset; speed = o.speed; beginTime = o.beginTime
        compositing = o.compositing; enabled = o.enabled; path = o.path
        if (extras.isNotEmpty() || o.extras.isNotEmpty()) {
            extras.clear(); extras.putAll(o.extras)
        }
    }

    private fun f(v: DoubleArray, i: Int = 0, d: Double = 0.0) = if (i < v.size) v[i] else d

    /** Current value for a key path, or null if the path is not a numeric property we know. */
    fun get(kp: String): DoubleArray? = when (kp) {
        "position" -> doubleArrayOf(px, py)
        "position.x" -> doubleArrayOf(px)
        "position.y" -> doubleArrayOf(py)
        "bounds" -> doubleArrayOf(bx, by, bw, bh)
        "bounds.size" -> doubleArrayOf(bw, bh)
        "bounds.size.width" -> doubleArrayOf(bw)
        "bounds.size.height" -> doubleArrayOf(bh)
        "bounds.origin.x" -> doubleArrayOf(bx)
        "bounds.origin.y" -> doubleArrayOf(by)
        "anchorPoint" -> doubleArrayOf(ax, ay)
        "anchorPoint.x" -> doubleArrayOf(ax)
        "anchorPoint.y" -> doubleArrayOf(ay)
        "zPosition" -> doubleArrayOf(z)
        "opacity" -> doubleArrayOf(opacity)
        "hidden" -> doubleArrayOf(if (hidden) 1.0 else 0.0)
        "cornerRadius" -> doubleArrayOf(cornerRadius)
        "masksToBounds" -> doubleArrayOf(if (masksToBounds) 1.0 else 0.0)
        "backgroundColor" -> bg.copyOf()
        "borderColor" -> borderColor.copyOf()
        "borderWidth" -> doubleArrayOf(borderWidth)
        "fillColor" -> fill.copyOf()
        "strokeColor" -> stroke.copyOf()
        "lineWidth" -> doubleArrayOf(lineWidth)
        "contentsMultiplyColor" -> multiply.copyOf()
        "contentsRect" -> crect.copyOf()
        "contentsRect.origin.x" -> doubleArrayOf(crect[0])
        "contentsRect.origin.y" -> doubleArrayOf(crect[1])
        "contentsRect.size.width" -> doubleArrayOf(crect[2])
        "contentsRect.size.height" -> doubleArrayOf(crect[3])
        "timeOffset" -> doubleArrayOf(timeOffset)
        "speed" -> doubleArrayOf(speed)
        "transform" -> tr.copyOf()
        "sublayerTransform" -> subTr.copyOf()
        else -> trGet(kp, "transform.", tr) ?: trGet(kp, "sublayerTransform.", subTr) ?: extras[kp]?.copyOf()
    }

    private fun trGet(kp: String, prefix: String, t: DoubleArray): DoubleArray? {
        if (!kp.startsWith(prefix)) return null
        return when (kp.substring(prefix.length)) {
            "translation.x" -> doubleArrayOf(t[T3.TX])
            "translation.y" -> doubleArrayOf(t[T3.TY])
            "translation.z" -> doubleArrayOf(t[T3.TZ])
            "translation" -> doubleArrayOf(t[T3.TX], t[T3.TY])
            "rotation.x" -> doubleArrayOf(t[T3.RX])
            "rotation.y" -> doubleArrayOf(t[T3.RY])
            "rotation.z", "rotation" -> doubleArrayOf(t[T3.RZ])
            "scale", "scale.x", "scale.xy" -> doubleArrayOf(t[T3.SX])
            "scale.y" -> doubleArrayOf(t[T3.SY])
            "scale.z" -> doubleArrayOf(t[T3.SZ])
            "shear.x", "shear.xy", "shear" -> doubleArrayOf(t[T3.SHEAR])
            else -> null
        }
    }

    private fun trSet(kp: String, prefix: String, t: DoubleArray, v: DoubleArray): Boolean {
        if (!kp.startsWith(prefix)) return false
        val x = f(v)
        when (kp.substring(prefix.length)) {
            "translation.x" -> t[T3.TX] = x
            "translation.y" -> t[T3.TY] = x
            "translation.z" -> t[T3.TZ] = x
            "translation" -> { t[T3.TX] = x; t[T3.TY] = f(v, 1, t[T3.TY]) }
            "rotation.x" -> t[T3.RX] = x
            "rotation.y" -> t[T3.RY] = x
            "rotation.z", "rotation" -> t[T3.RZ] = x
            "scale" -> { t[T3.SX] = x; t[T3.SY] = x; t[T3.SZ] = x }
            "scale.x" -> t[T3.SX] = x
            "scale.y" -> t[T3.SY] = x
            "scale.z" -> t[T3.SZ] = x
            "scale.xy" -> { t[T3.SX] = x; t[T3.SY] = x }
            "shear.x", "shear.xy", "shear" -> t[T3.SHEAR] = x
            else -> return false
        }
        return true
    }

    /** Sets a numeric property. Returns false if the key path is not understood. */
    fun set(kp: String, v: DoubleArray): Boolean {
        val x = f(v)
        when (kp) {
            "position" -> { px = x; py = f(v, 1, py) }
            "position.x" -> px = x
            "position.y" -> py = x
            "bounds" -> { bx = x; by = f(v, 1, by); bw = f(v, 2, bw); bh = f(v, 3, bh) }
            "bounds.size" -> { bw = x; bh = f(v, 1, bh) }
            "bounds.size.width" -> bw = x
            "bounds.size.height" -> bh = x
            "bounds.origin.x" -> bx = x
            "bounds.origin.y" -> by = x
            "anchorPoint" -> { ax = x; ay = f(v, 1, ay) }
            "anchorPoint.x" -> ax = x
            "anchorPoint.y" -> ay = x
            "zPosition" -> z = x
            "opacity" -> opacity = x
            "hidden" -> hidden = x != 0.0
            "cornerRadius" -> cornerRadius = x
            "masksToBounds" -> masksToBounds = x != 0.0
            "backgroundColor" -> copyColor(bg, v)
            "borderColor" -> copyColor(borderColor, v)
            "borderWidth" -> borderWidth = x
            "fillColor" -> { copyColor(fill, v); hasFill = true }
            "strokeColor" -> copyColor(stroke, v)
            "lineWidth" -> lineWidth = x
            "contentsMultiplyColor" -> { copyColor(multiply, v); hasMultiply = true }
            "contentsRect" -> { crect[0] = x; crect[1] = f(v, 1, crect[1]); crect[2] = f(v, 2, crect[2]); crect[3] = f(v, 3, crect[3]) }
            "contentsRect.origin.x" -> crect[0] = x
            "contentsRect.origin.y" -> crect[1] = x
            "contentsRect.size.width" -> crect[2] = x
            "contentsRect.size.height" -> crect[3] = x
            "timeOffset" -> timeOffset = x
            "speed" -> speed = x
            "transform" -> if (v.size >= T3.SIZE) System.arraycopy(v, 0, tr, 0, T3.SIZE) else return false
            "sublayerTransform" -> if (v.size >= T3.SIZE) System.arraycopy(v, 0, subTr, 0, T3.SIZE) else return false
            else -> {
                if (trSet(kp, "transform.", tr, v) || trSet(kp, "sublayerTransform.", subTr, v)) return true
                if (kp.startsWith("filters.") || kp.startsWith("emitterCells.") || kp.startsWith("shadow")) {
                    extras[kp] = v.copyOf(); return true
                }
                return false
            }
        }
        return true
    }

    fun setStr(kp: String, s: String): Boolean {
        when (kp) {
            "contents" -> contents = s
            "compositingFilter" -> compositing = s
            else -> return false
        }
        return true
    }

    /** Applies an animation sample. Additive numeric samples are added to the current value. */
    fun apply(kp: String, v: Val, additive: Boolean): Boolean {
        val pth = v.path
        if (pth != null) { if (kp == "path") { path = pth; return true }; return false }
        val s = v.s
        if (s != null) return setStr(kp, s)
        val n = v.n ?: return false
        if (additive) {
            val cur = get(kp)
            if (cur != null && cur.size == n.size) {
                val sum = DoubleArray(n.size) { cur[it] + n[it] }
                return set(kp, sum)
            }
        }
        return set(kp, n)
    }

    private fun copyColor(dst: DoubleArray, v: DoubleArray) {
        dst[0] = f(v, 0); dst[1] = f(v, 1); dst[2] = f(v, 2); dst[3] = f(v, 3, 1.0)
    }
}

class FilterDef(val type: String, val name: String) {
    val inputs = HashMap<String, DoubleArray>()
    var enabled = true
}

class GradientDef {
    var colors: List<DoubleArray> = emptyList()
    var locations: DoubleArray? = null
    var startX = 0.5; var startY = 0.0
    var endX = 0.5; var endY = 1.0
    var radial = false
    var conic = false
}

class ShapeDef {
    var path: PathData? = null
    var lineCap = "butt"
    var lineJoin = "miter"
}

class EmitterCell(val name: String, val attrs: Map<String, String>, val contents: String?)

class EmitterDef {
    var posX = 0.0; var posY = 0.0
    var sizeW = 0.0; var sizeH = 0.0
    var shape = "point"
    var mode = "volume"
    var renderMode = "unordered"
    var seed = 0L
    var birthRate = 1.0
    var lifetime = 1.0
    var velocity = 1.0
    var scale = 1.0
    var spin = 1.0
    val cells = ArrayList<EmitterCell>()
    var behaviors = 0
}

class ReplicatorDef {
    var count = 1
    var delay = 0.0
    var transform = T3.identity()
    var color = doubleArrayOf(1.0, 1.0, 1.0, 1.0)
}

class TextDef {
    var string = ""
    var fontSize = 36.0
    var fontName: String? = null
    var color = doubleArrayOf(1.0, 1.0, 1.0, 1.0)
    var alignment = "left"
    var wrapped = false
}

/** One CAAnimation (CABasicAnimation / CAKeyframeAnimation / CASpringAnimation). */
class Anim(
    val type: String,
    val keyPath: String,
    val key: String?,
    val duration: Double,
    val beginTime: Double,
    val speed: Double,
    val timeOffset: Double,
    val repeatCount: Double,
    val repeatDuration: Double,
    val autoreverses: Boolean,
    val fillMode: Int,               // bit0 = forwards, bit1 = backwards
    val additive: Boolean,
    val calcMode: String,
    val keyTimes: DoubleArray?,
    val values: List<Val>?,
    val timing: TimingFn?,
    val segTiming: List<TimingFn>?,
    val from: Val?,
    val to: Val?,
    val by: Val?,
    val damping: Double,
    val mass: Double,
    val stiffness: Double,
    val velocity: Double,
    val autoRecalc: Boolean
) {
    val isSpring get() = type == "CASpringAnimation"
    val isKeyframe get() = type == "CAKeyframeAnimation"
}

class StateSetValue(val targetId: String, val keyPath: String, val value: Val)
class StateAddAnim(val targetId: String, val anim: Anim)
class StateRemoveAnim(val targetId: String, val key: String)
class StateAddElement(val targetId: String, val layer: CamlLayer)
class StateRemoveElement(val targetId: String, val objectId: String?)

class StateDef(val name: String) {
    val sets = ArrayList<StateSetValue>()
    val addAnims = ArrayList<StateAddAnim>()
    val removeAnims = ArrayList<StateRemoveAnim>()
    val addElements = ArrayList<StateAddElement>()
    val removeElements = ArrayList<StateRemoveElement>()
}

class TransitionElem(val targetId: String, val key: String, val anim: Anim?)
class TransitionDef(val from: String, val to: String, val elems: List<TransitionElem>)

/** Apple "ParameterizedCA" per-context values (`v_home` / `v_lock` / `v_sleep`). */
class PropertyGroup(
    val layerName: String, val keyPath: String, val title: String?,
    val home: Double?, val lock: Double?, val sleep: Double?, val view: String?
)

/** Tilt-to-property mapping used for gyro parallax (`wallpaperParallaxGroups`). */
class ParallaxGroup(
    val layerName: String, val keyPath: String, val axis: String,
    val mapMinTo: Double, val mapMaxTo: Double, val view: String?
)

class CamlLayer(val kind: Kind, val className: String) {
    var id: String? = null
    var name: String = ""
    var nuggetId: String? = null
    val base = LayerProps()
    val sublayers = ArrayList<CamlLayer>()
    var mask: CamlLayer? = null
    val animations = ArrayList<Anim>()
    val filters = ArrayList<FilterDef>()
    var gradient: GradientDef? = null
    var shape: ShapeDef? = null
    var emitter: EmitterDef? = null
    var replicator: ReplicatorDef? = null
    var text: TextDef? = null
    var hasZ = false
    /** Sublayers ordered by zPosition (computed lazily when [hasZ]). */
    var sortedSubs: List<CamlLayer>? = null
    /** Scratch presentation state, rewritten every frame by the renderer. */
    val pres = LayerProps()
}

class Diagnostics {
    val unhandledTags = TreeMap<String, Int>()
    val unhandledAttrs = TreeMap<String, Int>()
    val warnings = ArrayList<String>()
    fun tag(t: String) { unhandledTags[t] = (unhandledTags[t] ?: 0) + 1 }
    fun attr(kind: String, a: String) { val k = "$kind.$a"; unhandledAttrs[k] = (unhandledAttrs[k] ?: 0) + 1 }
    fun warn(w: String) { if (warnings.size < 50) warnings.add(w) }
}

class CamlDocument(
    val dir: String,
    val width: Double,
    val height: Double,
    val root: CamlLayer,
    val byId: Map<String, CamlLayer>,
    val byName: Map<String, List<CamlLayer>>,
    val states: Map<String, StateDef>,
    val stateOrder: List<String>,
    val transitions: List<TransitionDef>,
    val propertyGroups: List<PropertyGroup>,
    val parallax: List<ParallaxGroup>,
    val layerCount: Int,
    val animationCount: Int
)
