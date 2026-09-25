# Omni-Swarm Protocol (OSP)

### Knowledge negotiation for very small language models on constrained edge devices

| | |
|---|---|
| **Document** | OSP v0.6 "Connect" — protocol description and conformance overview |
| **Profiles** | v0.4 full · v0.5 Lite (edge) · v0.6 Connect (interoperability) |
| **Status** | Working specification, three interoperable implementations |
| **Normative keywords** | *shall*, *should*, *may* per clause 2 |

---

## Foreword

This README describes the Omni-Swarm Protocol (OSP) in the drafting style of a
telecommunications standard: a scope, normative definitions, numbered clauses
using requirement keywords, and conformance clauses backed by a test matrix.

The complete specification set consists of four documents, each cross-referenced
throughout this text:

- `[1]` `omni_swarm_protocol.html` — full profile **v0.4** (JWS/Ed25519 envelopes,
  contracting auctions, 3-layer hallucination firewall);
- `[2]` `osp_lite_v05.html` — edge profile **v0.5** (generation-once, decision
  ladder D0–D3, WhatsApp hub and mesh transports);
- `[3]` `requirements_v06.html` — **v0.6 "Connect"** numbered requirements (REQ-\*);
- `[4]` `osp_connect_v06.html` — v0.6 architecture, sequence diagram, test plan
  L0–L6 and requirement-to-test traceability;
- `[5]` `osp_paper.html` — technical paper synthesising the design, its research
  grounding (calibration, ontology matching, centroid routing) and the current
  validation status (informative).

Where this README and the referenced documents disagree, `[3]` prevails for
requirements and `[4]` for architecture.

## 1 Scope

The present document specifies a stateless peer-to-peer **knowledge negotiation
protocol** by which very small language models (0,5 B–1,5 B parameters, 4-bit)
running on resource-limited devices (phones, tablets, single-board computers,
messaging-bot hosts) exchange verified knowledge without a central server.

The protocol is designed so that:

- a device that **cannot generate** text at all can still *ask* and receive an
  answer it can independently verify (origin rung N1);
- a device that **can generate** only sells answers that are *grounded in
  evidence it actually holds* (responder rung N2, provider rung N3);
- every failure is **explicit**: a negotiation can only end in a bounded set of
  convergence modes, never in a silent timeout or an ungrounded guess;
- implementations **in different programming languages interoperate** over one
  wire format (the present chain is validated across Python, Kotlin and
  JavaScript).

## 2 References and keywords

| Ref | Document |
|---|---|
| [1] | `omni_swarm_protocol.html`, OSP v0.4 full profile |
| [2] | `osp_lite_v05.html`, OSP v0.5 Lite edge profile |
| [3] | `requirements_v06.html`, v0.6 requirements (REQ-F, REQ-NF, REQ-S) |
| [4] | `osp_connect_v06.html`, v0.6 architecture and test plan |
| [5] | `osp_paper.html`, technical paper (informative) |
| [6] | RFC 2119, *Key words for use in RFCs to Indicate Requirement Levels* |

The key words **"shall"** (mandatory), **"should"** (recommended) and **"may"**
(permitted) are used as in [6]. A clause of the form *"The origin shall
discard…"* is a testable requirement; clauses marked *(informative)* carry no
conformance weight.

## 3 Definitions and abbreviations

### 3.1 Definitions

**3.1.1 node** — A process participating in the protocol. A node declares a
class (3.1.2), an identifier, and a signing key bundle.

**3.1.2 node class** — One of:

| Class | Rungs held | May generate? | Typical device |
|---|---|---|---|
| **N1** thin origin | D0–D1 (ask, verify) | never | a phone app, a bot client |
| **N2** full responder | D0–D3 (ask, verify, generate) | yes, budgeted | a bot host, a tablet |
| **N3** provider-backed | D3 bound to an external model API | yes, delegated | a gateway to an LLM service |

**3.1.3 chunk** — The smallest citable unit of local knowledge held by a
responder (a document fragment, a conversation excerpt). Chunks never travel
whole unless cited (REQ-NF-02).

**3.1.4 envelope** — The closed prompt handed to a D3 generator: grounding
instructions, the query, and the cited chunks as *quoted data* inside
`<evidence>` tags (REQ-S-03). A chunk-level directive-stripper neutralises
injected imperatives before the envelope is built.

