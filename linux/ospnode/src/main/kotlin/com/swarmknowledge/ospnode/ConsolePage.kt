package com.swarmknowledge.ospnode

/**
 * Browser console served at `/` — the zero-dependency web face of the node
 * (REQ-NF-01 spirit: one hand-written HTML page, no bundler, no CDN).
 *
 * The page is static and contains no secrets: the operator pastes the node's
 * link token once, it is kept in the browser's localStorage and sent as
 * `x-api-token` — the same header any OSP peer uses. All protocol routes are
 * same-origin, so no CORS is involved.
 */
object ConsolePage {
    val html: String = """<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>osp-node console</title>
<style>
  :root { --ink:#16211c; --dim:#5f6f66; --line:#d7ddd9; --ok:#1a7f4f; --bad:#b3372f; --card:#f6f8f7; }
  * { box-sizing:border-box; }
  body { font:15px/1.5 system-ui, sans-serif; color:var(--ink); max-width:860px; margin:0 auto; padding:24px 16px 64px; }
  h1 { font-size:20px; margin:0 0 4px; } h1 code { font-size:14px; color:var(--dim); }
  .sub { color:var(--dim); font-size:13px; margin-bottom:20px; }
  section { background:var(--card); border:1px solid var(--line); border-radius:10px; padding:16px; margin-bottom:16px; }
  h2 { font-size:14px; text-transform:uppercase; letter-spacing:.06em; color:var(--dim); margin:0 0 10px; }
  label { display:block; font-size:12px; color:var(--dim); margin:10px 0 4px; }
  input, textarea, select { width:100%; font:inherit; padding:8px 10px; border:1px solid var(--line); border-radius:8px; background:#fff; }
  textarea { resize:vertical; min-height:64px; }
  button { font:inherit; padding:8px 18px; border:0; border-radius:8px; background:var(--ink); color:#fff; cursor:pointer; margin-top:10px; }
  button.sec { background:#fff; color:var(--ink); border:1px solid var(--line); }
  .row { display:flex; gap:10px; } .row > * { flex:1; }
  pre { background:#0f1713; color:#d8e5de; padding:12px; border-radius:8px; overflow:auto; font-size:12.5px; white-space:pre-wrap; }
  .mode { display:inline-block; padding:2px 10px; border-radius:999px; font-size:12px; font-weight:600; }
  .mode.RESOLVED { background:#e0f2e9; color:var(--ok); }
  .mode.BAD { background:#fbe9e7; color:var(--bad); }
  .answer { margin:10px 0; padding:12px; background:#fff; border:1px solid var(--line); border-radius:8px; }
  .meta { color:var(--dim); font-size:12.5px; }
  .err { color:var(--bad); font-size:13px; margin-top:8px; }
</style>
</head>
<body>
<h1>osp-node console</h1>
<div class="sub">Omni-Swarm Protocol v0.6 — verified negotiation from the browser</div>

<section>
  <h2>Connection</h2>
  <label>Link token (kept in this browser, sent as x-api-token)</label>
  <div class="row">
    <input id="token" type="password" placeholder="node link secret">
    <button class="sec" style="flex:0 0 auto" onclick="saveToken()">Save</button>
  </div>
  <div id="status" class="meta" style="margin-top:10px">status: —</div>
</section>

<section>
  <h2>Query (verified path — PROPOSE / BID / ALIGN / RESOLVE / verify)</h2>
  <textarea id="query" placeholder="e.g. why does hydraulic pump failure happen"></textarea>
  <label>Privacy tier</label>
  <select id="tier">
    <option value="0">T0 — public (k=1, q=1)</option>
    <option value="1" selected>T1 — personal (k=2, q=2)</option>
    <option value="2">T2 — sensitive (k=3, q=2, no remote provider)</option>
  </select>
  <button onclick="ask()">Ask the swarm</button>
  <div id="qerr" class="err"></div>
  <div id="outcome"></div>
</section>

<section>
  <h2>Teach (add a knowledge chunk)</h2>
  <textarea id="chunk" placeholder="A fact this node may answer from…"></textarea>
  <button onclick="teach()">Add chunk</button>
  <div id="terr" class="err"></div>
</section>

<section>
  <h2>Peers (remote OSP nodes)</h2>
  <div class="row">
    <input id="peerId" placeholder="node id, e.g. tablet-osp">
    <input id="peerUrl" placeholder="http://192.168.1.20:8090">
  </div>
  <label>Link secret (optional — leave empty for open dev nodes)</label>
  <input id="peerToken" type="password">
  <button onclick="addPeer()">Add / update peer</button>
  <div id="perr" class="err"></div>
  <div id="peers" class="meta" style="margin-top:10px"></div>
</section>

<script>
function tok() { return document.getElementById('token').value.trim(); }
function hdrs() { return {'Content-Type':'application/json', 'x-api-token': tok()}; }
function saveToken() { localStorage.setItem('osp_token', tok()); status(); }

function status() {
  fetch('/osp/status').then(function(r){ return r.json(); }).then(function(s) {
    document.getElementById('status').textContent =
      'node ' + s.node_id + ' (' + s.node_class + ') · provider ' + s.provider +
      ' · chunks ' + s.chunks + ' · budget left ' + s.budget_left +
      ' · peers ' + (s.peers || []).length + ' · signer ' + s.signer;
    loadPeers();
  }).catch(function(e) { document.getElementById('status').textContent = 'status: unreachable (' + e + ')'; });
}

function ask() {
  document.getElementById('qerr').textContent = '';
  var body = JSON.stringify({query: document.getElementById('query').value,
                             tier: parseInt(document.getElementById('tier').value, 10)});
  fetch('/osp/query', {method:'POST', headers: hdrs(), body: body})
    .then(function(r){ return r.json(); })
    .then(function(d) { render(d.outcome); })
    .catch(function(e) { document.getElementById('qerr').textContent = 'failed: ' + e; });
}

function render(o) {
  var cls = o.mode === 'RESOLVED' ? 'mode RESOLVED' : 'mode BAD';
  var html = '<p><span class="' + cls + '">' + o.mode + '</span>' +
    (o.groundedness != null ? ' <span class="meta">groundedness ' + o.groundedness + '</span>' : '') +
    (o.budget_left != null ? ' <span class="meta">· budget left ' + o.budget_left + '</span>' : '') + '</p>';
  if (o.answer) html += '<div class="answer">' + escapeHtml(o.answer) + '</div>';
  if (o.detail) html += '<div class="meta">' + escapeHtml(o.detail) + '</div>';
  html += '<pre>' + escapeHtml(JSON.stringify(o.trace, null, 2)) + '</pre>';
  document.getElementById('outcome').innerHTML = html;
}

function escapeHtml(s) {
  return String(s).replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;');
}

function teach() {
  document.getElementById('terr').textContent = '';
  fetch('/osp/teach', {method:'POST', headers: hdrs(),
    body: JSON.stringify({text: document.getElementById('chunk').value})})
    .then(function(r){ return r.json(); })
    .then(function(d) { document.getElementById('chunk').value = '';
      document.getElementById('terr').style.color = '#1a7f4f';
      document.getElementById('terr').textContent = 'ok — ' + d.chunks + ' chunks in store'; status(); })
    .catch(function(e) { document.getElementById('terr').textContent = 'failed: ' + e; });
}

function loadPeers() {
  fetch('/osp/peers', {headers: hdrs()}).then(function(r){ return r.json(); }).then(function(p) {
    var names = Object.keys(p);
    document.getElementById('peers').textContent =
      names.length ? names.map(function(n){ return n + ' → ' + (p[n].url || p[n]); }).join('  ·  ')
                   : 'no remote peers — this node is a swarm of one (T0)';
  }).catch(function(){ /* token not set yet */ });
}

function addPeer() {
  document.getElementById('perr').textContent = '';
  var id = document.getElementById('peerId').value.trim();
  var url = document.getElementById('peerUrl').value.trim();
  if (!id || !url) { document.getElementById('perr').textContent = 'node id and URL required'; return; }
  var entry = {url: url};
  var t = document.getElementById('peerToken').value.trim();
  if (t) entry.token = t;
  var peers = {}; peers[id] = entry;
  fetch('/osp/peers', {method:'POST', headers: hdrs(), body: JSON.stringify({peers: peers})})
    .then(function(r){ return r.json(); })
    .then(function(){ status(); })
    .catch(function(e) { document.getElementById('perr').textContent = 'failed: ' + e; });
}

document.getElementById('token').value = localStorage.getItem('osp_token') || '';
status();
</script>
</body>
</html>
"""
}
