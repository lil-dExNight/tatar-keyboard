#!/usr/bin/env python3
"""Build the frozen independent Tatar Common Voice final evaluation set."""
from __future__ import annotations

import bz2
import hashlib
import json
import os
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts"))

import make_eval_set  # noqa: E402

SOURCE = ROOT / "build" / "corpus-sources" / "text" / "common-voice" / "tt-test.txt"
SOURCE_SHARD = ROOT / "data" / "provenance.d" / "corpus-common-voice.json"
OUTPUT = ROOT / "app" / "src" / "test" / "resources" / "tt_trigram_common_voice_final.txt"
PROVENANCE_OUTPUT = (
    ROOT / "data" / "provenance.d" / "eval-trigram-common-voice-final.json"
)
CORPUS_DIR = Path.home() / "corpora-leipzig"
NEWS_DEVELOPMENT = CORPUS_DIR / "tat_news_2015_1M-sentences.txt"
SEED = "trigram-independent-final/2026-11-18"
LIMIT = 1_000
MIN_WORDS = 3
MAX_WORDS = 12
LICENSE = "CC0-1.0"
REVISION = "8262c16bf297c87a9cd88c51997c4758ed7a8ba2"
GENERATION_COMMAND = (
    "python3 research/corpus-sources/fetch_sources.py --sources common_voice && "
    "python3 research/corpus/acquire_trigram_common_voice_final.py"
)

EXCLUSIONS = (
    (CORPUS_DIR / "tat_mixed_2015_1M-sentences.txt", 1, "conversational_or_leipzig_train"),
    (CORPUS_DIR / "tat_web_2018_1M-sentences.txt", 1, "conversational_or_leipzig_train"),
    (NEWS_DEVELOPMENT, 1, "historical_development"),
    # The full stream also excludes held-out rows used for earlier bonus-weight tuning.
    (CORPUS_DIR / "tt_conv_all-sentences.txt", 1, "conversational_or_leipzig_train"),
    (ROOT / "app/src/test/resources/tt_eval_sentences.txt", 0, "current_development"),
    (ROOT / "app/src/test/resources/tt_ru_mix_sentences.txt", 0, "current_development"),
    (ROOT / "app/src/test/resources/tt_common_voice_final.txt", 0, "current_final"),
    (
        ROOT / "build/corpus-sources/text/common-voice/tt-train.txt",
        0,
        "common_voice_other_split",
    ),
    (
        ROOT / "build/corpus-sources/text/common-voice/tt-dev.txt",
        0,
        "common_voice_other_split",
    ),
)


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1 << 20), b""):
            digest.update(block)
    return digest.hexdigest()


def canonical(raw: str) -> str:
    return " ".join(make_eval_set.normalize_sentence(raw))


def write_atomic(path: Path, data: bytes) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(path.name + ".tmp")
    temporary.write_bytes(data)
    os.replace(temporary, path)


