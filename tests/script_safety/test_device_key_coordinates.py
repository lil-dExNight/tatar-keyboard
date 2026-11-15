"""Host contract: the device scripts' tap coordinates match the Tatar layout resources.

device-perf-ritual.sh and device-netstats-proof.sh drive the keyboard by absolute
taps on the 720x1640 POCO C71. Both carry a copy of the letter->(x, y) table, and
the ritual's layout probe taps the top-left key. This suite ties every table entry
to the keyboard that actually ships: the key centers are recomputed from
rows_tatar.xml and the rowkeys_*.xml files, the probe words һәм and дөнья must land
on the keys that type their letters, and the ё/ъ keys of the extra Tatar row must
be tt-only (on ru they are long-press duplicates of е/ь, so a plain tap script has
no business targeting them). No device is needed.
"""

from __future__ import annotations

import re
import subprocess
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
DEVICE_PERF = ROOT / "scripts" / "device-perf-ritual.sh"
NETSTATS = ROOT / "scripts" / "device-netstats-proof.sh"
SCRIPTS = (DEVICE_PERF, NETSTATS)
XML_DIR = ROOT / "app" / "src" / "main" / "res" / "xml"
LATIN = "{http://schemas.android.com/apk/res-auto}"
SCREEN_WIDTH_PX = 720

# script_points is a shell function whose body is a python heredoc: the first ^}$
# inside it would close the extraction too early, so the match runs to the heredoc
# terminator and the brace that follows.
SCRIPT_POINTS_RE = re.compile(r"(?ms)^script_points\(\) \{.*?^PYEOF\n^\}\n")


def keys_of(layout_file: Path) -> list[ET.Element]:
    """The Key elements of one keyboardLayout file, includes resolved, in order."""
    keys: list[ET.Element] = []

    def walk(node: ET.Element) -> None:
        for child in node:
            tag = child.tag.rsplit("}", 1)[-1]
            if tag == "Key":
                keys.append(child)
            elif tag == "include":
                ref = child.get(f"{LATIN}keyboardLayout")
                if ref:
                    walk(ET.parse(XML_DIR / (ref.split("/")[-1] + ".xml")).getroot())
            elif tag == "switch":
                # Both branches carry the same geometry and letters; one is enough.
                walk(next(iter(child)))

    walk(ET.parse(layout_file).getroot())
    return keys


def keyboard_geometry() -> tuple[dict[str, tuple[int, int]], list[list[str]]]:
    """letter -> (row index, key-center x px) from rows_tatar.xml, plus row letters."""
    rows = ET.parse(XML_DIR / "rows_tatar.xml").getroot()
    letter_pos: dict[str, tuple[int, int]] = {}
    row_letters: list[list[str]] = []
    for row_index, row in enumerate(rows):
        if row.tag.rsplit("}", 1)[-1] != "Row":
            continue
        row_fraction = float(row.get(f"{LATIN}keyWidth").rstrip("%p")) / 100
        x = 0.0
        letters: list[str] = []
        for child in row:
            if child.tag.rsplit("}", 1)[-1] == "include":
                members = keys_of(
                    XML_DIR / (child.get(f"{LATIN}keyboardLayout").split("/")[-1] + ".xml")
                )
            else:
                members = [child]
            for member in members:
                width = member.get(f"{LATIN}keyWidth")
                if width == "fillRight":
                    fraction = 1.0 - x
                elif width is not None:
                    fraction = float(width.rstrip("%p")) / 100
                else:
                    fraction = row_fraction
                spec = member.get(f"{LATIN}keySpec")
                if spec:
                    letter_pos[spec] = (
                        row_index,
                        round((x + fraction / 2) * SCREEN_WIDTH_PX),
                    )
                    letters.append(spec)
                x += fraction
        if letters:
            row_letters.append(letters)
    return letter_pos, row_letters


def script_points(script: Path, layout: str, text: str) -> subprocess.CompletedProcess[str]:
    """Run the script's own script_points function; no adb call exists inside it."""
    match = SCRIPT_POINTS_RE.search(script.read_text(encoding="utf-8"))
    if match is None:
        raise AssertionError(f"script_points not found in {script}")
    return subprocess.run(
        ["bash", "-c", match.group(0) + '\nscript_points "$1" "$2"', "bash", layout, text],
        check=False,
        capture_output=True,
        text=True,
    )


