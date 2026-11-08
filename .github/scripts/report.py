"""Summarize existing GitHub-run reports. Does not build or execute tests."""
import glob
import os
import xml.etree.ElementTree as ET
from pathlib import Path

files = glob.glob("build/test-results/test/TEST-*.xml")
if not files:
    print("No test reports were generated; inspect the failed compilation/build step.")
    raise SystemExit(0)
roots = [ET.parse(file).getroot() for file in files]
totals = {key: sum(int(root.get(key, 0)) for root in roots)
          for key in ("tests", "failures", "errors", "skipped")}
lines = ["## Aurora verification", "",
         f"Tests: **{totals['tests']}** · failures: **{totals['failures']}** · errors: **{totals['errors']}** · skipped: **{totals['skipped']}**", "",
         "| Package | Line coverage | Branch coverage |", "|---|---:|---:|"]
coverage = Path("build/reports/jacoco/test/jacocoTestReport.xml")
if coverage.exists():
    for package in ET.parse(coverage).getroot().findall("package"):
        counters = {item.get("type"): item for item in package.findall("counter")}
        percentages = []
        for kind in ("LINE", "BRANCH"):
            counter = counters.get(kind)
            covered = int(counter.get("covered", 0)) if counter is not None else 0
            missed = int(counter.get("missed", 0)) if counter is not None else 0
            percentages.append(f"{100 * covered / (covered + missed):.1f}%" if covered + missed else "—")
        lines.append(f"| `{package.get('name')}` | {percentages[0]} | {percentages[1]} |")
lines += ["", "Core + storage build gate: **80% line / 65% branch**.",
          "Paper adapters use mocks; a live in-game acceptance test is still required.", ""]
summary = "\n".join(lines)
print(summary)
if os.environ.get("GITHUB_STEP_SUMMARY"):
    with open(os.environ["GITHUB_STEP_SUMMARY"], "a", encoding="utf-8") as output:
        output.write(summary)
