"""Test duration per class, as a Markdown table, from JUnit XML reports.

Written to the run summary (issue #76): shows the slowest test classes and how
long each shard of the instrumented tests takes.

    python3 test_durations.py "<title>" <dir> [<dir>...]

Reads every *.xml file under the given directories (recursively) and prints the
table on stdout. Without any report, prints a line saying so.
"""

import sys
import xml.etree.ElementTree as ET
from collections import defaultdict
from pathlib import Path


def durations(dirs):
    """{class: [seconds, tests, skipped, failures]} over every <testcase> found."""
    by_class = defaultdict(lambda: [0.0, 0, 0, 0])
    for d in dirs:
        for f in sorted(Path(d).rglob("*.xml")):
            try:
                root = ET.parse(f).getroot()
            except ET.ParseError:
                continue
            for case in root.iter("testcase"):
                row = by_class[case.get("classname", "?")]
                row[0] += float(case.get("time") or 0)
                row[1] += 1
                if case.find("skipped") is not None:
                    row[2] += 1
                if case.find("failure") is not None or case.find("error") is not None:
                    row[3] += 1
    return by_class


def fmt(seconds):
    m, s = divmod(round(seconds), 60)
    return f"{m} min {s:02d} s" if m else f"{s} s"


def table(title, by_class):
    if not by_class:
        return f"### {title}\n\nAucun rapport JUnit trouvé.\n"
    total = sum(r[0] for r in by_class.values())
    lines = [
        f"### {title}",
        "",
        f"Total des tests : **{fmt(total)}** ({sum(r[1] for r in by_class.values())} tests)",
        "",
        "| Classe | Durée | Tests | Sautés | Échecs |",
        "|---|---:|---:|---:|---:|",
    ]
    for name, (secs, n, skipped, failed) in sorted(by_class.items(), key=lambda kv: -kv[1][0]):
        lines.append(f"| {name.rsplit('.', 1)[-1]} | {fmt(secs)} | {n} | {skipped} | {failed} |")
    return "\n".join(lines) + "\n"


if __name__ == "__main__":
    if len(sys.argv) < 3:
        sys.exit(__doc__)
    print(table(sys.argv[1], durations(sys.argv[2:])))
