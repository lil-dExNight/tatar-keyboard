#!/usr/bin/env python3
"""Check comments and Markdown documents against the repository writing rules.

Scans the files listed by `git ls-files` (tracked plus untracked, not ignored; os.walk when git
is unavailable) and prints one `path:line: rule: excerpt` line per finding. Comment text only:
string literals, code and XML element values are never inspected. Rules and scopes are in
RULES below; exceptions live in scripts/text_hygiene_allowlist.txt, one `path`, `path:line`,
`path:rule` or `path:line:rule` entry per line, each with a trailing `# reason`.
Exit: 0 clean, 1 findings or stale allowlist entries, 2 unreadable or malformed allowlist.
"""

from __future__ import annotations

import argparse
import ast
import io
import os
import posixpath
import re
import subprocess
import sys
import tokenize
import urllib.parse
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable, Iterator, Sequence

ROOT = Path(__file__).resolve().parents[1]
DEFAULT_ALLOWLIST = "scripts/text_hygiene_allowlist.txt"

RULES = {
    "doc-link": "comment cites a docs/*.md file that does not exist",
    "dated-note": "comment carries a YYYY-MM-DD date",
    "history-word": "comment in app/src/main uses operator, handoff or uncommitted",
    "broken-link": "Markdown link or backticked docs/*.md path points to a missing file",
    "docs-non-md": "file under docs/ is not Markdown",
    "cyrillic": "Cyrillic prose outside quotes, backticks or a short run of words",
    "stale-allowlist": "allowlist entry matches no finding",
}

# Roots whose comments are checked by doc-link, dated-note and cyrillic.
CODE_ROOTS = ("app/src/", "scripts/", "tests/", "research/", "baselineprofile/")
# Extra files whose comments are checked by cyrillic only.
CYRILLIC_EXTRA_ROOTS = (".github/",)
HISTORY_WORD_ROOT = "app/src/main/"

IGNORED_PREFIXES = (
    "metadata/",
    "app/src/main/assets/",
    "data/",
    "app/src/test/resources/",
    "app/src/release/generated/",
    "cleaning/",
)
IGNORED_SUFFIXES = ("-prof.txt",)
WALK_SKIP_DIRS = {".git", ".gradle", ".idea", "build", "dist", "__pycache__", "node_modules"}

SLASH_COMMENT_EXTS = {".java", ".kt", ".kts", ".gradle"}
HASH_COMMENT_EXTS = {".sh", ".yml", ".yaml"}

DOC_PATH_RE = re.compile(r"docs/[A-Za-z0-9/_.-]+\.md")
DATE_RE = re.compile(r"\b20\d\d-\d\d-\d\d\b")
HISTORY_WORD_RE = re.compile(r"\b(operator|uncommitted|handoff)\b", re.IGNORECASE)
CYRILLIC_RE = re.compile(r"[\u0400-\u052F]")
MAX_CYRILLIC_RUN = 3

# Spans that may hold Cyrillic language data: code spans, quotes, upstream `U+XXXX "x"` notes.
ALLOWED_SPAN_RES = (
    re.compile(r"`[^`]*`"),
    re.compile(r"U\+[0-9A-Fa-f]{4,6}(?:\s+\S+)?"),
    re.compile(r"«[^»]*»"),
    re.compile(r"“[^”]*”"),
    re.compile(r"„[^“”]*[“”]"),
    re.compile(r"\"[^\"]*\""),
    re.compile(r"(?<!\w)'[^'\n]*'(?!\w)"),
)

MD_LINK_RE = re.compile(r"\]\(\s*<?([^)\s>]+)>?(?:\s+\"[^\"]*\")?\s*\)")
MD_REF_DEF_RE = re.compile(r"^\s{0,3}\[[^\]]+\]:\s+<?(\S+?)>?(?:\s|$)")
MD_CODE_SPAN_RE = re.compile(r"(`+)(.+?)\1")
URL_SCHEME_RE = re.compile(r"^[A-Za-z][A-Za-z0-9+.-]*:")
FENCE_RE = re.compile(r"^\s{0,3}(```|~~~)")


@dataclass(frozen=True)
class Finding:
    path: str
    line: int
    rule: str
    excerpt: str

    def format(self) -> str:
        return f"{self.path}:{self.line}: {self.rule}: {self.excerpt}"


