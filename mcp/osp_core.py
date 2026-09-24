"""OSP v0.6 protocol core — stdlib only.

Implements the convergence and firewall rules defined in omni_swarm_protocol.html (v0.4)
and osp_lite_v05.html (v0.5), extended per requirements_v06.html:

  - signed stateless packets (DEV-SIGNER: HMAC-SHA256 — production MUST use Ed25519, REQ-S-01)
  - gas monotonicity (C1), path-vector loop freedom (C2), mapping contraction (C3)
  - quorum + provenance diversity (C4), stakes tiers T0/T1/T2
  - 3-layer hallucination firewall (closed-book provenance, entailment verify, reputation)
  - node classes N1 thin / N2 full / N3 provider-backed (REQ-F-03)
  - D3 provider and D2 verifier as pluggable interfaces (REQ-F-01, REQ-F-08)
  - generation budget with abstention (REQ-F-04)

No third-party dependencies (REQ-NF-01). No network calls (REQ-NF-03).
"""
from __future__ import annotations

import hashlib
import hmac
import json
import threading
import time
import uuid
from dataclasses import dataclass, field
from enum import Enum

PACKET_VERSION = "0.6"

# ---------------------------------------------------------------------------
# Dev embedder — deterministic hashed bag-of-words binary vectors.
# Production uses a real 384d embedder (MiniLM class); the interface is identical
# (bytes of dims/8) so tests and prod share all vector math.
# ---------------------------------------------------------------------------

# Conservative dev stopwords — interrogatives and function words that dilute
# set-similarity without carrying topic. Production embedders handle this
# internally; the dev hashed-BOW embedder needs it explicit.
_STOPWORDS = frozenset(
    "a an the is are was were be been am do does did why how what when where "
    "which who of and or to in on at by for with from as it its this that "
    "if then so not no yes will would can could should may might".split()
)


def tokenize(text: str) -> list[str]:
    return [t for t in "".join(c if c.isalnum() else " " for c in text.lower()).split()
            if t and t not in _STOPWORDS]


