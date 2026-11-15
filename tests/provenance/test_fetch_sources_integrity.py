#!/usr/bin/env python3
"""Regression tests for TatSC page-cache integrity in fetch_sources.py.

A cached datasets-server page used to be trusted by offset/length alone and fetched
without a pinned revision, so an edited page could gain valid-looking provenance.
These tests pin the fix: cache entries are bound to the immutable dataset revision
plus the SHA-256 of the exact upstream response bytes, tampered pages are refetched,
pages without a verifiable binding are rejected, and provenance-worthy text is
assembled only from byte-verified pages. No live huggingface.co access: a stubbed
upstream serves fixed payload bytes and asserts the pinned revision header.
"""
from __future__ import annotations

import importlib.util
import json
import tempfile
import unittest
import urllib.error
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def load_module(name: str, relative: str):
    spec = importlib.util.spec_from_file_location(name, ROOT / relative)
    module = importlib.util.module_from_spec(spec)
    assert spec.loader is not None
    spec.loader.exec_module(module)
    return module


fetch_sources = load_module("fetch_sources", "research/corpus-sources/fetch_sources.py")

DATASET = "stub/TatSC_ASR"
REVISION = "3da29ce200d04a27c1696464f2653c982afb277f"
CONFIG = "audiobooks"
SPLIT = "dev"


def pins_for(rows: int) -> dict:
    return {
        "tatsc": {
            "dataset": DATASET,
            "revision": REVISION,
            "license": "CC-BY-4.0",
            "splits": {CONFIG: {SPLIT: rows}},
        }
    }


def upstream_payload(offset: int, length: int) -> bytes:
    """Deterministic upstream rows response for [offset, offset + length)."""
    return json.dumps(
        {
            "rows": [
                {
                    "row_idx": offset + index,
                    "row": {"text": f"упмы {offset + index}\ncypher номер"},
                }
                for index in range(length)
            ]
        },
        ensure_ascii=False,
    ).encode("utf-8")


class StubUpstream:
    """Serves fixed payload bytes and records the pinned-revision request header."""

    def __init__(self) -> None:
        self.payloads: dict[tuple[int, int], bytes] = {}
        self.requests: list[tuple[str, dict]] = []

    def handler(self, url: str, **_kwargs) -> bytes:
        if "/api/datasets/" in url:
            return json.dumps({"sha": REVISION}).encode("utf-8")
        if "/size?" in url:
            return json.dumps(
                {
                    "size": {
                        "splits": [
                            {"config": CONFIG, "split": SPLIT, "num_rows": self.num_rows}
                        ]
                    }
                }
            ).encode("utf-8")
        offset, length = self._offset_length(url)
        if (offset, length) not in self.payloads:
            raise urllib.error.URLError(f"stub upstream has no page ({offset}, {length})")
        self.requests.append((url, self.seen_headers))
        return self.payloads[(offset, length)]

    @staticmethod
    def _offset_length(url: str) -> tuple[int, int]:
        query = dict(part.split("=", 1) for part in url.split("?", 1)[1].split("&"))
        return int(query["offset"]), int(query["length"])

    def install(self, module) -> None:
        def capture(url: str, headers: dict | None = None, **kwargs) -> bytes:
            self.seen_headers = headers or {}
            return self.handler(url, **kwargs)

        module.request_bytes = capture
        module.request_json = lambda url: json.loads(capture(url).decode("utf-8"))

    def fetch_count(self) -> int:
        return len(self.requests)


class TatscPageCacheIntegrityTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        root = Path(self.temporary.name)
        build = root / "build" / "corpus-sources"
        self.originals = {
            name: getattr(fetch_sources, name)
            for name in (
                "ROOT", "BUILD", "RAW", "PAGES", "TEXT", "request_bytes", "request_json"
            )
        }
        fetch_sources.ROOT = root
        fetch_sources.BUILD = build
        fetch_sources.RAW = build / "raw"
        fetch_sources.PAGES = build / "pages"
        fetch_sources.TEXT = build / "text"
        self.upstream = StubUpstream()
        self.upstream.install(fetch_sources)
        self.pin = {"dataset": DATASET, "revision": REVISION}
        self.split_info = {"config": CONFIG, "split": SPLIT, "num_rows": 4}
        self.payload = upstream_payload(0, 4)

    def tearDown(self) -> None:
        for name, value in self.originals.items():
            setattr(fetch_sources, name, value)

    def page_path(self, offset: int = 0) -> Path:
        return fetch_sources.PAGES / "tatsc" / CONFIG / SPLIT / f"{offset:08d}.json"

    def seed_cache(self, offset: int = 0, length: int = 4) -> dict:
        self.upstream.payloads[(offset, length)] = self.payload
        path, fingerprint = fetch_sources.fetch_tatsc_page(
            self.pin, self.split_info, offset, length
        )
        self.assertTrue(path.exists())
        self.assertEqual(fingerprint, fetch_sources.sha256_bytes(self.payload))
        return json.loads(path.read_text(encoding="utf-8"))

    def test_fresh_fetch_binds_revision_and_content_hash(self) -> None:
        page = self.seed_cache()
        self.assertEqual(page["revision"], REVISION)
        self.assertEqual(page["offset"], 0)
        self.assertEqual(page["length"], 4)
        self.assertEqual(page["response_sha256"], fetch_sources.sha256_bytes(self.payload))
        self.assertEqual(
            [row["row_idx"] for row in page["rows"]], [0, 1, 2, 3]
        )
        # Whitespace normalization is unchanged by the binding.
        self.assertEqual(page["rows"][0]["text"], "упмы 0 cypher номер")
        # The request carried the pinned immutable revision.
        _url, headers = self.upstream.requests[-1]
        self.assertEqual(headers.get("X-Pinned-Revision"), REVISION)

    def test_valid_cached_page_is_reused_without_refetch(self) -> None:
        self.seed_cache()
        fetches = self.upstream.fetch_count()
        path, fingerprint = fetch_sources.cached_tatsc_page(self.pin, self.split_info, 0, 4)
        self.assertEqual(self.upstream.fetch_count(), fetches)
        self.assertEqual(path, self.page_path())
        self.assertEqual(fingerprint, fetch_sources.sha256_bytes(self.payload))

    def test_edited_page_with_preserved_offset_length_and_row_idx_is_refetched(self) -> None:
        page = self.seed_cache()
        fetches = self.upstream.fetch_count()
        # Tamper: edit text but keep offset, length, and every row_idx intact so the
        # pre-fix offset/length trust check would have accepted the page.
        tampered = dict(page)
        tampered["rows"] = [
            {"row_idx": row["row_idx"], "text": "башкарылган ялган текст"}
            for row in page["rows"]
        ]
        encoded = json.dumps(tampered, ensure_ascii=False, sort_keys=True).encode("utf-8")
        self.page_path().write_bytes(encoded + b"\n")
        path, fingerprint = fetch_sources.cached_tatsc_page(self.pin, self.split_info, 0, 4)
        self.assertEqual(self.upstream.fetch_count(), fetches + 1)
        restored = json.loads(path.read_text(encoding="utf-8"))
        self.assertEqual(fingerprint, fetch_sources.sha256_bytes(self.payload))
        self.assertEqual(restored["rows"][0]["text"], "упмы 0 cypher номер")
        # The refetch was still bound to the pinned revision.
        _url, headers = self.upstream.requests[-1]
        self.assertEqual(headers.get("X-Pinned-Revision"), REVISION)

    def test_stale_unbound_page_is_rejected_before_provenance(self) -> None:
        # Legacy on-disk shape: trusted by offset/length only, no revision/hash binding.
        legacy_rows = [
            {
                "row_idx": row_idx,
                "text": fetch_sources.clean_sentence(f"упмы {row_idx}\nтекст"),
            }
            for row_idx in range(4)
        ]
        page = {"offset": 0, "length": 4, "rows": legacy_rows}
        path = self.page_path()
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(
            json.dumps(page, ensure_ascii=False, sort_keys=True) + "\n", encoding="utf-8"
        )
        with self.assertRaisesRegex(RuntimeError, "no verifiable revision"):
            fetch_sources.cached_tatsc_page(self.pin, self.split_info, 0, 4)
        self.assertFalse(self.page_path().exists())
        # fetch_tatsc fails the same way and must not emit provenance-worthy output.
        self.upstream.num_rows = 4
        self.upstream.payloads[(0, 4)] = self.payload
        self.page_path().parent.mkdir(parents=True, exist_ok=True)
        self.page_path().write_text(
            json.dumps(page, ensure_ascii=False, sort_keys=True) + "\n", encoding="utf-8"
        )
        with self.assertRaisesRegex(RuntimeError, "no verifiable revision"):
            fetch_sources.fetch_tatsc(pins_for(4), force=False)
        self.assertFalse((fetch_sources.TEXT / "tatsc").exists())
        self.assertFalse(self.page_path().exists())

    def test_fetch_tatsc_uses_cache_without_refetch_when_unchanged(self) -> None:
        self.upstream.num_rows = 4
        self.upstream.payloads[(0, 4)] = self.payload
        first = fetch_sources.fetch_tatsc(pins_for(4), force=False)
        fetches = self.upstream.fetch_count()
        second = fetch_sources.fetch_tatsc(pins_for(4), force=False)
        self.assertEqual(self.upstream.fetch_count(), fetches)
        self.assertEqual(first[0]["text_sha256"], second[0]["text_sha256"])
        self.assertEqual(first[0]["rows"], 4)
        self.assertEqual(first[0]["revision"], REVISION)
        text_path = fetch_sources.TEXT / "tatsc" / f"tt-{CONFIG}-{SPLIT}.txt"
        self.assertEqual(
            text_path.read_text(encoding="utf-8"),
            "".join(f"упмы {index} cypher номер\n" for index in range(4)),
        )

    def test_fetch_tatsc_rejects_page_edited_between_phases(self) -> None:
        self.upstream.num_rows = 4
        self.upstream.payloads[(0, 4)] = self.payload
        original_pool = fetch_sources.concurrent.futures.ThreadPoolExecutor
        case = self

        class InlinePool:
            def __init__(self, *args, **kwargs):
                pass

            def __enter__(self):
                return self

            def __exit__(self, *exc):
                return False

            def submit(self, function, task):
                with original_pool(max_workers=1) as pool:
                    future = pool.submit(function, task)
                future.result()  # ensure the page is fetched and cached before editing
                # Assemble-time edit: keep offset/length/row_idx, corrupt the text.
                path = case.page_path()
                page = json.loads(path.read_text(encoding="utf-8"))
                page["rows"] = [
                    {"row_idx": row["row_idx"], "text": "edited after fetch"}
                    for row in page["rows"]
                ]
                path.write_bytes(
                    (json.dumps(page, ensure_ascii=False, sort_keys=True) + "\n").encode(
                        "utf-8"
                    )
                )
                return future

        fetch_sources.concurrent.futures.ThreadPoolExecutor = InlinePool
        self.addCleanup(
            setattr, fetch_sources.concurrent.futures, "ThreadPoolExecutor", original_pool
        )
        with self.assertRaisesRegex(RuntimeError, "content-hash verification"):
            fetch_sources.fetch_tatsc(pins_for(4), force=False)
        self.assertFalse((fetch_sources.TEXT / "tatsc").exists())


if __name__ == "__main__":
    unittest.main()
