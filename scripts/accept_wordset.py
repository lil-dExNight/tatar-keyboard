#!/usr/bin/env python3
"""Acceptance run for a new word set: the evidence steps behind one command.

For a named candidate source, the runner executes, in order:

1. select — `scripts/dict_accept.py select`: the acceptance rule over the review pool,
   rewriting `data/dictionary/dict-accept/accepted-*.tsv` and `rejected-*.tsv`;
2. coverage — `research/corpus/measure_accept.py`: the held-out coverage delta of the
   accepted set against the shipped baseline (also rewrites `conv-freq-*.tsv`);
3. gate — `research/corpus/tt_gate_check.py` or `ru_gate_check.py`: the candidate dictionary
   and bigram table against the committed assets on the pinned eval set (the coverage gate
   and the paired bootstrap CI95 of the chain top-3 delta). A rejected gate stops the run
   before the query audit: the candidate is not accepted;
4. query-audit — `scripts/dictionary_pack.py query-audit`: the candidate's rows for the
   manual query list, saved for the review into `data/dictionary/*-query-review.tsv`.

Every step is a subprocess of the existing script; the runner measures nothing itself, it
relays the step reports saved under --work-dir. When every step passes, it prints the re-pin
checklist: the work a rebuild from the accepted word set leaves to you.

Run it from the repository root. Exit codes: 0 the candidate passed the gate; 1 a step failed
or broke its output contract; 2 bad arguments or a missing input; 3 the gate rejected the
candidate.
"""
from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
from pathlib import Path
from typing import NamedTuple

ROOT = Path(__file__).resolve().parents[1]

SELECT_SCRIPT = ROOT / "scripts" / "dict_accept.py"
MEASURE_SCRIPT = ROOT / "research" / "corpus" / "measure_accept.py"
AUDIT_SCRIPT = ROOT / "scripts" / "dictionary_pack.py"
GATE_SCRIPTS = {
    "tat": ROOT / "research" / "corpus" / "tt_gate_check.py",
    "rus": ROOT / "research" / "corpus" / "ru_gate_check.py",
}
QUERIES = {
    "tat": ROOT / "tests" / "dictionary_pack" / "fixtures" / "manual_tatar_queries.txt",
    "rus": ROOT / "tests" / "dictionary_pack" / "fixtures" / "manual_russian_queries.txt",
}
# The query-review table and the review-date pin of each language, for the checklist.
REVIEW_TABLE = {
    "tat": "data/dictionary/tt-query-review.tsv",
    "rus": "data/dictionary/ru-query-review.tsv",
}
REVIEW_DATE_PIN = {"tat": "TATAR_REVIEW_DATE", "rus": "RUSSIAN_REVIEW_DATE"}

DEFAULT_WORK_DIR = ROOT / "research" / "corpus" / "out" / "accept_wordset"

SOURCE_NAME = re.compile(r"[a-z0-9][a-z0-9-]*")


class StepFailed(Exception):
    """A step exited nonzero or broke its output contract; the message is already printed."""


class Step(NamedTuple):
    """One evidence step: the argv after the interpreter, the artifact name inside the work
    dir, and whether stdout is a JSON report to relay."""
    name: str
    argv: list
    artifact: str
    is_json: bool


def parse_args(argv=None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Run the acceptance evidence steps for a candidate word set: select "
                    "(the acceptance rule over the review pool), the held-out coverage "
                    "delta, the gate check against the committed assets, and the "
                    "query-audit rows; then print the re-pin checklist.",
        epilog="steps: dict_accept.py select -> measure_accept.py -> tt_gate_check.py / "
               "ru_gate_check.py -> dictionary_pack.py query-audit. Each step runs as a "
               "subprocess of the existing script; its report is saved under --work-dir. "
               "A gate rejection stops the run before the query audit. Exit codes: 0 the "
               "gate passed, 1 a step failed, 2 bad arguments or a missing input, 3 the "
               "gate rejected the candidate.")
    parser.add_argument("--source", required=True,
                        help="name of the candidate source, a lower-case slug; it labels "
                             "the run and the default work dir")
    parser.add_argument("--language", required=True, choices=sorted(GATE_SCRIPTS),
                        help="language of the candidate word set")
    parser.add_argument("--baseline", required=True, type=Path,
                        help="directory with the 1.8.4 assets; the steps check the SHA-256 "
                             "themselves")
    parser.add_argument("--corpus", required=True, nargs="+", type=Path, metavar="FILE",
                        help="the conversational corpus files the coverage pass reads")
    parser.add_argument("--candidate-dict", required=True, type=Path, metavar="FILE",
                        help="candidate dictionary asset, built outside the asset tree")
    parser.add_argument("--candidate-bigram", required=True, type=Path, metavar="FILE",
                        help="candidate bigram table packed against the candidate dictionary")
    parser.add_argument("--queries", type=Path, default=None,
                        help="manual query list for the audit (default: the committed "
                             "tests/dictionary_pack/fixtures/manual_<language>_queries.txt)")
    parser.add_argument("--work-dir", type=Path, default=None,
                        help="where the step reports are saved (default: "
                             "research/corpus/out/accept_wordset/<source>/)")
    args = parser.parse_args(argv)
    if not SOURCE_NAME.fullmatch(args.source):
        parser.error("--source must be a lower-case slug (letters, digits, dashes)")
    if args.queries is None:
        args.queries = QUERIES[args.language]
    return args


