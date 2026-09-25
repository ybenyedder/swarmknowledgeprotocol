"""REQ-S-01/02 signing tests — Ed25519 (RFC 8032) via JWS compact EdDSA.

Run:  cd mcp && python3 -m unittest discover tests -v

The OSP golden vector below is shared verbatim with the bot (osp/core.mjs
tests) and the tablet (android/osp-lite SigningTest.kt): same seed, same
canonical bytes, same signature — three implementations, one wire.
"""
from __future__ import annotations

import base64
import json
import os
import sys
import unittest
from pathlib import Path

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from ed25519 import publickey, sign, verify
from osp_core import Action, DevSigner, Ed25519Signer, InMemoryHub, LexicalVerifier, Node, Packet, RagStore, embed

# RFC 8032 §7.1 TEST 1
RFC_SEED = bytes.fromhex("9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60")
RFC_PUB = "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a"
RFC_PUB_B64U = "11qYAYKxCrfVS_7TyWQHOg7hcvPapiMlrwIaaPcHURo"
RFC_KID = "k21fe31dfa154"
# PROPOSE pktedfixed12 / jtiedfixed1616ab / qfixed12ed255, ts 1758372366.25,
# query 'pompe hydraulique en panne', sealed by the RFC 8032 TEST-1 key.
ED_SIG = (
    "eyJhbGciOiJFZERTQSIsImtpZCI6ImsyMWZlMzFkZmExNTQiLCJ0eXAiOiJPU1AvdjAuNiJ9"
    ".eyJhY3Rpb24iOiJQUk9QT1NFIiwiZXhwX3MiOjYwLCJnYXMiOjMsImp0aSI6Imp0aWVkZml4ZWQxNjE2YWIiLCJvcmlnaW5faWQiOiJvcmlnaW4iLCJwYWNrZXRfaWQiOiJwa3RlZGZpeGVkMTIiLCJwYXlsb2FkIjp7InF1ZXJ5X3RleHQiOiJwb21wZSBoeWRyYXVsaXF1ZSBlbiBwYW5uZSIsInF1ZXJ5X3ZlYyI6WzAsMCwwLDAsMCwwLDAsOSwwLDAsMCwwLDAsMCwwLDAsMSwwLDAsMCwwLDAsMCwwLDAsMCwwLDAsMCwwLDAsMzJdfSwicXVlcnlfaWQiOiJxZml4ZWQxMmVkMjU1Iiwic2VuZGVyIjoib3JpZ2luIiwidHJhaWwiOltdLCJ0cyI6MTc1ODM3MjM2Ni4yNSwidiI6IjAuNiJ9"
    ".ZFVEo0aF3unlGvQ5rAabXHYCNmWHq0BDOHZ1O-JxEKzpKzQuTy7flQSVLG6dbuXEoasXtwV51aVojqDte9LYCg"
)


def _fixed_packet() -> Packet:
    return Packet(
        action=Action.PROPOSE, origin_id="origin", query_id="qfixed12ed255",
        sender="origin", gas=3, packet_id="pktedfixed12", jti="jtiedfixed1616ab",
        ts=1758372366.25,
        payload={"query_vec": list(embed("pompe hydraulique en panne")),
                 "query_text": "pompe hydraulique en panne"},
    )


