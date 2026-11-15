#!/usr/bin/env python3
"""Fetch text-only Tatar corpus inputs and record their immutable provenance.

The raw downloads and extracted line files live under build/corpus-sources/, which is
ignored. This script is the reproducible acquisition step for TatSC and Common Voice:
it verifies pinned dataset revisions, drops every audio/speaker field, preserves the
upstream train/dev/test split, and writes SHA-256 manifests for the bytes it produced.

TatSC pages from the datasets-server rows API are cached under build/corpus-sources/pages/
together with the pinned dataset revision and the SHA-256 of the exact upstream response
bytes, and every request carries that revision in the X-Pinned-Revision header. A cached
page is trusted only while its recorded revision matches the pin and its bytes still hash
to the recorded fingerprint; edited or partially written pages are refetched, and pages
without a verifiable binding are deleted instead of being given provenance.

Usage:
    python3 research/corpus-sources/fetch_sources.py
    python3 research/corpus-sources/fetch_sources.py --force

Only the Python standard library is used. Large data is never committed.
"""
from __future__ import annotations

import argparse
import concurrent.futures
import gzip
import hashlib
import json
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
BUILD = ROOT / "build" / "corpus-sources"
RAW = BUILD / "raw"
PAGES = BUILD / "pages"
TEXT = BUILD / "text"
PINS_PATH = ROOT / "data" / "corpus-sources" / "source-pins.json"
MANIFEST_PATH = BUILD / "sources.json"

USER_AGENT = "tatar-keyboard-corpus-evaluation/1.0"
PAGE_SIZE = 100


def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def file_digest(path: Path) -> tuple[int, str]:
    digest = hashlib.sha256()
    size = 0
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1 << 20), b""):
            digest.update(chunk)
            size += len(chunk)
    return size, digest.hexdigest()


def atomic_write(path: Path, data: bytes) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(path.name + ".part")
    temporary.write_bytes(data)
    os.replace(temporary, path)


def request_bytes(
    url: str, attempts: int = 5, timeout: int = 120, headers: dict | None = None
) -> bytes:
    request = urllib.request.Request(
        url,
        headers=headers if headers is not None else {"User-Agent": USER_AGENT, "Accept-Encoding": "gzip"},
    )
    for attempt in range(1, attempts + 1):
        try:
            with urllib.request.urlopen(request, timeout=timeout) as response:
                data = response.read()
                if response.headers.get("Content-Encoding") == "gzip":
                    data = gzip.decompress(data)
                return data
        except (urllib.error.URLError, TimeoutError, OSError) as error:
            if attempt == attempts:
                raise RuntimeError(f"GET {url} failed after {attempts} attempts: {error}") from error
            time.sleep(min(20, attempt * 2))
    raise AssertionError("unreachable")


def request_json(url: str) -> object:
    return json.loads(request_bytes(url).decode("utf-8"))


def load_pins() -> dict:
    return json.loads(PINS_PATH.read_text(encoding="utf-8"))


def verify_dataset_revision(dataset: str, expected: str) -> None:
    metadata = request_json(f"https://huggingface.co/api/datasets/{dataset}")
    actual = metadata["sha"]
    if actual != expected:
        raise RuntimeError(
            f"{dataset} is at revision {actual}, pinned {expected}; "
            "update the pin deliberately after reviewing the new data"
        )


def download_pinned(dataset: str, revision: str, remote_path: str, expected: dict) -> Path:
    destination = RAW / dataset.replace("/", "--") / remote_path
    if destination.exists() and expected.get("sha256"):
        if file_digest(destination)[1] == expected["sha256"]:
            return destination
    url = f"https://huggingface.co/datasets/{dataset}/resolve/{revision}/{remote_path}"
    data = request_bytes(url)
    actual = sha256_bytes(data)
    if expected.get("sha256") and actual != expected["sha256"]:
        raise RuntimeError(
            f"{dataset}/{remote_path}: sha256 {actual}, pinned {expected['sha256']}"
        )
    if expected.get("size_bytes") and len(data) != expected["size_bytes"]:
        raise RuntimeError(
            f"{dataset}/{remote_path}: {len(data)} bytes, pinned {expected['size_bytes']}"
        )
    atomic_write(destination, data)
    return destination


