#!/usr/bin/env python3
from __future__ import annotations

import bz2
import contextlib
import gzip
import hashlib
import importlib.util
import io
import json
import os
import sys
import tempfile
import types
import unittest
from pathlib import Path
from unittest import mock

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts"))


def load_module(name: str, relative: str):
    spec = importlib.util.spec_from_file_location(name, ROOT / relative)
    module = importlib.util.module_from_spec(spec)
    assert spec.loader is not None
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module


splits = load_module("evaluation_splits_isolation_test", "scripts/evaluation_splits.py")
exclusions = load_module(
    "final_evaluation_exclusions_test", "scripts/final_evaluation_exclusions.py"
)
common = load_module(
    "acquire_common_voice_final_test", "research/corpus/acquire_common_voice_final.py"
)
trigram = load_module(
    "acquire_trigram_common_voice_final_test",
    "research/corpus/acquire_trigram_common_voice_final.py",
)


class OptionalCorpusDeclarationTest(unittest.TestCase):
    def write_config(self, root: Path, sources: list[dict[str, object]]) -> Path:
        path = root / "splits.json"
        path.write_text(
            json.dumps(
                {
                    "language": "tat",
                    "evaluation_splits": ["development"],
                    "sources": sources,
                }
            ),
            encoding="utf-8",
        )
        return path

    def test_absent_optional_raw_corpus_is_not_a_clean_archive_failure(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / "dev.txt").write_text("җиде сигез тугыз\n", encoding="utf-8")
            config = self.write_config(
                root,
                [
                    {
                        "id": "dev",
                        "kind": "sentences",
                        "split": "development",
                        "path": "dev.txt",
                    },
                    {
                        "id": "raw-bigram",
                        "kind": "bigram_training",
                        "split": "train",
                        "path": "${CORPUS_DIR}/train.txt",
                        "sentence_column": 1,
                        "optional": True,
                    },
                ],
            )
            report = splits.check_splits(root, config, corpus_dir=root / "absent")
            self.assertTrue(report["passed"])
            self.assertEqual(report["optional_missing"], ["raw-bigram"])
            self.assertNotIn("train:bigram_training", report["records"])

    def test_present_optional_raw_corpus_is_checked_for_normalized_collisions(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / "dev.txt").write_text("җиде сигез тугыз\n", encoding="utf-8")
            corpus = root / "corpus"
            corpus.mkdir()
            (corpus / "train.txt").write_text("1\tҖИДЕ сигез тугыз!\n", encoding="utf-8")
            config = self.write_config(
                root,
                [
                    {
                        "id": "dev",
                        "kind": "sentences",
                        "split": "development",
                        "path": "dev.txt",
                    },
                    {
                        "id": "raw-bigram",
                        "kind": "bigram_training",
                        "split": "train",
                        "path": "${CORPUS_DIR}/train.txt",
                        "sentence_column": 1,
                        "optional": True,
                    },
                ],
            )
            with self.assertRaises(splits.SplitCollisionError) as caught:
                splits.check_splits(root, config, corpus_dir=corpus)
            self.assertEqual(caught.exception.report["collision_count"], 1)
            self.assertEqual(
                caught.exception.report["collisions"][0]["normalized"],
                "җиде сигез тугыз",
            )

    def test_committed_evaluation_artifact_cannot_be_optional(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / "dev.txt").write_text("җиде сигез тугыз\n", encoding="utf-8")
            config = self.write_config(
                root,
                [
                    {
                        "id": "dev",
                        "kind": "sentences",
                        "split": "development",
                        "path": "dev.txt",
                    },
                    {
                        "id": "final",
                        "kind": "sentences",
                        "split": "final",
                        "path": "final.txt",
                        "optional": True,
                    },
                ],
            )
            with self.assertRaisesRegex(splits.SplitConfigError, "committed artifact"):
                splits.check_splits(root, config, corpus_dir=root / "absent")

    def test_required_coverage_does_not_weaken_development_final_leak_rule(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / "dev.txt").write_text("бер ике өч\n", encoding="utf-8")
            (root / "final.txt").write_text("дүрт биш алты\n", encoding="utf-8")
            corpus = root / "corpus"
            corpus.mkdir()
            (corpus / "train.txt").write_text("1\tДҮРТ биш алты!\n", encoding="utf-8")
            config = self.write_config(
                root,
                [
                    {
                        "id": "dev",
                        "kind": "sentences",
                        "split": "development",
                        "path": "dev.txt",
                    },
                    {
                        "id": "final",
                        "kind": "sentences",
                        "split": "final",
                        "path": "final.txt",
                    },
                    {
                        "id": "raw-bigram",
                        "kind": "bigram_training",
                        "split": "train",
                        "path": "${CORPUS_DIR}/train.txt",
                        "sentence_column": 1,
                        "optional": True,
                    },
                ],
            )
            with self.assertRaises(splits.SplitCollisionError) as caught:
                splits.check_splits(root, config, corpus_dir=corpus)
            self.assertEqual(caught.exception.report["collision_count"], 1)

    def test_repeated_evaluation_targets_do_not_fake_word_table_collisions(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / "dev.txt").write_text("җиде сигез тугыз\n", encoding="utf-8")
            (root / "typos.tsv").write_text("бер\tdel\tбр\nбер\tins\tббер\n", encoding="utf-8")
            config = self.write_config(
                root,
                [
                    {
                        "id": "dev",
                        "kind": "sentences",
                        "split": "development",
                        "path": "dev.txt",
                    },
                    {
                        "id": "typo-targets",
                        "kind": "evaluation_words",
                        "split": "development",
                        "path": "typos.tsv",
                    },
                ],
            )
            report = splits.check_splits(root, config, corpus_dir=root / "absent")
            self.assertEqual(report["records"]["development:evaluation_words"], 2)
            self.assertEqual(report["collision_count"], 0)


