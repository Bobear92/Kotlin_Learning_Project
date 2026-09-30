"""Turn Gradle's JUnit XML results into a readable Markdown test report.

Used by the GitHub Actions workflow (.github/workflows/tests.yml), which appends the output to the
run's summary page, so a test run can be read (and presented) without digging through logs.
Also works locally after a build:

    python scripts/test_report.py build/test-results/test
    python scripts/test_report.py build/test-results/loadTest

The report groups the test classes by what they prove (the oversell protection tests first),
shows every failure with its assertion message, lists every test by name, and, for the load test,
the throughput and latency summary it prints.
"""

from __future__ import annotations

import re
import sys
import xml.etree.ElementTree as ET
from dataclasses import dataclass, field
from pathlib import Path

PACKAGE = "com.robertwoolbright.inventory."

# (section, test class, what it proves). Keep in step with the README's oversell test table.
CLASSES = [
    ("Oversell protection", "inventory.InventoryConcurrencyTest",
     "Race tests: 50-60 threads released at once on one SKU. No oversell, no lost update."),
    ("Oversell protection", "inventory.NegativeControlTest",
     "The race check can fail: a naive read-then-write purchase is caught when buyers race."),
    ("Oversell protection", "inventory.MultiInstanceRaceTest",
     "Two app instances on one database, buyers split across both over HTTP. The guarantee lives in Postgres."),
    ("Oversell protection", "inventory.InventoryPropertyTest",
     "200 random receive/purchase sequences agree with an in-memory model after every step."),
    ("Oversell protection", "inventory.InventoryLoadTest",
     "Sustained HTTP load on hot SKUs kept near zero; the books balance exactly per SKU."),
    ("API behavior", "inventory.InventoryContractTest",
     "Every response of every endpoint matches inventory.yaml (status, content type, body schema)."),
    ("API behavior", "inventory.InventoryControllerTest",
     "Full stack over MockMvc: exact status, content type and body for every endpoint and error."),
    ("API behavior", "inventory.InventoryServiceTest",
     "Service rules: domain exceptions and the require(quantity >= 1) guard."),
    ("API behavior", "inventory.InventoryRepositoryTest",
     "SQL against real Postgres, including the CHECK (quantity >= 0) backstop."),
    ("API behavior", "InventoryApplicationTests",
     "The application context starts."),
]

REPETITION = re.compile(r"^(?P<name>.*?) :: repetition \d+ of (?P<total>\d+)$")
PARAMETER_TYPES = re.compile(r"\((?:[\w.<>\[\], ]*)\)")
# `org.opentest4j.AssertionFailedError: oversold: ...` -> `oversold: ...`
EXCEPTION_PREFIX = re.compile(r"^[\w.$]+(?:Error|Exception): ")


@dataclass
class TestCase:
    name: str
    failure: str | None = None
    skipped: bool = False


@dataclass
class Suite:
    name: str
    seconds: float
    cases: list[TestCase] = field(default_factory=list)
    output: str = ""

    @property
    def failed(self) -> int:
        return sum(1 for c in self.cases if c.failure is not None)

    @property
    def skipped(self) -> int:
        return sum(1 for c in self.cases if c.skipped)

    @property
    def passed(self) -> int:
        return len(self.cases) - self.failed - self.skipped


def read_suites(results_dir: Path) -> dict[str, Suite]:
    """Parse every TEST-*.xml in the directory, keyed by class name without the package prefix."""
    suites = {}
    for path in sorted(results_dir.glob("TEST-*.xml")):
        root = ET.parse(path).getroot()
        name = root.get("name", "").removeprefix(PACKAGE)
        suite = Suite(name, float(root.get("time", 0)), output=root.findtext("system-out") or "")
        for case in root.iter("testcase"):
            failure = case.find("failure")
            if failure is None:
                failure = case.find("error")
            suite.cases.append(TestCase(
                name=case.get("name", ""),
                failure=None if failure is None else (failure.get("message") or failure.text or "failed"),
                skipped=case.find("skipped") is not None,
            ))
        suites[name] = suite
    return suites


