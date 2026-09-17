# Google Play Store Listing Information

## App Name
Tree4Five OSP Bridge (by tree4five)

## Short Description
Join the swarm: share verified knowledge between your devices, no cloud.

## Full Description
Tree4Five OSP Bridge turns your phone or tablet into a node of the
Omni-Swarm Protocol — a peer-to-peer knowledge network for on-device AI.
Your devices answer each other's questions using ONLY the knowledge they
actually hold, and every answer is verified before it is shown.

**Key Features:**
* **Verified answers only:** each reply is checked against the evidence the
  answering device really stores; no evidence → the node honestly abstains.
* **No cloud:** negotiations run directly between your devices on your LAN.
* **Works with LLM Provider:** binds the Tree4Five local AI engine
  (com.tree4five.gguf) for on-device generation.
* **Peer with anything:** sealed-packet wire format shared with the Python
  MCP server and the whatsapp-bot JavaScript node.
* **Privacy tiers:** sensitive (T2) traffic never leaves to remote providers.
* **Tree4Five Material design:** same dark look and feel as the LLM Provider app.

Ask your swarm: "résume le document reçu" — it answers with grounded,
verifiable content or says it does not know.

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
