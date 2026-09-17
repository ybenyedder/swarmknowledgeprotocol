package com.swarmknowledge.ospbridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.SharedPreferences
import android.os.IBinder
import android.os.Build
import com.swarmknowledge.osp.DevSigner
import com.swarmknowledge.osp.Directory
import com.swarmknowledge.osp.HttpHub
import com.swarmknowledge.osp.InMemoryHub
import com.swarmknowledge.osp.LexicalVerifier
import com.swarmknowledge.osp.MiniJson
import com.swarmknowledge.osp.Mode
import com.swarmknowledge.osp.Node
import com.swarmknowledge.osp.Outcome
import com.swarmknowledge.osp.PACKET_VERSION
import com.swarmknowledge.osp.RagStore
import com.swarmknowledge.osp.newId
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * OSP-Lite foreground service (osp_lite_v05.html §5).
 *
 * Runs the full protocol on-device as a two-node negotiation over an
 * [InMemoryHub]: an N1 thin origin (this process never generates on its own
 * behalf) and an N2 full responder whose generation rung is the on-device
 * LLMProvider engine through [LlmD3Provider]. If LLMProvider is not installed,
 * the responder cannot generate and every query honestly abstains (REQ-F-04).
 *
 * Surfaces:
 *  - AIDL `IOspService` for host apps on this device (harnessDroid) — §5 contract
 *  - HTTP bridge on the LAN ([HttpBridge]) for the PC MCP server / whatsapp-bot
 */
class OspService : Service() {

    lateinit var bridge: LlmBridge
        private set
    lateinit var hub: InMemoryHub
        private set
    lateinit var rag: RagStore
        private set
    lateinit var directory: Directory
        private set
    lateinit var signer: DevSigner
        private set

    var origin: Node? = null
        private set
    var responder: Node? = null
        private set
    var startedAt: Long = 0
        private set
    var lastOutcome: Map<String, Any?>? = null
    var httpPort: Int = PORT_DEFAULT
        private set

    private val callbacks = HashMap<String, IOspCallback>()
    private val pool: ExecutorService = Executors.newCachedThreadPool()
    private var http: HttpBridge? = null
    private lateinit var prefs: SharedPreferences
    private val advertised = ArrayList<String>()
    private var originHub: HttpHub? = null

    /** A configured remote peer: endpoint + optional link secret. */
    data class Peer(val url: String, val token: String? = null)

    /**
     * Remote peer table (nodeId → Peer), persisted in prefs. Accepts both the
     * compact form {"id": "http://…"} and {"id": {"url": "…", "token": "…"}}.
     */
    val remotes: Map<String, Peer>
        get() {
            @Suppress("UNCHECKED_CAST")
            val raw = prefs.getString("osp_peers", null)
                ?.let { MiniJson.parse(it) as? Map<String, Any?> }
                ?: return emptyMap()
            return raw.entries.associate { (id, v) ->
                when (v) {
                    is Map<*, *> -> id.toString().let {
                        it to Peer(v["url"].toString(), v["token"]?.toString())
                    }
                    else -> id.toString() to Peer(v.toString())
                }
            }
        }

    val token: String
        get() = prefs.getString("token", null) ?: newId(16).also {
            prefs.edit().putString("token", it).apply()
        }

    val nodeId: String
        get() = prefs.getString("node_id", null) ?: ("osp-" + newId(8)).also {
            prefs.edit().putString("node_id", it).apply()
        }

    override fun onCreate() {
        super.onCreate()
        bridge = LlmBridge(this)
        prefs = getSharedPreferences("ospbridge", MODE_PRIVATE)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startInForeground()
        startedAt = System.currentTimeMillis()
        signer = DevSigner()
        rag = RagStore(prefs.getStringSet("taught", emptySet())!!.sorted())
        hub = InMemoryHub()
        directory = Directory()
        // N1 thin origin — the service never generates on its own behalf; it
        // relays to remote OSP peers over HTTP, and only to the local N2 when
        // no peer is configured (offline demo).
        origin = Node("origin-$nodeId", RagStore(), signer = signer)
        // N2 full responder — generation via the on-device engine only
        responder = Node(nodeId, rag, LlmD3Provider(bridge), signer = signer)
        responder!!.attach(hub, LexicalVerifier())
        rebuildOriginHub()
        bridge.bind()
        if (http == null) {
            http = HttpBridge(this).also { it.start() }
            httpPort = http!!.port
        }
        return START_STICKY
    }

