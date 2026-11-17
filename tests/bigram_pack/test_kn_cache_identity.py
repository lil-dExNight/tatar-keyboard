#!/usr/bin/env python3
"""Tests for the KN simulation's corpus-cache identity."""
from __future__ import annotations

import importlib.util
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
PATH = ROOT / "research" / "corpus" / "sim_kn_rerank.py"
SPEC = importlib.util.spec_from_file_location("sim_kn_rerank", PATH)
assert SPEC is not None and SPEC.loader is not None
sim = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = sim
SPEC.loader.exec_module(sim)


class CorpusSignatureTest(unittest.TestCase):
    def test_same_size_edit_changes_signature(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "train.txt"
            path.write_text("әни өйгә\n", encoding="utf-8")
            before = sim.corpus_signature([path])
            path.write_text("әни йорт\n", encoding="utf-8")
            self.assertEqual(path.stat().st_size, before[0][1])
            self.assertNotEqual(before, sim.corpus_signature([path]))


if __name__ == "__main__":
    unittest.main()
