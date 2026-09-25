package com.swarmknowledge.ospbridge

import com.swarmknowledge.osp.Ed25519Signer
import com.swarmknowledge.osp.MiniJson
import com.swarmknowledge.osp.Packet
import java.io.InputStream
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Minimal LAN HTTP bridge for the OSP node — hand-rolled on ServerSocket, no
 * third-party server (REQ-NF-01 spirit, no extra AARs on a constrained device).
 *
 * Routes (all require the link token under either spelling —
 * `Authorization: Bearer` or `x-api-token` — except /osp/status):
 *   GET  /osp/status           node + engine status (no secrets)
 *   GET  /osp/endpoint.json    contract consumed by the PC LLMProviderFileAdapter
 *   GET  /v1/models            OpenAI-compatible model list
 *   POST /v1/chat/completions  OpenAI-compatible RAW passthrough to LLMProvider
 *                              (NOT OSP-verified — the caller owns grounding)
 *   POST /osp/query            {query, tier} → verified OSP negotiation outcome
 *   POST /osp/packet           sealed OSP packet → responder's reply packet
 *   POST /osp/teach            {text} → add a knowledge chunk
 *   GET  /osp/peers            current remote peer table
 *   POST /osp/peers            {"peers": {nodeId: baseUrl}} → update + persist
 *
 * /osp/packet is the protocol-native surface: any OSP node in any language
 * (Python reference, osp-js in whatsapp-bot) can negotiate with this device
 * by exchanging sealed packets — no shared implementation, shared wire only.
 */
class HttpBridge(private val service: OspService, val port: Int = OspService.PORT_DEFAULT) {

    private var server: ServerSocket? = null
    private val pool: ExecutorService = Executors.newCachedThreadPool()

    @Volatile private var running = false

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
                if (running) android.util.Log.e(TAG, "http server stopped", e)
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
                s.soTimeout = 180_000
                // Request line, headers AND body are read as RAW BYTES off one
                // stream: Content-Length counts bytes, so a char-decoding reader
                // would block forever on multi-byte UTF-8 bodies (é = 2 bytes,
                // 1 char) — non-ASCII queries hung exactly there until timeout.
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
                // (HttpHub sets both): accept both instead of opening any
                // route — /osp/packet included — to unauthenticated traffic.
                // Packet HMAC alone is no gate: it uses the public dev secret.
                val authorized = headers["authorization"] == "Bearer ${service.token}" ||
                    headers["x-api-token"] == service.token
                if (!authorized && path != "/osp/status") {
                    respond(s, 401, mapOf("error" to "unauthorized"))
                    return
                }

