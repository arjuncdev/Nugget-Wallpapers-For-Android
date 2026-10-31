package dev.tendies.core.render

import dev.tendies.core.anim.LogicalState
import dev.tendies.core.anim.StateController
import dev.tendies.core.anim.StateNames
import dev.tendies.core.caml.CamlDocument
import dev.tendies.core.caml.CamlLayer

/** Which PosterBoard layer package a document came from (z-order: background < floating < foreground). */
enum class LayerRole { BACKGROUND, FLOATING, FOREGROUND, SINGLE }

/** A parsed `.ca` document plus its runtime state machine. */
class DocInstance(val role: LayerRole, val doc: CamlDocument) {
    val states = StateController(doc)
    /** Per-context values for Apple "ParameterizedCA" wallpapers (v_home / v_lock / v_sleep). */
    val overrides = HashMap<CamlLayer, ArrayList<Pair<String, Double>>>()
}

/** How the scene should be fitted/animated for one frame. */
class RenderParams(
    val outW: Double,
    val outH: Double,
    val userScale: Double = 1.0,
    val offX: Double = 0.0,
    val offY: Double = 0.0,
    /** Device tilt in -1..1 (drives `wallpaperParallaxGroups`). */
    val tiltX: Double = 0.0,
    val tiltY: Double = 0.0,
    /** Draw only these roles (null = all). */
    val roles: Set<LayerRole>? = null
)

/** One wallpaper variant ready to render: its layer documents in z-order. */
class Scene(val docs: List<DocInstance>) {
    val width: Double = docs.firstOrNull()?.doc?.width ?: 0.0
    val height: Double = docs.firstOrNull()?.doc?.height ?: 0.0

    var logical: LogicalState = LogicalState.LOCK
        private set
    var dark = false
    var orientation = "PortraitUp"

    /** Maps an Android situation (keyguard / launcher / screen off) onto the wallpaper's states. */
    fun setLogical(ls: LogicalState, now: Double, animated: Boolean = true) {
        logical = ls
        for (d in docs) {
            d.states.setState(StateNames.resolve(d.doc, ls, orientation, dark), now, animated)
            rebuildOverrides(d)
        }
    }

    /** Debug/test hook: switch every document that knows [name] to that state. */
    fun setStateByName(name: String, now: Double, animated: Boolean = true) {
        for (d in docs) if (d.states.has(name)) d.states.setState(name, now, animated)
    }

    private fun roleMatches(view: String?, role: LayerRole): Boolean {
        if (view == null || view.isEmpty()) return true
        return when (view.lowercase()) {
            "background" -> role == LayerRole.BACKGROUND || role == LayerRole.SINGLE
            "floating" -> role == LayerRole.FLOATING || role == LayerRole.SINGLE
            "foreground" -> role == LayerRole.FOREGROUND || role == LayerRole.SINGLE
            else -> true
        }
    }

    private fun rebuildOverrides(d: DocInstance) {
        d.overrides.clear()
        for (g in d.doc.propertyGroups) {
            if (!roleMatches(g.view, d.role)) continue
            val v = when (logical) {
                LogicalState.HOME -> g.home
                LogicalState.LOCK -> g.lock
                LogicalState.SLEEP -> g.sleep
            } ?: g.lock ?: g.home ?: continue
            val layers = d.doc.byName[g.layerName] ?: continue
            for (l in layers) d.overrides.getOrPut(l) { ArrayList() }.add(g.keyPath to v)
        }
    }
}
