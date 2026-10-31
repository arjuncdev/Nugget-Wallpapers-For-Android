package dev.tendies.core.anim

import dev.tendies.core.caml.Anim
import dev.tendies.core.caml.CamlDocument
import dev.tendies.core.caml.CamlLayer
import dev.tendies.core.caml.LayerProps
import dev.tendies.core.caml.TransitionElem
import dev.tendies.core.caml.TransitionDef
import dev.tendies.core.caml.Val

/** The three situations a PosterBoard wallpaper reacts to. Android events are mapped onto these. */
enum class LogicalState { LOCK, HOME, SLEEP }

object StateNames {
    /**
     * Picks the state that exists in [doc] for a logical state. Newer wallpapers name states
     * "Lock PortraitUp Light" / "Home LandscapeLeft Dark" / "Sleep ..."; older ones use
     * "Locked" / "Unlock" / "Sleep".
     */
    fun resolve(doc: CamlDocument, logical: LogicalState, orientation: String = "PortraitUp", dark: Boolean = false): String? {
        val look = if (dark) "Dark" else "Light"
        val cands = when (logical) {
            LogicalState.LOCK -> listOf("Lock $orientation $look", "Lock PortraitUp $look", "Lock PortraitUp Light", "Locked")
            LogicalState.HOME -> listOf("Home $orientation $look", "Home PortraitUp $look", "Home PortraitUp Light", "Unlock")
            LogicalState.SLEEP -> listOf("Sleep $orientation $look", "Sleep PortraitUp $look", "Sleep PortraitUp Light", "Sleep")
        }
        return cands.firstOrNull { doc.states.containsKey(it) }
    }
}

/**
 * Applies `LKState` values to layers and animates between states using the document's
 * `LKStateTransition` descriptions (usually springs). Semantics, as observed in the real files:
 *  - a state lists model-value overrides (`LKStateSetValue`); properties it does not mention fall back to the
 *    layer's own value;
 *  - on a state change every affected (layer, keyPath) is animated from its *current presentation value* to the
 *    new target using the best matching transition element: (from,to) > (*,to) > (from,*) > (*,*);
 *  - animations added/removed by a state (`LKStateAddAnimation` / `LKStateRemoveAnimation`) are scoped to that state.
 */
class StateController(private val doc: CamlDocument) {
    var current: String? = null
        private set

    private class Track(val layer: CamlLayer, val kp: String, var from: Val, var to: Val, var t0: Double, var anim: Anim?)

    private val tracks = LinkedHashMap<String, Track>()
    private val byLayer = HashMap<CamlLayer, ArrayList<Track>>()

    class DynAnim(val anim: Anim, val epoch: Double)

    private val dyn = HashMap<CamlLayer, ArrayList<DynAnim>>()
    private val removedKeys = HashMap<CamlLayer, HashSet<String>>()

    fun has(name: String) = doc.states.containsKey(name)

    private fun baseValue(layer: CamlLayer, kp: String): Val? {
        if (kp == "contents") return layer.base.contents?.let { Val.str(it) }
        if (kp == "compositingFilter") return layer.base.compositing?.let { Val.str(it) }
        if (kp == "path") return layer.base.path?.let { Val.path(it) }
        return layer.base.get(kp)?.let { Val.of(it) }
    }

    private fun same(a: Val, b: Val): Boolean {
        if (a.path != null || b.path != null) return a.path === b.path
        if (a.s != null || b.s != null) return a.s == b.s
        val x = a.n ?: return false
        val y = b.n ?: return false
        if (x.size != y.size) return false
        for (i in x.indices) if (kotlin.math.abs(x[i] - y[i]) > 1e-9) return false
        return true
    }

    private fun findTransition(from: String?, to: String?, tid: String, kp: String): TransitionElem? {
        val f = from ?: "*"
        val t = to ?: "*"
        val order = listOf(f to t, "*" to t, f to "*", "*" to "*")
        for ((ff, tt) in order) {
            for (tr in doc.transitions) {
                if (tr.from != ff || tr.to != tt) continue
                for (e in tr.elems) if (e.targetId == tid && e.key == kp) return e
            }
        }
        return null
    }

