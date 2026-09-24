#!/usr/bin/env python3
"""Swarm non-regression: N standalone OSP nodes that peer, share knowledge
and negotiate over the real HTTP wire.

Each "virtual machine" is a full `osp_node.py` subprocess — its own process,
port, link token and knowledge fragment. The script wires a full mesh, then
runs three phases:

  1. ROUTING  (tier 0) — every query fired at one VM must land on the VM
     that actually holds the knowledge (winner provenance, groundedness).
  2. QUORUM   (tier 1) — two VMs hold DIFFERENT chunks about the same topic;
     the origin must collect both BIDs (diversity rule C4) and resolve.
  3. HONESTY  — a query nobody can cover must end in an explicit non-RESOLVED
     outcome, never a made-up answer.

Usage:
  python3 swarm_nonreg.py                  # 5 VMs, all phases
  python3 swarm_nonreg.py --nodes 8        # scale the swarm
  python3 swarm_nonreg.py --with-android   # + the Android node (see below)

With `--with-android`, the script additionally joins the Android app
(com.tree4five.osp) as node N+1 on every adb device found (emulators AND
physical devices — list serials to restrict): it installs the APK when
missing (release on rooted devices, debug elsewhere so the link secret is
readable), starts the node service, waits for the LLM bridge, forwards the
port, peers both ways and runs the app as origin and as responder.

Exit code: number of failed checks (0 = green).
"""
from __future__ import annotations

import argparse
import json
import os
import re
import socket
import subprocess
import sys
import time
import urllib.error
import urllib.request

from providers import OllamaProvider   # noqa: E402 — HERE is on sys.path at runtime

HERE = os.path.dirname(os.path.abspath(__file__))
NODE = os.path.join(HERE, "osp_node.py")

# One distinct knowledge domain per VM index (index 3/4 share the solar topic
# with different chunks — that pair feeds the tier-1 quorum phase).
DOMAINS = [
    ("hydraulic-pump",
     "The hydraulic pump failed because the inlet filter was clogged with debris.",
     "why does the hydraulic pump fail"),
    ("chromecast-pairing",
     "Chromecast pairing requires the 4-digit PIN shown on the TV screen.",
     "how does chromecast pairing work"),
    ("espresso-descaling",
     "The espresso machine descaling cycle uses a 1:1 water to citric acid solution.",
     "what does the espresso machine descaling use"),
    ("solar-inverter-a",
     "Solar inverters shut down at night and restart automatically at sunrise.",
     "how does a solar inverter behave overnight"),
    ("solar-inverter-b",
     "At night the solar inverter switches to standby and powers back on at dawn.",
     "how does a solar inverter behave overnight"),  # same query: quorum pair
]
UNKNOWN_QUERY = "how does quantum entanglement distribute encryption keys"

# The Android node gets a topic no VM holds, so its provenance is unambiguous.
ANDROID_DOMAIN = ("first-aid",
                  "For a nosebleed, sit the person down and pinch the soft "
                  "part of the nose for ten minutes.",
                  "how do you stop a nosebleed")

# Routing contract of OSP "Connect" without a discovery directory: the origin
# proposes to the first k entries of its peer table (k = 1/2/3 for tier 0/1/2).
# The phases below order each origin's table before each query so every check
# is deterministic; topical routing comes from the Directory (MCP stack).
def set_table(vm: Vm, ordered: list[str]):
    """Rewrite vm's peer table with the given node order (first = consulted).
    `ordered` holds node names; ALL_NODES maps them to url/token handles."""
    by_name: dict[str, dict] = {}
    for name in ordered:
        src = next(o for o in ALL_NODES if o.name == name)
        by_name[name] = {"url": src.url, "token": src.token}
    code, out = vm.call("POST", "/osp/peers", {"peers": by_name})
    assert code == 200, f"{vm.name}: set_table failed ({code}) {out}"