def clean_sentence(text: str) -> str:
    """Keep the sentence, but make it one physical line; report if rewriting was needed."""
    return " ".join(text.replace("\r", "\n").split("\n")).strip()


def fetch_common_voice(pins: dict, force: bool) -> list[dict]:
    dataset = pins["common_voice"]["dataset"]
    revision = pins["common_voice"]["revision"]
    verify_dataset_revision(dataset, revision)
    results = []
    for split in pins["common_voice"]["splits"]:
        remote_path = f"transcript/tt/{split}.tsv"
        expected = pins["common_voice"]["files"][remote_path]
        raw_path = download_pinned(dataset, revision, remote_path, expected)
        raw_bytes = raw_path.read_bytes()
        raw_lines = raw_bytes.decode("utf-8").splitlines()
        header = raw_lines[0].split("\t")
        sentence_column = header.index("sentence")
        rows = [line.split("\t") for line in raw_lines[1:] if line]
        texts: list[str] = []
        rewritten = 0
        for row in rows:
            sentence = row[sentence_column].strip() if sentence_column < len(row) else ""
            if not sentence:
                continue
            cleaned = clean_sentence(sentence)
            if cleaned != sentence:
                rewritten += 1
            if cleaned:
                texts.append(cleaned)
        output = TEXT / "common-voice" / f"tt-{split}.txt"
        payload = ("\n".join(texts) + "\n").encode("utf-8")
        if force or not output.exists() or output.read_bytes() != payload:
            atomic_write(output, payload)
        size, digest = file_digest(output)
        results.append(
            {
                "source": "common_voice",
                "config": "tt",
                "split": split,
                "raw_path": str(raw_path.relative_to(ROOT)),
                "raw_size_bytes": len(raw_bytes),
                "raw_sha256": sha256_bytes(raw_bytes),
                "raw_rows": len(rows),
                "rows": len(texts),
                "whitespace_rewrites": rewritten,
                "text_path": str(output.relative_to(ROOT)),
                "text_size_bytes": size,
                "text_sha256": digest,
            }
        )
        print(f"common-voice tt/{split}: {len(texts)} sentences", flush=True)
    return results


def tatsc_page_headers(revision: str) -> dict:
    """Bind every TatSC rows request to the pinned immutable dataset revision.

    The revision gate and the resilience profile are inherited from request_bytes().
    """
    return {
        "User-Agent": USER_AGENT,
        "Accept-Encoding": "gzip",
        "X-Pinned-Revision": revision,
    }


def tatsc_page_content_digest(page: dict) -> str:
    """SHA-256 over the canonical page body that provenance is assembled from.

    Covers the pinned revision, page shape, and the exact normalized row bytes, so any
    edit to a cached page (even one preserving offset/length/row_idx) is detected on
    re-read. The upstream response fingerprint is stored separately and excluded here.
    """
    body = {
        "revision": page.get("revision"),
        "offset": page.get("offset"),
        "length": page.get("length"),
        "rows": page.get("rows"),
    }
    encoded = json.dumps(body, ensure_ascii=False, sort_keys=True).encode("utf-8")
    return sha256_bytes(encoded)


def fetch_tatsc_page(
    pin: dict, split_info: dict, offset: int, length: int
) -> tuple[Path, str]:
    """(Re)fetch one rows page and cache it bound to revision and content hashes."""
    dataset = pin["dataset"]
    revision = pin["revision"]
    config = split_info["config"]
    split = split_info["split"]
    page_path = PAGES / "tatsc" / config / split / f"{offset:08d}.json"
    url = (
        "https://datasets-server.huggingface.co/rows"
        f"?dataset={urllib.parse.quote(dataset, safe='')}"
        f"&config={urllib.parse.quote(config, safe='')}"
        f"&split={urllib.parse.quote(split, safe='')}"
        f"&offset={offset}&length={length}"
    )
    payload = request_bytes(url, headers=tatsc_page_headers(revision))
    fingerprint = sha256_bytes(payload)
    decoded = json.loads(payload.decode("utf-8"))
    rows = []
    for wrapped in decoded["rows"]:
        row_idx = wrapped["row_idx"]
        text = wrapped["row"].get("text")
        if not isinstance(text, str) or not text.strip():
            continue
        rows.append({"row_idx": row_idx, "text": clean_sentence(text)})
    page = {
        "revision": revision,
        "offset": offset,
        "length": length,
        "response_sha256": fingerprint,
        "rows": rows,
    }
    page["content_sha256"] = tatsc_page_content_digest(page)
    encoded = (json.dumps(page, ensure_ascii=False, sort_keys=True) + "\n").encode("utf-8")
    atomic_write(page_path, encoded)
    return page_path, fingerprint


