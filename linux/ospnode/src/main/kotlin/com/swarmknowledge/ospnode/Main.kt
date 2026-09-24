package com.swarmknowledge.ospnode

import com.swarmknowledge.osp.DevSigner
import com.swarmknowledge.osp.EchoGroundedProvider
import com.swarmknowledge.osp.MiniJson
import com.swarmknowledge.osp.NodeConfig
import com.swarmknowledge.osp.newId
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import kotlin.system.exitProcess

/**
 * ospnode — an OSP v0.6 node for Linux (any JVM ≥ 17).
 *
 *   java -jar ospnode-all.jar [options]
 *
 * Two modes:
 *   serve (default)  HTTP bridge + web console on --port, negotiate with peers
 *   --query TEXT     one-shot negotiation from the shell; prints the outcome
 *                    JSON and exits (exit 0 = RESOLVED, 1 = any other mode)
 *
 * The env contract matches the Python reference (`mcp/osp_cli.py`,
 * `mcp/providers.py`): OSP_TOKEN for the link secret, OSP_PROVIDER_URL /
 * OSP_PROVIDER_MODEL / OSP_PROVIDER_API_KEY for a remote N3 provider.
 */
fun main(args: Array<String>) {
    if (args.isEmpty() || args.contains("--help") || args.contains("-h")) {
        print(usage)
        return
    }

    var id: String? = null
    var port = NodeServer.PORT_DEFAULT
    var token: String? = null
    var providerKind = "echo"
    var queryText: String? = null
    var tier = 1
    var allowRemoteT2 = false
    val peers = LinkedHashMap<String, LinuxNode.Peer>()
    val corpora = ArrayList<String>()

    var i = 0
    fun value(flag: String): String {
        i++
        if (i >= args.size) fail("$flag needs a value")
        return args[i]
    }
    while (i < args.size) {
        when (val a = args[i]) {
            "--id" -> id = value(a)
            "--port" -> port = value(a).toIntOrNull() ?: fail("--port must be a number")
            "--token" -> token = value(a)
            "--provider" -> providerKind = value(a).lowercase()
            "--query" -> queryText = value(a)
            "--tier" -> tier = value(a).toIntOrNull() ?: fail("--tier must be 0, 1 or 2")
            "--allow-remote-t2" -> allowRemoteT2 = true
            "--corpus" -> corpora.add(value(a))
            "--peer" -> {
                val kv = value(a).split('=', limit = 2)
                if (kv.size != 2) fail("--peer wants id=url")
                peers[kv[0].trim()] = LinuxNode.Peer(kv[1].trim())
            }
            "--peer-token" -> {
                val kv = value(a).split('=', limit = 2)
                if (kv.size != 2) fail("--peer-token wants id=secret")
                val key = kv[0].trim()
                val p = peers[key] ?: fail("--peer $key must come before --peer-token")
                peers[key] = p.copy(token = kv[1].trim())
            }
            "--peers-file" -> mergePeersFile(peers, value(a))
            else -> fail("unknown option \"$a\" (--help lists them)")
        }
        i++
    }

    val provider = when (providerKind) {
        "echo" -> EchoGroundedProvider()
        "openai" -> OpenAiCompatProvider(
            System.getenv("OSP_PROVIDER_URL") ?: "",
            System.getenv("OSP_PROVIDER_MODEL") ?: "",
            System.getenv("OSP_PROVIDER_API_KEY") ?: "",
        )
        else -> fail("unknown provider \"$providerKind\" (echo | openai)")
    }

    val node = LinuxNode(id ?: ("osp-" + newId(8)), provider, DevSigner(), NodeConfig(allowRemoteT2 = allowRemoteT2))
    for (c in corpora) loadCorpus(node, c)
    if (peers.isNotEmpty()) node.setPeers(peers)

    if (queryText != null) {
        // one-shot: no bridge is served, the link token is irrelevant
        val out = node.submitQuery(queryText, tier)
        println(MiniJson.write(mapOf("outcome" to out.toMap(), "node" to node.id)))
        if (out.mode != com.swarmknowledge.osp.Mode.RESOLVED) exitProcess(1)
        return
    }

    // the link secret is transport auth for the bridge only; generate one when
    // neither --token nor OSP_TOKEN pins it, and share it with peers
    node.token = token ?: System.getenv("OSP_TOKEN") ?: newId(24).also { t ->
        System.err.println("link token (generated, share it with peers): $t")
    }
    serve(node, port)
}

