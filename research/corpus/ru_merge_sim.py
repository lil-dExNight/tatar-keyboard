#!/usr/bin/env python3
"""Merge simulation for the Taiga social-media ingestion into the Russian dictionary.

The Russian round of the corpus-ingestion experiment (research/prediction-engine.md, memo
P3); the Tatar round is tt_merge_sim.py, whose ranking and coverage functions this script
reuses. Builds candidate Russian dictionaries OUTSIDE the asset tree and measures them,
without touching the shipped pipeline. Re-rank only: the Russian composition is the 1.8.4
baseline plus the accepted words (Russian has no word-form stage), and the bonus mechanism
re-weights composition words but never adds new ones, so there are no admission variants to
compare. The composition cutoff is the shipped one (100 000).

Subcommands: ``dev-sets`` (build the development token set), ``pool`` (build and cache the
pool with base frequencies), ``tune`` (development coverage of weight tuples over a cached
pool), ``build`` (write a candidate dictionary asset and its report), ``bonus-tsv`` (write
the per-word integer bonus TSV consumed by dict_accept.py pack).

The development set is a held-out residue slice of the Russian conversational stream: the
bigram table trains on the rows with id % 60 == 0 (rus_conv_thinned60), so the residue is
everything else; the slice is the id % 60 == 1 class, normalized with the Russian alphabet,
minus the pinned eval lines. The stream itself is verified during the pass: reconstructing
the id % 60 == 0 rows must reproduce the manifest-pinned thinned file byte for byte.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import sys
from pathlib import Path

SCRIPT_DIR = Path(__file__).resolve().parent
ROOT = SCRIPT_DIR.parents[1]
sys.path.insert(0, str(ROOT / "scripts"))
sys.path.insert(0, str(SCRIPT_DIR))

import dict_accept  # noqa: E402
import dictionary_coverage as coverage  # noqa: E402
import dictionary_pack as dp  # noqa: E402
import rebuild_assets  # noqa: E402
import suggest_eval  # noqa: E402
import wordform_gen  # noqa: E402
from ru_extra_freq import ru_tokens  # noqa: E402
from tt_merge_sim import (  # noqa: E402
    merged_ranking,
    parse_sources,
    parse_weights,
    read_freq_tsv,
    token_coverage,
)

RUSSIAN_TOP = 100_000
THINNED_NAME = "rus_conv_thinned60-sentences.txt"
THIN_MODULUS = 60
DEV_RESIDUE_CLASS = 1

EVAL_PATH = ROOT / "app" / "src" / "test" / "resources" / "ru_eval_sentences.txt"


# --- development set --------------------------------------------------------------------------


def build_dev_set(conv_stream: Path, thinned: Path, out_dir: Path) -> dict:
    """Write the development set as normalized sentences, one per line, code-point sorted.

    One binary pass over the full conversational stream: the id % 60 == 0 rows are hashed
    verbatim and must reproduce the manifest-pinned thinned file (this pins the stream
    itself), and the id % 60 == 1 rows are normalized, emptied lines and the pinned eval
    lines dropped, and kept as the development slice.
    """
    manifest = rebuild_assets.load_corpus_manifest(ROOT / rebuild_assets.CORPUS_MANIFEST)
    if THINNED_NAME not in manifest:
        raise SystemExit(f"corpus manifest has no pin for {THINNED_NAME}")
    expected_size, expected_sha = manifest[THINNED_NAME]
    thinned_bytes = thinned.read_bytes()
    if len(thinned_bytes) != expected_size or hashlib.sha256(thinned_bytes).hexdigest() != expected_sha:
        raise SystemExit(f"{thinned}: does not match its manifest pin")

    eval_lines = frozenset(
        suggest_eval.load_eval_lines(EVAL_PATH, coverage.RUSSIAN.alphabet)
    )

    thinned_hash = hashlib.sha256()
    thinned_rows = 0
    thinned_size = 0
    dev_class_rows = 0
    dev_lines: set[str] = set()
    dropped_empty = 0
    dropped_eval = 0
    with conv_stream.open("rb") as stream:
        for raw in stream:
            row_id, _, content = raw.partition(b"\t")
            residue = int(row_id) % THIN_MODULUS
            if residue == 0:
                thinned_hash.update(raw)
                thinned_rows += 1
                thinned_size += len(raw)
            elif residue == DEV_RESIDUE_CLASS:
                dev_class_rows += 1
                words = ru_tokens(content.decode("utf-8"))
                if not words:
                    dropped_empty += 1
                    continue
                normalized = " ".join(words)
                if normalized in eval_lines:
                    dropped_eval += 1
                    continue
                dev_lines.add(normalized)
    if thinned_rows == 0 or thinned_hash.hexdigest() != hashlib.sha256(thinned_bytes).hexdigest() \
            or thinned_size != len(thinned_bytes):
        raise SystemExit(
            f"{conv_stream}: the id % {THIN_MODULUS} == 0 rows do not reproduce {THINNED_NAME}"
        )

    out_dir.mkdir(parents=True, exist_ok=True)
    dev_path = out_dir / "dev-conv-ru.txt"
    ordered = sorted(dev_lines)
    dev_path.write_text("\n".join(ordered) + "\n", encoding="utf-8")
    report = {
        "conv_stream": str(conv_stream),
        "thinned_rows_verified": thinned_rows,
        "residue_class": DEV_RESIDUE_CLASS,
        "dev_class_rows": dev_class_rows,
        "dropped_empty": dropped_empty,
        "dropped_eval_line": dropped_eval,
        "eval_lines_excluded": len(eval_lines),
        "dev_lines": len(ordered),
        "dev_tokens": sum(len(line.split(" ")) for line in ordered),
        "output": str(dev_path),
    }
    print(json.dumps(report, ensure_ascii=False, indent=2, sort_keys=True))
    return report


# --- pool -------------------------------------------------------------------------------------


def build_pool(args) -> int:
    """Cache the composition (1.8.4 baseline plus accepted words) with base frequencies.

    The base frequency is the pipeline frequency before any bonus: written plus
    conversational, exactly what dict_accept.merged_entries computes for the composition.
    """
    shipped, _asset = dict_accept.load_baseline("rus", Path(args.baseline))
    accepted = dict_accept.read_accepted("rus")
    conv = dict_accept.read_conv_freq("rus")
    composition = sorted(set(shipped) | set(accepted))
    base = {word: shipped.get(word, 0) + conv.get(word, 0) for word in composition}

    out = Path(args.output)
    data = "".join(f"{word}\t{base[word]}\n" for word in composition)
    out.write_text(data, encoding="utf-8")
    report: dict[str, object] = {
        "pool_words": len(composition),
        "pool_base_tokens": sum(base.values()),
        "zero_base_words": sum(1 for count in base.values() if count == 0),
        "shipped_words": len(shipped),
        "accepted_words": len(accepted),
        "output": str(out),
    }
    Path(str(out) + ".report.json").write_text(
        json.dumps(report, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    print(json.dumps(report, ensure_ascii=False, indent=2, sort_keys=True))
    return 0


# --- candidate build and bonus TSV --------------------------------------------------------------

BONUS_TSV_HEAD = """\
# Bonus frequencies of Russian dictionary pool words from the Taiga social-media segment
# (the corpus-ingestion experiment of research/prediction-engine.md, memo P3).
#
# The pipeline merge stays exact integer arithmetic: the bonus of a pool word is
#   max(1, round(base + sum_s W_s * freq_s(w) * T_base / T_s)) - base
# computed over the full pool (the 1.8.4 baseline plus accepted words), where base is the
# pipeline frequency (written plus conversational), freq_s the word's count in source s,
# T_s that source's kept token mass, and T_base the pool's total base mass. Words absent
# from every source have a zero bonus and no line here.
# Sources (the CoNLL `# text =` lines counted per word with the shared normalization under
# the Russian alphabet, which drops Latin-script tokens, URLs and the DataBaseItem
# separators; the extractor is research/corpus/ru_extra_freq.py):
#   taiga_fb: Taiga social-media segment, Facebook texts (CC BY-SA 3.0)
#   taiga_tw: Taiga social-media segment, Twitter texts (CC BY-SA 3.0)
#   taiga_vk: Taiga social-media segment, VK texts (CC BY-SA 3.0)
# Source list SHA-256: {source_pins}
# Weights W_s: {weights_text}; tuned on the conv-dev development split (a held-out residue
# class of the Russian conversational stream minus the pinned eval lines), never on the
# pinned eval set.
# word<TAB>bonus
"""


def bonus_tsv(args) -> int:
    """Write the per-word integer bonus TSV consumed by dict_accept.py pack."""
    base = read_freq_tsv(Path(args.pool))
    sources = parse_sources(args.source)
    weights = parse_weights(args.weights.split(",")) if args.weights else {}
    weighted = [(name, freqs, weights.get(name, 0.0)) for name, freqs in sources.items()]
    entries, report = merged_ranking(base, weighted, len(base) + 1)
    merged = dict(entries)
    bonus = {word: freq - base[word] for word, freq in merged.items() if freq > base[word]}
    source_pins = ", ".join(
        f"{spec.partition('=')[0]} "
        f"{hashlib.sha256(Path(spec.partition('=')[2]).read_bytes()).hexdigest()}"
        for spec in args.source
    )
    weights_text = ", ".join(f"{name}={weights[name]}" for name in sorted(weights))
    lines = [BONUS_TSV_HEAD.format(source_pins=source_pins, weights_text=weights_text)]
    lines.append("word\tbonus_freq\n")
    for word in sorted(bonus):
        lines.append(f"{word}\t{bonus[word]}\n")
    data = "".join(lines).encode("utf-8")
    out = Path(args.output)
    wordform_gen.write_atomic(out, data)
    report = {
        "pool_words": len(base),
        "bonus_words": len(bonus),
        "output": str(out),
        "output_bytes": len(data),
        "output_sha256": hashlib.sha256(data).hexdigest(),
    }
    print(json.dumps(report, ensure_ascii=False, indent=2, sort_keys=True))
    return 0


def tune(args) -> int:
    base = read_freq_tsv(Path(args.pool))
    sources = parse_sources(args.source)
    dev_paths = [Path(p) for p in args.dev]
    for combo in args.weights:
        weights = parse_weights(combo.split(",")) if combo else {}
        weighted = [
            (name, freqs, weights.get(name, 0.0)) for name, freqs in sources.items()
        ]
        entries, _report = merged_ranking(base, weighted, RUSSIAN_TOP)
        row: dict[str, object] = {"weights": weights}
        for dev_path in dev_paths:
            coverage_value, tokens = token_coverage(entries, dev_path)
            row[f"coverage:{dev_path.name}"] = round(coverage_value * 100, 4)
            row[f"tokens:{dev_path.name}"] = tokens
        print(json.dumps(row, ensure_ascii=False, sort_keys=True))
    return 0


def build(args) -> int:
    base = read_freq_tsv(Path(args.pool))
    sources = parse_sources(args.source)
    weights = parse_weights(args.weights.split(",")) if args.weights else {}
    weighted = [(name, freqs, weights.get(name, 0.0)) for name, freqs in sources.items()]
    entries, report = merged_ranking(base, weighted, RUSSIAN_TOP)

    raw = dp.serialize_entries(entries, schema=dp.SCHEMA_ID_V2)
    asset = dp.compress_raw(raw)
    parsed = dp.validate_asset(asset, language=coverage.RUSSIAN)  # raises past the budgets
    out = Path(args.output)
    dp.validate_raw(raw)  # validation before any write, like the pipeline
    wordform_gen.write_atomic(out, asset)

    baseline_words = frozenset(
        word for word, _f in read_freq_tsv(Path(args.reference_tsv)).items()
    ) if args.reference_tsv else frozenset()
    words = {word for word, _f in entries}
    report.update({
        "entries": len(entries),
        "asset": str(out),
        "asset_bytes": len(asset),
        "asset_sha256": hashlib.sha256(asset).hexdigest(),
        "raw_bytes": len(raw),
        "raw_sha256": hashlib.sha256(raw).hexdigest(),
        "fits_compressed": len(asset) <= dp.MAX_COMPRESSED_BYTES_V2,
        "fits_raw": len(raw) <= dp.MAX_UNCOMPRESSED_BYTES_V2,
        "entry_count_check": parsed.entry_count,
        "entered_vs_reference": len(words - baseline_words),
        "exited_vs_reference": len(baseline_words - words),
    })
    Path(str(out) + ".report.json").write_text(
        json.dumps(report, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    print(json.dumps(report, ensure_ascii=False, indent=2, sort_keys=True))
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    sub = parser.add_subparsers(dest="command", required=True)

    dev = sub.add_parser("dev-sets", help="build the development token set")
    dev.add_argument("--conv-stream", type=Path, required=True)
    dev.add_argument("--thinned", type=Path, required=True,
                     help="the manifest-pinned rus_conv_thinned60-sentences.txt")
    dev.add_argument("--out-dir", type=Path, required=True)
    dev.set_defaults(func=lambda args: 0 if build_dev_set(
        args.conv_stream, args.thinned, args.out_dir) else 0)

    pool = sub.add_parser("pool", help="build and cache the pool with base freqs")
    pool.add_argument("--baseline", required=True,
                      help="directory with the 1.8.4 dictionary assets")
    pool.add_argument("--output", required=True)
    pool.set_defaults(func=build_pool)

    tune_parser = sub.add_parser("tune", help="development coverage of weight tuples")
    tune_parser.add_argument("--pool", required=True)
    tune_parser.add_argument("--source", action="append", required=True)
    tune_parser.add_argument("--weights", action="append", required=True,
                             help="one tuple per flag: name=value,name=value; repeat per combo")
    tune_parser.add_argument("--dev", action="append", required=True)
    tune_parser.set_defaults(func=tune)

    build_parser = sub.add_parser("build", help="write a candidate dictionary asset")
    build_parser.add_argument("--pool", required=True)
    build_parser.add_argument("--source", action="append", required=True)
    build_parser.add_argument("--weights", default="")
    build_parser.add_argument("--reference-tsv", default=None,
                              help="word<TAB>freq TSV of the committed dictionary words")
    build_parser.add_argument("--output", required=True)
    build_parser.set_defaults(func=build)

    bonus_parser = sub.add_parser(
        "bonus-tsv", help="write the per-word integer bonus TSV consumed by dict_accept pack")
    bonus_parser.add_argument("--pool", required=True)
    bonus_parser.add_argument("--source", action="append", required=True)
    bonus_parser.add_argument("--weights", default="")
    bonus_parser.add_argument("--output", required=True)
    bonus_parser.set_defaults(func=bonus_tsv)

    args = parser.parse_args()
    return args.func(args)


if __name__ == "__main__":
    raise SystemExit(main())
