package com.swarmknowledge.osp

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/**
 * Quantization codec for embeddings crossing the Binder or stored on flash,
 * bit-compatible with LLMProvider's and harnessDroid's EmbeddingCodec:
 * per vector, [float32 scale][int8 x dim] little-endian, scale = maxAbs / 127
 * (1 when the vector is all zeros), quantization by truncation clamped to
 * [-127, 127].
 *
 * This is the bridge between the wire/stored format and float vectors:
 * ospbridge uses it on both sides of the AIDL calls to LLMProvider.
 */
object Q8Codec {

    /** Bytes occupied by one packed vector of the given dimension. */
    fun bytesPerVector(dim: Int): Int = 4 + dim

    /** Packs a single vector. */
    fun encodeOne(vector: FloatArray): ByteArray {
        val out = ByteArray(bytesPerVector(vector.size))
        val buf = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
        val scale = maxAbsScale(vector)
        buf.putFloat(scale)
        for (x in vector) buf.put(quantize(x, scale))
        return out
    }

    /** Unpacks a stream of packed vectors into a flat [count * dim] array. */
    fun decodeFlat(bytes: ByteArray, dim: Int): FloatArray {
        val perVector = bytesPerVector(dim)
        val count = bytes.size / perVector
        val out = FloatArray(count * dim)
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        for (t in 0 until count) {
            val scale = buf.float
            val base = t * dim
            for (d in 0 until dim) out[base + d] = buf.get() * scale
        }
        return out
    }

    /** Unpacks exactly one packed vector (e.g. the reply of embedText). */
    fun decodeOne(bytes: ByteArray): FloatArray {
        require(bytes.size > 4) { "packed vector too short: ${bytes.size}" }
        val dim = bytes.size - 4
        return decodeFlat(bytes, dim).copyOfRange(0, dim)
    }

    private fun maxAbsScale(vector: FloatArray): Float {
        var maxAbs = 0f
        for (x in vector) {
            val a = abs(x)
            if (a > maxAbs) maxAbs = a
        }
        return if (maxAbs == 0f) 1f else maxAbs / 127f
    }

    private fun quantize(x: Float, scale: Float): Byte =
        ((x / scale).toInt().coerceIn(-127, 127)).toByte()
}