ALL_NODES: list = []   # Vm + AndroidNode handles, filled by main()
QUERY_TIMEOUT = 60.0   # client timeout for the VM query phases; raised when
                       # --ollama routes generation to a real LAN model


def http(method: str, url: str, token: str | None = None, body: dict | None = None,
         timeout: float = 60) -> tuple[int, dict | str]:
    """POST/GET with the double-header auth every OSP node accepts."""
    req = urllib.request.Request(url, method=method)
    if token:
        req.add_header("x-api-token", token)
        req.add_header("Authorization", f"Bearer {token}")
    data = None
    if body is not None:
        data = json.dumps(body).encode()
        req.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(req, data=data, timeout=timeout) as r:
            raw = r.read().decode()
            try:
                return r.status, json.loads(raw)
            except ValueError:
                return r.status, raw
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode()[:200]
    except Exception as e:  # noqa: BLE001 — the report IS the point
        return 0, f"{type(e).__name__}: {e}"


def free_port() -> int:
    s = socket.socket()
    s.bind(("127.0.0.1", 0))
    p = s.getsockname()[1]
    s.close()
    return p


def wait_up(url: str, deadline_s: float = 20.0) -> bool:
    end = time.time() + deadline_s
    while time.time() < end:
        code, _ = http("GET", url)
        if code == 200:
            return True
        time.sleep(0.3)
    return False


def winner_from_trace(outcome: dict) -> str | None:
    """The ALIGN hop is emitted with the winner's sender id."""
    for hop in outcome.get("trace", []):
        if hop.get("action") == "ALIGN":
            return hop.get("from")
    return None


class Vm:
    """One virtual machine = one osp_node.py subprocess."""

    def __init__(self, name: str, proc: subprocess.Popen, port: int, token: str):
        self.name, self.proc, self.port, self.token = name, proc, port, token
        self.url = f"http://127.0.0.1:{port}"

    def call(self, method: str, path: str, body: dict | None = None,
             timeout: float = 60) -> tuple[int, dict | str]:
        return http(method, self.url + path, self.token, body, timeout)

    def query(self, text: str, tier: int = 0, timeout: float = 60) -> dict:
        code, out = self.call("POST", "/osp/query",
                              {"query": text, "tier": tier}, timeout=timeout)
        if code != 200 or not isinstance(out, dict):
            return {"mode": "HTTP_ERROR", "detail": f"{code} {out}"}
        return out.get("outcome", out)   # /osp/query wraps: {"outcome": …, "node": …}

    def stop(self):
        if self.proc.poll() is None:
            self.proc.terminate()
            try:
                self.proc.wait(timeout=5)
            except subprocess.TimeoutExpired:
                self.proc.kill()


def spawn_vms(n: int, log_dir: str, ollama: str | None = None) -> list[Vm]:
    vms: list[Vm] = []
    os.makedirs(log_dir, exist_ok=True)
    env = dict(os.environ)
    if ollama:
        # real generation rung for the VMs (N3); osp_node reads these itself
        env["OSP_PROVIDER_URL"] = ollama
        env["OSP_PROVIDER_KIND"] = "ollama"
    for i in range(n):
        name, chunk, _q = DOMAINS[i % len(DOMAINS)]
        port, token = free_port(), os.urandom(12).hex()
        log = open(os.path.join(log_dir, f"{name}.log"), "wb")
        proc = subprocess.Popen(
            [sys.executable, NODE, "--id", name, "--port", str(port),
             "--token", token],
            cwd=HERE, stdout=log, stderr=log, env=env)
        vms.append(Vm(name, proc, port, token))
    for vm in vms:
        assert vm.proc.poll() is None, f"{vm.name} died at startup"
        assert wait_up(vm.url + "/osp/status"), f"{vm.name} never came up"
    return vms


