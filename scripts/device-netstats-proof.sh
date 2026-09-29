#!/bin/bash
# device-netstats-proof.sh — the repeatable runtime no-traffic proof (S7 of
# docs/OPTIMIZE-SECURITY-PLAN-2026-09-29.md, NETWORK row of docs/THREAT-MODEL.md).
#
# The offline claim's dynamic leg: snapshot the per-UID traffic counters of the
# package under test, drive a scripted ~2-minute mixed session (tt typing with
# suggestion accepts, ru typing, emoji panel + emoji search), snapshot again,
# and assert the rx/tx delta is EXACTLY zero bytes. One machine-readable
# RESULT|... line per check goes to stdout and $OUTDIR/result.txt; raw evidence
# (both full snapshots, the UID slices, their diff, screenshots) lands in
# $OUTDIR.
#
# Counter source on the POCO C71 (Android 15, kernel with eBPF traffic
# accounting): `dumpsys netstats detail`, section "UID stats:" (tag=0x0
# NetworkStatsHistory buckets per iface/uid/set). Two fallbacks are documented
# but NOT usable here: /proc/net/xt_qtaguid/stats no longer exists on this
# kernel (probed at runtime, recorded in xtaguid-probe.txt), and the BPF
# mUidCounterSetMap entry that may appear for a foreground UID carries only the
# counter-set (foreground/background), no byte counters. A second, independent
# layer IS available in the same dump: BPF map content -> mAppUidStatsMap
# (per-UID rxBytes/txBytes from eBPF); its delta is reported as the
# corroborating bpf.zero-traffic line. Stale history buckets for a recycled UID
# cannot false-FAIL the proof: the verdict is on the before->after DELTA.
#
# Force-stop discipline (same lesson as device-perf-ritual.sh): this script
# never force-stops anything — the only process control is `ime set`, which
# never resets the IME selection (HyperOS resets the default IME only when the
# SELECTED package is force-stopped). The package under test is exercised warm,
# the realistic user scenario. The as-found default IME is restored by the
# EXIT trap either way.
#
# Key/strip/panel coordinates are the perf ritual's 720x1640 calibration
# (tt rows y=1110/1206/1301/1396, ru rows y=1153/1258/1363, bottom row y=1490,
# globe x=216, comma long-press x=144) plus the strip band y~1020 (four cells
# since 2026-09-27 -> centres x=90/270/450/630) and the emoji-panel geometry
# verified on the 2026-09-29 screencaps of this script: tab bar (with the search
# cell its rightmost slot, centre ~(670,1055)) at the panel top, recents row
# centre y~1201, first grid row centre y~1355. Panel taps are session content,
# not verdict input: only the byte deltas decide.
#
# Flags:
#   --serial <id>     device serial (default: $ANDROID_SERIAL, else the single
#                     online device, else the POCO C71 serial)
#   --pkg <package>   package under test (default org.tatarkeyboard.ime.debug)
#   --outdir <path>   evidence directory (default build/netstats-proof)
#   --min-seconds <n> lower bound of the session length (default 120; the
#                     scripted content usually covers it, the remainder is
#                     padded with the keyboard up)
#
# Exit code: 0 with no FAIL line, 1 otherwise. Idempotent: the pre-run state
# (default IME, stay-on-while-plugged, screen awake/asleep) is restored by an
# EXIT trap.

set -uo pipefail

POCO_SERIAL="9b0100593053303634000b3326e0cb"
SERIAL="${ANDROID_SERIAL:-}"
PKG="org.tatarkeyboard.ime.debug"
OUTDIR=""
MIN_SECONDS=120
ADB="${ADB:-$HOME/Android/Sdk/platform-tools/adb}"

while [ $# -gt 0 ]; do
    case "$1" in
        --serial) SERIAL="$2"; shift 2 ;;
        --pkg) PKG="$2"; shift 2 ;;
        --outdir) OUTDIR="$2"; shift 2 ;;
        --min-seconds) MIN_SECONDS="$2"; shift 2 ;;
        *) echo "unknown flag: $1" >&2; exit 2 ;;
    esac
