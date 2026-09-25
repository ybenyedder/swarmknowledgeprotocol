"""Ed25519 (RFC 8032) — vendored pure-stdlib implementation.

REQ-NF-01 forbids third-party dependencies, so the production signer
(mcp.osp_core.Ed25519Signer) needs Ed25519 without cryptography/libsodium.
This module implements the standard curves/point arithmetic directly from
RFC 8032 §5.1 — extended twisted-Edwards coordinates, SHA-512 key expansion.

Correctness is pinned by the RFC 8032 test vectors (TEST 1–3, 1024-octet
message) in mcp/tests/test_protocol.py, plus the shared OSP golden vector
(byte-identical signatures with the bot's osp/core.mjs and the tablet's
osp-lite Signing.kt).

Pure-Python scalar multiplication is deliberately simple, not constant-time:
this code runs on trusted, low-traffic LAN peers, and the signing key is a
per-node identity whose compromise is recoverable by re-pinning (REQ-S-02).
"""
from __future__ import annotations

import hashlib
import hmac

_P = 2**255 - 19
_L = 2**252 + 27742317777372353535851937790883648493


def _inv(x: int) -> int:
    return pow(x, _P - 2, _P)


_D = -121665 * _inv(121666) % _P
_I = pow(2, (_P - 1) // 4, _P)


def _xrecover(y: int) -> int:
    xx = (y * y - 1) * _inv(_D * y * y + 1)
    x = pow(xx, (_P + 3) // 8, _P)
    if (x * x - xx) % _P != 0:
        x = x * _I % _P
    if x % 2 != 0:
        x = _P - x
    return x


_BY = 4 * _inv(5) % _P
_BX = _xrecover(_BY)
# extended twisted-Edwards coords (X, Y, Z, T)
_B = (_BX % _P, _BY % _P, 1, _BX * _BY % _P)
_IDENT = (0, 1, 1, 0)


def _add(p1: tuple, p2: tuple) -> tuple:
    x1, y1, z1, t1 = p1
    x2, y2, z2, t2 = p2
    a = (y1 - x1) * (y2 - x2) % _P
    b = (y1 + x1) * (y2 + x2) % _P
    c = 2 * t1 * t2 * _D % _P
    d = 2 * z1 * z2 % _P
    e, f, g, h = b - a, d - c, d + c, b + a
    return (e * f % _P, g * h % _P, f * g % _P, e * h % _P)


def _mult(point: tuple, e: int) -> tuple:
    result = _IDENT
    for bit in bin(e)[2:]:                      # MSB first, double-and-add
        result = _add(result, result)
        if bit == "1":
            result = _add(result, point)
    return result


def _compress(point: tuple) -> bytes:
    x, y, z, _ = point
    zinv = _inv(z)
    x, y = x * zinv % _P, y * zinv % _P
    return int.to_bytes(y | ((x & 1) << 255), 32, "little")


def _decompress(data: bytes) -> tuple:
    if len(data) != 32:
        raise ValueError("bad point encoding")
    val = int.from_bytes(data, "little")
    sign, y = val >> 255, val & ((1 << 255) - 1)
    x = _xrecover(y)
    if x & 1 != sign:
        x = _P - x
    return (x, y, 1, x * y % _P)


def _secret_expand(seed: bytes) -> tuple:
    digest = hashlib.sha512(seed).digest()
    a = int.from_bytes(digest[:32], "little")
    a &= (1 << 254) - 8
    a |= 1 << 254
    return a, digest[32:]


def publickey(seed: bytes) -> bytes:
    """32-byte Ed25519 public key from a 32-byte seed (RFC 8032 §5.1.5)."""
    if len(seed) != 32:
        raise ValueError("seed must be 32 bytes")
    a, _ = _secret_expand(seed)
    return _compress(_mult(_B, a))


def sign(seed: bytes, msg: bytes) -> bytes:
    """64-byte signature (R || S) over msg (RFC 8032 §5.1.6)."""
    a, prefix = _secret_expand(seed)
    public = _compress(_mult(_B, a))
    r = int.from_bytes(hashlib.sha512(prefix + msg).digest(), "little") % _L
    enc_r = _compress(_mult(_B, r))
    h = int.from_bytes(hashlib.sha512(enc_r + public + msg).digest(), "little") % _L
    s = (r + h * a) % _L
    return enc_r + int.to_bytes(s, 32, "little")


def verify(public: bytes, msg: bytes, signature: bytes) -> bool:
    """RFC 8032 §5.1.7 — False on any malformed input, never raises."""
    try:
        if len(public) != 32 or len(signature) != 64:
            return False
        point_a = _decompress(public)
        enc_r = signature[:32]
        point_r = _decompress(enc_r)
        s = int.from_bytes(signature[32:], "little")
        if s >= _L:
            return False
        h = int.from_bytes(hashlib.sha512(enc_r + public + msg).digest(), "little") % _L
        lhs = _mult(_B, s)
        rhs = _add(point_r, _mult(point_a, h))
        return hmac.compare_digest(_compress(lhs), _compress(rhs))
    except (ValueError, IndexError):
        return False
