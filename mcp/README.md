# OSP v0.6 "Connect" — MCP server

Implementation starter for the Omni-Swarm Protocol (see `../requirements_v06.html` for
the requirements this satisfies and `../osp_connect_v06.html` for the architecture).

**stdlib-only** (Python ≥ 3.10, REQ-NF-01) — no pip install, offline tests (REQ-NF-03).

```
mcp/
├── osp_core.py        protocol core: packets, gas, path-vector, jti, contraction,
│                      quorum + provenance diversity, firewall, budgets, node classes
├── providers.py       D3 bindings: EchoGrounded/Confabulating (dev stubs),
│                      OpenAICompat (remote), Ollama, LLMProviderFileAdapter
├── discovery.py       lease-based node directory, TOFU pinning, replay protection
├── mcp_server.py      MCP over stdio (newline-delimited JSON-RPC 2.0) + tools
├── osp_cli.py         client for any OSP node's HTTP bridge (status/query/teach/…)
├── osp_node.py        self-hosted node: HTTP bridge + web console + one-shot CLI
│                      (the stdlib twin of ../linux/ospnode — same routes, same wire)
└── tests/             conformance tests — one per requirement criterion
```

## Run

```bash
# tests (offline, deterministic)
python3 -m unittest discover tests -v

# server over stdio (wire it into any MCP client, e.g. Claude Desktop / harnessdroid)
python3 mcp_server.py

# run a real OSP node (serve + browser console on :8090)
python3 osp_node.py --id lan-node --corpus ~/knowledge/

# one-shot negotiation from the shell (exit 0 only on RESOLVED, JSON on stdout)
python3 osp_node.py --corpus ~/knowledge/ --query "why does hydraulic pump failure happen" --tier 0
```

`osp_node.py` peers with the Android app, the whatsapp-bot and the Kotlin node
over the same sealed-packet wire (`/osp/packet`) — a Kotlin origin resolving
against this Python responder (and the reverse) is the cross-language parity
clause 5.2.1 demands, exercised live. See `../linux/ospnode/README.md` for the
full option table (identical flags) and the peering guide.

## Configuration (env vars only)

| Variable | Effect |
|---|---|
| `OSP_PROVIDER_URL` | OpenAI-compatible base URL → node becomes **N3** (provider-backed) |
| `OSP_PROVIDER_MODEL` | model id sent to the endpoint |
| `OSP_PROVIDER_API_KEY` | bearer token (optional for local servers) |
| *(no env)* | dev default **N2** with `EchoGroundedProvider` (grounded by construction) |
| `../LLMprovider/osp_endpoint.json` | when present, takes precedence — see `config.example.json` |

## MCP tools

`osp_query(text, tier)` · `osp_discover(topic, top_k)` · `osp_advertise(topic, klass)` ·
`osp_status()` · `osp_explain(query_id?)`

Quick check by hand:

```bash
echo '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}' | python3 mcp_server.py
```

## Security posture (do not skip)

- **`DevSigner` is HMAC-SHA256 with a module-wide secret — DEV-SIGNER, not production**
  (REQ-S-01). Production MUST swap in Ed25519 (RFC 8032) via COSE/JWS per v0.4. The
  signer is one class; replacing it touches nothing else.
- Provider output is **untrusted data** (REQ-F-02): it always passes the firewall
  (entailment verify, quorum tiers, reputation). `ConfabulatingProvider` exists to prove it.
- Tier-2 queries **never** go to a remote provider unless explicitly allowed (REQ-NF-02).
- The lexical verifier is dev-grade; a real NLI cross-encoder plugs into `D2Verifier`.

## Test → requirement traceability

`python3 -m unittest discover tests -v` prints each test's docstring, which names the
requirement it proves. The full matrix lives in `../osp_connect_v06.html`.
