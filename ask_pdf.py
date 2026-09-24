#!/usr/bin/env python3
"""ask_pdf.py — full OSP negotiation against a remote node's HTTP bridge.

Unlike test_peering.py (which posts /osp/query and lets the REMOTE node act as
origin — hence NO_QUORUM when its peer table is empty), this script runs the
origin side LOCALLY: PROPOSE → BID → ALIGN → RESOLVE, with origin-side
groundedness verification, so the remote node answers as responder from its own
corpus (e.g. a PDF just received over WhatsApp).

Usage:
  python3 ask_pdf.py "what does the newly received PDF contain?"
  python3 ask_pdf.py --url http://192.168.1.249:3000 --tier 1 "list documents received"
"""
import argparse
import json
import os
import sys
import urllib.request
import urllib.error
import uuid

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "mcp"))
from osp_core import (Action, DevSigner, LexicalVerifier, Packet,  # noqa: E402
                      embed)


def post(base, token, path, body):
    req = urllib.request.Request(
        base.rstrip("/") + path, method="POST",
        data=json.dumps(body).encode(),
        headers={"Content-Type": "application/json",
                 "Authorization": f"Bearer {token}",
                 "x-api-token": token})
    try:
        with urllib.request.urlopen(req, timeout=120) as r:
            return json.loads(r.read() or b"{}")
    except urllib.error.HTTPError as e:
        return {"http_error": e.code, **json.loads(e.read() or b"{}")}


def wire(pkt):
    return {**pkt.signed_object(), "sig": pkt.sig}


def parse_reply(raw):
    """Rebuild a Packet from the node's sealed-packet JSON reply."""
    if not raw or "action" not in raw:
        return None
    return Packet(
        action=Action(raw["action"]), origin_id=raw.get("origin_id", ""),
        query_id=raw.get("query_id", ""), sender=raw.get("sender", ""),
        gas=raw.get("gas", 0), trail=raw.get("trail", []),
        payload=raw.get("payload", {}),
    )


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("question")
    ap.add_argument("--url", default=os.environ.get("OSP_URL", "http://192.168.1.249:3000"))
    ap.add_argument("--token", default=os.environ.get("API_TOKEN", os.environ.get("OSP_TOKEN", "")))
    ap.add_argument("--tier", type=int, default=0, choices=[0, 1, 2],
                    help="0 → single explicit candidate (the node itself)")
    ap.add_argument("--peek", action="store_true",
                    help="dump the cited corpus chunk(s) instead of asking")
    args = ap.parse_args()
    if not args.token:
        sys.exit("error: --token or API_TOKEN required")

    origin = "ask-pdf-origin"
    signer = DevSigner()
    qv = list(embed(args.question))
    trace = []

    # 01 PROPOSE → 02 BID
    propose = Packet(
        action=Action.PROPOSE, origin_id=origin, query_id=uuid.uuid4().hex[:12],
        sender=origin, gas=3,
        payload={"query_vec": qv, "query_text": args.question},
    ).seal(signer)
    trace.append("PROPOSE")
    bid = parse_reply(post(args.url, args.token, "/osp/packet", wire(propose)))
    if bid is None or bid.action != Action.BID:
        print(json.dumps({"mode": "NO_QUORUM",
                          "detail": f"no BID (got {bid.action.value if bid else 'nothing'})",
                          "rfo": bid.payload if bid else None,
                          "trace": trace},
                         indent=2, ensure_ascii=False))
        return 1
    p = bid.payload
    print(f"BID  score={p.get('bid')} can_generate={p.get('can_generate')} "
          f"class={p.get('node_class')} similarity={p.get('retrieval_similarity')}")

    # --peek: dump the cited chunk(s) to see what the node's corpus actually holds
    if args.peek:
        for i, c in enumerate(p.get("provenance", [])):
            peek = Packet(
                action=Action.GET_CHUNK, origin_id=origin, query_id=bid.query_id,
                sender=origin, gas=3, payload={"chunk_hash": c["chunk_hash"]},
            ).seal(signer)
            rep = parse_reply(post(args.url, args.token, "/osp/packet", wire(peek)))
            chunk = (rep.payload.get("chunk") if rep else None) or {}
            print(f"--- provenance[{i}] score={c.get('score')} hash={c.get('chunk_hash')}")
            print(chunk.get("text", "(chunk not returned)"))
        return 0

    if not p.get("can_generate"):
        print(json.dumps({"mode": "NO_QUORUM", "detail": "bid not capable", "trace": trace},
                         indent=2, ensure_ascii=False))
        return 1
    prov = p["provenance"][0]

    # 03 ALIGN — lock-in on the cited chunk
    align = Packet(
        action=Action.ALIGN, origin_id=origin, query_id=bid.query_id,
        sender=origin, gas=3,
        payload={"query_vec": qv, "source": args.question, "target": prov["chunk_hash"]},
    ).seal(signer)
    trace.append("ALIGN")
    committed = parse_reply(post(args.url, args.token, "/osp/packet", wire(align)))
    if committed is None or committed.action == Action.RFO:
        print(json.dumps({"mode": "MISMATCH", "detail": "alignment refused", "trace": trace,
                          "rfo": committed.payload if committed else None},
                         indent=2, ensure_ascii=False))
        return 1
    print(f"ALIGN mapping_distance={committed.payload.get('mapping_distance')}")

    # 04 RESOLVE — the single generation
    resolve = Packet(
        action=Action.RESOLVE, origin_id=origin, query_id=bid.query_id,
        sender=origin, gas=3,
        payload={"query_vec": qv, "query_text": args.question},
    ).seal(signer)
    trace.append("RESOLVE")
    answered = parse_reply(post(args.url, args.token, "/osp/packet", wire(resolve)))
    if answered is None or answered.action == Action.RFO:
        print(json.dumps({"mode": "NO_QUORUM", "detail": "winner could not generate",
                          "rfo": answered.payload if answered else None,
                          "trace": trace}, indent=2, ensure_ascii=False))
        return 1

    # 05 VERIFY — origin-side lexical groundedness
    answer = answered.payload.get("answer")
    chunks = answered.payload.get("provenance", [])
    g = LexicalVerifier().groundedness(answer, chunks)
    out = {"mode": "RESOLVED" if g >= 0.6 else "REJECTED",
           "question": args.question, "answer": answer,
           "groundedness": round(g, 3),
           "provenance": [c.get("text", c)[:220] for c in chunks],
           "cost": answered.payload.get("cost"), "trace": trace}
    print(json.dumps(out, indent=2, ensure_ascii=False))
    return 0 if out["mode"] == "RESOLVED" else 1


if __name__ == "__main__":
    sys.exit(main())
