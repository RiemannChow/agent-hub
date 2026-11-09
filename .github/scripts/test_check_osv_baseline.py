#!/usr/bin/env python3
"""Unit tests for the OSV baseline gate; run directly in CI.

    python3 .github/scripts/test_check_osv_baseline.py
"""

from __future__ import annotations

import importlib.util
import json
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).with_name("check_osv_baseline.py")
spec = importlib.util.spec_from_file_location("check_osv_baseline", SCRIPT)
assert spec and spec.loader
gate = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gate)


def sample_report(*ids: str, use_vulnerabilities: bool = False) -> dict:
    package: dict = {"package": {"name": "demo", "version": "1.0"}}
    if use_vulnerabilities:
        package["vulnerabilities"] = [{"id": advisory_id} for advisory_id in ids]
    else:
        package["groups"] = [{"ids": list(ids), "max_severity": "7.5"}]
    return {"results": [{"packages": [package]}]}


class CollectIdsTest(unittest.TestCase):
    def test_reads_group_ids(self):
        self.assertEqual(gate.collect_ids(sample_report("GHSA-1", "GHSA-2")), {"GHSA-1", "GHSA-2"})

    def test_reads_vulnerability_ids_too(self):
        self.assertEqual(
            gate.collect_ids(sample_report("GHSA-9", use_vulnerabilities=True)), {"GHSA-9"}
        )

    def test_empty_report(self):
        self.assertEqual(gate.collect_ids({}), set())


class EvaluateTest(unittest.TestCase):
    def test_only_baselined_ids_pass(self):
        self.assertEqual(gate.evaluate({"A", "B"}, {"A", "B", "C"}), 0)

    def test_new_id_fails(self):
        self.assertEqual(gate.evaluate({"A", "NEW"}, {"A"}), 1)

    def test_no_findings_passes(self):
        self.assertEqual(gate.evaluate(set(), {"A"}), 0)


class MainTest(unittest.TestCase):
    def test_baselined_report_passes(self):
        with tempfile.TemporaryDirectory() as tmp:
            report = Path(tmp) / "report.json"
            baseline = Path(tmp) / "baseline.txt"
            report.write_text(json.dumps(sample_report("GHSA-1")), encoding="utf-8")
            baseline.write_text("# comment\nGHSA-1\n\nGHSA-OLD\n", encoding="utf-8")
            self.assertEqual(gate.main(["gate", str(report), str(baseline)]), 0)

    def test_new_advisory_fails(self):
        with tempfile.TemporaryDirectory() as tmp:
            report = Path(tmp) / "report.json"
            baseline = Path(tmp) / "baseline.txt"
            report.write_text(json.dumps(sample_report("GHSA-NEW")), encoding="utf-8")
            baseline.write_text("GHSA-1\n", encoding="utf-8")
            self.assertEqual(gate.main(["gate", str(report), str(baseline)]), 1)

    def test_missing_report_fails(self):
        with tempfile.TemporaryDirectory() as tmp:
            baseline = Path(tmp) / "baseline.txt"
            baseline.write_text("GHSA-1\n", encoding="utf-8")
            self.assertEqual(
                gate.main(["gate", str(Path(tmp) / "missing.json"), str(baseline)]), 1
            )

    def test_malformed_report_fails(self):
        with tempfile.TemporaryDirectory() as tmp:
            report = Path(tmp) / "report.json"
            baseline = Path(tmp) / "baseline.txt"
            report.write_text("{not json", encoding="utf-8")
            baseline.write_text("GHSA-1\n", encoding="utf-8")
            self.assertEqual(gate.main(["gate", str(report), str(baseline)]), 1)

    def test_missing_baseline_fails(self):
        with tempfile.TemporaryDirectory() as tmp:
            report = Path(tmp) / "report.json"
            report.write_text(json.dumps(sample_report("GHSA-1")), encoding="utf-8")
            self.assertEqual(
                gate.main(["gate", str(report), str(Path(tmp) / "missing.txt")]), 1
            )


if __name__ == "__main__":
    unittest.main(verbosity=2)
