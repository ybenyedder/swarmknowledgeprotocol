# ospnode — OSP v0.6 node for Linux

The Linux face of the Omni-Swarm Protocol: the **same pure-JVM `osp-lite`
library the Android app runs** (see `android/osp-lite`), packed behind a CLI,
a LAN HTTP bridge identical to the app's `HttpBridge`, and a browser console.
One artifact, `ospnode-all.jar`, no third-party runtime dependency beyond
kotlin-stdlib (the JVM counterpart of REQ-NF-01).

> **Python box?** `mcp/osp_node.py` is the stdlib-only twin of this node —
> same flags, same routes, same console, same wire (root README, Annex D.1).
> The two peer with each other and with the tablet interchangeably.

| | |
|---|---|
| Runs as | N2 full responder (default provider) or N3 provider-backed (OpenAI-compatible env) |
| Surfaces | HTTP bridge + web console + one-shot CLI + sealed packets on `/osp/packet` |
| Peers with | the ospbridge Android app, the whatsapp-bot node, `osp_cli.py`, any OSP wire-conformant node |
| Posture | **dev-grade**: DEV-SIGNER (HMAC) + cleartext HTTP — lab/LAN only (REQ-S-01) |

## 1. Build

Requirements: a JDK ≥ 17 (`java -version`) and nothing else — Gradle comes
through the wrapper. The build lives one directory up (`android/`), so all
platforms compile the single protocol core:

```bash
cd android
./gradlew :ospnode:fatJar          # → ../linux/ospnode/build/libs/ospnode-all.jar
```

Alternatives:

```bash
./gradlew :ospnode:installDist     # → linux/ospnode/build/install/ospnode/bin/ospnode
./gradlew :ospnode:distZip         # the same, zipped for deployment
```

## 2. Run a node

```bash
java -jar linux/ospnode/build/libs/ospnode-all.jar \
     --id lan-node --port 8090 \
     --token "$OSP_TOKEN" \
     --corpus ~/knowledge/          # .txt/.md; blank-line paragraphs become chunks
```

The node prints its identity, LAN URL, token prefix and knowledge count, then
serves until Ctrl-C. Without `--token` (or the `OSP_TOKEN` env) a link secret
is generated and printed once — pass it to every peer.

| Option | Meaning | Default |
|---|---|---|
| `--id NAME` | node id on the wire | `osp-XXXXXXXX` |
| `--port N` | HTTP bridge port (`0` = ephemeral) | `8090` |
| `--token SECRET` | link secret for the bridge | `OSP_TOKEN` env, else generated |
| `--peer id=url` | remote peer (repeatable) | — |
| `--peer-token id=s` | link secret of the preceding `--peer` | — |
| `--peers-file FILE` | `{"peers": {…}}` — the ospbridge `/osp/peers` format | — |
| `--corpus PATH` | teach file or directory at startup (repeatable) | — |
| `--provider NAME` | `echo` (grounded by construction) or `openai` (N3, env below) | `echo` |
| `--allow-remote-t2` | let T2 queries reach remote providers | denied (REQ-NF-02) |
| `--query TEXT` / `--tier 0|1|2` | one-shot mode, see §5 | — |

The `echo` provider answers by quoting the best chunk — grounded by
construction, useful to exercise the protocol with no model on the box.

### N3 — a real model behind the node

```bash
export OSP_PROVIDER_URL=http://localhost:11434/v1   # ollama's OpenAI-compatible API
export OSP_PROVIDER_MODEL=gemma4:12b
# export OSP_PROVIDER_API_KEY=…   # only for hosted endpoints
java -jar ospnode-all.jar --provider openai --corpus ~/knowledge/
```

The same env-var names as `mcp/providers.py`. The provider receives only the
query and the cited chunks inside the grounding envelope (REQ-F-02); its
output still passes the origin-side firewall.

## 3. Web console

Open `http://<node>:8090/` in any browser — no install, no build step:

- paste the link token once (kept in the browser's `localStorage`, sent as
  `x-api-token`, exactly like any OSP peer);
- **Ask the swarm** — runs the verified negotiation and renders mode, answer,
  groundedness and the full PROPOSE/BID/ALIGN/RESOLVE trace;
- **Teach** — add a knowledge chunk live;
- **Peers** — add or update remote nodes without restarting.

## 4. HTTP API