def cached_tatsc_page(pin: dict, split_info: dict, offset: int, length: int) -> tuple[Path, str]:
    """Return the cached page only if it is bound to the pinned revision and its
    recorded content hash still verifies; otherwise reject and refetch it."""
    config = split_info["config"]
    split = split_info["split"]
    page_path = PAGES / "tatsc" / config / split / f"{offset:08d}.json"
    revision = pin["revision"]
    if page_path.exists():
        raw = page_path.read_bytes()
        try:
            cached = json.loads(raw.decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError):
            cached = None
        if isinstance(cached, dict):
            fingerprint = cached.get("response_sha256")
            content_hash = cached.get("content_sha256")
            bound = (
                cached.get("revision") == revision
                and cached.get("offset") == offset
                and cached.get("length") == length
                and isinstance(cached.get("rows"), list)
                and isinstance(fingerprint, str)
                and isinstance(content_hash, str)
            )
            intact = bound and tatsc_page_content_digest(cached) == content_hash
            if intact:
                return page_path, fingerprint
            if not bound:
                # Stale entry: predates revision/hash binding or belongs to another
                # page shape. A refetch cannot heal it (the stub is offline), so drop
                # it and report the ingest as incomplete instead of blessing it.
                page_path.unlink()
                raise RuntimeError(
                    f"{page_path}: cached TatSC page has no verifiable revision and "
                    "content-hash binding; deleted. Re-fetch from the pinned upstream "
                    "before acquiring (requires live huggingface.co)."
                )
            # Bound but edited bytes fall through to a refetch.
        # Unparseable bytes fall through to a refetch.
    return fetch_tatsc_page(pin, split_info, offset, length)


def tatsc_page_task(task: tuple[dict, dict, int, bool]) -> dict:
    pin, split_info, offset, force = task
    total = split_info["num_rows"]
    length = min(PAGE_SIZE, total - offset)
    if force:
        page_path, fingerprint = fetch_tatsc_page(pin, split_info, offset, length)
    else:
        page_path, fingerprint = cached_tatsc_page(pin, split_info, offset, length)
    return {
        "config": split_info["config"],
        "split": split_info["split"],
        "offset": offset,
        "path": str(page_path),
        "response_sha256": fingerprint,
    }


