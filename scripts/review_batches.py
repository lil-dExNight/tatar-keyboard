#!/usr/bin/env python3
"""Cut the dictionary acceptance queues into numbered portions for manual review.

    slice    -- split each queue (data/dictionary/*-conv-review.tsv) into fixed-size TSV portions
                in queue order, and set mechanical garbage aside in a separate file with reasons.
    collect  -- read the portions back, count accepted and refused words, and record refusals in
                the queue's ``note`` column and in a marks file.

``approved`` is never written; a check on every write enforces it. An unmarked word counts as
accepted only in a portion whose ``# вычитано:`` line the reviewer has filled in.
"""
from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

# --- what the queue looks like -------------------------------------------------------------

QUEUES = {
    "ru": ("data/dictionary/ru-conv-review.tsv", "rus", "русская"),
    "tt": ("data/dictionary/tt-conv-review.tsv", "tat", "татарская"),
}
DEFAULT_OUT = "build/review-batches"
DEFAULT_SIZE = 200

# --- mechanical rejection ------------------------------------------------------------------
#
# Only obvious machine garbage is removed before review, and it is kept with its reason and
# counted. This is not a quality filter: judging a word is left to the reviewer.

LATIN = re.compile(r"[A-Za-z]")
DIGIT = re.compile(r"[0-9]")
MAX_LEN = 30


def rejection_reason(word: str) -> str | None:
    """Why this word is mechanical garbage, or None if it goes to the reviewer."""
    if LATIN.search(word):
        return "латиница в кириллическом наборе"
    if DIGIT.search(word):
        return "цифры в слове"
    if len(word) > MAX_LEN:
        return f"длиннее {MAX_LEN} символов"
    if len(word) == 1:
        return "одна буква"
    return None


# --- what counts as "not obvious from the word alone" ---------------------------------------
#
# The hint column is filled only for rows that cannot be settled from the word alone. Two
# signals from the queue generator decide it:
#   * cap_ratio -- share of occurrences capitalized mid-line; high means "probably a name that
#     slipped through the proper-noun filter".
#   * length <= 3 -- interjections, fragments and OCR debris, which look alike.
# Other rows get no hint, so the list stays easy to scan on a phone.

CAP_SUSPECT = 0.35
SHORT_WORD = 3


def needs_hint(row: dict[str, str]) -> bool:
    return float(row["cap_ratio"]) >= CAP_SUSPECT or len(row["word"]) <= SHORT_WORD


def hint_for(row: dict[str, str], hints: dict[str, str]) -> str:
    """The hint cell: the capitalization share first (for most flagged rows it decides the
    case), then the context from make_hints.py, which may be absent for very rare words.
    """
    if not needs_hint(row):
        return ""
    parts: list[str] = []
    cap = float(row["cap_ratio"])
    if cap >= CAP_SUSPECT:
        parts.append(f"с заглавной {round(cap * 100)}%")
    context = hints.get(row["word"], "")
    if context:
        parts.append(context)
    return " · ".join(parts)


# --- queue I/O ------------------------------------------------------------------------------


def read_queue(path: Path) -> tuple[list[str], list[str], list[dict[str, str]]]:
    """Return (preamble_lines, header_fields, rows)."""
    preamble: list[str] = []
    header: list[str] | None = None
    rows: list[dict[str, str]] = []
    with path.open(encoding="utf-8") as handle:
        for line in handle:
            line = line.rstrip("\n")
            if header is None and line.startswith("#"):
                preamble.append(line)
                continue
            if header is None:
                header = line.split("\t")
                continue
            if not line:
                continue
            fields = line.split("\t")
            fields += [""] * (len(header) - len(fields))
            rows.append(dict(zip(header, fields)))
    if header is None:
        raise SystemExit(f"{path}: не нашёл строку заголовка")
    return preamble, header, rows


