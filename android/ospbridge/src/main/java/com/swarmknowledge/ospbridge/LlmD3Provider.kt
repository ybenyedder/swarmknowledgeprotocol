package com.swarmknowledge.ospbridge

import com.swarmknowledge.osp.D3Provider
import com.swarmknowledge.osp.Evidence
import com.swarmknowledge.osp.ProviderOut
import com.swarmknowledge.osp.buildEnvelope

/**
 * The bridge's generation rung (D3): delegates to the on-device LLMProvider
 * engine through [LlmBridge], wrapped in the OSP grounding envelope
 * (REQ-F-02 — the provider only ever sees query + cited chunks).
 *
 * remote = false: the model lives on THIS device, so the bridge node is an
 * N2 full node. From another device (e.g. the PC MCP server reaching the
 * bridge over HTTP) it is the transport that is remote, not the model.
 */
class LlmD3Provider(private val bridge: LlmBridge) : D3Provider {
    override val name = "llmprovider-bridge"

    override val remote = false

    override val available: Boolean
        get() = bridge.connected

    override fun generate(query: String, chunks: List<Evidence>): ProviderOut {
        val answer = bridge.generate(buildEnvelope(query, chunks)).trim()
        return ProviderOut(answer = answer, provider = name)
    }
}