done

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUTDIR="${OUTDIR:-$ROOT/build/netstats-proof}"
mkdir -p "$OUTDIR"
RESULTS="$OUTDIR/result.txt"
: > "$RESULTS"
FAILURES=0

SETUP_ACTIVITY="rkr.simplekeyboard.inputmethod.latin.setup.SetupActivity"

if [ -z "$SERIAL" ]; then
    SERIAL=$("$ADB" devices | awk '$2 == "device" {print $1; exit}')
fi
SERIAL="${SERIAL:-$POCO_SERIAL}"

A() { "$ADB" -s "$SERIAL" "$@"; }
SHELL() { A shell "$@"; }
SHOT() { A exec-out screencap -p > "$OUTDIR/$1" 2>/dev/null; }

result() { # result PASS|FAIL|SKIP|INFO <check> <detail>
    local line="RESULT|$1|$2|$3"
    echo "$line"
    echo "$line" >> "$RESULTS"
    [ "$1" = "FAIL" ] && FAILURES=$((FAILURES + 1)) || true
}
log() { echo "netstats-proof: $*" >&2; }

# ── state capture + restore ───────────────────────────────────────────────────

PREV_IME=$(SHELL settings get secure default_input_method 2>/dev/null | tr -d '\r')
PREV_STAYON=$(SHELL settings get global stay_on_while_plugged_in 2>/dev/null | tr -d '\r')
PREV_AWAKE=$(SHELL dumpsys power 2>/dev/null | grep -oP 'mWakefulness=\K\w+' | head -1)
log "state on entry: ime=$PREV_IME stayon=$PREV_STAYON wakefulness=$PREV_AWAKE"

WOKEN=0
restore() {
    log "restoring device state"
    [ -n "$PREV_IME" ] && SHELL ime set "$PREV_IME" >/dev/null 2>&1 || true
    case "$PREV_STAYON" in
        ''|null|0) : ;;                       # was off; we never turned it off
        *) SHELL settings put global stay_on_while_plugged_in "$PREV_STAYON" >/dev/null 2>&1 || true ;;
    esac
    if [ "$WOKEN" = 1 ] && [ "$PREV_AWAKE" != "Awake" ]; then
        SHELL input keyevent KEYCODE_SLEEP >/dev/null 2>&1 || true
    fi
    SHELL input keyevent KEYCODE_HOME >/dev/null 2>&1 || true
}
trap restore EXIT

# Screen on and kept on for the duration (USB power).
[ "$PREV_STAYON" = "0" ] || [ -z "$PREV_STAYON" ] || [ "$PREV_STAYON" = "null" ] \
    && SHELL svc power stayon true >/dev/null 2>&1 || true
SHELL "input keyevent KEYCODE_WAKEUP; input keyevent 82" >/dev/null 2>&1
WOKEN=1
sleep 1

# ── preflight ─────────────────────────────────────────────────────────────────

SHELL true >/dev/null 2>&1 || { result FAIL device "serial $SERIAL unreachable"; exit 1; }
MODEL=$(SHELL getprop ro.product.model 2>/dev/null | tr -d '\r')
ANDROID_REL=$(SHELL getprop ro.build.version.release 2>/dev/null | tr -d '\r')
WH=$(SHELL wm size 2>/dev/null | grep -oP '\d+x\d+' | head -1)

SHELL pm path "$PKG" >/dev/null 2>&1 || { result FAIL package "$PKG not installed"; exit 1; }

# UID resolution: `dumpsys package` appId line first, `cmd package list
# packages -U` as the fallback.
APP_UID=$(SHELL dumpsys package "$PKG" 2>/dev/null | grep -m1 -oP 'appId=\K\d+')
if [ -z "$APP_UID" ]; then
    APP_UID=$(SHELL cmd package list packages -U 2>/dev/null | tr -d '\r' \
        | grep -oP "^package:\Q$PKG\E uid:\K\d+")
fi
[ -n "$APP_UID" ] || { result FAIL uid "could not resolve the uid of $PKG"; exit 1; }

