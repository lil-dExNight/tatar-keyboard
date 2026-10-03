#!/usr/bin/env python3
"""Merge simulation for the extra-corpus ingestion experiment (research memo P3).

Builds candidate Tatar dictionaries OUTSIDE the asset tree and measures them, without
touching the shipped pipeline. Three composition variants, all at the shipped cutoff:

* ``rerank``: the current pool (baseline plus accepted words plus word forms admitted by the
  current evidence), re-ranked by merged frequencies;
* ``admit-forms``: the new sources also count as word-form admission evidence, so forms of
  existing stems attested only there join the pool;
* ``admit-stems``: additionally, words attested in at least two of the filtered new sources
  join the pool as new stems, and their attested generated forms join too.

Merged frequency of a pool word: ``base(w) + sum_s W_s * freq_s(w) * T_base / T_s`` where
``base`` is the pipeline frequency (asset frequency plus conversational, or the admission
count for forms), ``T_s`` the accepted token mass of source s and ``T_base`` the total base
mass of the pool. ``W_s`` is thus the source's weight in units of the whole existing
evidence mass. Weights are tuned on development coverage only, never on the pinned eval.

Subcommands: ``dev-sets`` (build the development token sets), ``pool`` (build and cache one
variant's pool with base frequencies), ``tune`` (development coverage of weight tuples over
a cached pool), ``build`` (write a candidate dictionary asset and its report).
"""
from __future__ import annotations

import argparse
import hashlib
import json
import sys
from collections import Counter
from pathlib import Path

SCRIPT_DIR = Path(__file__).resolve().parent
ROOT = SCRIPT_DIR.parents[1]
sys.path.insert(0, str(ROOT / "scripts"))
sys.path.insert(0, str(SCRIPT_DIR))

import dict_accept  # noqa: E402
import dictionary_coverage as coverage  # noqa: E402
import dictionary_pack as dp  # noqa: E402
import rebuild_assets  # noqa: E402
import wordform_gen  # noqa: E402
from make_eval_set import CONV_SENTENCES_SHA256  # noqa: E402
from tt_extra_freq import dict_tokens  # noqa: E402

TATAR_TOP = rebuild_assets.TATAR_DICTIONARY_TOP

# Fragment rule for new stems, mirroring dict_accept: a word without a vowel is a fragment.
TATAR_VOWELS = dict_accept.VOWELS["tat"]


def read_freq_tsv(path: Path) -> dict[str, int]:
    """Read a word<TAB>frequency TSV (skipping comments and the header row)."""
    out: dict[str, int] = {}
    with path.open("r", encoding="utf-8") as stream:
        for line in stream:
            if line.startswith("#"):
                continue
            fields = line.rstrip("\n").split("\t")
            if len(fields) != 2 or not fields[1].isdigit():
                continue
            out[fields[0]] = int(fields[1])
    return out


# --- development sets -------------------------------------------------------------------------


def load_eval_lines() -> frozenset[str]:
    path = ROOT / "app/src/test/resources/tt_eval_sentences.txt"
    return frozenset(
        line
        for line in path.read_text(encoding="utf-8").split("\n")
        if line and not line.startswith("#")
    )


def build_dev_sets(conv_stream: Path, tatnews_sentences: Path, out_dir: Path) -> dict:
    """Write the two development sets as normalized sentences, one per line.

    conv-dev: held-out conversational rows (id % 10 == 1) of the pinned converted stream,
    normalized, minus the pinned eval lines. tatnews-dev: every normalized sentence of the
    Leipzig tat_news_2015_1M corpus (the frozen written held-out set; used here only to
    select merge weights, never as a frequency source).
    """
    data = conv_stream.read_bytes()
    digest = hashlib.sha256(data).hexdigest()
    if digest != CONV_SENTENCES_SHA256:
        raise SystemExit(f"{conv_stream}: SHA-256 {digest} does not match the pinned stream")
    eval_lines = load_eval_lines()
    conv_lines: list[str] = []
    heldout_rows = 0
    for line in data.decode("utf-8").split("\n"):
        if not line:
            continue
        row_id, content = line.split("\t", 1)
        if int(row_id) % 10 != 1:
            continue
        heldout_rows += 1
        words = dict_tokens(content)
        if not words:
            continue
        normalized = " ".join(words)
        if normalized in eval_lines:
            continue
        conv_lines.append(normalized)
    conv_lines.sort()

    tatnews_lines: list[str] = []
    with tatnews_sentences.open("r", encoding="utf-8") as stream:
        for line in stream:
            parts = line.rstrip("\n").split("\t", 1)
            if len(parts) != 2:
                continue
            words = dict_tokens(parts[1])
            if words:
                tatnews_lines.append(" ".join(words))

    out_dir.mkdir(parents=True, exist_ok=True)
    conv_path = out_dir / "dev-conv.txt"
    tatnews_path = out_dir / "dev-tatnews.txt"
    conv_path.write_text("\n".join(conv_lines) + "\n", encoding="utf-8")
    tatnews_path.write_text("\n".join(tatnews_lines) + "\n", encoding="utf-8")
    report = {
        "conv_heldout_rows": heldout_rows,
        "conv_dev_lines": len(conv_lines),
        "conv_dev_tokens": sum(len(line.split(" ")) for line in conv_lines),
        "tatnews_dev_lines": len(tatnews_lines),
        "tatnews_dev_tokens": sum(len(line.split(" ")) for line in tatnews_lines),
        "eval_lines_excluded": len(eval_lines),
    }
    print(json.dumps(report, ensure_ascii=False, indent=2, sort_keys=True))
    return report


