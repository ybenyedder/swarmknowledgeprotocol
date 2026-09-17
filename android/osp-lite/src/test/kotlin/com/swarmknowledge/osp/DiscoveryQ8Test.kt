package com.swarmknowledge.osp

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DiscoveryQ8Test {

    @AfterTest
    fun restoreClock() {
        OspClock.now = { System.currentTimeMillis() / 1000.0 }
    }

    private fun dirWithClock() = Directory().also { OspClock.now = { 1_000.0 } }

    private fun bundle(key: String) = mapOf<String, Any?>("signing" to key, "prekey" to "pk-$key")

    // -- directory -----------------------------------------------------------------

    @Test
    fun registerRenewExpire() {
        var t = 1_000.0
        OspClock.now = { t }
        val d = Directory()
        assertTrue(d.register("n1", "N2", bundle("k1"), jti = "j1"))
        assertEquals(listOf("n1"), d.alive())
        t += 100
        assertTrue(d.register("n1", "N2", bundle("k1"), jti = "j2"))   // renewal
        assertEquals(1, d.stats.renewals)
        t += DEFAULT_LEASE_S + 1
        assertEquals(1, d.expire())
        assertTrue(d.alive().isEmpty())
    }

    @Test
    fun tofuRejectsChangedKeyWithoutRepin() {
        val d = dirWithClock()
        assertTrue(d.register("n1", "N2", bundle("k1")))
        assertFalse(d.register("n1", "N2", bundle("ROTATED")))          // REQ-S-02
        assertEquals(1, d.stats.pinRejects)
        assertTrue(d.repin("n1", "N2", bundle("ROTATED")))              // escape hatch
        assertEquals("ROTATED", d["n1"]!!.keyBundle["signing"])
    }

    @Test
    fun announcementReplayRejected() {
        val d = dirWithClock()
        assertTrue(d.register("n1", "N2", bundle("k1"), jti = "same"))
        assertFalse(d.register("n1", "N2", bundle("k1"), jti = "same"))
        assertEquals(1, d.stats.replays)
    }

    @Test
    fun lookupRanksByCodebookSimilarity() {
        val d = dirWithClock()
        d.register("pumps", "N2", bundle("k1"),
            codebook = listOf(embed("hydraulic pump failure") to "pumps"))
        d.register("baking", "N1", bundle("k2"),
            codebook = listOf(embed("sourdough bread recipe oven") to "baking"))
        val hits = d.lookup(embed("why does hydraulic pump failure happen"), topK = 2)
        assertEquals("pumps", hits.first().nodeId)
    }

    // -- Q8 codec (EmbeddingCodec parity) --------------------------------------------

    @Test
    fun q8RoundTripKeepsShape() {
        val v = floatArrayOf(0.5f, -1.25f, 0.0f, 3.75f, -0.125f)
        val packed = Q8Codec.encodeOne(v)
        assertEquals(4 + v.size, packed.size)
        val back = Q8Codec.decodeOne(packed)
        assertEquals(v.size, back.size)
        // quantization error bounded by one step (scale = maxAbs/127)
        val step = 3.75f / 127f
        for (i in v.indices) assertTrue(kotlin.math.abs(v[i] - back[i]) <= step + 1e-6f)
    }

    private fun scaleBitsLE(packed: ByteArray): Int =
        (packed[0].toInt() and 0xFF) or
            ((packed[1].toInt() and 0xFF) shl 8) or
            ((packed[2].toInt() and 0xFF) shl 16) or
            ((packed[3].toInt() and 0xFF) shl 24)

    @Test
    fun q8LayoutIsScaleThenInt8LittleEndian() {
        val packed = Q8Codec.encodeOne(floatArrayOf(2.54f, -2.54f, 0f))
        // 2.54/127 is not exactly representable — check the little-endian
        // float32 bytes of the scale field instead of hardcoding them.
        val scale = Float.fromBits(scaleBitsLE(packed))
        assertEquals((2.54f / 127f).toDouble(), scale.toDouble(), 1e-9)
        assertEquals(127.toByte(), packed[4])          // truncation of 2.54/scale → 127
        assertEquals((-127).toByte(), packed[5])
        assertEquals(0.toByte(), packed[6])
    }

    @Test
    fun q8ZeroVectorUsesUnitScale() {
        val packed = Q8Codec.encodeOne(FloatArray(4))
        assertEquals(1.0f, Float.fromBits(scaleBitsLE(packed)))
    }

    @Test
    fun q8DecodeFlatHandlesMultipleVectors() {
        val a = floatArrayOf(1f, 2f, 3f)
        val b = floatArrayOf(-3f, -2f, -1f)
        val flat = Q8Codec.decodeFlat(Q8Codec.encodeOne(a) + Q8Codec.encodeOne(b), 3)
        assertEquals(6, flat.size)
        assertEquals(3f, flat[2])
        assertEquals(-3f, flat[3])
    }
}
