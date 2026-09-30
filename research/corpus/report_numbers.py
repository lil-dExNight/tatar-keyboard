"""Print the OpenSubtitles measurement report tables, straight from out/os_*.json.

Every value is read from the JSON a measurement wrote, so the report is not transcribed by
hand. If a file is missing the row says so instead of guessing.
"""
from __future__ import annotations

import json
from pathlib import Path

OUT = Path(__file__).resolve().parent / "out"


def load(name):
    path = OUT / f"{name}.json"
    if not path.exists() or path.stat().st_size == 0:
        return None
    return json.loads(path.read_text(encoding="utf-8"))


def pct(value):
    return "—" if value is None else f"{value:.4f}".replace(".", ",")


def num(value):
    return "—" if value is None else f"{value:,}".replace(",", " ")


def section(title):
    print(f"\n### {title}")


def main() -> None:
    for tag in ("tat", "rus"):
        filtered = load(f"os_filtered_{tag}")
        hit = load(f"os_hitrate_{tag}")
        big = load(f"os_bigrams_{tag}")
        by = load(f"os_bytes_{tag}")
        live = load(f"os_live_{tag}")

        section(f"{tag}: объём разбиения")
        if filtered:
            for key in ("train_lines", "held_lines", "held_tokens", "boundary_B",
                        "types_before_filter", "tokens_before_filter",
                        "types_after_filter", "tokens_after_filter"):
                print(f"  {key:26} {num(filtered[key])}")
            section(f"{tag}: что отсёк фильтр")
            for reason, data in filtered["removed"].items():
                print(f"  {reason:16} типов {num(data['types']):>12}  токенов {num(data['tokens']):>12}")
                print(f"                   {', '.join(data['examples'][:12])}")
            section(f"{tag}: охват словаря")
            print(f"  сейчас                {pct(filtered['coverage_shipped_pct'])} %")
            print(f"  после, нижняя         {pct(filtered['coverage_filtered_lower_pct'])} %"
                  f"   прирост {pct(filtered['gain_filtered_lower_pp'])} п.п.")
            print(f"  после, верхняя        {pct(filtered['coverage_filtered_upper_pct'])} %"
                  f"   прирост {pct(filtered['gain_filtered_upper_pp'])} п.п.")
            print(f"  без фильтра, нижняя   {pct(filtered['coverage_unfiltered_lower_pct'])} %"
                  f"   прирост {pct(filtered['gain_unfiltered_lower_pp'])} п.п.")
            print(f"  без фильтра, верхняя  {pct(filtered['coverage_unfiltered_upper_pct'])} %"
                  f"   прирост {pct(filtered['gain_unfiltered_upper_pp'])} п.п.")
            print(f"  новых слов в top-100k: нижняя {num(filtered['entered_top100k_filtered_lower'])}"
                  f", верхняя {num(filtered['entered_top100k_filtered_upper'])}")

        section(f"{tag}: предсказание следующего слова (top-3 на разговорном held-out)")
        if hit:
            print(f"  событий               {num(hit['events'])}")
            print(f"  поставляемая          {pct(hit['top3_hitrate_shipped_pct'])} %")
            print(f"  таблица из разговорных {pct(hit['top3_hitrate_conv_only_pct'])} %"
                  f"   Δ {pct(hit['delta_conv_only_pp'])} п.п.")
            print(f"  дописывание           {pct(hit['top3_hitrate_shipped_plus_pct'])} %"
                  f"   Δ {pct(hit['delta_shipped_plus_pp'])} п.п.")
            print(f"  голов в разговорной таблице {num(hit['conv_table_heads'])}, "
                  f"пар {num(hit['conv_table_pairs'])}")

        section(f"{tag}: биграммы")
        if big:
            for key in ("conv_pair_instances", "conv_distinct_pairs",
                        "conv_pairs_whose_head_is_a_shipped_head",
                        "conv_pairs_that_are_new_successors",
                        "shipped_heads_touched_by_corpus",
                        "shipped_heads_whose_top3_has_a_conversational_challenger",
                        "heads_entering_at_H10000", "heads_leaving_at_H10000",
                        "entering_heads_that_get_at_least_one_successor"):
                print(f"  {key:56} {num(big[key])}")
            print("  входящие головы: " +
                  ", ".join(f"{e['word']}" for e in big["heads_entering_examples"][:25]))

        section(f"{tag}: цена в байтах")
        if by:
            print(f"  поставляемый ассет   {num(by['shipped_asset_bytes'])} Б сжат / "
                  f"{num(by['shipped_raw_bytes'])} Б распакован")
            print(f"  слитый ассет         {num(by['merged_asset_bytes'])} Б сжат / "
                  f"{num(by['merged_raw_bytes'])} Б распакован")
            print(f"  дельта               {by['delta_asset_bytes']:+} Б сжат / "
                  f"{by['delta_raw_bytes']:+} Б распакован")
            print(f"  потолки формата      {num(by['format_limit_compressed'])} / "
                  f"{num(by['format_limit_raw'])}; влезает: "
                  f"{by['fits_compressed']} / {by['fits_raw']}")

        section(f"{tag}: живой случай (нижняя граница)")
        if live:
            print(f"  {'слово':16} {'в словаре':>9} {'ранг был':>9} {'голова':>7} "
                  f"{'ранг стал':>10} {'голова':>7}")
            for row in live:
                if row["bound"] != "lower":
                    continue
                print(f"  {row['word']:16} {str(row['in_shipped_dict']):>9} "
                      f"{str(row['shipped_rank']):>9} {str(row['was_bigram_head']):>7} "
                      f"{str(row['merged_rank']):>10} {str(row['becomes_bigram_head']):>7}")


if __name__ == "__main__":
    main()
