"""Check diagnostic output without Wren or a real embedding model."""

import json
from pathlib import Path
import runpy
import sys
import tempfile
from types import ModuleType
import unittest
from unittest.mock import patch


SCRIPT = Path(__file__).resolve().parents[2] / "main/bin/profile-wren.py"
MODULE = runpy.run_path(str(SCRIPT))


class WrenProfileTest(unittest.TestCase):
    def test_nested_same_stage_has_union_time_separate_from_inclusive_sum(self):
        profiler = MODULE["PhaseProfiler"]()
        with patch.dict(MODULE["PhaseProfiler"].enter.__globals__, {"perf_counter": lambda: 1.0}):
            profiler.enter("outer", "python_imports")
            profiler.enter("inner", "python_imports")
        with patch.dict(MODULE["PhaseProfiler"].enter.__globals__, {"perf_counter": lambda: 2.0}):
            profiler.leave("inner")
            profiler.leave("outer")
        row = profiler.summary()["python_imports"]
        self.assertEqual(row["calls"], 2)
        self.assertEqual(row["inclusiveMs"], 2000.0)
        self.assertEqual(row["outermostMs"], 1000.0)

    def test_only_known_functions_are_reported(self):
        stage = MODULE["stage_for"]
        self.assertEqual(stage("x/wren/memory/embeddings.py", "_runtime"), "embedding_runtime_including_load")
        self.assertEqual(stage("C:\\x\\wren\\memory\\store.py", "index_schema"), "schema_index_total")
        self.assertIsNone(stage("/private/secret.py", "secret_sql"))
        self.assertIsNone(stage("/private/secret.py", "_runtime"))

    def invoke(self, exit_code):
        def app():
            self.assertEqual(sys.argv, ["wren", "memory", "store", "--sql", "private-query"])
            raise SystemExit(exit_code)
        fake_cli = ModuleType("wren.cli")
        fake_cli.app = app
        with tempfile.TemporaryDirectory() as directory:
            report = Path(directory) / "result.json"
            original = sys.argv
            with patch.dict(sys.modules, {"wren.cli": fake_cli}):
                with self.assertRaises(SystemExit) as raised:
                    MODULE["main"](["--report", str(report), "--", "memory", "store", "--sql", "private-query"])
            self.assertEqual(raised.exception.code, exit_code)
            self.assertIs(sys.argv, original)
            text = report.read_text(encoding="utf-8")
            self.assertNotIn("private-query", text)
            self.assertNotIn("secret", text)
            return json.loads(text)

    def test_success_exit_is_preserved_and_output_has_no_arguments(self):
        report = self.invoke(0)
        self.assertEqual(report["outcome"], "ok")
        self.assertTrue(report["inclusiveTimesOverlap"])

    def test_failed_exit_is_preserved(self):
        self.assertEqual(self.invoke(7)["outcome"], "failed")

    def test_python_exception_still_produces_failed_report(self):
        fake_cli = ModuleType("wren.cli")
        def app():
            raise RuntimeError("private-failure-detail")
        fake_cli.app = app
        with tempfile.TemporaryDirectory() as directory:
            report = Path(directory) / "error.json"
            with patch.dict(sys.modules, {"wren.cli": fake_cli}):
                with self.assertRaisesRegex(RuntimeError, "private-failure-detail"):
                    MODULE["main"](["--report", str(report), "--", "memory", "status"])
            text = report.read_text(encoding="utf-8")
            self.assertNotIn("private-failure-detail", text)
            self.assertEqual(json.loads(text)["outcome"], "failed")

    def test_report_never_overwrites_an_existing_file(self):
        with tempfile.TemporaryDirectory() as directory:
            report = Path(directory) / "existing.json"
            report.write_text("keep", encoding="utf-8")
            with self.assertRaises(FileExistsError):
                MODULE["main"](["--report", str(report), "--", "memory", "status"])
            self.assertEqual(report.read_text(encoding="utf-8"), "keep")


if __name__ == "__main__":
    unittest.main()
