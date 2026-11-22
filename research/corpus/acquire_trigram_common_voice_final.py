#!/usr/bin/env python3
"""Build the frozen final using the shared Tatar final-exclusion inventory."""
from __future__ import annotations

import hashlib
import json
import os
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts"))

import final_evaluation_exclusions as final_exclusions  # noqa: E402
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
RECIPE_WORKDIR = ROOT / "build" / "trigram-final-common-voice"
GENERATION_COMMAND = (
    "python3 research/corpus-sources/fetch_sources.py --sources common_voice && "
    "python3 research/corpus/acquire_trigram_common_voice_final.py"
)

def heldout_namespaces(
    corpus_dir: Path = CORPUS_DIR,
    *,
    conversation_corpus_dir: Path = make_eval_set.DEFAULT_CORPUS_DIR,
    workdir: Path = RECIPE_WORKDIR,
    inventory_path: Path = final_exclusions.DEFAULT_INVENTORY,
) -> dict[str, frozenset[str]]:
    """Load verified shared exclusions except this builder's own test source and output."""
    recipe = make_eval_set.run_conv_recipe(conversation_corpus_dir, workdir)
    return final_exclusions.load_exclusion_namespaces(
        ROOT,
        corpus_dir,
        inventory_path=inventory_path,
        skip_ids=frozenset(("final-trigram", "common-voice-test")),
        provided_namespaces={
            "conversational-full-stream": final_exclusions.normalized_recipe_sentences(recipe.rows)
        },
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

    # Load and pin every required exclusion before selecting candidates or writing any output.
    excluded = heldout_namespaces()
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

    for line in tuple(candidates):
        owner = next(
            (identifier for identifier, names in excluded.items() if line in names), None
        )
        if owner is not None:
            candidates.remove(line)
            collision_counts[owner] = collision_counts.get(owner, 0) + 1

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
        "# Normalized sentences are disjoint from every selected source in the shared "
        "data/evaluation/final-exclusions-tt.json inventory.",
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
        "exclusion_namespaces": sorted(excluded),
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
