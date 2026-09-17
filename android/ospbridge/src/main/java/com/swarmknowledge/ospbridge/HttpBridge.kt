package com.swarmknowledge.ospbridge

import com.swarmknowledge.osp.MiniJson
import com.swarmknowledge.osp.Packet
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
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
 * Routes (all require `Authorization: Bearer <token>` except /osp/status):
 *   GET  /osp/status           node + engine status (no secrets)
 *   GET  /osp/endpoint.json    contract consumed by the PC LLMProviderFileAdapter
 *   GET  /v1/models            OpenAI-compatible model list
 *   POST /v1/chat/completions  OpenAI-compatible RAW passthrough to LLMProvider
 *                              (NOT OSP-verified — the caller owns grounding)
 *   POST /osp/query            {text, tier} → verified OSP negotiation outcome
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
                val reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
                val requestLine = reader.readLine() ?: return
                val parts = requestLine.split(" ")
                if (parts.size < 2) return
                val method = parts[0]
                val path = parts[1].substringBefore('?')
                val headers = HashMap<String, String>()
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                    val i = line.indexOf(':')
                    if (i > 0) headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
                }
                val body = headers["content-length"]?.toIntOrNull()?.let { n -> readBody(reader, n) } ?: ""

                val authorized = headers["authorization"] == "Bearer ${service.token}"
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
                    method == "GET" && path == "/osp/peers" -> service.remotes
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

    private fun readBody(reader: BufferedReader, n: Int): String {
        val buf = CharArray(n)
        var read = 0
        while (read < n) {
            val r = reader.read(buf, read, n - read)
            if (r < 0) break
            read += r
        }
        return buf.concatToString(0, read)
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
        val text = req["text"]?.toString() ?: throw IllegalArgumentException("text required")
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

    /** Peer table update: {"peers": {"node-id": "http://host:port"}} */
    private fun setPeers(body: String): Map<String, Any?> {
        val req = MiniJson.parse(body) as? Map<*, *> ?: throw IllegalArgumentException("body must be an object")
        @Suppress("UNCHECKED_CAST")
        val peers = (req["peers"] as? Map<String, Any?>)
            ?.entries?.associate { it.key.toString() to it.value.toString() }
            ?: throw IllegalArgumentException("peers object required")
        service.setPeers(peers)
        return mapOf("ok" to true, "peers" to service.remotes)
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
    }
}
