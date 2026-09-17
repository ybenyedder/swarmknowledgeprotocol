package com.swarmknowledge.osp

import java.util.UUID

/** Injectable wall clock (epoch seconds) — overridden by lease/replay tests. */
object OspClock {
    var now: () -> Double = { System.currentTimeMillis() / 1000.0 }
}

const val PACKET_VERSION = "0.6"

fun newId(n: Int): String = UUID.randomUUID().toString().replace("-", "").take(n)

enum class Action(val value: String) {
    PROPOSE("PROPOSE"),
    BID("BID"),
    ALIGN("ALIGN"),
    RESOLVE("RESOLVE"),
    GET_CHUNK("GET_CHUNK"),
    RFO("RFO"),      // explicit failure backtracked to origin (never silent)
    ACK("ACK");

    companion object {
        fun from(v: String): Action = entries.first { it.value == v }
    }
}

enum class Mode(val value: String) {
    RESOLVED("RESOLVED"),
    REJECTED("REJECTED"),        // firewall rejected the winning payload
    MISMATCH("MISMATCH"),
    GAS_EXHAUSTED("GAS_EXHAUSTED"),
    LOOP_DETECTED("LOOP_DETECTED"),
    NO_QUORUM("NO_QUORUM");

    companion object {
        fun from(v: String): Mode = entries.first { it.value == v }
    }
}

/**
 * Signed stateless packet (v0.4 envelope). Wire keys are snake_case and the
 * signature covers the canonical JSON of [signedObject] — byte-compatible with
 * the Python reference (asserted by cross-language test vectors).
 */
class Packet(
    val action: Action,
    val originId: String,
    val queryId: String,
    val sender: String,
    val gas: Int,
    val trail: List<Map<String, String>> = emptyList(),
    val payload: Map<String, Any?> = emptyMap(),
    val packetId: String = newId(12),
    val jti: String = newId(16),
    val ts: Double = OspClock.now(),
    val expS: Long = 60,
    val version: String = PACKET_VERSION,
) {
    var sig: String = ""
        private set

    fun signedObject(): Map<String, Any?> = linkedMapOf(
        "v" to version, "packet_id" to packetId, "jti" to jti,
        "ts" to ts, "exp_s" to expS, "action" to action.value,
        "origin_id" to originId, "query_id" to queryId,
        "sender" to sender, "gas" to gas.toLong(), "trail" to trail,
        "payload" to payload,
    )

    fun seal(signer: Signer): Packet {
        sig = signer.sign(signedObject())
        return this
    }

    fun verified(signer: Signer): Boolean = signer.verify(signedObject(), sig)

    fun expired(now: Double = OspClock.now()): Boolean = now > ts + expS

    fun toWire(): Map<String, Any?> = linkedMapOf(
        "v" to version, "packet_id" to packetId, "jti" to jti,
        "ts" to ts, "exp_s" to expS, "action" to action.value,
        "origin_id" to originId, "query_id" to queryId,
        "sender" to sender, "gas" to gas.toLong(), "trail" to trail,
        "payload" to payload, "sig" to sig,
    )

    companion object {
        /** Decode a wire map (e.g. parsed from JSON). Signature byte is `sig`. */
        fun fromWire(m: Map<String, Any?>): Packet {
            fun str(k: String) = m[k]?.toString() ?: throw IllegalArgumentException("missing $k")
            val p = Packet(
                action = Action.from(str("action")),
                originId = str("origin_id"),
                queryId = str("query_id"),
                sender = str("sender"),
                gas = (m["gas"] as Number).toInt(),
                trail = (m["trail"] as? List<*> ?: emptyList<Any?>()).map { h ->
                    (h as Map<*, *>).entries.associate { it.key.toString() to it.value.toString() }
                },
                payload = (m["payload"] as? Map<*, *> ?: emptyMap<Any?, Any?>())
                    .entries.associate { it.key.toString() to it.value },
                packetId = str("packet_id"),
                jti = str("jti"),
                ts = (m["ts"] as Number).toDouble(),
                expS = (m["exp_s"] as? Number)?.toLong() ?: 60L,
                version = str("v"),
            )
            p.sig = str("sig")        // companion sits inside Packet: setter reachable
            return p
        }
    }
}