                val payload: Any? = when {
                    method == "GET" && path == "/osp/status" -> service.statusMap()
                    method == "GET" && path == "/osp/endpoint.json" -> endpointJson()
                    method == "GET" && path == "/v1/models" -> models()
                    method == "POST" && path == "/v1/chat/completions" -> completions(body)
                    method == "POST" && path == "/osp/query" -> ospQuery(body)
                    method == "POST" && path == "/osp/packet" -> ospPacket(body)
                    method == "POST" && path == "/osp/teach" -> teach(body)
                    method == "GET" && path == "/osp/peers" ->
                        service.remotes.mapValues { (_, p) ->
                            if (p.token == null) p.url
                            else mapOf("url" to p.url, "token" to p.token)
                        }
                    method == "POST" && path == "/osp/peers" -> setPeers(body)
                    else -> null
                }
                if (payload == null && path != "/osp/status" && path != "/osp/endpoint.json") {
                    respond(s, 404, mapOf("error" to "not found", "path" to path))
                } else {
                    respond(s, 200, payload ?: mapOf<String, Any?>())
                }
            }
        } catch (e: Exception) {
            android.util.Log.w(TAG, "request failed", e)
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

    // -- routes -----------------------------------------------------------------

    private fun models(): Map<String, Any?> = mapOf(
        "object" to "list",
        "data" to listOf(
            mapOf("id" to "llmprovider", "object" to "model", "owned_by" to "ospbridge"),
        ),
    )

    /** Raw generation passthrough (unverified path — caller owns grounding). */
    private fun completions(body: String): Map<String, Any?> {
        val req = MiniJson.parse(body) as Map<*, *>
        val messages = req["messages"] as? List<*> ?: emptyList<Any?>()
        val prompt = messages.filterIsInstance<Map<*, *>>()
            .lastOrNull { it["role"] == "user" }?.get("content")?.toString()
            ?: throw IllegalArgumentException("no user message")
        val text = service.bridge.generate(prompt)
        return mapOf(
            "id" to ("chatcmpl-osp-" + com.swarmknowledge.osp.newId(12)),
            "object" to "chat.completion",
            "model" to "llmprovider",
            "choices" to listOf(
                mapOf(
                    "index" to 0,
                    "message" to mapOf("role" to "assistant", "content" to text),
                    "finish_reason" to "stop",
                ),
            ),
        )
    }

    /** The verified path: full local negotiation, firewall applied (REQ-F-02). */
    private fun ospQuery(body: String): Map<String, Any?> {
        val req = MiniJson.parse(body) as Map<*, *>
        // "query" is the contract used by osp_cli.py and osp-js; "text" kept as
        // the original bridge spelling
        val text = (req["query"] ?: req["text"])?.toString()
            ?: throw IllegalArgumentException("query (string) required")
        val tier = (req["tier"] as? Number)?.toInt() ?: 1
        val out = service.submitQueryLocal(text, tier)
            ?: throw IllegalStateException("service not ready")
        return mapOf("outcome" to out.toMap(), "node" to service.nodeId)
    }

    /** Protocol-native peering: sealed packet in, sealed reply out. */
    private fun ospPacket(body: String): Any? {
        val wire = MiniJson.parse(body) as Map<*, *>
        val pkt = Packet.fromWire(wire.entries.associate { it.key.toString() to it.value })
        val reply = service.responder?.onPacket(pkt)
        // forged/replayed packets yield no reply — an empty object, not a 404
        return reply?.toWire() ?: emptyMap<String, Any?>()
    }

    /**
     * Peer table update: {"peers": {"node-id": "http://host:port"}} or
     * {"peers": {"node-id": {"url": "http://host:port", "token": "…"}}}.
     */
    private fun setPeers(body: String): Map<String, Any?> {
        val req = MiniJson.parse(body) as? Map<*, *> ?: throw IllegalArgumentException("body must be an object")
        @Suppress("UNCHECKED_CAST")
        val raw = req["peers"] as? Map<String, Any?>
            ?: throw IllegalArgumentException("peers object required")
        val peers = raw.entries.associate { (id, v) ->
            when (v) {
                is Map<*, *> -> id to OspService.Peer(
                    v["url"].toString(), v["token"]?.toString())
                else -> id to OspService.Peer(v.toString())
            }
        }
        service.setPeers(peers)
        return mapOf("ok" to true, "peers" to service.remotes.keys.toList())
    }

    private fun teach(body: String): Map<String, Any?> {
        val req = MiniJson.parse(body) as Map<*, *>
        val text = req["text"]?.toString() ?: throw IllegalArgumentException("text required")
        val ok = service.teach(text)
        return mapOf("ok" to ok, "chunks" to service.rag.entries.size)
    }

    private fun endpointJson(): Map<String, Any?> = mapOf(
        "base_url" to "http://${lanAddress()}:$port/v1",
        "model" to "llmprovider",
        "api_key_env" to "OSP_BRIDGE_TOKEN",
        "osp_packet_url" to "http://${lanAddress()}:$port/osp/packet",
        "osp_node_id" to service.nodeId,
        // the bot's TOFU bootstrap (PinStore) reads key_bundle + node_id from
        // this record and pins it on first sight (REQ-S-02)
        "node_id" to service.nodeId,
        "key_bundle" to (service.signer as? Ed25519Signer)?.keyBundle(),
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

    private fun reason(code: Int) = when (code) {
        200 -> "OK"; 204 -> "No Content"; 401 -> "Unauthorized"
        404 -> "Not Found"; 500 -> "Internal Server Error"
        else -> "OK"
    }

    private fun lanAddress(): String =
        NetworkInterface.getNetworkInterfaces().asSequence()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.asSequence() }
            .firstOrNull { it is java.net.Inet4Address && it.isSiteLocalAddress }
            ?.hostAddress ?: "127.0.0.1"

    companion object {
        private const val TAG = "HttpBridge"
        private const val CR: Byte = 13
        private const val LF: Byte = 10
    }
}