def full_mesh(vms: list[Vm], extra: dict[str, dict] | None = None) -> None:
    """Every VM learns every other node's url + link secret."""
    for vm in vms:
        table = {o.name: {"url": o.url, "token": o.token}
                 for o in vms if o is not vm}
        if extra:
            table.update(extra)
        code, out = vm.call("POST", "/osp/peers", {"peers": table})
        assert code == 200, f"{vm.name}: peering failed ({code}) {out}"


def teach(vm: Vm, chunk: str) -> None:
    code, out = vm.call("POST", "/osp/teach", {"text": chunk})
    assert code == 200, f"{vm.name}: teach failed ({code}) {out}"


class Report:
    def __init__(self):
        self.passed = self.failed = 0

    def check(self, ok: bool, label: str, detail: str = "") -> bool:
        mark = "PASS" if ok else "FAIL"
        print(f"  [{mark}] {label}" + (f" — {detail}" if detail else ""))
        if ok:
            self.passed += 1
        else:
            self.failed += 1
        return ok

    def done(self, phase: str):
        print(f"  — {phase}: {self.passed} passed, {self.failed} failed\n")


def phase_routing(vms: list[Vm], r: Report) -> None:
    """tier 0 (k=1, q=1): the origin proposes to the FIRST peer in its table.
    Each query puts the knowledge owner first, so the whole cross matrix must
    resolve with the owner as provenance — that is the knowledge-sharing
    guarantee a configured swarm gives."""
    others = [o for o in ALL_NODES]
    for src in vms:
        for dst in vms:
            if src is dst:
                continue
            topic, _chunk, query = DOMAINS[vms.index(dst) % len(DOMAINS)]
            set_table(src, [dst.name] + [o.name for o in others
                                         if o.name not in (src.name, dst.name)])
            out = src.query(query, tier=0, timeout=QUERY_TIMEOUT)
            ok = out.get("mode") == "RESOLVED"
            r.check(ok, f"{src.name} → {topic} (tier 0) resolves",
                    f"mode={out.get('mode')} g={out.get('groundedness')}")
            if ok:
                w = winner_from_trace(out)
                r.check(w == dst.name,
                        f"{src.name} → {topic}: provenance is {dst.name}",
                        f"winner={w}")
                g = out.get("groundedness") or 0
                r.check(g >= 0.35, f"{src.name} → {topic}: grounded",
                        f"g={g} (threshold 0.35)")


def phase_quorum(vms: list[Vm], r: Report) -> None:
    """tier 1 (k=2, q=2) with the two solar VMs first in the table: both are
    consulted, hold DIFFERENT chunks on the topic, and their diverse BIDs
    must satisfy the quorum (diversity rule C4). Needs --nodes ≥ 5 — the
    duplicated-topic pair lives at VM indexes 3/4."""
    if len(vms) < 5:
        print(f"  (skip) quorum phase needs --nodes ≥ 5, got {len(vms)}")
        return
    src = vms[0]
    rest = [o.name for o in ALL_NODES
            if o.name not in (src.name, vms[3].name, vms[4].name)]
    set_table(src, [vms[3].name, vms[4].name] + rest)
    out = src.query(DOMAINS[3][2], tier=1, timeout=QUERY_TIMEOUT)
    ok = r.check(out.get("mode") == "RESOLVED",
                 f"{src.name} → solar-inverter quorum (tier 1) resolves",
                 f"mode={out.get('mode')} g={out.get('groundedness')}")
    if ok:
        bidders = {h.get("from") for h in out.get("trace", [])
                   if h.get("action") == "BID"}
        r.check({vms[3].name, vms[4].name} <= bidders,
                "both solar VMs bid (diversity quorum C4)",
                f"bidders={sorted(b for b in bidders if b)}")


