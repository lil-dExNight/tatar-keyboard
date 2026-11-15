#!/usr/bin/env python3
"""Extract the independent Tatar Common Voice final evaluation set.

The set must be independent of every training and development input: candidate sentences are
normalized like the development evaluator, then checked against the conversational train
split, the Leipzig Tatar training sentences, and the committed development sets, and the
survivors are sampled deterministically and frozen before tuning.

Two acquisition paths feed the same pipeline:

  --mirror-text  (obtainable now) Read the pinned, hash-verified plain-text Tatar dev split
                 produced by research/corpus-sources/fetch_sources.py from the ungated
                 fsicoli/common_voice_17_0 mirror (CC0-1.0). The text is re-verified against
                 the corpus-common-voice.json provenance shard before use. This is the path
                 that produces the committed final set.

  --archive      (official, externally blocked) Read the validated-sentence TSV inside the
                 official Mozilla Data Collective tar.gz without unpacking clips. This path
                 requires an authenticated account that has accepted the dataset terms:
                   python3 -m pip install datacollective
                   python3 -c 'from datacollective import download_dataset; \
                     download_dataset("cmu62fysg00noo1077ljif8fv")'
                 It is blocked on that external account; the mirror path is the working one.

Output: the committed final set app/src/test/resources/tt_common_voice_final.txt (CC0 text,
redistribution permitted) and its provenance shard data/provenance.d/eval-final-common-voice.json.

Host extraction:
  python3 research/corpus-sources/fetch_sources.py   # once, populates the pinned mirror text
  python3 research/corpus/acquire_common_voice_final.py --mirror-text
"""
from __future__ import annotations

import argparse
import hashlib
import io
import json
import sys
import tarfile
from pathlib import Path
from typing import BinaryIO, Iterator, Sequence

SCRIPT_DIR = Path(__file__).resolve().parent
ROOT = SCRIPT_DIR.parents[1]
sys.path.insert(0, str(ROOT / "scripts"))
sys.path.insert(0, str(SCRIPT_DIR))

import make_eval_set  # noqa: E402
import suggest_eval  # noqa: E402

# Official archive identifiers (the --archive path).
DATASET_ID = "cmu62fysg00noo1077ljif8fv"
REVISION = "cv-corpus-27.0-2026-09-11"
RELEASE_DATE = "2026-09-17"
LICENSE = "CC0-1.0"
# Mirror identifiers (the --mirror-text path), pinned by the corpus pipeline.
MIRROR_DATASET = "fsicoli/common_voice_17_0"
MIRROR_SPLIT = "dev"
DEFAULT_LIMIT = 1_000
DEFAULT_MIN_WORDS = 3
DEFAULT_MAX_WORDS = 12
SAMPLE_SEED = "common-voice-final/2026-11-14"
DEFAULT_MIRROR_TEXT = (
    ROOT / "build" / "corpus-sources" / "text" / "common-voice" / "tt-dev.txt"
)
CORPUS_SHARD = ROOT / "data" / "provenance.d" / "corpus-common-voice.json"
OUTPUT = ROOT / "app" / "src" / "test" / "resources" / "tt_common_voice_final.txt"
SHARD_OUTPUT = ROOT / "data" / "provenance.d" / "eval-final-common-voice.json"
ALLOWED_MEMBERS = frozenset(
    ("tt/validated_sentences.tsv", "tt/validated.tsv")
)


class CommonVoiceError(ValueError):
    """A missing, malformed, or colliding Common Voice input."""


def sha256_stream(stream: BinaryIO) -> str:
    digest = hashlib.sha256()
    while block := stream.read(8 * 1024 * 1024):
        digest.update(block)
    return digest.hexdigest()


def _sha256_file(path: Path) -> tuple[int, str]:
    digest = hashlib.sha256()
    size = 0
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1 << 20), b""):
            digest.update(block)
            size += len(block)
    return size, digest.hexdigest()


