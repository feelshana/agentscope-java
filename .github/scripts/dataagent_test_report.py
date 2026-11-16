"""Summarize one clean Surefire run and require the restored integration suites to execute."""

import argparse
from collections import Counter
import os
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET


REQUIRED_SUITES = {
    "io.agentscope.dataagent.web.persistence.jpa.JpaPersistenceIntegrationTest": 4,
    "io.agentscope.dataagent.web.persistence.jpa.H2DemoSeedTest": 1,
    "io.agentscope.dataagent.web.marketplace.MarketContributionServiceTest": 7,
}


def cell(value):
    """Keep report messages inside a Markdown table cell, without rendering raw markup."""
    return str(value).replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace(
        "|", "&#124;"
    ).replace("\r", " ").replace("\n", " ")


def skip_category(node):
    reason = " ".join([node.get("message", ""), node.get("type", ""), node.text or ""])
    if re.search(r"@Disabled|DisabledCondition|is disabled", reason, re.IGNORECASE):
        return "Explicitly disabled", reason
    if re.search(r"TestAbortedException|Assumption|System property|Environment variable", reason,
                 re.IGNORECASE):
        return "Conditional / assumption", reason
    return "Unclassified", reason or "No reason recorded"


def summarize(directory, required=REQUIRED_SUITES):
    totals = Counter(tests=0, failures=0, errors=0, skipped=0)
    suites = {}
    skips = Counter()
    problems = []
    files = sorted(Path(directory).glob("TEST-*.xml"))
    if not files:
        problems.append("No Surefire XML reports found; this does not count as a passing run.")
    for path in files:
        try:
            suite = ET.parse(path).getroot()
            if suite.tag != "testsuite":
                raise ValueError("expected a Surefire testsuite")
            name = suite.attrib["name"]
            if name in suites:
                raise ValueError("duplicate suite: " + name)
            cases = suite.findall("testcase")
            counts = Counter(
                tests=len(cases),
                failures=sum(case.find("failure") is not None for case in cases),
                errors=sum(case.find("error") is not None for case in cases),
                skipped=sum(case.find("skipped") is not None for case in cases),
            )
            for key, count in counts.items():
                if int(suite.get(key, "0")) != count:
                    raise ValueError("suite counters do not match test cases: " + key)
            suites[name] = counts
            totals.update(counts)
            for case in cases:
                skipped = case.find("skipped")
                if skipped is not None:
                    category, reason = skip_category(skipped)
                    skips[(category, name, reason)] += 1
                    if category != "Conditional / assumption":
                        problems.append(f"{name} has a {category.lower()} test: {case.get('name')}")
        except (ET.ParseError, OSError, ValueError, KeyError) as error:
            problems.append(f"Invalid report {path.name}: {error}")
    if totals["failures"] or totals["errors"]:
        problems.append("Test failures or errors were recorded.")
    for name, minimum in required.items():
        counts = suites.get(name)
        if counts is None:
            problems.append("Required suite was not discovered: " + name)
        elif counts["tests"] < minimum or any(counts[key] for key in ("failures", "errors", "skipped")):
            problems.append(f"Required suite must execute at least {minimum} passing tests: {name}")

    lines = ["## DataAgent backend test results", "",
             "| Tests | Failures | Errors | Skipped |", "| --- | --- | --- | --- |",
             "| {tests} | {failures} | {errors} | {skipped} |".format(**totals), "",
             "### Required integration suites", "",
             "| Suite | Tests | Skipped |", "| --- | --- | --- |"]
    for name in required:
        counts = suites.get(name)
        lines.append(f"| {cell(name)} | {counts['tests'] if counts else 'MISSING'} | "
                     f"{counts['skipped'] if counts else '—'} |")
    if skips:
        lines += ["", "### Skips (not passes)", "",
                  "| Category | Suite | Count | Reason |", "| --- | --- | --- | --- |"]
        for (category, name, reason), count in sorted(skips.items()):
            lines.append(f"| {cell(category)} | {cell(name)} | {count} | {cell(reason[:500])} |")
    lines += ["", "Real LLM, Wren, MySQL and Docker checks require their explicit fixtures. "
              "This report does not certify live services or production deployment."]
    if problems:
        lines += ["", "### Checks failed", ""] + ["- " + cell(problem) for problem in problems]
    return "\n".join(lines) + "\n", not problems


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("reports", type=Path)
    args = parser.parse_args()
    report, passed = summarize(args.reports)
    print(report)
    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        with open(summary, "a", encoding="utf-8") as output:
            output.write(report)
    return 0 if passed else 1


if __name__ == "__main__":
    sys.exit(main())