**3.1.5 bid** — A responder's offer to answer, carrying `bid`
(0,5·retrieval + 0,3·reputation + 0,2·top-chunk score), its retrieval
similarity, node class, generation capability and chunk provenance hashes.

**3.1.6 groundedness** — The fraction g of answer tokens (or, with a real NLI
model, of answer claims) entailed by the cited evidence. The origin-side
verifier rejects any answer with g below `groundedness_min` (clause 5.5.2).

**3.1.7 RFO (Request-Failure)** — The explicit failure packet backtracked to
the origin. Failure is never silent: abstention, gas exhaustion, loop
detection, TTL expiry and ungrounded answers all terminate as RFOs.

**3.1.8 convergence mode** — The terminal state of a negotiation, one of
`RESOLVED`, `REJECTED`, `MISMATCH`, `GAS_EXHAUSTED`, `LOOP_DETECTED`,
`NO_QUORUM` (REQ-F-07). All five v0.4 modes and their triggers are preserved.

**3.1.9 tier** — The privacy/sensitivity class of a query: **T0** (public,
single responder), **T1** (personal, k = 2, q = 2), **T2** (sensitive, k = 3,
q = 2, never routed to remote providers by default, REQ-NF-02).

### 3.2 Abbreviations

| | |
|---|---|
| AIDL | Android Interface Definition Language |
| EWMA | Exponentially Weighted Moving Average (reputation) |
| HMAC | Hash-based Message Authentication Code |
| JWS | JSON Web Signature |
| MCP | Model Context Protocol |
| NLI | Natural Language Inference (entailment model) |
| OSP | Omni-Swarm Protocol |
| RAG | Retrieval-Augmented Generation |
| SLM | Small Language Model |
| TOFU | Trust On First Use (key pinning, REQ-S-02) |
| TTL | Time To Live (`exp_s`, clause 5.2.3) |

## 4 System description

### 4.1 Trust model (informative)

No node is trusted. The origin trusts only what it can verify: packet
signatures, its own retrieval-competence gate, the origin-side groundedness
check on the returned answer, and reputation it has accumulated per sender.
Providers are untrusted generators (REQ-F-02): a remote provider receives only
the query and the cited evidence — never the node's corpus, identity graph or
any T2 traffic.

### 4.2 Negotiation actors

```
origin (N1)                responder (N2/N3)
    |                             |
    |--- PROPOSE (query) ------->|   pre-bid gate, no generation (5.5.1)
    |<-- BID (offer, prov.) -----|
    |--- ALIGN (lock-in) ------->|   C3: contract distance declared (5.3.3)
    |<-- ALIGN (confirmation) ---|
    |--- RESOLVE (award) ------->|   single generation, budget charged (5.3.4)
    |<-- RESOLVE (answer+prov.)--|
    |  verify groundedness (L2)  |
```
*Figure 1 (informative): the five-phase negotiation with one responder.*

### 4.3 Quorum and tiers

The origin solicits bids from k candidates and requires q verified capable
bids (C4 quorum with provenance diversity: two votes over the same top chunk
hash collapse to one):

| Tier | k | q | Remote provider allowed |
|---|---|---|---|
| T0 | 1 | 1 | yes (explicit candidate) |
| T1 | 2 | 2 | yes |
| T2 | 3 | 2 | **no** (default deny, REQ-NF-02) |

T0 exists so a swarm of one is still usable: the single bid is verified
exactly as in a larger quorum, and the origin reports honest abstention when
the lone responder cannot ground an answer.

### 4.4 Deployment surfaces

| Surface | Language | Entry points |
|---|---|---|
| MCP server + CLI + **node** | Python (stdlib only, REQ-NF-01) | `mcp/mcp_server.py`, `mcp/osp_cli.py`, `mcp/osp_node.py` |
| Linux node + web console | Kotlin/JVM (same `osp-lite` core, zero-dependency) | `linux/ospnode` → `java -jar ospnode-all.jar` (Annex D.1) |
| Android library + app | Kotlin, zero-dependency | `android/osp-lite`, `android/ospbridge` (AIDL + HTTP + OSP-over-HTTP) |
| whatsapp-bot peer | JavaScript (ESM, zero-dependency) | `osp/core.mjs`, `/osp/packet`, `/osp/query` |

All three implement the same wire format (clause 5.1) and the same canonical
serialisation; cross-language golden vectors pin the parity (clause 7).

## 5 Protocol specification

### 5.1 Packet format