def phase_honesty(vms: list[Vm], r: Report) -> None:
    """An unknown topic must end in an explicit non-RESOLVED outcome. The
    candidate matters: the pre-bid coverage gate is lexical, so a chunk with
    incidental word overlap can pass and the echo provider will honestly CITE
    it. The solar VMs demonstrably have no coverage of the quantum topic, so
    they are the deterministic honest-abstention target (needs --nodes ≥ 5)."""
    if len(vms) < 5:
        print(f"  (skip) honesty phase needs --nodes ≥ 5, got {len(vms)}")
        return
    src, candid = vms[0], vms[4]
    set_table(src, [candid.name] + [o.name for o in ALL_NODES
                                    if o.name not in (src.name, candid.name)])
    out = src.query(UNKNOWN_QUERY, tier=0, timeout=QUERY_TIMEOUT)
    mode = out.get("mode")
    r.check(mode is not None and mode != "RESOLVED" and mode != "HTTP_ERROR",
            f"unknown topic → honest abstention (no competent evidence)",
            f"mode={mode} detail={out.get('detail')}")


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--nodes", type=int, default=5,
                    help="number of virtual machines (default 5)")
    ap.add_argument("--with-android", nargs="*", default=None, metavar="SERIAL",
                    help="join the Android app node(s) on these adb devices "
                         "(default: every adb device found)")
    ap.add_argument("--android-token", action="append", default=[], metavar="SERIAL=SECRET",
                    help="link secret of an app that adb cannot read "
                         "(repeatable)")
    ap.add_argument("--keep", action="store_true",
                    help="leave the VM processes running after the run")
    ap.add_argument("--ollama", default=None, metavar="HOST:11434",
                    help="use this LAN Ollama server as the VMs' D3 provider "
                         "(real LLM generation instead of the echo provider); "
                         "the model is picked from /api/tags")
    args = ap.parse_args()
    given = dict(t.split("=", 1) for t in args.android_token)

    n = max(2, args.nodes)
    log_dir = os.path.join(HERE, ".nonreg-logs")
    vms = spawn_vms(n, log_dir, ollama=args.ollama)
    print(f"swarm: {n} VMs up " +
          " ".join(f"{v.name}@{v.port}" for v in vms) +
          (f" — D3 via ollama://{args.ollama}" if args.ollama else ""))
    r = Report()
    androids: list[AndroidNode] = []
    try:
        ALL_NODES.extend(vms)
        if args.with_android is not None:
            androids = join_androids(vms, args.with_android, given)
            ALL_NODES.extend(androids)
        full_mesh(vms, extra={a.name: {"url": a.url, "token": a.token}
                              for a in androids})
        for i, vm in enumerate(vms):
            teach(vm, DOMAINS[i % len(DOMAINS)][1])

        if args.ollama:
            # a cold Ollama loads the model on the first request (minutes on a
            # big quant) — warm it now so phase timings measure the protocol,
            # not the model load, and give the phases a matching client budget
            global QUERY_TIMEOUT
            QUERY_TIMEOUT = 300.0
            warm = OllamaProvider(args.ollama)
            print(f"ollama: model {warm._resolve_model()} — warming up …")
            try:
                warm.generate("warmup", [])
                print("ollama: warm")
            except Exception as e:   # noqa: BLE001 — phases will report honestly
                print(f"ollama: warmup failed ({type(e).__name__}: {e})")

        print("\nphase 1 — routing (tier 0): the knowledge owner answers")
        phase_routing(vms, r)
        print("phase 2 — quorum (tier 1): two chunks, one topic, diverse BIDs")
        phase_quorum(vms, r)
        print("phase 3 — honesty: unknown topic → explicit abstention")
        phase_honesty(vms, r)
        for a in androids:
            print(f"phase 4 — android node {a.name} ({a.serial}): "
                  f"app as responder and as origin")
            phase_android(a, vms, r)
    finally:
        if not args.keep:
            for vm in vms:
                vm.stop()
        else:
            print("kept alive: " + " ".join(f"{v.name}={v.url} token={v.token}"
                                            for v in vms))
    print(f"RESULT: {r.passed} passed, {r.failed} failed")
    return r.failed


