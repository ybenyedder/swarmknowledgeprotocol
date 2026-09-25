package com.swarmknowledge.osp

import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Packet signer (REQ-S-01). DEV-SIGNER (HMAC-SHA256) is the MVP signer and is
 * NOT for deployment — production must swap in an Ed25519 implementation
 * (RFC 8032) behind this same interface.
 */
interface Signer {
    val label: String
    fun sign(obj: Map<String, Any?>): String
    fun verify(obj: Map<String, Any?>, sig: String): Boolean
}

/**
 * DEV-SIGNER: HMAC-SHA256 over the canonical JSON bytes (MiniJson, key-sorted,
 * Python-parity). NOT for deployment (REQ-S-01).
 */
class DevSigner(private val secret: ByteArray = "osp-dev-secret".toByteArray(Charsets.UTF_8)) : Signer {
    override val label = "DEV-SIGNER"

    override fun sign(obj: Map<String, Any?>): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret, "HmacSHA256"))
        return hex(mac.doFinal(MiniJson.write(obj).toByteArray(Charsets.UTF_8)))
    }

    override fun verify(obj: Map<String, Any?>, sig: String): Boolean =
        MessageDigest.isEqual(sign(obj).toByteArray(), sig.toByteArray())
}

/**
 * REQ-S-01 production signer: Ed25519 (RFC 8032) via JWS compact EdDSA.
 *
 * sig = b64url(header) + "." + b64url(canonical bytes) + "." + b64url(64-byte
 * signature), header {"alg":"EdDSA","kid",…,"typ":"OSP/v0.6"} — byte-identical
 * wire format with the bot (osp/core.mjs) and the Python reference
 * (mcp/osp_core.py). The canonical payload rides inside the JWS, so verify()
 * byte-compares it against its own canonical form before trusting the
 * signature: a non-canonical sender fails closed. kid is derived from the
 * public key so peers can pin and select it (REQ-S-02).
 */
class Ed25519Signer(private val seed: ByteArray) : Signer {
    override val label = "ED25519-JWS"

    init {
        require(seed.size == 32) { "Ed25519 seed must be 32 bytes" }
    }

    val publicKey: ByteArray = Ed25519.publicKey(seed)
    val kid: String = "k" + hex(MessageDigest.getInstance("SHA-256").digest(publicKey)).take(12)

    private val headerB64: String = b64url(
        """{"alg":"EdDSA","kid":"$kid","typ":"OSP/v0.6"}""".toByteArray(Charsets.UTF_8),
    )

    override fun sign(obj: Map<String, Any?>): String {
        val payload = b64url(MiniJson.write(obj).toByteArray(Charsets.UTF_8))
        val sig = Ed25519.sign(seed, "$headerB64.$payload".toByteArray(Charsets.UTF_8))
        return "$headerB64.$payload.${b64url(sig)}"
    }

    override fun verify(obj: Map<String, Any?>, sig: String): Boolean {
        val parts = sig.split(".")
        if (parts.size != 3 || !parts[0].startsWith("eyJ")) return false
        val (headerB64, payloadB64, sigB64) = parts
        val header = try {
            @Suppress("UNCHECKED_CAST")
            MiniJson.parse(b64decode(headerB64).toString(Charsets.UTF_8)) as? Map<String, Any?>
                ?: return false
        } catch (_: Exception) {
            return false
        }
        if (header["alg"] != "EdDSA") return false
        if (b64url(MiniJson.write(obj).toByteArray(Charsets.UTF_8)) != payloadB64) return false
        return Ed25519.verify(publicKey, "$headerB64.$payloadB64".toByteArray(Charsets.UTF_8), b64decode(sigB64))
    }

    /** The discovery key bundle peers PIN (REQ-S-02, first sight). */
    fun keyBundle(): Map<String, String> =
        mapOf("alg" to "EdDSA", "kid" to kid, "signing" to b64url(publicKey))
}

/**
 * Migration signer: seals with Ed25519 when a seed is configured, otherwise
 * with the labelled dev HMAC (transition only — REQ-S-01). Verifies BOTH
 * schemes, resolving EdDSA keys through [keyLookup] (kid → pinned key +
 * node id, REQ-S-02); a pinned key used under another node id is rejected.
 */
class HybridSigner(
    private val hmacSecret: ByteArray = "osp-dev-secret".toByteArray(Charsets.UTF_8),
    edSeed: ByteArray? = null,
    private val keyLookup: ((String) -> Pair<String, String>?)? = null,
) : Signer {
    private val dev = DevSigner(hmacSecret)
    private val ed = edSeed?.let { Ed25519Signer(it) }
    override val label = ed?.label ?: "DEV-SIGNER"

    fun sealEd25519(): Boolean = ed != null

    override fun sign(obj: Map<String, Any?>): String =
        ed?.sign(obj) ?: dev.sign(obj)

    override fun verify(obj: Map<String, Any?>, sig: String): Boolean {
        if (!sig.startsWith("eyJ") || sig.count { it == '.' } != 2) return dev.verify(obj, sig)
        val lookup = keyLookup ?: return false
        val header = try {
            @Suppress("UNCHECKED_CAST")
            MiniJson.parse(b64decode(sig.substringBefore('.')).toString(Charsets.UTF_8)) as? Map<String, Any?>
                ?: return false
        } catch (_: Exception) {
            return false
        }
        if (header["alg"] != "EdDSA") return false
        val kid = header["kid"] as? String ?: return false
        val pinned = lookup(kid) ?: return false
        val (signing, nodeId) = pinned
        val headerB64 = sig.substringBefore('.')
        val payloadB64 = sig.substringAfter('.').substringBefore('.')
        val sigB64 = sig.substringAfterLast('.')
        if (b64url(MiniJson.write(obj).toByteArray(Charsets.UTF_8)) != payloadB64) return false
        val ok = Ed25519.verify(
            b64decode(signing),
            "$headerB64.$payloadB64".toByteArray(Charsets.UTF_8),
            b64decode(sigB64),
        )
        return ok && (nodeId == obj["sender"] as? String)
    }
}

/** base64url, unpadded — the JWS alphabet. */
internal fun b64url(data: ByteArray): String =
    java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(data)

internal fun b64decode(text: String): ByteArray =
    java.util.Base64.getUrlDecoder().decode(text + "=".repeat((4 - text.length % 4) % 4))
