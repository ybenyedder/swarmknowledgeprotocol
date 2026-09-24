"""OSP connectivity checker — probes the D3 provider targets and runs a real
grounding round-trip against whichever answers.

Targets checked:
  1. $OSP_PROVIDER_URL (if set) — OpenAI-compatible or Ollama (:11434 auto-detected)
  2. --remote host[:port] arguments (e.g. 192.168.1.194:11434)
  3. ../LLMProvider — on-device AIDL service; reachable only from the phone
     (harnessdroid), reported as such, never as a crash

Usage:
  python3 osp_ping.py --remote 192.168.1.194:11434
  python3 osp_ping.py                # env target + LLMProvider status only

Exit code 0 if at least one HTTP target answered.
No network is performed against targets you did not name or configure.
"""
from __future__ import annotations

import argparse
import json
import os
import time
import urllib.request

from providers import (LLMProviderFileAdapter, OllamaProvider, OpenAICompatProvider,
                       build_envelope, ensure_scheme)

QUERY = "why does hydraulic pump failure happen"
EVIDENCE = [{"hash": "sha256:demo", "text":
             "hydraulic pump failure is caused by cavitation and worn seals"}]


def probe_openai_compat(base_url: str, model: str, timeout: float) -> dict:
    """One grounded generation round-trip; returns answer, latency, tokens."""
    provider = OpenAICompatProvider(base_url, model, os.environ.get("OSP_PROVIDER_API_KEY", ""),
                                    timeout_s=timeout)
    envelope = build_envelope(QUERY, EVIDENCE)
    t0 = time.time()
    out = provider.generate(QUERY, EVIDENCE)
    dt = time.time() - t0
    return {
        "ok": True, "model": model, "latency_s": round(dt, 2),
        "answer": out["answer"][:160], "cost": out.get("cost", {}),
        "envelope_chars": len(envelope),
    }


def check_host(host: str, timeout: float = 90.0) -> dict:
    port = host.rsplit(":", 1)[-1]
    if port == "11434":
        ollama = OllamaProvider(host, timeout_s=timeout)
        try:
            models = ollama.list_models()
        except Exception as exc:
            return {"target": host, "ok": False, "error": f"{type(exc).__name__}: {exc}"}
        model = os.environ.get("OSP_PROVIDER_MODEL", "")
        if not model and models:
            model = ollama._pick(models)
        result = {"target": host, "ok": True, "kind": "ollama", "models": models}
        if model:
            try:
                result.update(probe_openai_compat(ollama.base_url, model, timeout))
            except Exception as exc:
                result["generation_error"] = f"{type(exc).__name__}: {exc}"
        return result
    # generic OpenAI-compatible
    model = os.environ.get("OSP_PROVIDER_MODEL", "default")
    try:
        out = probe_openai_compat(ensure_scheme(host) + "/v1", model, timeout)
        return {"target": host, "ok": True, "kind": "openai-compat", **out}
    except Exception as exc:
        return {"target": host, "ok": False, "error": f"{type(exc).__name__}: {exc}"}


def main() -> int:
    ap = argparse.ArgumentParser(description="OSP D3 provider connectivity check")
    ap.add_argument("--remote", action="append", default=[],
                    help="host[:port] to probe (repeatable), e.g. 192.168.1.194:11434")
    ap.add_argument("--timeout", type=float, default=90.0)
    ap.add_argument("--json", action="store_true", help="machine-readable output")
    args = ap.parse_args()

    results = []
    env_url = os.environ.get("OSP_PROVIDER_URL")
    if env_url:
        results.append(check_host(env_url, args.timeout))
    for host in args.remote:
        results.append(check_host(host, args.timeout))

    llmp = LLMProviderFileAdapter().status()
    results.append({"target": "../LLMProvider (Android AIDL)", **llmp})

    if args.json:
        print(json.dumps(results, indent=2))
    else:
        for r in results:
            print(f"\n=== {r.get('target')}")
            if r.get("reachable") is False:
                print(f"  not reachable over HTTP — {r['reason']}")
                print(f"  dirs seen: {r.get('dirs_seen')}")
            elif r.get("ok"):
                print(f"  OK ({r.get('kind')}) · models: {len(r.get('models', []))}")
                if "latency_s" in r:
                    print(f"  grounded generation · {r['model']} · {r['latency_s']}s "
                          f"· tokens={r.get('cost', {}).get('tokens', '?')}")
                    print(f"  answer: {r['answer']}")
                elif "models" in r:
                    for m in r["models"][:9]:
                        print(f"   - {m}")
            else:
                print(f"  UNREACHABLE — {r.get('error')}")

    http_ok = any(r.get("ok") for r in results)
    return 0 if http_ok else 1


if __name__ == "__main__":
    raise SystemExit(main())
