package dev.tendies.core.plist

import dev.tendies.core.xml.XNode
import dev.tendies.core.xml.Xml

/** NSKeyedArchiver object reference (only seen in providerInfo.plist, which we don't need). */
class PlistUid(val value: Long) {
    override fun toString() = "UID($value)"
}

/** Seconds since 2001-01-01 (Cocoa epoch). */
class PlistDate(val secondsSince2001: Double)

/**
 * Property-list reader. Apple ships `Wallpaper.plist` and most PosterBoard metadata as *binary*
 * plists (`bplist00`), while the Core Animation package manifest `index.xml` is an XML plist.
 *
 * Result types: Map<String,Any?>, List<Any?>, String, Long, Double, Boolean, ByteArray,
 * PlistDate, PlistUid, null.
 */
object Plist {
    fun parse(bytes: ByteArray): Any? {
        if (bytes.size >= 8 && String(bytes, 0, 6, Charsets.ISO_8859_1) == "bplist") {
            return Binary(bytes).parse()
        }
        val root = Xml.parse(bytes) ?: return null
        val top = if (root.name == "plist") root.children.firstOrNull() else root
        return top?.let { xmlValue(it) }
    }

    @Suppress("UNCHECKED_CAST")
    fun asDict(v: Any?): Map<String, Any?> = (v as? Map<String, Any?>) ?: emptyMap()

    @Suppress("UNCHECKED_CAST")
    fun asList(v: Any?): List<Any?> = (v as? List<Any?>) ?: emptyList()

    fun asDouble(v: Any?): Double? = when (v) { is Double -> v; is Long -> v.toDouble(); is String -> v.toDoubleOrNull(); else -> null }
    fun asBool(v: Any?): Boolean? = when (v) { is Boolean -> v; is Long -> v != 0L; else -> null }

    private fun xmlValue(n: XNode): Any? = when (n.name) {
        "dict" -> {
            val out = LinkedHashMap<String, Any?>()
            var key: String? = null
            for (c in n.children) {
                if (c.name == "key") key = c.textTrim() else if (key != null) { out[key] = xmlValue(c); key = null }
            }
            out
        }
        "array" -> n.children.map { xmlValue(it) }
        "string" -> n.text.toString()
        "integer" -> n.textTrim().toLongOrNull() ?: n.textTrim().toDoubleOrNull()
        "real" -> parseReal(n.textTrim())
        "true" -> true
        "false" -> false
        "data" -> try { java.util.Base64.getMimeDecoder().decode(n.textTrim()) } catch (e: IllegalArgumentException) { ByteArray(0) }
        "date" -> n.textTrim()
        else -> null
    }

    private fun parseReal(t: String): Double {
        val l = t.lowercase()
        return when {
            l.endsWith("infinity") || l.endsWith("inf") -> if (l.startsWith("-")) Double.NEGATIVE_INFINITY else Double.POSITIVE_INFINITY
            l == "nan" -> Double.NaN
            else -> t.toDoubleOrNull() ?: 0.0
        }
    }

    private class Binary(private val d: ByteArray) {
        private val offsetSize: Int
        private val refSize: Int
        private val numObjects: Int
        private val topObject: Int
        private val offsetTable: Int

        init {
            val t = d.size - 32
            require(t >= 8) { "bplist too small" }
            offsetSize = d[t + 6].toInt() and 0xff
            refSize = d[t + 7].toInt() and 0xff
            numObjects = uint(t + 8, 8).toInt()
            topObject = uint(t + 16, 8).toInt()
            offsetTable = uint(t + 24, 8).toInt()
        }

        private fun uint(pos: Int, n: Int): Long {
            var v = 0L
            for (k in 0 until n) v = (v shl 8) or (d[pos + k].toLong() and 0xff)
            return v
        }

        fun parse(): Any? = obj(topObject, 0)

        private fun offsetOf(idx: Int): Int {
            require(idx in 0 until numObjects) { "bad object ref $idx" }
            return uint(offsetTable + idx * offsetSize, offsetSize).toInt()
        }

        /** Returns (length, dataStart) for objects whose low nibble encodes a length. */
        private fun lenAt(p: Int, low: Int): LongArray {
            if (low != 0xF) return longArrayOf(low.toLong(), p.toLong())
            val m = d[p].toInt() and 0xff
            val nb = 1 shl (m and 0xf)
            return longArrayOf(uint(p + 1, nb), (p + 1 + nb).toLong())
        }

        private fun obj(idx: Int, depth: Int): Any? {
            require(depth < 128) { "bplist nesting too deep" }
            var p = offsetOf(idx)
            val marker = d[p].toInt() and 0xff
            val type = marker shr 4
            val low = marker and 0xf
            p++
            when (type) {
                0x0 -> return when (low) { 0x8 -> false; 0x9 -> true; else -> null }
                0x1 -> {
                    val n = 1 shl low
                    return if (n == 8) uint(p, 8) else uint(p, n) // 8-byte ints are signed two's complement (Long already)
                }
                0x2 -> {
                    val n = 1 shl low
                    return if (n == 4) java.lang.Float.intBitsToFloat(uint(p, 4).toInt()).toDouble()
                    else java.lang.Double.longBitsToDouble(uint(p, 8))
                }
                0x3 -> return PlistDate(java.lang.Double.longBitsToDouble(uint(p, 8)))
                0x4 -> {
                    val l = lenAt(p, low)
                    val s = l[1].toInt()
                    return d.copyOfRange(s, s + l[0].toInt())
                }
                0x5 -> {
                    val l = lenAt(p, low)
                    val s = l[1].toInt()
                    return String(d, s, l[0].toInt(), Charsets.ISO_8859_1)
                }
                0x6 -> {
                    val l = lenAt(p, low)
                    val s = l[1].toInt()
                    return String(d, s, l[0].toInt() * 2, Charsets.UTF_16BE)
                }
                0x8 -> return PlistUid(uint(p, low + 1))
                0xA, 0xC -> {
                    val l = lenAt(p, low)
                    val count = l[0].toInt()
                    var q = l[1].toInt()
                    val out = ArrayList<Any?>(count)
                    for (k in 0 until count) {
                        out.add(obj(uint(q, refSize).toInt(), depth + 1))
                        q += refSize
                    }
                    return out
                }
                0xD -> {
                    val l = lenAt(p, low)
                    val count = l[0].toInt()
                    val keyStart = l[1].toInt()
                    val valStart = keyStart + count * refSize
                    val out = LinkedHashMap<String, Any?>()
                    for (k in 0 until count) {
                        val key = obj(uint(keyStart + k * refSize, refSize).toInt(), depth + 1)
                        out[key.toString()] = obj(uint(valStart + k * refSize, refSize).toInt(), depth + 1)
                    }
                    return out
                }
                else -> return null
            }
        }
    }
}
