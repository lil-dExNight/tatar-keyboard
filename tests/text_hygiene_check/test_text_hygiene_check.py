#!/usr/bin/env python3
"""Tests for scripts/text_hygiene_check.py on synthetic trees in temporary directories."""

from __future__ import annotations

import contextlib
import importlib.util
import io
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "scripts" / "text_hygiene_check.py"


def load_module(name: str, path: Path):
    spec = importlib.util.spec_from_file_location(name, path)
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module


hygiene = load_module("text_hygiene_check", SCRIPT)

RUSSIAN_PROSE = "эта строка написана по-русски целиком и без кавычек"


class TreeTestCase(unittest.TestCase):
    def setUp(self) -> None:
        self._tmp = tempfile.TemporaryDirectory()
        self.root = Path(self._tmp.name)

    def tearDown(self) -> None:
        self._tmp.cleanup()

    def write(self, path: str, text: str) -> None:
        target = self.root / path
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(text, encoding="utf-8")

    def run_check(self, *extra: str) -> tuple[int, list[str], str]:
        out, err = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            code = hygiene.main(["--root", str(self.root), "--no-git", *extra])
        return code, [line for line in out.getvalue().splitlines() if line], err.getvalue()

    def findings(self) -> list[tuple[str, int, str]]:
        code, lines, _ = self.run_check()
        parsed = []
        for line in lines:
            path, number, rule, _ = line.split(":", 3)
            parsed.append((path, int(number), rule.strip()))
        self.assertEqual(code, 1 if parsed else 0)
        return parsed

    def assertClean(self) -> None:
        self.assertEqual(self.findings(), [])


class DocLinkTest(TreeTestCase):
    def test_missing_doc_in_kotlin_comment_fails(self):
        self.write("app/src/main/java/A.kt", "val a = 1\n// See docs/GONE.md for details.\n")
        self.assertEqual(self.findings(), [("app/src/main/java/A.kt", 2, "doc-link")])

    def test_existing_doc_passes(self):
        self.write("docs/ARCH.md", "# Architecture\n")
        self.write("scripts/a.py", "# Format described in docs/ARCH.md.\nx = 1\n")
        self.assertClean()

    def test_doc_path_in_string_literal_passes(self):
        self.write("app/src/main/java/A.java", 'class A { String p = "docs/GONE.md"; }\n')
        self.write("scripts/a.py", 'PATH = "docs/GONE.md"  # not a doc reference\n')
        self.assertClean()

    def test_block_comment_and_javadoc_lines(self):
        self.write("baselineprofile/B.java",
                   "/**\n * Intro.\n * See docs/GONE.md.\n */\nclass B {}\n")
        self.assertEqual(self.findings(), [("baselineprofile/B.java", 3, "doc-link")])

    def test_python_docstring_counts(self):
        self.write("tests/t/test_x.py", '"""Module.\n\nSee docs/GONE.md.\n"""\nX = 1\n')
        self.assertEqual(self.findings(), [("tests/t/test_x.py", 3, "doc-link")])

    def test_outside_code_roots_is_not_checked(self):
        self.write("build.gradle", "// See docs/GONE.md\n")
        self.assertClean()


class DatedNoteTest(TreeTestCase):
    def test_date_in_shell_comment_fails(self):
        self.write("scripts/run.sh", "#!/bin/sh\necho hi  # added 2026-01-02\n")
        self.assertEqual(self.findings(), [("scripts/run.sh", 2, "dated-note")])

    def test_date_in_shell_string_passes(self):
        self.write("scripts/run.sh", "#!/bin/sh\necho 'built 2026-01-02 # not a comment'\n")
        self.assertClean()

    def test_date_in_xml_comment_fails(self):
        self.write("app/src/main/res/layout/a.xml",
                   "<a>\n<!-- tuned\n  on 2026-01-02 -->\n<b/></a>\n")
        self.assertEqual(self.findings(), [("app/src/main/res/layout/a.xml", 3, "dated-note")])

    def test_date_in_kotlin_raw_string_passes(self):
        self.write("app/src/test/java/T.kt", 'val s = """\n// 2026-01-02\n"""\n')
        self.assertClean()


