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
    private val remotes: Map<String, Remote> = emptyMap(),
    private val connectTimeoutMs: Int = 3000,
    private val readTimeoutMs: Int = 15000,
) : Hub {

    /** A remote peer endpoint and the shared link secret (optional). */
    data class Remote(val url: String, val token: String? = null)

    private val locals = LinkedHashMap<String, Node>()

    /** Diagnostic hook — the hub stays zero-dependency (no logging lib). */
    var onError: ((String, Exception?) -> Unit)? = null

    override fun join(node: Node) {
        node.hub = this
        locals[node.id] = node
    }

    /** Candidates for routing: local nodes first, then configured remote ids. */
    override fun peers(nodeId: String): List<String> =
        locals.keys.filter { it != nodeId } + remotes.keys.filter { it !in locals }

    override fun send(from: String, to: String, pkt: Packet): Packet? {
        locals[to]?.let { return it.onPacket(pkt) }
        val remote = remotes[to] ?: return null
        val url = if (remote.url.endsWith("/osp/packet")) remote.url
        else remote.url.trimEnd('/') + "/osp/packet"
        return try {
            onError?.invoke("POST $url", null)
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = connectTimeoutMs
            conn.readTimeout = readTimeoutMs
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            remote.token?.let {
                // both spellings so either server-side check (x-api-token or
                // Authorization Bearer) accepts the same link secret
                conn.setRequestProperty("x-api-token", it)
                conn.setRequestProperty("Authorization", "Bearer $it")
            }
            conn.outputStream.use { it.write(MiniJson.write(pkt.toWire()).toByteArray()) }
            val code = conn.responseCode
            if (code != 200 && code != 204)
                onError?.invoke("HTTP $code from $url", IOException(conn.responseMessage))
            if (code == 204) return null                       // forged/replayed — silent
            val body = (if (code == 200) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.readText()
            @Suppress("UNCHECKED_CAST")
            val wire = MiniJson.parse(body ?: return null) as? Map<String, Any?> ?: return null
            if (wire.isEmpty()) null else Packet.fromWire(wire)
        } catch (e: Exception) {
            onError?.invoke("hub send to $to failed", e)       // honest failure, never silent
            null
        }
    }
}
