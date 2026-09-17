package com.swarmknowledge.osp

import java.security.MessageDigest

/**
 * Dev embedder — deterministic hashed bag-of-words binary vectors, bit-for-bit
 * identical to the Python reference (`osp_core.embed`/`similarity`). Production
 * uses a real 384d embedder (MiniLM class, v0.5 [A8]); the interface is the same
 * (bytes of dims/8) so tests and prod share all vector math.
 *
 * Parity note: Python takes the token's SHA-1 as a 160-bit int and computes
 * `(h >> 3) % len(vec)` — a full-width modulo that only degenerates to
 * last-byte bit math when len(vec) is a power of two. BigInteger reproduces
 * Python exactly for every vector width.
 */
private val SHA1 = ThreadLocal.withInitial { MessageDigest.getInstance("SHA-1") }
private val SHA256 = ThreadLocal.withInitial { MessageDigest.getInstance("SHA-256") }

private val STOPWORDS = setOf(
    "a", "an", "the", "is", "are", "was", "were", "be", "been", "am", "do", "does",
    "did", "why", "how", "what", "when", "where", "which", "who", "of", "and", "or",
    "to", "in", "on", "at", "by", "for", "with", "from", "as", "it", "its", "this",
    "that", "if", "then", "so", "not", "no", "yes", "will", "would", "can", "could",
    "should", "may", "might"
)

fun tokenize(text: String): List<String> =
    text.lowercase()
        .map { if (it.isLetterOrDigit()) it else ' ' }
        .joinToString("")
        .split(' ')
        .filter { it.isNotEmpty() && it !in STOPWORDS }

fun embed(text: String, dims: Int = 256): ByteArray {
    val vec = ByteArray(dims / 8)
    val width = java.math.BigInteger.valueOf(vec.size.toLong())
    for (tok in tokenize(text)) {
        val h = java.math.BigInteger(1, SHA1.get().digest(tok.toByteArray(Charsets.UTF_8)))
        val idx = h.shiftRight(3).mod(width).toInt()
        val bit = h.and(java.math.BigInteger.valueOf(7)).toInt()
        vec[idx] = (vec[idx].toInt() or (1 shl bit)).toByte()
    }
    return vec
}

/**
 * Jaccard over set bits (D0 — no model). Dev-embedder note, as in Python:
 * hashed bag-of-words vectors are sparse, so plain Hamming saturates near 1.0
 * and does not discriminate; Jaccard on set bits is the set-level similarity.
 */
fun similarity(a: ByteArray, b: ByteArray): Double {
    if (a.isEmpty() || b.isEmpty()) return 0.0
    var inter = 0
    var union = 0
    val n = minOf(a.size, b.size)
    for (i in 0 until n) {
        val x = a[i].toInt() and 0xFF
        val y = b[i].toInt() and 0xFF
        inter += Integer.bitCount(x and y)
        union += Integer.bitCount(x or y)
    }
    for (i in n until a.size) union += Integer.bitCount(a[i].toInt() and 0xFF)
    for (i in n until b.size) union += Integer.bitCount(b[i].toInt() and 0xFF)
    return if (union == 0) 0.0 else inter.toDouble() / union.toDouble()
}

/**
 * Asymmetric query-side coverage: |q ∩ c| / |q| (Python `query_cover`, same
 * shape as the LexicalVerifier's answer-vs-evidence ratio). The symmetric
 * Jaccard collapses when chunk ≫ query in token count — a full match on an
 * 8-token question against a 120-token chunk scores ~0.03, under any sane
 * bidMin. Competence is how much of the QUESTION a chunk can ground.
 */
fun queryCover(qv: ByteArray, cv: ByteArray): Double {
    var qBits = 0
    for (b in qv) qBits += Integer.bitCount(b.toInt() and 0xFF)
    if (qBits == 0) return 0.0
    var hit = 0
    val n = minOf(qv.size, cv.size)
    for (i in 0 until n) hit += Integer.bitCount(qv[i].toInt() and cv[i].toInt() and 0xFF)
    return hit.toDouble() / qBits.toDouble()
}

fun chunkHash(text: String): String =
    "sha256:" + SHA256.get().digest(text.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }.take(16)

fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
