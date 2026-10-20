"""Regression checks for the release preflight, without installing Python packages."""
from pathlib import Path
import runpy
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch


class WrenPreflightTest(unittest.TestCase):
    def setUp(self):
        source = Path(__file__).resolve().parents[2] / "main/bin/check-wren.py"
        self.fixture = tempfile.TemporaryDirectory(prefix="dataagent-wren-check-")
        self.addCleanup(self.fixture.cleanup)
        release = Path(self.fixture.name)
        (release / "bin").mkdir()
        script = release / "bin/check-wren.py"
        script.write_bytes(source.read_bytes())
        (release / "requirements-wren.txt").write_bytes(
            (source.parents[3] / "requirements-wren.txt").read_bytes())
        self.main = runpy.run_path(str(script))["main"]
        self.globals = self.main.__globals__
        self.versions = {"wrenai": "0.15.0", "wren-core-py": "0.8.0", "mcp": "1.30.0"}

    def invoke(self, importer):
        with patch.object(sys, "argv", ["check-wren.py", "dependencies", "wren"]), \
                patch.dict(self.globals, {"version": lambda name: self.versions[name]}), \
                patch.object(self.globals["importlib"], "import_module", side_effect=importer), \
                patch.object(self.globals["shutil"], "which", return_value="/fixture/bin/wren"), \
                patch.object(self.globals["subprocess"], "run", return_value=subprocess.CompletedProcess(
                    [], 0, stdout=b"wrenai 0.15.0\n", stderr=b"")):
            return self.main()

    def test_mysqlclient_is_checked_by_its_actual_import_name(self):
        imported = []
        self.assertEqual(self.invoke(lambda name: imported.append(name)), 0)
        self.assertIn("MySQLdb", imported)
        self.assertNotIn("pymysql", imported)

    def test_missing_mysqlclient_is_rejected_even_if_pymysql_is_available(self):
        def importer(name):
            if name == "MySQLdb":
                raise ModuleNotFoundError("No module named 'MySQLdb'")
        with self.assertRaisesRegex(ModuleNotFoundError, "MySQLdb"):
            self.invoke(importer)

    def test_pinned_package_version_is_still_enforced(self):
        self.versions["mcp"] = "0.0.0"
        with self.assertRaisesRegex(ValueError, "mcp"):
            self.invoke(lambda name: None)


if __name__ == "__main__":
    unittest.main()
