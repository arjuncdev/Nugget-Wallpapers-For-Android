package dev.tendies.core.fs

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/** Minimal read-only virtual file system over an archive. Paths use '/' and never start with '/'. */
interface Vfs : AutoCloseable {
    /** All file paths (directories excluded, macOS junk excluded). */
    val paths: List<String>
    fun exists(path: String): Boolean
    fun read(path: String): ByteArray
    fun readOrNull(path: String): ByteArray? = if (exists(path)) read(path) else null
    override fun close() {}
}

object VfsUtil {
    fun isJunk(path: String): Boolean =
        path.startsWith("__MACOSX/") || path.contains("/__MACOSX/") ||
            path.endsWith(".DS_Store") || path.substringAfterLast('/').startsWith("._")

    /** Decodes %XX sequences (CAML `src` attributes are percent-encoded); '+' is left untouched. */
    fun percentDecode(s: String): String {
        if (s.indexOf('%') < 0) return s
        val out = java.io.ByteArrayOutputStream()
        val b = s.toByteArray(Charsets.UTF_8)
        var i = 0
        while (i < b.size) {
            val c = b[i].toInt()
            if (c == '%'.code && i + 2 < b.size) {
                val h = Character.digit(b[i + 1].toInt().toChar(), 16)
                val l = Character.digit(b[i + 2].toInt().toChar(), 16)
                if (h >= 0 && l >= 0) {
                    out.write(h * 16 + l)
                    i += 3
                    continue
                }
            }
            out.write(c)
            i++
        }
        return String(out.toByteArray(), Charsets.UTF_8)
    }

    /** Joins and normalises "a/b" + "../c" style relative paths. */
    fun join(dir: String, rel: String): String {
        val parts = ArrayList<String>()
        val full = if (dir.isEmpty()) rel else "$dir/$rel"
        for (seg in full.split('/')) {
            when (seg) {
                "", "." -> {}
                ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1)
                else -> parts.add(seg)
            }
        }
        return parts.joinToString("/")
    }
}

class ZipVfs(file: File) : Vfs {
    private val zip = ZipFile(file)
    private val index = LinkedHashMap<String, ZipEntry>()

    init {
        val e = zip.entries()
        while (e.hasMoreElements()) {
            val z = e.nextElement()
            if (z.isDirectory) continue
            val name = z.name.trimStart('/')
            if (VfsUtil.isJunk(name)) continue
            index[name] = z
        }
    }

    override val paths: List<String> get() = index.keys.toList()
    override fun exists(path: String) = index.containsKey(path)
    override fun read(path: String): ByteArray {
        val e = index[path] ?: throw java.io.FileNotFoundException(path)
        return zip.getInputStream(e).use { it.readBytes() }
    }
    override fun close() = zip.close()
}

/** In-memory file system (tests, generated content). */
class MemVfs(files: Map<String, ByteArray>) : Vfs {
    private val map = LinkedHashMap(files)
    override val paths: List<String> get() = map.keys.toList()
    override fun exists(path: String) = map.containsKey(path)
    override fun read(path: String): ByteArray = map[path] ?: throw java.io.FileNotFoundException(path)
}

/** Copy-on-write layer used to apply `.batter` template options without touching the archive. */
class OverlayVfs(private val base: Vfs) : Vfs {
    val overrides = LinkedHashMap<String, ByteArray>()
    val removed = HashSet<String>()

    override val paths: List<String>
        get() = base.paths.filter { it !in removed && it !in overrides } + overrides.keys
    override fun exists(path: String) = path !in removed && (overrides.containsKey(path) || base.exists(path))
    override fun read(path: String): ByteArray {
        if (path in removed) throw java.io.FileNotFoundException(path)
        return overrides[path] ?: base.read(path)
    }
    fun put(path: String, data: ByteArray) { removed.remove(path); overrides[path] = data }
    fun remove(path: String) { overrides.remove(path); removed.add(path) }
    fun removeTree(prefix: String) {
        paths.filter { it == prefix || it.startsWith("$prefix/") }.forEach { remove(it) }
    }
    override fun close() = base.close()
}

/** A view of [base] rooted at [prefix] (e.g. "Layers Template/"). */
class PrefixVfs(private val base: Vfs, prefix: String) : Vfs {
    private val pre = if (prefix.isEmpty() || prefix.endsWith("/")) prefix else "$prefix/"
    override val paths: List<String> get() = base.paths.filter { it.startsWith(pre) }.map { it.substring(pre.length) }
    override fun exists(path: String) = base.exists(pre + path)
    override fun read(path: String) = base.read(pre + path)
    override fun close() = base.close()
}
