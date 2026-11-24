#!/usr/bin/env python3
"""Convert downloaded corpus formats to one JSON object per source document.

Parquet input needs a one-off pyarrow environment, matching the existing dump converters.
Every other reader uses the standard library. Zstandard input requires Python 3.14's
``compression.zstd`` module. Output files are build artifacts and are never direct
inputs to Android asset construction.
"""
from __future__ import annotations

import argparse
import bz2
import hashlib
import io
import json
import os
import tarfile
import xml.etree.ElementTree as ET
import zipfile
from collections.abc import Iterable, Iterator
from pathlib import Path

SOURCE_ORDER = (
    "hplt3",
    "culturax",
    "leipzig_community_2017",
    "leipzig_mixed_2015",
    "leipzig_news_2005_2011",
    "leipzig_news_2015",
    "leipzig_web_2018",
    "tatoeba",
    "fineweb2",
    "madlad400",
    "wikipedia",
    "corpus_tatar",
    "matbugat_ads",
    "matbugat_dating",
    "intertat_comments",
    "tatarcom_library",
    "tugan_tel",
)

LEIPZIG_FILES = {
    "leipzig_community_2017": "tat_community_2017.tar.gz",
    "leipzig_mixed_2015": "tat_mixed_2015_1M.tar.gz",
    "leipzig_news_2005_2011": "tat_news_2005-2011_300K.tar.gz",
    "leipzig_news_2015": "tat_news_2015_1M.tar.gz",
    "leipzig_web_2018": "tat_web_2018_1M.tar.gz",
}


def document(identifier: str, text: str, **metadata: object) -> dict[str, object]:
    result: dict[str, object] = {"id": identifier, "text": text}
    result.update((key, value) for key, value in metadata.items() if value is not None)
    return result


def iter_jsonl(path: Path) -> Iterator[dict[str, object]]:
    with path.open("r", encoding="utf-8") as stream:
        for index, line in enumerate(stream, 1):
            if not line.strip():
                continue
            row = json.loads(line)
            identifier = str(row.get("id", row.get("article_url", row.get("url", index))))
            yield document(
                identifier,
                str(row["text"]),
                url=row.get("url", row.get("article_url")),
            )


def iter_hplt(paths: Iterable[Path]) -> Iterator[dict[str, object]]:
    from compression import zstd

    for path in paths:
        with zstd.open(path, "rt", encoding="utf-8") as stream:
            for index, line in enumerate(stream, 1):
                if not line.strip():
                    continue
                row = json.loads(line)
                yield document(
                    f"{path.stem}:{index}", str(row["text"]), url=row.get("u")
                )


def iter_parquet(paths: Iterable[Path]) -> Iterator[dict[str, object]]:
    import pyarrow.parquet as pq

    for path in paths:
        parquet = pq.ParquetFile(path)
        columns = [
            name for name in ("text", "id", "url", "timestamp", "source", "dump")
            if name in parquet.schema_arrow.names
        ]
        row_offset = 0
        for batch in parquet.iter_batches(batch_size=5000, columns=columns):
            rows = batch.to_pylist()
            for index, row in enumerate(rows, row_offset + 1):
                identifier = str(row.get("id", row.get("url", f"{path.stem}:{index}")))
                yield document(
                    identifier,
                    str(row["text"]),
                    url=row.get("url"),
                    timestamp=row.get("timestamp"),
                    upstream_source=row.get("source"),
                    dump=row.get("dump"),
                )
            row_offset += len(rows)


def iter_leipzig(path: Path) -> Iterator[dict[str, object]]:
    with tarfile.open(path, "r:gz") as archive:
        member = next(
            item for item in archive.getmembers()
            if item.name.endswith("-sentences.txt")
        )
        extracted = archive.extractfile(member)
        if extracted is None:
            raise ValueError(f"{path}: cannot read {member.name}")
        with io.TextIOWrapper(extracted, encoding="utf-8") as stream:
            for line in stream:
                fields = line.rstrip("\r\n").split("\t", 1)
                if len(fields) == 2 and fields[1].strip():
                    yield document(fields[0], fields[1])


def iter_tatoeba(path: Path) -> Iterator[dict[str, object]]:
    with bz2.open(path, "rt", encoding="utf-8") as stream:
        for line in stream:
            fields = line.rstrip("\r\n").split("\t")
            if len(fields) >= 3 and fields[1] == "tat" and fields[2].strip():
                yield document(fields[0], fields[2])


def iter_wikipedia(path: Path) -> Iterator[dict[str, object]]:
    with bz2.open(path, "rb") as stream:
        for _event, element in ET.iterparse(stream, events=("end",)):
            if element.tag.rsplit("}", 1)[-1] != "page":
                continue
            title = ""
            text = ""
            for child in element.iter():
                local = child.tag.rsplit("}", 1)[-1]
                if local == "title" and not title:
                    title = child.text or ""
                elif local == "text":
                    text = child.text or ""
            if text.strip():
                yield document(title, text, url=f"https://tt.wikipedia.org/wiki/{title}")
            element.clear()


def _frequency_document(identifier: str, word: str, frequency: int) -> dict[str, object]:
    return document(identifier, word, frequency=frequency, lexical=True)