def missing_inputs(args: argparse.Namespace) -> list[str]:
    """Every missing input, listed before anything runs, like the rebuild's input gate."""
    missing = []
    if not args.baseline.is_dir():
        missing.append(f"baseline directory: {args.baseline}")
    for path in args.corpus:
        if not path.is_file():
            missing.append(f"corpus file: {path}")
    for label, path in (("candidate dictionary", args.candidate_dict),
                        ("candidate bigram table", args.candidate_bigram),
                        ("query list", args.queries)):
        if not path.is_file():
            missing.append(f"{label}: {path}")
    return missing


def build_steps(args: argparse.Namespace) -> list[Step]:
    tag = args.language
    steps = [
        Step("select",
             [SELECT_SCRIPT, "select", "--baseline", args.baseline],
             "select.json", True),
        Step("coverage",
             [MEASURE_SCRIPT, tag, args.baseline, *args.corpus],
             "coverage.json", True),
        Step("gate",
             [GATE_SCRIPTS[tag], "--candidate-dict", args.candidate_dict,
              "--candidate-bigram", args.candidate_bigram],
             "gate.json", True),
    ]
    audit_argv = [AUDIT_SCRIPT, "query-audit", "--asset", args.candidate_dict,
                  "--queries", args.queries]
    if tag == "rus":
        audit_argv += ["--language", "rus"]
    steps.append(Step("query-audit", audit_argv, "query-audit.tsv", False))
    return steps


def run_step(step: Step, work_dir: Path) -> dict | None:
    """Run one step, save its stdout under the work dir, return the parsed report if any.

    stderr is inherited, so a long step keeps its progress and a failed step has already
    printed its reason.
    """
    argv = [sys.executable, *(str(part) for part in step.argv)]
    print("+ " + " ".join(argv[1:]), file=sys.stderr)
    completed = subprocess.run(argv, stdout=subprocess.PIPE, encoding="utf-8", check=False)
    artifact = work_dir / step.artifact
    artifact.write_text(completed.stdout, encoding="utf-8")
    if completed.returncode != 0:
        print(f"error: step '{step.name}' failed with exit code {completed.returncode}",
              file=sys.stderr)
        raise StepFailed
    if not step.is_json:
        return None
    try:
        return json.loads(completed.stdout)
    except json.JSONDecodeError:
        print(f"error: step '{step.name}' printed no JSON report; "
              f"its output is saved in {artifact}", file=sys.stderr)
        raise StepFailed


def relay(report: dict, step: str, *keys):
    """One field of a step report; a missing field means the step broke its contract."""
    value = report
    for key in keys:
        if not isinstance(value, dict) or key not in value:
            print(f"error: step '{step}' report has no field "
                  f"\"{'.'.join(str(k) for k in keys)}\"", file=sys.stderr)
            raise StepFailed
        value = value[key]
    return value


