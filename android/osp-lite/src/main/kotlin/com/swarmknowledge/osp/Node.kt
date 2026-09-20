package com.swarmknowledge.osp

/**
 * OSP node — Kotlin port of the Python reference (`mcp/osp_core.py`), same
 * rules and same wire behavior:
 *
 *   - signed stateless packets (DEV-SIGNER MVP; Ed25519 for production, REQ-S-01)
 *   - C1 gas monotonicity, C2 path-vector loop freedom + jti replay cache,
 *     C3 mapping contraction (with C=1 the first align is a lock-in check),
 *     C4 quorum with provenance diversity
 *   - 3-layer hallucination firewall: closed-book provenance, origin-side
 *     groundedness verify, reputation EWMA
 *   - node classes N1 thin / N2 full / N3 provider-backed (REQ-F-03)
 *   - generation budget with abstention (REQ-F-04), T2 never remote by
 *     default (REQ-NF-02)
 */
data class NodeConfig(
    val gas: Int = 3,                 // G (v0.5 Lite)
    val maxClarify: Int = 1,          // C
    val retries: Int = 2,             // R
    val bidWindowS: Double = 0.0,     // sync hub → window collapses; hook for real transports
    val groundednessMin: Double = 0.35,  // dev verifier threshold (0.8 with real NLI)
    val bidMin: Double = 0.15,
    val allowRemoteT2: Boolean = false,  // REQ-NF-02 default deny
    val alignTolerance: Double = 0.05,   // allowed drift on the first align (C = 1)
    val contractionGamma: Double = 0.95, // per-round shrink when C > 1 (v0.4 rule C3)
)

interface Hub {
    fun join(node: Node)
    fun peers(nodeId: String): List<String>
    fun send(from: String, to: String, pkt: Packet): Packet?
}

/** Transport seam — WhatsApp / mesh / HTTP adapters implement this (v0.5 §4). */
class InMemoryHub : Hub {
    private val nodes = LinkedHashMap<String, Node>()

    override fun join(node: Node) {
        node.hub = this
        nodes[node.id] = node
    }

    override fun peers(nodeId: String): List<String> = nodes.keys.filter { it != nodeId }

    override fun send(from: String, to: String, pkt: Packet): Packet? =
        nodes[to]?.onPacket(pkt)

    fun node(id: String): Node? = nodes[id]
}

data class Outcome(
    val mode: Mode,
    val answer: String? = null,
    val detail: String? = null,
    val trace: List<Map<String, Any?>> = emptyList(),
    val origin: String = "",
    val budgetLeft: Int = 0,
    val groundedness: Double? = null,
    val cost: Map<String, Any?>? = null,
) {
    fun toMap(): Map<String, Any?> = buildMap {
        put("mode", mode.value)
        put("answer", answer)
        put("detail", detail)
        put("trace", trace)
        put("origin", origin)
        put("budget_left", budgetLeft.toLong())
        groundedness?.let { put("groundedness", round3(it)) }
        cost?.let { put("cost", it) }
    }
}