@dataclass(frozen=True)
class AllowEntry:
    source_line: int
    path: str
    line: int | None
    rule: str | None

    def matches(self, finding: Finding) -> bool:
        if finding.path != self.path:
            return False
        if self.line is not None and finding.line != self.line:
            return False
        return self.rule is None or finding.rule == self.rule


class AllowlistError(Exception):
    pass


def list_files(root: Path, use_git: bool = True) -> list[str]:
    """Repository-relative POSIX paths: git's view when available, else a directory walk."""
    if use_git and (root / ".git").exists():
        try:
            out = subprocess.run(
                ["git", "-C", str(root), "ls-files", "-z", "--cached", "--others",
                 "--exclude-standard"],
                check=True, capture_output=True,
            ).stdout
            paths = sorted({p for p in out.decode("utf-8").split("\0") if p})
            return [p for p in paths if (root / p).is_file()]
        except (OSError, subprocess.CalledProcessError):
            pass
    paths = []
    for directory, dirnames, filenames in os.walk(root):
        dirnames[:] = sorted(d for d in dirnames if d not in WALK_SKIP_DIRS)
        for name in filenames:
            rel = os.path.relpath(os.path.join(directory, name), root)
            paths.append(rel.replace(os.sep, "/"))
    return sorted(paths)


def is_ignored(path: str) -> bool:
    return path.startswith(IGNORED_PREFIXES) or path.endswith(IGNORED_SUFFIXES)


def read_text(root: Path, path: str) -> str | None:
    data = (root / path).read_bytes()
    if b"\0" in data:
        return None
    return data.decode("utf-8", errors="replace")


# ---------------------------------------------------------------------------------------------
# Comment extraction. Each extractor yields (line number, comment text) pairs, one per line.


def slash_comments(text: str, groovy: bool) -> Iterator[tuple[int, str]]:
    """`//` and `/* */` comments of Java, Kotlin and Groovy; skips string and char literals."""
    i, n, line = 0, len(text), 1
    quotes = ('"""', "'''") if groovy else ('"""',)
    while i < n:
        c = text[i]
        if c == "\n":
            line += 1
            i += 1
        elif text.startswith("//", i):
            end = text.find("\n", i)
            end = n if end < 0 else end
            yield line, text[i + 2:end]
            i = end
        elif text.startswith("/*", i):
            end = text.find("*/", i + 2)
            end = n if end < 0 else end
            for offset, part in enumerate(text[i + 2:end].split("\n")):
                yield line + offset, part
            line += text.count("\n", i, end)
            i = end + 2
        elif any(text.startswith(q, i) for q in quotes):
            q = text[i:i + 3]
            end = text.find(q, i + 3)
            end = n if end < 0 else end + 3
            line += text.count("\n", i, end)
            i = end
        elif c in "\"'":
            i += 1
            while i < n and text[i] != c and text[i] != "\n":
                i += 2 if text[i] == "\\" else 1
            i += 1 if i < n and text[i] == c else 0
        else:
            i += 1


def hash_comments(text: str) -> Iterator[tuple[int, str]]:
    """`#` comments of shell and YAML: a `#` at a word start outside quotes, per line."""
    for number, raw in enumerate(text.split("\n"), start=1):
        quote = None
        i = 0
        while i < len(raw):
            c = raw[i]
            if quote:
                if c == "\\" and quote == '"':
                    i += 1
                elif c == quote:
                    quote = None
            elif c in "\"'":
                quote = c
            elif c == "#" and (i == 0 or raw[i - 1] in " \t;|&("):
                yield number, raw[i + 1:]
                break
            i += 1


def gitignore_comments(text: str) -> Iterator[tuple[int, str]]:
    for number, raw in enumerate(text.split("\n"), start=1):
        if raw.startswith("#"):
            yield number, raw[1:]


def xml_comments(text: str) -> Iterator[tuple[int, str]]:
    for match in re.finditer(r"<!--(.*?)-->", text, re.DOTALL):
        start = text.count("\n", 0, match.start()) + 1
        for offset, part in enumerate(match.group(1).split("\n")):
            yield start + offset, part


