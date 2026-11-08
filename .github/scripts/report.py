"""Summarize existing GitHub-run reports. Does not build or execute tests."""
import os
import xml.etree.ElementTree as ET
from pathlib import Path


def parse_report(path, warnings):
    try:
        return ET.parse(path).getroot()
    except (ET.ParseError, OSError) as error:
        warnings.append(f"Could not read `{path}`: {type(error).__name__}.")
        return None


def summary():
    warnings = []
    files = sorted(Path("build/test-results/test").glob("TEST-*.xml"))
    roots = [root for path in files if (root := parse_report(path, warnings)) is not None]
    lines = ["## Aurora verification", ""]
    if roots:
        try:
            totals = {key: sum(int(root.get(key, "0")) for root in roots)
                      for key in ("tests", "failures", "errors", "skipped")}
            lines += [f"Tests: **{totals['tests']}** · failures: **{totals['failures']}** · "
                      f"errors: **{totals['errors']}** · skipped: **{totals['skipped']}**", ""]
        except ValueError:
            warnings.append("Invalid JUnit counters; test totals are unavailable.")
    else:
        lines += ["No readable test reports were generated; inspect the compilation/build step.", ""]

    coverage = Path("build/reports/jacoco/test/jacocoTestReport.xml")
    coverage_root = parse_report(coverage, warnings) if coverage.exists() else None
    if coverage_root is not None:
        lines += ["| Package | Line coverage | Branch coverage |", "|---|---:|---:|"]
        for package in sorted(coverage_root.findall("package"), key=lambda node: node.get("name", "")):
            counters = {item.get("type"): item for item in package.findall("counter")}
            percentages = []
            for kind in ("LINE", "BRANCH"):
                counter = counters.get(kind)
                try:
                    covered = int(counter.get("covered", "0")) if counter is not None else 0
                    missed = int(counter.get("missed", "0")) if counter is not None else 0
                    if covered < 0 or missed < 0:
                        raise ValueError("negative coverage counter")
                    percentages.append(f"{100 * covered / (covered + missed):.1f}%" if covered + missed else "—")
                except ValueError:
                    percentages.append("unavailable")
                    warnings.append(f"Invalid {kind} coverage counter in a package report.")
            name = package.get("name", "unknown").replace("|", "\\|").replace("`", "'")
            lines.append(f"| `{name}` | {percentages[0]} | {percentages[1]} |")
    else:
        lines.append("Coverage report unavailable; no coverage pass is implied.")

    if warnings:
        lines += ["", "### Report warnings", *[f"- {warning}" for warning in warnings]]
    lines += ["", "Core + storage build gate: **80% line / 65% branch** (see the build step for its result).",
              "Paper adapters use mocks; a live in-game acceptance test is still required.", ""]
    return "\n".join(lines)


if __name__ == "__main__":
    text = summary()
    print(text)
    destination = os.environ.get("GITHUB_STEP_SUMMARY")
    if destination:
        with open(destination, "a", encoding="utf-8") as output:
            output.write(text + "\n")