def embed(text: str, dims: int = 256) -> bytes:
    vec = bytearray(dims // 8)
    for tok in tokenize(text):
        h = int(hashlib.sha1(tok.encode()).hexdigest(), 16)
        vec[(h >> 3) % len(vec)] |= 1 << (h & 7)
    return bytes(vec)


def similarity(a: bytes, b: bytes) -> float:
    """Jaccard over set bits (D0 — no model).

    Dev embedder note: hashed bag-of-words vectors are sparse, so plain Hamming
    saturates near 1.0 and does not discriminate. Jaccard on set bits is the
    equivalent set-level similarity. Production keeps the same interface but
    scores int8/binary codes with asymmetric Hamming (v0.5 [A8])."""
    if not a or not b:
        return 0.0
    ia, ib = int.from_bytes(a, "big"), int.from_bytes(b, "big")
    inter = bin(ia & ib).count("1")
    union = bin(ia | ib).count("1")
    return inter / union if union else 0.0


def query_cover(qv: bytes, cv: bytes) -> float:
    """Asymmetric query-side coverage: |q ∩ c| / |q| (same shape as the
    LexicalVerifier's answer-vs-evidence ratio).

    The symmetric Jaccard collapses when chunk ≫ query in token count — a
    full match on an 8-token question against a 120-token chunk scores ~0.03,
    under any sane bidMin. Competence is measured as how much of the QUESTION
    the chunk can ground, not how much of the chunk the question repeats.
    Production keeps the interface and swaps in the cosine (v0.5 [A8])."""
    qb = int.from_bytes(qv, "big")
    qbits = bin(qb).count("1")
    if not qbits:
        return 0.0
    cb = int.from_bytes(cv, "big")
    return bin(qb & cb).count("1") / qbits


def chunk_hash(text: str) -> str:
    return "sha256:" + hashlib.sha256(text.encode()).hexdigest()[:16]


# ---------------------------------------------------------------------------
# Signers (REQ-S-01: DEV-SIGNER only in MVP — production Ed25519, RFC 8032)
# ---------------------------------------------------------------------------

class DevSigner:
    """DEV-SIGNER: HMAC-SHA256 over canonical JSON. NOT for deployment."""
    label = "DEV-SIGNER"

    def __init__(self, secret: bytes = b"osp-dev-secret"):
        self.secret = secret

    def sign(self, obj: dict) -> str:
        blob = json.dumps(obj, sort_keys=True, separators=(",", ":")).encode()
        return hmac.new(self.secret, blob, hashlib.sha256).hexdigest()

    def verify(self, obj: dict, sig: str) -> bool:
        return hmac.compare_digest(self.sign(obj), sig)


# ---------------------------------------------------------------------------
# Packets
# ---------------------------------------------------------------------------

class Action(str, Enum):
    PROPOSE = "PROPOSE"
    BID = "BID"
    ALIGN = "ALIGN"
    RESOLVE = "RESOLVE"
    GET_CHUNK = "GET_CHUNK"
    RFO = "RFO"      # explicit failure backtracked to origin (never silent)
    ACK = "ACK"


class Mode(str, Enum):
    RESOLVED = "RESOLVED"
    REJECTED = "REJECTED"        # firewall rejected the winning payload
    MISMATCH = "MISMATCH"
    GAS_EXHAUSTED = "GAS_EXHAUSTED"
    LOOP_DETECTED = "LOOP_DETECTED"
    NO_QUORUM = "NO_QUORUM"


@dataclass
class Packet:
    action: Action
    origin_id: str
    query_id: str
    sender: str
    gas: int
    trail: list = field(default_factory=list)      # [{node, action}] — path vector
    payload: dict = field(default_factory=dict)
    packet_id: str = field(default_factory=lambda: uuid.uuid4().hex[:12])
    jti: str = field(default_factory=lambda: uuid.uuid4().hex[:16])
    ts: float = field(default_factory=time.time)
    exp_s: int = 60
    version: str = PACKET_VERSION
    sig: str = ""

    def signed_object(self) -> dict:
        return {
            "v": self.version, "packet_id": self.packet_id, "jti": self.jti,
            "ts": self.ts, "exp_s": self.exp_s, "action": self.action.value,
            "origin_id": self.origin_id, "query_id": self.query_id,
            "sender": self.sender, "gas": self.gas, "trail": self.trail,
            "payload": self.payload,
        }

    def seal(self, signer: DevSigner) -> "Packet":
        self.sig = signer.sign(self.signed_object())
        return self

    def expired(self, now: float | None = None) -> bool:
        return (now or time.time()) > self.ts + self.exp_s


# ---------------------------------------------------------------------------
# Pluggable interfaces (REQ-F-01, REQ-F-02, REQ-F-08)
# ---------------------------------------------------------------------------

class D3Provider:
    """Generation rung. Receives ONLY the query + cited chunks (REQ-F-02)."""
    name = "abstract"

    def generate(self, query: str, chunks: list[dict]) -> dict:
        raise NotImplementedError

    @property
    def remote(self) -> bool:
        return False


class D2Verifier:
    """Entailment rung: groundedness of an answer against cited evidence."""
    name = "abstract"

    def groundedness(self, answer: str, chunks: list[dict]) -> float:
        raise NotImplementedError


class LexicalVerifier(D2Verifier):
    """Dev-grade verifier: token overlap. Clearly NOT an NLI model (REQ-F-08)."""
    name = "lexical-dev"

    def groundedness(self, answer: str, chunks: list[dict]) -> float:
        toks = set(tokenize(answer))
        if not toks:
            return 0.0
        evidence = set()
        for c in chunks:
            evidence.update(tokenize(c["text"]))
        return len(toks & evidence) / len(toks)


# ---------------------------------------------------------------------------
# RAG store (local, never shipped whole — only cited chunks travel, REQ-NF-02)
# ---------------------------------------------------------------------------

class RagStore:
    def __init__(self, chunks: list[str]):
        self.chunks = [{"hash": chunk_hash(t), "text": t, "vec": embed(t)} for t in chunks]

    def retrieve(self, qv: bytes, top_k: int = 3) -> list[dict]:
        scored = sorted(
            ((query_cover(qv, c["vec"]), c) for c in self.chunks),
            key=lambda p: p[0], reverse=True,
        )[:top_k]
        return [dict(c, score=round(s, 3)) for s, c in scored if s > 0]


# ---------------------------------------------------------------------------
# Budget (REQ-F-04)
# ---------------------------------------------------------------------------

class Budget:
    def __init__(self, generations_per_day: int = 50):
        self.limit = generations_per_day
        self.spent = 0

    @property
    def left(self) -> int:
        return max(0, self.limit - self.spent)

    def charge(self, n: int = 1) -> bool:
        if self.left < n:
            return False
        self.spent += n
        return True


# ---------------------------------------------------------------------------
# Node
# ---------------------------------------------------------------------------

@dataclass
class NodeConfig:
    gas: int = 3            # G (v0.5 Lite)
    max_clarify: int = 1    # C
    retries: int = 2        # R
    bid_window_s: float = 0.0   # sync hub → window collapses; hook for real transports
    groundedness_min: float = 0.35   # dev verifier threshold (0.8 with real NLI)
    bid_min: float = 0.15
    allow_remote_t2: bool = False    # REQ-NF-02 default deny
    align_tolerance: float = 0.05    # allowed drift on the first align (C = 1)
    contraction_gamma: float = 0.95  # per-round shrink when C > 1 (v0.4 rule C3)


class Node:
    """An OSP node. N1 = d3 is None; N2 = local provider; N3 = remote provider."""

    def __init__(self, node_id: str, rag: RagStore, d3: D3Provider | None,
                 signer: DevSigner | None = None, klass: str | None = None,
                 config: NodeConfig | None = None):
        self.id = node_id
        self.rag = rag
        self.d3 = d3
        self.signer = signer or DevSigner()
        self.cfg = config or NodeConfig()
        self.klass = klass or (self._auto_class())
        self.budget = Budget()
        self.reputation: dict[str, float] = {}      # peer → EWMA reliability, start neutral
        self.jti_cache: set[str] = set()            # replay protection (C2 / REQ-S-02)
        self.mapping_cache: dict[tuple, float] = {} # (query_id, peer, source, target) → distance
        self.hooks: dict = {}                        # test seams (e.g. "pre_align")
        self.hub: "InMemoryHub | None" = None

    def _auto_class(self) -> str:
        if self.d3 is None:
            return "N1"
        return "N3" if self.d3.remote else "N2"

    # -- validation pipeline (every inbound packet, v0.4 §Convergence) ---------

    def on_packet(self, pkt: Packet) -> Packet | None:
        if not self.signer.verify(pkt.signed_object(), pkt.sig):
            return None                                   # forged — drop
        if pkt.expired():
            return self._rfo(pkt, Mode.GAS_EXHAUSTED, "expired")  # old age ≈ death
        if pkt.jti in self.jti_cache:
            return None                                   # replay — silent drop (REQ-S-02)
        self.jti_cache.add(pkt.jti)
        if any(hop.get("node") == self.id for hop in pkt.trail):
            return self._rfo(pkt, Mode.LOOP_DETECTED, f"{self.id} seen in trail")
        if pkt.gas <= 0 and pkt.action != Action.RFO:
            return self._rfo(pkt, Mode.GAS_EXHAUSTED, "gas = 0 on arrival")
        return self._dispatch(pkt)

    def _rfo(self, pkt: Packet, mode: Mode, reason: str) -> Packet:
        return Packet(
            action=Action.RFO, origin_id=pkt.origin_id, query_id=pkt.query_id,
            sender=self.id, gas=pkt.gas,
            trail=pkt.trail + [{"node": self.id, "action": pkt.action.value}],
            payload={"mode": mode.value, "reason": reason},
        ).seal(self.signer)

    def _dispatch(self, pkt: Packet) -> Packet | None:
        handler = {
            Action.PROPOSE: self._on_propose,
            Action.ALIGN: self._on_align,
            Action.RESOLVE: self._on_resolve,
            Action.GET_CHUNK: self._on_get_chunk,
        }.get(pkt.action)
        return handler(pkt) if handler else None

    # -- responder-side handlers ------------------------------------------------

    def _retrieval_score(self, qv: bytes) -> tuple[float, list[dict]]:
        chunks = self.rag.retrieve(qv)
        return (chunks[0]["score"] if chunks else 0.0), chunks

    def _on_propose(self, pkt: Packet) -> Packet | None:
        """PRE-BID: generation-free gate (D0 + lexical cross-chunk agreement)."""
        qv = bytes(pkt.payload["query_vec"])
        score, chunks = self._retrieval_score(qv)
        rep = self.reputation.get(pkt.origin_id, 0.5)
        # Generation-free gate: no chunks, or similarity below floor → no bid at
        # all. (Full semantic entropy needs sampling; v0.5 §02 substitution.)
        if not chunks or score < self.cfg.bid_min:
            return self._rfo(pkt, Mode.NO_QUORUM, "no competent evidence — abstained")
        bid = round(min(1.0, 0.5 * score + 0.3 * rep + 0.2 * chunks[0]["score"]), 3)
        return Packet(
            action=Action.BID, origin_id=pkt.origin_id, query_id=pkt.query_id,
            sender=self.id, gas=pkt.gas - 1,
            trail=pkt.trail + [{"node": self.id, "action": "BID"}],
            payload={
                "bid": bid,
                "retrieval_similarity": score,
                "reputation": rep,
                "node_class": self.klass,
                "can_generate": self.d3 is not None and self.budget.left > 0,
                "provenance": [{"chunk_hash": c["hash"], "score": c["score"]} for c in chunks],
            },
        ).seal(self.signer)

    def _on_align(self, pkt: Packet) -> Packet | None:
        """Mapping on cache-miss uses D3; contraction enforced by the ORIGIN (C3)."""
        src, tgt = pkt.payload["source"], pkt.payload["target"]
        qv = bytes(pkt.payload["query_vec"])
        key = (pkt.query_id, pkt.origin_id, src, tgt)
        dist = self.mapping_cache.get(key)
        if dist is None:
            dist = round(1.0 - self._retrieval_score(qv)[0], 3)
            if self.d3 is not None and self.budget.charge():
                # fire-and-forget: the confirm text is discarded — the distance
                # is local. Blocking on generation here stalls the whole
                # negotiation for the length of a D3 completion.
                threading.Thread(
                    target=self._confirm_mapping, args=(src, tgt), daemon=True,
                ).start()
            self.mapping_cache[key] = dist
        return Packet(
            action=Action.ACK, origin_id=pkt.origin_id, query_id=pkt.query_id,
            sender=self.id, gas=pkt.gas - 1,
            trail=pkt.trail + [{"node": self.id, "action": "ALIGN"}],
            payload={"mapping_distance": dist, "source": src, "target": tgt},
        ).seal(self.signer)

    def _confirm_mapping(self, src: str, tgt: str) -> None:
        """Best-effort D3 confirm — result discarded, never surfaces a failure."""
        try:
            self.d3.generate(f"Confirm mapping {src} -> {tgt}", self.rag.chunks[:1])
        except Exception:
            pass

    def _on_resolve(self, pkt: Packet) -> Packet | None:
        """The single generation (v0.5: generation-once). Abstains if unable."""
        if self.d3 is None:
            return self._rfo(pkt, Mode.NO_QUORUM, "N1 node cannot generate")
        qv = bytes(pkt.payload["query_vec"])
        chunks = self.rag.retrieve(qv)
        if not self.budget.charge():
            return self._rfo(pkt, Mode.NO_QUORUM, "generation budget exhausted")
        try:
            out = self.d3.generate(pkt.payload["query_text"], chunks)
        except Exception as e:
            # explicit failure backtracked to the origin — never silent (v0.4);
            # mirrors the Kotlin port (android/osp-lite Node.kt onResolve)
            return self._rfo(pkt, Mode.NO_QUORUM, f"provider failure: {e}")
        head = out["answer"][:256]
        return Packet(
            action=Action.RESOLVE, origin_id=pkt.origin_id, query_id=pkt.query_id,
            sender=self.id, gas=pkt.gas - 1,
            trail=pkt.trail + [{"node": self.id, "action": "RESOLVE"}],
            payload={
                "answer": out["answer"],
                "digest": {"chunk_hashes": [c["hash"] for c in chunks], "head": head},
                "provenance": [{"chunk_hash": c["hash"], "text": c["text"]} for c in chunks],
                "provider": out.get("provider", "unknown"),
                "cost": out.get("cost", {"generations": 1}),
            },
        ).seal(self.signer)

    def _on_get_chunk(self, pkt: Packet) -> Packet | None:
        h = pkt.payload["chunk_hash"]
        for c in self.rag.chunks:
            if c["hash"] == h:
                return Packet(
                    action=Action.ACK, origin_id=pkt.origin_id, query_id=pkt.query_id,
                    sender=self.id, gas=pkt.gas - 1,
                    trail=pkt.trail + [{"node": self.id, "action": "GET_CHUNK"}],
                    payload={"chunk": {"hash": c["hash"], "text": c["text"]}},
                ).seal(self.signer)
        return self._rfo(pkt, Mode.MISMATCH, "unknown chunk")

    # -- origin-side orchestration ----------------------------------------------

    def query(self, text: str, tier: int = 1, directory=None) -> dict:
        """Run a full OSP negotiation. tier ∈ {0,1,2} → k = 1/2/3, q = 1/2/2."""
        assert tier in (0, 1, 2)
        k, q = [(1, 1), (2, 2), (3, 2)][tier]
        qv = embed(text)
        trace: list[dict] = []

        candidates = self._route(qv, k, directory)
        if not candidates:
            return self._outcome(Mode.NO_QUORUM, trace, "no candidate nodes")

        # 01 PROPOSE → 02 PRE-BID/BID
        bids = []
        alias_of: dict[str, str] = {}   # sealed sender id → table alias it answered through
        for node_id in candidates:
            pkt = Packet(
                action=Action.PROPOSE, origin_id=self.id, query_id=uuid.uuid4().hex[:12],
                sender=self.id, gas=self.cfg.gas, payload={"query_vec": list(qv), "query_text": text},
            ).seal(self.signer)
            trace.append({"to": node_id, "action": "PROPOSE"})
            reply = self.hub.send(self.id, node_id, pkt)
            if reply and reply.action == Action.BID:
                bids.append(reply)
                alias_of[reply.sender] = node_id
                trace.append({"from": node_id, "action": "BID", "bid": reply.payload["bid"]})
            elif reply and reply.action == Action.RFO:
                trace.append({"from": node_id, "rfo": reply.payload["reason"]})

        # C4 — provenance diversity: same-hash votes collapse to one
        bids = self._diverse_bids(bids)
        capable = [b for b in bids if b.payload["can_generate"]]
        if len(capable) < q:
            return self._outcome(Mode.NO_QUORUM, trace,
                                 f"{len(capable)} verified capable bids < q={q}")

        # 03 ALIGN — one clarify round max (C = 1, v0.5)
        winner = max(capable, key=lambda b: b.payload["bid"])
        prov = winner.payload["provenance"][0]
        dist0 = 1.0 - winner.payload["retrieval_similarity"]
        # the sealed BID carries the responder's internal id, which need not
        # equal the table alias it was reached through (Android peers peer under
        # an alias while their node id is osp-…): route follow-ups by alias
        to = alias_of.get(winner.sender, winner.sender)
        if "pre_align" in self.hooks:            # test seam: mutate state pre-align
            self.hooks["pre_align"](winner)
        align = Packet(
            action=Action.ALIGN, origin_id=self.id, query_id=winner.query_id,
            sender=self.id, gas=self.cfg.gas,
            payload={"query_vec": list(qv), "source": text, "target": prov["chunk_hash"]},
        ).seal(self.signer)
        reply = self.hub.send(self.id, to, align)
        trace.append({"from": winner.sender, "action": "ALIGN"})
        if reply is None or reply.action == Action.RFO:
            if reply is not None:      # the RFO's reason travels in the trace
                trace.append({"from": winner.sender, "rfo": reply.payload["reason"]})
            return self._outcome(Mode.MISMATCH, trace, "alignment refused")
        dist1 = reply.payload["mapping_distance"]
        # C3 with C = 1: the first align is a lock-in, not a clarify round — the
        # responder's evidence may not drift beyond tolerance between BID and
        # ALIGN. The γ-contraction rule applies to clarify rounds 2..C (C > 1).
        if dist1 > dist0 + self.cfg.align_tolerance:
            return self._outcome(Mode.MISMATCH, trace,
                                 f"distance {dist0:.2f} → {dist1:.2f}: evidence drifted")

        # 04 RESOLVE — the single generation, tier privacy gate (REQ-NF-02)
        if winner.payload["node_class"] == "N3" and tier == 2 and not self.cfg.allow_remote_t2:
            return self._outcome(Mode.REJECTED, trace, "T2 → remote provider denied by policy")
        resolve = Packet(
            action=Action.RESOLVE, origin_id=self.id, query_id=winner.query_id,
            sender=self.id, gas=self.cfg.gas,
            payload={"query_vec": list(qv), "query_text": text},
        ).seal(self.signer)
        reply = self.hub.send(self.id, to, resolve)
        trace.append({"from": winner.sender, "action": "RESOLVE"})
        if reply is None or reply.action == Action.RFO:
            if reply is not None:      # the RFO's reason travels in the trace
                trace.append({"from": winner.sender, "rfo": reply.payload["reason"]})
            return self._outcome(Mode.NO_QUORUM, trace, "winner could not generate")

        # 05 VERIFY — firewall L2 (origin-side, hash-addressable) + reputation
        answer = reply.payload["answer"]
        prov_chunks = reply.payload["provenance"]
        g = self._verifier.groundedness(answer, prov_chunks)  # set in attach()
        rep_before = self.reputation.get(winner.sender, 0.5)
        if g >= self.cfg.groundedness_min:
            self.reputation[winner.sender] = min(1.0, rep_before + 0.05)
            out = self._outcome(Mode.RESOLVED, trace, answer=answer)
        else:
            self.reputation[winner.sender] = max(0.0, rep_before - 0.20)
            out = self._outcome(Mode.REJECTED, trace,
                                f"groundedness {g:.2f} < {self.cfg.groundedness_min}")
        out["groundedness"] = round(g, 3)
        out["cost"] = reply.payload.get("cost")
        return out

    # -- helpers -----------------------------------------------------------------

    def _route(self, qv: bytes, k: int, directory) -> list[str]:
        if directory is not None:
            recs = directory.lookup(qv, top_k=max(k, 3))
            recs = [r for r in recs if r.node_id != self.id]
            # REQ-F-03: capability-aware routing — generation-capable classes
            # (N2/N3) are proposed first; N1 relays fill remaining slots.
            recs.sort(key=lambda r: r.klass == "N1")
            return [r.node_id for r in recs][:k]
        return [n for n in (self.hub.peers(self.id) if self.hub else [])][:k]

    @staticmethod
    def _diverse_bids(bids: list[Packet]) -> list[Packet]:
        """C4: votes citing the same leading chunk_hash count once."""
        seen, out = set(), []
        for b in sorted(bids, key=lambda x: -x.payload["bid"]):
            h = b.payload["provenance"][0]["chunk_hash"]
            if h in seen:
                continue
            seen.add(h)
            out.append(b)
        return out

    def _outcome(self, mode: Mode, trace: list, detail=None, answer: str | None = None) -> dict:
        return {
            "mode": mode.value, "answer": answer, "detail": detail,
            "trace": trace, "origin": self.id,
            "budget_left": self.budget.left,
        }

    def attach(self, hub: "InMemoryHub", verifier: D2Verifier) -> None:
        self.hub = hub
        self._verifier = verifier
        hub.join(self)


# ---------------------------------------------------------------------------
# In-memory hub (transport abstraction — WhatsApp/mesh adapters implement
# the same send/join/peers surface later, v0.6 non-goals)
# ---------------------------------------------------------------------------

class InMemoryHub:
    def __init__(self):
        self.nodes: dict[str, Node] = {}

    def join(self, node: Node) -> None:
        node.hub = self
        self.nodes[node.id] = node

    def peers(self, node_id: str) -> list[str]:
        return [n for n in self.nodes if n != node_id]

    def send(self, _from: str, to: str, pkt: Packet) -> Packet | None:
        target = self.nodes.get(to)
        if target is None:
            return None
        return target.on_packet(pkt)
