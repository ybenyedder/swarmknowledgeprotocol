package com.swarmknowledge.osp

import java.math.BigDecimal

/**
 * Canonical JSON — the only JSON in the library, written by hand so the module
 * stays zero-dependency (JVM counterpart of REQ-NF-01).
 *
 * The byte format is identical to the Python reference implementation's
 * `json.dumps(obj, sort_keys=True, separators=(",", ":"))` (ensure_ascii=True),
 * which is what osp_core.DevSigner signs. That parity is what lets a packet
 * sealed in Python verify in Kotlin and vice versa — asserted by the
 * cross-language vectors embedded in the test suite.
 *
 * Value model: Map<String, Any?> / List<Any?> / String / Long / Double /
 * Boolean / null. Parsing maps JSON ints to Long and floats to Double so the
 * distinction survives a round trip (Python json.loads behaves the same).
 */
object MiniJson {

    fun parse(s: String): Any? {
        val p = Parser(s)
        val v = p.parseValue()
        p.skipWs()
        require(p.pos >= s.length) { "trailing JSON content at ${p.pos}" }
        return v
    }

    /** Compact, recursively key-sorted serialization — the canonical form. */
    fun write(v: Any?): String {
        val sb = StringBuilder()
        writeTo(v, sb)
        return sb.toString()
    }

    private fun writeTo(v: Any?, sb: StringBuilder) {
        when (v) {
            null -> sb.append("null")
            is String -> writeString(v, sb)
            is Boolean -> sb.append(if (v) "true" else "false")
            is Int -> sb.append(v.toString())
            is Long -> sb.append(v.toString())
            is Double -> sb.append(pyDouble(v))
            is Float -> sb.append(pyDouble(v.toDouble()))
            is Map<*, *> -> {
                sb.append('{')
                val keys = v.keys.map { it.toString() }.sorted()
                for ((i, k) in keys.withIndex()) {
                    if (i > 0) sb.append(',')
                    writeString(k, sb)
                    sb.append(':')
                    writeTo(v[k], sb)
                }
                sb.append('}')
            }
            is Iterable<*> -> {
                sb.append('[')
                var first = true
                for (e in v) {
                    if (!first) sb.append(',')
                    first = false
                    writeTo(e, sb)
                }
                sb.append(']')
            }
            else -> error("not JSON-serializable: ${v::class.java}")
        }
    }

    private fun writeString(s: String, sb: StringBuilder) {
        sb.append('"')
        for (c in s) {
            when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c == '\n' -> sb.append("\\n")
                c == '\r' -> sb.append("\\r")
                c == '\t' -> sb.append("\\t")
                c == '\b' -> sb.append("\\b")
                c == '\u000C' -> sb.append("\\f")
                c < ' ' || c > '~' -> sb.append("\\u%04x".format(c.code))
                else -> sb.append(c)
            }
        }
        sb.append('"')
    }

    /**
     * Double formatting with Python `repr()` semantics — required for byte-identical
     * canonical JSON with the Python reference. Java switches to scientific notation
     * at abs < 1e-3 or >= 1e7; Python only at abs < 1e-4 or >= 1e16, and writes
     * exponents as `e-05` / `e+16` (signed, >= 2 digits).
     */
    fun pyDouble(d: Double): String {
        if (d.isNaN()) return "NaN"
        if (d.isInfinite()) return if (d > 0) "Infinity" else "-Infinity"
        val j = d.toString()                       // shortest round-trip digits
        val abs = kotlin.math.abs(d)
        val pySci = abs != 0.0 && (abs < 1e-4 || abs >= 1e16)
        val jSci = j.contains('E')
        if (!pySci && !jSci) return j              // both notations plain: same bytes
        if (pySci && jSci) return toPythonSci(j)
        // plain in Python, scientific in Java (e.g. 1758106059.123456, 0.0001);
        // stripTrailingZeros turns BigDecimal's "0.00010" into Python's "0.0001"
        val plain = BigDecimal(j).stripTrailingZeros().toPlainString()
        return if ('.' in plain || 'e' in plain) plain else "$plain.0"
    }

    private fun toPythonSci(j: String): String {
        val e = j.indexOf('E')
        var mantissa = j.substring(0, e)
        val neg = mantissa.startsWith("-")
        if (neg) mantissa = mantissa.substring(1)
        val exp = j.substring(e + 1).toInt()
        if (mantissa.endsWith(".0")) mantissa = mantissa.dropLast(2)
        val dot = mantissa.indexOf('.')
        val digits = if (dot >= 0) mantissa.removeDot() else mantissa
        val e10 = exp + (if (dot >= 0) dot else mantissa.length) - 1
        val frac = if (digits.length > 1) "." + digits.substring(1) else ""
        val sign = if (e10 < 0) '-' else '+'
        val mag = kotlin.math.abs(e10).toString().padStart(2, '0')
        return "${if (neg) "-" else ""}${digits[0]}${frac}e$sign$mag"
    }

    private fun String.removeDot(): String = replace(".", "")

    private class Parser(val s: String) {
        var pos = 0

        fun skipWs() {
            while (pos < s.length && s[pos].isWhitespace()) pos++
        }

        fun parseValue(): Any? {
            skipWs()
            require(pos < s.length) { "unexpected end of JSON" }
            return when (s[pos]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> parseString()
                't' -> lit("true", true)
                'f' -> lit("false", false)
                'n' -> lit("null", null)
                else -> parseNumber()
            }
        }

        private fun lit(word: String, v: Any?): Any? {
            require(s.startsWith(word, pos)) { "bad literal at $pos" }
            pos += word.length
            return v
        }

        private fun parseObject(): Map<String, Any?> {
            pos++ // {
            val m = LinkedHashMap<String, Any?>()
            skipWs()
            if (pos < s.length && s[pos] == '}') { pos++; return m }
            while (true) {
                skipWs()
                val k = parseString()
                skipWs()
                require(s[pos] == ':') { "expected : at $pos" }
                pos++
                m[k] = parseValue()
                skipWs()
                when (s[pos]) {
                    ',' -> pos++
                    '}' -> { pos++; return m }
                    else -> throw IllegalArgumentException("expected , or } at $pos")
                }
            }
        }

        private fun parseArray(): List<Any?> {
            pos++ // [
            val l = ArrayList<Any?>()
            skipWs()
            if (pos < s.length && s[pos] == ']') { pos++; return l }
            while (true) {
                l.add(parseValue())
                skipWs()
                when (s[pos]) {
                    ',' -> pos++
                    ']' -> { pos++; return l }
                    else -> throw IllegalArgumentException("expected , or ] at $pos")
                }
            }
        }

        private fun parseString(): String {
            require(s[pos] == '"') { "expected string at $pos" }
            pos++
            val sb = StringBuilder()
            while (true) {
                val c = s[pos++]
                when {
                    c == '"' -> return sb.toString()
                    c == '\\' -> {
                        when (val e = s[pos++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'u' -> {
                                sb.append(s.substring(pos, pos + 4).toInt(16).toChar())
                                pos += 4
                            }
                            else -> throw IllegalArgumentException("bad escape \\$e")
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        private fun parseNumber(): Any {
            val start = pos
            while (pos < s.length && s[pos] in "+-.eE0123456789") pos++
            val tok = s.substring(start, pos)
            return if ('.' in tok || 'e' in tok || 'E' in tok) tok.toDouble()
            else tok.toLong()
        }
    }
}
