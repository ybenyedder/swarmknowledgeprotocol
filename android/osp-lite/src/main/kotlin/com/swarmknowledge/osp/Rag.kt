package com.swarmknowledge.osp

import kotlin.math.round

/**
 * Generation budget with abstention (REQ-F-04). v0.5 default: 50/day, and a
 * battery-saver node stops charging it entirely (routing/pre-bidding stay alive).
 */
class Budget(private val generationsPerDay: Int = 50) {
    var spent: Int = 0
        private set
    val left: Int
        get() = maxOf(0, generationsPerDay - spent)

    fun charge(n: Int = 1): Boolean {
        if (left < n) return false
        spent += n
        return true
    }
}

data class Chunk(val hash: String, val text: String, val vec: ByteArray)
data class Scored(val score: Double, val chunk: Chunk)

fun round3(x: Double): Double = round(x * 1000.0) / 1000.0

/**
 * Local RAG store. Chunks never ship whole — only cited chunks travel with an
 * answer (REQ-NF-02). Dev vectors are the hashed-BOW binaries from Text.kt;
 * production stores 48 B packed bitvectors (v0.5) behind the same interface.
 */
class RagStore(chunks: List<String> = emptyList()) {
    val entries: MutableList<Chunk> = chunks.mapTo(ArrayList()) { Chunk(chunkHash(it), it, embed(it)) }

    /** Add knowledge at runtime (e.g. ospbridge /osp/teach or AIDL advertisement). */
    fun add(text: String): Chunk = Chunk(chunkHash(text), text, embed(text)).also { entries.add(it) }

    fun retrieve(qv: ByteArray, topK: Int = 3): List<Scored> =
        entries.map { Scored(similarity(qv, it.vec), it) }
            .filter { it.score > 0.0 }
            .sortedByDescending { it.score }          // stable, like the Python sort
            .take(topK)
            .map { it.copy(score = round3(it.score)) }

    operator fun get(hash: String): Chunk? = entries.firstOrNull { it.hash == hash }
}