def points_of(result: subprocess.CompletedProcess[str]) -> list[tuple[int, int]]:
    if result.returncode != 0:
        raise AssertionError(f"script_points failed: {result.stderr}")
    return [tuple(int(v) for v in line.split()) for line in result.stdout.splitlines()]


class TatarKeyCoordinatesContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.geometry, cls.rows = keyboard_geometry()
        alphabet = "".join(cls.geometry)
        cls.points: dict[Path, dict[str, tuple[int, int]]] = {}
        for script in SCRIPTS:
            result = script_points(script, "tt", alphabet)
            found = points_of(result)
            if len(found) != len(alphabet):
                raise AssertionError(f"{script.name} emitted {len(found)} points")
            cls.points[script] = dict(zip(alphabet, found))

    def row_y(self, script: Path, row_index: int) -> int:
        return self.points[script][self.rows[row_index][0]][1]

    def test_resource_row_has_eight_tatar_extra_keys(self) -> None:
        # The contract input itself: the extra Tatar row is eight 12.5%p keys,
        # ә ө ү җ ң һ ё ъ left to right.
        self.assertEqual(list("әөүҗңһёъ"), self.rows[0])
        self.assertEqual(
            [45, 135, 225, 315, 405, 495, 585, 675],
            [self.geometry[letter][1] for letter in self.rows[0]],
        )

    def test_scripts_tables_match_resources(self) -> None:
        for script in SCRIPTS:
            with self.subTest(script=script.name):
                for letter, (row_index, center_x) in self.geometry.items():
                    with self.subTest(letter=letter):
                        point_x, point_y = self.points[script][letter]
                        self.assertEqual(center_x, point_x)
                        # Same key row in the XML -> same tap y in the script.
                        self.assertEqual(self.row_y(script, row_index), point_y)
                # Rows must be struck top to bottom.
                ys = [self.row_y(script, i) for i in range(len(self.rows))]
                self.assertEqual(sorted(ys), ys)
                self.assertEqual(len(set(ys)), len(ys))

    def test_probe_words_hit_intended_keys(self) -> None:
        # һ and ә are the extra row's keys 5 and 0; м, д, о, н, ь, я come from the
        # ЙЦУКЕН rows. The word sequences must equal, letter by letter, the
        # resource-derived key centers on each script's own row y.
        for script in SCRIPTS:
            for word in ("һәм", "дөнья"):
                with self.subTest(script=script.name, word=word):
                    expected = [
                        (self.geometry[letter][1], self.row_y(script, self.geometry[letter][0]))
                        for letter in word
                    ]
                    self.assertEqual(expected, points_of(script_points(script, "tt", word)))

    def test_scripts_tables_agree_with_each_other(self) -> None:
        for letter in self.geometry:
            self.assertEqual(self.points[DEVICE_PERF][letter], self.points[NETSTATS][letter])

    def test_io_and_hard_sign_are_tt_only_taps(self) -> None:
        for script in SCRIPTS:
            with self.subTest(script=script.name):
                result = script_points(script, "tt", "ёъ")
                self.assertEqual(
                    [(585, self.row_y(script, 0)), (675, self.row_y(script, 0))],
                    points_of(result),
                )
                result = script_points(script, "ru", "ё")
                self.assertNotEqual(0, result.returncode)
                self.assertIn("ё", result.stderr)

    def test_layout_probe_taps_the_top_left_key(self) -> None:
        # probe_layout in the ritual types the top-left key to detect the layout:
        # it must be the extra row's first key center, on the extra row's y.
        body = DEVICE_PERF.read_text(encoding="utf-8")
        match = re.search(r"(?ms)^probe_layout\(\) \{.*?^\}\n", body)
        self.assertIsNotNone(match)
        taps = [(int(x), int(y)) for x, y in re.findall(r"input tap (\d+) (\d+) ", match.group(0))]
        self.assertTrue(taps)
        self.assertEqual((self.geometry["ә"][1], self.row_y(DEVICE_PERF, 0)), taps[0])
        self.assertEqual("45 1110", f"{taps[0][0]} {taps[0][1]}")


if __name__ == "__main__":
    unittest.main()
