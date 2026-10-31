package dev.tendies.app.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream

/** One wallpaper stored on this device. */
data class LibraryRecord(
    val id: String,
    val name: String,
    val fileName: String,
    /** "tendies" or "batter" */
    val kind: String,
    /** repo-relative path for catalog downloads, "import" for user-imported files */
    val source: String,
    val category: String?,
    val author: String?,
    val description: String?,
    val previewUrl: String?
) {
    val isBatter: Boolean get() = kind == "batter"
}

/** Installed wallpapers: archives live in filesDir/wallpapers, metadata in library.json. */
class Library(context: Context) {
    val dir = File(context.filesDir, "wallpapers").also { it.mkdirs() }
    private val index = File(dir, "library.json")

    @Synchronized
    fun list(): List<LibraryRecord> {
        if (!index.exists()) return emptyList()
        return try {
            val arr = JSONArray(index.readText())
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                LibraryRecord(
                    id = o.getString("id"), name = o.getString("name"), fileName = o.getString("fileName"),
                    kind = o.optString("kind", "tendies"), source = o.optString("source", "import"),
                    category = o.optString("category").ifEmpty { null }, author = o.optString("author").ifEmpty { null },
                    description = o.optString("description").ifEmpty { null }, previewUrl = o.optString("previewUrl").ifEmpty { null }
                )
            }.filter { File(dir, it.fileName).exists() }
        } catch (e: Exception) {
            emptyList()
        }
    }

    @Synchronized
    private fun write(list: List<LibraryRecord>) {
        val arr = JSONArray()
        for (r in list) {
            arr.put(JSONObject().apply {
                put("id", r.id); put("name", r.name); put("fileName", r.fileName); put("kind", r.kind)
                put("source", r.source); put("category", r.category ?: ""); put("author", r.author ?: "")
                put("description", r.description ?: ""); put("previewUrl", r.previewUrl ?: "")
            })
        }
        index.writeText(arr.toString())
    }

    fun find(id: String): LibraryRecord? = list().firstOrNull { it.id == id }
    fun fileOf(r: LibraryRecord) = File(dir, r.fileName)

    @Synchronized
    fun add(r: LibraryRecord) {
        write(list().filter { it.id != r.id } + r)
    }

    @Synchronized
    fun remove(id: String) {
        val all = list()
        all.firstOrNull { it.id == id }?.let { File(dir, it.fileName).delete() }
        write(all.filter { it.id != id })
    }

    /** Picks a file name that does not collide with an existing archive. */
    fun uniqueFile(desired: String): File {
        val clean = desired.replace(Regex("[^A-Za-z0-9._ -]"), "_")
        var f = File(dir, clean)
        var n = 1
        while (f.exists()) {
            val base = clean.substringBeforeLast('.', clean)
            val ext = clean.substringAfterLast('.', "")
            f = File(dir, if (ext.isEmpty()) "$base-$n" else "$base-$n.$ext")
            n++
        }
        return f
    }

    /** Copies a user-selected file (Storage Access Framework) into the library. */
    fun importStream(input: InputStream, displayName: String): LibraryRecord {
        val lower = displayName.lowercase()
        val kind = if (lower.endsWith(".batter")) "batter" else "tendies"
        val ext = if (kind == "batter") ".batter" else ".tendies"
        val base = displayName.substringBeforeLast('.', displayName)
        val file = uniqueFile(base + ext)
        file.outputStream().use { out -> input.copyTo(out) }
        val rec = LibraryRecord(
            id = "import-" + file.name, name = base, fileName = file.name, kind = kind, source = "import",
            category = null, author = null, description = null, previewUrl = null
        )
        add(rec)
        return rec
    }
}