IME_ID=$(SHELL ime list -a -s 2>/dev/null | tr -d '\r' | grep "^$PKG/" | head -1)
[ -n "$IME_ID" ] || { result FAIL ime-id "ime list shows no IME of $PKG"; exit 1; }
SHELL ime enable "$IME_ID" >/dev/null 2>&1 || true
RUN_AS_OK=0
A shell "run-as $PKG true" >/dev/null 2>&1 && RUN_AS_OK=1
VER=$(SHELL dumpsys package "$PKG" 2>/dev/null | grep -m1 versionName | grep -oP '= *\K\S+')

# The xt_qtaguid fallback probe: absent on this kernel, recorded for the archive.
SHELL "cat /proc/net/xt_qtaguid/stats" > "$OUTDIR/xtaguid-probe.txt" 2>&1
XTAGUID="absent"
grep -q "^uid" "$OUTDIR/xtaguid-probe.txt" && XTAGUID="present"

{
    echo "date: $(date -Iseconds)"
    echo "serial: $SERIAL  model: $MODEL  android: $ANDROID_REL  screen: $WH"
    echo "package: $PKG  versionName: $VER  uid: $APP_UID  run-as: $RUN_AS_OK"
    echo "ime: $IME_ID  prev ime: $PREV_IME  xt_qtaguid: $XTAGUID"
    if [ -d "$ROOT/.git" ]; then
        dirty=clean; [ -n "$(git -C "$ROOT" status --porcelain)" ] && dirty=dirty
        echo "git: $(git -C "$ROOT" rev-parse --short HEAD) $dirty"
    fi
} > "$OUTDIR/meta.txt"
result INFO device "serial=$SERIAL model=$MODEL android=$ANDROID_REL screen=$WH pkg=$PKG version=$VER"
result INFO counter-source "primary='dumpsys netstats detail' UID-stats section; xt_qtaguid=$XTAGUID (fallback unusable on this kernel); corroborating=BPF mAppUidStatsMap; mUidCounterSetMap carries no byte counters"

if [ "$WH" != "720x1640" ]; then
    log "WARNING: screen $WH, key coordinates are calibrated for 720x1640"
    result INFO screen-size "screen $WH differs from the 720x1640 calibration; taps may miss"
fi

# Suggestions make the session heavier (dictionary loads, strip updates); the
# debuggable package gets the same surgical run-as pref write the perf ritual
# does. A non-debuggable package keeps whatever state it has.
PREFS_PATH="/data/user_de/0/$PKG/shared_prefs/${PKG}_preferences.xml"
if [ "$RUN_AS_OK" = 1 ]; then
    pref=$(SHELL "run-as $PKG cat $PREFS_PATH" 2>/dev/null | tr -d '\r' \
        | grep -oP 'name="pref_tatar_suggestions" value="\K[^"]*' | head -1 || true)
    if [ "$pref" != "true" ]; then
        A shell "run-as $PKG cat $PREFS_PATH" 2>/dev/null | tr -d '\r' > "$OUTDIR/prefs-before.xml" || true
        python3 - "$OUTDIR/prefs-before.xml" > "$OUTDIR/prefs-new.xml" <<'PYEOF'
import re
import sys
from pathlib import Path
source = Path(sys.argv[1])
xml = source.read_text(encoding="utf-8") if source.is_file() else ""
for key in ("pref_tatar_suggestions", "pref_tatar_suggestions_offer_spent"):
    entry = f'<boolean name="{key}" value="true" />'
    pattern = re.compile(rf'<boolean name="{key}" value="[^"]*" ?/>')
    if pattern.search(xml):
        xml = pattern.sub(entry, xml)
    elif "</map>" in xml:
        xml = xml.replace("</map>", f"    {entry}\n</map>", 1)
    else:
        xml = ""
if not xml:
    xml = ("<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map>\n"
           + "".join(f'    <boolean name="{k}" value="true" />\n'
                     for k in ("pref_tatar_suggestions", "pref_tatar_suggestions_offer_spent"))
           + "</map>\n")