class PrivateDeclarationTest(unittest.TestCase):
    def config(self, root: Path, private_text: bytes) -> Path:
        lines = private_text.split(b"\n")
        if lines and lines[-1] == b"":
            lines.pop()
        private = {
            "id": "native-final",
            "kind": "sentences",
            "split": "final",
            "path": "native/final.txt",
            "private": True,
            "bytes": len(private_text),
            "lines": len(lines),
            "line_sha256": [
                hashlib.sha256(line.removesuffix(b"\r")).hexdigest() for line in lines
            ],
        }
        path = root / "splits.json"
        path.write_text(
            json.dumps(
                {
                    "language": "tat",
                    "evaluation_splits": ["development"],
                    "sources": [
                        {
                            "id": "dev",
                            "kind": "sentences",
                            "split": "development",
                            "path": "dev.txt",
                        },
                        {
                            "id": "train",
                            "kind": "bigram_training",
                            "split": "train",
                            "path": "train.txt",
                        },
                        private,
                    ],
                }
            ),
            encoding="utf-8",
        )
        (root / "dev.txt").write_text("бер ике өч\n", encoding="utf-8")
        (root / "train.txt").write_text("дүрт биш алты\n", encoding="utf-8")
        return path

    def test_absent_private_file_passes_and_reports_skip(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            config = self.config(root, "җиде сигез тугыз\n".encode())
            document = json.loads(config.read_text(encoding="utf-8"))
            document["evaluation_splits"] = ["development", "final"]
            config.write_text(json.dumps(document), encoding="utf-8")
            report = splits.check_splits(root, config, private_dir=root / "absent")
            self.assertTrue(report["passed"])
            self.assertEqual(report["private_skipped"], ["native-final"])
            self.assertEqual(report["private_checked"], [])
            self.assertNotIn("final:sentences", report["records"])

    def test_present_private_file_is_verified_and_checked(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            text = "җиде сигез тугыз\n".encode()
            config = self.config(root, text)
            private = root / "private" / "native"
            private.mkdir(parents=True)
            (private / "final.txt").write_bytes(text)
            report = splits.check_splits(root, config, private_dir=root / "private")
            self.assertTrue(report["passed"])
            self.assertEqual(report["private_checked"], ["native-final"])
            self.assertEqual(report["private_skipped"], [])
            self.assertEqual(report["records"]["final:sentences"], 1)

    def test_present_private_file_must_match_declared_hashes(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            config = self.config(root, "җиде сигез тугыз\n".encode())
            private = root / "private" / "native"
            private.mkdir(parents=True)
            changed = "җиде сигез ун\n".encode()
            (private / "final.txt").write_bytes(changed)
            document = json.loads(config.read_text(encoding="utf-8"))
            document["sources"][-1]["bytes"] = len(changed)
            config.write_text(json.dumps(document), encoding="utf-8")
            with self.assertRaisesRegex(splits.SplitConfigError, "line 1.*hash"):
                splits.check_splits(root, config, private_dir=root / "private")

    def test_cli_private_argument_overrides_environment(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            text = "җиде сигез тугыз\n".encode()
            config = self.config(root, text)
            private = root / "private" / "native"
            private.mkdir(parents=True)
            (private / "final.txt").write_bytes(text)
            output = io.StringIO()
            with mock.patch.dict(
                os.environ, {splits.PRIVATE_DIR_ENV: str(root / "absent")}
            ), contextlib.redirect_stdout(output):
                code = splits.main(
                    [str(config), "--root", str(root), "--private-dir", str(root / "private")]
                )
            self.assertEqual(0, code)
            report = json.loads(output.getvalue())
            self.assertEqual(report["private_checked"], ["native-final"])

    def test_private_collision_with_training_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            text = "ДҮРТ биш алты!\n".encode()
            config = self.config(root, text)
            private = root / "private" / "native"
            private.mkdir(parents=True)
            (private / "final.txt").write_bytes(text)
            with self.assertRaises(splits.SplitCollisionError) as caught:
                splits.check_splits(root, config, private_dir=root / "private")
            self.assertEqual(caught.exception.report["collision_count"], 1)
            self.assertEqual(
                caught.exception.report["collisions"][0]["normalized"],
                "дүрт биш алты",
            )


class LiveDeclarationTest(unittest.TestCase):
    def test_clean_offline_tatar_and_russian_declarations_pass(self) -> None:
        with tempfile.TemporaryDirectory() as empty_corpus:
            for language, optional_count in (("tt", 3), ("ru", 6)):
                with self.subTest(language=language):
                    config = ROOT / "data" / "evaluation" / f"splits-{language}.json"
                    report = splits.check_splits(ROOT, config, corpus_dir=Path(empty_corpus))
                    self.assertTrue(report["passed"])
                    self.assertEqual(report["collision_count"], 0)
                    self.assertEqual(len(report["optional_missing"]), optional_count)
                    self.assertGreater(report["records"]["development:sentences"], 0)
                    self.assertGreater(report["records"]["train:frequencies"], 0)
            tatar = splits.check_splits(
                ROOT, ROOT / "data/evaluation/splits-tt.json", corpus_dir=Path(empty_corpus)
            )
            self.assertGreater(tatar["records"]["final:sentences"], 0)

    def test_shared_inventory_retains_all_current_and_new_exclusion_classes(self) -> None:
        sources = exclusions.load_inventory()
        expected = {
            "leipzig-tat-mixed-2015",
            "leipzig-tat-web-2018",
            "conversational-train90",
            "conversational-full-stream",
            "historical-news-development",
            "development-tatoeba",
            "development-russian-mix",
            "final-common-voice",
            "final-trigram",
            "common-voice-train",
            "common-voice-dev",
            "common-voice-test",
            "tatsc-audiobooks-train",
            "tatsc-audiobooks-dev",
            "tatsc-audiobooks-test",
            "tatsc-crowdsourced-tg-train",
            "tatsc-crowdsourced-web-train",
            "tatsc-crowdsourced-web-dev",
            "tatsc-crowdsourced-web-test",
            "extra-tatar-qed",
            "extra-tatar-ted2020",
            "extra-tatar-tatoeba-snapshot",
        }
        self.assertEqual(expected, {source.identifier for source in sources})
        self.assertEqual(
            "recipe_provided",
            next(s for s in sources if s.identifier == "conversational-full-stream").format,
        )

    def test_every_shipped_bigram_input_has_an_optional_declaration(self) -> None:
        import rebuild_assets

        for language, tag in (("tt", "tat"), ("ru", "rus")):
            document = json.loads(
                (ROOT / "data/evaluation" / f"splits-{language}.json").read_text(encoding="utf-8")
            )
            declared = {
                source["path"].removeprefix("${CORPUS_DIR}/")
                for source in document["sources"]
                if source["kind"] == "bigram_training" and source.get("optional") is True
            }
            expected = {
                name
                for bigram in rebuild_assets.BIGRAMS
                if bigram.tag == tag
                for name in bigram.train
            }
            self.assertEqual(declared, expected)


class SharedFinalExclusionTest(unittest.TestCase):
    def setUp(self) -> None:
        self.recipe_rows = (
            (1, "ТҮГЕЛ сүзләре"),
            (2, "бер ике өч"),
        )
        self.provided = {
            "conversational-full-stream": frozenset(("түгел сүзләре",))
        }

    def pin(self, path: Path) -> dict[str, object]:
        digest = hashlib.sha256()
        size = 0
        with path.open("rb") as stream:
            for block in iter(lambda: stream.read(1 << 20), b""):
                digest.update(block)
                size += len(block)
        return {"size_bytes": size, "sha256": digest.hexdigest()}

    def make_inventory(self, root: Path) -> tuple[Path, Path]:
        corpus = root / "corpus"
        corpus.mkdir(parents=True)
        (root / "data").mkdir(parents=True, exist_ok=True)
        (root / "data/corpus-manifest.json").write_text("{}", encoding="utf-8")
        (root / "data/provenance.d").mkdir(parents=True, exist_ok=True)
        (root / "data/provenance.d/eval-trigram-common-voice-final.json").write_text(
            "{}", encoding="utf-8"
        )
        file_sources = [
            ("historical-news-development", "${CORPUS_DIR}/news.txt", "text", 1, "1\tисторик сүзләре\n"),
            ("final-common-voice", "app/src/test/resources/tt_common_voice_final.txt", "text", 0, "беренче финал сүзләре\n"),
            ("final-trigram", "app/src/test/resources/tt_trigram_common_voice_final.txt", "text", 0, "икенче финал сүзләре\n"),
            ("common-voice-dev", "build/corpus-sources/text/common-voice/tt-dev.txt", "text", 0, "үсеш сүзләре\n"),
            ("common-voice-test", "build/corpus-sources/text/common-voice/tt-test.txt", "text", 0, "test сүзләре\n"),
            ("common-voice-train", "build/corpus-sources/text/common-voice/tt-train.txt", "text", 0, "train сүзләре\n"),
            ("tatsc-test", "build/corpus-sources/text/tatsc/tt-test.txt", "text", 0, "tatsc сүзләре\n"),
            ("extra-tatar-tatoeba-snapshot", "research/corpus/tat_sentences.tsv.bz2", "tatoeba_tsv_bz2", None, None),
            ("extra-tatar-gzip", "research/corpus/QED.tt.txt.gz", "gzip_text", 0, None),
        ]
        rows: list[dict[str, object]] = []
        for identifier, configured_path, source_format, column, text in file_sources:
            destination = (
                corpus / Path(configured_path).name
                if configured_path.startswith("${CORPUS_DIR}/")
                else root / configured_path
            )
            destination.parent.mkdir(parents=True, exist_ok=True)
            if source_format == "tatoeba_tsv_bz2":
                destination.write_bytes(
                    bz2.compress("42\ttat\tҖИДЕ сигез тугыз!\n".encode("utf-8"))
                )
            elif source_format == "gzip_text":
                destination.write_bytes(
                    gzip.compress("QED СҮЗЛӘРЕ\n".encode("utf-8"))
                )
            else:
                assert text is not None and column is not None
                destination.write_text(text, encoding="utf-8")
            row: dict[str, object] = {
                "id": identifier,
                "path": configured_path,
                "format": source_format,
                "pin": self.pin(destination),
            }
            if column is not None:
                row["sentence_column"] = column
            rows.append(row)
        rows.append({"id": "conversational-full-stream", "format": "recipe_provided"})
        inventory = root / "inventory.json"
        inventory.write_text(
            json.dumps({"schema": 1, "language": "tat", "sources": rows}, indent=2), encoding="utf-8"
        )
        return inventory, corpus

    def test_loader_requires_inputs_normalizes_sources_and_verifies_pins(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            inventory, corpus = self.make_inventory(root)
            namespaces = exclusions.load_exclusion_namespaces(
                root,
                corpus,
                inventory_path=inventory,
                provided_namespaces=self.provided,
            )
            self.assertEqual(namespaces["extra-tatar-tatoeba-snapshot"], frozenset(("җиде сигез тугыз",)))
            self.assertEqual(namespaces["extra-tatar-gzip"], frozenset(("сүзләре",)))
            self.assertEqual(
                namespaces["conversational-full-stream"], frozenset(("түгел сүзләре",))
            )
            with self.assertRaisesRegex(exclusions.FinalExclusionError, "unknown exclusion"):
                exclusions.load_exclusion_namespaces(
                    root,
                    corpus,
                    inventory_path=inventory,
                    provided_namespaces=self.provided,
                    skip_ids=frozenset(("typo",)),
                )

            snapshot = root / "research/corpus/tat_sentences.tsv.bz2"
            original = snapshot.read_bytes()
            snapshot.write_bytes(
                bz2.compress("42\ttat\tДРИФТЕН\n".encode("utf-8"))
            )
            with self.assertRaisesRegex(exclusions.FinalExclusionError, "differs from its"):
                exclusions.load_exclusion_namespaces(
                    root, corpus, inventory_path=inventory, provided_namespaces=self.provided
                )
            snapshot.write_bytes(original)
            (root / "build/corpus-sources/text/tatsc/tt-test.txt").unlink()
            with self.assertRaisesRegex(exclusions.FinalExclusionError, "required final-set"):
                exclusions.load_exclusion_namespaces(
                    root, corpus, inventory_path=inventory, provided_namespaces=self.provided
                )

    def test_recipe_provided_source_cannot_be_silently_replaced(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            inventory, corpus = self.make_inventory(root)
            with self.assertRaisesRegex(exclusions.FinalExclusionError, "verified conversational"):
                exclusions.load_exclusion_namespaces(root, corpus, inventory_path=inventory)

    def test_both_builders_apply_the_same_inventory_except_their_own_inputs(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            inventory, corpus = self.make_inventory(root)
            recipe = types.SimpleNamespace(
                rows=self.recipe_rows, train90_contents=frozenset(("ТҮГЕЛ сүзләре",))
            )
            with (
                mock.patch.object(common, "ROOT", root),
                mock.patch.object(trigram, "ROOT", root),
                mock.patch.object(common.make_eval_set, "run_conv_recipe", return_value=recipe),
                mock.patch.object(
                    common.make_eval_set, "load_leipzig_normalized", return_value=frozenset()
                ),
                mock.patch.object(common.suggest_eval, "load_eval_lines", return_value=[]),
            ):
                common_names = common.heldout_namespaces(
                    root,
                    corpus,
                    root / "work",
                    mirror_dev_source=True,
                    inventory_path=inventory,
                )
                trigram_names = trigram.heldout_namespaces(
                    corpus,
                    conversation_corpus_dir=root,
                    workdir=root / "trigram-work",
                    inventory_path=inventory,
                )
            self.assertNotIn("final-common-voice", common_names)
            self.assertIn("final-trigram", common_names)
            self.assertNotIn("common-voice-dev", common_names)
            self.assertIn("common-voice-test", common_names)
            self.assertIn("final-common-voice", trigram_names)
            self.assertNotIn("final-trigram", trigram_names)
            self.assertIn("common-voice-dev", trigram_names)
            self.assertNotIn("common-voice-test", trigram_names)
            for identifier in (
                "historical-news-development",
                "common-voice-train",
                "tatsc-test",
                "extra-tatar-gzip",
                "extra-tatar-tatoeba-snapshot",
                "conversational-full-stream",
            ):
                self.assertEqual(common_names[identifier], trigram_names[identifier])

    def test_common_voice_skip_ids_follow_the_actual_source(self) -> None:
        self.assertEqual(
            frozenset(("final-common-voice",)),
            common.common_voice_skip_ids(mirror_dev_source=False),
        )
        self.assertEqual(
            frozenset(("final-common-voice", "common-voice-dev")),
            common.common_voice_skip_ids(mirror_dev_source=True),
        )

    def test_common_source_skip_follows_actual_input_identity(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            inventory, corpus = self.make_inventory(root)
            recipe = types.SimpleNamespace(
                rows=self.recipe_rows, train90_contents=frozenset()
            )
            with (
                mock.patch.object(common, "ROOT", root),
                mock.patch.object(common.make_eval_set, "run_conv_recipe", return_value=recipe),
                mock.patch.object(
                    common.make_eval_set, "load_leipzig_normalized", return_value=frozenset()
                ),
                mock.patch.object(common.suggest_eval, "load_eval_lines", return_value=[]),
            ):
                mirror = common.heldout_namespaces(
                    root, corpus, root / "work", mirror_dev_source=True, inventory_path=inventory
                )
                archive = common.heldout_namespaces(
                    root, corpus, root / "work", mirror_dev_source=False, inventory_path=inventory
                )
            self.assertNotIn("common-voice-dev", mirror)
            self.assertIn("common-voice-dev", archive)
            self.assertIn("common-voice-test", archive)
            self.assertIn(
                "үсеш сүзләре",
                archive["common-voice-dev"],
            )


    def test_official_archive_rows_collide_with_common_voice_source_splits(self) -> None:
        excluded = {
            "common-voice-dev": frozenset(("бер сүзләре түгел",)),
            "common-voice-test": frozenset(("ике сүзләре түгел",)),
            "common-voice-train": frozenset(("өч сүзләре түгел",)),
            "independent": frozenset(),
        }
        rows = [
            {"sentence": "БЕР сүзләре түгел!", "source": "dev"},
            {"sentence": "ИКЕ сүзләре түгел!", "source": "test"},
            {"sentence": "ӨЧ сүзләре түгел!", "source": "train"},
            {"sentence": "дүрт сүзләре түгел", "source": "validated"},
        ]
        candidates, stats, _counts = common.collect_candidates(
            rows, excluded, min_words=3, max_words=12
        )
        self.assertEqual(["дүрт сүзләре түгел"], candidates)
        self.assertEqual(
            stats["dropped_collision"],
            {
                "common-voice-dev": 1,
                "common-voice-test": 1,
                "common-voice-train": 1,
                "independent": 0,
            },
        )


if __name__ == "__main__":
    unittest.main()
