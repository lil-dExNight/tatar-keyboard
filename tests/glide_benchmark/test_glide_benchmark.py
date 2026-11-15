#!/usr/bin/env python3

from __future__ import annotations

import argparse
import copy
import importlib.util
import json
from pathlib import Path
import sys
import tempfile
import unittest
import xml.etree.ElementTree as ElementTree

ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "scripts" / "glide-benchmark.py"
FIXTURE = ROOT / "research" / "glide-evaluation" / "fixtures" / "synthetic.jsonl"
FIXTURE_GEOMETRY = ROOT / "research" / "glide-evaluation" / "fixtures" / "reference-geometry.json"
BRIDGE = (
    ROOT / "research" / "glide-evaluation" / "kotlin" / "rkr" / "simplekeyboard" /
    "inputmethod" / "latin" / "glide" / "GlideBenchmark.kt"
)
COLLECTOR = ROOT / "research" / "glide-collector" / "index.html"


def load_module():
    spec = importlib.util.spec_from_file_location("glide_benchmark", SCRIPT)
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


benchmark = load_module()


def decoded_fixture(record, *, rank=1, stage="returned"):
    return {
        "type": "record",
        "record_index": record["record_index"],
        "normalized_prompt": record["prompt"].lower(),
        "candidates": [record["prompt"], "әллә", "сәләм", "милли"][:4] if rank == 1 else
            ["белән", "әллә", "сәләм", record["prompt"]][:4],
        "rank": rank,
        "decode_empty": rank == 0 and stage == "decoder_rejected",
        "path_too_short": False,
        "candidate_count_after_endpoint_pruning": 20,
        "fully_scored_candidate_count": 10,
        "target_indexed": stage not in ("out_of_vocabulary", "unmappable"),
        "unmappable_letters": ["f", "o"] if stage == "unmappable" else [],
        "alias_letters": ["ё"] if record["layout_id"] == "ru" and "ё" in record["prompt"] else [],
        "doubled_letter": any(a == b for a, b in zip(record["prompt"], record["prompt"][1:])),
        "vertical_transition_count": 1,
        "extra_row_letters": (
            [c for c in "әөүҗңһёъ" if c in record["prompt"]]
            if record["layout_id"] == "tt" else []
        ),
        "endpoint_start_pruned": stage == "endpoint_pruned",
        "endpoint_end_pruned": False,
        "length_pruned": stage == "length_pruned",
        "target_stage": stage,
    }


class FixtureTest(unittest.TestCase):
    def test_fixture_is_deterministic_and_covers_negative_records(self):
        records, invalid, _sources = benchmark.load_inputs([FIXTURE])
        self.assertEqual(invalid, [])
        self.assertEqual(len(records), 12)
        self.assertEqual({r["trial_kind"] for r in records[-2:]}, {"tap", "tremor"})
        self.assertFalse(records[-1]["gate_passed"])
        self.assertTrue(all(r["explicit_session_id"] and r["explicit_device_id"] for r in records))
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "synthetic.jsonl"
            benchmark.make_synthetic(argparse.Namespace(output=str(output)))
            self.assertEqual(output.read_bytes(), FIXTURE.read_bytes())

    def test_failed_and_unmappable_records_stay_in_report(self):
        records, invalid, sources = benchmark.load_inputs([FIXTURE])
        results = {}
        for record in records:
            stage = "returned"
            rank = 1
            if record.get("synthetic_variant") == "unmappable" or record["prompt"] == "foo":
                stage, rank = "unmappable", 0
            elif record.get("synthetic_variant") == "length_prune":
                stage, rank = "length_pruned", 0
            results[record["record_index"]] = decoded_fixture(record, rank=rank, stage=stage)
        report = benchmark.build_report(
            records=records, invalid=invalid, sources=sources, split="development", frozen=False,
            manifest_path=None, source_kind="synthetic", results=results, bridge_metadata={},
        )
        self.assertEqual(report["summary"]["records"], 12)
        self.assertEqual(report["summary"]["negative_control_records"], 2)
        self.assertEqual(report["summary"]["unmappable"], 1)
        self.assertEqual(report["summary"]["length_pruned"], 1)
        self.assertEqual(report["visible_top_n"], 3)
        self.assertEqual(report["engine_diagnostic_top_n"], 4)
        self.assertFalse(report["new_human_coverage"])
        self.assertEqual(report["unmappable_words"]["foo"]["missing_letters"], ["f", "o"])