sys.stdout.write(xml)
PYEOF
        A shell "run-as $PKG sh -c 'mkdir -p \$(dirname $PREFS_PATH) && cat > $PREFS_PATH'" \
            < "$OUTDIR/prefs-new.xml" \
            && result INFO suggestions-pref "pref_tatar_suggestions=true written via run-as (was '${pref:-missing}')" \
            || result INFO suggestions-pref "pref write failed; keeping device state"
    else
        result INFO suggestions-pref "pref_tatar_suggestions already true"
    fi
else
    result INFO suggestions-pref "package not debuggable; suggestions state untouched"
fi

# ── helpers ───────────────────────────────────────────────────────────────────

current_ime() { SHELL settings get secure default_input_method 2>/dev/null | tr -d '\r'; }

keyboard_shown() {
    SHELL dumpsys input_method 2>/dev/null | grep -q "mInputShown=true"
}
wait_keyboard() { # wait_keyboard <seconds> -> 0 if mInputShown=true in time
    local deadline=$((SECONDS + $1))
    while [ $SECONDS -lt $deadline ]; do
        keyboard_shown && return 0
        sleep 0.5
    done
    return 1
}

dump_ui() {
    SHELL uiautomator dump /data/local/tmp/proof-ui.xml >/dev/null 2>&1
    A exec-out cat /data/local/tmp/proof-ui.xml 2>/dev/null | tr -d '\r'
}
# tap_node <resource-id-substring> -> taps the centre of the first match
tap_node() {
    local dump bounds x y
    dump=$(dump_ui)
    bounds=$(echo "$dump" | grep -oP "$1[^>]*bounds=\"\K[^\"]*" | head -1)
    [ -n "$bounds" ] || return 1
    read -r x y <<<"$(python3 -c "
import re
m = [int(v) for v in re.findall(r'\d+', '$bounds')]
print((m[0]+m[2])//2, (m[1]+m[3])//2)")"
    SHELL input tap "$x" "$y" >/dev/null 2>&1
}

# raise_keyboard_over_setup: fresh SetupActivity (keyboard down -> try-it field
# visible), tap the field, wait for the IME window.
raise_keyboard_over_setup() {
    SHELL am start --activity-clear-task -n "$PKG/$SETUP_ACTIVITY" >/dev/null 2>&1
    sleep 3
    tap_node setup_test_field || return 1
    wait_keyboard 10
}

# Key coordinates via python (no host-locale dependence for the Cyrillic
# char splitting). Emits "x y" per event. The 720x1640 calibration of
# device-perf-ritual.sh.
script_points() { # script_points tt|ru "word1 word2 ..."
    python3 - "$1" "$2" <<'PYEOF'
import sys
layout, text = sys.argv[1], sys.argv[2]
TT = {
    "ә": (60, 1110), "ө": (180, 1110), "ү": (300, 1110), "җ": (420, 1110),
    "ң": (540, 1110), "һ": (660, 1110),
    "й": (33, 1206), "ц": (98, 1206), "у": (164, 1206), "к": (229, 1206),
    "е": (295, 1206), "н": (360, 1206), "г": (425, 1206), "ш": (491, 1206),
    "щ": (556, 1206), "з": (622, 1206), "х": (687, 1206),
    "ф": (33, 1301), "ы": (98, 1301), "в": (164, 1301), "а": (229, 1301),
    "п": (295, 1301), "р": (360, 1301), "о": (425, 1301), "л": (491, 1301),
    "д": (556, 1301), "ж": (622, 1301), "э": (687, 1301),
    "я": (109, 1396), "ч": (172, 1396), "с": (235, 1396), "м": (297, 1396),
    "и": (360, 1396), "т": (423, 1396), "ь": (485, 1396), "б": (548, 1396),
    "ю": (611, 1396), " ": (396, 1490),
}
RU = {k: (x, {1110: 1153, 1206: 1153, 1301: 1258, 1396: 1363, 1490: 1490}[y])
      for k, (x, y) in TT.items()}
RU.update({"й": (33, 1153), "ц": (98, 1153), "у": (164, 1153), "к": (229, 1153),
           "е": (295, 1153), "н": (360, 1153), "г": (425, 1153), "ш": (491, 1153),
           "щ": (556, 1153), "з": (622, 1153), "х": (687, 1153),
           "ф": (33, 1258), "ы": (98, 1258), "в": (164, 1258), "а": (229, 1258),
           "п": (295, 1258), "р": (360, 1258), "о": (425, 1258), "л": (491, 1258),
           "д": (556, 1258), "ж": (622, 1258), "э": (687, 1258),
           "я": (109, 1363), "ч": (172, 1363), "с": (235, 1363), "м": (297, 1363),
           "и": (360, 1363), "т": (423, 1363), "ь": (485, 1363), "б": (548, 1363),
           "ю": (611, 1363), " ": (396, 1490)})
table = TT if layout == "tt" else RU
for ch in text:
    if ch not in table:
        sys.exit(f"no coordinate for {ch!r} on layout {layout}")
    print(*table[ch])
PYEOF
}

type_text() { # type_text tt|ru "text" <gap-seconds>
    local gap="$3" pts line x y
    # Materialize the point list BEFORE the tap loop: `adb shell` inside a
    # `while read` pipeline would eat the loop's stdin and silently drop every
    # point after the first few (the perf ritual's 2026-09-29 lesson).
    pts=$(script_points "$1" "$2") || { log "script_points failed for layout $1"; return 1; }
    while IFS= read -r line; do
        x="${line% *}"; y="${line#* }"
        SHELL input tap "$x" "$y" </dev/null >/dev/null 2>&1
        sleep "$gap"
    done <<< "$pts"
}

# switch the subtype via the globe key with pref feedback (the cycle order is
# MRU-rotated, so blind tap counts are meaningless).
globe_to() { # globe_to tt|ru|en -> 0 when pref_current_subtype starts with the code
    local want="$1" i poll pref=""
    [ "$RUN_AS_OK" = 1 ] || return 2
    for i in 1 2 3 4 5; do
        for poll in 1 2 3; do
            pref=$(SHELL "run-as $PKG cat $PREFS_PATH" 2>/dev/null | tr -d '\r' \
                | grep -oP 'name="pref_current_subtype"[^>]*>\K[^<]*' | head -1 || true)
            case "$pref" in "$want"*) return 0 ;; esac
            sleep 0.8
        done
        SHELL input tap 216 1490 </dev/null >/dev/null 2>&1   # globe, y shared by tt/ru bottom rows
        sleep 1.2
    done
    return 1
}