Same routes and same auth as the Android bridge — the link token under either
spelling, `x-api-token: …` or `Authorization: Bearer …` (`/` and
`/osp/status` are open):

| Route | Meaning |
|---|---|
| `GET /` | browser console |
| `GET /osp/status` | node status JSON (no secrets) |
| `GET /osp/endpoint.json` | provider contract for the PC `LLMProviderFileAdapter` |
| `GET|POST /osp/peers` | read / update the peer table (ospbridge format) |
| `POST /osp/query` | `{"query": "…", "tier": 0}` → verified outcome |
| `POST /osp/teach` | `{"text": "…"}` → one chunk |
| `POST /osp/packet` | sealed packet in → sealed reply out (the wire surface) |
| `GET /v1/models`, `POST /v1/chat/completions` | OpenAI-compatible RAW passthrough — **not** OSP-verified, the caller owns grounding |

```bash
T=<link-secret>; B=http://<node>:8090
curl -s $B/osp/status
curl -s -H "x-api-token: $T" $B/osp/peers -d '{"peers": {"tablet": {"url": "http://<tablet>:8090", "token": "<tablet-secret>"}}}'
curl -s -H "x-api-token: $T" $B/osp/query  -d '{"query": "why does hydraulic pump failure happen", "tier": 0}'
```

## 5. One-shot CLI (scripts)

`--query` runs a single negotiation — local knowledge, and every `--peer` —
prints the outcome JSON and exits (`0` only on `RESOLVED`):

```bash
$ java -jar ospnode-all.jar --corpus ~/knowledge/ --query "chromecast pairing" --tier 0
{"node":"osp-fea76a09","outcome":{"mode":"RESOLVED","answer":"…",
 "groundedness":0.75,"trace":[{"action":"PROPOSE","to":"osp-fea76a09"},…]}}
```

## 6. Peering

**With the Android tablet** (both directions, one link secret each side):

```bash
# Linux → tablet: add the tablet as a peer, then query through it
java -jar ospnode-all.jar --id lan-node \
     --peer tablet=http://<tablet-lan-ip>:8090 --peer-token tablet=<tablet-secret> \
     --corpus ~/knowledge/

# tablet → Linux: register the Linux node on the tablet (USB debugging on)
adb forward tcp:18090 tcp:8090
curl -H "x-api-token: $TABLET_TOKEN" localhost:18090/osp/peers \
     -d '{"peers": {"lan-node": {"url": "http://<linux-lan-ip>:8090", "token": "'"$OSP_TOKEN"'"}}}'
```

**With any other node** — the whatsapp-bot, `osp_cli.py`, or a second
ospnode — it is the same `/osp/peers` shape and the same `/osp/packet` wire.
Sealed packets make the implementations interchangeable: a PROPOSE sealed by
`osp_cli.py` bids back from this Kotlin node, byte-verified both ways.

## 7. Run as a service (systemd, user session)

```ini
# ~/.config/systemd/user/ospnode.service
[Unit]
Description=OSP v0.6 node (ospnode)
After=network-online.target

[Service]
Environment=OSP_TOKEN=<link-secret>
Environment=OSP_PROVIDER_URL=http://localhost:11434/v1
Environment=OSP_PROVIDER_MODEL=gemma4:12b
ExecStart=/usr/bin/java -jar %h/ospnode/ospnode-all.jar \
          --id lan-node --provider openai --corpus %h/knowledge/
Restart=on-failure

[Install]
WantedBy=default.target
```

```bash
systemctl --user enable --now ospnode
journalctl --user -u ospnode -f
```

(For a headless box, `sudo loginctl enable-linger $USER` keeps the user
service alive across logouts. The service binds `0.0.0.0` — firewall the port
to the LAN you trust.)

## 8. Tests

```bash
cd android && ./gradlew :ospnode:test     # 7 tests — HTTP surface, auth, sealed packets
```

| Suite | Scope | Status |
|---|---|---|
| `ospnode` JVM tests | routes, double-header auth, 401, teach → RESOLVED with groundedness, forged packet → silence, ospbridge peer format, console, `/v1` passthrough | 7/7 (2026-09-23) |

## 9. Dev posture — do not skip

Same honesty as the tablet: DEV-SIGNER is HMAC-SHA256 with the public dev
secret and the bridge speaks cleartext HTTP (REQ-S-01). The link token is
transport auth, not a signature — production shall use Ed25519 + TLS. Run it
on a LAN you own; do not forward the port to the internet.