def write_queue(path: Path, preamble: list[str], header: list[str],
                rows: list[dict[str, str]], approved_before: list[str]) -> None:
    """Rewrite the queue; refuse to write if ``approved`` differs from [approved_before],
    the column exactly as it was read.
    """
    now = [row.get("approved", "") for row in rows]
    if now != approved_before:
        raise SystemExit(
            f"{path}: колонка approved изменилась бы при записи. Это запрещено: её заполняет "
            "только оператор лично. Останавливаюсь, ничего не записав."
        )
    with path.open("w", encoding="utf-8", newline="\n") as handle:
        for line in preamble:
            handle.write(line + "\n")
        handle.write("\t".join(header) + "\n")
        for row in rows:
            handle.write("\t".join(row.get(name, "") for name in header) + "\n")


# --- portion files --------------------------------------------------------------------------

BATCH_HEADER = ["нет", "слово", "частота", "ист", "подсказка"]
UNMARKED = {"", "."}
DONE_PREFIX = "# вычитано:"
# The portion header lets the reviewer write a reason after the refusal mark («x имя»), so a
# mark is split into the refusal and the text after it.
REFUSAL_MARKS = "xXхХ"


def split_mark(mark: str) -> tuple[bool, str]:
    """(refused, reason) for one cell of the first column."""
    if mark.strip() in UNMARKED:
        return False, ""
    return True, mark.strip().lstrip(REFUSAL_MARKS).strip(" .,:;-—")

SOURCE_CODE = {
    "Tatoeba": "T",
    "OpenSubtitles": "S",
    "OpenSubtitles+Tatoeba": "TS",
}


def source_code(sources: str) -> str:
    return SOURCE_CODE.get(sources, sources)


def batch_preamble(name: str, index: int, total: int, first: int, last: int,
                   grand_total: int, language: str) -> str:
    return f"""\
# ПОРЦИЯ {name} — {index} из {total}. Слова {first}–{last} из {grand_total} ({language} очередь).
# Чем выше слово в списке, тем чаще оно встречается: бросить чтение можно на любой строке.
#
# ОТМЕЧАЙ ТОЛЬКО ОТКАЗЫ. Поставь x в первой колонке у слова, которому не место в клавиатуре;
# всё непомеченное считается принятым. Причину можно дописать тут же: «x имя».
#
# Когда порция дочитана — допиши что угодно после двоеточия в строке ниже и сохрани файл.
# Без этой отметки порция считается непрочитанной, и её слова в приём не пойдут.
{DONE_PREFIX}
#
# Дальше на компьютере: python3 scripts/review_batches.py collect
#
# колонки: нет | слово | частота | ист | подсказка
#   нет       — «.» не значит ничего; замени на x, чтобы отказать
#   частота   — сколько раз слово встретилось в разговорных корпусах
#   ист       — T Tatoeba (лицензия чистая) · S OpenSubtitles (гранта нет) · TS оба
#   подсказка — стоит только там, где по одному виду слова не решить:
#               «с заглавной 86%» — так часто слово писалось с большой буквы не в начале
#               строки, то есть это, скорее всего, имя; дальше — пример строки или список
#               соседних слов с числом встреч
#
# Примеры в подсказках — предложения из Tatoeba (CC BY 2.0 FR, tatoeba.org). Строк из
# OpenSubtitles здесь нет: лицензии на их распространение не существует (docs/CORPUS-OS.md),
# поэтому оттуда взяты только соседние слова с их числами, а не сам текст.
"""


