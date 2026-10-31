package dev.tendies.app.data

import android.content.Context
import dev.tendies.core.catalog.Catalog
import dev.tendies.core.catalog.CatalogEntry
import dev.tendies.core.catalog.Category
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Reads the upstream repository's JSON manifests and downloads archives from raw.githubusercontent.com. */
class CatalogRepository(private val context: Context, private val library: Library) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private fun get(url: String) = http.newCall(Request.Builder().url(url).header("User-Agent", "NuggetWallpapers-Android").build()).execute()

    /** Network first, falling back to the last cached copy when offline. */
    suspend fun entries(cat: Category): List<CatalogEntry> = withContext(Dispatchers.IO) {
        val cache = File(context.cacheDir, "manifest-" + cat.manifest)
        val text: String = try {
            get(Catalog.RAW_BASE + cat.manifest).use { r ->
                if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
                r.body!!.string().also { cache.writeText(it) }
            }
        } catch (e: IOException) {
            if (cache.exists()) cache.readText() else throw e
        }
        Catalog.parse(cat, text)
    }

    suspend fun download(entry: CatalogEntry, onProgress: (Float) -> Unit): LibraryRecord = withContext(Dispatchers.IO) {
        val tmp = File(context.cacheDir, "dl-" + System.nanoTime() + ".part")
        try {
            get(Catalog.rawUrl(entry.path)).use { r ->
                if (!r.isSuccessful) throw IOException("HTTP ${r.code} for ${entry.fileName} (some large files are not stored in the repository)")
                val body = r.body!!
                val total = body.contentLength()
                var done = 0L
                body.byteStream().use { input ->
                    tmp.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            if (total > 0) onProgress(done.toFloat() / total)
                        }
                    }
                }
            }
            val ext = if (entry.extension == "batter") "batter" else "tendies"
            val target = library.uniqueFile(entry.fileName.substringBeforeLast('.') + "." + ext)
            if (!tmp.renameTo(target)) { tmp.copyTo(target, overwrite = true); tmp.delete() }
            val rec = LibraryRecord(
                id = entry.category.name.lowercase() + "-" + entry.path, name = entry.name, fileName = target.name,
                kind = ext, source = entry.path, category = entry.category.label, author = entry.authors,
                description = entry.description, previewUrl = Catalog.rawUrl(entry.previewPath)
            )
            library.add(rec)
            rec
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }
}
