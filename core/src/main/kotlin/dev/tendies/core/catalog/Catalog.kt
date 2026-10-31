package dev.tendies.core.catalog

import dev.tendies.core.util.Json

/** The four manifests that make up the upstream repository's catalog. */
enum class Category(val manifest: String, val label: String, val renderable: Boolean) {
    APPLE("wallpapers-apple.json", "Apple", true),
    CUSTOM("wallpapers-custom.json", "Community", true),
    TEMPLATE("wallpapers-template.json", "Templates", true),
    /** Passcode keypad skins: there is no Android equivalent, so they can only be previewed. */
    PASSTHEME("wallpapers-passthemes.json", "Passcode themes", false)
}

class CatalogEntry(
    val category: Category,
    val id: Int,
    val name: String,
    val description: String,
    /** Repository-relative path of the archive, e.g. `wallpapers/custom/Cipher.tendies`. */
    val path: String,
    /** Repository-relative path of the preview image/GIF. */
    val previewPath: String,
    val authors: String?,
    val contest: String?
) {
    val fileName: String get() = path.substringAfterLast('/')
    val extension: String get() = fileName.substringAfterLast('.', "").lowercase()
    /** Some manifest entries (oversized Apple files) point at a third-party server with an absolute URL instead of a repo path. */
    val isExternal: Boolean get() = path.startsWith("http://") || path.startsWith("https://")
}

object Catalog {
    const val REPO = "SerStars/Nugget-Wallpapers"
    const val BRANCH = "main"
    const val RAW_BASE = "https://raw.githubusercontent.com/$REPO/$BRANCH/"

    fun parse(category: Category, json: String): List<CatalogEntry> {
        val out = ArrayList<CatalogEntry>()
        for (o in Json.asList(Json.parse(json))) {
            val m = Json.asObject(o)
            val path = m["url"] as? String ?: continue
            out.add(CatalogEntry(
                category = category,
                id = (m["id"] as? Number)?.toInt() ?: -1,
                name = m["name"] as? String ?: path.substringAfterLast('/'),
                description = m["description"] as? String ?: "",
                path = path,
                previewPath = m["preview"] as? String ?: "",
                authors = (m["authors"] as? String)?.takeIf { it.isNotBlank() },
                contest = (m["contest"] as? String)?.takeIf { it.isNotBlank() }
            ))
        }
        return out
    }

    /**
     * Repo-relative paths get the raw-content base (each segment percent-encoded: file names contain spaces);
     * absolute http(s) URLs found in the manifests are used as they are.
     */
    fun rawUrl(path: String): String {
        if (path.startsWith("https://") || path.startsWith("http://")) return path
        return RAW_BASE + path.split('/').joinToString("/") { java.net.URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
    }
}