# --- pool construction ------------------------------------------------------------------------


def load_current_evidence(corpus_dir: Path) -> dict[str, int]:
    """Leipzig tt word counts plus conversational counts: the current admission evidence."""
    frequencies: Counter = Counter()
    for name in rebuild_assets.WORDFORM_FREQUENCY_SOURCES:
        path = corpus_dir / name
        with path.open("r", encoding="utf-8-sig", newline="") as stream:
            coverage.read_source(
                stream, str(path), frequencies,
                skip_malformed=False, alphabet=coverage.TATAR.alphabet,
            )
    conversational = dict_accept.read_conv_freq("tat")
    for word, count in conversational.items():
        frequencies[word] += count
    return dict(frequencies)


def admit_forms(
    stems: list[str],
    composition: set[str],
    exceptions,
    evidence: list[dict[str, int]],
) -> dict[str, int]:
    """Forms of the stems attested in any evidence source; mirrors the word-form stage.

    The value is the current-evidence count (the pipeline's base frequency for forms);
    evidence beyond the current sources admits but does not set the base.
    """
    admitted: dict[str, int] = {}
    current = evidence[0]
    for stem in stems:
        if not wordform_gen.harmony_variants(stem):
            continue
        for _label, form in wordform_gen.generate_all(stem, exceptions):
            if form == stem or form in composition or form in admitted:
                continue
            if any(form in source for source in evidence):
                admitted[form] = current.get(form, 0)
    return admitted


