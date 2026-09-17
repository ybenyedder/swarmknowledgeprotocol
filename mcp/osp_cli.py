#!/usr/bin/env python3
"""osp_cli.py — command-line client for OSP nodes (ospbridge app, whatsapp-bot).

Talks to a node's HTTP bridge: status, verified queries, teaching, peer table,
sealed-packet debugging. Stdlib only.

Usage:
  python3 osp_cli.py --url http://192.168.1.249:3000 --token $API_TOKEN status
  python3 osp_cli.py --url http://<tablet-ip>:8090 --token $OSP_BRIDGE_TOKEN query "list documents received" --tier 1
  python3 osp_cli.py --url http://<tablet-ip>:8090 --token $T peers set --map '{"whatsapp-bot": "http://192.168.1.249:3000"}'
  python3 osp_cli.py --url http://<tablet-ip>:8090 --token $T teach "chunk text..."
  python3 osp_cli.py --url http://<tablet-ip>:8090 --token $T packet --action PROPOSE --query "hello"
"""
import argparse
import json
import os
import sys
import urllib.request
import urllib.error

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from osp_core import Packet, Action, DevSigner, embed  # noqa: E402


def call(base, token, method, path, body=None):
    req = urllib.request.Request(
        base.rstrip('/') + path, method=method,
        data=json.dumps(body).encode() if body is not None else None,
        headers={'Content-Type': 'application/json',
                 'Authorization': f'Bearer {token}',
                 'x-api-token': token})
    try:
        with urllib.request.urlopen(req, timeout=120) as r:
            data = r.read()
            return r.status, json.loads(data) if data else None
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read() or b'{}')
    except urllib.error.URLError as e:
        sys.exit(f'error: cannot reach {base} ({e.reason})')


def show(status, payload):
    print(json.dumps(payload, indent=2, sort_keys=True, ensure_ascii=False)
          if payload is not None else f'({status}, no content)')
    return 0 if status < 400 else 1


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument('--url', default=os.environ.get('OSP_URL', 'http://localhost:8090'))
    ap.add_argument('--token', default=os.environ.get('OSP_TOKEN', ''))
    sub = ap.add_subparsers(dest='cmd', required=True)

    sub.add_parser('status', help='node status')
    sub.add_parser('endpoint', help='discovery record (endpoint.json)')
    p = sub.add_parser('query', help='verified OSP negotiation (origin side)')
    p.add_argument('text')
    p.add_argument('--tier', type=int, default=1, choices=[0, 1, 2])
    p = sub.add_parser('teach', help='add a knowledge chunk (responder side)')
    p.add_argument('text')
    p = sub.add_parser('peers', help='get or set the remote peer table')
    p.add_argument('action', choices=['get', 'set'])
    p.add_argument('--map', dest='peers_map', default='{}', help='JSON map nodeId → baseUrl')
    p = sub.add_parser('packet', help='craft + send one sealed packet (debug)')
    p.add_argument('--action', default='PROPOSE', choices=[a.value for a in Action])
    p.add_argument('--query', default='hello', help='query_text payload')
    p.add_argument('--origin', default='cli-origin')

    args = ap.parse_args()
    if not args.token:
        sys.exit('error: --token or OSP_TOKEN required')

    if args.cmd == 'status':
        s, p = call(args.url, args.token, 'GET', '/osp/status')
    elif args.cmd == 'endpoint':
        s, p = call(args.url, args.token, 'GET', '/osp/endpoint.json')
    elif args.cmd == 'query':
        s, p = call(args.url, args.token, 'POST', '/osp/query',
                    {'text': args.text, 'tier': args.tier})
    elif args.cmd == 'teach':
        s, p = call(args.url, args.token, 'POST', '/osp/teach', {'text': args.text})
    elif args.cmd == 'peers':
        if args.action == 'get':
            s, p = call(args.url, args.token, 'GET', '/osp/peers')
        else:
            s, p = call(args.url, args.token, 'POST', '/osp/peers',
                        {'peers': json.loads(args.peers_map)})
    elif args.cmd == 'packet':
        signer = DevSigner()
        pkt = Packet(
            action=Action(args.action), origin_id=args.origin, query_id=os.urandom(6).hex(),
            sender=args.origin, gas=3,
            payload={'query_vec': list(embed(args.query)), 'query_text': args.query},
        ).seal(signer)
        wire = {**pkt.signed_object(), 'sig': pkt.sig}
        s, p = call(args.url, args.token, 'POST', '/osp/packet', wire)
    return show(s, p)


if __name__ == '__main__':
    sys.exit(main())