# ── counter snapshots ─────────────────────────────────────────────────────────

snapshot_netstats() { # snapshot_netstats before|after
    SHELL dumpsys netstats detail > "$OUTDIR/netstats-$1.txt" 2>/dev/null
    [ -s "$OUTDIR/netstats-$1.txt" ]
}

# uid_slice <netstats-file> <uid> -> the UID's blocks of the "UID stats:"
# section (ident line + its st= bucket lines), for the archived diff.
uid_slice() {
    python3 - "$1" "$2" <<'PYEOF'
import re
import sys
text = open(sys.argv[1], encoding="utf-8", errors="replace").read()
uid = sys.argv[2]
in_section = False
keep = False
for line in text.splitlines():
    if re.match(r"^UID stats:\s*$", line):
        in_section, keep = True, False
        continue
    if in_section and re.match(r"^\S", line) and not line.startswith("ident="):
        break  # next unindented section header ends UID stats
    if not in_section:
        continue
    m = re.match(r"^\s*ident=.*\buid=(\d+)\b", line)
    if m:
        keep = m.group(1) == uid
    if keep:
        print(line)
PYEOF
}

# uid_bytes <netstats-file> <uid> -> "rx tx buckets": sums over all st= buckets
# of the UID's blocks in the "UID stats:" section (all ifaces, both DEFAULT and
# FOREGROUND sets; a byte is attributed to exactly one set, so no double count).
uid_bytes() {
    python3 - "$1" "$2" <<'PYEOF'
import re
import sys
text = open(sys.argv[1], encoding="utf-8", errors="replace").read()
uid = sys.argv[2]
in_section = False
keep = False
rx = tx = buckets = 0
for line in text.splitlines():
    if re.match(r"^UID stats:\s*$", line):
        in_section, keep = True, False
        continue
    if in_section and re.match(r"^\S", line) and not line.startswith("ident="):
        break
    if not in_section:
        continue
    m = re.match(r"^\s*ident=.*\buid=(\d+)\b", line)
    if m:
        keep = m.group(1) == uid
        continue
    if keep:
        b = re.search(r"\bst=\d+ rb=(\d+) rp=\d+ tb=(\d+)", line)
        if b:
            rx += int(b.group(1))
            tx += int(b.group(2))
            buckets += 1
print(rx, tx, buckets)
PYEOF
}

