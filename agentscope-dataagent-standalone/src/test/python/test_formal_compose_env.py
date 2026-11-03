"""Verify private dotenv escaping with the real Compose parser and a disposable container."""
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[3]
spec = importlib.util.spec_from_file_location("private_config_real", ROOT / "deploy/bi-sync/configure.py")
configure = importlib.util.module_from_spec(spec)
spec.loader.exec_module(configure)


@unittest.skipUnless(os.environ.get("DATAAGENT_COMPOSE_ENV_TESTS") == "1", "real Compose opt-in")
class ComposeEnvTest(unittest.TestCase):
    def test_actual_container_receives_literal_values(self):
        for value in ("fake#x&$D'\\z", "ends\\", '${UNDEFINED_VARIABLE}$$"quoted"'):
            with self.subTest(case_hash=hashlib.sha256(value.encode()).hexdigest()[:8]), \
                    tempfile.TemporaryDirectory() as folder:
                path = Path(folder)
                configure.prepare(path, value, "not-a-production-password")
                code = 'import os,hashlib;print(hashlib.sha256(os.environ["DATAAGENT_DB_PASSWORD"].encode()).hexdigest())'
                doc = {"services": {"check": {"image": "dataagent/runtime:20261028", "network_mode": "none",
                    "env_file": [str(path / "database.env")], "entrypoint": ["python", "-c", code]}}}
                file = path / "compose.json"
                file.write_text(json.dumps(doc))
                result = subprocess.run(["docker", "compose", "-p", "dataagent-env-fixture", "-f", str(file),
                    "run", "--rm", "--no-deps", "--pull", "never", "-T", "check"],
                    capture_output=True, text=True, timeout=30)
                self.assertEqual(result.returncode, 0, "Compose fixture did not start")
                self.assertEqual(result.stdout.strip(), hashlib.sha256(value.encode()).hexdigest())


if __name__ == "__main__":
    unittest.main()
