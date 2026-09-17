package com.swarmknowledge.osp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MiniJsonTest {

    @Test
    fun parsesScalarsAndContainers() {
        val v = MiniJson.parse("""{"a":1,"b":[1.5,"x",null,true],"c":{"d":-3}}""")
        assertEquals(
            mapOf("a" to 1L, "b" to listOf(1.5, "x", null, true), "c" to mapOf("d" to -3L)),
            v,
        )
    }

    @Test
    fun intFloatDistinctionSurvivesRoundTrip() {
        // canonical output is key-sorted, so the expected string is sorted too
        assertEquals("""{"f":3.0,"i":3}""", MiniJson.write(MiniJson.parse("""{"i":3,"f":3.0}""")))
    }

    @Test
    fun escapesLikePythonEnsureAscii() {
        val s = "say \"hi\"\nline‑accent é ✅"
        val written = MiniJson.write(mapOf("k" to s))
        // é → \u00e9, ✅ → surrogate pair \ud83d\udd00.. — lowercase hex, like Python
        assertTrue("\\u00e9" in written)
        assertTrue("\\n" in written)
        val back = (MiniJson.parse(written) as Map<*, *>)["k"]
        assertEquals(s, back)
    }

    @Test
    fun keySortingIsRecursive() {
        val out = MiniJson.write(
            mapOf("z" to mapOf("b" to 1L, "a" to 2L), "a" to listOf(mapOf("y" to 1L, "x" to 2L))),
        )
        assertEquals("""{"a":[{"x":2,"y":1}],"z":{"a":2,"b":1}}""", out)
    }

    @Test
    fun rejectsTrailingGarbage() {
        assertFailsWith<IllegalArgumentException> { MiniJson.parse("{} oops") }
    }

    @Test
    fun handlesUnicodeEscapesOnParse() {
        assertEquals("é", MiniJson.parse("\"\\u00e9\""))
        assertEquals("🐅", MiniJson.parse("\"\\ud83d\\udc05\""))   // surrogate pair
    }
}