# ---------------------------------------------------------------------------
# Android node ----------------------------------------------------------------
# ---------------------------------------------------------------------------

ANDROID_PKG = "com.tree4five.osp"
ANDROID_PORT = 8090
ADB = "/home/pc/Android/Sdk/platform-tools/adb"
APK_DIR = os.path.join(HERE, "..", "android", "ospbridge", "build", "outputs",
                       "apk")
PREFS = f"/data/data/{ANDROID_PKG}/shared_prefs/ospbridge.xml"


def adb(*args: str, serial: str | None = None) -> str:
    a = [ADB]
    if serial:
        a += ["-s", serial]
    a += list(args)
    return subprocess.run(a, capture_output=True, text=True, timeout=180)


def adb_devices() -> list[str]:
    out = adb("devices").stdout
    return [ln.split()[0] for ln in out.splitlines()[1:]
            if ln.strip().endswith("device")]


def is_emulator(serial: str) -> bool:
    return serial.startswith("emulator-") or serial.startswith("127.0.0.1:")


def has_root(serial: str) -> bool:
    return "uid=0" in adb("shell", "su", "0", "id", serial=serial).stdout


def read_token(serial: str) -> str:
    """The link secret lives in the app's private prefs — readable over adb
    with root, or run-as on a debuggable (debug) build."""
    for cmd in (["shell", "su", "0", "cat", PREFS],
                ["shell", "run-as", ANDROID_PKG, "cat", "shared_prefs/ospbridge.xml"]):
        out = adb(*cmd, serial=serial).stdout
        m = re.search(r'name="token">([^<]+)<', out)
        if m:
            return m.group(1)
    return ""


class AndroidNode:
    def __init__(self, serial: str, fwd_port: int, token: str):
        self.serial, self.fwd_port, self.token = serial, fwd_port, token
        self.name = "android-" + serial.lower().replace(":", "-")
        self.url = f"http://127.0.0.1:{fwd_port}"          # from the host
        if is_emulator(serial):
            self.host_url = f"http://10.0.2.2:{ANDROID_PORT}"   # emu → host alias
        else:
            self.host_url = f"http://127.0.0.1:{ANDROID_PORT}"  # USB adb reverse
        self.app_node_id = None

    def call(self, method: str, path: str, body: dict | None = None,
             timeout: float = 120) -> tuple[int, dict | str]:
        return http(method, self.url + path, self.token, body, timeout)

    def query(self, text: str, tier: int = 0) -> dict:
        code, out = self.call("POST", "/osp/query", {"query": text, "tier": tier})
        if code != 200 or not isinstance(out, dict):
            return {"mode": "HTTP_ERROR", "detail": f"{code} {out}"}
        return out.get("outcome", out)


def join_androids(vms: list[Vm], serials: list[str], given_tokens: dict,
                  ) -> list[AndroidNode]:
    """Prepare every Android device as a swarm node: install the app when
    missing (release on rooted devices, debug elsewhere — the link secret
    must be readable), start the node service, wait for the LLM bridge, then
    wire peering in both directions."""
    if not serials:
        serials = adb_devices()
        assert serials, "no adb device found"
        print(f"android: auto-detected devices: {', '.join(serials)}")
    nodes = []
    for dev in serials:
        nodes.append(prepare_device(dev, vms, given_tokens.get(dev, "")))
    # peer every VM with every android node (host side goes through forwards)
    full_mesh(vms, extra={n.name: {"url": n.url, "token": n.token}
                          for n in nodes})
    # each app's own table: the VMs via its device-appropriate host alias.
    # Physical devices have no host alias — one adb reverse per VM port maps
    # the app's loopback onto every VM's ephemeral port.
    for n in nodes:
        host = re.search(r"//([^:/]+):", n.host_url).group(1)  # 10.0.2.2 | 127.0.0.1
        if host == "127.0.0.1":
            for v in vms:
                adb("reverse", f"tcp:{v.port}", f"tcp:{v.port}", serial=n.serial)
        table = {v.name: {"url": v.url.replace("127.0.0.1", host),
                          "token": v.token} for v in vms}
        code, out = n.call("POST", "/osp/peers", {"peers": table})
        assert code == 200, f"{n.serial}: app peering failed: {code} {out}"
    return nodes


