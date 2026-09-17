# Privacy Policy — Tree4Five OSP Bridge

_Last updated: 2026-09-17_

Tree4Five OSP Bridge (package `com.tree4five.osp`, published by tree4five)
is a peer-to-peer knowledge bridge for on-device AI. This policy describes
what the app does — and above all what it does **not** do with your data.

## Short version

**The app collects nothing.** There is no account, no analytics, no
advertising SDK, no telemetry and no server operated by tree4five. Your
knowledge and your questions never pass through us — they stay on your
devices or travel directly between them.

## What the app stores, and where

| Data | Where it lives | Leaves the device? |
|---|---|---|
| Knowledge chunks you teach ("Teach a chunk") | App-private storage on your device | Only if YOU peer with another of YOUR devices |
| Questions you ask ("Ask the swarm") | Memory only, not persisted | Only to the peer devices you configured |
| Peer configuration (URLs of your other devices) | App-private storage | Never |
| Link secrets (peer authentication tokens) | App-private storage; shown truncated on screen | Only inside packets to your own peer devices |

## What the app sends, and to whom

When you run a verified query, the app sends sealed, signed protocol
packets (Omni-Swarm Protocol v0.6) **directly to the peer devices you
explicitly configured** — typically your own phone, tablet, NAS or bot on
your local network. There is no relay operated by tree4five, no cloud
endpoint, no third party. A device that is not in your peer list receives
nothing.

Answers are verified against the evidence the answering device actually
stores; a device without evidence abstains rather than inventing.

## Permissions

- **INTERNET** — to reach the peer devices you configured (local network
  or your own hosts).
- **FOREGROUND_SERVICE** — keeps the OSP node reachable while you use it.
- **POST_NOTIFICATIONS** — shows the "node running" notification.

The app reads no contacts, no location, no media, no phone state.

## Clear-text HTTP

Peering uses plain HTTP on your local network for the sealed-packet wire
format (packet integrity is enforced by packet signatures). A production
transport hardening (Ed25519 device keys + TLS, requirement REQ-S-01) is
on the roadmap before wide rollout; until then, peer only with devices on
networks you trust.

## Children

The app contains no content directed at children and no user-generated
content sharing; it is a tool for connecting your own AI devices.

## Contact

tree4five — via the Play Store listing contact e-mail.

## Changes

Any change to this posture will be reflected here before the relevant
app update ships.
