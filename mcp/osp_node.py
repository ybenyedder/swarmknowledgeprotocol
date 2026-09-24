#!/usr/bin/env python3
"""osp_node.py — a self-hosted OSP v0.6 node for Linux (any Python ≥ 3.10).

The stdlib twin of ../linux/ospnode (Kotlin): the same protocol core rules,
the same HTTP routes as the Android ospbridge app, the same browser console
and the same sealed-packet wire — so the tablet, the whatsapp-bot, the Kotlin
node and this one all peer interchangeably.

  python3 osp_node.py [options]            serve on --port (default)
  python3 osp_node.py --query "…"          one-shot negotiation, JSON out (exit
                                           0 only on RESOLVED)

Two nodes in one process, like OspService on Android: an N1 thin origin that
relays to the configured peers over HTTP, and a local N2/N3 responder whose
generation rung is the configured provider (echo by default — grounded by
construction; openai/auto reads OSP_PROVIDER_URL / MODEL / API_KEY).

Surfaces (token-gated, except / and /osp/status):
  GET  /                     browser console
  GET  /osp/status           node status JSON
  GET  /osp/endpoint.json    provider contract (LLMProviderFileAdapter)
  GET|POST /osp/peers        peer table (ospbridge format)
  GET  /v1/models            OpenAI-compatible model list
  POST /v1/chat/completions  RAW generation passthrough (unverified)
  POST /osp/query            verified negotiation outcome
  POST /osp/packet           sealed packet in → sealed reply out
  POST /osp/teach            {text} → one knowledge chunk

DEV POSTURE: HMAC dev signer + cleartext HTTP — lab/LAN only (REQ-S-01).
Auth failures are logged to stderr (with the token prefix) before dispatch —
a wrong token must leave a trace, not silence.
"""
from __future__ import annotations

import argparse
import json
import os
import re
import sys
import time
import urllib.request
import urllib.error
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from osp_core import (PACKET_VERSION, Action, DevSigner, LexicalVerifier,  # noqa: E402
                      Mode, Node, NodeConfig, Packet, RagStore, chunk_hash, embed)
from providers import (EchoGroundedProvider, OpenAICompatProvider,  # noqa: E402
                       provider_from_env)


def packet_to_wire(pkt: Packet) -> dict:
    """The sealed-packet wire object (osp_cli.py uses the same shape)."""
    return {**pkt.signed_object(), "sig": pkt.sig}


def packet_from_wire(w: dict) -> Packet:
    """Rebuild a Packet from a wire object so its signature can be verified."""
    pkt = Packet(
        action=Action(w["action"]), origin_id=w["origin_id"], query_id=w["query_id"],
        sender=w["sender"], gas=int(w["gas"]),
        trail=[dict(hop) for hop in w.get("trail", [])],
        payload=dict(w.get("payload", {})),
        packet_id=w["packet_id"], jti=w["jti"],
        ts=float(w["ts"]), exp_s=int(w.get("exp_s", 60)),
        version=w.get("v", PACKET_VERSION),
    )
    pkt.sig = w["sig"]
    return pkt


# ---------------------------------------------------------------------------
# The node — Python twin of linux/ospnode's LinuxNode
# ---------------------------------------------------------------------------