class VisibleMetricTest(unittest.TestCase):
    def test_rank_four_is_not_visible_top_three(self):
        raw = json.loads(FIXTURE.read_text(encoding="utf-8").splitlines()[0])
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "one.jsonl"
            path.write_text(json.dumps(raw, ensure_ascii=False) + "\n", encoding="utf-8")
            records, invalid, sources = benchmark.load_inputs([path])
        record = records[0]
        result = decoded_fixture(record, rank=4)
        evaluated = benchmark.evaluate_records(records, {record["record_index"]: result})
        self.assertFalse(evaluated[0]["top1"])
        self.assertFalse(evaluated[0]["visible_top3"])
        self.assertTrue(evaluated[0]["engine_top4"])
        self.assertEqual(evaluated[0]["error_class"], "visible_top3_miss_engine_rank4")


class DatasetFreezeTest(unittest.TestCase):
    def test_existing_session_is_development_only(self):
        manifest = json.loads(
            (ROOT / "research" / "glide-evaluation" / "datasets" / "session-1-development.json")
            .read_text(encoding="utf-8")
        )
        self.assertTrue(manifest["splits"]["development"]["frozen"])
        self.assertFalse(manifest["splits"]["final"]["frozen"])
        self.assertEqual(manifest["splits"]["development"]["records"], 101)

    def test_final_rejects_legacy_identity_fallbacks(self):
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / "legacy.jsonl"
            source.write_text(json.dumps({
                "prompt": "сәләм", "layout_id": "tt", "path_x": [0, 1], "path_y": [0, 1],
                "dt_ms": [0, 10], "participant": "p", "gate_passed": True,
            }, ensure_ascii=False) + "\n", encoding="utf-8")
            args = argparse.Namespace(
                development_input=[], final_input=[str(source)], source_kind="recorded",
                output=str(Path(directory) / "dataset.json"),
            )
            with self.assertRaisesRegex(benchmark.BenchmarkError, "explicit session_id"):
                benchmark.freeze_dataset(args)

    def test_final_and_development_cannot_share_a_device(self):
        with tempfile.TemporaryDirectory() as directory:
            paths = []
            for name in ("dev.jsonl", "final.jsonl"):
                path = Path(directory) / name
                path.write_text(json.dumps({
                    "collector_schema": 2, "record_id": name, "prompt": "сәләм", "layout_id": "tt",
                    "path_x": [0, 1], "path_y": [0, 1], "dt_ms": [0, 10],
                    "session_id": name, "device_id": "same-device", "gate_passed": True,
                }, ensure_ascii=False) + "\n", encoding="utf-8")
                paths.append(str(path))
            args = argparse.Namespace(
                development_input=[paths[0]], final_input=[paths[1]], source_kind="recorded",
                output=str(Path(directory) / "dataset.json"),
            )
            with self.assertRaisesRegex(benchmark.BenchmarkError, "devices leak"):
                benchmark.freeze_dataset(args)


class CollectorContractTest(unittest.TestCase):
    def test_collector_exports_failure_and_split_metadata(self):
        source = COLLECTOR.read_text(encoding="utf-8")
        for field in (
            "collector_schema: 2", "device_id: state.deviceId", "session_id: state.sessionId",
            "orientation: screenOrientation()", "key_geometry: exportGeometry()",
            "trial_kind: trialKind", "speed_condition: speedCondition",
            "gate_passed: !problem", "rejection_reason: problem",
        ):
            self.assertIn(field, source)
        self.assertIn("finishTrace('pointer_cancel')", source)
        self.assertIn("finishTrace('manual_cancel')", source)
        self.assertNotIn("if (problem) {\n      setStatus(problem + ' — try again'", source)

    def test_collector_board_is_the_production_eight_key_extra_row(self):
        source = COLLECTOR.read_text(encoding="utf-8")
        self.assertIn("letterRow('әөүҗңһёъ', 12.5)", source)
        self.assertNotIn("letterRow('әөүҗңһ', 16.667)", source)
        tt = source.split("tt: {", 1)[1].split("ru: {", 1)[0]
        self.assertIn("aliases: {}", tt)
        self.assertNotIn("'ъ': 'ь'", tt)
        self.assertNotIn("'ё': 'е'", tt)
        for prompt in ("режиссёр", "режиссёрның", "вәгъдә", "мәгълүм", "тәкъдим", "тәъмин"):
            self.assertIn("'" + prompt + "'", tt)
        ru = source.split("ru: {", 1)[1]
        self.assertIn("'ё': 'е'", ru)
        self.assertIn("'ъ': 'ь'", ru)
        self.assertIn("'ө': 'о'", ru)