    override fun onDestroy() {
        http?.stop()
        bridge.unbind()
        pool.shutdownNow()
        instance = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    // -- local operations (HTTP bridge + activity) --------------------------------

    /** Add a chunk to the responder's store and persist it. */
    fun teach(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty()) return false
        rag.add(t)
        prefs.edit().putStringSet("taught", rag.entries.map { it.text }.toSet()).apply()
        return true
    }

    /** (Re)build the origin's transport from the persisted peer table. */
    fun rebuildOriginHub() {
        // read timeout must cover the REMOTE generation (ALIGN/RESOLVE round
        // trips against an LLM can take tens of seconds)
        val h = HttpHub(
            remotes.mapValues { (_, p) -> HttpHub.Remote(p.url, p.token) },
            connectTimeoutMs = 5_000,
            readTimeoutMs = 300_000,
        )
        h.onError = { msg, err ->
            android.util.Log.w(TAG, msg, err)
        }
        origin?.attach(h, LexicalVerifier())
        originHub = h
    }

    /** Update the remote peer table and persist it. */
    fun setPeers(peers: Map<String, Peer>) {
        prefs.edit().putString(
            "osp_peers",
            MiniJson.write(peers.mapValues { (_, p) ->
                if (p.token == null) p.url else mapOf("url" to p.url, "token" to p.token)
            }),
        ).apply()
        rebuildOriginHub()
    }

    /** Blocking single-hop negotiation: origin N1 → responder N2 (local hub). */
    fun submitQueryLocal(text: String, tier: Int): Outcome? =
        origin?.query(text, tier.coerceIn(0, 2), directory)

    fun statusMap(): Map<String, Any?> = mapOf(
        "node_id" to nodeId,
        "node_class" to responder?.klass,
        "llmprovider_bound" to bridge.connected,
        "llmprovider_version" to bridge.providerVersion(),
        "embedding_dim" to bridge.embeddingDim(),
        "context_length" to bridge.contextLength(),
        "budget_left" to (responder?.budget?.left ?: 0),
        "chunks" to rag.entries.size,
        "http_port" to httpPort,
        "peers" to remotes.keys.toList(),
        "uptime_s" to if (startedAt == 0L) 0 else (System.currentTimeMillis() - startedAt) / 1000,
        "last_outcome" to lastOutcome,
        "packet_version" to PACKET_VERSION,
    )

    fun statusSummary(): String {
        val s = statusMap()
        return """
            node        : ${s["node_id"]} (${s["node_class"]})
            LLMProvider : ${if (s["llmprovider_bound"] == true) "bound v${s["llmprovider_version"]}" else "not bound"}
            embedding   : dim=${s["embedding_dim"]}  context=${s["context_length"]}
            knowledge   : ${s["chunks"]} chunks · budget left ${s["budget_left"]}
            HTTP        : port ${s["http_port"]} · token ${token.take(6)}…
        """.trimIndent()
    }

    // -- forenseek menu ----------------------------------------------------------------