def readable(name: str) -> str:
    """`GET inventory by SKU returns zero stock as 200()` -> `GET inventory by SKU returns zero stock as 200`."""
    return PARAMETER_TYPES.sub("", name, count=1).strip()


def status(suite: Suite) -> str:
    if suite.failed:
        return f"❌ {suite.failed} failed, {suite.passed} passed"
    if not suite.passed:
        return "⚪ skipped"
    return f"✅ {suite.passed} passed"


def test_lines(suite: Suite) -> list[str]:
    """One line per test; repetitions of the same test are folded into one line."""
    lines, repeated = [], {}
    for case in suite.cases:
        match = REPETITION.match(case.name)
        if match:
            key = readable(match.group("name"))
            runs = repeated.setdefault(key, [0, 0, int(match.group("total"))])
            runs[0 if case.failure is None else 1] += 1
            continue
        icon = "❌" if case.failure else ("⚪" if case.skipped else "✅")
        lines.append(f"- {icon} {readable(case.name)}")
    for name, (ok, bad, total) in repeated.items():
        icon = "❌" if bad else "✅"
        lines.append(f"- {icon} {name} ({ok}/{total} repetitions passed)")
    return sorted(lines, key=lambda line: line[4:])


def load_summary(suite: Suite) -> str | None:
    """The block the load test prints, from `Load test:` to `final stock:`."""
    match = re.search(r"Load test:.*?final stock:[^\n]*", suite.output, re.S)
    return match.group(0) if match else None


def report(results_dir: Path) -> str:
    suites = read_suites(results_dir)
    if not suites:
        return f"## Test report\n\nNo test results found in `{results_dir}`.\n"

    total = sum(len(s.cases) for s in suites.values())
    failed = sum(s.failed for s in suites.values())
    skipped = sum(s.skipped for s in suites.values())
    seconds = sum(s.seconds for s in suites.values())
    headline = "✅ All tests passed" if failed == 0 else f"❌ {failed} test(s) failed"
    out = [
        f"## {headline}",
        "",
        f"**{total - failed - skipped} passed**, {failed} failed, {skipped} skipped "
        f"across {len(suites)} test classes, {seconds:.1f} s of test time.",
        "",
    ]

    if failed:
        out += ["### Failures", ""]
        for suite in suites.values():
            for case in suite.cases:
                if case.failure is not None:
                    message = EXCEPTION_PREFIX.sub("", case.failure.strip().splitlines()[0])[:400]
                    out += [f"- **{suite.name.split('.')[-1]}** › {readable(case.name)}", f"  > {message}"]
        out.append("")

    known = {name for _, name, _ in CLASSES}
    sections: dict[str, list[tuple[str, str]]] = {}
    for section, name, proves in CLASSES:
        if name in suites:
            sections.setdefault(section, []).append((name, proves))
    others = [(name, "") for name in suites if name not in known]
    if others:
        sections["Other tests"] = others

    for section, rows in sections.items():
        out += [f"### {section}", "", "| Test class | Result | What it proves |", "| --- | --- | --- |"]
        for name, proves in rows:
            out.append(f"| `{name.split('.')[-1]}` | {status(suites[name])} | {proves} |")
        out.append("")

    for suite in suites.values():
        summary = load_summary(suite)
        if summary:
            out += ["### Load test results", "", "```", summary.strip(), "```", ""]

    out += ["### Every test", ""]
    for section, rows in sections.items():
        for name, _ in rows:
            suite = suites[name]
            out += [
                "<details>",
                f"<summary><b>{name.split('.')[-1]}</b>: {status(suite)} ({suite.seconds:.1f} s)</summary>",
                "",
                *test_lines(suite),
                "",
                "</details>",
                "",
            ]
    return "\n".join(out)


def main() -> int:
    dirs = [Path(arg) for arg in sys.argv[1:]] or [Path("build/test-results/test")]
    print("\n".join(report(d) for d in dirs))
    return 0


if __name__ == "__main__":
    sys.exit(main())