# bpf_bytes <netstats-file> <uid> -> "rx tx present": the eBPF mAppUidStatsMap
# row for the UID ("uid rxBytes rxPackets txBytes txPackets"); absent row = 0 0.
bpf_bytes() {
    python3 - "$1" "$2" <<'PYEOF'
import re
import sys
text = open(sys.argv[1], encoding="utf-8", errors="replace").read()
uid = sys.argv[2]
m = re.search(r"^  mAppUidStatsMap:\n(.*?)(?=^  m\w|^---|^\S)", text, re.M | re.S)
if not m:
    print("0 0 map-absent")
    sys.exit(0)
row = re.search(rf"^\s*{re.escape(uid)} (\d+) \d+ (\d+) \d+\s*$", m.group(1), re.M)
if row:
    print(row.group(1), row.group(2), "row")
else:
    print("0 0 no-row")
PYEOF
}

# ── the run ───────────────────────────────────────────────────────────────────
# ours must be the selected IME for the session; `ime set` is safe (it never
# resets the selection and never force-stops anything).

cur=$(current_ime)
[ "$cur" = "$IME_ID" ] || SHELL ime set "$IME_ID" >/dev/null 2>&1
sleep 1

snapshot_netstats before || { result FAIL snapshot "before-snapshot of 'dumpsys netstats detail' failed"; exit 1; }
read -r RX0 TX0 BUCKETS0 <<<"$(uid_bytes "$OUTDIR/netstats-before.txt" "$APP_UID")"
read -r BRX0 BTX0 BPF0 <<<"$(bpf_bytes "$OUTDIR/netstats-before.txt" "$APP_UID")"
uid_slice "$OUTDIR/netstats-before.txt" "$APP_UID" > "$OUTDIR/uid-slice-before.txt"
log "before: uid=$APP_UID netstats rx=$RX0 tx=$TX0 buckets=$BUCKETS0; bpf rx=$BRX0 tx=$BTX0 ($BPF0)"

SESSION_START=$SECONDS
EVENTS=""

if raise_keyboard_over_setup; then
    SHOT proof-keyboard.png
    if globe_to tt; then
        log "typing the tt half"
        type_text tt "сәләм дөнья мин сине яратам дус һәм белән татар теле дәүләт китап укытучы мәктәп иртә кич бүген әти әни бала " 0.12 \
            && EVENTS="$EVENTS tt-words"
        # suggestion accepts: word + space, then a strip cell (four cells,
        # centres x=90/270/450/630 in the strip band y~1020)
        type_text tt "татар " 0.15 && SHELL input tap 270 1020 </dev/null >/dev/null 2>&1 && EVENTS="$EVENTS tt-cell2"
        sleep 0.8
        type_text tt "сәләм " 0.15 && SHELL input tap 90 1020 </dev/null >/dev/null 2>&1 && EVENTS="$EVENTS tt-cell1"
        sleep 0.8
        if globe_to ru; then
            log "typing the ru half"
            type_text ru "привет время дом работа город улица книга школа друг семья день ночь " 0.12 \
                && EVENTS="$EVENTS ru-words"
        else
            result INFO session "ru half skipped: globe switch unconfirmed"
        fi
    else
        result INFO session "tt layout unconfirmed; typing on whatever layout is up"
        type_text tt "сәләм дөнья мин сине яратам дус " 0.12 || true
    fi

    # emoji panel: long-press the comma key (bottom row), one grid commit,
    # then the search cell (rightmost tab slot) + a tt query + a result tap
    SHELL input swipe 144 1490 144 1490 900 </dev/null >/dev/null 2>&1
    sleep 3
    SHOT proof-emoji-panel.png
    SHELL input tap 45 1201 </dev/null >/dev/null 2>&1 && EVENTS="$EVENTS emoji-commit"
    sleep 1
    SHELL input tap 670 1055 </dev/null >/dev/null 2>&1 && EVENTS="$EVENTS emoji-search-open"
    sleep 2
    SHOT proof-emoji-search.png
    globe_to tt || result INFO session "tt layout unconfirmed for the emoji-search query"
    type_text tt "кояш" 0.15 && EVENTS="$EVENTS emoji-search-query"
    sleep 1
    SHELL input tap 90 1020 </dev/null >/dev/null 2>&1 && EVENTS="$EVENTS emoji-search-commit"
    sleep 1
    SHELL input keyevent KEYCODE_BACK >/dev/null 2>&1   # search -> letters / panel -> letters
    sleep 1
    keyboard_shown && SHELL input keyevent KEYCODE_BACK >/dev/null 2>&1   # hide the IME window