def prepare_device(dev: str, vms: list[Vm], given_token: str) -> AndroidNode:
    print(f"android: preparing {dev}")
    assert dev in adb_devices(), f"no such adb device: {dev}"

    installed = ANDROID_PKG in adb("shell", "pm", "list", "packages",
                                   ANDROID_PKG, serial=dev).stdout
    if not installed:
        root = has_root(dev)
        variant = "release" if root else "debug"
        apk = os.path.abspath(os.path.join(APK_DIR, variant,
                                           f"ospbridge-{variant}.apk"))
        if variant == "debug" and not os.path.isfile(apk):
            print("android: building debug APK (non-rooted device needs a "
                  "readable link secret)…")
            g = subprocess.run(["./gradlew", ":ospbridge:assembleDebug"],
                               cwd=os.path.join(HERE, "..", "android"),
                               capture_output=True, text=True, timeout=600)
            assert g.returncode == 0, f"debug build failed:\n{g.stdout[-800:]}"
        assert os.path.isfile(apk), f"APK not found: {apk}"
        adb("install", "-r", apk, serial=dev)
        print(f"android: installed {os.path.basename(apk)}")

    # start the node service FIRST, the activity second — the activity only
    # touches the token in onCreate. NOTE the component names: applicationId
    # is com.tree4five.osp but the classes live in the
    # com.swarmknowledge.ospbridge namespace.
    adb("shell", "am", "start-foreground-service", "-a",
        "com.swarmknowledge.ospbridge.ACTION_OSP_SERVICE", "-n",
        f"{ANDROID_PKG}/com.swarmknowledge.ospbridge.OspService", serial=dev)
    time.sleep(3)
    adb("shell", "am", "start", "-n",
        f"{ANDROID_PKG}/com.swarmknowledge.ospbridge.MainActivity", serial=dev)

    fwd_port = free_port()
    adb("forward", f"tcp:{fwd_port}", f"tcp:{ANDROID_PORT}", serial=dev)
    if not is_emulator(dev):
        # physical devices have no 10.0.2.2: map the app's loopback to the host
        adb("reverse", f"tcp:{ANDROID_PORT}", f"tcp:{ANDROID_PORT}", serial=dev)

    # the token is created lazily on first access: any authenticated-looking
    # request evaluates service.token in the auth check, so a deliberate 401
    # probe persists the link secret where run-as/root can read it
    http("POST", f"http://127.0.0.1:{fwd_port}/osp/teach",
         token="probe", body={"text": ""}, timeout=10)

    token = given_token
    end = time.time() + 30
    while not token and time.time() < end:
        token = read_token(dev)
        if not token:
            time.sleep(2)
    assert token, (f"cannot read the link secret on {dev} (no root, release "
                   f"build) — pass it with --android-token {dev}=SECRET")

    node = AndroidNode(dev, fwd_port, token)
    code, status = node.call("GET", "/osp/status")
    assert code == 200 and isinstance(status, dict), \
        f"app bridge unreachable through adb forward: {code} {status}"
    node.app_node_id = status.get("node_id")
    # the LLM bridge binds asynchronously — a cold BID carries
    # can_generate=false and the whole negotiation reads as NO_QUORUM
    end = time.time() + 90
    while not status.get("llmprovider_bound") and time.time() < end:
        time.sleep(2)
        _c, status = node.call("GET", "/osp/status")
    bound = status.get("llmprovider_bound")
    print(f"android: {node.name} node {node.app_node_id} up "
          f"(chunks={status.get('chunks')}, llmprovider_bound={bound}"
          f"{' v' + str(status.get('llmprovider_version')) if bound else ''})")

    # the app gets a topic no VM holds, so its provenance stays unambiguous
    code, out = node.call("POST", "/osp/teach", {"text": ANDROID_DOMAIN[1]})
    assert code == 200, f"app teach failed: {code} {out}"
    return node


