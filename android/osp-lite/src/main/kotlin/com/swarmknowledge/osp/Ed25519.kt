package com.swarmknowledge.osp

import java.math.BigInteger
import java.security.MessageDigest

/**
 * Ed25519 (RFC 8032) — pure-Kotlin implementation on BigInteger.
 *
 * REQ-NF-01 (zero third-party dependencies) applies on Android too, so the
 * production signer needs Ed25519 without a crypto library: the standard
 * twisted-Edwards arithmetic straight from RFC 8032 §5.1. Correctness is
 * pinned by the RFC §7.1 vectors and the shared OSP golden vector
 * (byte-identical signatures with mcp/osp_core.py and osp/core.mjs) in
 * SigningTest.kt.
 *
 * Deliberately simple, not constant-time: this runs on a trusted phone whose
 * identity key is recoverable by re-pinning (REQ-S-02), not on a shared host.
 */
internal object Ed25519 {
    private val P: BigInteger = BigInteger.TWO.pow(255).subtract(BigInteger.valueOf(19))
    private val L: BigInteger = BigInteger("27742317777372353535851937790883648493")
        .add(BigInteger.TWO.pow(252))
    private val D: BigInteger = BigInteger.valueOf(-121665)
        .multiply(BigInteger.valueOf(121666).modInverse(P)).mod(P)
    private val I: BigInteger = BigInteger.TWO.modPow(P.subtract(BigInteger.ONE).shiftRight(2), P)

    // extended twisted-Edwards coordinates (X, Y, Z, T)
    private val IDENTITY = point(BigInteger.ZERO, BigInteger.ONE)
    private val B: Array<BigInteger>

    init {
        val by = BigInteger.valueOf(4).multiply(BigInteger.valueOf(5).modInverse(P)).mod(P)
        val bx = xRecover(by)
        B = point(bx.mod(P), by.mod(P))
    }

    private fun point(x: BigInteger, y: BigInteger): Array<BigInteger> =
        arrayOf(x, y, BigInteger.ONE, x.multiply(y).mod(P))

    private fun xRecover(y: BigInteger): BigInteger {
        val xx = y.multiply(y).subtract(BigInteger.ONE)
            .multiply(D.multiply(y).multiply(y).add(BigInteger.ONE).modInverse(P)).mod(P)
        var x = xx.modPow(P.add(BigInteger.valueOf(3)).shiftRight(3), P)
        if (x.multiply(x).subtract(xx).mod(P) != BigInteger.ZERO) x = x.multiply(I).mod(P)
        if (x.testBit(0)) x = P.subtract(x)
        return x
    }

    private fun add(p: Array<BigInteger>, q: Array<BigInteger>): Array<BigInteger> {
        val (x1, y1, z1, t1) = p
        val (x2, y2, z2, t2) = q
        val a = y1.subtract(x1).multiply(y2.subtract(x2)).mod(P)
        val b = y1.add(x1).multiply(y2.add(x2)).mod(P)
        val c = BigInteger.TWO.multiply(t1).multiply(t2).multiply(D).mod(P)
        val d = BigInteger.TWO.multiply(z1).multiply(z2).mod(P)
        val e = b.subtract(a)
        val f = d.subtract(c)
        val g = d.add(c)
        val h = b.add(a)
        return arrayOf(
            e.multiply(f).mod(P), g.multiply(h).mod(P),
            f.multiply(g).mod(P), e.multiply(h).mod(P),
        )
    }

    private fun mult(p: Array<BigInteger>, e: BigInteger): Array<BigInteger> {
        var result = IDENTITY
        for (i in e.bitLength() - 1 downTo 0) {          // MSB first, double-and-add
            result = add(result, result)
            if (e.testBit(i)) result = add(result, p)
        }
        return result
    }

    private fun compress(p: Array<BigInteger>): ByteArray {
        val zinv = p[2].modInverse(P)
        val x = p[0].multiply(zinv).mod(P)
        val y = p[1].multiply(zinv).mod(P)
        val yBytes = to32LE(y)
        if (x.testBit(0)) yBytes[31] = (yBytes[31].toInt() or 0x80).toByte()
        return yBytes
    }

    private fun decompress(data: ByteArray): Array<BigInteger> {
        require(data.size == 32) { "bad point encoding" }
        val sign = data[31].toInt() and 0x80 != 0
        val bytes = data.copyOf()
        bytes[31] = (bytes[31].toInt() and 0x7f).toByte()
        val y = from32LE(bytes)
        var x = xRecover(y)
        if (x.testBit(0) != sign) x = P.subtract(x)
        return point(x, y)
    }

    private fun secretExpand(seed: ByteArray): Pair<BigInteger, ByteArray> {
        val h = sha512(seed)
        // RFC 8032 §5.1.2: a = h[0:32] as LE int, CLAMPED — the mask kills the
        // low three bits AND every bit ≥ 254 (clearing bit 254 alone would let
        // bit 255 through, ~50 % of seeds, silently deriving a wrong key)
        val a = from32LE(h.copyOfRange(0, 32))
            .and(BigInteger.TWO.pow(254).subtract(BigInteger.valueOf(8)))
            .or(BigInteger.TWO.pow(254))
        return a to h.copyOfRange(32, 64)
    }

    fun publicKey(seed: ByteArray): ByteArray {
        require(seed.size == 32) { "seed must be 32 bytes" }
        val (a, _) = secretExpand(seed)
        return compress(mult(B, a))
    }

    fun sign(seed: ByteArray, msg: ByteArray): ByteArray {
        val (a, prefix) = secretExpand(seed)
        val public = compress(mult(B, a))
        val r = from32LE(sha512(prefix + msg)).mod(L)
        val encR = compress(mult(B, r))
        val k = from32LE(sha512(encR + public + msg)).mod(L)
        val s = r.add(k.multiply(a)).mod(L)
        return encR + to32LE(s)
    }

    fun verify(public: ByteArray, msg: ByteArray, signature: ByteArray): Boolean {
        return try {
            if (public.size != 32 || signature.size != 64) return false
            val pointA = decompress(public)
            val encR = signature.copyOfRange(0, 32)
            val pointR = decompress(encR)
            val s = from32LE(signature.copyOfRange(32, 64))
            if (s >= L) return false
            val k = from32LE(sha512(encR + public + msg)).mod(L)
            val lhs = mult(B, s)
            val rhs = add(pointR, mult(pointA, k))
            compress(lhs).contentEquals(compress(rhs))
        } catch (_: IllegalArgumentException) {
            false
        }
    }

    private fun sha512(data: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-512").digest(data)

    private fun to32LE(v: BigInteger): ByteArray {
        val be = v.toByteArray()                       // big-endian, possibly padded sign
        val out = ByteArray(32)
        var i = be.size - 1
        var j = 0
        while (i >= 0 && j < 32) {
            out[j] = be[i]
            i--; j++
        }
        return out
    }

    private fun from32LE(data: ByteArray): BigInteger {
        val be = ByteArray(data.size)
        for (i in data.indices) be[data.size - 1 - i] = data[i]
        return BigInteger(1, be)
    }
}
