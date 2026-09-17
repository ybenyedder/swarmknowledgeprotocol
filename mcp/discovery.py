"""OSP discovery service (REQ-F-05) — lease-based node directory.

Bootstrap order per v0.6: configured seeds → mDNS (future, same interface) →
directory (this module, in-memory MVP). The interface is transport-agnostic so
mDNS and directory-backed implementations can slot in behind it.

Security (REQ-S-02): TOFU key pinning — the first key bundle registered for a
node_id is pinned; a changed bundle is rejected unless explicitly re-pinned.
Announcements carry a jti and are subject to replay rejection.
"""
from __future__ import annotations

import time
from dataclasses import dataclass, field

from osp_core import similarity

DEFAULT_LEASE_S = 900.0


@dataclass
class NodeRecord:
    node_id: str
    klass: str                          # N1 thin | N2 full | N3 provider-backed
    key_bundle: dict                    # {"signing": ..., "prekey": ...}
    endpoints: list[str] = field(default_factory=list)
    transports: list[str] = field(default_factory=lambda: ["memory"])
    codebook: list = field(default_factory=list)   # [(centroid_bytes, topic)]
    lease_until: float = 0.0
    jti: str = ""


@dataclass
class DirectoryStats:
    registers: int = 0
    renewals: int = 0
    pin_rejects: int = 0
    replays: int = 0
    expiries: int = 0


class Directory:
    """In-memory node directory. `now` is injectable for lease-expiry tests."""

    def __init__(self, now=time.time):
        self.records: dict[str, NodeRecord] = {}
        self.pins: dict[str, str] = {}          # node_id → pinned signing key
        self.seen_jti: set[str] = set()
        self._now = now
        self.stats = DirectoryStats()

    # -- registration ------------------------------------------------------------

    def register(self, node_id: str, klass: str, key_bundle: dict,
                 codebook: list | None = None, endpoints: list[str] | None = None,
                 transports: list[str] | None = None, jti: str = "",
                 lease_s: float = DEFAULT_LEASE_S) -> bool:
        now = self._now()
        if jti:
            if jti in self.seen_jti:
                self.stats.replays += 1
                return False                    # replay — rejected (REQ-S-02)
            self.seen_jti.add(jti)

        pinned = self.pins.get(node_id)
        if pinned is not None and pinned != key_bundle.get("signing"):
            self.stats.pin_rejects += 1
            return False                        # TOFU violation (REQ-S-02)

        rec = self.records.get(node_id)
        if rec is None or rec.lease_until < now:
            self.stats.registers += 1
            rec = NodeRecord(node_id=node_id, klass=klass, key_bundle=key_bundle)
            self.records[node_id] = rec
            self.pins[node_id] = key_bundle.get("signing")
        else:
            self.stats.renewals += 1
        rec.klass = klass
        rec.endpoints = endpoints or rec.endpoints
        rec.transports = transports or rec.transports
        rec.codebook = codebook if codebook is not None else rec.codebook
        rec.lease_until = now + lease_s
        rec.jti = jti
        return True

    def repin(self, node_id: str, klass: str, key_bundle: dict, **kwargs) -> bool:
        """Explicit re-pinning after key rotation (REQ-S-02 escape hatch)."""
        self.pins[node_id] = key_bundle.get("signing")
        self.records.pop(node_id, None)
        return self.register(node_id, klass, key_bundle, **kwargs)

    # -- queries -------------------------------------------------------------------

    def expire(self) -> int:
        """Drop expired leases; returns count removed."""
        now = self._now()
        dead = [nid for nid, r in self.records.items() if r.lease_until < now]
        for nid in dead:
            del self.records[nid]
        self.stats.expiries += len(dead)
        return len(dead)

    def lookup(self, query_vec: bytes, top_k: int = 3,
               klass: str | None = None) -> list[NodeRecord]:
        """Live records ranked by codebook similarity (D0 — no model)."""
        self.expire()
        recs = list(self.records.values())
        if klass is not None:
            recs = [r for r in recs if r.klass == klass]
        def best(r: NodeRecord) -> float:
            if not r.codebook:
                return 0.0
            return max(similarity(query_vec, c) for c, _topic in r.codebook)
        return sorted(recs, key=best, reverse=True)[:top_k]

    def get(self, node_id: str) -> NodeRecord | None:
        self.expire()
        return self.records.get(node_id)

    def alive(self) -> list[str]:
        self.expire()
        return sorted(self.records)