class HistoryWordTest(TreeTestCase):
    def test_workflow_word_in_main_comment_fails(self):
        self.write("app/src/main/java/A.kt", "// Left UNCOMMITTED by the operator.\nval a = 1\n")
        self.assertEqual(self.findings(), [("app/src/main/java/A.kt", 1, "history-word")])

    def test_identifiers_and_code_pass(self):
        self.write("app/src/main/java/A.kt",
                   "// Delivered through ResultHandoff and TimedHandoff.\n"
                   "operator fun plus(handoff: Int) = handoff\n")
        self.assertClean()

    def test_outside_main_passes(self):
        self.write("app/src/test/java/T.kt", "// see the handoff\n")
        self.assertClean()


class MarkdownLinkTest(TreeTestCase):
    def test_missing_relative_link_fails(self):
        self.write("docs/README.md", "# Index\n\n[Gone](GONE.md) and [ok](../PRIVACY.md)\n")
        self.write("PRIVACY.md", "# Privacy\n")
        self.assertEqual(self.findings(), [("docs/README.md", 3, "broken-link")])

    def test_existing_links_urls_and_anchors_pass(self):
        self.write("README.md", "[a](docs/A.md#top) [b](https://example.org) [c](#x) "
                                "[d](scripts/) ![e](icons/i.svg)\n")
        self.write("docs/A.md", "# A\n")
        self.write("scripts/x.py", "X = 1\n")
        self.write("icons/i.svg", "<svg/>\n")
        self.assertClean()

    def test_backticked_docs_path_must_exist(self):
        self.write("HANDOFF.md", "Read `docs/GONE.md` and `docs/A.md`.\n")
        self.write("docs/A.md", "# A\n")
        self.assertEqual(self.findings(), [("HANDOFF.md", 1, "broken-link")])

    def test_link_outside_repository_fails(self):
        self.write("BRIEF.md", "[ios](../ios/README.md)\n")
        self.assertEqual(self.findings(), [("BRIEF.md", 1, "broken-link")])

    def test_fenced_block_and_code_span_are_skipped(self):
        self.write("BRIEF.md", "```\n[x](GONE.md) docs/GONE.md\n```\n`[y](GONE.md)`\n")
        self.assertClean()


class DocsNonMarkdownTest(TreeTestCase):
    def test_non_markdown_under_docs_fails(self):
        self.write("docs/shot.png", "png")
        self.write("docs/A.md", "# A\n")
        self.assertEqual(self.findings(), [("docs/shot.png", 1, "docs-non-md")])


class CyrillicTest(TreeTestCase):
    def test_prose_in_code_comment_fails(self):
        self.write("app/src/main/java/A.java", f"class A {{}} // {RUSSIAN_PROSE}\n")
        self.assertEqual(self.findings(), [("app/src/main/java/A.java", 1, "cyrillic")])

    def test_quoted_language_data_passes(self):
        self.write("app/src/main/java/A.kt", "\n".join([
            "// Typing `сәләм дөнья хәлләр ничек` commits one word per space.",
            '// The phrase "мин татарча сөйләшәм бик яхшы" is a fixture.',
            "// Shown as «Татар теле клавиатурасы өчен» in the picker.",
            "// Tatar “өч дүрт биш алты” and 'бер ике өч дүрт' both pass.",
            "// U+04D9 \"ә\" CYRILLIC SMALL LETTER SCHWA",
            "// Short runs pass: сәләм дөнья, китап укы.",
            "// Example lists pass: барыр, булыр, алыр, калыр, тулыр.",
            "// Morphemes pass: -ар/-әр/-ыр/-ер and баласы+н/на/нда.",
            "// The fifth row holds ә ө ү җ ң һ.",
            "val s = \"" + RUSSIAN_PROSE + "\"",
            "",
        ]))
        self.assertClean()

    def test_four_word_run_fails(self):
        self.write("scripts/a.py", "# Tatar: мин татарча яхшы сөйләшәм.\n")
        self.assertEqual(self.findings(), [("scripts/a.py", 1, "cyrillic")])

    def test_markdown_scope(self):
        self.write("README.md", f"{RUSSIAN_PROSE}\n")
        self.write("cleaning/PLAN.md", f"{RUSSIAN_PROSE}\n")
        self.write("docs/A.md", f"# A\n\n```\n{RUSSIAN_PROSE}\n```\n\n{RUSSIAN_PROSE}\n")
        self.assertEqual(self.findings(), [("docs/A.md", 7, "cyrillic")])

    def test_extra_roots(self):
        self.write(".github/workflows/ci.yml", f"on: push\n# {RUSSIAN_PROSE}\n")
        self.write(".gitignore", f"build/\n# {RUSSIAN_PROSE}\n")
        self.write("settings.gradle", f"rootProject.name = 'x' // {RUSSIAN_PROSE}\n")
        self.assertEqual(self.findings(), [
            (".github/workflows/ci.yml", 2, "cyrillic"),
            (".gitignore", 2, "cyrillic"),
            ("settings.gradle", 1, "cyrillic"),
        ])

    def test_xml_string_values_pass_and_comments_fail(self):
        self.write("app/src/main/res/values-ru/strings.xml",
                   f"<resources>\n<string name=\"a\">{RUSSIAN_PROSE}</string>\n"
                   f"<!-- {RUSSIAN_PROSE} -->\n</resources>\n")
        self.assertEqual(self.findings(),
                         [("app/src/main/res/values-ru/strings.xml", 3, "cyrillic")])

    def test_ignored_trees(self):
        comment = f"// {RUSSIAN_PROSE} 2026-01-02 docs/GONE.md\n"
        self.write("app/src/main/assets/a.kt", comment)
        self.write("app/src/test/resources/r.kt", comment)
        self.write("app/src/release/generated/g.kt", comment)
        self.write("metadata/en-US/a.md", f"{RUSSIAN_PROSE}\n")
        self.write("data/x.md", f"{RUSSIAN_PROSE}\n")
        self.assertClean()


