# OSP Android — library + bridge app

Kotlin implementation of the Omni-Swarm Protocol (see the repository root
`README.md` for the normative protocol description). Two Gradle modules,
zero third-party runtime dependencies (REQ-NF-01):

| Module | Kind | Content |
|---|---|---|
| `osp-lite/` | Android library | protocol core: packets, sealing, negotiation, tiers, firewall, discovery, Q8 codec, `HttpHub` |
| `ospbridge/` | app | foreground `OspService` + `MainActivity`, AIDL surface for host apps (harnessdroid), HTTP bridge for LAN peers |

## Surfaces

- **AIDL** `IOspService` (`submitQuery`, `registerCallback`, `advertiseCentroid`,
  `fetchKeyBundle`) — the osp_lite_v05.html §5 contract, for on-device host apps.
- **HTTP** (dev-grade, cleartext, port 8090, token-authenticated):
  `GET /osp/status`, `GET /osp/endpoint.json`, `POST /osp/query`,
  `POST /osp/teach`, `GET|POST /osp/peers`, `POST /osp/packet`.
- **OSP native** — sealed packets in/out; the service is both an N1 origin
  (remote peers via `HttpHub`, never generates for itself) and an N2 responder
  (generation only through a bound LLMProvider engine, `LlmD3Provider`).

The app talks to the **../llmprovider** app through `LlmBridge`; when
LLMProvider is not installed the responder cannot generate and every query
honestly abstains (REQ-F-04).

## Build and install

```bash
./gradlew :osp-lite:testDebugUnitTest      # protocol conformance (offline)
./gradlew :ospbridge:assembleDebug
adb install -r ospbridge/build/outputs/apk/debug/ospbridge-debug.apk
adb shell am start-foreground-service -n com.swarmknowledge.ospbridge/.OspService
```

Peer the tablet with a remote node (whatsapp-bot) and query it:

```bash
adb forward tcp:18090 tcp:8090
curl -H "x-api-token: $T" -H "Authorization: Bearer $T" \
     localhost:18090/osp/peers -d '{"peers": {"whatsapp-bot": "http://<bot>:<port>"}}'
curl -H "x-api-token: $T" -H "Authorization: Bearer $T" \
     localhost:18090/osp/query -d '{"text": "…", "tier": 0}'
```

`HttpHub` uses per-peer link secrets (double header `x-api-token` +
`Authorization`) and a 300 s read timeout — remote generation (ollama on a
CPU box) legitimately takes tens of seconds.

### Ed25519 provisioning (REQ-S-01/02)

The bridge seals with the labelled dev HMAC until an Ed25519 seed is
provisioned — the bot refuses nothing during the migration window (its
`HybridSigner` verifies both schemes), but the goal is every node on Ed25519:

```bash
# on the PC: generate a seed for the tablet (the seed stays secret)
node scripts/osp-keygen.mjs          # (whatsapp-bot repo) — or any 32-byte hex

# on the tablet: provision it, then restart the service
adb shell am start-foreground-service -n com.swarmknowledge.ospbridge/.OspService
adb shell "run-as com.swarmknowledge.ospbridge cat /dev/null"  # (seed set via app prefs / adb)
```

The seed lives in the `ospbridge` shared prefs under `osp_ed25519_seed`
(64 hex chars) — write it with the app running once (`adb shell run-as
com.swarmknowledge.ospbridge` on a debuggable build), then restart the
service so `onStartCommand` picks it up. Once set, the service seals JWS
compact EdDSA and serves the pinnable bundle (`alg`/`kid`/`signing`) on
`/osp/endpoint.json`; the bot pins it on first sight (TOFU) or accepts it via
`POST /osp/pins/repin`. A rotated key must be re-pinned explicitly — the pin
store rejects silent drift.

### Peer with the Linux node (`../linux/ospnode`)

The tablet and a headless Linux node run the **same** `osp-lite` core, so
peering is just an exchange of URLs and link secrets (guide:
`../linux/ospnode/README.md`, root README Annex D.3):

```bash
# tablet → Linux node (the Linux node serves /osp/packet on :8090)
adb forward tcp:18090 tcp:8090
curl -H "x-api-token: $T" localhost:18090/osp/peers \
     -d '{"peers": {"lan-node": {"url": "http://<linux-lan-ip>:8090", "token": "'"$NODE_TOKEN"'"}}}'

# Linux node → tablet (start ospnode with the tablet as a peer)
java -jar ../linux/ospnode/build/libs/ospnode-all.jar --id lan-node \
     --peer tablet=http://<tablet-lan-ip>:8090 --peer-token tablet=$T
```

## Android requirements and test traceability

| ID | Requirement | Verified by |
|---|---|---|
| REQ-A-01 | The library shall implement the same wire format as the Python and JavaScript cores (golden vectors, `pyDouble`, canonical JSON). | `InteropTest` (9 tests) |
| REQ-A-02 | Negotiation shall terminate in a convergence mode of clause 3.1.8, never silently. | `NodeProtocolTest` (15 tests) |
| REQ-A-03 | Discovery shall pin keys TOFU and reject replayed registrations. | `DiscoveryQ8Test` (8 tests) |
| REQ-A-04 | The JSON codec shall be self-contained and canonical. | `MiniJsonTest` (6 tests) |
| REQ-A-05 | The HTTP bridge shall read request bodies as raw **bytes** (Content-Length counts bytes; a char-decoding reader hangs on non-ASCII). | field-tested (accented POSTs) |
| REQ-A-06 | T2 traffic shall not reach remote peers by default (`allow_remote_t2=false`). | `NodeProtocolTest` |

38/38 tests green (2026-09-17); end-to-end validated against a remote
whatsapp-bot node (root `README.md`, clause 7.2).

**Dev posture.** `DevSigner` (HMAC-SHA256) and cleartext HTTP are development
postures (REQ-S-01); production shall use Ed25519 + TLS.
