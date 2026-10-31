package dev.tendies.core.util

/**
 * Tiny tolerant JSON parser (no dependencies, identical behaviour on JVM and Android).
 * Accepts `//` and block comments and trailing commas, because the upstream manifests/templates
 * are hand-edited. Returns Map / List / String / Long / Double / Boolean / null.
 */
object Json {
    fun parse(text: String): Any? = Parser(text.removePrefix("\uFEFF")).readValue()

    @Suppress("UNCHECKED_CAST")
    fun asObject(v: Any?): Map<String, Any?> = (v as? Map<String, Any?>) ?: emptyMap()

    @Suppress("UNCHECKED_CAST")
    fun asList(v: Any?): List<Any?> = (v as? List<Any?>) ?: emptyList()

    private class Parser(private val s: String) {
        private var i = 0

        private fun fail(msg: String): Nothing = throw IllegalArgumentException("JSON parse error: $msg at offset $i")

        private fun skipWs() {
            while (i < s.length) {
                val c = s[i]
                if (c == ' ' || c == '\n' || c == '\r' || c == '\t') {
                    i++
                } else if (c == '/' && i + 1 < s.length && s[i + 1] == '/') {
                    while (i < s.length && s[i] != '\n') i++
                } else if (c == '/' && i + 1 < s.length && s[i + 1] == '*') {
                    i += 2
                    while (i + 1 < s.length && !(s[i] == '*' && s[i + 1] == '/')) i++
                    i += 2
                } else return
            }
        }

        fun readValue(): Any? {
            skipWs()
            if (i >= s.length) fail("unexpected end of input")
            val c = s[i]
            return when {
                c == '{' -> readObject()
                c == '[' -> readArray()
                c == '"' -> readString()
                c == 't' -> literal("true", true)
                c == 'f' -> literal("false", false)
                c == 'n' -> literal("null", null)
                c == '-' || c.isDigit() -> readNumber()
                else -> fail("unexpected '$c'")
            }
        }

        private fun literal(word: String, value: Any?): Any? {
            if (!s.startsWith(word, i)) fail("expected $word")
            i += word.length
            return value
        }

        private fun readObject(): Map<String, Any?> {
            val out = LinkedHashMap<String, Any?>()
            i++ // {
            while (true) {
                skipWs()
                if (i >= s.length) fail("unterminated object")
                if (s[i] == '}') { i++; return out }
                if (s[i] == ',') { i++; continue }
                if (s[i] != '"') fail("expected string key")
                val key = readString()
                skipWs()
                if (i >= s.length || s[i] != ':') fail("expected ':'")
                i++
                out[key] = readValue()
            }
        }

        private fun readArray(): List<Any?> {
            val out = ArrayList<Any?>()
            i++ // [
            while (true) {
                skipWs()
                if (i >= s.length) fail("unterminated array")
                if (s[i] == ']') { i++; return out }
                if (s[i] == ',') { i++; continue }
                out.add(readValue())
            }
        }

        private fun readString(): String {
            val sb = StringBuilder()
            i++ // opening quote
            while (true) {
                if (i >= s.length) fail("unterminated string")
                val c = s[i++]
                if (c == '"') return sb.toString()
                if (c != '\\') { sb.append(c); continue }
                if (i >= s.length) fail("bad escape")
                when (val e = s[i++]) {
                    '"' -> sb.append('"')
                    '\\' -> sb.append('\\')
                    '/' -> sb.append('/')
                    'b' -> sb.append('\b')
                    'f' -> sb.append('\u000C')
                    'n' -> sb.append('\n')
                    'r' -> sb.append('\r')
                    't' -> sb.append('\t')
                    'u' -> {
                        if (i + 4 > s.length) fail("bad unicode escape")
                        sb.append(s.substring(i, i + 4).toInt(16).toChar())
                        i += 4
                    }
                    else -> sb.append(e)
                }
            }
        }

        private fun readNumber(): Any {
            val start = i
            if (s[i] == '-') i++
            while (i < s.length && (s[i].isDigit() || s[i] == '.' || s[i] == 'e' || s[i] == 'E' || s[i] == '+' || s[i] == '-')) i++
            val t = s.substring(start, i)
            return if (t.any { it == '.' || it == 'e' || it == 'E' }) t.toDouble() else (t.toLongOrNull() ?: t.toDouble())
        }
    }
}