class TestRfc8032(unittest.TestCase):
    """The vendored primitive against the RFC's own vectors (§7.1)."""

    def test_test1_public_key(self):
        self.assertEqual(publickey(RFC_SEED).hex(), RFC_PUB)

    def test_test1_signature(self):
        sig = sign(RFC_SEED, b"")
        self.assertEqual(sig.hex(),
                         "e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e06522490155"
                         "5fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b")

    def test_test2_roundtrip(self):
        seed = bytes.fromhex("4ccd089b28ff96da9db6c346ec114e0f5b8a319f35aba624da8cf6ed4fb8a6fb")
        pub = publickey(seed)
        self.assertEqual(pub.hex(),
                         "3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c")
        sig = sign(seed, b"\x72")
        self.assertEqual(sig.hex(),
                         "92a009a9f0d4cab8720e820b5f642540a2b27b5416503f8fb3762223ebdb69da"
                         "085ac1e43e15996e458f3613d0f11d8c387b2eaeb4302aeeb00d291612bb0c00")
        self.assertTrue(verify(pub, b"\x72", sig))
        self.assertFalse(verify(pub, b"\x73", sig))

    def test_verify_fail_closed(self):
        pk = publickey(RFC_SEED)
        sig = sign(RFC_SEED, b"msg")
        self.assertFalse(verify(pk, b"msg", sig[:-1] + bytes([sig[-1] ^ 1])))
        self.assertFalse(verify(pk, b"msg", sig[:-1]))
        self.assertFalse(verify(pk, b"msg", b"short"))
        self.assertFalse(verify(b"tiny", b"msg", sig))


class TestEd25519Signer(unittest.TestCase):
    def test_kid_and_bundle(self):
        s = Ed25519Signer(RFC_SEED)
        self.assertEqual(s.kid, RFC_KID)
        self.assertEqual(s.public.hex(), RFC_PUB)
        self.assertEqual(s.key_bundle(),
                         {"alg": "EdDSA", "kid": RFC_KID, "signing": RFC_PUB_B64U})

    def test_shared_golden_vector(self):
        """Same signature as osp/core.test.mjs and SigningTest.kt."""
        pkt = _fixed_packet()
        pkt.seal(Ed25519Signer(RFC_SEED))
        self.assertEqual(pkt.sig, ED_SIG)

    def test_verify_self_and_tamper(self):
        s = Ed25519Signer(RFC_SEED)
        pkt = _fixed_packet()
        pkt.seal(s)
        obj = pkt.signed_object()
        self.assertTrue(s.verify(obj, pkt.sig))
        # any signed-field mutation invalidates the signature
        bad = dict(obj, gas=2)
        self.assertFalse(s.verify(bad, pkt.sig))
        # flipped sig bytes, truncated, and wrong scheme all fail closed
        self.assertFalse(s.verify(obj, pkt.sig[:-4] + "AAAA"))
        self.assertFalse(s.verify(obj, "not-a-jws"))
        hmac_sig = DevSigner().sign(obj)
        self.assertFalse(s.verify(obj, hmac_sig), "HMAC is not EdDSA")

    def test_non_canonical_payload_rejected(self):
        """The JWS payload must BE our canonical form, not merely verify."""
        s = Ed25519Signer(RFC_SEED)
        pkt = _fixed_packet()
        pkt.seal(s)
        header, payload, signature = pkt.sig.split(".")
        # same object, key order shuffled → different canonical bytes → reject
        shuffled = json.dumps(pkt.signed_object(), sort_keys=False,
                              separators=(",", ":"))
        forged = f"{header}.{base64.urlsafe_b64encode(shuffled.encode()).decode().rstrip('=')}.{signature}"
        self.assertFalse(s.verify(pkt.signed_object(), forged))


class TestNodeIntegration(unittest.TestCase):
    def test_node_negotiation_with_ed25519(self):
        signer = Ed25519Signer(RFC_SEED)
        chunk = "hydraulic pump failure is caused by cavitation and worn seals"

        class _Scripted:
            name = "scripted"
            remote = False

            def generate(self, query, chunks):
                return {"answer": chunks[0]["text"], "cost": {"generations": 1}}

        origin = Node("origin", RagStore([]), None, signer=signer)
        responder = Node("bot", RagStore([chunk]), _Scripted(), signer=signer)
        hub = InMemoryHub()
        origin.attach(hub, LexicalVerifier())
        responder.attach(hub, LexicalVerifier())
        out = origin.query("why does hydraulic pump failure happen", tier=0)
        self.assertEqual(out["mode"], "RESOLVED")
        self.assertIn("cavitation", out["answer"])
        # and the negotiation's packets were EdDSA-sealed end to end
        self.assertEqual(responder.signer.label, "ED25519-JWS")


if __name__ == "__main__":
    unittest.main()
