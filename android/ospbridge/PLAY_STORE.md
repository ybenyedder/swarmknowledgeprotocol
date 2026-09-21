# Google Play Store Listing Information

## App Name
Tree4Five OSP Bridge (by tree4five)

## Short Description
Join the swarm: share verified knowledge between your devices, no cloud. (72/80 chars)

## Full Description
Tree4Five OSP Bridge turns your phone or tablet into a node of the
Omni-Swarm Protocol — a peer-to-peer knowledge network for on-device AI.
Your devices answer each other's questions using ONLY the knowledge they
actually hold, and every answer is verified before it is shown.

HOW IT WORKS
• Start a node on each device — no account, no cloud, no server.
• Peer them over your own network with a URL and a link token.
• Ask a question: nodes negotiate, the best-evidenced node answers, and
  the reply is checked against its cited evidence before it reaches you.
• No evidence? The node says so — it abstains instead of guessing.

KEY FEATURES
• Verified answers only — every reply is checked against the evidence the
  answering device really stores; no evidence → honest abstention.
• No cloud — negotiations run directly between your devices on your
  network; nothing is collected, nothing is shared.
• Works with LLM Provider — binds the Tree4Five local AI engine
  (com.tree4five.gguf) for on-device generation.
• Peer with anything — sealed-packet wire format shared with the Python
  MCP server and the whatsapp-bot JavaScript node.
• Privacy tiers — sensitive (T2) traffic never goes to remote providers.
• Honest failures — timeouts, out-of-gas, loops and ungrounded answers
  end in explicit, inspectable outcomes, never a silent wrong answer.
• Support menu — one-tap diagnostics in the toolbar overflow: LOG ALL
  exports a complete node journal (link secrets truncated), VERSION shows
  app + protocol versions, RESET ALL wipes identity, peers and taught
  knowledge, and HELP explains the node in the selected app language.
• Tree4Five Material design — same dark look and feel as the LLM Provider
  app.

Ask your swarm: "summarize the document I received" — it answers with
grounded, verifiable content, or honestly says it does not know.

WHO IS IT FOR
• Tinkerers running small local AI models on phones, tablets and
  single-board computers.
• Privacy-minded users who want the knowledge of their documents to stay
  on their own devices.
• Developers of the Omni-Swarm Protocol — spec, reference code and
  conformance tests at the project repository.

HONEST POSTURE
Early release (v0.6.4), developer-oriented: peering is manual (URL +
token), packets are signed and TTL/gas-bounded, and a production
Ed25519 + TLS signer is the committed next step.

## Tags
AI, Knowledge Sharing, P2P, Privacy, Offline AI, Mesh, Tree4Five

## Category
Productivity / Tools

## Content Rating
Everyone (no user-generated content shared online)

---

## Release checklist

1. **Signing** — `android/release.keystore` (NOT committed; copied from the
   LLMProvider tree). Passwords come from gradle properties
   `MYAPP_RELEASE_STORE_PASSWORD` / `MYAPP_RELEASE_KEY_PASSWORD`
   (alias default `release`). Build:
   ```bash
   cd android
   ./gradlew :ospbridge:bundleRelease   # AAB for the Play Console
   ./gradlew :ospbridge:assembleRelease # signed APK for direct install
   # → ospbridge/build/outputs/bundle/release/ospbridge-release.aab
   ```
2. **applicationId** `com.tree4five.osp` — same brand family as
   `com.tree4five.gguf`. First release: versionCode 1, versionName 0.6.0.
3. **Cleartext HTTP** — the app uses `usesCleartextTraffic=true` (LAN peering,
   packet-signature integrity). The Play data-safety form should state: no
   data collected, no data shared; traffic stays on the local network.
4. **Dev crypto posture** — dev builds use the HMAC dev signer; the Ed25519 +
   TLS production signer (REQ-S-01) is the committed next step before a wide
   rollout.
5. **Content rating** — Everyone; no ads; no in-app purchases.
6. **Target SDK** 36, min SDK 26 (matches harnessDroid / LLMProvider).

---

## Console form answers (copy-paste)

### Data safety (all "No" unless noted)

| Console question | Answer |
|---|---|
| Does your app collect or share any of the required user data types? | **No** |
| Is all of the user data collected by your app encrypted in transit? | n/a (nothing collected) |
| Do you provide a way for users to request that their data is deleted? | n/a (nothing collected) |
| Data collected / shared | **none** — no analytics, no ads, no telemetry, no device IDs |

The app's whole point is that traffic goes device-to-device on the user's
own network; see `store/PRIVACY_POLICY.md` (host it at a public URL and
paste the link in App content → Privacy policy).

### Content rating questionnaire

- No objectionable content, no user-generated content shared between
  users, no purchases, no ads, no location sharing → expect **Everyone**.

### App content

- **Ads**: no. **In-app purchases**: no.
- **Permissions**: INTERNET, FOREGROUND_SERVICE, POST_NOTIFICATIONS —
  declared usage: peering with the user's own devices.
- **Privacy policy URL**: required (host PRIVACY_POLICY.md anywhere
  public, e.g. GitHub Pages / a tree4five site page).

### Release notes (v0.6.4 — versionCode 5)

```
Stability and security fixes — no protocol change.
• Fixed a crash on the asking device when a peer reports an exact
  evidence match (integral JSON number).
• Fixed concurrent-query handling in the node engine.
• Restored link-token authentication on the packet endpoint; peers may
  also send the token as x-api-token.
```

### Release notes (v0.6.0)

```
First release. Join the swarm: turn this device into an Omni-Swarm
Protocol node.
• Verified answers only — every reply is checked against the evidence
  your devices actually store; no evidence → honest abstention.
• Peer directly with your other devices on your LAN (URL + link token,
  no cloud, no account).
• Works with LLM Provider (com.tree4five.gguf) for on-device generation.
• Teach knowledge chunks to the local node.
```

### Upload assets (in `android/store/`)

| Asset | File | Spec |
|---|---|---|
| App icon | `play_icon_512.png` | 512×512 PNG |
| Feature graphic | `feature_graphic_1024x500.png` | 1024×500 PNG |
| Phone screenshot 1 | `screenshot_node_status.png` | 1200×2000 PNG (node started, examples) |
| Phone screenshot 2 | `screenshot_query_resolved.png` | 1200×2000 PNG (verified query → RESOLVED) |
| Phone screenshot 3 | `screenshot_connect_peer.png` | 1200×2000 PNG (Connect a peer card) |

Screenshots are the tablet's real session (bot peer on the LAN, magazine
query → RESOLVED) — no personal documents, no secrets on screen (link
tokens are truncated to 6 chars by the app). Regenerate graphics with
`python3 android/store/make_assets.py`; regenerate the French user guide
(6-page PDF, `user_guide_fr.pdf`) with `python3 android/store/make_guide.py`.