class AllowlistTest(TreeTestCase):
    def setUp(self) -> None:
        super().setUp()
        self.write("scripts/a.py", "# pinned 2026-01-02\nX = 1\n# built 2026-01-03\n")

    def allow(self, text: str) -> None:
        self.write(hygiene.DEFAULT_ALLOWLIST, text)

    def test_path_rule_entry_suppresses_all_matching_findings(self):
        self.allow("# header\nscripts/a.py:dated-note  # data pin\n")
        self.assertClean()

    def test_path_line_entry_suppresses_one_line(self):
        self.allow("scripts/a.py:1  # data pin\n")
        self.assertEqual(self.findings(), [("scripts/a.py", 3, "dated-note")])

    def test_path_line_rule_entry(self):
        self.allow("scripts/a.py:3:dated-note  # data pin\n")
        self.assertEqual(self.findings(), [("scripts/a.py", 1, "dated-note")])

    def test_stale_entry_fails(self):
        self.allow("scripts/a.py:dated-note  # data pin\nscripts/b.py  # gone\n")
        self.assertEqual(self.findings(),
                         [(hygiene.DEFAULT_ALLOWLIST, 2, "stale-allowlist")])

    def test_entry_without_reason_is_rejected(self):
        self.allow("scripts/a.py:dated-note\n")
        code, lines, err = self.run_check()
        self.assertEqual((code, lines), (2, []))
        self.assertIn("reason", err)

    def test_malformed_entry_is_rejected(self):
        self.allow("scripts/a.py:no-such-rule  # typo\n")
        self.assertEqual(self.run_check()[0], 2)


class OutputTest(TreeTestCase):
    def test_output_format_and_exit_codes(self):
        self.write("scripts/a.py", "X = 1\n")
        self.assertEqual(self.run_check(), (0, [], ""))
        self.write("scripts/a.py", "X = 1  # added 2026-01-02 for the release\n")
        code, lines, err = self.run_check()
        self.assertEqual(code, 1)
        self.assertEqual(lines, ["scripts/a.py:1: dated-note: added 2026-01-02 for the release"])
        self.assertIn("1 finding", err)


@unittest.skipUnless(shutil.which("git"), "git is not installed")
class GitListingTest(TreeTestCase):
    def test_git_listing_skips_ignored_files(self):
        subprocess.run(["git", "init", "-q", str(self.root)], check=True)
        self.write(".gitignore", "build/\n")
        self.write("build/x.py", "# 2026-01-02\n")
        self.write("scripts/a.py", "# 2026-01-02\n")
        self.assertEqual(hygiene.list_files(self.root), [".gitignore", "scripts/a.py"])
        out = io.StringIO()
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(io.StringIO()):
            code = hygiene.main(["--root", str(self.root)])
        self.assertEqual((code, out.getvalue()), (1, "scripts/a.py:1: dated-note: 2026-01-02\n"))


if __name__ == "__main__":
    unittest.main()