def iter_corpus_tatar(directory: Path) -> Iterator[dict[str, object]]:
    case_path = directory / "tatcorpus3.words_frequency_case-sensitive.bz2"
    with bz2.open(case_path, "rt", encoding="utf-8") as stream:
        for index, line in enumerate(stream, 1):
            fields = line.rstrip("\r\n").split("\t")
            if len(fields) == 2 and fields[0].isdigit():
                yield _frequency_document(f"case:{index}", fields[1], int(fields[0]))

    full_path = directory / "tatcorpus2.words_frequency_full.txt.zip"
    with zipfile.ZipFile(full_path) as archive:
        names = [name for name in archive.namelist() if not name.endswith("/")]
        if len(names) != 1:
            raise ValueError(f"{full_path}: expected one text member, got {names}")
        with archive.open(names[0]) as raw:
            with io.TextIOWrapper(raw, encoding="utf-8") as stream:
                for index, line in enumerate(stream, 1):
                    fields = line.rstrip("\r\n").split("\t")
                    if len(fields) == 3 and fields[0].isdigit() and fields[2].isdigit():
                        yield _frequency_document(f"full:{index}", fields[1], int(fields[2]))

    length_path = directory / "tatcorpus3.words.sorted.length.single.txt"
    with length_path.open("r", encoding="utf-8") as stream:
        for index, line in enumerate(stream, 1):
            word = line.strip()
            if word:
                yield _frequency_document(f"length:{index}", word, 1)


def iter_madlad(path: Path) -> Iterator[dict[str, object]]:
    """Decode MADLAD JSONL and its literal newline separators."""
    import gzip

    with gzip.open(path, "rt", encoding="utf-8") as stream:
        for index, line in enumerate(stream, 1):
            if line.strip():
                row = json.loads(line)
                text = str(row["text"]).replace("\\n", "\n")
                yield document(str(index), text)


def source_documents(source: str, raw_root: Path) -> Iterator[dict[str, object]]:
    webscale = raw_root / "webscale"
    curated = raw_root / "curated"
    community = raw_root / "community"
    if source == "hplt3":
        yield from iter_hplt(sorted(webscale.glob("hplt3-tat_Cyrl-*.jsonl.zst")))
    elif source == "culturax":
        yield from iter_parquet([webscale / "culturax-tt-public.parquet"])
    elif source in LEIPZIG_FILES:
        yield from iter_leipzig(curated / "leipzig" / LEIPZIG_FILES[source])
    elif source == "tatoeba":
        yield from iter_tatoeba(curated / "tatoeba" / "tat_sentences.tsv.bz2")
    elif source == "fineweb2":
        yield from iter_parquet(sorted(webscale.glob("fineweb2-tat_Cyrl-*.parquet")))
    elif source == "madlad400":
        yield from iter_madlad(webscale / "madlad400-tt-clean.jsonl.gz")
    elif source == "wikipedia":
        yield from iter_wikipedia(
            curated / "wikipedia" / "ttwiki-20261101-pages-articles.xml.bz2"
        )
    elif source == "corpus_tatar":
        yield from iter_corpus_tatar(curated / "corpus-tatar")
    elif source in {
        "matbugat_ads", "matbugat_dating", "intertat_comments",
        "tatarcom_library", "tugan_tel",
    }:
        names = {
            "matbugat_ads": "matbugat-ads.jsonl",
            "matbugat_dating": "matbugat-dating.jsonl",
            "intertat_comments": "intertat-comments-recent.jsonl",
            "tatarcom_library": "tatarcom-library.jsonl",
            "tugan_tel": "tugan-lessons.jsonl",
        }
        yield from iter_jsonl(community / names[source])
    else:
        raise ValueError(f"unknown source: {source}")


def convert_source(source: str, raw_root: Path, output_root: Path) -> dict[str, object]:
    output_root.mkdir(parents=True, exist_ok=True)
    destination = output_root / f"{source}.jsonl"
    temporary = destination.with_suffix(".jsonl.part")
    digest = hashlib.sha256()
    count = 0
    size = 0
    with temporary.open("wb") as stream:
        for row in source_documents(source, raw_root):
            encoded = (json.dumps(row, ensure_ascii=False, separators=(",", ":")) + "\n").encode("utf-8")
            stream.write(encoded)
            digest.update(encoded)
            count += 1
            size += len(encoded)
    os.replace(temporary, destination)
    return {
        "source": source,
        "documents": count,
        "path": str(destination),
        "bytes": size,
        "sha256": digest.hexdigest(),
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--raw-root", type=Path, default=Path("build/corpus-raw"))
    parser.add_argument("--output-root", type=Path, default=Path("build/corpus-raw/documents"))
    parser.add_argument("--sources", nargs="+", choices=SOURCE_ORDER, default=SOURCE_ORDER)
    arguments = parser.parse_args()
    reports = []
    for source in arguments.sources:
        report = convert_source(source, arguments.raw_root, arguments.output_root)
        reports.append(report)
        print(json.dumps(report, ensure_ascii=False), flush=True)
    report_path = arguments.output_root / "conversion-stats.json"
    combined = {}
    if report_path.is_file():
        previous = json.loads(report_path.read_text(encoding="utf-8"))
        combined.update((item["source"], item) for item in previous["sources"])
    combined.update((item["source"], item) for item in reports)
    ordered = [combined[source] for source in SOURCE_ORDER if source in combined]
    temporary = report_path.with_suffix(".json.part")
    temporary.write_text(
        json.dumps({"sources": ordered}, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    os.replace(temporary, report_path)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