class ComparisonTest(unittest.TestCase):
    def test_comparison_is_paired_and_uses_visible_top_three(self):
        records, invalid, sources = benchmark.load_inputs([FIXTURE])
        results = {r["record_index"]: decoded_fixture(r, rank=1) for r in records}
        baseline = benchmark.build_report(
            records=records, invalid=invalid, sources=sources, split="development", frozen=True,
            manifest_path="fixture", source_kind="synthetic", results=results, bridge_metadata={},
        )
        candidate_results = copy.deepcopy(results)
        first = records[0]
        candidate_results[first["record_index"]] = decoded_fixture(first, rank=4)
        candidate = benchmark.build_report(
            records=records, invalid=invalid, sources=sources, split="development", frozen=True,
            manifest_path="fixture", source_kind="synthetic", results=candidate_results,
            bridge_metadata={},
        )
        with tempfile.TemporaryDirectory() as directory:
            before = Path(directory) / "before.json"
            after = Path(directory) / "after.json"
            benchmark.write_json(before, baseline)
            benchmark.write_json(after, candidate)
            comparison = benchmark.compare_reports(argparse.Namespace(
                baseline=str(before), candidate=str(after), output=None,
            ))
        self.assertEqual(comparison["paired"]["top1"]["baseline_only"], 1)
        self.assertEqual(comparison["paired"]["visible_top3"]["baseline_only"], 1)
        self.assertEqual(comparison["paired"]["engine_top4"]["baseline_only"], 0)


class ReferenceGeometryTest(unittest.TestCase):
    def test_tt_fixture_matches_production_eight_key_geometry(self):
        pack = benchmark.load_glide_pack()
        layout_dir = ROOT / "app" / "src" / "main" / "res" / "xml"
        rects = pack.read_glide_geometry(layout_dir)
        fixture = json.loads(FIXTURE_GEOMETRY.read_text(encoding="utf-8"))["tt"]
        self.assertEqual(fixture["model"], "grid-v1")
        self.assertEqual(fixture["grid_width"], 100000)
        self.assertEqual(fixture["key_radius"], pack.key_radius(rects))
        self.assertEqual(
            fixture["keys"],
            [
                {"letter": chr(r.code_point), "left": r.left, "top": r.top,
                 "right": r.right, "bottom": r.bottom, "more": []}
                for r in rects
            ],
        )
        extra = fixture["keys"][:8]
        self.assertEqual([key["letter"] for key in extra], list("әөүҗңһёъ"))
        self.assertEqual(len({key["top"] for key in extra}), 1)
        self.assertEqual(pack.read_layout_aliases(layout_dir, rects), {})

    def test_ru_fixture_matches_production_alias_geometry(self):
        pack = benchmark.load_glide_pack()
        typo = pack.typo_pack
        layout_dir = ROOT / "app" / "src" / "main" / "res" / "xml"
        ns = "{http://schemas.android.com/apk/res-auto}"
        rows = []
        for row in ElementTree.parse(layout_dir / "rows_russian.xml").getroot().findall("Row"):
            default = typo._parse_percent(row.get(ns + "keyWidth"))
            entries = []
            for child in row:
                if child.tag == "Key":
                    width_attr = child.get(ns + "keyWidth")
                    width = ("fill" if width_attr == "fillRight"
                             else typo._parse_percent(width_attr) if width_attr is not None
                             else default)
                    spec = child.get(ns + "keySpec")
                    entries.append((spec if spec is not None and len(spec) == 1 else None, width))
                elif child.tag == "include":
                    name = child.get(ns + "keyboardLayout").split("/")[-1] + ".xml"
                    entries.extend(
                        (spec, default)
                        for spec in typo._read_row_key_specs(layout_dir / name)
                    )
            rows.append(entries)
        gap, left_pad, right_pad = typo.read_keyboard_gaps(
            layout_dir.parent / "values" / typo._VALUES_CONFIG_FILE)
        geo = typo.build_device_geometry(
            rows, gap_percent=gap, left_padding_percent=left_pad,
            right_padding_percent=right_pad)
        pitch, key_height, top = pack.vertical_model()
        rects = [
            pack._Rect(g.code_point, g.left, top + g.row * pitch, g.right,
                       top + g.row * pitch + key_height)
            for g in geo
        ]
        letters = frozenset(r.code_point for r in rects)
        candidates = {}
        for name in ("rowkeys_russian1.xml", "rowkeys_russian2.xml", "rowkeys_russian3.xml"):
            for base_raw, partners_raw in typo._read_directed_pairs(layout_dir / name):
                base = typo._normalize_letter(base_raw)
                if base is None or base not in letters:
                    continue
                for partner_raw in partners_raw:
                    partner = typo._normalize_letter(partner_raw)
                    if partner is None or partner in letters:
                        continue
                    candidates.setdefault(partner, set()).add(base)
        # GlideKeyGeometry.ALIAS_BASES: the alias letters two keys carry, and their base key.
        named_bases = {0x04BB: 0x0445, 0x04D9: 0x0430}
        aliases = {}
        for alias, bases in sorted(candidates.items()):
            if len(bases) == 1:
                aliases[alias] = next(iter(bases))
            elif named_bases.get(alias) in bases:
                aliases[alias] = named_bases[alias]
        fixture = json.loads(FIXTURE_GEOMETRY.read_text(encoding="utf-8"))["ru"]
        self.assertEqual(fixture["model"], "grid-v1")
        self.assertEqual(fixture["grid_width"], 100000)
        self.assertEqual(fixture["key_radius"], pack.key_radius(rects))
        self.assertEqual(
            fixture["keys"],
            [
                {"letter": chr(r.code_point), "left": r.left, "top": r.top,
                 "right": r.right, "bottom": r.bottom,
                 "more": sorted(chr(a) for a, b in aliases.items() if b == r.code_point)}
                for r in rects
            ],
        )

    def test_missing_geometry_hashes_the_concrete_reference_geometry(self):
        records, invalid, _sources = benchmark.load_inputs([FIXTURE])
        self.assertEqual(invalid, [])
        reference = benchmark.reference_geometry("tt")
        self.assertEqual(set(reference), {"model", "grid_width", "key_radius", "keys"})
        expected_id = benchmark.fingerprint(reference)[:16]
        for record in records:
            self.assertEqual(record["geometry_key"], reference)
            self.assertEqual(record["geometry_id"], expected_id)