def cmd_slice(args: argparse.Namespace) -> int:
    out_root = ROOT / args.out
    out_root.mkdir(parents=True, exist_ok=True)
    index_rows: list[list[str]] = []

    for key in args.language:
        queue_rel, tag, language = QUEUES[key]
        queue_path = ROOT / queue_rel
        _preamble, _header, rows = read_queue(queue_path)

        hints: dict[str, str] = {}
        hint_path = Path(args.hints_dir)
        if not hint_path.is_absolute():
            hint_path = ROOT / hint_path
        hint_path = hint_path / f"hints_{tag}.json"
        if hint_path.exists():
            hints = json.loads(hint_path.read_text(encoding="utf-8"))

        kept: list[dict[str, str]] = []
        dropped: list[tuple[dict[str, str], str]] = []
        for row in rows:
            reason = rejection_reason(row["word"])
            (dropped.append((row, reason)) if reason else kept.append(row))

        lang_dir = out_root / key
        lang_dir.mkdir(parents=True, exist_ok=True)
        for stale in lang_dir.glob(f"{key}-*.tsv"):
            stale.unlink()

        total_batches = (len(kept) + args.size - 1) // args.size
        for number in range(total_batches):
            chunk = kept[number * args.size:(number + 1) * args.size]
            name = f"{key}-{number + 1:03d}"
            first = number * args.size + 1
            last = first + len(chunk) - 1
            path = lang_dir / f"{name}.tsv"
            with path.open("w", encoding="utf-8", newline="\n") as handle:
                handle.write(batch_preamble(name, number + 1, total_batches, first, last,
                                            len(kept), language))
                handle.write("\t".join(BATCH_HEADER) + "\n")
                for row in chunk:
                    hint = hint_for(row, hints)
                    handle.write("\t".join([
                        ".", row["word"], row["train_freq"],
                        source_code(row["sources"]), hint,
                    ]) + "\n")
            index_rows.append([
                name, language, str(len(chunk)), chunk[0]["word"], chunk[-1]["word"],
                chunk[0]["train_freq"], chunk[-1]["train_freq"],
                str(sum(1 for row in chunk if needs_hint(row))),
                str(path.relative_to(ROOT)),
            ])

        dropped_path = lang_dir / f"{key}-dropped.tsv"
        with dropped_path.open("w", encoding="utf-8", newline="\n") as handle:
            handle.write(
                "# Слова, снятые машиной до вычитки. Оператор их не смотрит, но может\n"
                "# проверить, что именно машина выкинула и почему. В очереди они остаются.\n"
            )
            handle.write("\t".join(["слово", "частота", "ист", "причина"]) + "\n")
            for row, reason in dropped:
                handle.write("\t".join([
                    row["word"], row["train_freq"], source_code(row["sources"]), reason,
                ]) + "\n")

        print(f"{key}: {len(kept)} слов в {total_batches} порциях по {args.size}; "
              f"снято машиной {len(dropped)}; с подсказкой "
              f"{sum(1 for row in kept if needs_hint(row))}; "
              f"подсказок непустых {sum(1 for row in kept if hint_for(row, hints))}")

    index_path = out_root / "INDEX.tsv"
    with index_path.open("w", encoding="utf-8", newline="\n") as handle:
        handle.write("# Порядок вычитки: сверху вниз. Первая порция — самые частые слова.\n")
        handle.write("\t".join([
            "порция", "язык", "слов", "первое", "последнее",
            "частота_первого", "частота_последнего", "с_подсказкой", "файл",
        ]) + "\n")
        for row in index_rows:
            handle.write("\t".join(row) + "\n")
    print(f"{index_path.relative_to(ROOT)}: {len(index_rows)} порций")
    return 0


# --- collecting the marks back --------------------------------------------------------------


def read_batch(path: Path) -> tuple[bool, list[tuple[str, str]]]:
    """Return (declared_read, [(word, mark)]) for one portion file."""
    declared = False
    header_seen = False
    marks: list[tuple[str, str]] = []
    with path.open(encoding="utf-8") as handle:
        for line in handle:
            line = line.rstrip("\n")
            if line.startswith(DONE_PREFIX):
                declared = bool(line[len(DONE_PREFIX):].strip())
                continue
            if line.startswith("#"):
                continue
            if not header_seen:
                header_seen = True
                continue
            if not line.strip():
                continue
            fields = line.split("\t")
            fields += [""] * (len(BATCH_HEADER) - len(fields))
            marks.append((fields[1].strip(), fields[0].strip()))
    return declared, marks