def fetch_tatsc(pins: dict, force: bool) -> list[dict]:
    dataset = pins["tatsc"]["dataset"]
    revision = pins["tatsc"]["revision"]
    verify_dataset_revision(dataset, revision)
    sizes = request_json(
        f"https://datasets-server.huggingface.co/size?dataset={urllib.parse.quote(dataset, safe='')}"
    )["size"]
    actual = {
        (entry["config"], entry["split"]): entry["num_rows"]
        for entry in sizes["splits"]
    }
    tasks = []
    for config, expected_config in pins["tatsc"]["splits"].items():
        for split, expected_rows in expected_config.items():
            got = actual.get((config, split))
            if got != expected_rows:
                raise RuntimeError(f"TatSC {config}/{split}: {got} rows, pinned {expected_rows}")
            for offset in range(0, expected_rows, PAGE_SIZE):
                tasks.append(
                    (
                        {"dataset": dataset, "revision": revision},
                        {"config": config, "split": split, "num_rows": expected_rows},
                        offset,
                        force,
                    )
                )
    page_records = []
    with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool:
        futures = [pool.submit(tatsc_page_task, task) for task in tasks]
        for index, future in enumerate(concurrent.futures.as_completed(futures), start=1):
            page_records.append(future.result())
            if index % 100 == 0 or index == len(futures):
                print(f"tatsc pages: {index}/{len(futures)}", flush=True)

    results = []
    for config, expected_config in pins["tatsc"]["splits"].items():
        for split, expected_rows in expected_config.items():
            verified = {
                (record["config"], record["split"], record["offset"]): record["response_sha256"]
                for record in page_records
            }
            paths = sorted((PAGES / "tatsc" / config / split).glob("*.json"))
            rows = []
            for path in paths:
                raw = path.read_bytes()
                try:
                    page = json.loads(raw.decode("utf-8"))
                except (UnicodeDecodeError, json.JSONDecodeError):
                    raise RuntimeError(
                        f"{path}: cached TatSC page is not valid UTF-8 JSON"
                    ) from None
                if not isinstance(page, dict):
                    raise RuntimeError(f"{path}: cached TatSC page is not a JSON object")
                offset = page.get("offset")
                fingerprint = page.get("response_sha256")
                content_hash = page.get("content_sha256")
                if page.get("revision") != revision or not isinstance(offset, int):
                    raise RuntimeError(
                        f"{path}: cached TatSC page is not bound to pinned revision {revision}"
                    )
                expected_fingerprint = verified.get((config, split, offset))
                if (
                    expected_fingerprint is None
                    or fingerprint != expected_fingerprint
                    or not isinstance(content_hash, str)
                    or tatsc_page_content_digest(page) != content_hash
                ):
                    raise RuntimeError(
                        f"{path}: cached TatSC page fails content-hash verification against "
                        "the pinned upstream response; refusing to give it provenance. "
                        "Delete it and re-fetch from the pinned upstream."
                    )
                rows.extend(page["rows"])
            rows.sort(key=lambda entry: entry["row_idx"])
            indices = [entry["row_idx"] for entry in rows]
            if indices != list(range(expected_rows)):
                raise RuntimeError(
                    f"TatSC {config}/{split}: extracted row indices are not 0..{expected_rows - 1}"
                )
            texts = [entry["text"] for entry in rows]
            output = TEXT / "tatsc" / f"tt-{config}-{split}.txt"
            payload = ("\n".join(texts) + "\n").encode("utf-8")
            if force or not output.exists() or output.read_bytes() != payload:
                atomic_write(output, payload)
            size, digest = file_digest(output)
            results.append(
                {
                    "source": "tatsc",
                    "config": config,
                    "split": split,
                    "revision": revision,
                    "rows": len(texts),
                    "text_path": str(output.relative_to(ROOT)),
                    "text_size_bytes": size,
                    "text_sha256": digest,
                }
            )
            print(f"tatsc {config}/{split}: {len(texts)} sentences", flush=True)
    return results


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--force", action="store_true", help="ignore cached pages/downloads")
    parser.add_argument("--sources", choices=("all", "common_voice", "tatsc"), default="all")
    arguments = parser.parse_args()
    pins = load_pins()
    BUILD.mkdir(parents=True, exist_ok=True)
    records = []
    if arguments.sources in ("all", "common_voice"):
        records.extend(fetch_common_voice(pins, arguments.force))
    if arguments.sources in ("all", "tatsc"):
        records.extend(fetch_tatsc(pins, arguments.force))
    manifest = {
        "schema": 1,
        "generated_by": "research/corpus-sources/fetch_sources.py",
        "datasets": {
            "common_voice": {
                "dataset": pins["common_voice"]["dataset"],
                "revision": pins["common_voice"]["revision"],
                "license": pins["common_voice"]["license"],
            },
            "tatsc": {
                "dataset": pins["tatsc"]["dataset"],
                "revision": pins["tatsc"]["revision"],
                "license": pins["tatsc"]["license"],
            },
        },
        "files": sorted(records, key=lambda record: (record["source"], record["config"] or "", record["split"])),
    }
    atomic_write(
        MANIFEST_PATH,
        (json.dumps(manifest, ensure_ascii=False, indent=2, sort_keys=True) + "\n").encode("utf-8"),
    )
    print(f"wrote {MANIFEST_PATH.relative_to(ROOT)}", flush=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