class Node(
    val id: String,
    val rag: RagStore,
    private val d3: D3Provider? = null,
    val signer: Signer = DevSigner(),
    klass: String? = null,
    val cfg: NodeConfig = NodeConfig(),
) {
    /** N1 = d3 null (thin); N2 = local provider (full); N3 = remote provider. */
    val klass: String = klass ?: when {
        d3 == null -> "N1"
        d3.remote -> "N3"
        else -> "N2"
    }

    val budget = Budget()
    val reputation = HashMap<String, Double>()       // peer → EWMA reliability
    val hooks = HashMap<String, (Packet) -> Unit>()  // test seams (e.g. "pre_align")
    var hub: Hub? = null

    private val jtiCache = HashSet<String>()         // replay protection (C2 / REQ-S-02)
    private val mappingCache = HashMap<String, Double>()
    private var verifier: D2Verifier = LexicalVerifier()

    // -- validation pipeline (every inbound packet, v0.4 §Convergence) ----------

    fun onPacket(pkt: Packet): Packet? {
        if (!pkt.verified(signer)) return null                          // forged — drop
        if (pkt.expired()) return rfo(pkt, Mode.GAS_EXHAUSTED, "expired")  // old age ≈ death
        if (pkt.jti in jtiCache) return null                            // replay — silent drop
        jtiCache.add(pkt.jti)
        if (pkt.trail.any { it["node"] == id })
            return rfo(pkt, Mode.LOOP_DETECTED, "$id seen in trail")
        if (pkt.gas <= 0 && pkt.action != Action.RFO)
            return rfo(pkt, Mode.GAS_EXHAUSTED, "gas = 0 on arrival")
        return dispatch(pkt)
    }

    /** Protocol numbers arrive JSON-parsed: an integral value ("0", "1")
     *  parses to Long, a fractional one to Double — the wire contract is
     *  "a number", so accept both (a bot emitting mapping_distance: 0 on an
     *  exact-evidence match crashed the origin with a ClassCastException). */
    private fun dbl(v: Any?): Double = (v as? Number)?.toDouble()
        ?: error("expected a number, got $v")

    private fun rfo(pkt: Packet, mode: Mode, reason: String): Packet = Packet(
        action = Action.RFO, originId = pkt.originId, queryId = pkt.queryId,
        sender = id, gas = pkt.gas,
        trail = pkt.trail + mapOf("node" to id, "action" to pkt.action.value),
        payload = mapOf("mode" to mode.value, "reason" to reason),
    ).seal(signer)

    private fun dispatch(pkt: Packet): Packet? = when (pkt.action) {
        Action.PROPOSE -> onPropose(pkt)
        Action.ALIGN -> onAlign(pkt)
        Action.RESOLVE -> onResolve(pkt)
        Action.GET_CHUNK -> onGetChunk(pkt)
        else -> null
    }

    // -- responder-side handlers --------------------------------------------------

    private fun retrievalScore(qv: ByteArray): Pair<Double, List<Scored>> {
        val chunks = rag.retrieve(qv)
        return (chunks.firstOrNull()?.score ?: 0.0) to chunks
    }

    private fun onPropose(pkt: Packet): Packet? {
        // PRE-BID: generation-free gate (D0 + lexical cross-chunk agreement)
        val qv = queryVec(pkt.payload)
        val (score, chunks) = retrievalScore(qv)
        val rep = reputation[pkt.originId] ?: 0.5
        // Generation-free gate: no chunks, or similarity below floor → no bid at
        // all. (Full semantic entropy needs sampling; v0.5 §02 substitution.)
        if (chunks.isEmpty() || score < cfg.bidMin)
            return rfo(pkt, Mode.NO_QUORUM, "no competent evidence — abstained")
        val bid = round3(minOf(1.0, 0.5 * score + 0.3 * rep + 0.2 * chunks[0].score))
        return Packet(
            action = Action.BID, originId = pkt.originId, queryId = pkt.queryId,
            sender = id, gas = pkt.gas - 1,
            trail = pkt.trail + mapOf("node" to id, "action" to "BID"),
            payload = mapOf(
                "bid" to bid,
                "retrieval_similarity" to score,
                "reputation" to rep,
                "node_class" to klass,
                "can_generate" to (d3 != null && d3.available && budget.left > 0),
                "provenance" to chunks.map {
                    mapOf<String, Any?>("chunk_hash" to it.chunk.hash, "score" to it.score)
                },
            ),
        ).seal(signer)
    }

    private fun onAlign(pkt: Packet): Packet? {
        // Mapping on cache-miss uses D3; contraction enforced by the ORIGIN (C3)
        val src = pkt.payload["source"]?.toString() ?: return null
        val tgt = pkt.payload["target"]?.toString() ?: return null
        val qv = queryVec(pkt.payload)
        val key = "${pkt.queryId}|${pkt.originId}|$src|$tgt"
        val dist = mappingCache.getOrPut(key) {
            val d = round3(1.0 - retrievalScore(qv).first)
            if (d3 != null && budget.charge())
                d3.generate("Confirm mapping $src -> $tgt", rag.entries.take(1).map { Evidence(it.hash, it.text) })
            d
        }
        return Packet(
            action = Action.ACK, originId = pkt.originId, queryId = pkt.queryId,
            sender = id, gas = pkt.gas - 1,
            trail = pkt.trail + mapOf("node" to id, "action" to "ALIGN"),
            payload = mapOf("mapping_distance" to dist, "source" to src, "target" to tgt),
        ).seal(signer)
    }

    private fun onResolve(pkt: Packet): Packet? {
        // The single generation (v0.5: generation-once). Abstains if unable.
        val provider = d3 ?: return rfo(pkt, Mode.NO_QUORUM, "N1 node cannot generate")
        val qv = queryVec(pkt.payload)
        val chunks = rag.retrieve(qv)
        if (!budget.charge()) return rfo(pkt, Mode.NO_QUORUM, "generation budget exhausted")
        val out = try {
            provider.generate(
                pkt.payload["query_text"]?.toString() ?: "",
                chunks.map { Evidence(it.chunk.hash, it.chunk.text) },
            )
        } catch (e: Exception) {
            // explicit failure backtracked to the origin — never silent (v0.4)
            return rfo(pkt, Mode.NO_QUORUM, "provider failure: ${e.message}")
        }
        return Packet(
            action = Action.RESOLVE, originId = pkt.originId, queryId = pkt.queryId,
            sender = id, gas = pkt.gas - 1,
            trail = pkt.trail + mapOf("node" to id, "action" to "RESOLVE"),
            payload = mapOf(
                "answer" to out.answer,
                "digest" to mapOf(
                    "chunk_hashes" to chunks.map { it.chunk.hash },
                    "head" to out.answer.take(256),
                ),
                "provenance" to chunks.map {
                    mapOf<String, Any?>("chunk_hash" to it.chunk.hash, "text" to it.chunk.text)
                },
                "provider" to out.provider,
                "cost" to out.cost,
            ),
        ).seal(signer)
    }

    private fun onGetChunk(pkt: Packet): Packet? {
        val h = pkt.payload["chunk_hash"]?.toString() ?: return null
        val c = rag[h] ?: return rfo(pkt, Mode.MISMATCH, "unknown chunk")
        return Packet(
            action = Action.ACK, originId = pkt.originId, queryId = pkt.queryId,
            sender = id, gas = pkt.gas - 1,
            trail = pkt.trail + mapOf("node" to id, "action" to "GET_CHUNK"),
            payload = mapOf("chunk" to mapOf("hash" to c.hash, "text" to c.text)),
        ).seal(signer)
    }

    // -- origin-side orchestration --------------------------------------------------

    /** Run a full OSP negotiation. tier ∈ {0,1,2} → k = 1/2/3, q = 1/2/2. */
    fun query(text: String, tier: Int = 1, directory: Directory? = null): Outcome {
        require(tier in 0..2) { "tier must be 0, 1 or 2" }
        val (k, q) = listOf(1 to 1, 2 to 2, 3 to 2)[tier]
        val qv = embed(text)
        val trace = ArrayList<Map<String, Any?>>()

        val candidates = route(qv, k, directory)
        if (candidates.isEmpty())
            return outcome(Mode.NO_QUORUM, trace, "no candidate nodes", origin = id, budgetLeft = budget.left)

        // 01 PROPOSE → 02 PRE-BID/BID
        val bids = ArrayList<Packet>()
        for (nodeId in candidates) {
            val pkt = Packet(
                action = Action.PROPOSE, originId = id, queryId = newId(12),
                sender = id, gas = cfg.gas,
                payload = mapOf("query_vec" to bytesToLongs(qv), "query_text" to text),
            ).seal(signer)
            trace.add(mapOf("to" to nodeId, "action" to "PROPOSE"))
            val reply = hub?.send(id, nodeId, pkt) ?: continue
            when (reply.action) {
                Action.BID -> {
                    bids.add(reply)
                    trace.add(mapOf("from" to nodeId, "action" to "BID", "bid" to reply.payload["bid"]))
                }
                Action.RFO -> trace.add(mapOf("from" to nodeId, "rfo" to reply.payload["reason"]))
                else -> {}
            }
        }

        // C4 — provenance diversity: same-hash votes collapse to one
        val capable = diverseBids(bids).filter { it.payload["can_generate"] == true }
        if (capable.size < q)
            return outcome(Mode.NO_QUORUM, trace,
                "${capable.size} verified capable bids < q=$q", origin = id, budgetLeft = budget.left)

        // 03 ALIGN — one clarify round max (C = 1, v0.5)
        val winner = capable.maxBy { dbl(it.payload["bid"]) }
        val prov = (winner.payload["provenance"] as List<*>).first() as Map<*, *>
        val dist0 = 1.0 - dbl(winner.payload["retrieval_similarity"])
        hooks["pre_align"]?.invoke(winner)      // test seam: mutate state pre-align
        val align = Packet(
            action = Action.ALIGN, originId = id, queryId = winner.queryId,
            sender = id, gas = cfg.gas,
            payload = mapOf(
                "query_vec" to bytesToLongs(qv),
                "source" to text,
                "target" to prov["chunk_hash"],
            ),
        ).seal(signer)
        val alignReply = hub?.send(id, winner.sender, align)
        trace.add(mapOf("from" to winner.sender, "action" to "ALIGN"))
        if (alignReply == null || alignReply.action == Action.RFO)
            return outcome(Mode.MISMATCH, trace, "alignment refused", origin = id, budgetLeft = budget.left)
        val dist1 = dbl(alignReply.payload["mapping_distance"])
        // C3 with C = 1: the first align is a lock-in — the responder's evidence
        // may not drift beyond tolerance between BID and ALIGN. The γ-contraction
        // rule applies to clarify rounds 2..C (C > 1).
        if (dist1 > dist0 + cfg.alignTolerance)
            return outcome(Mode.MISMATCH, trace,
                "distance %.2f → %.2f: evidence drifted".format(dist0, dist1),
                origin = id, budgetLeft = budget.left)

        // 04 RESOLVE — the single generation, tier privacy gate (REQ-NF-02)
        if (winner.payload["node_class"] == "N3" && tier == 2 && !cfg.allowRemoteT2)
            return outcome(Mode.REJECTED, trace, "T2 → remote provider denied by policy",
                origin = id, budgetLeft = budget.left)
        val resolve = Packet(
            action = Action.RESOLVE, originId = id, queryId = winner.queryId,
            sender = id, gas = cfg.gas,
            payload = mapOf("query_vec" to bytesToLongs(qv), "query_text" to text),
        ).seal(signer)
        val reply = hub?.send(id, winner.sender, resolve)
        trace.add(mapOf("from" to winner.sender, "action" to "RESOLVE"))
        if (reply == null || reply.action == Action.RFO)
            return outcome(Mode.NO_QUORUM, trace, "winner could not generate",
                origin = id, budgetLeft = budget.left)

        // 05 VERIFY — firewall L2 (origin-side, hash-addressable) + reputation
        val answer = reply.payload["answer"].toString()
        @Suppress("UNCHECKED_CAST")
        val provChunks = (reply.payload["provenance"] as List<Map<String, Any?>>)
            .map { Evidence(it["chunk_hash"].toString(), it["text"].toString()) }
        val g = verifier.groundedness(answer, provChunks)
        val repBefore = reputation[winner.sender] ?: 0.5
        val out: Outcome
        if (g >= cfg.groundednessMin) {
            reputation[winner.sender] = minOf(1.0, repBefore + 0.05)
            out = outcome(Mode.RESOLVED, trace, answer = answer, origin = id, budgetLeft = budget.left)
        } else {
            reputation[winner.sender] = maxOf(0.0, repBefore - 0.20)
            out = outcome(Mode.REJECTED, trace,
                "groundedness %.2f < %.2f".format(g, cfg.groundednessMin),
                origin = id, budgetLeft = budget.left)
        }
        @Suppress("UNCHECKED_CAST")
        val cost = reply.payload["cost"] as? Map<String, Any?>
        return out.copy(groundedness = round3(g), cost = cost)
    }

    // -- helpers ----------------------------------------------------------------------

    private fun route(qv: ByteArray, k: Int, directory: Directory?): List<String> {
        if (directory != null) {
            val recs = directory.lookup(qv, topK = maxOf(k, 3)).filter { it.nodeId != id }
            // REQ-F-03: capability-aware routing — generation-capable classes
            // (N2/N3) are proposed first; N1 relays fill remaining slots.
            val sorted = recs.sortedBy { it.klass == "N1" }
            if (sorted.isNotEmpty()) return sorted.take(k).map { it.nodeId }
            // empty directory: fall back to the transport's own peers
        }
        return hub?.peers(id)?.take(k) ?: emptyList()
    }

    private fun diverseBids(bids: List<Packet>): List<Packet> {
        // C4: votes citing the same leading chunk_hash count once
        val seen = HashSet<String>()
        return bids.sortedByDescending { dbl(it.payload["bid"]) }
            .filter {
                val h = ((it.payload["provenance"] as List<*>).first() as Map<*, *>)["chunk_hash"].toString()
                seen.add(h)
            }
    }

    private fun outcome(
        mode: Mode, trace: List<Map<String, Any?>>, detail: String? = null,
        answer: String? = null, origin: String, budgetLeft: Int,
    ) = Outcome(mode = mode, answer = answer, detail = detail, trace = trace,
        origin = origin, budgetLeft = budgetLeft)

    fun attach(hub: Hub, verifier: D2Verifier) {
        this.hub = hub
        this.verifier = verifier
        hub.join(this)
    }

    companion object {
        fun queryVec(payload: Map<String, Any?>): ByteArray =
            (payload["query_vec"] as List<*>).map { (it as Number).toInt().toByte() }.toByteArray()

        fun bytesToLongs(b: ByteArray): List<Long> = b.map { it.toLong() and 0xFF }
    }
}
