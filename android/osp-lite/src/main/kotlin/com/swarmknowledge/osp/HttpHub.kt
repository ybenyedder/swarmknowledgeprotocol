package com.swarmknowledge.osp

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Transport that posts sealed packets to remote nodes' `/osp/packet` endpoints —
 * the ospbridge app, whatsapp-bot and any other OSP node become peers over
 * plain HTTP (the bot's JS `HttpHub` is the exact counterpart). Local nodes
 * joined via [join] are routed in-process; every other id is resolved through
 * [remotes] (`nodeId → base URL`) and sent over HTTP.
 *
 * Blocking I/O by design: OSP negotiations already run on worker threads
 * (query() is a synchronous round-trip per hop).
 */
class HttpHub(
    private val remotes: Map<String, String> = emptyMap(),
    private val connectTimeoutMs: Int = 3000,
    private val readTimeoutMs: Int = 15000,
) : Hub {

    private val locals = LinkedHashMap<String, Node>()

    override fun join(node: Node) {
        node.hub = this
        locals[node.id] = node
    }

    /** Candidates for routing: local nodes first, then configured remote ids. */
    override fun peers(nodeId: String): List<String> =
        locals.keys.filter { it != nodeId } + remotes.keys.filter { it !in locals }

    override fun send(from: String, to: String, pkt: Packet): Packet? {
        locals[to]?.let { return it.onPacket(pkt) }
        val url = urlFor(to) ?: return null
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = connectTimeoutMs
            conn.readTimeout = readTimeoutMs
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(MiniJson.write(pkt.toWire()).toByteArray()) }
            if (conn.responseCode == 204) return null          // forged/replayed — silent
            val body = conn.inputStream.bufferedReader().readText()
            @Suppress("UNCHECKED_CAST")
            val wire = MiniJson.parse(body) as? Map<String, Any?> ?: return null
            if (wire.isEmpty()) null else Packet.fromWire(wire)
        } catch (e: IOException) {
            null                                               // unreachable peer — honest failure
        }
    }

    private fun urlFor(nodeId: String): String? = remotes[nodeId]?.let { base ->
        if (base.endsWith("/osp/packet")) base else base.trimEnd('/') + "/osp/packet"
    }
}