def python_comments(text: str) -> Iterator[tuple[int, str]]:
    """`#` comments via tokenize plus module, class and function docstrings via ast."""
    lines = text.split("\n")
    try:
        for token in tokenize.generate_tokens(io.StringIO(text).readline):
            if token.type == tokenize.COMMENT:
                yield token.start[0], token.string[1:]
    except (tokenize.TokenError, SyntaxError, IndentationError):
        for number, raw in enumerate(lines, start=1):
            if raw.lstrip().startswith("#"):
                yield number, raw.lstrip()[1:]
        return
    try:
        tree = ast.parse(text)
    except SyntaxError:
        return
    owners = [tree] + [
        node for node in ast.walk(tree)
        if isinstance(node, (ast.ClassDef, ast.FunctionDef, ast.AsyncFunctionDef))
    ]
    for owner in owners:
        body = getattr(owner, "body", [])
        if not body or not isinstance(body[0], ast.Expr):
            continue
        value = body[0].value
        if isinstance(value, ast.Constant) and isinstance(value.value, str):
            for number in range(value.lineno, (value.end_lineno or value.lineno) + 1):
                yield number, lines[number - 1]


def comments_for(path: str, text: str) -> Iterator[tuple[int, str]]:
    name = posixpath.basename(path)
    ext = posixpath.splitext(name)[1]
    if ext in SLASH_COMMENT_EXTS:
        return slash_comments(text, groovy=ext == ".gradle")
    if ext == ".py":
        return python_comments(text)
    if ext in HASH_COMMENT_EXTS:
        return hash_comments(text)
    if ext == ".xml":
        return xml_comments(text)
    if name == ".gitignore":
        return gitignore_comments(text)
    return iter(())


# ---------------------------------------------------------------------------------------------
# Rules.


def excerpt(text: str, limit: int = 100) -> str:
    text = " ".join(text.split())
    return text if len(text) <= limit else text[:limit - 3] + "..."


def cyrillic_prose(text: str) -> bool:
    """True when a run of more than MAX_CYRILLIC_RUN Cyrillic words is left outside the allowed
    spans. A run is words separated by whitespace or hyphens only: punctuation and non-Cyrillic
    words end it, so comma-separated example lists and morpheme notes (-ар/-әр) pass. A run
    made only of single letters (a letter list) passes too.
    """
    if not CYRILLIC_RE.search(text):
        return False
    for span_re in ALLOWED_SPAN_RES:
        text = span_re.sub(" _ ", text)
    run = 0
    long_words = 0
    for token in re.sub(r"[^\w\s-]+", " | ", text).split():
        word = token.strip("-")
        if not word:
            continue
        if word == "|" or word == "_" or not CYRILLIC_RE.search(word):
            run = long_words = 0
            continue
        run += 1
        long_words += len(word) > 1
        if run > MAX_CYRILLIC_RUN and long_words:
            return True
    return False


def exists_in(target: str, files: set[str], dirs: set[str]) -> bool:
    target = target.rstrip("/")
    return target in files or target in dirs or target == ""


def check_comments(path: str, text: str, files: set[str], dirs: set[str]) -> Iterator[Finding]:
    in_code_root = path.startswith(CODE_ROOTS)
    cyrillic_scope = (in_code_root or path.startswith(CYRILLIC_EXTRA_ROOTS)
                      or path.endswith(".gradle") or posixpath.basename(path) == ".gitignore")
    if not cyrillic_scope:
        return
    history_scope = path.startswith(HISTORY_WORD_ROOT)
    for number, comment in comments_for(path, text):
        if in_code_root:
            for match in DOC_PATH_RE.finditer(comment):
                if match.group(0) not in files:
                    yield Finding(path, number, "doc-link", excerpt(comment))
                    break
            if DATE_RE.search(comment):
                yield Finding(path, number, "dated-note", excerpt(comment))
        if history_scope and HISTORY_WORD_RE.search(comment):
            yield Finding(path, number, "history-word", excerpt(comment))
        if cyrillic_prose(comment):
            yield Finding(path, number, "cyrillic", excerpt(comment))


def markdown_lines(text: str) -> Iterator[tuple[int, str]]:
    """Lines outside fenced code blocks."""
    fence = None
    for number, raw in enumerate(text.split("\n"), start=1):
        match = FENCE_RE.match(raw)
        if match:
            if fence is None:
                fence = match.group(1)
                continue
            if match.group(1) == fence:
                fence = None
                continue
        if fence is None:
            yield number, raw