def main() -> int:
    shard = json.loads(SOURCE_SHARD.read_text(encoding="utf-8"))
    pin = next(
        item
        for item in shard["input_files"]
        if item["split"] == "test" and item["text_path"].endswith("tt-test.txt")
    )
    source_sha256 = sha256(SOURCE)
    if source_sha256 != pin["text_sha256"] or SOURCE.stat().st_size != pin["text_size_bytes"]:
        raise ValueError("Common Voice test text does not match its provenance pin")

    candidates: set[str] = set()
    source_rows = 0
    outside_word_bounds = 0
    for raw in SOURCE.read_text(encoding="utf-8").splitlines():
        if not raw:
            continue
        source_rows += 1
        line = canonical(raw)
        if not MIN_WORDS <= len(line.split(" ")) <= MAX_WORDS:
            outside_word_bounds += 1
            continue
        candidates.add(line)
    before_exclusions = len(candidates)
    collision_counts: dict[str, int] = {}

    def exclude(line: str, owner: str) -> None:
        if line in candidates:
            candidates.remove(line)
            collision_counts[owner] = collision_counts.get(owner, 0) + 1

    for path, column, owner in EXCLUSIONS:
        with path.open("r", encoding="utf-8") as stream:
            for raw in stream:
                raw = raw.rstrip("\r\n")
                if not raw or raw.startswith("#"):
                    continue
                fields = raw.split("\t")
                if column >= len(fields):
                    continue
                line = canonical(fields[column])
                if line:
                    exclude(line, owner)

    # This snapshot feeds extra-corpus frequency experiments and is also held out.
    tatoeba_snapshot = ROOT / "research" / "corpus" / "tat_sentences.tsv.bz2"
    with bz2.open(tatoeba_snapshot, "rt", encoding="utf-8") as stream:
        for raw in stream:
            fields = raw.rstrip("\r\n").split("\t")
            if len(fields) >= 3 and fields[1] == "tat":
                line = canonical(fields[2])
                if line:
                    exclude(line, "extra_corpus_tuning")

    if len(candidates) < LIMIT:
        raise ValueError(f"only {len(candidates)} independent candidates remain")
    selected = sorted(
        candidates,
        key=lambda line: hashlib.sha256(f"{SEED}\t{line}".encode("utf-8")).digest(),
    )[:LIMIT]
    selected.sort()
    header = (
        "# Tatar independent final evaluation sentences for the trigram experiment.",
        "# Frozen before trigram configuration tuning; never used for training or selection.",
        "# Source: Mozilla Common Voice Corpus 17.0 Tatar test transcript, mirror "
        "fsicoli/common_voice_17_0.",
        f"# Source revision: {REVISION}; license: {LICENSE}; text only, no audio or speakers.",
        "# Normalized sentences are disjoint from all current train, development and final "
        "sources and from the other Common Voice splits.",
        f"# Sample: SHA-256 ordered top {LIMIT} of {len(candidates)} survivors; seed {SEED!r}.",
        "# Generation: python3 research/corpus/acquire_trigram_common_voice_final.py",
        "",
    )
    data = ("\n".join((*header, *selected)) + "\n").encode("utf-8")
    provenance = {
        "id": "eval-trigram-independent-final-tt-v1",
        "revision": f"common_voice_17_0/tt-test/trigram-{LIMIT}",
        "source_revision": REVISION,
        "source_size_bytes": pin["text_size_bytes"],
        "source_sha256": source_sha256,
        "historical_development_exclusion": {
            "path": NEWS_DEVELOPMENT.name,
            "size_bytes": NEWS_DEVELOPMENT.stat().st_size,
            "sha256": sha256(NEWS_DEVELOPMENT),
        },
        "size_bytes": len(data),
        "sha256": hashlib.sha256(data).hexdigest(),
        "license": LICENSE,
        "source_role": "final_evaluation_sentences",
        "split_rule": (
            "final: frozen before trigram tuning; no normalized sentence shared with current "
            "or historical train, development or final inputs"
        ),
        "generation_command": GENERATION_COMMAND,
        "path": OUTPUT.relative_to(ROOT).as_posix(),
        "sample_seed": SEED,
        "source_rows": source_rows,
        "source_candidates_before_exclusions": before_exclusions,
        "candidate_count": len(candidates),
        "selected": len(selected),
        "dropped_normalized_duplicate": (
            source_rows - outside_word_bounds - before_exclusions
        ),
        "dropped_word_count_outside_bounds": outside_word_bounds,
        "dropped_collision": dict(sorted(collision_counts.items())),
    }
    provenance_data = (
        json.dumps(provenance, ensure_ascii=False, indent=2, sort_keys=True) + "\n"
    ).encode("utf-8")
    write_atomic(OUTPUT, data)
    write_atomic(PROVENANCE_OUTPUT, provenance_data)
    print(json.dumps(provenance, ensure_ascii=False, indent=2, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
