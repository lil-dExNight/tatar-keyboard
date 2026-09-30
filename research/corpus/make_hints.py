"""Build the hint column for the acceptance portions: research/corpus/out/hints_{tag}.json.

A hint exists only for rows scripts/review_batches.py marks as "not decidable from the word
alone" (a high mid-line capitalization ratio, or three letters or fewer). Everything else in
the queue is an ordinary word form, and burying tens of thousands of those under example sentences would
make the portions unreadable on the phone they are meant to be read on.

Two kinds of hint, and the difference between them is a licence, not a preference:

* AN EXAMPLE SENTENCE, but only from Tatoeba. Tatoeba is CC BY 2.0 FR: redistributing its
  sentences with attribution is exactly what the licence grants, and the attribution is
  printed in every portion file.

* NEIGHBOURING WORDS WITH COUNTS, for every word Tatoeba does not have. Those words live only
  in OpenSubtitles, which grants no licence to redistribute its text at all. The project uses
  the corpus, but that does not allow copying its sentences into a public repository. Counts
  of which words stand next to which are facts about the text rather than the text, the same
  distinction make_review.py draws for frequencies. Real sentences are available behind a
  flag (--os-lines) that the curator has to set explicitly.

Stop words are dropped from the neighbour lists by ONE mechanical rule -- membership in the
top NEIGHBOUR_STOP_RANK of the SHIPPED dictionary. No judgement about a word's quality is made
anywhere here; that is left to the human curator.
"""
from __future__ import annotations

import argparse
import json
import sys
from collections import Counter, defaultdict
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import corpuslib as CL  # noqa: E402
import dictionary_coverage as cov  # noqa: E402
import review_batches as RB  # noqa: E402
from stream import fast_normalizer, source_name  # noqa: E402

# An example has to be short enough to read on a phone and long enough to be a sentence.
EXAMPLE_MAX_CHARS = 90
EXAMPLE_MIN_TOKENS = 3
EXAMPLE_MAX_TOKENS = 12

# Neighbours that are among the most frequent words of the language carry no information about
# the word being judged. The cut is the shipped asset's own frequency order, so it is a
# measured property of the language and not a hand-written stop list.
NEIGHBOUR_STOP_RANK = 60
NEIGHBOURS_SHOWN = 3
# A word that occurs three times in the whole corpus has no neighbour that occurs twice, and
# those rare words are exactly the ones the curator cannot settle by sight. One sighting of a
# neighbour is still evidence, and the count is printed next to it so it can be weighed.
NEIGHBOUR_MIN_COUNT = 1


def targets_of(queue_path: Path) -> set[str]:
    _preamble, _header, rows = RB.read_queue(queue_path)
    return {row["word"] for row in rows
            if RB.needs_hint(row) and RB.rejection_reason(row["word"]) is None}


def collect_examples(paths, targets, norm, want):
    """Shortest acceptable sentence per target, taken only from corpora named in [want]."""
    best: dict[str, str] = {}
    for path in paths:
        name = source_name(Path(path))
        if name not in want:
            continue
        with CL.open_text(Path(path)) as handle:
            for line in handle:
                text = line.strip()
                if not text or len(text) > EXAMPLE_MAX_CHARS:
                    continue
                chunks = text.split()
                if not (EXAMPLE_MIN_TOKENS <= len(chunks) <= EXAMPLE_MAX_TOKENS):
                    continue
                words = {norm(chunk.strip(CL._EDGE)) for chunk in chunks}
                words.discard(None)
                for word in words & targets:
                    current = best.get(word)
                    if current is None or len(text) < len(current):
                        best[word] = text
    return best


def collect_neighbours(paths, targets, norm):
    """{word: Counter(neighbour)} over immediate left and right neighbours, all corpora.

    The Russian OpenSubtitles file has over a hundred million lines, so the loop is written for
    one pass and a cheap rejection: lowercase-and-strip is enough to tell that a line holds none of the few
    hundred target words, and only the lines that DO hold one pay for full normalization. The
    counts are identical either way -- normalize_word lowercases as its second step, so a token
    that fails the cheap test could not have passed the full one.
    """
    near: dict[str, Counter] = defaultdict(Counter)
    edge = CL._EDGE
    for path in paths:
        with CL.open_text(Path(path)) as handle:
            for line in handle:
                chunks = line.lower().split()
                if len(chunks) < 2:
                    continue
                rough = [chunk.strip(edge) for chunk in chunks]
                if not any(chunk in targets for chunk in rough):
                    continue
                words = [norm(chunk) for chunk in rough]
                for position, word in enumerate(words):
                    if word is None or word not in targets:
                        continue
                    counter = near[word]
                    left = words[position - 1] if position else None
                    right = words[position + 1] if position + 1 < len(words) else None
                    if left:
                        counter[left] += 1
                    if right:
                        counter[right] += 1
    return near


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("tag", choices=("tat", "rus"))
    parser.add_argument("queue")
    parser.add_argument("corpora", nargs="+")
    parser.add_argument("--os-lines", action="store_true",
                        help="брать примеры и из OpenSubtitles (лицензии на это нет; "
                             "включать только по решению оператора)")
    parser.add_argument("--no-tatoeba-lines", action="store_true",
                        help="не класть в репозиторий ни одного предложения: подсказкой "
                             "остаётся только список соседних слов с числами")
    args = parser.parse_args()

    queue_path = Path(args.queue)
    if not queue_path.exists():
        queue_path = RB.ROOT / args.queue
    targets = targets_of(queue_path)

    lang = cov.language_for(args.tag)
    norm = fast_normalizer(lang.alphabet)
    shipped, _boundary = CL.load_shipped(args.tag)
    stop = {word for word, _freq in
            sorted(shipped.items(), key=lambda item: (-item[1], item[0]))[:NEIGHBOUR_STOP_RANK]}

    want = set() if args.no_tatoeba_lines else {"Tatoeba"}
    if args.os_lines:
        want.add("OpenSubtitles")
    examples = collect_examples(args.corpora, targets, norm, want) if want else {}
    remaining = targets - set(examples)
    near = collect_neighbours(args.corpora, remaining, norm) if remaining else {}

    hints: dict[str, str] = {}
    for word in sorted(targets):
        sentence = examples.get(word)
        if sentence:
            hints[word] = sentence
            continue
        neighbours = [(other, count) for other, count in near.get(word, Counter()).most_common()
                      if other not in stop and count >= NEIGHBOUR_MIN_COUNT][:NEIGHBOURS_SHOWN]
        if neighbours:
            hints[word] = "рядом: " + ", ".join(f"{other} {count}" for other, count in neighbours)

    out = CL.OUT / f"hints_{args.tag}.json"
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(hints, ensure_ascii=False, indent=0, sort_keys=True) + "\n",
                   encoding="utf-8")
    from_tatoeba = sum(1 for word in hints if word in examples)
    print(f"{out}: подсказок {len(hints)} на {len(targets)} слов, требующих подсказки; "
          f"примеров-предложений {from_tatoeba}, списков соседей {len(hints) - from_tatoeba}; "
          f"без подсказки осталось {len(targets) - len(hints)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