class OspNode:
    """An N1 origin + a local N2/N3 responder in one process.

    Configured remotes are candidates FIRST (they are the reason the node
    peers at all); the local responder fills the remaining slot — and is the
    whole swarm of one (T0, clause 4.3) when no remote is configured.
    """

    def __init__(self, node_id: str, provider, signer: DevSigner | None = None,
                 config: NodeConfig | None = None):
        self.id = node_id
        self.provider = provider
        self.signer = signer or DevSigner()
        self.token = ""                       # bridge link secret (transport only)
        self.rag = RagStore([])
        self.remotes: dict[str, dict] = {}    # nodeId → {"url": …, "token": …|None}
        self.last_outcome: dict | None = None
        self.started_at = time.time()
        self.responder = Node(node_id, self.rag, provider, self.signer)
        self.origin = Node("origin-" + node_id, RagStore([]), None,
                           self.signer, klass="N1")
        hub = _HybridHub(self)
        self.responder.attach(hub, LexicalVerifier())
        self.origin.attach(hub, LexicalVerifier())

    # -- local operations -------------------------------------------------------

    def teach(self, text: str) -> bool:
        t = text.strip()
        if not t:
            return False
        self.rag.chunks.append({"hash": chunk_hash(t), "text": t, "vec": embed(t)})
        return True

    def set_peers(self, peers: dict) -> None:
        """Accepts both compact {"id": "http://…"} and {"id": {"url", "token"}}."""
        norm: dict[str, dict] = {}
        for node_id, v in peers.items():
            norm[node_id] = v if isinstance(v, dict) else {"url": str(v), "token": None}
        self.remotes = norm

    def submit_query(self, text: str, tier: int) -> dict:
        out = self.origin.query(text, max(0, min(2, tier)))
        self.last_outcome = out
        return out

    def generate_raw(self, prompt: str) -> str | None:
        """Raw, unverified passthrough for the OpenAI-compatible route."""
        if self.provider is None or not self.provider_available:
            return None
        return self.provider.generate(prompt, [])["answer"]

    # -- introspection ------------------------------------------------------------

    @property
    def provider_name(self) -> str:
        return getattr(self.provider, "name", "none") if self.provider else "none"

    @property
    def provider_available(self) -> bool:
        """An unconfigured N3 must not claim availability (REQ-F-04 abstention)."""
        p = self.provider
        if p is None:
            return False
        if isinstance(p, OpenAICompatProvider):
            return bool(p.base_url and p.model)
        return True

    def status_map(self, http_port: int, uptime_s: int) -> dict:
        return {
            "node_id": self.id,
            "node_class": self.responder.klass,
            "provider": self.provider_name,
            "provider_available": self.provider_available,
            "signer": self.signer.label,
            "budget_left": self.responder.budget.left,
            "chunks": len(self.rag.chunks),
            "http_port": http_port,
            "peers": list(self.remotes),
            "uptime_s": uptime_s,
            "last_outcome": self.last_outcome,
            "packet_version": PACKET_VERSION,
        }

    # -- origin transport (HTTP to remotes, in-process to the responder) ----------

    def send_remote(self, peer: dict, pkt: Packet) -> Packet | None:
        url = peer["url"].rstrip("/") + "/osp/packet"
        token = peer.get("token")
        # both spellings so either server-side check accepts the same secret
        headers = {"Content-Type": "application/json"}
        if token:
            headers["x-api-token"] = token
            headers["Authorization"] = f"Bearer {token}"
        req = urllib.request.Request(url, data=json.dumps(packet_to_wire(pkt)).encode(),
                                     method="POST", headers=headers)
        try:
            # remote generation (ALIGN/RESOLVE against an LLM) takes minutes
            with urllib.request.urlopen(req, timeout=300) as r:
                if r.status == 204:
                    return None                      # forged/replayed — silent
                data = json.loads(r.read() or b"{}")
        except (urllib.error.URLError, OSError, ValueError) as e:
            print(f"hub: send to {url} failed — {e}", file=sys.stderr)  # never silent
            return None
        return packet_from_wire(data) if data else None


class _HybridHub:
    """Hub seam joining the local responder and the HTTP peers (send/join/peers)."""

    def __init__(self, node: OspNode):
        self._node = node

    def join(self, node: Node) -> None:
        node.hub = self

    def peers(self, node_id: str) -> list[str]:
        peers = list(self._node.remotes)
        if node_id != self._node.id:
            peers.append(self._node.id)
        return peers

    def send(self, _from: str, to: str, pkt: Packet) -> Packet | None:
        if to == self._node.id:
            return self._node.responder.on_packet(pkt)
        peer = self._node.remotes.get(to)
        return self._node.send_remote(peer, pkt) if peer else None


# ---------------------------------------------------------------------------
# HTTP bridge — same routes and auth as the Android HttpBridge
# ---------------------------------------------------------------------------