def build_pool(args) -> int:
    baseline_dir = Path(args.baseline)
    corpus_dir = Path(args.corpus_dir)
    shipped, _asset = dict_accept.load_baseline("tat", baseline_dir)
    accepted = dict_accept.read_accepted("tat")
    conv = dict_accept.read_conv_freq("tat")
    exceptions = wordform_gen.load_exceptions(ROOT / rebuild_assets.WORDFORM_EXCEPTIONS)
    current_evidence = load_current_evidence(corpus_dir)

    stems = sorted(set(shipped) | set(accepted))
    composition = set(stems)
    base: dict[str, int] = {
        word: shipped.get(word, 0) + conv.get(word, 0) for word in composition
    }

    sources: dict[str, dict[str, int]] = {}
    if args.variant in ("admit-forms", "admit-stems"):
        for spec in args.source or []:
            name, _, path = spec.partition("=")
            sources[name] = read_freq_tsv(Path(path))

    admitted = admit_forms(
        stems, composition, exceptions, [current_evidence, *sources.values()]
    )
    report: dict[str, object] = {
        "variant": args.variant,
        "stems": len(stems),
        "admitted_forms": len(admitted),
    }
    composition |= set(admitted)
    for form, count in admitted.items():
        base[form] = count

    if args.variant == "admit-stems":
        attestation: Counter = Counter()
        for source in sources.values():
            for word in source:
                attestation[word] += 1
        new_stems = sorted(
            word
            for word, hits in attestation.items()
            if hits >= 2
            and word not in composition
            and set(word) & TATAR_VOWELS
        )
        report["new_stems"] = len(new_stems)
        composition |= set(new_stems)
        for word in new_stems:
            base[word] = 0
        new_forms = admit_forms(new_stems, composition, exceptions,
                                [current_evidence, *sources.values()])
        report["new_stem_forms"] = len(new_forms)
        composition |= set(new_forms)
        for form, count in new_forms.items():
            base[form] = count

    out = Path(args.output)
    data = "".join(f"{word}\t{base[word]}\n" for word in sorted(composition))
    out.write_text(data, encoding="utf-8")
    report["pool_words"] = len(composition)
    report["pool_base_tokens"] = sum(base.values())
    report["output"] = str(out)
    Path(str(out) + ".report.json").write_text(
        json.dumps(report, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    print(json.dumps(report, ensure_ascii=False, indent=2, sort_keys=True))
    return 0


# --- ranking, coverage, candidate build --------------------------------------------------------


def merged_ranking(
    base: dict[str, int],
    sources: list[tuple[str, dict[str, int], float]],
    top: int,
) -> tuple[list[tuple[str, int]], dict[str, object]]:
    """Top entries by merged frequency; the ranking tie-break is the word itself."""
    total_base = sum(base.values())
    scaled: list[tuple[str, dict[str, int], float]] = []
    report: dict[str, object] = {"total_base": total_base, "sources": {}}
    for name, freqs, weight in sources:
        mass = sum(freqs.values())
        scale = total_base / mass if mass else 0.0
        scaled.append((name, freqs, weight * scale))
        report["sources"][name] = {"tokens": mass, "weight": weight, "scale": weight * scale}
    merged: dict[str, int] = {}
    for word, base_freq in base.items():
        value = float(base_freq)
        for _name, freqs, factor in scaled:
            value += freqs.get(word, 0) * factor
        merged[word] = max(1, round(value))
    ranked = sorted(merged.items(), key=lambda kv: (-kv[1], kv[0]))[:top]
    report["boundary_frequency"] = ranked[-1][1] if ranked else None
    return sorted(ranked, key=lambda kv: kv[0]), report


def token_coverage(entries: list[tuple[str, int]], dev_path: Path) -> tuple[float, int]:
    vocabulary = {word for word, _freq in entries}
    tokens = 0
    covered = 0
    with dev_path.open("r", encoding="utf-8") as stream:
        for line in stream:
            for word in line.split():
                tokens += 1
                if word in vocabulary:
                    covered += 1
    return (covered / tokens if tokens else 0.0), tokens


BONUS_TSV_HEAD = """\
# Bonus frequencies of Tatar dictionary pool words from the extra corpora (the corpus-ingestion
# experiment of research/prediction-engine.md, memo P3).
#
# The pipeline merge stays exact integer arithmetic: the bonus of a pool word is
#   max(1, round(base + sum_s W_s * freq_s(w) * T_base / T_s)) - base
# computed over the full pool (the 1.8.4 baseline plus accepted words plus admitted word
# forms), where base is the pipeline frequency, freq_s the word's count in the filtered
# source s, T_s that source's accepted token mass, and T_base the pool's total base mass.
# Words absent from every source have a zero bonus and no line here.
# Sources (raw set -> filtered per-word list; the Russian-bleed filter lives in
# research/corpus/tt_extra_freq.py, the HPLT text intermediate is written once from the
# parquet shards by research/corpus/hplt_dump_to_text.py with the pyarrow venv):
#   madlad: MADLAD-400 tt clean split (CC BY 4.0)
#   ttwiki: tt.wikipedia pages dump, namespace 0, minimal markup strip (CC BY-SA 4.0)
#   hplt:   HPLT 2.0 tat_Cyrl documents, prob >= 0.9 and tat_Cyrl segment share >= 0.5 (CC0)
# Filtered list SHA-256: {source_pins}
# Weights W_s: {weights_text}; tuned on the conv-dev development split (held-out
# conversational rows minus the pinned eval lines), never on the pinned eval set.
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


def parse_sources(specs: list[str]) -> dict[str, dict[str, int]]:
    sources: dict[str, dict[str, int]] = {}
    for spec in specs:
        name, _, path = spec.partition("=")
        sources[name] = read_freq_tsv(Path(path))
    return sources


def parse_weights(specs: list[str]) -> dict[str, float]:
    weights: dict[str, float] = {}
    for spec in specs:
        name, _, value = spec.partition("=")
        weights[name] = float(value)
    return weights


def tune(args) -> int:
    base = read_freq_tsv(Path(args.pool))
    sources = parse_sources(args.source)
    dev_paths = [Path(p) for p in args.dev]
    results = []
    for combo in args.weights:
        weights = parse_weights(combo.split(",")) if combo else {}
        weighted = [
            (name, freqs, weights.get(name, 0.0)) for name, freqs in sources.items()
        ]
        entries, _report = merged_ranking(base, weighted, TATAR_TOP)
        row: dict[str, object] = {"weights": weights}
        for dev_path in dev_paths:
            coverage_value, tokens = token_coverage(entries, dev_path)
            row[f"coverage:{dev_path.name}"] = round(coverage_value * 100, 4)
            row[f"tokens:{dev_path.name}"] = tokens
        results.append(row)
        print(json.dumps(row, ensure_ascii=False, sort_keys=True))
    return 0


def build(args) -> int:
    base = read_freq_tsv(Path(args.pool))
    sources = parse_sources(args.source)
    weights = parse_weights(args.weights.split(",")) if args.weights else {}
    weighted = [(name, freqs, weights.get(name, 0.0)) for name, freqs in sources.items()]
    entries, report = merged_ranking(base, weighted, TATAR_TOP)

    raw = dp.serialize_entries(entries, schema=dp.SCHEMA_ID_V2)
    asset = dp.compress_raw(raw)
    parsed = dp.validate_asset(asset, language=coverage.TATAR)  # raises past the budgets
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

    dev = sub.add_parser("dev-sets", help="build the development token sets")
    dev.add_argument("--conv-stream", type=Path, required=True)
    dev.add_argument("--tatnews-sentences", type=Path, required=True)
    dev.add_argument("--out-dir", type=Path, required=True)
    dev.set_defaults(func=lambda args: 0 if build_dev_sets(
        args.conv_stream, args.tatnews_sentences, args.out_dir) else 0)

    pool = sub.add_parser("pool", help="build and cache a variant's pool with base freqs")
    pool.add_argument("--variant", choices=("rerank", "admit-forms", "admit-stems"),
                      required=True)
    pool.add_argument("--baseline", required=True)
    pool.add_argument("--corpus-dir", required=True)
    pool.add_argument("--source", action="append",
                      help="name=path of a filtered source TSV (admission evidence)")
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
