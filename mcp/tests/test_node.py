"""Offline tests for osp_node.py — the stdlib twin of ../linux/ospnode.

Same routes, same double-header auth, same sealed-packet wire as the Android
ospbridge HttpBridge. Loopback only, deterministic, no remote calls (REQ-NF-03).
"""
from __future__ import annotations

import json
import os
import sys
import threading
import unittest
import urllib.error
import urllib.request

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import osp_node as impl
from osp_core import Action, DevSigner, Mode, Packet, embed
from providers import EchoGroundedProvider

TOKEN = "test-link-secret"


def call(port, method, path, token=None, body=None):
    """Minimal client — both header spellings like osp_cli.py's call().
    Returns (status, payload); non-JSON bodies come back as text."""
    headers = {"Content-Type": "application/json"}
    if token:
        headers["x-api-token"] = token
    req = urllib.request.Request(
        f"http://127.0.0.1:{port}{path}", method=method,
        data=json.dumps(body).encode() if body is not None else None,
        headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            return _decode(r.status, r.read())
    except urllib.error.HTTPError as e:
        return _decode(e.code, e.read())


def _decode(status, raw):
    text = raw.decode() if raw else ""
    try:
        return status, (json.loads(text) if text else None)
    except ValueError:
        return status, text


def serve(node):
    srv = impl.make_server(node, 0)
    port = srv.server_address[1]
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    return srv, port


class NodeServerTest(unittest.TestCase):
    """One fresh node + bridge for the whole class; order-independent tests
    spin their own node when they need an empty store."""

    @classmethod
    def setUpClass(cls):
        cls.node = impl.OspNode("py-test", EchoGroundedProvider())
        cls.node.token = TOKEN
        cls.srv, cls.port = serve(cls.node)

    @classmethod
    def tearDownClass(cls):
        cls.srv.shutdown()
        cls.srv.server_close()

    def test_status_open_query_needs_token(self):
        """/osp/status is open; /osp/query refuses a missing or wrong token."""
        s, p = call(self.port, "GET", "/osp/status")
        self.assertEqual(200, s)
        self.assertEqual("py-test", p["node_id"])

        s, _ = call(self.port, "POST", "/osp/query", body={"query": "hello"})
        self.assertEqual(401, s)
        s, _ = call(self.port, "POST", "/osp/query", token="wrong-secret",
                    body={"query": "hello"})
        self.assertEqual(401, s)

        # HttpHub's second header spelling must be accepted too
        req = urllib.request.Request(
            f"http://127.0.0.1:{self.port}/osp/query", method="POST",
            data=json.dumps({"query": "hello"}).encode(),
            headers={"Authorization": f"Bearer {TOKEN}"})
        with urllib.request.urlopen(req, timeout=30) as r:
            self.assertEqual(200, r.status)

    def test_teach_then_query_resolves_with_groundedness(self):
        """Own node: the shared class node's store is polluted by other tests
        (and the tests must stay order-independent)."""
        node = impl.OspNode("py-fresh", EchoGroundedProvider())
        node.token = TOKEN
        srv, port = serve(node)
        try:
            s, p = call(port, "POST", "/osp/teach", token=TOKEN, body={
                "text": "A hydraulic pump failure happens when fluid contamination "
                        "blocks the relief valve."})
            self.assertEqual(200, s)
            self.assertEqual(1, p["chunks"])

            s, p = call(port, "POST", "/osp/query", token=TOKEN, body={
                "query": "why does hydraulic pump failure happen", "tier": 0})
            self.assertEqual(200, s)
            out = p["outcome"]
            self.assertEqual(Mode.RESOLVED.value, out["mode"])
            self.assertTrue(out["answer"])
            self.assertGreaterEqual(out["groundedness"], 0.35)
        finally:
            srv.shutdown()
            srv.server_close()

    def test_query_on_empty_store_abstains(self):
        """Fresh node, empty store: honest NO_QUORUM, never a confabulation."""
        node = impl.OspNode("py-empty", EchoGroundedProvider())
        node.token = TOKEN
        srv, port = serve(node)
        try:
            s, p = call(port, "POST", "/osp/query", token=TOKEN, body={
                "query": "what about an unknown topic", "tier": 0})
            self.assertEqual(200, s)
            self.assertEqual(Mode.NO_QUORUM.value, p["outcome"]["mode"])
        finally:
            srv.shutdown()
            srv.server_close()

    def test_sealed_propose_gets_bid_and_forged_gets_silence(self):
        """The protocol-native surface: Python seals, Python verifies — and a
        tampered packet is silently dropped (empty object, not a 404)."""
        self.node.teach("A hydraulic pump failure happens when fluid contamination "
                        "blocks the relief valve.")
        propose = Packet(
            action=Action.PROPOSE, origin_id="cli-origin", query_id="q-1",
            sender="cli-origin", gas=3,
            payload={"query_vec": list(embed("hydraulic pump failure")),
                     "query_text": "why does hydraulic pump failure happen"},
        ).seal(DevSigner())
        s, wire = call(self.port, "POST", "/osp/packet", token=TOKEN,
                       body=impl.packet_to_wire(propose))
        self.assertEqual(200, s)
        self.assertEqual(Action.BID.value, wire["action"])
        self.assertTrue(DevSigner().verify(
            impl.packet_from_wire(wire).signed_object(), wire["sig"]))

        tampered = impl.packet_to_wire(propose)
        tampered["gas"] = 99
        tampered["jti"] = "forged-jti-00000001"     # fresh nonce: forgery, not replay
        s, wire = call(self.port, "POST", "/osp/packet", token=TOKEN, body=tampered)
        self.assertEqual(200, s)
        self.assertEqual({}, wire, "forged packets are silently dropped")

    def test_peer_table_speaks_the_ospbridge_format(self):
        s, p = call(self.port, "POST", "/osp/peers", token=TOKEN, body={"peers": {
            "tablet": {"url": "http://192.168.1.10:8090", "token": "t1"},
            "bot": "http://192.168.1.20:5000",
        }})
        self.assertEqual(200, s)
        s, p = call(self.port, "GET", "/osp/peers", token=TOKEN)
        self.assertEqual({"url": "http://192.168.1.10:8090", "token": "t1"}, p["tablet"])
        self.assertEqual("http://192.168.1.20:5000", p["bot"])

    def test_console_served_at_root_without_token(self):
        s, raw = call(self.port, "GET", "/")
        self.assertEqual(200, s)
        self.assertIn("osp-node console", raw)

    def test_openai_compatible_passthrough_answers(self):
        s, p = call(self.port, "POST", "/v1/chat/completions", token=TOKEN,
                    body={"messages": [{"role": "user", "content": "chromecast pairing"}]})
        self.assertEqual(200, s)
        content = p["choices"][0]["message"]["content"]
        # the dev echo stub answers from the query terms — proves the plumbing
        self.assertIn("chromecast", content)

    def test_provider_failure_backtracks_as_rfo(self):
        """A raising provider must end as an explicit RFO, never a 500 or a
        silent timeout (parity with the Kotlin core's onResolve)."""
        from osp_core import D3Provider

        class Exploding(D3Provider):
            name = "exploding"
            def generate(self, query, chunks):
                raise RuntimeError("engine gone")

        node = impl.OspNode("py-boom", Exploding())
        node.token = TOKEN
        srv, port = serve(node)
        try:
            node.teach("A hydraulic pump failure happens when fluid contamination "
                       "blocks the relief valve.")
            s, p = call(port, "POST", "/osp/query", token=TOKEN, body={
                "query": "why does hydraulic pump failure happen", "tier": 0})
            self.assertEqual(200, s)
            out = p["outcome"]
            self.assertEqual(Mode.NO_QUORUM.value, out["mode"])
            self.assertEqual("winner could not generate", out["detail"])
            # the provider's own failure reason travels in the trace
            rfo_hops = [t for t in out["trace"] if t.get("rfo")]
            self.assertTrue(any("provider failure" in t["rfo"] for t in rfo_hops))
        finally:
            srv.shutdown()
            srv.server_close()


if __name__ == "__main__":
    unittest.main()