def resolve_link(md_path: str, target: str) -> str | None:
    """Repository-relative path of a local link target, or None for URLs and anchors."""
    if not target or target.startswith("#") or URL_SCHEME_RE.match(target):
        return None
    target = urllib.parse.unquote(target.split("#", 1)[0].split("?", 1)[0])
    if not target:
        return None
    if target.startswith("/"):
        joined = target.lstrip("/")
    else:
        joined = posixpath.join(posixpath.dirname(md_path), target)
    return posixpath.normpath(joined)


def check_markdown(path: str, text: str, files: set[str], dirs: set[str]) -> Iterator[Finding]:
    cyrillic_scope = path != "README.md"
    for number, raw in markdown_lines(text):
        without_code = MD_CODE_SPAN_RE.sub(" ", raw)
        targets = [m.group(1) for m in MD_LINK_RE.finditer(without_code)]
        ref = MD_REF_DEF_RE.match(without_code)
        if ref:
            targets.append(ref.group(1))
        broken = False
        for target in targets:
            resolved = resolve_link(path, target)
            if resolved is not None and (resolved.startswith("../")
                                         or not exists_in(resolved, files, dirs)):
                broken = True
        for span in MD_CODE_SPAN_RE.finditer(raw):
            for match in DOC_PATH_RE.finditer(span.group(2)):
                if match.group(0) not in files:
                    broken = True
        if broken:
            yield Finding(path, number, "broken-link", excerpt(raw))
        if cyrillic_scope and cyrillic_prose(raw):
            yield Finding(path, number, "cyrillic", excerpt(raw))


def scan(root: Path, paths: Sequence[str]) -> list[Finding]:
    files = set(paths)
    dirs = {posixpath.dirname(p) for p in paths}
    for d in list(dirs):
        while d:
            d = posixpath.dirname(d)
            dirs.add(d)
    findings: list[Finding] = []
    for path in paths:
        if is_ignored(path):
            continue
        if path.startswith("docs/") and not path.endswith(".md"):
            findings.append(Finding(path, 1, "docs-non-md", "not a Markdown file"))
            continue
        text = read_text(root, path)
        if text is None:
            continue
        if path.endswith(".md"):
            findings.extend(check_markdown(path, text, files, dirs))
        else:
            findings.extend(check_comments(path, text, files, dirs))
    return findings


# ---------------------------------------------------------------------------------------------
# Allowlist.


def parse_allowlist(text: str) -> list[AllowEntry]:
    entries = []
    for number, raw in enumerate(text.split("\n"), start=1):
        body, _, reason = raw.partition("#")
        body = body.strip()
        if not body:
            continue
        if not reason.strip():
            raise AllowlistError(f"line {number}: entry without a '# reason' comment: {raw!r}")
        path, *rest = body.split(":")
        line = None
        rule = None
        for part in rest:
            if part.isdigit() and line is None and rule is None:
                line = int(part)
            elif part in RULES and rule is None:
                rule = part
            else:
                raise AllowlistError(f"line {number}: expected path[:line][:rule], got {body!r}")
        entries.append(AllowEntry(number, path, line, rule))
    return entries


def apply_allowlist(findings: Iterable[Finding], entries: Sequence[AllowEntry],
                    allowlist_path: str) -> list[Finding]:
    used = set()
    kept = []
    for finding in findings:
        hit = next((e for e in entries if e.matches(finding)), None)
        if hit is None:
            kept.append(finding)
        else:
            used.add(hit.source_line)
    for entry in entries:
        if entry.source_line not in used:
            kept.append(Finding(allowlist_path, entry.source_line, "stale-allowlist",
                                f"{entry.path} matches no finding"))
    return kept


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n", 1)[0])
    parser.add_argument("--root", type=Path, default=ROOT, help="repository root")
    parser.add_argument("--allowlist", default=DEFAULT_ALLOWLIST,
                        help="allowlist path relative to the root")
    parser.add_argument("--no-git", action="store_true", help="list files with os.walk")
    args = parser.parse_args(argv)

    root = args.root.resolve()
    allowlist_file = root / args.allowlist
    try:
        entries = parse_allowlist(allowlist_file.read_text(encoding="utf-8")
                                  if allowlist_file.exists() else "")
    except (OSError, UnicodeDecodeError, AllowlistError) as error:
        print(f"{args.allowlist}: {error}", file=sys.stderr)
        return 2

    findings = apply_allowlist(scan(root, list_files(root, use_git=not args.no_git)),
                               entries, args.allowlist)
    for finding in findings:
        print(finding.format())
    if findings:
        print(f"text hygiene: {len(findings)} finding(s)", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
