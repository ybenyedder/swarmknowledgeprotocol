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
