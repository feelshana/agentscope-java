"""Local preparation and handover tests: no production services or credentials."""
import importlib.util
import json
from pathlib import Path
import subprocess
import os
import socket
from datetime import datetime
from types import SimpleNamespace
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[3]


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, ROOT / path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


configure = load("private_config", "deploy/bi-sync/configure.py")
formal = load("formal_app", "docker/formal-app.py")
preflight = load("db_preflight", "docker/database-preflight.py")
sync = load("bi_sync_unit", "deploy/bi-sync/bi_sync.py")
cron = load("bi_cron", "docker/install-bi-sync-cron.py")


class FormalPreparationTest(unittest.TestCase):
    @unittest.skipUnless(os.name == "posix" and os.geteuid() == 0, "Linux container fixture")
    def test_real_shell_prepare_external_and_legacy_preserve_files(self):
        # A private socket inside the disposable test container satisfies the shell's socket guard.
        owned_socket = None
        sock_path = Path("/var/run/docker.sock")
        if not sock_path.exists():
            owned_socket = socket.socket(socket.AF_UNIX)
            owned_socket.bind(str(sock_path))
        try:
            with tempfile.TemporaryDirectory() as folder:
                p = Path(folder)
                binaries = p / "bin"
                binaries.mkdir()
                fake = binaries / "docker"
                fake.write_text('''#!/usr/bin/env python
import os,sys
from pathlib import Path
args=sys.argv[1:]
with open(os.environ['FAKE_LOG'],'a') as f:f.write(' '.join(args)+'\\n')
if args[:2]==['network','inspect']:
 print(os.environ['FAKE_LABEL'])
elif args[:2]==['image','inspect']:
 if 'dataagent.application' in ' '.join(args): print('linux/amd64 true')
 elif '{{.Id}}' in args: print('sha256:'+'a'*64)
 else: print('linux/amd64')
''')
                fake.chmod(0o755)
                cache = p / "cache"
                (cache / "hub").mkdir(parents=True)
                private = p / "database.env"
                private.write_text("PRIVATE_FIXTURE=not-a-production-secret\n")
                private.chmod(0o600)
                db = p / "db"
                db.mkdir()
                (db / ".dataagent-rehearsal").write_text(str(db) + "|fixture-db")
                (db / "app-db.env").write_text("PRIVATE_FIXTURE=legacy\n")
                for mode in ("external", "rehearsal"):
                    base = p / mode
                    env = dict(os.environ, PATH=str(binaries) + os.pathsep + os.environ["PATH"],
                        DATAAGENT_DOCKER_SUDO="false", DATAAGENT_REHEARSAL_APP_DIR=str(base),
                        DATAAGENT_REHEARSAL_APP_PROJECT="fixture-" + mode, DATAAGENT_DB_MODE=mode,
                        DATAAGENT_EXTERNAL_DB_ENV=str(private), DATAAGENT_MODEL_CACHE=str(cache),
                        DATAAGENT_REHEARSAL_DB_DIR=str(db), DATAAGENT_REHEARSAL_DB_PROJECT="fixture-db",
                        FAKE_LOG=str(p / "commands"), FAKE_LABEL=str(base if mode == "external" else db))
                    result = subprocess.run(["bash", str(ROOT / "docker/rehearsal-app.sh"),
                                             "prepare", "fixture:image"], env=env,
                                            capture_output=True, text=True)
                    self.assertEqual(result.returncode, 0, result.stderr)
                    conf = formal.values(base / "compose.env")
                    self.assertEqual(conf["DB_ENV"], str(private if mode == "external" else db / "app-db.env"))
                    before = (base / "runtime.env").read_bytes()
                    subprocess.run(["bash", str(ROOT / "docker/rehearsal-app.sh"),
                                    "prepare", "fixture:image"], env=env, capture_output=True, check=True)
                    self.assertEqual((base / "runtime.env").read_bytes(), before)
                    self.assertNotIn("PRIVATE_FIXTURE", result.stdout)
        finally:
            if owned_socket:
                owned_socket.close()
                sock_path.unlink()

    def test_private_files_preserve_special_passwords_and_refuse_overwrite(self):
        with tempfile.TemporaryDirectory() as folder:
            target, source = "a#b&c$D'\\x", "e*F#G"
            configure.prepare(folder, target, source)
            config = json.loads((Path(folder) / "bi-sync.json").read_text())
            self.assertEqual(config["target"]["password"], target)
            self.assertEqual(config["source"]["password"], source)
            text = (Path(folder) / "database.env").read_text()
            self.assertIn("/data_agent_platform?", text)
            self.assertIn(configure.dotenv(target), text)
            with self.assertRaises(ValueError):
                configure.prepare(folder, "changed", "changed")
            self.assertEqual(config["target"]["password"], target)

    def test_jdbc_parsing_and_rejection(self):
        self.assertEqual(preflight.endpoint("jdbc:mysql://db.internal:3322/data_agent_platform?serverTimezone=Hongkong"),
                         ("db.internal", 3322, "data_agent_platform"))
        for url in ("jdbc:mysql://host/", "jdbc:mysql://u:p@host/db", "jdbc:mysql://host/db?password=x",
                    "jdbc:mysql://host:99999/db", "jdbc:postgresql://host/db", "jdbc:mysql://host/db/other"):
            with self.subTest(url=url), self.assertRaises(ValueError):
                preflight.endpoint(url)

    def fixture(self, folder):
        base, old = Path(folder) / "formal", Path(folder) / "old"
        for path, project, port in ((base, formal.PROJECT, 18081), (old, formal.OLD_PROJECT, 18080)):
            path.mkdir()
            (path / ".dataagent-app").write_text(str(path) + "|" + project)
            (path / "compose.env").write_text("APP_BASE=" + str(path) + "\nAPP_PORT=" + str(port) + "\n")
        (base / ".formal-ready").write_text("formal-v1")
        return base, old

    def test_failed_preflight_never_stops_old(self):
        with tempfile.TemporaryDirectory() as folder:
            base, old = self.fixture(folder)
            calls = []
            def control(action, image=None, old=False):
                calls.append((action, old))
                if action == "check":
                    raise subprocess.CalledProcessError(1, "fixture")
            with patch.object(formal, "BASE", base), patch.object(formal, "OLD", old), \
                    patch.object(formal, "control", control), patch.object(formal, "candidate_ready"), \
                    self.assertRaises(subprocess.CalledProcessError):
                formal.handover()
            self.assertEqual(calls, [("check", False)])

    def test_failed_start_restores_old_and_candidate_port(self):
        with tempfile.TemporaryDirectory() as folder:
            base, old = self.fixture(folder)
            original_old = (old / "compose.env").read_bytes()
            calls = []
            def control(action, image=None, old=False):
                calls.append((action, old))
                if action == "start" and not old:
                    raise subprocess.CalledProcessError(1, "fixture")
            with patch.object(formal, "BASE", base), patch.object(formal, "OLD", old), \
                    patch.object(formal, "control", control), patch.object(formal, "candidate_ready"), \
                    self.assertRaises(subprocess.CalledProcessError):
                formal.handover()
            self.assertIn(("start", True), calls)
            self.assertEqual(formal.values(base / "compose.env")["APP_PORT"], "18081")
            self.assertEqual((old / "compose.env").read_bytes(), original_old)

    def test_success_and_revert_restore_candidate_port(self):
        with tempfile.TemporaryDirectory() as folder:
            base, old = self.fixture(folder)
            calls = []
            with patch.object(formal, "BASE", base), patch.object(formal, "OLD", old), \
                    patch.object(formal, "control", lambda action, image=None, old=False: calls.append((action, old))), \
                    patch.object(formal, "candidate_ready"):
                formal.handover()
                self.assertEqual(formal.values(base / "compose.env")["APP_PORT"], "18080")
                formal.handover(revert=True)
                self.assertEqual(formal.values(base / "compose.env")["APP_PORT"], "18081")
            self.assertTrue((old / ".dataagent-app").exists())

    def test_unhealthy_candidate_never_stops_old(self):
        with tempfile.TemporaryDirectory() as folder:
            base, old = self.fixture(folder)
            calls = []
            with patch.object(formal, "BASE", base), patch.object(formal, "OLD", old), \
                    patch.object(formal, "control", lambda action, image=None, old=False: calls.append((action, old))), \
                    patch.object(formal, "candidate_ready", side_effect=ValueError("not healthy")), \
                    self.assertRaises(ValueError):
                formal.handover()
            self.assertEqual(calls, [("check", False)])

    def test_stale_configuration_never_stops_old(self):
        with tempfile.TemporaryDirectory() as folder:
            base, old = self.fixture(folder)
            (base / "compose.env.next").write_text("preserve this incomplete operation")
            with patch.object(formal, "BASE", base), patch.object(formal, "OLD", old), \
                    patch.object(formal, "control") as control, self.assertRaises(ValueError):
                formal.handover()
            control.assert_not_called()

    def test_configuration_write_failure_still_attempts_old_service(self):
        with tempfile.TemporaryDirectory() as folder:
            base, old = self.fixture(folder)
            calls = []
            with patch.object(formal, "BASE", base), patch.object(formal, "OLD", old), \
                    patch.object(formal, "control", lambda action, image=None, old=False: calls.append((action, old))), \
                    patch.object(formal, "candidate_ready"), \
                    patch.object(formal, "set_port", side_effect=OSError("injected disk error")), \
                    self.assertRaises(OSError):
                formal.handover()
            self.assertIn(("start", True), calls)

    def test_config_and_table_whitelist(self):
        with tempfile.TemporaryDirectory() as folder:
            configure.prepare(folder, "target", "source")
            path = Path(folder) / "bi-sync.json"
            self.assertEqual(sync.load_config(path)["target"]["database"], "data_agent")
            config = json.loads(path.read_text())
            config["target"]["database"] = "data_agent_platform"
            path.write_text(json.dumps(config))
            with self.assertRaises(ValueError):
                sync.load_config(path)
        self.assertEqual(len(sync.TABLES), 7)
        for table in sync.TABLES:
            self.assertNotIn("password", sync.columns(table))
            self.assertNotIn("config_info", sync.columns(table))
            self.assertNotIn("phone", sync.columns(table))

    def test_cron_refuses_stale_sync_without_touching_schedule(self):
        with patch.object(cron.time, "strftime", return_value="+0800"), \
                patch.object(cron, "datetime", SimpleNamespace(
                    now=lambda tz: datetime(2026, 11, 3, tzinfo=tz))):
            with patch.object(cron.subprocess, "run", return_value=SimpleNamespace(stdout='{"success_until":null}')) as run, \
                    self.assertRaises(ValueError):
                cron.main()
            self.assertEqual(run.call_count, 1)

    def test_cron_preserves_existing_entries_and_read_error_blocks_write(self):
        status = SimpleNamespace(stdout='{"success_until":"2026-11-03"}')
        existing = "12 1 * * * /usr/bin/existing-task\n"
        with patch.object(cron.time, "strftime", return_value="+0800"), \
                patch.object(cron, "datetime", SimpleNamespace(now=lambda tz: datetime(2026, 11, 3, tzinfo=tz))):
            with patch.object(cron.subprocess, "run", side_effect=[status,
                    SimpleNamespace(returncode=0, stdout=existing, stderr=""), SimpleNamespace(returncode=0)]) as run:
                cron.main()
                content = run.call_args.kwargs["input"]
                self.assertIn(existing.strip(), content)
                self.assertEqual(content.count(cron.MARKER), 1)
            with patch.object(cron.subprocess, "run", side_effect=[status,
                    SimpleNamespace(returncode=1, stdout="", stderr="permission denied")]) as run, \
                    self.assertRaises(ValueError):
                cron.main()
            self.assertEqual(run.call_count, 2)

    def test_remove_cron_preserves_other_lines_and_never_contacts_database(self):
        before = "12 1 * * * /usr/bin/existing-task\n"
        entry = "0 2 * * * /bin/bash " + str(cron.HERE / "bi-sync.sh") + " sync >> /data/dataagent/formal-app/logs/bi-sync/cron.log 2>&1\n"
        after = "# unrelated\n"
        current = SimpleNamespace(returncode=0, stdout=before + cron.MARKER + "\n" + entry + after, stderr="")
        with patch.object(cron.subprocess, "run", side_effect=[current, SimpleNamespace(returncode=0)]) as run:
            cron.main(["remove"])
            self.assertEqual(run.call_args.kwargs["input"], before + after)
            self.assertEqual(run.call_count, 2)
            self.assertTrue(all(call.args[0][0] == "crontab" for call in run.call_args_list))
        current.stdout = before + cron.MARKER + "\n0 2 * * * /bin/bash /unrelated.sh\n"
        with patch.object(cron.subprocess, "run", return_value=current) as run, self.assertRaises(ValueError):
            cron.main(["remove"])
        self.assertEqual(run.call_count, 1)

    def test_paused_sync_cannot_install_cron(self):
        status = SimpleNamespace(stdout='{"success_until":"2026-11-03","paused":true}')
        with patch.object(cron.time, "strftime", return_value="+0800"), \
                patch.object(cron.subprocess, "run", return_value=status) as run, self.assertRaises(ValueError):
            cron.main()
        self.assertEqual(run.call_count, 1)


if __name__ == "__main__":
    unittest.main()