def phase_android(android: AndroidNode, vms: list[Vm], r: Report) -> None:
    """The app as responder and as origin over the sealed-packet wire.
    Both directions run at tier 0 with the intended counterpart first in the
    querying node's table (the Connect routing contract, see module doc)."""
    # 1. app as responder: a VM asks the topic only the app was taught.
    #    A sealed BID from the app (verified by the VM's core) proves the
    #    wire; RESOLVED additionally proves the on-device generation passed
    #    the alignment + groundedness firewalls. The other accepted modes are
    #    the honesty properties doing their job on weak on-device models:
    #    MISMATCH (alignment refused), NO_QUORUM (provider could not
    #    generate) and REJECTED (origin firewall refused an ungrounded
    #    answer — e.g. a small quant replying with degenerate repetition).
    src = vms[0]
    others = [o.name for o in ALL_NODES if o.name not in (src.name, android.name)]
    set_table(src, [android.name] + others)
    out = src.query(ANDROID_DOMAIN[2], tier=0, timeout=900)   # on-device gen is slow (real model)
    mode = out.get("mode")
    trace = out.get("trace", [])
    r.check(mode in ("RESOLVED", "MISMATCH", "NO_QUORUM", "REJECTED"),
            f"app as responder: {src.name} → {ANDROID_DOMAIN[0]} "
            f"ends in an explicit outcome",
            f"mode={mode} detail={out.get('detail')} g={out.get('groundedness')}")
    # the BID hop is keyed by the table alias; the ALIGN hop carries the app's
    # sealed sender id (its internal node id) — both prove a real exchange
    bid = any(h.get("action") == "BID" and h.get("from") == android.name
              for h in trace)
    r.check(bid, "app sealed a verifiable BID on the wire",
            f"BID from {android.name}" if bid else f"trace={trace}")
    if mode == "RESOLVED":
        r.check(winner_from_trace(out) == android.app_node_id,
                "provenance is the android node",
                f"winner={winner_from_trace(out)}")
    elif mode == "MISMATCH":
        print("  (info) alignment firewall refused the emulated model's "
              "confirm — wire negotiation itself succeeded")
    elif mode == "REJECTED":
        print("  (info) origin firewall refused the app's answer as "
              "ungrounded — the on-device model needs attention, the "
              "protocol did its job")

    # 2. app as origin: ask through the app. Kotlin's peer map is unordered
    #    (unlike Python's insertion order), so k=1 tier 0 would pick an
    #    arbitrary peer — give the app EXACTLY the VM owner as its table and
    #    the proposal deterministically lands on the knowledge holder. The
    #    echo provider is always capable, so RESOLVED is expected.
    topic, _c, query = DOMAINS[0]
    src = vms[0]
    code, out = android.call("POST", "/osp/peers",
                             {"peers": {src.name: {"url": src.url.replace(
                                 "127.0.0.1", re.search(r"//([^:/]+):",
                                 android.host_url).group(1)),
                                 "token": src.token}}})
    assert code == 200, f"{android.serial}: set table failed {code} {out}"
    out = android.query(query, tier=0)
    mode = out.get("mode")
    r.check(mode not in (None, "HTTP_ERROR"),
            f"app as origin: → {topic} ends in an explicit outcome",
            f"mode={mode} detail={out.get('detail')}")
    if mode == "RESOLVED":
        r.check(winner_from_trace(out) == vms[0].name,
                f"app as origin: provenance is {vms[0].name}",
                f"winner={winner_from_trace(out)}")
    else:
        print(f"  (info) app trace: {json.dumps(out.get('trace'))[:400]}")


if __name__ == "__main__":
    sys.exit(main())
