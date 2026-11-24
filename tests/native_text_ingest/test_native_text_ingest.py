"""Tests for scripts/native_text_ingest.py and the donation page it reads."""

from __future__ import annotations

import json
import re
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts"))

import native_text_ingest as ingest  # noqa: E402

PAGE = ROOT / "research" / "text-donation" / "index.html"


def donation(donor_id: str = "d-0a1b2c3d", texts=None, **head) -> str:
    record = {"schema": "tt-text-donation/1", "kind": "donor", "donor_id": donor_id,
              "publish_ok": False, "region": "", "age_band": "", "date": "2026-11-23"}
    record.update(head)
    lines = [json.dumps(record, ensure_ascii=False)]
    for index, text in enumerate(texts or ["Сәлам, ничек хәлләр?"], start=1):
        lines.append(json.dumps({"kind": "text", "id": index, "source": "messenger",
                                 "text": text}, ensure_ascii=False))
    return "\n".join(lines) + "\n"


class IngestTest(unittest.TestCase):
    def setUp(self) -> None:
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)

    def write(self, name: str, content: str) -> Path:
        path = self.root / name
        path.write_text(content, encoding="utf-8")
        return path

    def test_a_valid_file_is_counted_and_written(self) -> None:
        path = self.write("a.jsonl", donation(texts=["Сәлам, ничек хәлләр?", "Мин өйдә <NAME> белән"]))
        out = self.root / "out"
        self.assertEqual(0, ingest.main([str(path), "--out", str(out)]))
        summary = json.loads((out / "summary.json").read_text(encoding="utf-8"))
        self.assertEqual(1, summary["donors"])
        self.assertEqual(6, summary["tokens_total"])
        self.assertEqual(100.0, summary["max_donor_share_pct"])
        self.assertTrue((out / "d-0a1b2c3d.json").is_file())

    def test_residual_personal_data_fails_without_rescrub(self) -> None:
        path = self.write("a.jsonl", donation(texts=["яз миңа test@example.com адресына"]))
        with self.assertRaises(ingest.IngestError):
            ingest.ingest([path], rescrub=False)
        donations, summary = ingest.ingest([path], rescrub=True)
        self.assertEqual(1, summary["rescrubbed_items"])
        self.assertIn("<EMAIL>", donations[0].texts[0]["text"])
        self.assertNotIn("example.com", donations[0].texts[0]["text"])

    def test_scrub_replaces_every_kind(self) -> None:
        text, count = ingest.scrub(
            "https://x.tatar/a www.y.ru @tatar_user +7 (987) 123-45-67 код 123456 и 2026")
        self.assertEqual(5, count)
        self.assertEqual("<URL> <URL> <USER> <PHONE> код <NUM> и 2026", text)

    def test_the_same_donor_twice_is_rejected(self) -> None:
        a = self.write("a.jsonl", donation())
        b = self.write("b.jsonl", donation(texts=["Икенче хәбәр бу"]))
        with self.assertRaises(ingest.IngestError):
            ingest.ingest([a, b], rescrub=False)

    def test_repeated_lines_across_donors_are_dropped(self) -> None:
        a = self.write("a.jsonl", donation("d-00000001", texts=["Хәерле иртә!"]))
        b = self.write("b.jsonl", donation("d-00000002", texts=["хәерле иртә", "Рәхмәт сиңа"]))
        donations, summary = ingest.ingest([a, b], rescrub=False)
        self.assertEqual(1, summary["duplicate_lines_dropped"])
        self.assertEqual(["Рәхмәт сиңа"], [t["text"] for t in donations[1].texts])

    def test_malformed_records_are_rejected(self) -> None:
        bad_heads = [
            donation(donor_id="user-1"),
            donation(publish_ok="yes"),
            donation(region="moscow"),
            donation(date="23.11.2026"),
        ]
        for index, content in enumerate(bad_heads):
            path = self.write(f"bad{index}.jsonl", content)
            with self.assertRaises(ingest.IngestError, msg=content):
                ingest.parse_file(path, rescrub=False)
        extra_field = donation().replace('"kind": "text"', '"kind": "text", "time": "12:00"')
        with self.assertRaises(ingest.IngestError):
            ingest.parse_file(self.write("extra.jsonl", extra_field), rescrub=False)

    def test_invalid_input_exits_2_and_writes_nothing(self) -> None:
        path = self.write("a.jsonl", "not json\n")
        out = self.root / "out"
        self.assertEqual(2, ingest.main([str(path), "--out", str(out)]))
        self.assertFalse(out.exists())


class DonationPageTest(unittest.TestCase):
    def setUp(self) -> None:
        self.source = PAGE.read_text(encoding="utf-8")

    def test_the_page_makes_no_network_requests_and_stores_nothing(self) -> None:
        for forbidden in ("fetch(", "XMLHttpRequest", "sendBeacon", "WebSocket", "localStorage",
                          "sessionStorage", "indexedDB", "<link", "src=\"http", "src='http"):
            self.assertNotIn(forbidden, self.source)
        self.assertNotIn("<script src", self.source)

    def test_the_page_scrubber_mirrors_the_ingest_patterns(self) -> None:
        block = self.source[self.source.index("var RULES = ["):]
        block = block[:block.index("];")]
        page_rules = re.findall(r"\['([A-Z]+)', /(.+?)/(g|gi)\]", block)
        self.assertEqual([name for name, _, _ in ingest.SCRUB_RULES],
                         [name for name, _, _ in page_rules])
        for (name, source, flags), (page_name, page_source, page_flags) in zip(
                ingest.SCRUB_RULES, page_rules):
            self.assertEqual(source, page_source.replace("\\/", "/"), name)
            self.assertEqual(bool(flags & re.IGNORECASE), "i" in page_flags, name)

    def test_the_export_schema_matches_the_ingest_schema(self) -> None:
        self.assertIn(f"schema: '{ingest.SCHEMA}'", self.source)
        for value in ingest.SOURCES:
            self.assertIn(f'value="{value}"', self.source)
        for value in ingest.REGIONS | ingest.AGE_BANDS:
            self.assertIn(f'value="{value}"', self.source)


if __name__ == "__main__":
    unittest.main()
