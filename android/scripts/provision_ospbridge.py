#!/usr/bin/env python3
"""Re-provision the OSP Bridge tablet node over adb, end to end.

AGP uninstalls the app after every connectedDebugAndroidTest run, which wipes
the node identity, link secret and peer table (incident of 2026-09-18, hit
again on 2026-09-20). This script restores a working node without touching
the screen: install, grant, start, peer against the whatsapp-bot node.

Every tap is preceded by a fresh uiautomator dump and followed by a check —
the keyboard shifts the layout between dumps, so stale bounds tap the wrong
field (and a BACK with no keyboard up exits the app to the launcher).

Usage:
  python3 provision_ospbridge.py <serial> <peer-url> <peer-token> [--query "question"]
"""
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

ADB = "adb"


def sh(*args, timeout=30):
    return subprocess.run([ADB, "-s", SERIAL, *args], capture_output=True,
                          text=True, timeout=timeout).stdout


def dump():
    sh("shell", "uiautomator", "dump", "/sdcard/ui-prov.xml", timeout=20)
    return ET.fromstring(sh("exec-out", "cat", "/sdcard/ui-prov.xml"))


def find(root, rid_key):
    # endswith: "id/status" must not match android:id/statusBarBackground
    for n in root.iter("node"):
        if (n.get("resource-id") or "").endswith(rid_key):
            return n
    return None


def center(node):
    x1, y1, x2, y2 = map(int, re.findall(r"\d+", node.get("bounds")))
    return (x1 + x2) // 2, (y1 + y2) // 2


def tap_node(rid_key, check_focus=None):
    n = find(dump(), rid_key)
    assert n is not None, f"{rid_key} not on screen"
    x, y = center(n)
    sh("shell", "input", "tap", str(x), str(y))
    time.sleep(1)
    if check_focus:
        assert find(dump(), rid_key).get("focused") == "true", \
            f"{rid_key} did not take focus after tap at ({x},{y})"
    return n


def type_text(text):
    # adb joins args with spaces — the device shell needs the text quoted
    sh("shell", "input", "text", f"'{text}'")
    time.sleep(0.5)


def read_field(rid_key):
    n = find(dump(), rid_key)
    return (n.get("text") or "") if n is not None else ""


SERIAL = sys.argv[1]
PEER_URL = sys.argv[2]
PEER_TOKEN = sys.argv[3]
QUERY = sys.argv[sys.argv.index("--query") + 1] if "--query" in sys.argv else None
PKG = "com.tree4five.osp"
ACT = f"{PKG}/com.swarmknowledge.ospbridge.MainActivity"
APK = "ospbridge/build/outputs/apk/debug/ospbridge-debug.apk"

print("install + grant + launch")
print(sh("install", "-r", APK, timeout=180).strip().splitlines()[-1])
sh("shell", "pm", "grant", PKG, "android.permission.POST_NOTIFICATIONS")
sh("shell", "am", "start", "-n", ACT)
time.sleep(2)

print("start node")
tap_node("btnStart")
for _ in range(10):
    time.sleep(1)
    st = read_field("id/status") or ""
    if "node" in st:
        print("  " + st.splitlines()[0])
        break
else:
    sys.exit("node did not come up")

print("peer: url")
tap_node("peerUrl", check_focus=True)
type_text(PEER_URL)
assert read_field("peerUrl") == PEER_URL, "URL field mismatch"
sh("shell", "input", "keyevent", "111")   # dismiss keyboard (ESC)
time.sleep(1)

print("peer: token")
tap_node("peerToken", check_focus=True)
type_text(PEER_TOKEN)
assert len(read_field("peerToken") or "") == len(PEER_TOKEN), "token field mismatch"
sh("shell", "input", "keyevent", "111")
time.sleep(1)

print("add peer")
tap_node("btnAddPeer")
for _ in range(8):
    time.sleep(1)
    out = read_field("id/out") or ""
    if "peer added" in out:
        print("  " + out)
        break
else:
    sys.exit(f"peer was not added, out={out!r}")

if QUERY:
    print(f"query: {QUERY!r}")
    tap_node("queryBox", check_focus=True)
    type_text(re.sub(r"[^A-Za-z0-9 ]", "", QUERY))
    sh("shell", "input", "keyevent", "111")
    time.sleep(1)
    tap_node("btnQuery")
    for _ in range(60):
        time.sleep(2)
        out = read_field("id/out") or ""
        if out.startswith("mode="):
            print("  " + out.splitlines()[0])
            break
    else:
        sys.exit("query produced no mode= line in 120 s")
    print("PROVISIONED + VERIFIED")
else:
    print("PROVISIONED")