    /**
     * Forenseek "Log all": complete diagnostic dump of this node. Link secrets
     * (own + peers) are truncated to their 6-char prefix, so the dump is safe
     * to read on screen, write to a file or share for support.
     */
    fun dumpInfo(): String {
        val s = statusMap()
        val pkg = applicationContext.packageManager.getPackageInfo(applicationContext.packageName, 0)
        val vcode = if (Build.VERSION.SDK_INT >= 28) pkg.longVersionCode
        else @Suppress("DEPRECATION") pkg.versionCode.toLong()
        val peers = remotes.entries.joinToString("\n") { (id, p) ->
            val tok = p.token?.let { it.take(6) + "…" } ?: "none"
            "  $id · ${p.url} · token $tok"
        }.ifEmpty { "  (none)" }
        val chunks = rag.entries.mapIndexed { i, e ->
            val t = e.text.replace("\n", " ")
            "  [$i] " + if (t.length > 160) t.take(160) + "…" else t
        }.joinToString("\n").ifEmpty { "  (none)" }
        return buildString {
            appendLine("== forenseek dump ==")
            appendLine("app        : v${pkg.versionName} ($vcode, ${applicationContext.packageName})")
            appendLine("protocol   : OSP v${s["packet_version"]}")
            appendLine("node       : ${s["node_id"]} (${s["node_class"]}) · uptime ${s["uptime_s"]}s")
            appendLine("HTTP       : port ${s["http_port"]} · link secret ${token.take(6)}…")
            appendLine("LLMProvider: ${if (s["llmprovider_bound"] == true) "bound v${s["llmprovider_version"]}" else "not bound"}")
            appendLine("embedding  : dim=${s["embedding_dim"]} context=${s["context_length"]}")
            appendLine("budget     : ${s["budget_left"]} verified queries left today")
            appendLine("last mode  : ${(lastOutcome?.get("mode")) ?: "(none yet)"}")
            appendLine("peers (${remotes.size}):")
            appendLine(peers)
            appendLine("knowledge (${s["chunks"]} chunks):")
            appendLine(chunks)
        }
    }

    /**
     * Forenseek "Reset all": wipe the node identity, link secret, peer table
     * and taught knowledge. The caller stops the service right after — the
     * next START NODE provisions a fresh node.
     */
    fun resetAll() {
        http?.stop()
        prefs.edit().clear().commit()
        rag = RagStore(emptyList())
        origin = null
        responder = null
        lastOutcome = null
        startedAt = 0
        httpPort = PORT_DEFAULT
        originHub = null
    }

    // -- AIDL surface (osp_lite_v05.html §5) ----------------------------------------

    private val binder = object : IOspService.Stub() {
        override fun submitQuery(text: String?, stakesTier: Int): String? {
            if (text.isNullOrBlank()) return null
            val qid = newId(12)
            pool.execute {
                val out = try {
                    submitQueryLocal(text, stakesTier)
                } catch (e: Exception) {
                    null
                }
                lastOutcome = out?.toMap()
                lastQueryIdFor(qid)
                val report = MiniJson.write(
                    out?.toMap() ?: mapOf<String, Any?>("mode" to Mode.NO_QUORUM.value))
                synchronized(callbacks) { callbacks[qid] }?.onOutcome(
                    qid,
                    out?.mode?.ordinal ?: Mode.NO_QUORUM.ordinal,
                    out?.answer ?: "",
                    report.toByteArray(Charsets.UTF_8),
                )
            }
            return qid
        }

        override fun registerCallback(queryId: String?, cb: IOspCallback?) {
            if (queryId != null && cb != null) synchronized(callbacks) { callbacks[queryId] = cb }
        }

        override fun advertiseCentroid(cborCodebook: ByteArray?) {
            // dev: recorded for status; the v0.5 CBOR codebook lands with mesh transport
            if (cborCodebook != null) advertised.add(cborCodebook.size.toString() + "B")
        }

        override fun fetchKeyBundle(nodeId: String?): ByteArray? =
            MiniJson.write(
                mapOf<String, Any?>(
                    "signing" to signer.label,
                    "node" to this@OspService.nodeId,
                    "prekey" to "dev-none",
                ),
            ).toByteArray(Charsets.UTF_8)
    }

    private fun lastQueryIdFor(qid: String) {
        lastQueryId = qid
    }

    private var lastQueryId: String? = null

    // -- foreground plumbing ----------------------------------------------------------

    private fun startInForeground() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "OSP Bridge", NotificationManager.IMPORTANCE_LOW))
        }
        val builder = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION") Notification.Builder(this)
        }
        val n: Notification = builder
            .setContentTitle("OSP Bridge")
            .setContentText("Knowledge node active · HTTP :$httpPort")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .build()
        startForeground(NOTIFICATION_ID, n)
    }

    companion object {
        private const val TAG = "OspService"
        const val CHANNEL_ID = "osp-bridge"
        const val NOTIFICATION_ID = 1
        const val PORT_DEFAULT = 8090

        /** Pragmatic handle for the activity / tests; cleared in onDestroy. */
        @Volatile
        var instance: OspService? = null
    }

    init {
        instance = this
    }
}
