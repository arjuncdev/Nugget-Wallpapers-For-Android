package dev.tendies.core.xml

/** A parsed XML element. Namespaces are ignored (CAML's default namespace is irrelevant here). */
class XNode(val name: String, val attrs: Map<String, String>) {
    val children = ArrayList<XNode>()
    val text = StringBuilder()

    fun attr(n: String): String? = attrs[n]
    fun child(n: String): XNode? = children.firstOrNull { it.name == n }
    fun childrenNamed(n: String): List<XNode> = children.filter { it.name == n }
    fun textTrim(): String = text.toString().trim()
}

/**
 * Small non-validating XML reader.
 *
 * Why not DOM/SAX? (1) identical behaviour on JVM and Android, (2) it never tries to download the
 * Apple plist DTD referenced by `index.xml`, and (3) it stops at the end of the root element, which
 * matters because some real wallpapers (`Waves_Bundle_Gyro.tendies`) have an unterminated comment
 * appended after `</caml>` that makes strict parsers reject the whole file.
 */
object Xml {
    fun parse(bytes: ByteArray): XNode? = parse(String(bytes, Charsets.UTF_8).removePrefix("\uFEFF"))

    fun parse(src: String): XNode? {
        val n = src.length
        var i = 0
        val stack = ArrayList<XNode>()
        var root: XNode? = null
        while (i < n) {
            val lt = src.indexOf('<', i)
            if (lt < 0) break
            if (stack.isNotEmpty() && lt > i) stack[stack.size - 1].text.append(decode(src.substring(i, lt)))
            if (src.startsWith("<!--", lt)) {
                val e = src.indexOf("-->", lt + 4)
                if (e < 0) break // unterminated comment: tolerate trailing junk
                i = e + 3
            } else if (src.startsWith("<![CDATA[", lt)) {
                val e = src.indexOf("]]>", lt + 9)
                if (e < 0) break
                if (stack.isNotEmpty()) stack[stack.size - 1].text.append(src, lt + 9, e)
                i = e + 3
            } else if (src.startsWith("<?", lt)) {
                val e = src.indexOf("?>", lt + 2)
                if (e < 0) break
                i = e + 2
            } else if (src.startsWith("<!", lt)) {
                var depth = 0
                var j = lt + 2
                while (j < n) {
                    val c = src[j]
                    if (c == '[') depth++ else if (c == ']') depth-- else if (c == '>' && depth <= 0) break
                    j++
                }
                i = j + 1
            } else if (src.startsWith("</", lt)) {
                val e = src.indexOf('>', lt + 2)
                if (e < 0) break
                if (stack.isNotEmpty()) stack.removeAt(stack.size - 1)
                i = e + 1
                if (stack.isEmpty() && root != null) return root
            } else {
                var j = lt + 1
                val nameStart = j
                while (j < n && !src[j].isWhitespace() && src[j] != '>' && src[j] != '/') j++
                val name = src.substring(nameStart, j)
                val attrs = LinkedHashMap<String, String>()
                var selfClosing = false
                while (j < n) {
                    while (j < n && src[j].isWhitespace()) j++
                    if (j >= n) break
                    val c = src[j]
                    if (c == '>') { j++; break }
                    if (c == '/') { selfClosing = true; j++; continue }
                    val an0 = j
                    while (j < n && src[j] != '=' && !src[j].isWhitespace() && src[j] != '>' && src[j] != '/') j++
                    val an = src.substring(an0, j)
                    while (j < n && src[j].isWhitespace()) j++
                    if (j < n && src[j] == '=') {
                        j++
                        while (j < n && src[j].isWhitespace()) j++
                        if (j < n && (src[j] == '"' || src[j] == '\'')) {
                            val q = src[j]
                            val e = src.indexOf(q, j + 1)
                            if (e < 0) { j = n; break }
                            attrs[an] = decode(src.substring(j + 1, e))
                            j = e + 1
                        } else {
                            val vs = j
                            while (j < n && !src[j].isWhitespace() && src[j] != '>' && src[j] != '/') j++
                            attrs[an] = decode(src.substring(vs, j))
                        }
                    } else if (an.isNotEmpty()) {
                        attrs[an] = ""
                    }
                }
                val node = XNode(name, attrs)
                if (stack.isNotEmpty()) stack[stack.size - 1].children.add(node) else if (root == null) root = node
                if (selfClosing) {
                    if (stack.isEmpty()) return root
                } else {
                    stack.add(node)
                }
                i = j
            }
        }
        return root
    }

    private fun decode(s: String): String {
        if (s.indexOf('&') < 0) return s
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c != '&') { sb.append(c); i++; continue }
            val semi = s.indexOf(';', i + 1)
            if (semi < 0 || semi - i > 10) { sb.append(c); i++; continue }
            val ent = s.substring(i + 1, semi)
            val rep: String? = when {
                ent == "lt" -> "<"
                ent == "gt" -> ">"
                ent == "amp" -> "&"
                ent == "quot" -> "\""
                ent == "apos" -> "'"
                ent.startsWith("#x") || ent.startsWith("#X") -> ent.substring(2).toIntOrNull(16)?.let { String(Character.toChars(it)) }
                ent.startsWith("#") -> ent.substring(1).toIntOrNull()?.let { String(Character.toChars(it)) }
                else -> null
            }
            if (rep == null) { sb.append(c); i++ } else { sb.append(rep); i = semi + 1 }
        }
        return sb.toString()
    }
}