def mirror_sentence_rows(text_path: Path) -> tuple[str, Iterator[dict[str, str]], str]:
    """Read the pinned plain-text mirror dev split, verifying it against the corpus shard.

    Returns (source label, row iterator, text sha256). Each row carries only ``sentence``;
    the mirror text carries no audio, clip, or speaker metadata.
    """
    if not CORPUS_SHARD.is_file():
        raise CommonVoiceError(
            f"corpus provenance shard is missing: {CORPUS_SHARD.relative_to(ROOT)}; "
            "run research/corpus-sources/fetch_sources.py and write_provenance.py first"
        )
    shard = json.loads(CORPUS_SHARD.read_text(encoding="utf-8"))
    pin = next(
        (
            item
            for item in shard.get("input_files", [])
            if item.get("split") == MIRROR_SPLIT
            and str(item.get("text_path", "")).endswith(f"tt-{MIRROR_SPLIT}.txt")
        ),
        None,
    )
    if pin is None or not pin.get("text_sha256"):
        raise CommonVoiceError(
            f"{CORPUS_SHARD.name}: no pinned tt-{MIRROR_SPLIT}.txt text entry"
        )
    if not text_path.is_file():
        raise CommonVoiceError(
            f"mirror text is missing: {text_path.relative_to(ROOT)}; "
            "run research/corpus-sources/fetch_sources.py first"
        )
    _size, text_sha256 = _sha256_file(text_path)
    if text_sha256 != pin["text_sha256"]:
        raise CommonVoiceError(
            f"{text_path.name}: drifted from the pinned Common Voice bundle "
            f"(pinned {str(pin['text_sha256'])[:12]}…, got {text_sha256[:12]}…)"
        )

    def rows() -> Iterator[dict[str, str]]:
        with text_path.open("r", encoding="utf-8", newline="") as stream:
            for raw in stream:
                line = raw.rstrip("\r\n")
                if line:
                    yield {"sentence": line, "source": "mirror-dev"}

    return f"common-voice-17.0/tt-{MIRROR_SPLIT}", rows(), text_sha256


def sentence_rows(archive: Path) -> tuple[str, Iterator[dict[str, str]], str]:
    """Read selected TSV rows from the official tar archive without extracting clips."""
    with archive.open("rb") as raw_stream:
        archive_sha256 = sha256_stream(raw_stream)
    with tarfile.open(archive, mode="r:gz") as archive_stream:
        for member in archive_stream.getmembers():
            if not member.isfile() or member.name not in ALLOWED_MEMBERS:
                continue
            source = archive_stream.extractfile(member)
            if source is None:
                continue
            records: list[dict[str, str]] = []
            text = io.TextIOWrapper(source, encoding="utf-8", newline="")
            header: list[str] | None = None
            for line_number, raw in enumerate(text, 1):
                fields = raw.rstrip("\r\n").split("\t")
                if header is None:
                    if "sentence" not in fields:
                        raise CommonVoiceError(
                            f"{member.name}:{line_number}: sentence column is missing"
                        )
                    header = fields
                    sentence_index = header.index("sentence")
                    variant_index = header.index("variant") if "variant" in header else -1
                    identifier_index = (
                        header.index("sentence_id") if "sentence_id" in header else -1
                    )
                    source_index = header.index("source") if "source" in header else -1
                    clip_index = (
                        header.index("clips_count") if "clips_count" in header else -1
                    )
                    continue
                if sentence_index >= len(fields):
                    raise CommonVoiceError(
                        f"{member.name}:{line_number}: sentence column is absent"
                    )
                records.append(
                    {
                        "sentence": fields[sentence_index],
                        "variant": fields[variant_index] if variant_index >= 0 else "",
                        "sentence_id": (
                            fields[identifier_index] if identifier_index >= 0 else ""
                        ),
                        "source": fields[source_index] if source_index >= 0 else "",
                        "clips_count": fields[clip_index] if clip_index >= 0 else "",
                    }
                )
            return member.name, iter(records), archive_sha256
    raise CommonVoiceError(
        f"archive has no {' or '.join(sorted(ALLOWED_MEMBERS))}: {archive}"
    )


def canonical(line: str) -> list[str]:
    return make_eval_set.normalize_sentence(line)