def cmd_collect(args: argparse.Namespace) -> int:
    out_root = ROOT / args.out
    progress: list[list[str]] = []
    exit_code = 0

    for key in args.language:
        queue_rel, _tag, language = QUEUES[key]
        queue_path = ROOT / queue_rel
        preamble, header, rows = read_queue(queue_path)
        approved_before = [row.get("approved", "") for row in rows]
        by_word = {row["word"]: row for row in rows}

        verdicts: dict[str, str] = {}
        batches = sorted((out_root / key).glob(f"{key}-[0-9]*.tsv"))
        read_batches = 0
        unknown_words: list[str] = []
        for path in batches:
            declared, marks = read_batch(path)
            refused = [(word, mark) for word, mark in marks if split_mark(mark)[0]]
            for word, _mark in marks:
                if word not in by_word:
                    unknown_words.append(f"{path.name}:{word}")
            if declared:
                read_batches += 1
                for word, mark in marks:
                    is_refused, reason = split_mark(mark)
                    verdicts[word] = ("отказ" if is_refused else "принято", reason)
            progress.append([
                path.stem, language, str(len(marks)),
                "вычитано" if declared else "не вычитано",
                str(len(refused)),
                str(len(marks) - len(refused)) if declared else "0",
            ])

        if unknown_words:
            print(f"{key}: ВНИМАНИЕ, {len(unknown_words)} слов из порций нет в очереди — "
                  f"порции устарели относительно {queue_rel}. Примеры: "
                  + ", ".join(unknown_words[:5]), file=sys.stderr)
            exit_code = 1

        marks_path = out_root / f"marks-{key}.tsv"
        with marks_path.open("w", encoding="utf-8", newline="\n") as handle:
            handle.write(
                "# Итог вычитки, собранный из порций. Переживает перегенерацию очереди:\n"
                "# после run_review.sh достаточно снова запустить collect.\n"
                "# approved этот файл НЕ содержит и содержать не может — его ставит оператор.\n"
            )
            handle.write("\t".join(["слово", "решение", "причина"]) + "\n")
            for word in sorted(verdicts):
                verdict, reason = verdicts[word]
                handle.write(f"{word}\t{verdict}\t{reason}\n")

        refused_total = sum(1 for verdict, _reason in verdicts.values() if verdict == "отказ")
        accepted_total = len(verdicts) - refused_total
        for row in rows:
            decision = verdicts.get(row["word"])
            note = row.get("note", "")
            note = re.sub(r"\s*\[вычитка: [^\]]*\]", "", note).strip()
            if decision:
                verdict, reason = decision
                stamp = f"{verdict}, {reason}" if reason else verdict
                note = (note + f" [вычитка: {stamp}]").strip()
            row["note"] = note
        if not args.dry_run:
            write_queue(queue_path, preamble, header, rows, approved_before)

        in_batches = sum(int(entry[2]) for entry in progress if entry[1] == language)
        print(f"{key}: порций {len(batches)}, из них вычитано {read_batches}; "
              f"принято {accepted_total}, отвергнуто {refused_total}, "
              f"не вычитано {in_batches - len(verdicts)}. "
              f"approved не заполнен ни в одной строке — это делает оператор.")

    progress_path = out_root / "PROGRESS.tsv"
    with progress_path.open("w", encoding="utf-8", newline="\n") as handle:
        handle.write("# Состояние вычитки на момент последнего запуска collect.\n")
        handle.write("\t".join([
            "порция", "язык", "слов", "состояние", "отказов", "принято",
        ]) + "\n")
        for row in progress:
            handle.write("\t".join(row) + "\n")
    print(f"{progress_path.relative_to(ROOT)}: {len(progress)} порций")
    return exit_code


def main(argv: list[str] | None = None) -> int:
    common = argparse.ArgumentParser(add_help=False)
    common.add_argument("--out", default=DEFAULT_OUT)
    common.add_argument("--language", nargs="+", choices=sorted(QUEUES),
                        default=sorted(QUEUES))

    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    sub = parser.add_subparsers(dest="command", required=True)

    cut = sub.add_parser("slice", parents=[common], help="нарезать очереди на порции")
    cut.add_argument("--size", type=int, default=DEFAULT_SIZE)
    cut.add_argument("--hints-dir", default="research/corpus/out")
    cut.set_defaults(func=cmd_slice)

    back = sub.add_parser("collect", parents=[common], help="собрать отметки из порций обратно")
    back.add_argument("--dry-run", action="store_true")
    back.set_defaults(func=cmd_collect)

    args = parser.parse_args(argv)
    return args.func(args)


if __name__ == "__main__":
    raise SystemExit(main())
