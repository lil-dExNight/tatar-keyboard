#!/usr/bin/env python3
"""Build and verify the retained corpus acquisition inventory."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import sys
from pathlib import Path, PurePosixPath
from typing import Any, Sequence

SOURCE_IDS = (
    "hplt3", "culturax", "leipzig_community_2017", "leipzig_mixed_2015",
    "leipzig_news_2005_2011", "leipzig_news_2015", "leipzig_web_2018", "tatoeba",
    "fineweb2", "madlad400", "wikipedia", "corpus_tatar", "matbugat_ads",
    "matbugat_dating", "intertat_comments", "tatarcom_library", "tugan_tel",
)
ROLES = {
    "hplt3": "general_written_text", "culturax": "informal_web_text",
    "leipzig_community_2017": "community_and_general_sentences",
    "leipzig_mixed_2015": "mixed_register_sentences",
    "leipzig_news_2005_2011": "news_sentences", "leipzig_news_2015": "news_sentences",
    "leipzig_web_2018": "web_sentences", "tatoeba": "curated_conversational_sentences",
    "fineweb2": "general_web_text", "madlad400": "general_web_text_clean_split",
    "wikipedia": "lexical_and_names_only", "corpus_tatar": "wordform_evidence_only",
    "matbugat_ads": "informal_user_written_text",
    "matbugat_dating": "informal_user_written_text",
    "intertat_comments": "informal_user_comments",
    "tatarcom_library": "written_tatar_only", "tugan_tel": "constructed_dialogue_forms",
}
LAST_MODIFIED = {
    "curated/leipzig/tat_community_2017.tar.gz": "Fri, 15 Jan 2021 12:04:07 GMT",
    "curated/leipzig/tat_mixed_2015_1M.tar.gz": "Sat, 21 Nov 2020 05:19:28 GMT",
    "curated/leipzig/tat_news_2005-2011_300K.tar.gz": "Thu, 18 Aug 2011 23:36:48 GMT",
    "curated/leipzig/tat_news_2015_1M.tar.gz": "Fri, 20 Nov 2020 21:20:29 GMT",
    "curated/leipzig/tat_web_2018_1M.tar.gz": "Fri, 20 Nov 2020 22:58:43 GMT",
    "curated/tatoeba/tat_sentences.tsv.bz2": "Sat, 21 Nov 2026 06:31:36 GMT",
    "curated/wikipedia/ttwiki-20261101-pages-articles.xml.bz2": "Mon, 02 Nov 2026 00:44:07 GMT",
    "curated/corpus-tatar/tatcorpus3.words_frequency_case-sensitive.bz2": "Wed, 13 Feb 2019 19:52:00 GMT",
    "curated/corpus-tatar/tatcorpus2.words_frequency_full.txt.zip": "Thu, 08 Feb 2018 23:43:00 GMT",
    "curated/corpus-tatar/tatcorpus3.words.sorted.length.single.txt": "Tue, 01 Jan 2019 16:43:00 GMT",
}
CRAWL_FILES = {
    "matbugat_ads": "matbugat-ads-manifest.json",
    "matbugat_dating": "matbugat-dating-manifest.json",
    "intertat_comments": "intertat-comments-recent-manifest.json",
    "tatarcom_library": "tatarcom-library-manifest.json",
    "tugan_tel": "tugan-lessons-r5c-manifest.json",
}
COMMUNITY_URLS = {
    "matbugat_ads": "https://matbugat.ru/ads/",
    "matbugat_dating": "https://matbugat.ru/dating/",
    "intertat_comments": "https://intertat.tatar/sitemap/sitemap.xml",
    "tatarcom_library": "https://tatar.com.ru/rubrika/lib/",
    "tugan_tel": "https://tugan-tel.ru/sitemap.html",
}
HEX64 = re.compile(r"[0-9a-f]{64}")
METADATA_FIELDS = ("hf_revision", "dump_release", "last_modified", "retrieved_utc")


class ManifestValidationError(ValueError):
    """A manifest is malformed or does not match retained files."""


def file_digest(path: Path) -> tuple[int, str]:
    """Return byte size and SHA-256, computed from one streaming read."""
    digest = hashlib.sha256()
    size = 0
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1 << 20), b""):
            size += len(chunk)
            digest.update(chunk)
    return size, digest.hexdigest()


def _source_id(path: str) -> str:
    name = PurePosixPath(path).name
    if name.startswith("hplt3-"): return "hplt3"
    if name.startswith("culturax-"): return "culturax"
    if name.startswith("fineweb2-"): return "fineweb2"
    if name.startswith("madlad400-"): return "madlad400"
    if name.startswith("ttwiki-"): return "wikipedia"
    if name.startswith("tatcorpus"): return "corpus_tatar"
    if name == "tat_sentences.tsv.bz2": return "tatoeba"
    leipzig = {
        "tat_community_2017.tar.gz": "leipzig_community_2017",
        "tat_mixed_2015_1M.tar.gz": "leipzig_mixed_2015",
        "tat_news_2005-2011_300K.tar.gz": "leipzig_news_2005_2011",
        "tat_news_2015_1M.tar.gz": "leipzig_news_2015",
        "tat_web_2018_1M.tar.gz": "leipzig_web_2018",
    }
    community = {
        "matbugat-ads.jsonl": "matbugat_ads",
        "matbugat-dating.jsonl": "matbugat_dating",
        "intertat-comments-recent.jsonl": "intertat_comments",
        "tatarcom-library.jsonl": "tatarcom_library",
        "tugan-lessons.jsonl": "tugan_tel",
    }
    if name in leipzig: return leipzig[name]
    if name in community: return community[name]
    raise ManifestValidationError(f"unmapped retained path: {path}")


def _metadata(record: dict[str, Any], local_path: str, retrieved: str | None) -> dict[str, Any]:
    values = {
        "hf_revision": record.get("revision"),
        "dump_release": record.get("release"),
        "last_modified": LAST_MODIFIED.get(local_path),
        "retrieved_utc": retrieved,
    }
    unavailable = {
        key: "The acquisition evidence does not provide this field."
        for key, value in values.items() if value is None
    }
    return {**values, "metadata_unavailable": unavailable}


def _retained(record: dict[str, Any], raw_root: Path, retrieved: str | None) -> dict[str, Any]:
    full_path = str(record["path"])
    prefix = "build/corpus-raw/"
    if not full_path.startswith(prefix):
        raise ManifestValidationError(f"unexpected retained path: {full_path}")
    local_path = full_path[len(prefix):]
    source_id = _source_id(local_path)
    size, sha256 = file_digest(raw_root / local_path)
    if "bytes" in record and size != record["bytes"]:
        raise ManifestValidationError(f"{local_path}: byte size disagrees with acquisition evidence")
    if "sha256" in record and sha256 != record["sha256"]:
        raise ManifestValidationError(f"{local_path}: SHA-256 disagrees with acquisition evidence")
    return {
        "source_id": source_id, "source_role": ROLES[source_id],
        "local_path": local_path, "source_url": record["url"], "retained": True,
        "bytes": size, "sha256": sha256,
        **_metadata(record, local_path, retrieved),
    }


def _crawl_records(source_id: str, document: dict[str, Any]) -> list[dict[str, Any]]:
    retrieved = document["retrieved_at"]
    records = []
    if source_id in {"matbugat_ads", "matbugat_dating"}:
        base = document["source"]
        for page in document["pages"]:
            records.append({
                "url": f"{base}?page={page['page']}", "retrieved_utc": retrieved,
                "kind": "content_html", "retained": False, "local_path": None,
                "bytes": page["bytes"], "sha256": page["sha256"],
                "download_status": "downloaded",
            })
        return records
    if source_id == "intertat_comments":
        records.append({
            "url": document["sitemap_index"], "retrieved_utc": retrieved,
            "kind": "sitemap_index", "retained": False, "local_path": None,
            "bytes": 0, "sha256": None, "download_status": "hash_unavailable",
            "metadata_unavailable": {"sha256": "The collector did not record index size or hash."},
        })
        for kind, pages in (("sitemap", document["sitemaps"]), ("article_html", document["article_pages"])):
            for page in pages:
                failed = bool(page.get("error"))
                records.append({
                    "url": page["url"], "retrieved_utc": retrieved, "kind": kind,
                    "retained": False, "local_path": None, "bytes": page["bytes"],
                    "sha256": None if failed else page.get("sha256"),
                    "download_status": "failed" if failed else "downloaded",
                    **({"metadata_unavailable": {"sha256": page["error"]}} if failed else {}),
                })
        return records
    for page in document["pages"]:
        records.append({
            "url": page["url"], "retrieved_utc": retrieved, "kind": "content_html",
            "retained": False, "local_path": None, "bytes": page["bytes"],
            "sha256": page["sha256"], "download_status": "downloaded",
        })
    return records


def _totals(sources: list[dict[str, Any]]) -> dict[str, int]:
    retained = [record for source in sources for record in source["retained_files"]]
    downloads = [record for source in sources for record in source["non_retained_downloads"]]
    return {
        "source_count": len(sources), "retained_file_count": len(retained),
        "retained_bytes": sum(record["bytes"] for record in retained),
        "non_retained_download_count": len(downloads),
        "non_retained_bytes": sum(record["bytes"] for record in downloads),
        "failed_download_count": sum(record["download_status"] == "failed" for record in downloads),
    }


def build_manifest(
    raw_root: Path, bulk_report: Path, community_report: Path, crawl_dir: Path
) -> dict[str, Any]:
    """Hash retained files and attach retained crawl metadata."""
    bulk = json.loads(bulk_report.read_text(encoding="utf-8"))
    community = json.loads(community_report.read_text(encoding="utf-8"))
    sources = {source_id: {"source_id": source_id, "role": ROLES[source_id],
                           "retained_files": [], "non_retained_downloads": []}
               for source_id in SOURCE_IDS}
    crawl_documents = {}
    for source_id, filename in CRAWL_FILES.items():
        crawl_documents[source_id] = json.loads((crawl_dir / filename).read_text(encoding="utf-8"))
    for record in bulk["files"]:
        retained = _retained(record, raw_root, None)
        sources[retained["source_id"]]["retained_files"].append(retained)
    for record in community["files"]:
        normalized = dict(record)
        normalized["path"] = f"build/corpus-raw/community/{record['name']}"
        source_id = _source_id(normalized["path"])
        normalized["url"] = COMMUNITY_URLS[source_id]
        retained = _retained(normalized, raw_root, crawl_documents[source_id]["retrieved_at"])
        sources[retained["source_id"]]["retained_files"].append(retained)
    for source_id, document in crawl_documents.items():
        sources[source_id]["non_retained_downloads"] = _crawl_records(source_id, document)
    ordered = [sources[source_id] for source_id in SOURCE_IDS]
    return {
        "schema_version": 1, "expected_source_ids": list(SOURCE_IDS),
        "sources": ordered, "totals": _totals(ordered),
    }


def _safe_path(raw_root: Path, value: Any, label: str) -> Path:
    if not isinstance(value, str) or not value:
        raise ManifestValidationError(f"{label}: invalid local path")
    relative = PurePosixPath(value)
    if relative.is_absolute() or ".." in relative.parts:
        raise ManifestValidationError(f"{label}: unsafe relative path")
    return raw_root.joinpath(*relative.parts)


def _require(condition: bool, message: str) -> None:
    if not condition:
        raise ManifestValidationError(message)


def validate_manifest(
    manifest: dict[str, Any], raw_root: Path, required_sources: Sequence[str] = SOURCE_IDS
) -> None:
    """Validate structure and recompute every retained file's size and SHA-256."""
    _require(isinstance(manifest, dict), "manifest must be an object")
    _require(manifest.get("schema_version") == 1, "unsupported schema_version")
    expected = manifest.get("expected_source_ids")
    _require(
        expected == list(required_sources),
        "declared sources must exactly match required source ids in order",
    )
    sources = manifest.get("sources")
    _require(isinstance(sources, list), "sources must be an array")
    ids = [source.get("source_id") for source in sources if isinstance(source, dict)]
    _require(ids == list(required_sources), "actual sources must exactly match required ids")
    paths = set()
    for source in sources:
        source_id = source["source_id"]
        retained = source.get("retained_files")
        downloads = source.get("non_retained_downloads")
        _require(isinstance(retained, list) and retained, f"{source_id}: no retained files")
        _require(isinstance(downloads, list), f"{source_id}: downloads must be an array")
        if source_id in ROLES:
            _require(source["role"] == ROLES[source_id], f"{source_id}: invalid source role")
        for record in retained:
            label = f"{source_id}:{record.get('local_path')}"
            _require(record.get("source_id") == source_id, f"{label}: source ownership mismatch")
            if source_id in ROLES:
                _require(
                    _source_id(record.get("local_path")) == source_id,
                    f"{label}: local path belongs to another source",
                )
            _require(record.get("source_role") == source["role"], f"{label}: role mismatch")
            _require(record.get("retained") is True, f"{label}: retained must be true")
            _require(isinstance(record.get("source_url"), str), f"{label}: invalid URL")
            path = _safe_path(raw_root, record.get("local_path"), label)
            _require(record["local_path"] not in paths, f"{label}: duplicate path")
            paths.add(record["local_path"])
            _require(type(record.get("bytes")) is int and record["bytes"] >= 0, f"{label}: invalid bytes")
            _require(isinstance(record.get("sha256"), str) and HEX64.fullmatch(record["sha256"]) is not None, f"{label}: invalid SHA-256")
            unavailable = record.get("metadata_unavailable", {})
            for field in METADATA_FIELDS:
                _require(field in record, f"{label}: missing {field}")
                value = record[field]
                _require(
                    (isinstance(value, str) and bool(value))
                    or (value is None and isinstance(unavailable.get(field), str) and bool(unavailable[field])),
                    f"{label}: null {field} needs an evidence reason",
                )
            _require(path.is_file(), f"{label}: retained file is missing")
            size, sha256 = file_digest(path)
            _require(size == record["bytes"], f"{label}: byte size mismatch")
            _require(sha256 == record["sha256"], f"{label}: SHA-256 mismatch")
        for record in downloads:
            _require(record.get("retained") is False and record.get("local_path") is None,
                     f"{source_id}: invalid non-retained record")
            _require(type(record.get("bytes")) is int and record["bytes"] >= 0,
                     f"{source_id}: invalid download bytes")
            status = record.get("download_status")
            _require(status in {"downloaded", "failed", "hash_unavailable"},
                     f"{source_id}: invalid download status")
            sha256 = record.get("sha256")
            if sha256 is None:
                _require(status != "downloaded", f"{source_id}: downloaded record needs SHA-256")
                _require(isinstance(record.get("metadata_unavailable", {}).get("sha256"), str),
                         f"{source_id}: null download SHA-256 needs a reason")
            else:
                _require(status == "downloaded" and HEX64.fullmatch(sha256) is not None,
                         f"{source_id}: invalid download SHA-256")
    _require(manifest.get("totals") == _totals(sources), "totals do not match source records")


