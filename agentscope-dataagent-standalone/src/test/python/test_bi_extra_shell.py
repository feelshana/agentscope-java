"""Run the actual shell entry points against isolated Linux external-command stubs."""
import os
from pathlib import Path
import subprocess
import unittest

ROOT = Path(__file__).resolve().parents[3]
LINUX_TEST = r'''
import json,os,pathlib,shutil,subprocess,tempfile,unittest
from datetime import datetime,timezone,timedelta

class ShellGuards(unittest.TestCase):
    def setUp(self):
        self.tmp=pathlib.Path(tempfile.mkdtemp(prefix="bi-extra-shell-"))
        self.scripts=self.tmp/"scripts"; self.scripts.mkdir()
        for f in ("bi-extra-sync.sh","install-bi-extra-sync-cron.sh"):
            shutil.copy2(pathlib.Path("/scripts")/f,self.scripts/f)
        self.base=pathlib.Path("/data/dataagent/formal-app"); self.base.mkdir(parents=True,exist_ok=True)
        self.private=pathlib.Path("/data/dataagent/formal-private"); self.private.mkdir(parents=True,exist_ok=True)
        (self.base/".dataagent-app").write_text(str(self.base)+"|dataagent66-formal-app")
        (self.base/"compose.env").write_text("APP_BASE="+str(self.base)+"\nDB_NETWORK=dataagent66-formal-app_default\nAPP_IMAGE=sha256:"+"a"*64+"\n")
        for f in ("bi-sync.json","bi-sync-extra.json"):
            (self.private/f).write_text("synthetic-private-not-to-echo")
            (self.private/f).chmod(0o600)
        self.bin=self.tmp/"bin"; self.bin.mkdir()
        self.cron=self.tmp/"crontab"
        self.old="# DataAgent BI mirror v1\n0 2 * * * /bin/bash /original/bi-sync.sh sync\n# unrelated\n5 4 * * * /original/other\n"
        self.cron.write_text(self.old)
        today=datetime.now(timezone(timedelta(hours=8))).date().isoformat()
        names=["bi_page_click_log","bi_iam_user","bi_tenant_optimization_daily","bi_project_capacity_daily","bi_screen","bi_screen_visit_log"]
        self.status=dict(paused=False,running=False,last_verify=dict(until=today,from_="2026-01-01",tables=names),
                         last_run=dict(action="verify",outcome="SUCCESS_WITH_GAPS"),
                         tables={n:dict(success_until=today) for n in names})
        self.state=self.tmp/"status.json"; self.state.write_text(json.dumps(self.status))
        stubs={
            "docker": """#!/bin/bash
printf '%s\\n' "$*" >> "$FIXTURE_DOCKER_LOG"
if [[ "$1" == network ]]; then echo /data/dataagent/formal-app; exit 0; fi
if [[ "$1" == exec ]]; then shift; [[ "$1" == -i ]] && shift; shift; exec "$@"; fi
if [[ "$1" == run ]]; then
 [[ "${FIXTURE_DOCKER_EXIT:-0}" == 0 ]] || exit "$FIXTURE_DOCKER_EXIT"
 if [[ "$*" == *'extra_sync.py status'* ]]; then cat "$FIXTURE_STATUS"; else echo simulated; fi
 exit 0
fi
exit 1
""",
            "crontab": """#!/bin/bash
if [[ "$1" == -l ]]; then
 if [[ -f "$FIXTURE_CRON" ]]; then cat "$FIXTURE_CRON"; exit 0; fi
 echo 'no crontab for fixture' >&2; exit 1
fi
[[ "$1" == - ]] || exit 1
cat > "$FIXTURE_CRON"
""",
            "date": """#!/bin/bash
if [[ "$1" == +%z ]]; then echo "${FIXTURE_ZONE:-+0800}"; else /bin/date "$@"; fi
"""
        }
        for name,code in stubs.items():
            p=self.bin/name; p.write_text(code); p.chmod(0o755)
        self.env=dict(os.environ,PATH=str(self.bin)+":"+os.environ["PATH"],
                      DATAAGENT_DOCKER_SUDO="false",FIXTURE_CRON=str(self.cron),
                      FIXTURE_STATUS=str(self.state),FIXTURE_DOCKER_LOG=str(self.tmp/"docker.log"))
        self.env.pop("DATAAGENT_FORMAL_APP_DIR",None)
        self.env.pop("DATAAGENT_FORMAL_PRIVATE_DIR",None)

    def run_script(self,name,*args,**env):
        return subprocess.run(["bash",str(self.scripts/name),*args],env=dict(self.env,**env),
                              capture_output=True,text=True,timeout=20)

    def test_install_and_repeat_preserve_old_and_unrelated(self):
        r=self.run_script("install-bi-extra-sync-cron.sh"); self.assertEqual(0,r.returncode,r.stdout+r.stderr)
        new=self.cron.read_text(); self.assertTrue(new.startswith(self.old))
        self.assertEqual(1,new.count("# DataAgent BI extra mirror v1"))
        self.assertIn("15 2 * * *",new)
        r=self.run_script("install-bi-extra-sync-cron.sh"); self.assertEqual(0,r.returncode,r.stdout+r.stderr)
        self.assertEqual(new,self.cron.read_text())

    def test_remove_works_without_database_or_container_and_keeps_old(self):
        self.assertEqual(0,self.run_script("install-bi-extra-sync-cron.sh").returncode)
        (self.private/"bi-sync-extra.json").unlink()
        r=self.run_script("install-bi-extra-sync-cron.sh","remove",FIXTURE_DOCKER_EXIT="23")
        self.assertEqual(0,r.returncode,r.stdout+r.stderr)
        self.assertEqual(self.old,self.cron.read_text())

    def test_failed_or_partial_verify_does_not_install(self):
        self.status["last_verify"]["tables"]=["bi_page_click_log"]; self.state.write_text(json.dumps(self.status))
        r=self.run_script("install-bi-extra-sync-cron.sh")
        self.assertNotEqual(0,r.returncode)
        self.assertEqual(self.old,self.cron.read_text())

    def test_paused_task_does_not_install(self):
        self.status["paused"]=True; self.state.write_text(json.dumps(self.status))
        self.assertNotEqual(0,self.run_script("install-bi-extra-sync-cron.sh").returncode)
        self.assertEqual(self.old,self.cron.read_text())

    def test_mismatched_marked_command_is_preserved(self):
        text=self.old+"# DataAgent BI extra mirror v1\narbitrary command\n"
        self.cron.write_text(text)
        r=self.run_script("install-bi-extra-sync-cron.sh","remove")
        self.assertNotEqual(0,r.returncode); self.assertEqual(text,self.cron.read_text())

    def test_wrong_timezone_does_not_install(self):
        r=self.run_script("install-bi-extra-sync-cron.sh",FIXTURE_ZONE="+0000")
        self.assertNotEqual(0,r.returncode); self.assertEqual(self.old,self.cron.read_text())

    def test_runner_permission_guard_and_no_private_contents(self):
        (self.private/"bi-sync-extra.json").chmod(0o644)
        r=self.run_script("bi-extra-sync.sh","status")
        self.assertNotEqual(0,r.returncode)
        self.assertNotIn("synthetic-private-not-to-echo",r.stdout+r.stderr)

    def test_runner_propagates_docker_failure_through_tee(self):
        r=self.run_script("bi-extra-sync.sh","sync",FIXTURE_DOCKER_EXIT="23")
        self.assertEqual(23,r.returncode,r.stdout+r.stderr)
        self.assertNotIn("synthetic-private-not-to-echo",r.stdout+r.stderr)

    def test_configure_refuses_pipe_or_overwrite(self):
        before=(self.private/"bi-sync.json").read_bytes()
        r=self.run_script("bi-extra-sync.sh","configure")
        self.assertNotEqual(0,r.returncode)
        self.assertEqual(before,(self.private/"bi-sync.json").read_bytes())

unittest.main(verbosity=2)
'''


@unittest.skipUnless(os.environ.get("DATAAGENT_CONTAINER_TESTS") == "1", "isolated Linux opt-in")
class ExtraShellTest(unittest.TestCase):
    def test_shell_guards_on_linux(self):
        result = subprocess.run(
            ["docker", "run", "--rm", "--pull", "never", "--network", "none", "-i",
             "--cpus", "1", "--memory", "256m",
             "--mount", "type=bind,source=" + (ROOT / "docker").as_posix() + ",target=/scripts,readonly",
             "--entrypoint", "python", "python:3.12-slim-bookworm", "-"],
            input=LINUX_TEST.encode(), capture_output=True, timeout=90)
        self.assertEqual(0, result.returncode, result.stdout.decode() + result.stderr.decode())
        self.assertIn("Ran 9 tests", result.stderr.decode())


if __name__ == "__main__":
    unittest.main()
