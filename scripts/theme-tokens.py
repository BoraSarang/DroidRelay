#!/usr/bin/env python3
"""v0.27 웹 테마 토큰 치환. 순서 고정(복합 패턴 우선). dry-run 지원."""
import sys

PATH = ("apps/android/app/src/main/java/com/borasarang/droidrelay/"
        "relay/WebAssets.kt")

ORDERED = [
    ("background:#2F80ED;border-color:#2F80ED;color:#fff",
     "background:var(--accent);border-color:var(--accent);color:var(--on-accent)"),
    ("background:#2F80ED;color:#fff",
     "background:var(--accent);color:var(--on-accent)"),
    ("color:#fff", "color:var(--text)"),
]

SINGLE = {
    "#0A1428": "var(--bg)",
    "#101E3A": "var(--surface)",
    "#0D1830": "var(--surface)",
    "#181F2E": "var(--surface)",
    "#12203D": "var(--surface2)",
    "#1A2540": "var(--surface2)",
    "#1B2B4D": "var(--track)",
    "#22345A": "var(--line)",
    "#22335A": "var(--line)",
    "#2A3B5C": "var(--line2)",
    "#E6EEF8": "var(--text)",
    "#E8F0FF": "var(--text)",
    "#E0E6F0": "var(--text)",
    "#C7D4F0": "var(--text)",
    "#E3E8EF": "var(--text)",
    "#8FA3BF": "var(--muted)",
    "#9FB4D4": "var(--muted)",
    "#55688C": "var(--dim)",
    "#66788C": "var(--dim)",
    "#A0AABB": "var(--dim)",
    "#2F80ED": "var(--accent)",
    "#8FD8FF": "var(--accent2)",
    "#123A63": "var(--accentbg)",
    "#69E29B": "var(--ok)",
    "#12402F": "var(--okbg)",
    "#FF8A93": "var(--err)",
    "#ff8a93": "var(--err)",
    "#40191C": "var(--danger)",
    "#FFD59E": "var(--warn)",
    "#B36B00": "var(--warn)",
    "#122A4D": "var(--sel)",
    "#0E1B33": "var(--surface)",
    "#1A2233": "var(--line)",
}

EXPECTED_KEEP = {"#22335433", "#1A5C3A", "#1a5c3a", "#3A3312", "#333", "#0A3A2F",
                 "#6FE3C4", "#6a8", "#f86", "#000", "#5c1a1a", "#f96", "#667",
                 "#69e29b"}


def main():
    dry = "--dry" in sys.argv
    src = open(PATH, encoding="utf-8").read()
    total = 0
    for old, new in ORDERED:
        n = src.count(old)
        total += n
        print(f"{old[:52]!r:60} x{n}")
        src = src.replace(old, new)
    for old, new in SINGLE.items():
        n = src.count(old)
        total += n
        if n:
            print(f"{old:12} -> {new:18} x{n}")
        src = src.replace(old, new)
    import re
    rest = set(re.findall(r"#[0-9A-Fa-f]{3,8}\b", src)) - EXPECTED_KEEP
    # rgba()/8자리 등 허용 오탐 제거
    rest = {c for c in rest if len(c) in (4, 7)}
    print(f"총 치환 {total}건, 잔여 6/3자리 hex: {sorted(rest) or '없음'}")
    if not dry:
        open(PATH, "w", encoding="utf-8").write(src)
        print("기록 완료")


if __name__ == "__main__":
    main()
