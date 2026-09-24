package com.swarmknowledge.ospnode

import com.swarmknowledge.osp.MiniJson
import com.swarmknowledge.osp.Packet
import java.io.InputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * LAN HTTP bridge for a Linux OSP node — same hand-rolled ServerSocket server
 * as the Android app's `HttpBridge`, same routes (so an ospbridge tablet, the
 * whatsapp-bot and this node all peer over the identical wire), plus a browser
 * console served at `/`.
 *
 * Routes (all require the link token under either spelling —
 * `Authorization: Bearer` or `x-api-token` — except / and /osp/status):
 *   GET  /                    web console (static HTML, no secrets)
 *   GET  /osp/status           node + provider status (no secrets)
 *   GET  /osp/endpoint.json    contract consumed by the PC LLMProviderFileAdapter
 *   GET  /v1/models            OpenAI-compatible model list
 *   POST /v1/chat/completions  OpenAI-compatible RAW passthrough to the provider
 *                              (NOT OSP-verified — the caller owns grounding)
 *   POST /osp/query            {query, tier} → verified OSP negotiation outcome
 *   POST /osp/packet           sealed OSP packet → responder's reply packet
 *   POST /osp/teach            {text} → add a knowledge chunk
 *   GET  /osp/peers            current remote peer table
 *   POST /osp/peers            {"peers": {nodeId: url | {url, token}}} → update
 *
 * /osp/packet is the protocol-native surface: any OSP node in any language
 * negotiates with this one by exchanging sealed packets — no shared
 * implementation, shared wire only.
 */
