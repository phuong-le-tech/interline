"""Summarize actual JUnit XML and Swift selftest output for the RED handoff.

Run only after the complete reactor with test-failure-ignore, so dependency
failures do not prevent downstream tests from executing. This is not a green
build assertion. Each reported failure comes from a parsed test result.
"""
from pathlib import Path
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
reports = sorted((ROOT / "services/core").glob("*/target/*-reports/TEST-*.xml"))
rows = []
failures = []
for path in reports:
    suite = ET.parse(path).getroot()
    tests = suite.findall("testcase")
    failed = [case for case in tests if case.find("failure") is not None]
    errors = [case for case in tests if case.find("error") is not None]
    skipped = [case for case in tests if case.find("skipped") is not None]
    rows.append((path.parent.parent.parent.name, path.parent.name,
                 suite.get("name", ""), len(tests), len(failed), len(errors), len(skipped)))
    for case in failed + errors:
        node = case.find("failure")
        if node is None:
            node = case.find("error")
        assert node is not None
        lines = [line.strip() for line in node.get("message", "").splitlines() if line.strip()]
        message = lines[0] if lines else node.get("type", "")
        failures.append((case.get("classname", ""), case.get("name", ""), message))

assert rows, "No real Maven XML results found"
print("# M1 RED execution results\n")
print("Generated from actual JUnit XML and Swift selftest output. Not a green feature claim.\n")
print("## Java\n")
print("| Module | Runner | Suite | Tests | Failures | Errors | Skipped |")
print("|---|---|---|---:|---:|---:|---:|")
for row in rows:
    print("| " + " | ".join(map(str, row)) + " |")
print("\nTotals (actual runner executions, including packaged-contract reruns): "
      f"{sum(row[3] for row in rows)} tests, {sum(row[4] for row in rows)} failures, "
      f"{sum(row[5] for row in rows)} errors, {sum(row[6] for row in rows)} skipped.\n")
assert all("M1" in name for name, _, _ in failures), "Unexpected M0 failure - investigate before handoff"
assert sum(row[5] for row in rows) == 0, "Unexpected test error - investigate before handoff"
print("Every recorded Java failure belongs to a new M1 suite.\n")
m0 = [row for row in rows if "M1" not in row[2]]
m1 = [row for row in rows if "M1" in row[2]]
print(f"Existing M0 runner executions: {sum(row[3] for row in m0)}, all passing.\n")
print(f"New Java M1 invocations: {sum(row[3] for row in m1)}, "
      f"{sum(row[4] for row in m1)} assertion failures, {sum(row[6] for row in m1)} native opt-in skips.\n")
print("### One line per failed Java invocation\n")
for name, test, message in failures:
    print(f"- `{name}.{test}`: {message}")
print("\n## Swift\n")
log = (ROOT / "agents/macos-capture/red-tests.log").read_text()
lines = log.splitlines()
summary = [line for line in lines if line.startswith("CaptureSelfTest:")]
assert len(summary) == 1, "Missing unique Swift run summary"
print(summary[0] + "\n")
for line in lines:
    if line.startswith("FAIL "):
        print("- " + line)
print("\n## Browser and evidence scripts\n")
print("See M1-RED-HANDOFF.md for the separately executed browser suite, native profile and evidence-script results.")
