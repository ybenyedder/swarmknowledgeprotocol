package com.swarmknowledge.ospnode

import com.swarmknowledge.osp.D3Provider
import com.swarmknowledge.osp.DevSigner
import com.swarmknowledge.osp.Evidence
import com.swarmknowledge.osp.HttpHub
import com.swarmknowledge.osp.Hub
import com.swarmknowledge.osp.LexicalVerifier
import com.swarmknowledge.osp.Node
import com.swarmknowledge.osp.NodeConfig
import com.swarmknowledge.osp.Outcome
import com.swarmknowledge.osp.PACKET_VERSION
import com.swarmknowledge.osp.Packet
import com.swarmknowledge.osp.RagStore
import com.swarmknowledge.osp.Signer

/**
 * A Linux OSP node — the headless counterpart of the Android app's
 * `OspService`: an N1 thin origin (never generates on its own behalf) that
 * relays to remote peers over HTTP plus a local N2 responder whose generation
 * rung is the configured [provider]. If no provider is usable the responder
 * cannot generate and every query honestly abstains (REQ-F-04).
 *
 * Same wiring, same wire: packets are sealed with the DEV-SIGNER exactly like
 * the tablet's, so `/osp/packet` interops with ospbridge, the whatsapp-bot and
 * the Python CLI.
 */
class LinuxNode(
    val id: String,
    private val provider: D3Provider?,
    private val signer: Signer = DevSigner(),
    val cfg: NodeConfig = NodeConfig(),
) {

    /** A configured remote peer: endpoint + optional link secret. */
    data class Peer(val url: String, val token: String? = null)

    val rag = RagStore()

    val responder: Node = Node(id, rag, provider, signer)
    val origin: Node = Node("origin-$id", RagStore(), signer = signer, klass = "N1")

    /** HTTP transport to the remote peers — rebuilt on [setPeers]. */
    private var remoteHub: Hub = HttpHub()

    /**
     * Origin transport: configured remotes go over HTTP and are candidates
     * FIRST (they are the reason the node peers at all); the local N2
     * responder fills the remaining slot — and is the whole swarm of one (T0,
     * clause 4.3) when no remote is configured (offline demo).
     */
    private val hub: Hub = object : Hub {
        override fun join(node: Node) {}
        override fun peers(nodeId: String): List<String> =
            remotes.keys.toList() + if (id != nodeId) listOf(id) else emptyList()
        override fun send(from: String, to: String, pkt: Packet): Packet? =
            if (to == id) responder.onPacket(pkt) else remoteHub.send(from, to, pkt)
    }

    /** Remote peer table (nodeId → Peer), populated by [setPeers]. */
    var remotes: Map<String, Peer> = emptyMap()
        private set

    /**
     * Link secret gating the HTTP bridge (Main sets it from --token, OSP_TOKEN
     * or a generated value). Transport auth only — it never signs packets
     * (root README, clause 6).
     */
    @Volatile lateinit var token: String

    /** Whether the generation rung can actually serve (REQ-F-04 abstention). */
    val providerAvailable: Boolean
        get() = provider?.available ?: false

    @Volatile var lastOutcome: Map<String, Any?>? = null
        private set

    init {
        responder.attach(hub, LexicalVerifier())
        origin.attach(hub, LexicalVerifier())
    }

    /** (Re)build the origin transport from the peer table. */
    fun setPeers(peers: Map<String, Peer>) {
        remotes = peers
        // read timeout must cover the REMOTE generation (ALIGN/RESOLVE round
        // trips against an LLM on a CPU box legitimately take minutes)
        val http = HttpHub(peers.mapValues { (_, p) -> HttpHub.Remote(p.url, p.token) })
        http.onError = { msg, err -> System.err.println("hub: $msg${err?.let { " — $it" } ?: ""}") }
        remoteHub = http
    }

    /** Add a chunk to the responder's store. */
    fun teach(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty()) return false
        rag.add(t)
        return true
    }

    /** Blocking negotiation from this node's origin (tier ∈ {0,1,2}). */
    fun submitQuery(text: String, tier: Int): Outcome =
        origin.query(text, tier.coerceIn(0, 2)).also { lastOutcome = it.toMap() }

    /** Label advertised in /v1/models and /osp/endpoint.json. */
    val providerName: String
        get() = provider?.name ?: "none"

    /** Raw, unverified generation passthrough for the OpenAI-compatible route. */
    fun generateRaw(prompt: String): String? {
        val d3 = provider ?: return null
        if (!d3.available) return null
        return d3.generate(prompt, emptyList<Evidence>()).answer
    }

    fun statusMap(httpPort: Int, uptimeS: Long): Map<String, Any?> = mapOf(
        "node_id" to id,
        "node_class" to responder.klass,
        "provider" to (provider?.name ?: "none"),
        "provider_available" to (provider?.available ?: false),
        "signer" to signer.label,
        "budget_left" to responder.budget.left,
        "chunks" to rag.entries.size,
        "http_port" to httpPort,
        "peers" to remotes.keys.toList(),
        "uptime_s" to uptimeS,
        "last_outcome" to lastOutcome,
        "packet_version" to PACKET_VERSION,
    )
}
