"""MCP conformance tests (REQ-F-06, REQ-NF-04/05) — dispatcher exercised in-process,
no subprocess, no network (REQ-NF-03).
"""
from __future__ import annotations

import json
import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import mcp_server
from discovery import Directory
from osp_core import InMemoryHub, LexicalVerifier, Node, RagStore, embed
from providers import EchoGroundedProvider
from tests.test_protocol import CHUNKS_ALT, CHUNKS_HYDRAULIC, QUERY, TOPIC


class TestMcpServer(unittest.TestCase):

    def setUp(self):
        self.srv = mcp_server.OspMcpServer(node_id="mcp-origin")

        # Two grounded responder nodes wired into the same hub + directory.
        self.b = Node("node_b", RagStore(CHUNKS_HYDRAULIC), EchoGroundedProvider())
        self.c = Node("node_c", RagStore(CHUNKS_ALT), EchoGroundedProvider())
        hub = InMemoryHub()
        for n in (self.b, self.c):
            n.attach(hub, LexicalVerifier())
            self.srv.hub.join(n)
            self.srv.directory.register(
                node_id=n.id, klass=n.klass,
                key_bundle={"signing": n.signer.label},
                codebook=[(embed(TOPIC), TOPIC)],
            )

    def call(self, name: str, args: dict | None = None) -> dict:
        """tools/call → parsed text payload."""
        msg = {"jsonrpc": "2.0", "id": 1, "method": "tools/call",
               "params": {"name": name, "arguments": args or {}}}
        resp = self.srv.handle(msg)
        self.assertNotIn("error", resp, resp)
        return json.loads(resp["result"]["content"][0]["text"])

    def rpc(self, method: str, params: dict | None = None, id: int | None = 1):
        return self.srv.handle({"jsonrpc": "2.0", "id": id, "method": method,
                                "params": params or {}})

    # -- handshake (REQ-F-06) -----------------------------------------------------

    def test_initialize_and_list_tools(self):
        init = self.rpc("initialize", {"protocolVersion": "2024-11-05"})
        self.assertEqual(init["result"]["serverInfo"]["name"], "osp-mcp")
        tools = self.rpc("tools/list")["result"]["tools"]
        names = {t["name"] for t in tools}
        self.assertEqual(names, {"osp_query", "osp_discover", "osp_advertise",
                                 "osp_status", "osp_explain"})
        self.assertIsNone(self.rpc("notifications/initialized", id=None))

    def test_unknown_tool_is_error(self):
        msg = {"jsonrpc": "2.0", "id": 2, "method": "tools/call",
               "params": {"name": "nope", "arguments": {}}}
        resp = self.srv.handle(msg)
        self.assertTrue(resp["result"]["isError"])

    # -- the protocol over MCP ------------------------------------------------------

    def test_query_resolved_via_mcp(self):
        out = self.call("osp_query", {"text": QUERY, "tier": 1})
        self.assertEqual(out["mode"], "RESOLVED")
        self.assertIn("seals", out["answer"])
        self.assertIn("query_id", out)

    def test_discover_and_status(self):
        self.call("osp_advertise", {"topic": TOPIC, "klass": "N1"})
        disc = self.call("osp_discover", {"topic": QUERY, "top_k": 3})
        ids = [n["node_id"] for n in disc["nodes"]]
        self.assertIn("node_b", ids)
        self.assertIn("mcp-origin", ids)
        status = self.call("osp_status")
        self.assertEqual(status["signer"], "DEV-SIGNER")     # REQ-S-01: visible
        self.assertIn("node_b", status["directory"])

    def test_explain_trace_complete(self):
        """REQ-NF-04 — every query leaves a full audit trace."""
        out = self.call("osp_query", {"text": QUERY, "tier": 1})
        qid = out["query_id"]
        explained = self.call("osp_explain", {"query_id": qid})
        actions = [t.get("action") for t in explained["trace"]]
        self.assertIn("PROPOSE", actions)
        self.assertIn("BID", actions)
        self.assertIn("RESOLVE", actions)
        # default: last query
        self.assertEqual(self.call("osp_explain", {})["query_id"], qid)


if __name__ == "__main__":
    unittest.main()