class GeometryMixTest(unittest.TestCase):
    def _stale_geometry(self):
        """The pre-eight-key generation: no ё/ъ keys, both letters aliased on е/ь."""
        stale = copy.deepcopy(json.loads(FIXTURE_GEOMETRY.read_text(encoding="utf-8"))["tt"])
        stale["keys"] = [
            key for key in stale["keys"] if key["letter"] not in ("ё", "ъ")
        ]
        for key in stale["keys"]:
            if key["letter"] == "е":
                key["more"] = ["ё"]
            elif key["letter"] == "ь":
                key["more"] = ["ъ"]
        return stale

    def _load(self, directory, rows):
        path = Path(directory) / "records.jsonl"
        path.write_text(
            "".join(json.dumps(row, ensure_ascii=False) + "\n" for row in rows),
            encoding="utf-8",
        )
        records, invalid, _sources = benchmark.load_inputs([path])
        self.assertEqual(invalid, [])
        return records

    def _row(self, record_id, geometry=None):
        row = {
            "collector_schema": 2, "record_id": record_id, "prompt": "сәләм",
            "layout_id": "tt", "path_x": [0, 1], "path_y": [0, 1], "dt_ms": [0, 10],
            "gate_passed": True,
        }
        if geometry is not None:
            row["key_geometry"] = geometry
        return row

    def test_reference_records_cannot_mix_with_stale_geometry(self):
        with tempfile.TemporaryDirectory() as directory:
            records = self._load(directory, [
                self._row("legacy"),
                self._row("recorded", self._stale_geometry()),
            ])
        with self.assertRaisesRegex(benchmark.BenchmarkError, "mixes records without key_geometry"):
            benchmark.check_geometry_consistency(records)

    def test_freeze_rejects_mixed_old_and_new_geometry(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "records.jsonl"
            path.write_text(
                "".join(json.dumps(row, ensure_ascii=False) + "\n" for row in [
                    self._row("legacy"),
                    self._row("recorded", self._stale_geometry()),
                ]),
                encoding="utf-8",
            )
            args = argparse.Namespace(
                development_input=[str(path)], final_input=[], source_kind="recorded",
                output=str(Path(directory) / "dataset.json"),
            )
            with self.assertRaisesRegex(benchmark.BenchmarkError, "mixes records without key_geometry"):
                benchmark.freeze_dataset(args)

    def test_recorded_reference_geometry_coexists_with_legacy_records(self):
        reference = json.loads(FIXTURE_GEOMETRY.read_text(encoding="utf-8"))["tt"]
        with tempfile.TemporaryDirectory() as directory:
            records = self._load(directory, [
                self._row("legacy"),
                self._row("recorded", reference),
            ])
        benchmark.check_geometry_consistency(records)

    def test_distinct_recorded_geometries_without_reference_records_are_legal(self):
        reference = json.loads(FIXTURE_GEOMETRY.read_text(encoding="utf-8"))["tt"]
        with tempfile.TemporaryDirectory() as directory:
            records = self._load(directory, [
                self._row("a", reference),
                self._row("b", self._stale_geometry()),
            ])
        benchmark.check_geometry_consistency(records)


class DecoderFingerprintTest(unittest.TestCase):
    def test_bridge_reachable_sources_are_fingerprinted(self):
        sources = set(benchmark.PRODUCTION_SOURCES)
        for path in (
            "app/src/main/java/rkr/simplekeyboard/inputmethod/latin/glide/GlideUtf8.kt",
            "app/src/main/java/rkr/simplekeyboard/inputmethod/latin/glide/GlideDecoder.kt",
            "app/src/main/java/rkr/simplekeyboard/inputmethod/latin/dictionary/engine/AutocorrectAdvice.kt",
            "app/src/main/java/rkr/simplekeyboard/inputmethod/latin/dictionary/storage/TdictValidator.kt",
            "app/src/main/java/rkr/simplekeyboard/inputmethod/latin/dictionary/storage/DictionaryStorageContracts.kt",
            "app/src/main/java/rkr/simplekeyboard/inputmethod/latin/dictionary/storage/StoreValidationToken.kt",
        ):
            self.assertIn(path, sources)
            self.assertTrue((ROOT / path).is_file(), path)

    def test_changed_glide_utf8_changes_the_decoder_fingerprint(self):
        records, invalid, _sources = benchmark.load_inputs([FIXTURE])
        self.assertEqual(invalid, [])
        baseline = benchmark.source_identities(records)["decoder_fingerprint"]
        original = benchmark.sha256_file

        def tampered(path):
            if Path(path).name == "GlideUtf8.kt":
                return benchmark.sha256_bytes(original(path).encode("utf-8") + b"tampered")
            return original(path)

        benchmark.sha256_file = tampered
        try:
            changed = benchmark.source_identities(records)["decoder_fingerprint"]
        finally:
            benchmark.sha256_file = original
        self.assertNotEqual(baseline, changed)


class ExtraRowClassificationTest(unittest.TestCase):
    def test_bridge_extra_row_covers_direct_io_and_hard_sign(self):
        self.assertIn('EXTRA_ROW = "әөүҗңһёъ"', BRIDGE.read_text(encoding="utf-8"))

    def test_tt_io_and_hard_sign_are_extra_row_not_alias(self):
        for prompt, stratum in (("вәгъдә", "extra_row:ъ"), ("режиссёр", "extra_row:ё")):
            raw = json.loads(FIXTURE.read_text(encoding="utf-8").splitlines()[0])
            raw["prompt"] = prompt
            with tempfile.TemporaryDirectory() as directory:
                path = Path(directory) / "one.jsonl"
                path.write_text(json.dumps(raw, ensure_ascii=False) + "\n", encoding="utf-8")
                records, invalid, _sources = benchmark.load_inputs([path])
            self.assertEqual(invalid, [])
            record = records[0]
            decoded = decoded_fixture(record, rank=1)
            evaluated = benchmark.evaluate_records(records, {record["record_index"]: decoded})
            classes = set(evaluated[0]["classes"])
            self.assertIn("extra_row", classes)
            self.assertIn(stratum, classes)
            self.assertNotIn("alias", classes)
            self.assertNotIn("alias:ъ", classes)
            self.assertNotIn("alias:ё", classes)
            self.assertNotIn("unmappable", classes)


if __name__ == "__main__":
    unittest.main()