    private fun findMatchingTransition(from: String?, to: String?): TransitionDef? {
        val f = from ?: "*"
        val t = to ?: "*"
        val order = listOf(f to t, "*" to t, f to "*", "*" to "*")
        for ((ff, tt) in order) {
            for (tr in doc.transitions) {
                if (tr.from == ff && tr.to == tt) return tr
            }
        }
        return null
    }

    private fun defaultTransitionAnim(kp: String): Anim = Anim(
        type = "CASpringAnimation",
        keyPath = kp,
        key = null,
        duration = 0.0,
        beginTime = 0.0,
        speed = 1.0,
        timeOffset = 0.0,
        repeatCount = 1.0,
        repeatDuration = 0.0,
        autoreverses = false,
        fillMode = 3,
        additive = false,
        calcMode = "linear",
        keyTimes = null,
        values = null,
        timing = null,
        segTiming = null,
        from = null,
        to = null,
        by = null,
        damping = 22.0,
        mass = 1.0,
        stiffness = 220.0,
        velocity = 0.0,
        autoRecalc = true
    )

    private fun value(tr: Track, now: Double): Val {
        val a = tr.anim ?: return tr.to
        return AnimEval.sample(a, now - tr.t0, tr.from, tr.to) ?: tr.to
    }

    /** Switches to [name] (null = no state). With [animated] false values jump immediately. */
    fun setState(name: String?, now: Double, animated: Boolean = true) {
        if (name == current) return
        if (name != null && !has(name)) return
        val prev = current
        val prevDef = prev?.let { doc.states[it] }
        val newDef = name?.let { doc.states[it] }

        val keys = LinkedHashSet<String>()
        prevDef?.sets?.forEach { keys.add(it.targetId + "|" + it.keyPath) }
        newDef?.sets?.forEach { keys.add(it.targetId + "|" + it.keyPath) }
        keys.addAll(tracks.keys)

        for (key in keys) {
            val sep = key.indexOf('|')
            val tid = key.substring(0, sep)
            val kp = key.substring(sep + 1)
            val layer = doc.byId[tid] ?: continue
            val baseV = baseValue(layer, kp) ?: continue
            val target = newDef?.sets?.firstOrNull { it.targetId == tid && it.keyPath == kp }?.value ?: baseV
            val existing = tracks[key]
            val cur = existing?.let { value(it, now) }
                ?: prevDef?.sets?.firstOrNull { it.targetId == tid && it.keyPath == kp }?.value
                ?: baseV
            if (same(cur, target)) {
                tracks[key] = Track(layer, kp, target, target, now, null)
                continue
            }
            val elem = if (animated) findTransition(prev, name, tid, kp) else null
            val anim = elem?.anim ?: if (animated && findMatchingTransition(prev, name) != null) defaultTransitionAnim(kp) else null
            tracks[key] = if (anim != null) Track(layer, kp, cur, target, now, anim)
            else Track(layer, kp, target, target, now, null)
        }

        dyn.clear()
        removedKeys.clear()
        newDef?.addAnims?.forEach { aa ->
            doc.byId[aa.targetId]?.let { l -> dyn.getOrPut(l) { ArrayList() }.add(DynAnim(aa.anim, now)) }
        }
        newDef?.removeAnims?.forEach { ra ->
            doc.byId[ra.targetId]?.let { l -> removedKeys.getOrPut(l) { HashSet() }.add(ra.key) }
        }
        current = name

        byLayer.clear()
        for (t in tracks.values) byLayer.getOrPut(t.layer) { ArrayList() }.add(t)
    }

    /** Writes state-driven values for [layer] into [pres]. */
    fun apply(layer: CamlLayer, pres: LayerProps, now: Double) {
        val list = byLayer[layer] ?: return
        for (tr in list) {
            val a = tr.anim
            if (a != null && now - tr.t0 > 0.0) {
                val end = tr.t0 + AnimEval.endTime(a)
                if (now >= end) { tr.from = tr.to; tr.anim = null }
            }
            pres.apply(tr.kp, value(tr, now), false)
        }
    }

    fun dynamicAnims(layer: CamlLayer): List<DynAnim>? = dyn[layer]
    fun isKeyRemoved(layer: CamlLayer, key: String?): Boolean = key != null && (removedKeys[layer]?.contains(key) == true)
    fun hasRemovals(layer: CamlLayer): Boolean = removedKeys.containsKey(layer)
}