# The browser console — byte-identical in spirit to ConsolePage.kt in
# ../linux/ospnode: static page, no secrets, token pasted once and kept in
# localStorage, everything same-origin so no CORS is involved.
CONSOLE_HTML = """<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>osp-node console</title>
<style>
  :root { --ink:#16211c; --dim:#5f6f66; --line:#d7ddd9; --ok:#1a7f4f; --bad:#b3372f; --card:#f6f8f7; }
  * { box-sizing:border-box; }
  body { font:15px/1.5 system-ui, sans-serif; color:var(--ink); max-width:860px; margin:0 auto; padding:24px 16px 64px; }
  h1 { font-size:20px; margin:0 0 4px; } h1 code { font-size:14px; color:var(--dim); }
  .sub { color:var(--dim); font-size:13px; margin-bottom:20px; }
  section { background:var(--card); border:1px solid var(--line); border-radius:10px; padding:16px; margin-bottom:16px; }
  h2 { font-size:14px; text-transform:uppercase; letter-spacing:.06em; color:var(--dim); margin:0 0 10px; }
  label { display:block; font-size:12px; color:var(--dim); margin:10px 0 4px; }
  input, textarea, select { width:100%; font:inherit; padding:8px 10px; border:1px solid var(--line); border-radius:8px; background:#fff; }
  textarea { resize:vertical; min-height:64px; }
  button { font:inherit; padding:8px 18px; border:0; border-radius:8px; background:var(--ink); color:#fff; cursor:pointer; margin-top:10px; }
  button.sec { background:#fff; color:var(--ink); border:1px solid var(--line); }
  .row { display:flex; gap:10px; } .row > * { flex:1; }
  pre { background:#0f1713; color:#d8e5de; padding:12px; border-radius:8px; overflow:auto; font-size:12.5px; white-space:pre-wrap; }
  .mode { display:inline-block; padding:2px 10px; border-radius:999px; font-size:12px; font-weight:600; }
  .mode.RESOLVED { background:#e0f2e9; color:var(--ok); }
  .mode.BAD { background:#fbe9e7; color:var(--bad); }
  .answer { margin:10px 0; padding:12px; background:#fff; border:1px solid var(--line); border-radius:8px; }
  .meta { color:var(--dim); font-size:12.5px; }
  .err { color:var(--bad); font-size:13px; margin-top:8px; }
</style>
</head>
<body>
<h1>osp-node console</h1>
<div class="sub">Omni-Swarm Protocol v0.6 — verified negotiation from the browser</div>

<section>
  <h2>Connection</h2>
  <label>Link token (kept in this browser, sent as x-api-token)</label>
  <div class="row">
    <input id="token" type="password" placeholder="node link secret">
    <button class="sec" style="flex:0 0 auto" onclick="saveToken()">Save</button>
  </div>
  <div id="status" class="meta" style="margin-top:10px">status: &mdash;</div>
</section>

<section>
  <h2>Query (verified path &mdash; PROPOSE / BID / ALIGN / RESOLVE / verify)</h2>
  <textarea id="query" placeholder="e.g. why does hydraulic pump failure happen"></textarea>
  <label>Privacy tier</label>
  <select id="tier">
    <option value="0">T0 &mdash; public (k=1, q=1)</option>
    <option value="1" selected>T1 &mdash; personal (k=2, q=2)</option>
    <option value="2">T2 &mdash; sensitive (k=3, q=2, no remote provider)</option>
  </select>
  <button onclick="ask()">Ask the swarm</button>
  <div id="qerr" class="err"></div>
  <div id="outcome"></div>
</section>

<section>
  <h2>Teach (add a knowledge chunk)</h2>
  <textarea id="chunk" placeholder="A fact this node may answer from&hellip;"></textarea>
  <button onclick="teach()">Add chunk</button>
  <div id="terr" class="err"></div>
</section>

<section>
  <h2>Peers (remote OSP nodes)</h2>
  <div class="row">
    <input id="peerId" placeholder="node id, e.g. tablet-osp">
    <input id="peerUrl" placeholder="http://192.168.1.20:8090">
  </div>
  <label>Link secret (optional &mdash; leave empty for open dev nodes)</label>
  <input id="peerToken" type="password">
  <button onclick="addPeer()">Add / update peer</button>
  <div id="perr" class="err"></div>
  <div id="peers" class="meta" style="margin-top:10px"></div>
</section>

<script>
function tok() { return document.getElementById('token').value.trim(); }
function hdrs() { return {'Content-Type':'application/json', 'x-api-token': tok()}; }
function saveToken() { localStorage.setItem('osp_token', tok()); status(); }

function status() {
  fetch('/osp/status').then(function(r){ return r.json(); }).then(function(s) {
    document.getElementById('status').textContent =
      'node ' + s.node_id + ' (' + s.node_class + ') · provider ' + s.provider +
      ' · chunks ' + s.chunks + ' · budget left ' + s.budget_left +
      ' · peers ' + (s.peers || []).length + ' · signer ' + s.signer;
    loadPeers();
  }).catch(function(e) { document.getElementById('status').textContent = 'status: unreachable (' + e + ')'; });
}

function ask() {
  document.getElementById('qerr').textContent = '';
  var body = JSON.stringify({query: document.getElementById('query').value,
                             tier: parseInt(document.getElementById('tier').value, 10)});
  fetch('/osp/query', {method:'POST', headers: hdrs(), body: body})
    .then(function(r){ return r.json(); })
    .then(function(d) { render(d.outcome); })
    .catch(function(e) { document.getElementById('qerr').textContent = 'failed: ' + e; });
}

function render(o) {
  var cls = o.mode === 'RESOLVED' ? 'mode RESOLVED' : 'mode BAD';
  var html = '<p><span class="' + cls + '">' + o.mode + '</span>' +
    (o.groundedness != null ? ' <span class="meta">groundedness ' + o.groundedness + '</span>' : '') +
    (o.budget_left != null ? ' <span class="meta">· budget left ' + o.budget_left + '</span>' : '') + '</p>';
  if (o.answer) html += '<div class="answer">' + escapeHtml(o.answer) + '</div>';
  if (o.detail) html += '<div class="meta">' + escapeHtml(o.detail) + '</div>';
  html += '<pre>' + escapeHtml(JSON.stringify(o.trace, null, 2)) + '</pre>';
  document.getElementById('outcome').innerHTML = html;
}

function escapeHtml(s) {
  return String(s).replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;');
}

function teach() {
  document.getElementById('terr').textContent = '';
  fetch('/osp/teach', {method:'POST', headers: hdrs(),
    body: JSON.stringify({text: document.getElementById('chunk').value})})
    .then(function(r){ return r.json(); })
    .then(function(d) { document.getElementById('chunk').value = '';
      document.getElementById('terr').style.color = '#1a7f4f';
      document.getElementById('terr').textContent = 'ok — ' + d.chunks + ' chunks in store'; status(); })
    .catch(function(e) { document.getElementById('terr').textContent = 'failed: ' + e; });
}

function loadPeers() {
  fetch('/osp/peers', {headers: hdrs()}).then(function(r){ return r.json(); }).then(function(p) {
    var names = Object.keys(p);
    document.getElementById('peers').textContent =
      names.length ? names.map(function(n){ return n + ' → ' + (p[n].url || p[n]); }).join('  ·  ')
                   : 'no remote peers — this node is a swarm of one (T0)';
  }).catch(function(){ /* token not set yet */ });
}

function addPeer() {
  document.getElementById('perr').textContent = '';
  var id = document.getElementById('peerId').value.trim();
  var url = document.getElementById('peerUrl').value.trim();
  if (!id || !url) { document.getElementById('perr').textContent = 'node id and URL required'; return; }
  var entry = {url: url};
  var t = document.getElementById('peerToken').value.trim();
  if (t) entry.token = t;
  var peers = {}; peers[id] = entry;
  fetch('/osp/peers', {method:'POST', headers: hdrs(), body: JSON.stringify({peers: peers})})
    .then(function(r){ return r.json(); })
    .then(function(){ status(); })
    .catch(function(e) { document.getElementById('perr').textContent = 'failed: ' + e; });
}

document.getElementById('token').value = localStorage.getItem('osp_token') || '';
status();
</script>
</body>
</html>
"""