else
    result FAIL session "keyboard did not raise over SetupActivity"
fi

# Pad to the documented ~2-minute floor with the process alive, then re-snapshot.
elapsed=$((SECONDS - SESSION_START))
if [ "$elapsed" -lt "$MIN_SECONDS" ]; then
    log "padding $((MIN_SECONDS - elapsed)) s to the $MIN_SECONDS s floor"
    sleep $((MIN_SECONDS - elapsed))
fi
elapsed=$((SECONDS - SESSION_START))

snapshot_netstats after || { result FAIL snapshot "after-snapshot of 'dumpsys netstats detail' failed"; exit 1; }
read -r RX1 TX1 BUCKETS1 <<<"$(uid_bytes "$OUTDIR/netstats-after.txt" "$APP_UID")"
read -r BRX1 BTX1 BPF1 <<<"$(bpf_bytes "$OUTDIR/netstats-after.txt" "$APP_UID")"
uid_slice "$OUTDIR/netstats-after.txt" "$APP_UID" > "$OUTDIR/uid-slice-after.txt"
diff "$OUTDIR/uid-slice-before.txt" "$OUTDIR/uid-slice-after.txt" > "$OUTDIR/diff.txt" || true
SHOT proof-end.png

result INFO session "duration_s=$elapsed events:$(echo $EVENTS | tr ' ' ',')"
result INFO counters "uid=$APP_UID netstats rx=$RX0->$RX1 tx=$TX0->$TX1 buckets=$BUCKETS0->$BUCKETS1; bpf rx=$BRX0->$BRX1 tx=$BTX0->$BTX1"

DRX=$((RX1 - RX0)); DTX=$((TX1 - TX0))
if [ "$DRX" = 0 ] && [ "$DTX" = 0 ]; then
    result PASS netstats.zero-traffic "uid=$APP_UID rx=$RX0->$RX1 tx=$TX0->$TX1 delta=0 duration_s=$elapsed evidence=netstats-before.txt+netstats-after.txt+diff.txt"
else
    result FAIL netstats.zero-traffic "uid=$APP_UID rx=$RX0->$RX1 tx=$TX0->$TX1 delta_rx=$DRX delta_tx=$DTX (see diff.txt)"
fi

BDRX=$((BRX1 - BRX0)); BDTX=$((BTX1 - BTX0))
if [ "$BDRX" = 0 ] && [ "$BDTX" = 0 ]; then
    result PASS bpf.zero-traffic "mAppUidStatsMap uid=$APP_UID rx=$BRX0->$BRX1 ($BPF0->$BPF1) tx=$BTX0->$BTX1 delta=0"
else
    result FAIL bpf.zero-traffic "mAppUidStatsMap uid=$APP_UID rx=$BRX0->$BRX1 tx=$BTX0->$BTX1 delta_rx=$BDRX delta_tx=$BDTX"
fi

log "done; failures: $FAILURES"
[ "$FAILURES" = 0 ]
