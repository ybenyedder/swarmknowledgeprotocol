package com.swarmknowledge.ospnode

import com.swarmknowledge.osp.D3Provider
import com.swarmknowledge.osp.Evidence
import com.swarmknowledge.osp.MiniJson
import com.swarmknowledge.osp.ProviderOut
import com.swarmknowledge.osp.buildEnvelope
import java.net.HttpURLConnection
import java.net.URL

/**
 * Remote OpenAI-compatible `/chat/completions` endpoint as the node's D3 rung
 * (REQ-F-01) — the Kotlin counterpart of `mcp/providers.py:OpenAICompatProvider`,
 * same envelope, same temperature, same env-var contract:
 *
 *   OSP_PROVIDER_URL    OpenAI-compatible base URL (node becomes N3)
 *   OSP_PROVIDER_MODEL  model id sent to the endpoint
 *   OSP_PROVIDER_API_KEY  bearer token (optional for local servers, e.g. ollama)
 *
 * The provider receives ONLY the query and the cited chunks, inside the
 * grounding envelope; its output is untrusted data (REQ-F-02) and still runs
 * the origin-side firewall.
 */
class OpenAiCompatProvider(
    baseUrl: String,
    private val model: String,
    private val apiKey: String = "",
    private val timeoutMs: Int = 120_000,
) : D3Provider {

    // trailing "/" would double up on "<base>/chat/completions" (Python rstrip("/"))
    private val base = baseUrl.trimEnd('/')

    override val name = "openai-compat"

    /** False until both env vars are set — an unconfigured N3 must not bid (REQ-F-04). */
    override val available: Boolean
        get() = base.isNotEmpty() && model.isNotEmpty()

    override fun generate(query: String, chunks: List<Evidence>): ProviderOut {
        val body = MiniJson.write(
            mapOf(
                "model" to model,
                "messages" to listOf(
                    mapOf("role" to "user", "content" to buildEnvelope(query, chunks)),
                ),
                "temperature" to 0.2,
            ),
        )
        val conn = URL("$base/chat/completions").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 5_000
        conn.readTimeout = timeoutMs
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        if (apiKey.isNotEmpty()) conn.setRequestProperty("Authorization", "Bearer $apiKey")
        conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        val code = conn.responseCode
        val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.readText()
            ?: throw IllegalStateException("provider HTTP $code with no body")
        if (code !in 200..299) throw IllegalStateException("provider HTTP $code: ${text.take(200)}")
        val data = MiniJson.parse(text) as? Map<*, *> ?: throw IllegalStateException("non-object reply")
        @Suppress("UNCHECKED_CAST")
        val choice = (data["choices"] as? List<Map<String, Any?>>)?.firstOrNull()
            ?: throw IllegalStateException("no choices in reply")
        val answer = (choice["message"] as? Map<*, *>)?.get("content")?.toString()
            ?: throw IllegalStateException("no message content in reply")
        val usage = data["usage"] as? Map<*, *>
        return ProviderOut(
            answer = answer,
            provider = "$name:$model",
            cost = mapOf(
                "generations" to 1L,
                "tokens" to ((usage?.get("total_tokens") as? Number)?.toLong() ?: 0L),
            ),
        )
    }
}
