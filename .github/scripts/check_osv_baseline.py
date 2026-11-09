#!/usr/bin/env python3
"""Fail the build when OSV-Scanner reports an advisory that is not in the baseline.

The repository carries known vulnerability debt (see .github/osv-baseline.txt). A plain
`continue-on-error: true` on the scanner hid both that debt and every NEW finding. This gate
keeps the debt visible but makes any newly introduced advisory block the merge.

Usage: check_osv_baseline.py <osv-report.json> <baseline.txt>
Exit codes: 0 = no new advisories, 1 = new advisories or an unusable input.
"""

from __future__ import annotations

import json
import sys
from pathlib import Path


def collect_ids(report: dict) -> set[str]:
    """Extract every advisory id from an OSV-Scanner JSON report.

    OSV-Scanner groups findings and also emits a full vulnerability list; both places are
    inspected so a format tweak in one of them cannot silently disable the gate.
    """
    found: set[str] = set()
    for result in report.get("results", []):
        for package in result.get("packages", []):
            for group in package.get("groups", []):
                for advisory_id in group.get("ids", []):
                    if advisory_id:
                        found.add(str(advisory_id))
            for vulnerability in package.get("vulnerabilities", []):
                advisory_id = vulnerability.get("id")
                if advisory_id:
                    found.add(str(advisory_id))
    return found


def load_baseline(path: Path) -> set[str]:
    known: set[str] = set()
    for line in path.read_text(encoding="utf-8").splitlines():
        stripped = line.strip()
        if stripped and not stripped.startswith("#"):
            known.add(stripped)
    return known


def evaluate(found: set[str], known: set[str]) -> int:
    """Return 0 when every reported advisory is already baselined, 1 otherwise."""
    new = sorted(found - known)
    resolved = sorted(known - found)
    print(
        f"OSV baseline gate: {len(found)} reported, {len(known)} baselined, "
        f"{len(new)} new, {len(resolved)} resolved since baseline."
    )
    if resolved:
        print("Resolved advisories (can be removed from the baseline after review):")
        for advisory_id in resolved:
            print(f"  - {advisory_id}")
    if new:
        print("ERROR: new advisories are not in .github/osv-baseline.txt:")
        for advisory_id in new:
            print(f"  - {advisory_id}")
        print("Fix the dependency, or add the id to the baseline with a written justification.")
        return 1
    print("OK: no new advisories.")
    return 0


def main(argv: list[str]) -> int:
    if len(argv) != 3:
        print(f"Usage: {argv[0]} <osv-report.json> <baseline.txt>", file=sys.stderr)
        return 1
    report_path, baseline_path = Path(argv[1]), Path(argv[2])
    if not report_path.is_file():
        print(f"ERROR: OSV report not found: {report_path} (scanner failed?)", file=sys.stderr)
        return 1
    if not baseline_path.is_file():
        print(f"ERROR: baseline not found: {baseline_path}", file=sys.stderr)
        return 1
    try:
        report = json.loads(report_path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        print(f"ERROR: cannot parse OSV report: {exc}", file=sys.stderr)
        return 1
    if not isinstance(report, dict):
        print("ERROR: OSV report must be a JSON object", file=sys.stderr)
        return 1
    return evaluate(collect_ids(report), load_baseline(baseline_path))


if __name__ == "__main__":
    sys.exit(main(sys.argv))