class _Bridge(BaseHTTPRequestHandler):
    node: OspNode = None          # bound by make_server()

    # -- plumbing ---------------------------------------------------------------

    def log_message(self, fmt: str, *args) -> None:  # noqa: D102 (compact stderr)
        sys.stderr.write(f"http: {self.address_string()} {fmt % args}\n")

    def _token_seen(self) -> str:
        auth = self.headers.get("Authorization") or ""
        return auth[7:] if auth.startswith("Bearer ") else (self.headers.get("x-api-token") or "")

    def _authorized(self) -> bool:
        if self.path in ("/", "/osp/status"):
            return True
        if self._token_seen() == self.node.token:
            return True
        # auth failures are logged BEFORE dispatch — a wrong token must
        # leave a trace (the gap that cost 30 min on the live chain)
        sys.stderr.write(
            f"auth: 401 for {self.command} {self.path} — token "
            f"{(self._token_seen() or '(none)')[:6]}… did not match "
            f"{self.node.token[:6]}…\n")
        return False

    def _body(self) -> str:
        n = int(self.headers.get("Content-Length") or 0)
        return self.rfile.read(n).decode("utf-8") if n else ""

    def _json(self, code: int, payload) -> None:
        body = json.dumps(payload).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Connection", "close")
        self.end_headers()
        self.wfile.write(body)

    def _html(self, code: int, page: str) -> None:
        body = page.encode()
        self.send_response(code)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Connection", "close")
        self.end_headers()
        self.wfile.write(body)

    # -- dispatch -----------------------------------------------------------------

    def do_GET(self) -> None:  # noqa: N802 (http.server contract)
        self._route("GET")

    def do_POST(self) -> None:  # noqa: N802
        self._route("POST")

    def _route(self, method: str) -> None:
        path = self.path.split("?", 1)[0]
        try:
            if not self._authorized():
                self._json(401, {"error": "unauthorized"})
                return
            if method == "GET" and path == "/":
                self._html(200, CONSOLE_HTML)
            elif method == "GET" and path == "/osp/status":
                self._json(200, self.node.status_map(
                    self.server.server_address[1],
                    int(time.time() - self.node.started_at)))
            elif method == "GET" and path == "/osp/endpoint.json":
                self._json(200, self._endpoint_json())
            elif method == "GET" and path == "/v1/models":
                self._json(200, self._models())
            elif method == "POST" and path == "/v1/chat/completions":
                self._json(200, self._completions(self._body()))
            elif method == "POST" and path == "/osp/query":
                self._json(200, self._query(self._body()))
            elif method == "POST" and path == "/osp/packet":
                self._json(200, self._packet(self._body()))
            elif method == "POST" and path == "/osp/teach":
                self._json(200, self._teach(self._body()))
            elif method == "GET" and path == "/osp/peers":
                self._json(200, self._peers_payload())
            elif method == "POST" and path == "/osp/peers":
                self._json(200, self._set_peers(self._body()))
            else:
                self._json(404, {"error": "not found", "path": path})
        except (ValueError, KeyError) as e:
            self._json(400, {"error": str(e)})
        except Exception as e:                     # noqa: BLE001 — honest 500, never a hang
            sys.stderr.write(f"http: request failed — {e!r}\n")
            self._json(500, {"error": str(e)})

    # -- route bodies ---------------------------------------------------------------

    def _query(self, body: str) -> dict:
        req = json.loads(body)
        # "query" is the contract used by osp_cli.py and osp-js; "text" kept
        # as the original bridge spelling
        text = req.get("query") or req.get("text")
        if not text:
            raise ValueError("query (string) required")
        tier = req.get("tier", 1)          # never `or 1`: tier 0 is valid (falsy-zero)
        tier = 1 if tier is None else int(tier)
        out = self.node.submit_query(str(text), tier)
        return {"outcome": out, "node": self.node.id}

    def _packet(self, body: str) -> dict:
        wire = json.loads(body)
        pkt = packet_from_wire(wire)
        # forged/replayed packets yield no reply — an empty object, not a 404
        reply = self.node.responder.on_packet(pkt)
        return packet_to_wire(reply) if reply else {}

    def _teach(self, body: str) -> dict:
        req = json.loads(body)
        text = req.get("text")
        if not text:
            raise ValueError("text required")
        ok = self.node.teach(str(text))
        return {"ok": ok, "chunks": len(self.node.rag.chunks)}

    def _set_peers(self, body: str) -> dict:
        req = json.loads(body)
        peers = req.get("peers")
        if not isinstance(peers, dict):
            raise ValueError("peers object required")
        self.node.set_peers(peers)
        return {"ok": True, "peers": list(self.node.remotes)}

    def _peers_payload(self) -> dict:
        """Compact form for tokenless peers — what the Android bridge and the
        Kotlin node render ({"id": "http://…"} vs {"id": {"url", "token"}})."""
        return {
            node_id: (p["url"] if not p.get("token")
                      else {"url": p["url"], "token": p["token"]})
            for node_id, p in self.node.remotes.items()
        }

    def _models(self) -> dict:
        return {"object": "list", "data": [
            {"id": self.node.provider_name, "object": "model", "owned_by": "ospnode"},
        ]}

    def _completions(self, body: str) -> dict:
        req = json.loads(body)
        messages = req.get("messages") or []
        prompt = next((m.get("content") for m in reversed(messages)
                       if isinstance(m, dict) and m.get("role") == "user"), None)
        if prompt is None:
            raise ValueError("no user message")
        text = self.node.generate_raw(str(prompt))
        if text is None:
            raise ValueError("provider not available")
        return {
            "id": "chatcmpl-osp-" + uuid.uuid4().hex[:12],
            "object": "chat.completion",
            "model": self.node.provider_name,
            "choices": [{"index": 0,
                         "message": {"role": "assistant", "content": text},
                         "finish_reason": "stop"}],
        }

    def _endpoint_json(self) -> dict:
        port = self.server.server_address[1]
        lan = lan_address()
        return {
            "base_url": f"http://{lan}:{port}/v1",
            "model": self.node.provider_name,
            "api_key_env": "OSP_TOKEN",
            "osp_packet_url": f"http://{lan}:{port}/osp/packet",
            "osp_node_id": self.node.id,
        }


