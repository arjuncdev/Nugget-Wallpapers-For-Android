package dev.tendies.core.pkg

import dev.tendies.core.fs.OverlayVfs
import dev.tendies.core.fs.PrefixVfs
import dev.tendies.core.fs.Vfs
import dev.tendies.core.fs.ZipVfs
import dev.tendies.core.util.Json
import java.io.File

/** One user-facing option of a `.batter` template (types seen in the real files: picker, remove, replace, set). */
class BatterOption(val index: Int, val raw: Map<String, Any?>) {
    val type: String = raw["type"] as? String ?: ""
    val label: String = raw["label"] as? String ?: type
    val files: List<String> = Json.asList(raw["files"]).filterIsInstance<String>()
    val required: Boolean = raw["required"] as? Boolean ?: false
    val defaultValue: Any? = raw["default_value"]
    val inverted: Boolean = raw["inverted"] as? Boolean ?: false
    val setterType: String? = raw["setter_type"] as? String
    val key: String? = raw["key"] as? String
    val identifier: String? = raw["identifier"] as? String
    val min: Double? = (raw["min_value"] as? Number)?.toDouble()
    val max: Double? = (raw["max_value"] as? Number)?.toDouble()
    val step: Double? = (raw["step"] as? Number)?.toDouble()
    val valueType: String? = raw["value_type"] as? String
    val toggleOn: Double? = (raw["toggle_on_value"] as? Number)?.toDouble()
    val toggleOff: Double? = (raw["toggle_off_value"] as? Number)?.toDouble()
    val allowMultiple: Boolean = raw["allow_multiple_selection"] as? Boolean ?: false
    val allowedFiles: String? = raw["allowed_files"] as? String
    /** For pickers: the selectable presets and the directories each one owns. */
    val choices: List<Pair<String, List<String>>> = Json.asList(raw["options"]).map {
        val m = Json.asObject(it)
        (m["label"] as? String ?: "") to Json.asList(m["files"]).filterIsInstance<String>()
    }
}

/** User choices: picker -> selected indices, remove -> Boolean, replace -> image bytes, set -> Double. */
class BatterChoices {
    val picks = HashMap<Int, List<Int>>()
    val toggles = HashMap<Int, Boolean>()
    val images = HashMap<Int, ByteArray>()
    val numbers = HashMap<Int, Double>()
}

/**
 * `.batter` = a "Pocket Poster / Nugget" template: a normal PosterBoard descriptor tree plus a `config.json`
 * that lists user options. Semantics were derived from the three real templates in the repo:
 *  - picker : keep only the chosen descriptor directories;
 *  - remove : delete layer files (the `inverted` flag means the toggle *keeps* them when on);
 *  - replace: swap a placeholder image for a user-supplied one;
 *  - set    : patch attribute `key` on CAML elements carrying `nuggetId="<identifier>"`.
 */
class BatterTemplate(private val base: Vfs) : AutoCloseable {
    val title: String
    val author: String
    val description: String
    val options: List<BatterOption>
    val warnings = ArrayList<String>()

    init {
        val cfg = Json.asObject(Json.parse(String(base.read("config.json"), Charsets.UTF_8)))
        title = cfg["title"] as? String ?: "Template"
        author = cfg["author"] as? String ?: ""
        description = cfg["description"] as? String ?: ""
        options = Json.asList(cfg["options"]).mapIndexed { i, o -> BatterOption(i, Json.asObject(o)) }
    }

    /** Default choices exactly as the template author configured them. */
    fun defaults(): BatterChoices {
        val c = BatterChoices()
        for (o in options) when (o.type) {
            "picker" -> c.picks[o.index] = if (o.choices.isNotEmpty()) listOf(0) else emptyList()
            "remove" -> c.toggles[o.index] = (o.defaultValue as? Boolean) ?: false
            "set" -> (o.defaultValue as? Number)?.let { c.numbers[o.index] = it.toDouble() }
        }
        return c
    }

    fun apply(choices: BatterChoices = defaults()): TendiesArchive {
        val ov = OverlayVfs(base)
        warnings.clear()
        for (o in options) {
            if (o.type != "picker") continue
            val picked = choices.picks[o.index] ?: listOf(0)
            o.choices.forEachIndexed { i, (_, files) -> if (i !in picked) files.forEach { ov.removeTree(it) } }
        }
        for (o in options) when (o.type) {
            "remove" -> {
                val v = choices.toggles[o.index] ?: ((o.defaultValue as? Boolean) ?: false)
                val doRemove = if (o.inverted) !v else v
                if (doRemove) o.files.forEach { if (ov.exists(it)) ov.remove(it) }
            }
            "replace" -> choices.images[o.index]?.let { bytes -> o.files.forEach { if (ov.exists(it)) ov.put(it, bytes) } }
            "set" -> {
                val key = o.key
                val id = o.identifier
                if (key == null || id == null) continue
                val v = choices.numbers[o.index] ?: (o.defaultValue as? Number)?.toDouble() ?: continue
                val text = formatValue(o, v)
                for (f in o.files) {
                    if (!ov.exists(f)) continue
                    val src = String(ov.read(f), Charsets.UTF_8)
                    val patched = patchAttr(src, id, key, text)
                    if (patched == null) warnings.add("nuggetId '$id' not found in $f") else ov.put(f, patched.toByteArray(Charsets.UTF_8))
                }
            }
        }
        return TendiesArchive(ov)
    }

    private fun formatValue(o: BatterOption, v: Double): String = when {
        o.setterType == "toggle" -> {
            val on = o.toggleOn ?: 1.0; val off = o.toggleOff ?: 0.0
            val x = if (v != 0.0) on else off
            if (x == Math.floor(x)) x.toLong().toString() else x.toString()
        }
        o.valueType == "float" -> v.toString()
        v == Math.floor(v) -> v.toLong().toString()
        else -> v.toString()
    }

    /** Sets `key="value"` on every element whose start tag contains `nuggetId="id"`; null if none matched. */
    private fun patchAttr(src: String, id: String, key: String, value: String): String? {
        val tagRe = Regex("<[A-Za-z_][^<>]*\\snuggetId=\"" + Regex.escape(id) + "\"[^<>]*>")
        var hit = false
        val out = tagRe.replace(src) { m ->
            hit = true
            val tag = m.value
            val attrRe = Regex("(\\s" + Regex.escape(key) + "=\")[^\"]*(\")")
            if (attrRe.containsMatchIn(tag)) attrRe.replace(tag) { a -> a.groupValues[1] + value + a.groupValues[2] }
            else Regex("\\s*(/?>)$").replace(tag) { e -> " $key=\"$value\"" + e.groupValues[1] }
        }
        return if (hit) out else null
    }

    override fun close() = base.close()

    companion object {
        /** Opens a `.batter` zip (templates keep everything under one "<Title> Template/" folder). */
        fun open(file: File): BatterTemplate {
            val z = ZipVfs(file)
            val cfg = z.paths.firstOrNull { it == "config.json" || it.endsWith("/config.json") }
                ?: throw IllegalArgumentException("not a .batter archive (no config.json)")
            val prefix = cfg.removeSuffix("config.json")
            return BatterTemplate(if (prefix.isEmpty()) z else PrefixVfs(z, prefix))
        }
    }
}