A packet is a JSON object with the following members; the `sig` member is
added by sealing (5.2):

| Member | Type | Meaning |
|---|---|---|
| `v` | string | profile version, `"0.6"` |
| `action` | enum | `PROPOSE`, `BID`, `ALIGN`, `RESOLVE`, `GET_CHUNK`, `RFO`, `ACK` |
| `origin_id` | string | the asking node |
| `query_id` | string | negotiation instance identifier |
| `sender` | string | the node that sealed this packet |
| `gas` | int | remaining hops, decremented exactly once per hop (5.6.1) |
| `trail` | array | path vector `{node, action}` records (5.6.2) |
| `ts` | number | sealing time, seconds |
| `exp_s` | int | TTL (seconds); a packet older than its TTL dies (5.2.3) |
| `jti` | string | unique packet nonce, replay-protected (5.2.2) |
| `payload` | object | action-specific members (5.3) |
| `sig` | string | signature over the canonical serialisation of the above |

A `PROPOSE` payload carries `query_vec` (the query embedding) and
`query_text` (the verbatim query) so that a responder may run any retrieval
it owns.

### 5.2 Sealing, verification, replay and expiry

**5.2.1 Canonical serialisation.** Numbers shall be serialised with
Python-`repr` parity (the `pyDouble` rule: scientific notation only outside
[1e-4, 1e16), two-digit signed exponents, `-0.0` preserved, integer-valued
floats carry `.0`), keys sorted, and every non-ASCII code unit escaped as
`\uXXXX`. The rule is golden-tested across Python, Kotlin and JavaScript — a
signature computed in one language shall verify in all others.

**5.2.2 Replay.** A node shall cache received `jti` values for the TTL window
and silently discard (HTTP 204 / null reply) replays. Discovery registrations
carry their own `jti` with the same protection (REQ-S-02).

**5.2.3 Expiry.** A packet whose age exceeds `exp_s` (default 60 s) shall be
answered with an RFO carrying reason `expired` — packets die of old age, not
only of gas.

**5.2.4 Cryptography posture (REQ-S-01).** The development signer is
HMAC-SHA256 over the canonical serialisation and **is a dev-only stand-in**.
Production deployments shall use Ed25519 (JWS in v0.4 profile) and a TLS
transport. No implementation shall ship the dev secret beyond a laboratory
LAN; the Android bridge declares cleartext HTTP for exactly this reason and
labels itself dev-grade.

*Status:* all three reference implementations now ship `Ed25519Signer` —
JWS compact EdDSA over the canonical bytes, `kid` = `sha256(public key)[:12]`,
byte-identical across Python (`mcp/osp_core.py`), JavaScript (`osp/core.mjs`)
and Kotlin (`android/osp-lite` Signing.kt, pinned by a shared golden vector in
each suite). Each ships a `HybridSigner` that seals Ed25519 when a seed is
provisioned, falls back to the labelled dev HMAC otherwise, and verifies both
schemes during the migration window. TOFU pinning (REQ-S-02) is enforced
bot-side by `osp/peer.mjs` `PinStore`: first key bundle per node id is pinned,
a changed bundle is rejected until an explicit re-pin, and bootstrap only
trusts a sender's `/osp/endpoint.json` record that proves its own `node_id`.

### 5.3 Negotiation procedures

**5.3.1 PROPOSE.** The origin selects up to k candidates (from its discovery
directory or its peer table) and seals a PROPOSE to each. `gas` = G (default
3).