def checklist(tag: str, audit_artifact: Path) -> str:
    """The re-pin work a rebuild from the accepted word set leaves to you."""
    return f"""\
A rebuild from the accepted word set leaves the following to you; each one fails its test
until it is updated:

 1. Contract pins. Rebuild the assets: python3 scripts/rebuild_assets.py --baseline <dir>
    [--corpus-dir <dir> | --only tatar|russian]. The build rewrites the generated pins in
    app/src/main/java/rkr/simplekeyboard/inputmethod/latin/dictionary/storage/DictionaryStorageContracts.kt,
    app/src/main/java/rkr/simplekeyboard/inputmethod/latin/dictionary/storage/BigramStorageContracts.kt
    and app/src/main/java/rkr/simplekeyboard/inputmethod/latin/dictionary/storage/CompanionListStorageContracts.kt.
 2. Sentence-start tables. Rebuild them with scripts/sentstart_pack.py when a word left the
    dictionary, then update the record count and SHA-256 in tests/sentstart_pack/,
    TatarSentStartAssetTest and RussianSentStartAssetTest.
 3. Query-review table. Review the candidates in {audit_artifact}, write the accepted rows
    into {REVIEW_TABLE[tag]} with the review date, and set {REVIEW_DATE_PIN[tag]} in
    tests/dictionary_pack/test_dictionary_pack.py to that date.
 4. Extra bigram heads. After a rebuild that changed the Tatar dictionary, run
    scripts/bigram_extra_heads_regen.py --corpus-dir <dir>, rebuild the Tatar side again
    (--only tatar), then update the word count and sample words in ShippedExtraHeadListTest
    (tests/bigram_asset_pack/).
 5. Typo eval sets. Regenerate the committed sets in app/src/test/resources/ with
    scripts/typo_eval_pack.py; update the row, byte and class counts and SHA-256 in
    tests/typo_eval_pack/, TypoMutatedEvalTest, TtDl1TierCalibrationTest,
    RuTypoMutatedEvalTest and AutocorrectWideningCalibrationTest.
 6. Measured results. Re-pin from a reviewed measurement: TtSuggestEvalTest,
    RuSuggestEvalTest, PersonalizationEvalTest, tests/personalization_eval/,
    TypoMutatedEvalTest, RuTypoMutatedEvalTest, TtDl1TierCalibrationTest,
    TtFullSubstitutionCalibrationTest, AutocorrectWideningCalibrationTest and
    GlideRecoveryCalibrationTest.
 7. Autocorrect floors. AutocorrectPolicy.MIN_CANDIDATE_FREQUENCY_TT and
    MIN_CANDIDATE_FREQUENCY_RU in
    app/src/main/java/rkr/simplekeyboard/inputmethod/latin/dictionary/engine/AutocorrectAdvice.kt
    must equal the frequency of a fixed-rank word; AutocorrectFloorPinTest reports the value.
 8. Expected words. The concrete-word assertions: TdictPrefixIndexTopFrequentWordsTest,
    TtNextWordFillE2ETest, TatBigrPrefixIndexTest, TdictPrefixIndexSameStemBoostTest,
    CursorMoveBandTest, EmptyPrefixProbeEquivalenceTest, ShippedIndexPrefixCoverageTest,
    GlideEndToEndTest and GlideDecoderHostGeometryReuseTest.
 9. Head drift. scripts/known_asset_drift.json and the drift counts asserted in
    tests/rebuild_assets/."""


def main(argv=None) -> int:
    args = parse_args(argv)
    missing = missing_inputs(args)
    if missing:
        print("error: missing inputs:", file=sys.stderr)
        for item in missing:
            print(f"  {item}", file=sys.stderr)
        return 2
    work_dir = args.work_dir if args.work_dir is not None else DEFAULT_WORK_DIR / args.source
    work_dir.mkdir(parents=True, exist_ok=True)

    steps = build_steps(args)
    print(f"accept-wordset: source '{args.source}', language {args.language}")
    print(f"work dir: {work_dir}")
    total = len(steps)
    for index, step in enumerate(steps, start=1):
        print(f"[{index}/{total}] {step.name}", flush=True)
        try:
            report = run_step(step, work_dir)
        except StepFailed:
            return 1
        print(f"      -> {work_dir / step.artifact}")
        if step.name == "select":
            try:
                accepted = relay(report, step.name, "languages", args.language, "accepted")
                rejected = relay(report, step.name, "languages", args.language, "rejected")
            except StepFailed:
                return 1
            print(f"      accepted: {accepted}, rejected: {rejected}")
        elif step.name == "coverage":
            try:
                shipped_pct = relay(report, step.name, "coverage_shipped_pct")
                accepted_pct = relay(report, step.name, "coverage_accepted_pct")
                delta = relay(report, step.name, "gain_accepted_pp")
            except StepFailed:
                return 1
            print(f"      held-out coverage: {shipped_pct}% shipped, "
                  f"{accepted_pct}% accepted (delta {delta:+.4f} pp)")
        elif step.name == "gate":
            try:
                coverage_delta = relay(report, step.name, "coverage_delta_pp")
                chain_delta = relay(report, step.name, "chain_top3_delta_pp")
                chain_ci = relay(report, step.name, "chain_top3_delta_ci95")
                passed = relay(report, step.name, "gate_passed")
            except StepFailed:
                return 1
            verdict = "PASSED" if passed else "REJECTED"
            print(f"      coverage delta {coverage_delta:+.4f} pp, chain top-3 delta "
                  f"{chain_delta:+.4f} pp CI95 {chain_ci}: {verdict}")
            if not passed:
                print("the gate rejected the candidate word set; the query audit and the "
                      "re-pin checklist apply only to an accepted set")
                return 3
    print()
    print(checklist(args.language, work_dir / "query-audit.tsv"))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