def _write(path: Path, value: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(path.name + ".part")
    temporary.write_text(json.dumps(value, ensure_ascii=False, indent=2, sort_keys=True) + "\n")
    os.replace(temporary, path)


def main(argv: Sequence[str] | None = None, *, required_sources: Sequence[str] = SOURCE_IDS) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    build = commands.add_parser("build")
    build.add_argument("--raw-root", type=Path, required=True)
    build.add_argument("--bulk-report", type=Path, required=True)
    build.add_argument("--community-report", type=Path, required=True)
    build.add_argument("--crawl-dir", type=Path, required=True)
    build.add_argument("--output", type=Path, required=True)
    check = commands.add_parser("check")
    check.add_argument("--manifest", type=Path, required=True)
    check.add_argument("--raw-root", type=Path, required=True)
    args = parser.parse_args(argv)
    try:
        if args.command == "build":
            manifest = build_manifest(args.raw_root, args.bulk_report, args.community_report, args.crawl_dir)
            _write(args.output, manifest)
            print(json.dumps({"status": "built", **manifest["totals"]}, sort_keys=True))
        else:
            manifest = json.loads(args.manifest.read_text(encoding="utf-8"))
            validate_manifest(manifest, args.raw_root, required_sources)
            print(json.dumps({"status": "valid", **manifest["totals"]}, sort_keys=True))
    except (ManifestValidationError, OSError, json.JSONDecodeError, KeyError, TypeError) as error:
        print(f"ERROR: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
