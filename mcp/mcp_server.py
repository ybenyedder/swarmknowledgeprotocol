"""OSP MCP server (REQ-F-06) — Model Context Protocol over stdio, stdlib only.

Wire format: newline-delimited JSON-RPC 2.0 (MCP stdio transport). Implements the
handshake (`initialize`), `tools/list`, `tools/call`, and `ping`. The dispatcher
(`handle`) is importable and callable in-process, so the conformance tests never
spawn a process (REQ-NF-03).

Run standalone:   python3 mcp_server.py
Replaceable shim: the transport loop is ~20 lines — swapping to the official
MCP SDK later touches only this file (REQ-NF-01).

Tools:
  osp_query(text, tier)         full negotiation → outcome mode + trace
  osp_discover(topic, top_k)    directory lookup by centroid match
  osp_advertise(topic, klass)   register THIS node in the directory
  osp_status()                  budgets, reputation, directory, ledger
  osp_explain(query_id?)        audit trace of one query (or the last)
"""
from __future__ import annotations

import json
import sys

import osp_core
from discovery import Directory
from osp_core import InMemoryHub, LexicalVerifier, Node, NodeConfig, Packet, embed
from providers import provider_from_env

SERVER_INFO = {"name": "osp-mcp", "version": "0.6.0"}
PROTOCOL_VERSION = "2024-11-05"

# ---------------------------------------------------------------------------

TOOLS = [
    {
        "name": "osp_query",
        "description": "Run a full OSP negotiation: PROPOSE → BID → ALIGN → RESOLVE → VERIFY. "
                       "Returns the outcome mode (RESOLVED/REJECTED/NO_QUORUM/...) and the audit trace.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "text": {"type": "string", "description": "The query."},
                "tier": {"type": "integer", "enum": [0, 1, 2],
                         "description": "Stakes tier: 0 casual k=1, 1 default k=2q=2, 2 sensitive k=3q=2."},
            },
            "required": ["text"],
        },
    },
    {
        "name": "osp_discover",
        "description": "Look up live nodes in the directory, ranked by topic-centroid match.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "topic": {"type": "string"},
                "top_k": {"type": "integer", "minimum": 1, "maximum": 10},
            },
            "required": ["topic"],
        },
    },
    {
        "name": "osp_advertise",
        "description": "Register this node in the discovery directory with a topic centroid and its class.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "topic": {"type": "string"},
                "klass": {"type": "string", "enum": ["N1", "N2", "N3"]},
            },
            "required": ["topic"],
        },
    },
    {
        "name": "osp_status",
        "description": "Node status: class, provider, generation budget, reputation table, directory contents.",
        "inputSchema": {"type": "object", "properties": {}},
    },
    {
        "name": "osp_explain",
        "description": "Audit trace of a query (default: the most recent one) — every packet and verdict.",
        "inputSchema": {
            "type": "object",
            "properties": {"query_id": {"type": "string"}},
        },
    },
]