private fun serve(node: LinuxNode, port: Int) {
    val server = NodeServer(node, port)
    server.start()
    while (server.boundPort != port) Thread.sleep(50)   // port 0 → wait for the ephemeral bind
    val availability = if (node.providerAvailable) "available" else "NOT available — honest abstention (REQ-F-04)"
    println("osp-node — OSP v0.6 Connect (${node.responder.klass}, provider ${node.providerName}, $availability)")
    println("  node id    : ${node.id}")
    println("  HTTP       : http://0.0.0.0:${server.boundPort}/  (LAN http://${lanAddress()}:${server.boundPort}/)")
    println("  web console: http://localhost:${server.boundPort}/")
    println("  link token : ${node.token.take(6)}…  (peers send it as x-api-token or Authorization: Bearer)")
    println("  knowledge  : ${node.rag.entries.size} chunks · peers: ${node.remotes.keys.joinToString(", ").ifEmpty { "(none — swarm of one)" }}")
    println("DEV POSTURE: HMAC dev signer + cleartext HTTP — lab/LAN only (REQ-S-01).")
    Thread.currentThread().join()          // serve until SIGINT/SIGTERM
}

private fun mergePeersFile(peers: LinkedHashMap<String, LinuxNode.Peer>, path: String) {
    val f = File(path)
    if (!f.isFile) fail("peers file not found: $path")
    val root = runCatching { MiniJson.parse(f.readText()) }.getOrNull()
        ?: fail("peers file is not valid JSON: $path")
    @Suppress("UNCHECKED_CAST")
    val raw = (root as? Map<String, Any?>)?.get("peers") as? Map<String, Any?>
        ?: fail("peers file must contain a top-level {\"peers\": {…}} object")
    for ((id, v) in raw) {
        when (v) {
            is Map<*, *> -> peers[id] = LinuxNode.Peer(v["url"].toString(), v["token"]?.toString())
            else -> peers[id] = LinuxNode.Peer(v.toString())
        }
    }
}

/**
 * Teach from files: a .txt/.md file, or a directory walked recursively.
 * Blank-line paragraphs become individual chunks — the retrieval granularity
 * the pre-bid gate scores (clause 5.5.1).
 */
private fun loadCorpus(node: LinuxNode, path: String) {
    val f = File(path)
    if (!f.exists()) fail("corpus path not found: $path")
    val files = if (f.isDirectory)
        f.walkTopDown().filter { it.isFile && it.extension.lowercase() in setOf("txt", "md", "markdown") }
    else
        sequenceOf(f)
    var n = 0
    for (file in files) {
        for (para in file.readText().split(Regex("\\n\\s*\\n"))) {
            if (node.teach(para)) n++
        }
    }
    // stderr: the one-shot --query mode must keep stdout for the JSON payload only
    System.err.println("  corpus $path → $n chunks")
}

private fun lanAddress(): String = runCatching {
    NetworkInterface.getNetworkInterfaces().asSequence()
        .filter { it.isUp && !it.isLoopback }
        .flatMap { it.inetAddresses.asSequence() }
        .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }
        ?.hostAddress
}.getOrNull() ?: "127.0.0.1"

private fun fail(msg: String): Nothing {
    System.err.println("ospnode: $msg")
    exitProcess(2)
}

private val usage: String = """
ospnode — OSP v0.6 node for Linux (JVM 17+)

Usage:
  java -jar ospnode-all.jar [options]          serve on --port (default)
  java -jar ospnode-all.jar --query "…"        one-shot negotiation, JSON out

Options:
  --id NAME            node id                     (default osp-XXXXXXXX)
  --port N             HTTP bridge port            (default ${NodeServer.PORT_DEFAULT}; 0 = ephemeral)
  --token SECRET       link secret for the bridge  (default: OSP_TOKEN env, else generated)
  --peer id=url        add a remote peer           (repeatable)
  --peer-token id=s    link secret of a --peer     (repeatable)
  --peers-file FILE    JSON {"peers": {…}} — the ospbridge /osp/peers format
  --corpus PATH        teach a .txt/.md file or directory at startup (repeatable;
                       blank-line paragraphs become chunks)
  --provider NAME      echo (default, grounded by construction) | openai
                       (N3 via OSP_PROVIDER_URL / OSP_PROVIDER_MODEL / OSP_PROVIDER_API_KEY)
  --query TEXT         run one negotiation and exit (exit 0 only if RESOLVED)
  --tier 0|1|2         tier for --query             (default 1)
  --allow-remote-t2    let T2 queries reach remote providers (default deny, REQ-NF-02)
  -h, --help           this text

Surfaces (token-gated, except / and /osp/status):
  GET  /                     browser console
  GET  /osp/status           node status JSON
  GET  /osp/endpoint.json    provider contract (LLMProviderFileAdapter)
  GET|POST /osp/peers        peer table (ospbridge format)
  GET  /v1/models            OpenAI-compatible model list
  POST /v1/chat/completions  RAW generation passthrough (unverified)
  POST /osp/query            verified negotiation outcome
  POST /osp/packet           sealed packet in → sealed reply out
  POST /osp/teach            {text} → one knowledge chunk

DEV POSTURE: HMAC dev signer + cleartext HTTP — lab/LAN only (REQ-S-01).
""".trimIndent()
