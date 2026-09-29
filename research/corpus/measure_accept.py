"""Один проход по корпусу на язык: таблица разговорных частот и все числа приёмки.

Проход дорогой (русский корпус — 107 446 104 строки, около пяти минут на чтение), поэтому
он делает всё сразу и оставляет после себя два артефакта:

1. `data/dictionary/dict-accept/conv-freq-{ru,tt}.tsv` — разговорная частота каждого слова, которое
   либо стоит в поставляемом словаре 1.8.4, либо стоит в очереди приёмки. Этот файл
   КОММИТИТСЯ, и именно по нему `scripts/dict_accept.py pack` собирает ассет. Без него
   пересборка требовала бы 1,5 ГБ корпуса на диске; с ним она воспроизводится из репозитория.
2. `research/corpus/out/accept_cov_{tag}.json` — охват на отложенной выборке.

Почему разговорная частота нужна и поставляемым словам, а не только новым. Досье разделяет
два вопроса: СОСТАВ словаря режется вторым независимым источником, а ЧАСТОТЫ берутся из всего
корпуса целиком. Первая сборка этой миссии нарушила вторую половину: новые слова получали
частоту из субтитров, а поставляемые оставались с частотой Leipzig, то есть письменной. Шкалы
разные, и на префиксе «пап» тройка становилась `папочка|папин|папочку` — 8 929 вхождений в
субтитрах против 918 у «папы» в новостях и Википедии. Слово `папа` из словаря никуда не
девалось, но подсказать его было уже нельзя. Числа этого прогона печатаются в
`docs/DICT-ACCEPT.md` рядом с исправлением.

Охват считается на четырёх словарях сразу, на одной и той же отложенной выборке:

  shipped        — ассет 1.8.4, база сравнения;
  accepted       — состав `поставляемые ∪ принятые`, частота = письменная + разговорная.
                   Ровно то, что собирает `scripts/dict_accept.py pack`;
  whole_queue    — то же, но принята ВСЯ очередь: цена планки, выраженная охватом;
  filtered_lower — полное слияние отфильтрованного корпуса, без всякого отбора. Это в
                   точности `coverage_filtered_lower_pct` из docs/CORPUS-OS.md и он здесь
                   затем, чтобы сойтись с опубликованным числом: если сходится, значит
                   выборка, разбиение и нормализация не поехали.
"""
from __future__ import annotations

import hashlib
import json
import sys
from collections import Counter
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "scripts"))

import corpuslib as CL
import filters as F
import dictionary_coverage as cov
import dictionary_pack as dp
import dict_accept as DA
from measure_filtered import collect_split, held_tokens


def load_baseline(tag: str, directory: Path):
    """Словарь ДО пересборки, из явно названного каталога.

    Не `CL.load_shipped`: к моменту замера `app/src/main/assets` уже содержит новый ассет, и
    «сейчас» перестало быть тем «сейчас», с которым сравниваются все прежние отчёты. Базовая
    копия берётся из коммита 1.8.4, её SHA-256 печатается в результат и стоит в
    `docs/DICT-ACCEPT.md`, поэтому сравнение воспроизводимо и после смены ассета в дереве.
    """
    language = cov.language_for(tag)
    asset = (directory / CL.SHIPPED[tag].name).read_bytes()
    parsed = dp.validate_raw(dp.decompress_asset(asset, language), language=language)
    return dict(zip(parsed.words, parsed.frequencies)), min(parsed.frequencies), \
        hashlib.sha256(asset).hexdigest()


def top100k(frequencies: dict[str, int]) -> set[str]:
    return {w for w, _ in sorted(frequencies.items(), key=lambda kv: (-kv[1], kv[0]))[:100_000]}


def main() -> None:
    tag = sys.argv[1]
    baseline_dir = Path(sys.argv[2])
    paths = sys.argv[3:]
    language = cov.language_for(tag)
    shipped, boundary, baseline_sha = load_baseline(tag, baseline_dir)

    accepted = DA.read_accepted(tag)
    queue = {row.word: row.freq for row in DA.read_queue(tag)}

    split, freq, evidence = collect_split(paths, tag)
    kept, _removed = F.apply_filters(freq, evidence, tag)

    # Артефакт для сборки: разговорная частота слов, которые могут оказаться в ассете.
    # Слова, которых нет ни в словаре, ни в очереди, сюда не идут — в состав они не попадут
    # ни при каком решении, и таскать их в репозитории незачем.
    interesting = set(shipped) | set(queue)
    conv = {word: kept[word] for word in interesting if kept.get(word)}
    DA.write_conv_freq(tag, conv, sources=paths, baseline_sha=baseline_sha)

    def compose(words, conv_source):
        return {w: shipped.get(w, 0) + conv_source.get(w, 0) for w in words}

    dicts = {
        "shipped": set(shipped),
        "accepted": top100k(compose(set(shipped) | set(accepted), conv)),
        "whole_queue": top100k(compose(set(shipped) | set(queue), conv)),
        "filtered_lower": top100k(compose(set(shipped) | set(kept), kept)),
    }

    held = Counter()
    for word in held_tokens(split, language.alphabet):
        held[word] += 1
    total = sum(held.values())

    out = {
        "language": tag,
        "baseline_asset": str(baseline_dir / CL.SHIPPED[tag].name),
        "baseline_sha256": baseline_sha,
        "boundary_B": boundary,
        "train_lines": split.train_lines,
        "held_lines": split.held_lines,
        "held_tokens": total,
        "accepted_words": len(accepted),
        "queue_words": len(queue),
        "kept_types": len(kept),
        "conv_freq_rows": len(conv),
        "conv_freq_covers_shipped": sum(1 for w in shipped if w in conv),
    }
    for name, words in dicts.items():
        hit = sum(count for word, count in held.items() if word in words)
        out[f"coverage_{name}_pct"] = round(100.0 * hit / total, 4) if total else 0.0
        if name != "shipped":
            out[f"entered_{name}"] = len(words - set(shipped))
            out[f"displaced_{name}"] = len(set(shipped) - words)
    base = out["coverage_shipped_pct"]
    for name in dicts:
        if name != "shipped":
            out[f"gain_{name}_pp"] = round(out[f"coverage_{name}_pct"] - base, 4)

    # Цена планки, выраженная не в словах, а в том, сколько раз отклонённые слова реально
    # встретились в тексте, который в их ранжировании не участвовал.
    rejected_words = set(queue) - set(accepted)
    out["held_hits_on_rejected"] = sum(c for w, c in held.items() if w in rejected_words)
    out["held_hits_on_accepted"] = sum(c for w, c in held.items() if w in accepted)
    json.dump(out, sys.stdout, ensure_ascii=False, indent=2)
    print()


if __name__ == "__main__":
    main()