**5.3.2 BID — pre-bid gate (firewall layer 1).** A responder shall decide
whether to bid **without generating**: it computes its retrieval score
(5.5.1) and abstains (RFO, `NO_QUORUM`, *"no competent evidence —
abstained"*) when the score is below `bid_min` (default 0,15) or when it holds
no chunk at all. The bid carries provenance hashes, not text.

**5.3.3 ALIGN — lock-in (C3).** With C = 1 (v0.5 Lite) the origin aligns once
with the winning bidder; a drift above `align_tolerance` (default 0,05)
aborts the contract. With C > 1 (v0.4 profile) the tolerance contracts by
`gamma` = 0,95 per round.

**5.3.4 RESOLVE — the single generation.** The origin awards the winner. The
winner shall charge its budget (5.6.3) before generating, shall generate
**only** inside the envelope (3.1.4), and shall return the answer with the
cited chunks (hash + text) as provenance. A winner that cannot generate
returns an RFO (`"winner could not generate"` at the origin) — the origin
reports `NO_QUORUM` rather than an ungrounded answer.

**5.3.5 GET_CHUNK / ACK.** The origin may fetch the full text of any cited
chunk by hash; the responder answers with an ACK or an RFO (`unknown chunk`).
This is the only path by which chunk text travels on demand (REQ-NF-02).

**5.3.6 Verification and reputation (firewall layer 3).** The origin computes
groundedness (5.5.2). On success the sender's reputation rises by 0,05; on
failure it falls by 0,20 and the negotiation terminates `REJECTED`. Reputation
enters the bid formula, so unreliable responders are progressively not
selected.

### 5.4 Routing and discovery

A lease-based directory (REQ-F-05) provides register / renew / expire /
lookup. Registration shall pin the first key bundle seen (TOFU, REQ-S-02); a
re-registration with a different key is rejected and requires an explicit
re-pin. Nodes advertise centroid codebooks; the origin looks up the top-k
nearest codebooks to `query_vec` to choose candidates without flooding.

### 5.5 The hallucination firewall (three layers)

**5.5.1 Layer 1 — pre-bid competence.** No generation before a bid. The
dev-embedder score is **query-side coverage**: the fraction of query tokens
grounded by the node's best chunk, `|q ∩ c| / |q|` — the symmetric Jaccard is
unusable here because it collapses when chunk ≫ query. A production embedder
(e.g. MiniLM cosine, v0.5 [A8]) replaces the score behind the same interface
(pluggable D2/D3, REQ-F-01, REQ-F-08).

**5.5.2 Layer 2 — origin-side groundedness.** The origin verifies the answer
against the *cited* chunks only. The dev verifier is lexical (fraction of
answer tokens present in the evidence, threshold 0,35); a real NLI model with
threshold 0,8 is the committed production path. Output of any provider is
untrusted data (REQ-F-02).

**5.5.3 Layer 3 — reputation.** The EWMA described in 5.3.6. The firewall is
complete only when all three layers run: layer 1 stops incompetent responders
before they cost anything; layer 2 stops ungrounded answers; layer 3 makes
recidivism expensive.

### 5.6 Resource rules

**5.6.1 Gas (C1).** `gas` shall decrease exactly once per hop; `gas = 0` on
arrival yields RFO `GAS_EXHAUSTED`. Structural termination is mandatory: the
protocol has no unbounded loops.

**5.6.2 Path vector (C2).** A node seeing its own id in `trail` answers RFO
`LOOP_DETECTED`.

**5.6.3 Budgets (REQ-F-04).** Every D3 generation charges the node's daily
budget (default 50 generations/day). An exhausted responder abstains; a
battery-saver node stops charging its budget entirely while continuing to
route and pre-bid.

## 6 Security considerations

- **Dev crypto.** HMAC-SHA256 and cleartext HTTP are development postures
  (5.2.4); production shall use Ed25519 + TLS (REQ-S-01).
- **Prompt injection.** Evidence is quoted data inside `<evidence>` tags with
  an explicit "data, not instructions" clause and a directive-stripper
  (REQ-S-03). This is hygiene, not a defence; the origin-side verification is
  the actual containment.
- **Privacy.** T2 traffic shall not reach remote providers (REQ-NF-02);
  chunks travel only when cited or explicitly fetched (REQ-NF-02); the
  responder's corpus never leaves the node.
- **No secrets on the wire.** Link secrets (peer tokens) authenticate HTTP
  peering but never sign packets; the packet signature is the integrity
  layer.

## 7 Conformance

An implementation conforms to OSP v0.6 when it satisfies all MUST requirements
of `[3]` for the surfaces it claims, passes the golden-vector parity suite
across at least one other implementation, and terminates every negotiation in
a convergence mode of 3.1.8.

### 7.1 Test matrix (current status)

| Suite | Scope | Status |
|---|---|---|
| `mcp/tests/` (Python, offline, deterministic — REQ-NF-03) | core convergence modes, firewall, budgets, discovery TOFU/replay, MCP surface, `osp_node` HTTP bridge, RFC 8032 vectors + shared signing golden vector | 55/55 |
| `osp/core.test.mjs` + `osp/peer.test.mjs` (JavaScript) | wire parity with Python (embed, similarity, `pyDouble`, canonical JSON, sealed packets), negotiation, Ed25519/Hybrid signer + TOFU pin store | 45/45 |
| `android/osp-lite` JVM tests (Kotlin) | protocol core, discovery Q8, mini-JSON, cross-language interop vectors, Ed25519 (RFC 8032) + shared signing golden vector | 43/43 |
| `linux/ospnode` JVM tests (Kotlin) | Linux HTTP surface: auth double-header, teach → RESOLVED, forged-packet silence, ospbridge peer format | 7/7 |
| Property/fuzz (L5, planned) | differential serialisation, adversarial providers | `[4]` test plan |

### 7.2 Field validation (informative)

A live chain has been exercised end-to-end: an Android tablet (Kotlin N1
origin, sealed packets over HTTP with per-peer link secrets) → a socat relay →
a remote whatsapp-bot (JavaScript N2 responder, chroma retrieval, ollama
`gemma4:12b` generation) → `PROPOSE → BID 0,786 → ALIGN → RESOLVED`,
groundedness ≈ 0,80, ~30 s round trip, both corpus documents summarised and
verified. The same queries through the Python CLI produce byte-identical
negotiations against the same responder.

## Annex A (informative): repository layout

```
swarmknowledge_protocol/
├── README.md                        this document
├── omni_swarm_protocol.html         [1] v0.4 full profile
├── osp_lite_v05.html                [2] v0.5 Lite edge profile
├── requirements_v06.html            [3] v0.6 requirements
├── osp_connect_v06.html             [4] v0.6 architecture + test plan
├── osp_paper.html                   [5] technical paper (informative)
├── mcp/                             Python reference (stdlib only)
│   ├── osp_core.py                  protocol core (packets, firewall, tiers)
│   ├── providers.py                 D3 bindings + dev stubs + envelope
│   ├── discovery.py                 lease directory, TOFU, replay
│   ├── mcp_server.py                MCP stdio server
│   ├── osp_cli.py                   CLI: status / query / teach / peers / packet
│   ├── osp_node.py                  self-hosted node: HTTP bridge + web console
│   └── tests/                       conformance suite
└── android/                         Kotlin library + Android app
    ├── osp-lite/                    protocol core (JVM tests included) — shared
    │                                by the Android app and the Linux node
    └── ospbridge/                   foreground service: AIDL + HTTP bridge

linux/                               the same core, headless (see Annex D.1)
└── ospnode/                         JVM node: HTTP bridge + web console + CLI
```

The JavaScript core lives in the whatsapp-bot repository (`osp/core.mjs`,
`osp/peer.mjs`) and is exercised through the bot's `/osp/*` endpoints.

## Annex B (informative): quick start

```bash
# conformance tests (offline, no network)
cd mcp && python3 -m unittest discover tests -v

# start the MCP server over stdio (wire into any MCP client)
python3 mcp_server.py

# compile and run the Linux node (Annex D.1) — serve + web console
cd ../android && ./gradlew :ospnode:fatJar
java -jar ../linux/ospnode/build/libs/ospnode-all.jar --id lan-node --corpus ~/knowledge/

# …or the stdlib-only Python twin — no JDK, same routes, same wire
python3 mcp/osp_node.py --id py-node --corpus ~/knowledge/

# talk to a running OSP node (Linux node, bridge app or whatsapp-bot)
python3 osp_cli.py --url http://<node>:<port> --token <link-secret> status
python3 osp_cli.py --url http://<node>:<port> --token <link-secret> \
        query --text "why does hydraulic pump failure happen" --tier 0

# Android: install the ospbridge debug APK, start the OspService foreground
# service, then peer it with a remote node via POST /osp/peers (Annex D.3)
```

Platform guides: **Linux** → Annex D.1 and `linux/ospnode/README.md` ·
**Web** → Annex D.2 · **Android** → Annex D.3 and `android/README.md`.

## Annex C (informative): document history

| Version | Date | Content |
|---|---|---|
| v0.4 | 2026-09 | full profile `[1]` |
| v0.5 | 2026-09 | Lite edge profile `[2]` |
| v0.6 | 2026-09-17 | Connect requirements `[3]`, architecture `[4]`, three interoperable implementations, live chain validated |
| v0.6.1 | 2026-09-24 | Linux nodes — `linux/ospnode` (Kotlin/JVM) and its stdlib twin `mcp/osp_node.py`: the cores served headless with an HTTP bridge, web console and CLI; platform guides in Annex D |

## Annex D (informative): platform guides — Linux, Web, Android

All surfaces speak the same wire (clause 5.1): sealed packets on `/osp/packet`,
link secrets as `x-api-token` / `Authorization: Bearer`, outcomes with the
same trace and convergence modes. Any two nodes below peer without bridging
code.

### D.1 Linux — compile and run `ospnode`

```bash
# build (JDK ≥ 17; the build reuses the android/ wrapper so osp-lite is
# compiled exactly once for both platforms)
cd android && ./gradlew :ospnode:fatJar
# → linux/ospnode/build/libs/ospnode-all.jar   (self-contained, ~2 MB)

# serve a node: id, port, link secret, knowledge corpus
java -jar ../linux/ospnode/build/libs/ospnode-all.jar \
     --id lan-node --port 8090 --token "$OSP_TOKEN" --corpus ~/knowledge/

# a real model behind the node (N3) — same env contract as mcp/providers.py
export OSP_PROVIDER_URL=http://localhost:11434/v1  OSP_PROVIDER_MODEL=gemma4:12b
java -jar ../linux/ospnode/build/libs/ospnode-all.jar --provider openai

# one-shot negotiation from the shell (exit 0 only on RESOLVED)
java -jar ../linux/ospnode/build/libs/ospnode-all.jar \
     --corpus ~/knowledge/ --query "why does hydraulic pump failure happen" --tier 0
```

Full option table, the systemd user-service unit and the test suite are in
`linux/ospnode/README.md`.

**Two interchangeable implementations.** `mcp/osp_node.py` is the stdlib-only
Python twin of this node — identical flags, routes, console and wire, built on
the Python reference core instead of `osp-lite`. Pick by runtime constraints
(a JDK ≥ 17 box vs a bare `python3`), and mix freely: a Kotlin origin
negotiates with the Python responder and vice-versa over sealed packets, which
is clause 5.2.1's cross-language parity exercised live:

```bash
python3 mcp/osp_node.py --id py-node --port 18201 --token "$T" --corpus ~/knowledge/
java -jar ../linux/ospnode/build/libs/ospnode-all.jar --query "…" --tier 0 \
     --peer py-node=http://localhost:18201 --peer-token py-node="$T"
```

### D.2 Web — browser console and HTTP clients

The Linux node serves a **zero-install console** at `http://<node>:8090/`:
paste the link token once, then query the swarm (mode, answer, groundedness
and the full PROPOSE → BID → ALIGN → RESOLVE trace rendered), teach chunks and
manage peers. Everything the console does is plain `fetch` against the
documented routes, so any web client is one `POST` away:

```js
const r = await fetch("http://<node>:8090/osp/query", {
  method: "POST",
  headers: { "Content-Type": "application/json", "x-api-token": TOKEN },
  body: JSON.stringify({ query: "why does hydraulic pump failure happen", tier: 0 }),
});
const { outcome } = await r.json();   // outcome.mode, outcome.answer, outcome.groundedness
```

For machine clients the same routes take curl (D.1, §4 of
`linux/ospnode/README.md`) and the Python CLI of Annex B. The JavaScript
implementation itself lives in the whatsapp-bot repository (`osp/core.mjs`) —
point a peer at its `/osp/packet` endpoint to negotiate with it from the
browser's node.

### D.3 Android — the ospbridge app

```bash
./gradlew :ospbridge:assembleDebug
adb install -r ospbridge/build/outputs/apk/debug/ospbridge-debug.apk
adb shell am start-foreground-service -n com.swarmknowledge.ospbridge/.OspService
```

The app runs the protocol as an N1 origin + N2 responder (generation through
the on-device LLMProvider engine; without it every query honestly abstains,
REQ-F-04). Peering with a Linux node, tablet side:

```bash
adb forward tcp:18090 tcp:8090
curl -H "x-api-token: $TABLET_TOKEN" localhost:18090/osp/peers \
     -d '{"peers": {"lan-node": {"url": "http://<linux-lan-ip>:8090", "token": "'"$OSP_TOKEN"'"}}}'
curl -H "x-api-token: $TABLET_TOKEN" localhost:18090/osp/query \
     -d '{"query": "why does hydraulic pump failure happen", "tier": 0}'
```

Reverse direction — the Linux node adds the tablet with `--peer
tablet=http://<tablet-lan-ip>:8090 --peer-token tablet=<tablet-secret>`. The
AIDL surface (`IOspService`) lets on-device host apps submit queries without
HTTP. Details and the requirement traceability table: `android/README.md`.