class NodeServer(
    private val node: LinuxNode,
    val port: Int = PORT_DEFAULT,
) {

    private var server: ServerSocket? = null
    private val pool: ExecutorService = Executors.newCachedThreadPool()

    @Volatile private var running = false
    private val startedAt = System.currentTimeMillis()

    /** The port actually bound (arguably port 0 → the ephemeral port). */
    val boundPort: Int
        get() = server?.localPort ?: port

    fun start() {
        running = true
        pool.execute {
            try {
                server = ServerSocket(port, 50, InetAddress.getByName("0.0.0.0"))
                while (running) {
                    val sock = server!!.accept()
                    pool.execute { handle(sock) }
                }
            } catch (e: Exception) {
                if (running) System.err.println("http server stopped: $e")
            }
        }
    }

    fun stop() {
        running = false
        try {
            server?.close()
        } catch (_: Exception) {
        }
        pool.shutdownNow()
    }

    private fun handle(sock: Socket) {
        try {
            sock.use { s ->
                // /osp/query relays to remote peers whose CPU generation
                // legitimately takes minutes — the socket must outlive them
                s.soTimeout = 330_000
                // Request line, headers AND body are read as RAW BYTES off one
                // stream: Content-Length counts bytes, so a char-decoding reader
                // would block forever on multi-byte UTF-8 bodies (é = 2 bytes,
                // 1 char) — the exact hang recorded as REQ-A-05 on the app.
                val ins = s.getInputStream()
                val headBytes = ArrayList<Byte>(512)
                while (true) {
                    val b = ins.read()
                    if (b < 0) return
                    headBytes.add(b.toByte())
                    val z = headBytes.size
                    if (z >= 4 && headBytes[z - 1] == LF && headBytes[z - 2] == CR &&
                        headBytes[z - 3] == LF && headBytes[z - 4] == CR
                    ) break
                }
                val head = String(headBytes.toByteArray(), Charsets.US_ASCII)
                val eol = head.indexOf("\r\n")
                if (eol < 0) return
                val parts = head.substring(0, eol).split(" ")
                if (parts.size < 2) return
                val method = parts[0]
                val path = parts[1].substringBefore('?')
                val headers = HashMap<String, String>()
                for (line in head.substring(eol + 2).split("\r\n")) {
                    val i = line.indexOf(':')
                    if (i > 0) headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
                }
                val body = headers["content-length"]?.toIntOrNull()?.let { n -> readBody(ins, n) } ?: ""

                // Peers send the link secret under either header spelling
                // (HttpHub sets both); the packet HMAC is no gate — it uses the
                // public dev secret — so the token is the only real auth.
                val open = path == "/" || path == "/osp/status"
                val authorized = headers["authorization"] == "Bearer ${node.token}" ||
                    headers["x-api-token"] == node.token
                if (!authorized && !open) {
                    respond(s, 401, mapOf("error" to "unauthorized"))
                    return
                }

                when {
                    method == "GET" && path == "/" ->
                        respondHtml(s, 200, ConsolePage.html)
                    method == "GET" && path == "/osp/status" ->
                        respond(s, 200, node.statusMap(boundPort, uptimeS()))
                    method == "GET" && path == "/osp/endpoint.json" ->
                        respond(s, 200, endpointJson())
                    method == "GET" && path == "/v1/models" ->
                        respond(s, 200, models())
                    method == "POST" && path == "/v1/chat/completions" ->
                        respond(s, 200, completions(body))
                    method == "POST" && path == "/osp/query" ->
                        respond(s, 200, queryOutcome(body))
                    method == "POST" && path == "/osp/packet" ->
                        respond(s, 200, packetReply(body))
                    method == "POST" && path == "/osp/teach" ->
                        respond(s, 200, teachChunk(body))
                    method == "GET" && path == "/osp/peers" ->
                        respond(s, 200, peersPayload())
                    method == "POST" && path == "/osp/peers" ->
                        respond(s, 200, setPeers(body))
                    else -> respond(s, 404, mapOf("error" to "not found", "path" to path))
                }
            }
        } catch (e: Exception) {
            System.err.println("request failed: $e")
            try {
                respond(sock, 500, mapOf("error" to (e.message ?: e.javaClass.simpleName)))
            } catch (_: Exception) {
            }
        }
    }

    /** Body decoded ONCE at the end — counts octets, not decoded characters. */
    private fun readBody(ins: InputStream, n: Int): String {
        val buf = ByteArray(n)
        var read = 0
        while (read < n) {
            val r = ins.read(buf, read, n - read)
            if (r < 0) break
            read += r
        }
        return String(buf, 0, read, Charsets.UTF_8)
    }

    // -- route bodies (pure: parse → node call → payload) -------------------------

    /** The verified path: full local negotiation, firewall applied (REQ-F-02). */
    private fun queryOutcome(body: String): Map<String, Any?> {
        val req = MiniJson.parse(body) as Map<*, *>
        // "query" is the contract used by osp_cli.py and osp-js; "text" kept as
        // the original bridge spelling
        val text = (req["query"] ?: req["text"])?.toString()
            ?: throw IllegalArgumentException("query (string) required")
        val tier = (req["tier"] as? Number)?.toInt() ?: 1
        val out = node.submitQuery(text, tier)
        return mapOf("outcome" to out.toMap(), "node" to node.id)
    }

    /** Protocol-native peering: sealed packet in, sealed reply out. */
    private fun packetReply(body: String): Map<String, Any?> {
        val wire = MiniJson.parse(body) as? Map<*, *>
            ?: throw IllegalArgumentException("packet must be an object")
        val pkt = Packet.fromWire(wire.entries.associate { it.key.toString() to it.value })
        // forged/replayed packets yield no reply — an empty object, not a 404
        return node.responder.onPacket(pkt)?.toWire() ?: emptyMap()
    }

    private fun teachChunk(body: String): Map<String, Any?> {
        val req = MiniJson.parse(body) as Map<*, *>
        val text = req["text"]?.toString() ?: throw IllegalArgumentException("text required")
        val ok = node.teach(text)
        return mapOf("ok" to ok, "chunks" to node.rag.entries.size)
    }

    private fun peersPayload(): Map<String, Any?> =
        node.remotes.mapValues { (_, p) ->
            if (p.token == null) p.url
            else mapOf("url" to p.url, "token" to p.token)
        }

    /**
     * Peer table update: {"peers": {"node-id": "http://host:port"}} or
     * {"peers": {"node-id": {"url": "http://host:port", "token": "…"}}} —
     * the same shape the Android app and osp_cli.py already speak.
     */
    private fun setPeers(body: String): Map<String, Any?> {
        val req = MiniJson.parse(body) as? Map<*, *> ?: throw IllegalArgumentException("body must be an object")
        @Suppress("UNCHECKED_CAST")
        val raw = req["peers"] as? Map<String, Any?>
            ?: throw IllegalArgumentException("peers object required")
        val peers = raw.entries.associate { (id, v) ->
            when (v) {
                is Map<*, *> -> id to LinuxNode.Peer(v["url"].toString(), v["token"]?.toString())
                else -> id to LinuxNode.Peer(v.toString())
            }
        }
        node.setPeers(peers)
        return mapOf("ok" to true, "peers" to node.remotes.keys.toList())
    }

    private fun models(): Map<String, Any?> = mapOf(
        "object" to "list",
        "data" to listOf(
            mapOf("id" to node.providerName, "object" to "model", "owned_by" to "ospnode"),
        ),
    )

    /** Raw generation passthrough (unverified path — caller owns grounding). */
    private fun completions(body: String): Map<String, Any?> {
        val req = MiniJson.parse(body) as Map<*, *>
        val messages = req["messages"] as? List<*> ?: emptyList<Any?>()
        val prompt = messages.filterIsInstance<Map<*, *>>()
            .lastOrNull { it["role"] == "user" }?.get("content")?.toString()
            ?: throw IllegalArgumentException("no user message")
        val text = node.generateRaw(prompt)
            ?: throw IllegalStateException("provider not available")
        return mapOf(
            "id" to ("chatcmpl-osp-" + com.swarmknowledge.osp.newId(12)),
            "object" to "chat.completion",
            "model" to node.providerName,
            "choices" to listOf(
                mapOf(
                    "index" to 0,
                    "message" to mapOf("role" to "assistant", "content" to text),
                    "finish_reason" to "stop",
                ),
            ),
        )
    }

    private fun endpointJson(): Map<String, Any?> = mapOf(
        "base_url" to "http://${lanAddress()}:$boundPort/v1",
        "model" to node.providerName,
        "api_key_env" to "OSP_TOKEN",
        "osp_packet_url" to "http://${lanAddress()}:$boundPort/osp/packet",
        "osp_node_id" to node.id,
    )

    // -- plumbing ----------------------------------------------------------------

    private fun respond(sock: Socket, code: Int, payload: Any?) {
        val body = MiniJson.write(payload)
        val bytes = body.toByteArray(Charsets.UTF_8)
        val head = "HTTP/1.1 $code ${reason(code)}\r\n" +
            "Content-Type: application/json\r\n" +
            "Content-Length: ${bytes.size}\r\n" +
            "Connection: close\r\n\r\n"
        sock.getOutputStream().apply {
            write(head.toByteArray(Charsets.UTF_8))
            write(bytes)
            flush()
        }
    }

    private fun respondHtml(sock: Socket, code: Int, html: String) {
        val bytes = html.toByteArray(Charsets.UTF_8)
        val head = "HTTP/1.1 $code ${reason(code)}\r\n" +
            "Content-Type: text/html; charset=utf-8\r\n" +
            "Content-Length: ${bytes.size}\r\n" +
            "Connection: close\r\n\r\n"
        sock.getOutputStream().apply {
            write(head.toByteArray(Charsets.UTF_8))
            write(bytes)
            flush()
        }
    }

    private fun reason(code: Int) = when (code) {
        200 -> "OK"; 204 -> "No Content"; 401 -> "Unauthorized"
        404 -> "Not Found"; 500 -> "Internal Server Error"
        else -> "OK"
    }

    private fun uptimeS(): Long = (System.currentTimeMillis() - startedAt) / 1000

    private fun lanAddress(): String =
        NetworkInterface.getNetworkInterfaces().asSequence()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.asSequence() }
            .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }
            ?.hostAddress ?: "127.0.0.1"

    companion object {
        const val PORT_DEFAULT = 8090
        private const val CR: Byte = 13
        private const val LF: Byte = 10
    }
}