def make_server(node: OspNode, port: int = 8090) -> ThreadingHTTPServer:
    """Bind the bridge (port 0 → ephemeral; read server_address[1])."""
    handler = type("BoundBridge", (_Bridge,), {"node": node})
    srv = ThreadingHTTPServer(("0.0.0.0", port), handler)
    srv.daemon_threads = True
    return srv


def lan_address() -> str:
    """Best-effort site-local IPv4 for the banner / endpoint.json."""
    import socket
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(("10.255.255.255", 1))           # no packet leaves the host
        return s.getsockname()[0]
    except OSError:
        return "127.0.0.1"
    finally:
        s.close()


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------

def load_corpus(node: OspNode, path: str) -> int:
    """Teach from a .txt/.md file or a directory walked recursively.

    Blank-line paragraphs become individual chunks — the retrieval granularity
    the pre-bid gate scores (clause 5.5.1)."""
    p = os.path.expanduser(path)
    if not os.path.exists(p):
        sys.exit(f"ospnode: corpus path not found: {path}")
    if os.path.isdir(p):
        files = [os.path.join(root, f)
                 for root, _dirs, names in os.walk(p)
                 for f in names if f.rsplit(".", 1)[-1].lower() in ("txt", "md", "markdown")]
    else:
        files = [p]
    n = 0
    for fp in files:
        with open(fp, encoding="utf-8") as fh:
            for para in _PARAGRAPH.split(fh.read()):
                if node.teach(para):
                    n += 1
    return n


