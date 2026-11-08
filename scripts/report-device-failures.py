"""Names the device tests that failed, from the JUnit XML that connectedAndroidTest writes.

Usage: report-device-failures.py SUMMARY_FILE RESULTS_DIR...

Prints one GitHub error annotation per failure, so the job log and the checks page name the test, and appends a
table of the failures to SUMMARY_FILE (the job's step summary). A run that stopped before writing results says so.
"""
import html
import sys
from pathlib import Path
from xml.etree import ElementTree


def first_line(text):
    return next((line.strip() for line in (text or "").splitlines() if line.strip()), "")


def escape_annotation(text):
    return text.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")


def escape_property(text):
    return escape_annotation(text).replace(":", "%3A").replace(",", "%2C")


def main(summary, directories):
    reports = [(Path(d).name, p) for d in directories for p in sorted(Path(d).rglob("TEST-*.xml"))]
    failures = []
    reported = 0
    for results, report in reports:
        try:
            root = ElementTree.parse(report).getroot()
        except ElementTree.ParseError as error:
            # A run killed while writing leaves a truncated file; the other files still report.
            failures.append((results, report.name, "unreadable", str(error)))
            continue
        for case in root.iter("testcase"):
            if case.find("skipped") is None:
                reported += 1
            for problem in list(case.findall("failure")) + list(case.findall("error")):
                message = first_line(problem.text) or problem.get("message", "")
                failures.append((results, case.get("classname", "").rsplit(".", 1)[-1], case.get("name", ""), message))
    with open(summary, "a", encoding="utf-8") as out:
        if not reports:
            out.write("No device test results were written; the run stopped before the tests reported.\n")
            print("::error title=Device tests::No device test results were written")
            return
        if not failures:
            # A crash or hang between tests leaves the rest unreported rather than failed.
            out.write(f"All {reported} device tests that ran passed, so the run stopped before the rest reported, "
                      "or the job failed before or after the tests.\n")
            print(f"::error title=Device tests::The run stopped after {reported} passing tests")
            return
        out.write(f"### {len(failures)} failed device test{'s' if len(failures) != 1 else ''}\n\n")
        out.write("| Results | Test | First line |\n| --- | --- | --- |\n")
        for results, cls, name, message in failures:
            # The summary is Markdown that drops unknown tags, which would take `expected:<true>` with it.
            cell = html.escape(message[:300]).replace("|", "\\|")
            out.write(f"| {results} | `{cls}.{name}` | {cell} |\n")
            print(f"::error title={escape_property(f'{cls}.{name}')}::{escape_annotation(message[:500])}")


if __name__ == "__main__":
    if len(sys.argv) < 3:
        sys.exit(__doc__)
    main(sys.argv[1], sys.argv[2:])
