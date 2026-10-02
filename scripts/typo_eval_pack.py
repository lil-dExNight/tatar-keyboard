#!/usr/bin/env python3
"""Build the deterministic typo-mutated held-out eval set for typo-recovery measurement.

Substrate: the unique words of the held-out eval set (``tt_eval_sentences.txt``) that are words
of the pinned bundled dictionary and have at least 3 code points. One mutation per (word, class):
``sub`` replaces a code point with a geometric key neighbor, ``del`` deletes one code point,
``ins`` inserts a layout-alphabet letter, ``trans`` swaps an adjacent pair of distinct code
points. Each (word, class) pair draws its choices from a private SplitMix64 stream seeded
``splitmix64(TYPO_EVAL_SEED ^ fnv1a64(word)) ^ class_tag`` (class tags are 1..4 in the class
order above), walked position-first, then the choice within the position; ``del`` and ``trans``
have no second draw. A pair with no legal mutation is skipped. Rows are
``original<TAB>class<TAB>mutated`` sorted by (original, class), UTF-8/LF. The set is regenerated,
never committed: its identity is the pinned SHA-256 asserted by tests/typo_eval_pack and mirrored
bit-for-bit by TypoMutatedEvalTest.kt. Exits 2 on missing or invalid input.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Sequence, TextIO

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "scripts"))

import typo_pack  # noqa: E402


# --- Deterministic-selection knobs. -----------------------------------------------------
# The seed is fixed and arbitrary; changing it changes the whole set and the JVM test that
# mirrors it. The class tags double as stream salts so the four mutations of one word are
# independent draws.
TYPO_EVAL_SEED = 0x54544556
MIN_WORD_CODE_POINTS = 3
CLASS_TAGS = {"sub": 1, "del": 2, "ins": 3, "trans": 4}
CLASS_NAMES = tuple(sorted(CLASS_TAGS, key=CLASS_TAGS.get))


class SplitMixStream:
    """One private SplitMix64 stream for a (word, class) pair; see the module docstring.

    ``next()`` returns the current mixed state and then advances the state by the SplitMix64
    gamma, exactly the sequence TypoMutatedEvalTest.kt walks on the JVM.
    """

    __slots__ = ("state",)

    def __init__(self, seed: int) -> None:
        self.state = seed & typo_pack._MASK64

    def next(self) -> int:
        output = typo_pack.splitmix64(self.state)
        self.state = (self.state + typo_pack._SPLITMIX_GAMMA) & typo_pack._MASK64
        return output

    def index(self, choices: int) -> int:
        """The next output mapped to ``[0, choices)`` by unsigned modulo."""
        if choices <= 0:
            raise typo_pack.TypoPackError("selection over an empty choice set")
        return self.next() % choices


def stream_seed(word: str, class_tag: int, *, seed: int = TYPO_EVAL_SEED) -> int:
    """The per-(word, class) stream seed (see the module docstring)."""
    return typo_pack.splitmix64(seed ^ typo_pack.fnv1a64(word.encode("utf-8"))) ^ class_tag


# --------------------------------------------------------------------------------------
# The four mutation primitives. Each returns the mutated code points, or None when the word
# has no legal mutation of the class.
# --------------------------------------------------------------------------------------
def mutate_sub(
    code_points: tuple[int, ...],
    geometric_map: dict[int, tuple[int, ...]],
    alphabet: tuple[int, ...],
    stream: SplitMixStream,
) -> tuple[int, ...] | None:
    """``sub``: one code point replaced by a geometric neighbor; position draw, neighbor draw."""
    positions = [i for i, cp in enumerate(code_points) if geometric_map.get(cp)]
    if not positions:
        return None
    position = positions[stream.index(len(positions))]
    neighbors = geometric_map[code_points[position]]
    mutated = list(code_points)
    mutated[position] = neighbors[stream.index(len(neighbors))]
    return tuple(mutated)


def mutate_del(
    code_points: tuple[int, ...],
    geometric_map: dict[int, tuple[int, ...]],
    alphabet: tuple[int, ...],
    stream: SplitMixStream,
) -> tuple[int, ...] | None:
    """``del``: one code point removed; a single position draw."""
    position = stream.index(len(code_points))
    return code_points[:position] + code_points[position + 1 :]


def mutate_ins(
    code_points: tuple[int, ...],
    geometric_map: dict[int, tuple[int, ...]],
    alphabet: tuple[int, ...],
    stream: SplitMixStream,
) -> tuple[int, ...] | None:
    """``ins``: one alphabet letter inserted; position draw (0..len), then the letter draw."""
    position = stream.index(len(code_points) + 1)
    letter = alphabet[stream.index(len(alphabet))]
    return code_points[:position] + (letter,) + code_points[position:]


def mutate_trans(
    code_points: tuple[int, ...],
    geometric_map: dict[int, tuple[int, ...]],
    alphabet: tuple[int, ...],
    stream: SplitMixStream,
) -> tuple[int, ...] | None:
    """``trans``: two adjacent distinct code points swapped; a single pivot draw."""
    pivots = [i for i in range(len(code_points) - 1) if code_points[i] != code_points[i + 1]]
    if not pivots:
        return None
    pivot = pivots[stream.index(len(pivots))]
    mutated = list(code_points)
    mutated[pivot], mutated[pivot + 1] = mutated[pivot + 1], mutated[pivot]
    return tuple(mutated)


_MUTATORS = {
    "sub": mutate_sub,
    "del": mutate_del,
    "ins": mutate_ins,
    "trans": mutate_trans,
}


def read_eval_words(eval_set_path: Path) -> set[str]:
    """The unique words of the eval set: blank lines and ``#`` header lines are skipped."""
    if not eval_set_path.is_file():
        raise typo_pack.TypoPackError(f"eval set is missing: {eval_set_path}")
    words: set[str] = set()
    for line in eval_set_path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        words.update(token for token in line.split(" ") if token)
    if not words:
        raise typo_pack.TypoPackError(f"eval set has no words: {eval_set_path}")
    return words