_PARAGRAPH = re.compile(r"\n\s*\n")


def serve(node: OspNode, port: int) -> None:
    srv = make_server(node, port)
    bound = srv.server_address[1]
    availability = "available" if node.provider_available \
        else "NOT available — honest abstention (REQ-F-04)"
    print(f"osp-node — OSP v{PACKET_VERSION} Connect "
          f"({node.responder.klass}, provider {node.provider_name}, {availability})")
    print(f"  node id    : {node.id}")
    print(f"  HTTP       : http://0.0.0.0:{bound}/  (LAN http://{lan_address()}:{bound}/)")
    print(f"  web console: http://localhost:{bound}/")
    print(f"  link token : {node.token[:6]}…  "
          f"(peers send it as x-api-token or Authorization: Bearer)")
    print(f"  knowledge  : {len(node.rag.chunks)} chunks · peers: "
          f"{', '.join(node.remotes) if node.remotes else '(none — swarm of one)'}")
    print("DEV POSTURE: HMAC dev signer + cleartext HTTP — lab/LAN only (REQ-S-01).")
    try:
        srv.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        srv.server_close()


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(
        description="OSP v0.6 node for Linux — HTTP bridge, web console, one-shot CLI",
        epilog="env: OSP_TOKEN, OSP_PROVIDER_URL, OSP_PROVIDER_MODEL, OSP_PROVIDER_API_KEY",
    )
    ap.add_argument("--id", help="node id (default osp-XXXXXXXX)")
    ap.add_argument("--port", type=int, default=8090, help="HTTP bridge port (0 = ephemeral)")
    ap.add_argument("--token", help="link secret (default: OSP_TOKEN env, else generated)")
    ap.add_argument("--peer", action="append", default=[], metavar="ID=URL",
                    help="add a remote peer (repeatable)")
    ap.add_argument("--peer-token", action="append", default=[], metavar="ID=SECRET",
                    help="link secret of a --peer (repeatable)")
    ap.add_argument("--peers-file", help='JSON {"peers": {…}} — the ospbridge format')
    ap.add_argument("--corpus", action="append", default=[], metavar="PATH",
                    help="teach a .txt/.md file or directory at startup (repeatable)")
    ap.add_argument("--provider", choices=["auto", "echo", "openai"], default="auto",
                    help="echo (grounded stub) | openai (env above) | auto (default)")
    ap.add_argument("--query", help="run one negotiation and exit (exit 0 only if RESOLVED)")
    ap.add_argument("--tier", type=int, default=1, choices=[0, 1, 2])
    ap.add_argument("--allow-remote-t2", action="store_true",
                    help="let T2 queries reach remote providers (default deny, REQ-NF-02)")
    args = ap.parse_args(argv)

    if args.provider == "echo":
        provider = EchoGroundedProvider()
    elif args.provider == "openai":
        provider = OpenAICompatProvider(os.environ.get("OSP_PROVIDER_URL", ""),
                                        os.environ.get("OSP_PROVIDER_MODEL", ""),
                                        os.environ.get("OSP_PROVIDER_API_KEY", ""))
    else:
        provider = provider_from_env()

    peers: dict = {}
    for spec in args.peer:
        if "=" not in spec:
            sys.exit('ospnode: --peer wants id=url')
        k, v = spec.split("=", 1)
        peers[k.strip()] = v.strip()
    for spec in args.peer_token:
        if "=" not in spec:
            sys.exit('ospnode: --peer-token wants id=secret')
        k, v = spec.split("=", 1)
        if k.strip() not in peers:
            sys.exit(f"ospnode: --peer {k.strip()} must come before --peer-token")
        peers[k.strip()] = {"url": peers[k.strip()], "token": v.strip()}
    if args.peers_file:
        with open(os.path.expanduser(args.peers_file), encoding="utf-8") as fh:
            try:
                raw = json.load(fh).get("peers")
            except (ValueError, AttributeError):
                raw = None
        if not isinstance(raw, dict):
            sys.exit('ospnode: peers file must contain a top-level {"peers": {…}} object')
        peers.update(raw)

    node = OspNode(args.id or ("osp-" + uuid.uuid4().hex[:8]), provider,
                   config=NodeConfig(allow_remote_t2=args.allow_remote_t2))
    for c in args.corpus:
        # stderr: the one-shot --query mode must keep stdout for the JSON payload only
        print(f"  corpus {c} → {load_corpus(node, c)} chunks", file=sys.stderr)
    if peers:
        node.set_peers(peers)

    if args.query:
        # one-shot: no bridge is served, the link token is irrelevant
        out = node.submit_query(args.query, args.tier)
        print(json.dumps({"outcome": out, "node": node.id}))
        return 0 if out["mode"] == Mode.RESOLVED.value else 1

    node.token = args.token or os.environ.get("OSP_TOKEN") or uuid.uuid4().hex[:24]
    if not (args.token or os.environ.get("OSP_TOKEN")):
        print(f"link token (generated, share it with peers): {node.token}")
    serve(node, args.port)
    return 0


if __name__ == "__main__":
    sys.exit(main())