class OspMcpServer:
    """Owns one OSP node (this process), the hub, and the directory."""

    def __init__(self, node_id: str = "mcp-origin", config: NodeConfig | None = None):
        self.directory = Directory()
        self.hub = InMemoryHub()
        self.verifier = LexicalVerifier()
        self.node = Node(
            node_id=node_id,
            rag=osp_core.RagStore([]),
            d3=None,                      # origin never generates (v0.5: generation-once)
            klass="N1",
            config=config or NodeConfig(),
        )
        self.node.attach(self.hub, self.verifier)
        self.ledger: dict[str, dict] = {}      # query_id → outcome
        self._last_query: str | None = None

    # -- tool implementations ------------------------------------------------------

    def tool_osp_query(self, args: dict) -> dict:
        text = args["text"]
        tier = int(args.get("tier", 1))
        out = self.node.query(text, tier=tier, directory=self.directory)
        qid = f"q-{len(self.ledger) + 1}-{text.split()[0].lower()[:8]}"
        self.ledger[qid] = out
        self._last_query = qid
        out["query_id"] = qid
        return out

    def tool_osp_discover(self, args: dict) -> dict:
        recs = self.directory.lookup(embed(args["topic"]), top_k=int(args.get("top_k", 3)))
        return {"nodes": [
            {"node_id": r.node_id, "klass": r.klass, "transports": r.transports,
             "lease_left_s": max(0, round(r.lease_until - self.directory._now()))}
            for r in recs
        ]}

    def tool_osp_advertise(self, args: dict) -> dict:
        topic = args["topic"]
        klass = args.get("klass", self.node.klass)
        ok = self.directory.register(
            node_id=self.node.id, klass=klass,
            key_bundle={"signing": self.node.signer.label},
            codebook=[(embed(topic), topic)],
            endpoints=[], transports=["memory"],
        )
        return {"registered": ok, "node_id": self.node.id, "topic": topic, "klass": klass}

    def tool_osp_status(self, _args: dict) -> dict:
        return {
            "node_id": self.node.id,
            "class": self.node.klass,
            "signer": self.node.signer.label,     # REQ-S-01: always visible
            "budget": {"limit": self.node.budget.limit, "left": self.node.budget.left},
            "reputation": {k: round(v, 2) for k, v in self.node.reputation.items()},
            "directory": self.directory.alive(),
            "queries_served": len(self.ledger),
        }

    def tool_osp_explain(self, args: dict) -> dict:
        qid = args.get("query_id") or self._last_query
        if qid is None:
            return {"error": "no query recorded yet"}
        out = self.ledger.get(qid)
        return {"query_id": qid, **out} if out else {"error": f"unknown query_id {qid}"}

    # -- JSON-RPC / MCP layer --------------------------------------------------------

    def handle(self, msg: dict) -> dict | None:
        """One JSON-RPC message in → response dict out (or None for notifications)."""
        method = msg.get("method", "")
        mid = msg.get("id")
        try:
            if method == "initialize":
                return self._ok(mid, {
                    "protocolVersion": PROTOCOL_VERSION,
                    "capabilities": {"tools": {}},
                    "serverInfo": SERVER_INFO,
                })
            if method == "notifications/initialized" or method.startswith("notifications/"):
                return None
            if method == "ping":
                return self._ok(mid, {})
            if method == "tools/list":
                return self._ok(mid, {"tools": TOOLS})
            if method == "tools/call":
                return self._ok(mid, self._call_tool(msg["params"]))
            return self._err(mid, -32601, f"method not found: {method}")
        except Exception as exc:                    # surface, never crash the loop
            return self._err(mid, -32603, f"{type(exc).__name__}: {exc}")

    def _call_tool(self, params: dict) -> dict:
        name = params.get("name", "")
        args = params.get("arguments", {}) or {}
        impl = {
            "osp_query": self.tool_osp_query,
            "osp_discover": self.tool_osp_discover,
            "osp_advertise": self.tool_osp_advertise,
            "osp_status": self.tool_osp_status,
            "osp_explain": self.tool_osp_explain,
        }.get(name)
        if impl is None:
            return {"content": [{"type": "text", "text": f"unknown tool {name}"}],
                    "isError": True}
        result = impl(args)
        return {"content": [{"type": "text", "text": json.dumps(result, indent=2)}]}

    @staticmethod
    def _ok(mid, result: dict) -> dict:
        return {"jsonrpc": "2.0", "id": mid, "result": result}

    @staticmethod
    def _err(mid, code: int, message: str) -> dict:
        return {"jsonrpc": "2.0", "id": mid, "error": {"code": code, "message": message}}

    # -- stdio loop --------------------------------------------------------------------

    def serve_forever(self) -> None:
        for line in sys.stdin:
            line = line.strip()
            if not line:
                continue
            resp = self.handle(json.loads(line))
            if resp is not None:
                sys.stdout.write(json.dumps(resp) + "\n")
                sys.stdout.flush()


def main() -> None:
    OspMcpServer().serve_forever()


if __name__ == "__main__":
    main()
