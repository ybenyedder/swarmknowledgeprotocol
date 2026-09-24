package com.swarmknowledge.ospnode

import com.swarmknowledge.osp.Action
import com.swarmknowledge.osp.DevSigner
import com.swarmknowledge.osp.EchoGroundedProvider
import com.swarmknowledge.osp.MiniJson
import com.swarmknowledge.osp.Mode
import com.swarmknowledge.osp.Node
import com.swarmknowledge.osp.Packet
import com.swarmknowledge.osp.embed
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Conformance of the Linux HTTP surface: same routes, same auth double-header,
 * same raw-bytes body discipline and same sealed-packet wire as the Android
 * app's HttpBridge (root README clause 5.1, android/README REQ-A-05).
 */
class NodeServerTest {

    private val token = "test-link-secret"
    private val node = LinuxNode("linux-test", EchoGroundedProvider())
    private val server = NodeServer(node, port = 0).apply {
        node.token = token
        start()
    }

    init {
        while (server.boundPort == 0) Thread.sleep(10)   // wait for the ephemeral bind
    }

    @AfterTest
    fun tearDown() = server.stop()

    private fun port(): Int = server.boundPort

    // -- auth --------------------------------------------------------------------

    @Test
    fun `status is open but query demands the link token`() {
        val status = request(port(), "GET", "/osp/status")
        assertEquals(200, status.code)
        assertEquals("linux-test", (status.json() as Map<*, *>)["node_id"])

        val denied = request(port(), "POST", "/osp/query", body = """{"query":"hello"}""")
        assertEquals(401, denied.code)

        val bearerSpelling = request(
            port(), "POST", "/osp/query",           // HttpHub's second header spelling
            headers = mapOf("Authorization" to "Bearer $token"),
            body = """{"query":"hello"}""",
        )
        assertEquals(200, bearerSpelling.code)
    }

    // -- teach → verified query ----------------------------------------------------

    @Test
    fun `teach then query resolves with groundedness`() {
        val taught = request(port(), "POST", "/osp/teach", tokenHeaders(),
            body = """{"text":"A hydraulic pump failure happens when fluid contamination blocks the relief valve."}""")
        assertEquals(200, taught.code)
        assertEquals(1, ((taught.json() as Map<*, *>)["chunks"] as Number).toInt())

        val reply = request(port(), "POST", "/osp/query", tokenHeaders(),
            body = """{"query":"why does hydraulic pump failure happen","tier":0}""")
        assertEquals(200, reply.code)
        @Suppress("UNCHECKED_CAST")
        val outcome = (reply.json() as Map<*, *>)["outcome"] as Map<String, Any?>
        assertEquals(Mode.RESOLVED.value, outcome["mode"])
        assertTrue((outcome["answer"] as String).isNotEmpty())
        assertTrue(((outcome["groundedness"] as? Number)?.toDouble() ?: 0.0) >= 0.35)
    }

    @Test
    fun `query on an empty store abstains instead of confabulating`() {
        val reply = request(port(), "POST", "/osp/query", tokenHeaders(),
            body = """{"query":"what about an unknown topic","tier":0}""")
        @Suppress("UNCHECKED_CAST")
        val outcome = (reply.json() as Map<*, *>)["outcome"] as Map<String, Any?>
        assertEquals(Mode.NO_QUORUM.value, outcome["mode"])
    }

    // -- protocol-native surface -----------------------------------------------------

    @Test
    fun `sealed PROPOSE gets a sealed BID and a forged packet gets silence`() {
        // an empty store honestly abstains (RFO NO_QUORUM) — give it evidence
        node.teach("A hydraulic pump failure happens when fluid contamination blocks the relief valve.")
        val propose = Packet(
            action = Action.PROPOSE, originId = "cli-origin", queryId = "q-1",
            sender = "cli-origin", gas = 3,
            payload = mapOf(
                "query_vec" to Node.bytesToLongs(embed("hydraulic pump failure")),
                "query_text" to "why does hydraulic pump failure happen",
            ),
        ).seal(DevSigner())

        val reply = request(port(), "POST", "/osp/packet", tokenHeaders(),
            body = MiniJson.write(propose.toWire()))
        assertEquals(200, reply.code)
        val wire = reply.json() as Map<*, *>
        assertEquals(Action.BID.value, wire["action"])
        val sealed = Packet.fromWire(wire.entries.associate { it.key.toString() to it.value })
        assertTrue(sealed.verified(DevSigner()), "the reply must be sealed by the node")

        // tampered AND freshly-nonces'd: signature fails, not the replay cache
        val tampered = propose.toWire().toMutableMap().apply {
            put("gas", 99L)
            put("jti", "forged-jti-00000001")
        }
        val forged = request(port(), "POST", "/osp/packet", tokenHeaders(),
            body = MiniJson.write(tampered))
        assertEquals(200, forged.code)
        assertEquals(emptyMap<String, Any?>(), forged.json(), "forged packets are silently dropped")
    }

    @Test
    fun `peer table speaks the ospbridge format`() {
        val set = request(port(), "POST", "/osp/peers", tokenHeaders(), body =
            """{"peers": {"tablet": {"url": "http://192.168.1.10:8090", "token": "t1"},
                           "bot": "http://192.168.1.20:5000"}}""")
        assertEquals(200, set.code)
        val got = request(port(), "GET", "/osp/peers", tokenHeaders())
        val peers = got.json() as Map<*, *>
        assertEquals(mapOf("url" to "http://192.168.1.10:8090", "token" to "t1"), peers["tablet"])
        assertEquals("http://192.168.1.20:5000", peers["bot"])
    }

    // -- web console + openai-compatible passthrough ---------------------------------

    @Test
    fun `console page is served at the root without a token`() {
        val page = request(port(), "GET", "/")
        assertEquals(200, page.code)
        assertTrue(page.text().contains("osp-node console"))
    }

    @Test
    fun `openai-compatible passthrough answers from the configured provider`() {
        val reply = request(port(), "POST", "/v1/chat/completions", tokenHeaders(),
            body = """{"messages":[{"role":"user","content":"chromecast pairing"}]}""")
        assertEquals(200, reply.code)
        @Suppress("UNCHECKED_CAST")
        val choices = (reply.json() as Map<*, *>)["choices"] as List<Map<String, Any?>>
        val content = ((choices[0]["message"] as Map<*, *>)["content"]) as String
        // the dev echo stub answers from the query terms — proves the plumbing
        assertTrue(content.contains("chromecast"))
    }

    // -- helpers ---------------------------------------------------------------------

    private fun tokenHeaders(): Map<String, String> = mapOf("x-api-token" to token)

    private fun request(
        port: Int,
        method: String,
        path: String,
        headers: Map<String, String> = emptyMap(),
        body: String? = null,
    ): Response {
        val conn = URL("http://127.0.0.1:$port$path").openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = 5_000
        conn.readTimeout = 60_000
        for ((k, v) in headers) conn.setRequestProperty(k, v)
        if (body != null) {
            conn.doOutput = true
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        }
        val code = conn.responseCode
        val stream: InputStream? = if (code in 200..299) conn.inputStream else conn.errorStream
        return Response(code, stream?.bufferedReader()?.readText() ?: "")
    }

    private class Response(val code: Int, private val body: String) {
        fun text(): String = body
        fun json(): Any? = MiniJson.parse(body)
    }
}
