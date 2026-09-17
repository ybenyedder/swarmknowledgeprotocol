package com.swarmknowledge.osp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NodeProtocolTest {

    private val facts = listOf(
        "hydraulic pump failure is caused by cavitation and worn seals",
        "inspect hydraulic pump seals every 500 hours of operation",
    )

    private fun hubOf(
        originRag: List<String> = emptyList(),
        responderRag: List<String> = facts,
        d3: D3Provider? = EchoGroundedProvider(),
    ): Pair<Node, Node> {
        val hub = InMemoryHub()
        val origin = Node("origin", RagStore(originRag), null).also { it.attach(hub, LexicalVerifier()) }
        val responder = Node("phone", RagStore(responderRag), d3).also { it.attach(hub, LexicalVerifier()) }
        return origin to responder
    }

    // -- validation pipeline -----------------------------------------------------

    @Test
    fun forgedPacketIsDropped() {
        val (origin, responder) = hubOf()
        val pkt = Packet(
            action = Action.PROPOSE, originId = origin.id, queryId = "q1",
            sender = origin.id, gas = 3,
            payload = mapOf("query_vec" to Node.bytesToLongs(embed(facts[0]))),
        ).seal(DevSigner("attacker-secret".toByteArray()))   // wrong key
        assertNull(responder.onPacket(pkt))
    }

    @Test
    fun replayedJtiIsSilentlyDropped() {
        val (origin, responder) = hubOf()
        val pkt = Packet(
            action = Action.PROPOSE, originId = origin.id, queryId = "q1",
            sender = origin.id, gas = 3, jti = "fixed-jti",
            payload = mapOf("query_vec" to Node.bytesToLongs(embed(facts[0]))),
        ).seal(origin.signer)
        assertEquals(Action.BID, responder.onPacket(pkt)!!.action)
        assertNull(responder.onPacket(pkt))                  // same jti again
    }

    @Test
    fun loopInTrailIsRefusedWithRfo() {
        val (_, responder) = hubOf()
        val pkt = Packet(
            action = Action.PROPOSE, originId = "o", queryId = "q1",
            sender = "o", gas = 3, trail = listOf(mapOf("node" to responder.id, "action" to "BID")),
            payload = mapOf("query_vec" to Node.bytesToLongs(embed(facts[0]))),
        ).seal(DevSigner())
        val rfo = responder.onPacket(pkt)!!
        assertEquals(Action.RFO, rfo.action)
        assertEquals(Mode.LOOP_DETECTED.value, rfo.payload["mode"])
    }

    @Test
    fun gasZeroOnArrivalIsRefused() {
        val (_, responder) = hubOf()
        val pkt = Packet(
            action = Action.PROPOSE, originId = "o", queryId = "q1",
            sender = "o", gas = 0,
            payload = mapOf("query_vec" to Node.bytesToLongs(embed(facts[0]))),
        ).seal(DevSigner())
        val rfo = responder.onPacket(pkt)!!
        assertEquals(Mode.GAS_EXHAUSTED.value, rfo.payload["mode"])
    }

    @Test
    fun expiredPacketIsRefused() {
        val (_, responder) = hubOf()
        val pkt = Packet(
            action = Action.PROPOSE, originId = "o", queryId = "q1",
            sender = "o", gas = 3, ts = OspClock.now() - 120.0,
            payload = mapOf("query_vec" to Node.bytesToLongs(embed(facts[0]))),
        ).seal(DevSigner())
        assertEquals(Mode.GAS_EXHAUSTED.value, responder.onPacket(pkt)!!.payload["mode"])
    }

    // -- full negotiation ----------------------------------------------------------

    @Test
    fun happyPathResolvesAndChargesReferenceBudget() {
        val (origin, responder) = hubOf()
        val out = origin.query("why does hydraulic pump failure happen", tier = 0)
        assertEquals(Mode.RESOLVED, out.mode)
        assertTrue((out.groundedness ?: 0.0) >= 0.35)
        // reference semantics (Python osp_core): the ALIGN mapping confirm and
        // the RESOLVE answer each charge one generation — the v0.5 "generation
        // once" rule applies to answer generations.
        assertEquals(2, responder.budget.spent)
        assertTrue(out.answer!!.contains("cavitation"))
    }

    @Test
    fun confabulatingProviderIsRejectedAndReputationDrops() {
        val (origin, responder) = hubOf(d3 = ConfabulatingProvider())
        val before = origin.reputation[responder.id] ?: 0.5
        val out = origin.query("why does hydraulic pump failure happen", tier = 0)
        assertEquals(Mode.REJECTED, out.mode)                // firewall L2 (REQ-F-02)
        assertTrue((origin.reputation[responder.id] ?: 1.0) < before)
    }

    @Test
    fun evidenceDriftBetweenBidAndAlignIsMismatch() {
        val (origin, responder) = hubOf(d3 = EchoGroundedProvider())
        // seam: swap the responder's store to unrelated evidence just before ALIGN
        origin.hooks["pre_align"] = {
            @Suppress("UNCHECKED_CAST")
            val entries = responder.rag.entries as MutableList<com.swarmknowledge.osp.Chunk>
            entries.clear()
            RagStore(listOf("the moon orbits the earth in silence")).entries.forEach(entries::add)
        }
        val out = origin.query("why does hydraulic pump failure happen", tier = 0)
        assertEquals(Mode.MISMATCH, out.mode)                // C3 lock-in
    }

    @Test
    fun sameProvenanceVotesCollapseToNoQuorum() {
        // C4: two nodes citing the SAME leading chunk count once → T1 (q=2) fails
        val hub = InMemoryHub()
        val origin = Node("origin", RagStore(emptyList()), null).also { it.attach(hub, LexicalVerifier()) }
        Node("a", RagStore(facts), EchoGroundedProvider()).also { it.attach(hub, LexicalVerifier()) }
        Node("b", RagStore(facts), EchoGroundedProvider()).also { it.attach(hub, LexicalVerifier()) }
        val out = origin.query("why does hydraulic pump failure happen", tier = 1)
        assertEquals(Mode.NO_QUORUM, out.mode)
    }

    @Test
    fun distinctProvenanceMeetsQuorum() {
        val hub = InMemoryHub()
        val origin = Node("origin", RagStore(emptyList()), null).also { it.attach(hub, LexicalVerifier()) }
        Node("a", RagStore(facts), EchoGroundedProvider()).also { it.attach(hub, LexicalVerifier()) }
        // b's top chunk for this query must differ from a's for C4 to pass it
        Node("b", RagStore(listOf(
            "hydraulic pump failure happens when seals wear out",
            "alternator belt slip causes charging failure")),
            EchoGroundedProvider()).also { it.attach(hub, LexicalVerifier()) }
        val out = origin.query("why does hydraulic pump failure happen", tier = 1)
        assertEquals(Mode.RESOLVED, out.mode)
    }

    @Test
    fun t2QueryToRemoteProviderDeniedByDefault() {
        val hub = InMemoryHub()
        val origin = Node("origin", RagStore(emptyList()), null).also { it.attach(hub, LexicalVerifier()) }
        val answer = "hydraulic pump failure is caused by cavitation and worn seals"
        val r1 = Node("r1", RagStore(facts), ScriptedRemoteProvider(answer))
        val r2 = Node("r2", RagStore(listOf("hydraulic pump failure happens when seals wear out")),
            ScriptedRemoteProvider(answer))
        r1.attach(hub, LexicalVerifier())
        r2.attach(hub, LexicalVerifier())
        val out = origin.query("why does hydraulic pump failure happen", tier = 2)
        assertEquals(Mode.REJECTED, out.mode)                // REQ-NF-02 default deny
        // the ALIGN mapping confirm (1) is charged before the policy gate —
        // reference semantics — but the answer generation never happens
        assertEquals(1, r1.budget.spent + r2.budget.spent)
    }

    @Test
    fun t2QueryToRemoteAllowedWhenConfigured() {
        val hub = InMemoryHub()
        val origin = Node("origin", RagStore(emptyList()), null,
            cfg = NodeConfig(allowRemoteT2 = true)).also { it.attach(hub, LexicalVerifier()) }
        val answer = "hydraulic pump failure is caused by cavitation and worn seals"
        Node("r1", RagStore(facts), ScriptedRemoteProvider(answer)).also { it.attach(hub, LexicalVerifier()) }
        Node("r2", RagStore(listOf("hydraulic pump failure happens when seals wear out")),
            ScriptedRemoteProvider(answer)).also { it.attach(hub, LexicalVerifier()) }
        val out = origin.query("why does hydraulic pump failure happen", tier = 2)
        assertEquals(Mode.RESOLVED, out.mode)
    }

    @Test
    fun exhaustedBudgetAbstainsWithoutGeneration() {
        val hub = InMemoryHub()
        val origin = Node("origin", RagStore(emptyList()), null).also { it.attach(hub, LexicalVerifier()) }
        val responder = Node("phone", RagStore(facts), EchoGroundedProvider())
        responder.budget.charge(50)                          // drain the day
        responder.attach(hub, LexicalVerifier())
        val out = origin.query("why does hydraulic pump failure happen", tier = 0)
        assertEquals(Mode.NO_QUORUM, out.mode)               // can_generate=false at BID
        assertEquals(50, responder.budget.spent)
    }

    @Test
    fun thinOriginRoutesGenerationCapableNodesFirst() {
        val hub = InMemoryHub()
        val directory = Directory()
        val origin = Node("origin", RagStore(emptyList()), null).also { it.attach(hub, LexicalVerifier()) }
        Node("relay", RagStore(facts), null).also { it.attach(hub, LexicalVerifier()) }
        Node("full", RagStore(facts), EchoGroundedProvider()).also { it.attach(hub, LexicalVerifier()) }
        for ((nid, k) in listOf("relay" to "N1", "full" to "N2"))
            directory.register(nid, k, mapOf("signing" to "k-$nid"),
                codebook = listOf(embed("hydraulic pump failure") to "pumps"))
        val out = origin.query("why does hydraulic pump failure happen", tier = 0, directory = directory)
        assertEquals(Mode.RESOLVED, out.mode)                // REQ-F-03 capability routing
    }

    @Test
    fun nodeClassesAreInferred() {
        assertEquals("N1", Node("a", RagStore(emptyList()), null).klass)
        assertEquals("N2", Node("b", RagStore(emptyList()), EchoGroundedProvider()).klass)
        assertEquals("N3", Node("c", RagStore(emptyList()), ScriptedRemoteProvider("x")).klass)
    }
}
