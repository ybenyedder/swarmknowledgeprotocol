package com.swarmknowledge.osp

/**
 * OSP discovery service (REQ-F-05) — lease-based node directory.
 *
 * Bootstrap order per v0.6: configured seeds → mDNS (future, same interface) →
 * directory (in-memory). The interface is transport-agnostic.
 *
 * Security (REQ-S-02): TOFU key pinning — the first key bundle registered for
 * a node_id is pinned; a changed bundle is rejected unless explicitly re-pinned.
 * Announcements carry a jti and are subject to replay rejection.
 */
const val DEFAULT_LEASE_S = 900.0

class NodeRecord(
    val nodeId: String,
    var klass: String,                                   // N1 thin | N2 full | N3 provider-backed
    val keyBundle: Map<String, Any?>,                    // {"signing": ..., "prekey": ...}
    var endpoints: List<String> = emptyList(),
    var transports: List<String> = listOf("memory"),
    var codebook: List<Pair<ByteArray, String>> = emptyList(),
    var leaseUntil: Double = 0.0,
    var jti: String = "",
)

class DirectoryStats {
    var registers = 0; var renewals = 0
    var pinRejects = 0; var replays = 0; var expiries = 0
}

class Directory(private val now: () -> Double = OspClock.now) {
    val records = LinkedHashMap<String, NodeRecord>()
    private val pins = HashMap<String, Any?>()           // node_id → pinned signing key
    private val seenJti = HashSet<String>()
    val stats = DirectoryStats()

    // -- registration -----------------------------------------------------------

    fun register(
        nodeId: String,
        klass: String,
        keyBundle: Map<String, Any?>,
        codebook: List<Pair<ByteArray, String>>? = null,
        endpoints: List<String>? = null,
        transports: List<String>? = null,
        jti: String = "",
        leaseS: Double = DEFAULT_LEASE_S,
    ): Boolean {
        val t = now()
        if (jti.isNotEmpty()) {
            if (jti in seenJti) {
                stats.replays++
                return false                             // replay — rejected (REQ-S-02)
            }
            seenJti.add(jti)
        }

        val pinned = pins[nodeId]
        if (pinned != null && pinned != keyBundle["signing"]) {
            stats.pinRejects++
            return false                                 // TOFU violation (REQ-S-02)
        }

        val rec = records[nodeId]
        if (rec == null || rec.leaseUntil < t) {
            stats.registers++
            records[nodeId] = NodeRecord(nodeId, klass, keyBundle)
            pins[nodeId] = keyBundle["signing"]
        } else {
            stats.renewals++
        }
        val r = records[nodeId]!!
        r.klass = klass
        endpoints?.let { r.endpoints = it }
        transports?.let { r.transports = it }
        codebook?.let { r.codebook = it }
        r.leaseUntil = t + leaseS
        r.jti = jti
        return true
    }

    /** Explicit re-pinning after key rotation (REQ-S-02 escape hatch). */
    fun repin(
        nodeId: String, klass: String, keyBundle: Map<String, Any?>,
        codebook: List<Pair<ByteArray, String>>? = null,
        endpoints: List<String>? = null,
        transports: List<String>? = null,
        jti: String = "",
        leaseS: Double = DEFAULT_LEASE_S,
    ): Boolean {
        pins[nodeId] = keyBundle["signing"]
        records.remove(nodeId)
        return register(nodeId, klass, keyBundle, codebook, endpoints, transports, jti, leaseS)
    }

    // -- queries -------------------------------------------------------------------

    /** Drop expired leases; returns count removed. */
    fun expire(): Int {
        val t = now()
        val dead = records.filterValues { it.leaseUntil < t }.keys.toList()
        for (nid in dead) records.remove(nid)
        stats.expiries += dead.size
        return dead.size
    }

    /** Live records ranked by codebook similarity (D0 — no model). */
    fun lookup(queryVec: ByteArray, topK: Int = 3, klass: String? = null): List<NodeRecord> {
        expire()
        var recs = records.values.toList()
        if (klass != null) recs = recs.filter { it.klass == klass }
        fun best(r: NodeRecord): Double =
            if (r.codebook.isEmpty()) 0.0 else r.codebook.maxOf { similarity(queryVec, it.first) }
        return recs.sortedByDescending { best(it) }.take(topK)   // stable, like Python
    }

    operator fun get(nodeId: String): NodeRecord? {
        expire()
        return records[nodeId]
    }

    fun alive(): List<String> {
        expire()
        return records.keys.sorted()
    }
}
