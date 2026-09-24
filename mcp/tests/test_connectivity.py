"""Offline tests for the v0.6.1 connectivity additions (Ollama + LLMProvider reality).

Deterministic, no network (REQ-NF-03). Live probing is done by osp_ping.py.
"""
from __future__ import annotations

import os
import sys
import unittest
from pathlib import Path
from unittest import mock

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from providers import (LLMProviderFileAdapter, OllamaProvider, OpenAICompatProvider,
                       provider_from_env)


class TestOllamaProvider(unittest.TestCase):

    def test_url_normalization(self):
        """host:11434 → http:// scheme + /v1 base + native /api/tags on the same host."""
        p = OllamaProvider("192.168.1.194:11434")
        self.assertEqual(p.base_url, "http://192.168.1.194:11434/v1")
        self.assertEqual(p.tags_url, "http://192.168.1.194:11434/api/tags")
        p2 = OllamaProvider("http://192.168.1.194:11434/v1/")
        self.assertEqual(p2.base_url, "http://192.168.1.194:11434/v1")

    def test_model_fallback_without_network(self):
        """Unreachable /api/tags falls back to a default instead of crashing."""
        p = OllamaProvider("host:11434", model="")
        with mock.patch.object(p, "list_models", side_effect=OSError("no route")):
            self.assertEqual(p._resolve_model(), "fastmodel:latest")

    def test_model_selection_skips_specialists_and_big_quants(self):
        """No explicit model → /api/tags picks a general tuning ≤ 16B, smallest
        first: the name alone lies (`qwen-opti` is a coder child), so
        parent_model is checked too."""
        p = OllamaProvider("host:11434", model="")
        models = [   # the real 192.168.1.194:11434 listing, same order
            {"name": "qwen-opti:latest", "details": {
                "parameter_size": "7.6B", "parent_model": "qwen2.5-coder:7b"}},
            {"name": "qwen2.5-coder:7b", "details": {"parameter_size": "7.6B"}},
            {"name": "gemma-opti:latest", "details": {
                "parameter_size": "11.9B", "parent_model": "gemma4:12b"}},
            {"name": "gemma4:12b", "details": {"parameter_size": "11.9B"}},
            {"name": "vision:latest", "details": {
                "parameter_size": "11.9B", "parent_model": "gemma4:12b"}},
            {"name": "codeur:latest", "details": {
                "parameter_size": "14.8B", "parent_model": "qwen2.5-coder:14b"}},
            {"name": "qwen2.5-coder:14b", "details": {"parameter_size": "14.8B"}},
            {"name": "fastmodel:latest", "details": {
                "parameter_size": "7.6B", "parent_model": "qwen2.5:7b"}},
            {"name": "bestmodel:latest", "details": {
                "parameter_size": "27.3B", "parent_model": "qwen3.8:latest"}},
        ]
        with mock.patch.object(p, "list_models", return_value=models):
            self.assertEqual(p._resolve_model(), "fastmodel:latest")

    def test_explicit_model_wins_over_selection(self):
        p = OllamaProvider("host:11434", model="fastmodel:latest")
        self.assertEqual(p._resolve_model(), "fastmodel:latest")

    def test_remote_semantics(self):
        """Ollama on the LAN is an N3 (remote) provider — REQ-F-03."""
        self.assertTrue(OllamaProvider("host:11434").remote)


class TestProviderSelection(unittest.TestCase):

    def test_env_auto_detects_ollama_port(self):
        with mock.patch.dict(os.environ, {"OSP_PROVIDER_URL": "192.168.1.194:11434"}):
            self.assertIsInstance(provider_from_env(), OllamaProvider)

    def test_env_kind_overrides(self):
        with mock.patch.dict(os.environ, {"OSP_PROVIDER_URL": "http://x:8080/v1",
                                          "OSP_PROVIDER_KIND": "ollama"}):
            self.assertIsInstance(provider_from_env(), OllamaProvider)

    def test_env_plain_openai(self):
        with mock.patch.dict(os.environ, {"OSP_PROVIDER_URL": "http://x:8080/v1"},
                             clear=False):
            p = provider_from_env()
            self.assertIsInstance(p, OpenAICompatProvider)
            self.assertNotIsInstance(p, OllamaProvider)


class TestLLMProviderReality(unittest.TestCase):

    def test_discovers_real_app_directory(self):
        """The adapter finds /home/pc/sby/LLMProvider and reports the AIDL truth."""
        adapter = LLMProviderFileAdapter()
        status = adapter.status()
        self.assertTrue(any(p.exists() and p.name == "LLMProvider"
                            for p in adapter.candidate_dirs()),
                        f"existing app dir not found in {adapter.candidate_dirs()}")
        self.assertIn("LLMProvider", str(status.get("dirs_seen")))
        self.assertFalse(status["reachable"])
        self.assertIn("AIDL", status["reason"])

    def test_generate_fails_with_guidance(self):
        adapter = LLMProviderFileAdapter(provider_dir=Path("/nonexistent"))
        with self.assertRaises(RuntimeError) as ctx:
            adapter.generate("q", [])
        self.assertIn("on-device binder service", str(ctx.exception))

    def test_contract_file_activates_bridge(self):
        """When a bridge publishes osp_endpoint.json, the adapter goes live (REQ-F-01)."""
        import json
        import tempfile
        with tempfile.TemporaryDirectory() as tmp:
            Path(tmp, "osp_endpoint.json").write_text(json.dumps(
                {"base_url": "http://192.168.1.50:8090/v1", "model": "m"}))
            adapter = LLMProviderFileAdapter(provider_dir=Path(tmp))
            self.assertTrue(adapter.available())
            self.assertTrue(adapter.remote)


if __name__ == "__main__":
    unittest.main()
