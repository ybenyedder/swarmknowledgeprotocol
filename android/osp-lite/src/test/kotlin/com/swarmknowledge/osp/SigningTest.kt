package com.swarmknowledge.osp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * REQ-S-01/02 signing tests — Ed25519 (RFC 8032) via JWS compact EdDSA.
 *
 * The OSP golden vector is shared verbatim with the bot (osp/core.test.mjs)
 * and the Python reference (mcp/tests/test_signing.py): same seed, same
 * canonical bytes, same signature — three implementations, one wire.
 */
class SigningTest {

    companion object {
        // RFC 8032 §7.1 TEST 1
        val RFC_SEED: ByteArray = hexToBytes("9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60")
        const val RFC_PUB = "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a"
        const val RFC_PUB_B64U = "11qYAYKxCrfVS_7TyWQHOg7hcvPapiMlrwIaaPcHURo"
        const val RFC_KID = "k21fe31dfa154"

        // PROPOSE pktedfixed12 / jtiedfixed1616ab / qfixed12ed255,
        // ts 1758372366.25, query 'pompe hydraulique en panne'
        const val ED_SIG =
            "eyJhbGciOiJFZERTQSIsImtpZCI6ImsyMWZlMzFkZmExNTQiLCJ0eXAiOiJPU1AvdjAuNiJ9" +
                ".eyJhY3Rpb24iOiJQUk9QT1NFIiwiZXhwX3MiOjYwLCJnYXMiOjMsImp0aSI6Imp0aWVkZml4ZWQxNjE2YWIiLCJvcmlnaW5faWQiOiJvcmlnaW4iLCJwYWNrZXRfaWQiOiJwa3RlZGZpeGVkMTIiLCJwYXlsb2FkIjp7InF1ZXJ5X3RleHQiOiJwb21wZSBoeWRyYXVsaXF1ZSBlbiBwYW5uZSIsInF1ZXJ5X3ZlYyI6WzAsMCwwLDAsMCwwLDAsOSwwLDAsMCwwLDAsMCwwLDAsMSwwLDAsMCwwLDAsMCwwLDAsMCwwLDAsMCwwLDAsMzJdfSwicXVlcnlfaWQiOiJxZml4ZWQxMmVkMjU1Iiwic2VuZGVyIjoib3JpZ2luIiwidHJhaWwiOltdLCJ0cyI6MTc1ODM3MjM2Ni4yNSwidiI6IjAuNiJ9" +
                ".ZFVEo0aF3unlGvQ5rAabXHYCNmWHq0BDOHZ1O-JxEKzpKzQuTy7flQSVLG6dbuXEoasXtwV51aVojqDte9LYCg"

        fun hexToBytes(s: String): ByteArray = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

        fun fixedPacket(): Packet = Packet(
            action = Action.PROPOSE, originId = "origin", queryId = "qfixed12ed255",
            sender = "origin", gas = 3, packetId = "pktedfixed12", jti = "jtiedfixed1616ab",
            ts = 1758372366.25,
            payload = mapOf(
                "query_vec" to embed("pompe hydraulique en panne").map { it.toLong() },
                "query_text" to "pompe hydraulique en panne",
            ),
        )
    }

    @Test
    fun ed25519DerivesTheRfc8032Test1KeyPair() {
        assertEquals(RFC_PUB, hex(Ed25519.publicKey(RFC_SEED)))
        val signer = Ed25519Signer(RFC_SEED)
        assertEquals(RFC_KID, signer.kid)
        assertEquals(RFC_PUB_B64U, signer.keyBundle()["signing"])
        assertEquals("ED25519-JWS", signer.label)
    }

    @Test
    fun sharedGoldenVectorSealsByteIdentically() {
        val pkt = fixedPacket().seal(Ed25519Signer(RFC_SEED))
        assertEquals(ED_SIG, pkt.sig, "same seed + canonical bytes must give the same JWS")
    }

    @Test
    fun pythonSealedGoldenVectorVerifiesHere() {
        val signer = Ed25519Signer(RFC_SEED)
        assertTrue(signer.verify(fixedPacket().signedObject(), ED_SIG),
            "the JS/Python signature must verify in Kotlin")
    }

    @Test
    fun tamperingFailsClosed() {
        val signer = Ed25519Signer(RFC_SEED)
        val pkt = fixedPacket().seal(signer)
        assertTrue(signer.verify(pkt.signedObject(), pkt.sig))

        // any signed-field mutation invalidates the signature
        val mutated = linkedMapOf<String, Any?>()
        pkt.signedObject().forEach { (k, v) -> mutated[k] = v }
        mutated["gas"] = 2L
        assertFalse(signer.verify(mutated, pkt.sig), "mutated signed field must break the sig")

        // flipped signature bytes
        assertFalse(signer.verify(pkt.signedObject(), pkt.sig.dropLast(4) + "AAAA"))
        // not a JWS / wrong scheme
        assertFalse(signer.verify(pkt.signedObject(), "not-a-jws"))
        assertFalse(signer.verify(pkt.signedObject(), DevSigner().sign(pkt.signedObject())),
            "an HMAC sig is not EdDSA trust")
    }

    @Test
    fun hybridVerifiesBothSchemesAndBindsTheSender() {
        val lookup: (String) -> Pair<String, String>? = { kid ->
            if (kid == RFC_KID) RFC_PUB_B64U to "origin" else null
        }
        val hybrid = HybridSigner(edSeed = RFC_SEED, keyLookup = lookup)
        assertEquals("ED25519-JWS", hybrid.label)
        assertTrue(hybrid.sealEd25519())

        val pkt = fixedPacket().seal(hybrid)
        assertTrue(pkt.sig.startsWith("eyJ"), "EdDSA mode seals JWS")
        assertTrue(hybrid.verify(pkt.signedObject(), pkt.sig))

        // the same key under ANOTHER node id is an impostor — sender binding
        val stolen: (String) -> Pair<String, String>? = { kid ->
            if (kid == RFC_KID) RFC_PUB_B64U to "bot" else null
        }
        assertFalse(HybridSigner(keyLookup = stolen).verify(pkt.signedObject(), pkt.sig),
            "a pinned key used by another node_id is rejected")

        // unknown kid → fail closed
        assertFalse(HybridSigner(keyLookup = { null }).verify(pkt.signedObject(), pkt.sig))
        assertFalse(HybridSigner().verify(pkt.signedObject(), pkt.sig), "no lookup → no trust")

        // legacy HMAC packets still verify during the migration window
        val legacy = fixedPacket().seal(DevSigner())
        assertTrue(hybrid.verify(legacy.signedObject(), legacy.sig))
    }
}