def heldout_namespaces(corpus_dir: Path, leipzig_dir: Path, workdir: Path) -> dict[str, frozenset[str]]:
    recipe = make_eval_set.run_conv_recipe(corpus_dir, workdir)
    train: set[str] = set()
    for content in recipe.train90_contents:
        words = canonical(content)
        if words:
            train.add(" ".join(words))
    return {
        "conversational_train": frozenset(train),
        "leipzig_train": make_eval_set.load_leipzig_normalized(leipzig_dir, make_eval_set.CORPUS_MANIFEST),
        "development": frozenset(suggest_eval.load_eval_lines(make_eval_set.DEFAULT_OUT)),
    }


def build_final_set(
    corpus_dir: Path,
    leipzig_dir: Path,
    workdir: Path,
    output: Path,
    limit: int,
    min_words: int,
    max_words: int,
    *,
    archive: Path | None = None,
    mirror_text: Path | None = None,
) -> dict[str, object]:
    if (archive is None) == (mirror_text is None):
        raise CommonVoiceError("exactly one of --archive or --mirror-text is required")
    if limit <= 0:
        raise CommonVoiceError("limit must be positive")
    if not 0 < min_words <= max_words:
        raise CommonVoiceError("word bounds must satisfy 0 < min <= max")

    if mirror_text is not None:
        source_label, rows, source_sha256 = mirror_sentence_rows(mirror_text)
        input_description = (
            f"Mirror {MIRROR_DATASET} tt-{MIRROR_SPLIT} text, "
            "pin-verified against corpus-common-voice.json"
        )
        archive_bytes = source_sha256_extra = None
    else:
        assert archive is not None
        if not archive.is_file():
            raise CommonVoiceError(
                "official Common Voice archive is missing; download it with an authenticated "
                "Mozilla Data Collective account and pass --archive"
            )
        source_label, rows, source_sha256 = sentence_rows(archive)
        input_description = (
            f"Official Mozilla Data Collective archive {REVISION} (dataset ID {DATASET_ID})"
        )
        archive_bytes = archive.stat().st_size
        source_sha256_extra = source_sha256

    excluded = heldout_namespaces(corpus_dir, leipzig_dir, workdir)
    seen: set[str] = set()
    candidates: list[str] = []
    stats = {
        "source_rows": 0,
        "dropped_no_surviving_tokens": 0,
        "dropped_word_count_outside_bounds": 0,
        "dropped_normalized_duplicate": 0,
        "dropped_collision": {name: 0 for name in sorted(excluded)},
    }
    source_counts: dict[str, int] = {}
    for row in rows:
        stats["source_rows"] += 1
        if row["source"]:
            source_counts[row["source"]] = source_counts.get(row["source"], 0) + 1
        words = canonical(row["sentence"])
        if not words:
            stats["dropped_no_surviving_tokens"] += 1
            continue
        if not min_words <= len(words) <= max_words:
            stats["dropped_word_count_outside_bounds"] += 1
            continue
        normalized = " ".join(words)
        if normalized in seen:
            stats["dropped_normalized_duplicate"] += 1
            continue
        collision = next(
            (name for name, names in excluded.items() if normalized in names), None
        )
        if collision is not None:
            stats["dropped_collision"][collision] += 1
            continue
        seen.add(normalized)
        candidates.append(normalized)

    if len(candidates) < limit:
        raise CommonVoiceError(
            f"independent candidate count {len(candidates)} is below required limit {limit}"
        )
    keyed = sorted(
        candidates,
        key=lambda line: hashlib.sha256(f"{SAMPLE_SEED}\t{line}".encode("utf-8")).digest(),
    )
    selected = sorted(keyed[:limit])
    header = (
        "# Tatar FINAL evaluation sentences; frozen before tuning. Do not train or tune on these.",
        f"# Source: {input_description}. License: {LICENSE}.",
        "# Extracted from sentence text only; no audio or speaker metadata is kept.",
        "# Independent of training and development: no normalized sentence appears in the",
        "# conversational train split, the Leipzig training sentences, or the development set",
        "# (enforced here at build time and by scripts/evaluation_splits.py before gates).",
        f"# Sample: sha256-seeded top {limit} of {len(candidates)} survivors, seed {SAMPLE_SEED!r}.",
        "# Generation command: research/corpus/acquire_common_voice_final.py --mirror-text",
        "",
    )
    data = ("\n".join([*header, *selected]) + "\n").encode("utf-8")
    output.parent.mkdir(parents=True, exist_ok=True)
    temporary = output.with_name(output.name + ".tmp")
    temporary.write_bytes(data)
    temporary.replace(output)
    output_sha256 = hashlib.sha256(data).hexdigest()
    stats.update(
        {
            "source": source_label,
            "source_sha256": source_sha256,
            "candidate_sentences": len(candidates),
            "sample_seed": SAMPLE_SEED,
            "limit": limit,
            "min_words": min_words,
            "max_words": max_words,
            "selected": len(selected),
            "source_rows_by_source": dict(sorted(source_counts.items())),
        }
    )
    if archive_bytes is not None:
        stats["archive_bytes"] = archive_bytes

    # Write the provenance shard for the committed final set.
    shard = {
        "id": "eval-final-common-voice-tt-v1",
        "revision": f"{MIRROR_DATASET.split('/')[1]}/tt-{MIRROR_SPLIT}/final-{limit}",
        "size_bytes": len(data),
        "sha256": output_sha256,
        "license": LICENSE,
        "source_role": "final_evaluation_sentences",
        "split_rule": (
            "final: frozen before tuning; independent of train and development "
            "(no shared normalized sentence)"
        ),
        "generation_command": (
            "python3 research/corpus-sources/fetch_sources.py && "
            "python3 research/corpus/acquire_common_voice_final.py --mirror-text"
        ),
        "path": "app/src/test/resources/tt_common_voice_final.txt",
        "sample_seed": SAMPLE_SEED,
        "candidate_count": len(candidates),
        "drop_counts": {
            key: value for key, value in stats.items() if key.startswith("dropped_")
        },
    }
    SHARD_OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    shard_tmp = SHARD_OUTPUT.with_name(SHARD_OUTPUT.name + ".tmp")
    shard_tmp.write_text(
        json.dumps(shard, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    shard_tmp.replace(SHARD_OUTPUT)

    report = {
        "dataset_id": DATASET_ID if archive is not None else MIRROR_DATASET,
        "revision": REVISION if archive is not None else f"tt-{MIRROR_SPLIT}",
        "license": LICENSE,
        "output": str(output.relative_to(ROOT)),
        "shard": str(SHARD_OUTPUT.relative_to(ROOT)),
        "output_bytes": len(data),
        "output_sha256": output_sha256,
        "split_rule": "final evaluation only; fixed before tuning",
        "stats": stats,
    }
    return report


def create_argument_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    source = parser.add_mutually_exclusive_group()
    source.add_argument(
        "--archive",
        type=Path,
        help="official Mozilla Data Collective tar.gz (requires an authenticated account)",
    )
    source.add_argument(
        "--mirror-text",
        type=Path,
        nargs="?",
        const=DEFAULT_MIRROR_TEXT,
        default=None,
        help="pinned mirror dev text from fetch_sources.py (obtainable path)",
    )
    parser.add_argument("--corpus-dir", type=Path, default=make_eval_set.DEFAULT_CORPUS_DIR)
    parser.add_argument("--leipzig-dir", type=Path, default=make_eval_set.DEFAULT_LEIPZIG_DIR)
    parser.add_argument("--workdir", type=Path, default=ROOT / "build" / "tt-final-common-voice")
    parser.add_argument("--output", type=Path, default=OUTPUT)
    parser.add_argument("--limit", type=int, default=DEFAULT_LIMIT)
    parser.add_argument("--min-words", type=int, default=DEFAULT_MIN_WORDS)
    parser.add_argument("--max-words", type=int, default=DEFAULT_MAX_WORDS)
    return parser


def main(argv: Sequence[str] | None = None) -> int:
    args = create_argument_parser().parse_args(argv)
    mirror_text = args.mirror_text
    if args.archive is None and mirror_text is None:
        mirror_text = DEFAULT_MIRROR_TEXT
    try:
        report = build_final_set(
            args.corpus_dir,
            args.leipzig_dir,
            args.workdir,
            args.output,
            args.limit,
            args.min_words,
            args.max_words,
            archive=args.archive,
            mirror_text=mirror_text,
        )
    except (CommonVoiceError, make_eval_set.EvalSetError, OSError) as error:
        print(f"error: {error}", file=sys.stderr)
        return 2
    print(json.dumps(report, ensure_ascii=False, indent=2, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