def substrate_words(dictionary_words: Sequence[str], eval_words: set[str]) -> list[str]:
    """The dictionary words (stored order) that are eval words of >= 3 code points."""
    return [
        word
        for word in dictionary_words
        if len(word) >= MIN_WORD_CODE_POINTS and word in eval_words
    ]


@dataclass(frozen=True)
class TypoEvalSet:
    rows: tuple[tuple[str, str, str], ...]
    text: str
    data: bytes
    class_counts: dict[str, int]
    substrate_count: int

    @property
    def size(self) -> int:
        return len(self.rows)

    @property
    def sha256(self) -> str:
        return hashlib.sha256(self.data).hexdigest()


def build_typo_eval_set(
    words: Sequence[str],
    geometric_map: dict[int, tuple[int, ...]],
    alphabet: tuple[int, ...],
    *,
    seed: int = TYPO_EVAL_SEED,
) -> TypoEvalSet:
    """Build the mutated set from the substrate ``words``.

    One mutation per (word, class); classes are tried in tag order. The rendered rows are
    sorted by (original, class) — Python string order is code-point order, which the JVM
    mirror reproduces with an explicit code-point comparator.
    """
    rows: list[tuple[str, str, str]] = []
    for word in words:
        code_points = tuple(ord(character) for character in word)
        for class_name in CLASS_NAMES:
            stream = SplitMixStream(stream_seed(word, CLASS_TAGS[class_name], seed=seed))
            mutated = _MUTATORS[class_name](code_points, geometric_map, alphabet, stream)
            if mutated is None:
                continue
            rows.append((word, class_name, "".join(chr(cp) for cp in mutated)))
    if not rows:
        raise typo_pack.TypoPackError("no substrate word yielded a mutation")
    rows.sort(key=lambda row: (row[0], row[1]))
    counts = {name: 0 for name in CLASS_NAMES}
    for _, class_name, _ in rows:
        counts[class_name] += 1
    text = "".join(
        f"{original}\t{class_name}\t{mutated}\n" for original, class_name, mutated in rows
    )
    return TypoEvalSet(
        rows=tuple(rows),
        text=text,
        data=text.encode("utf-8"),
        class_counts=counts,
        substrate_count=len(words),
    )


def generate(
    eval_set_path: Path,
    dictionary_path: Path,
    layout_dir: Path,
    *,
    seed: int = TYPO_EVAL_SEED,
) -> TypoEvalSet:
    """Read the pinned inputs and build the mutated eval set."""
    dictionary_words = typo_pack.read_dictionary_words(dictionary_path)
    eval_words = read_eval_words(eval_set_path)
    geometric_map = typo_pack.read_layout_geometric_map(layout_dir)
    alphabet = typo_pack.read_layout_alphabet(layout_dir)
    return build_typo_eval_set(
        substrate_words(dictionary_words, eval_words), geometric_map, alphabet, seed=seed
    )


# --------------------------------------------------------------------------------------
# CLI.
# --------------------------------------------------------------------------------------
def create_argument_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    build = commands.add_parser("build", help="generate the typo-mutated eval set")
    build.add_argument("--eval-set", type=Path, required=True)
    build.add_argument("--dictionary", type=Path, required=True)
    build.add_argument("--layout-dir", type=Path, required=True)
    build.add_argument("--output", type=Path, required=True)
    return parser


def _print_json(value: object, stream: TextIO | None = None) -> None:
    if stream is None:
        stream = sys.stdout
    json.dump(value, stream, ensure_ascii=False, indent=2, sort_keys=True)
    stream.write("\n")


def main(argv: Sequence[str] | None = None) -> int:
    args = create_argument_parser().parse_args(argv)
    try:
        if args.command == "build":
            result = generate(args.eval_set, args.dictionary, args.layout_dir)
            typo_pack.write_atomic(args.output, result.data)
            _print_json(
                {
                    "class_counts": result.class_counts,
                    "rows": result.size,
                    "seed": TYPO_EVAL_SEED,
                    "set_bytes": len(result.data),
                    "set_sha256": result.sha256,
                    "substrate_words": result.substrate_count,
                }
            )
        else:  # pragma: no cover
            raise AssertionError(args.command)
    except (typo_pack.TypoPackError, OSError, UnicodeError) as error:
        print(f"error: {error}", file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
