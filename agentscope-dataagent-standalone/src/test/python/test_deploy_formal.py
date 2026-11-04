"""Exercise the real host entry in isolated Linux repositories with a fake Docker CLI."""
import json
import os
from pathlib import Path
import shutil
import socket
import subprocess
import tempfile
import unittest

SCRIPTS = Path(__file__).resolve().parents[3] / "docker"
OLD = "sha256:" + "a" * 64
NEW = "sha256:" + "b" * 64
TAG = "dataagent/app:fixture-release"


def git(where, *args):
    return subprocess.check_output(["/usr/bin/git", *args], cwd=where, stderr=subprocess.DEVNULL).decode().strip()


def executable(path, content):
    path.write_text(content, encoding="utf-8")
    path.chmod(0o700)


DOCKER = r'''#!/usr/bin/env python3
import json,os,pathlib,sys
a=sys.argv[1:];r=pathlib.Path(os.environ['CASE_DIR']);mode=os.environ.get('FAILURE','')
state=json.loads((r/'state.json').read_text());cfg=r/'formal/compose.env'
old='sha256:'+'a'*64;new='sha256:'+'b'*64
def event(text):
 with (r/'events').open('a') as f:f.write(text+'\n')
if a[0]=='ps':
 print('c'*12);sys.exit(0)
if a[0]=='inspect':
 fmt=a[a.index('--format')+1]
 if '.Image' in fmt:print(state['image'])
 elif 'Health.Status' in fmt:print(state['health'])
 elif 'dataagent.rehearsal.app' in fmt:print(r/'formal')
 else:sys.exit(2)
 sys.exit(0)
if a[:2]==['image','inspect']:
 if '--format' not in a:sys.exit(0)
 fmt=a[a.index('--format')+1];ref=a[-1]
 image=new if ref.startswith('dataagent/app:') else ref if ref.startswith('sha256:') else old
 if 'org.opencontainers.image.revision' in fmt:
  print((r/('old' if ref==old else 'target')).read_text().strip() if mode!='revision' else 'd'*40)
 elif '.Os' in fmt:print('linux/amd64 true' if 'application' in fmt else 'linux/amd64')
 elif '.Id' in fmt:print(fmt.replace('{{.Id}}',image))
 else:sys.exit(2)
 sys.exit(0)
if a[:2]==['container','inspect']:sys.exit(1)
if a[0]=='build':event('image-build');sys.exit(0)
if a[0]=='run':
 if '/control/build-in-container.sh' in a:
  event('real-builder')
  for item in a:
   if item.startswith('type=bind,source=') and item.endswith(',target=/result'):
    p=pathlib.Path(item.split('source=',1)[1].split(',target=')[0]);(p/'app').mkdir(exist_ok=True);(p/'jar.sha256').write_text('fixture-jar-sha\n')
  sys.exit(0)
 if 'image-check' in a:sys.exit(4 if mode=='static-check' else 0)
 indexes=[i for i,x in enumerate(a) if x.endswith('/docker/formal-app.py')]
 if not indexes:sys.exit(3)
 action=a[indexes[0]+1];event(action)
 if action=='status':print('healthy');sys.exit(0)
 assert action=='upgrade'
 assert a[indexes[0]+2]=='dataagent/app:fixture-release'
 assert '--read-only' in a and '--group-add' in a and 'DATAAGENT_DOCKER_SUDO=false' in a
 if mode in ('preflight','recovered'):
  print('预检拒绝' if mode=='preflight' else '候选失败，原镜像已恢复');sys.exit(5)
 if mode=='recovery-failed':
  state['health']='unhealthy';(r/'state.json').write_text(json.dumps(state));sys.exit(6)
 state['image']=new if mode!='final-image' else 'sha256:'+'e'*64
 cfg.write_text(cfg.read_text().replace('APP_IMAGE='+old,'APP_IMAGE='+state['image']))
 (r/'state.json').write_text(json.dumps(state));sys.exit(0)
sys.exit(7)
'''

BUILD = r'''#!/usr/bin/env bash
set -eu
echo build >> "$CASE_DIR/events"
printf '%s' "${HTTP_PROXY:-unset}" > "$CASE_DIR/build-proxy"
if [[ "${FAILURE:-}" == config-change ]]; then echo 'UNEXPECTED_CHANGE=true' >> "$CASE_DIR/formal/compose.env"; fi
[[ "${FAILURE:-}" != build ]] || exit 8
revision="$1"
[[ "${FAILURE:-}" != source ]] || revision=dddddddddddddddddddddddddddddddddddddddd
[[ "${FAILURE:-}" != missing-result ]] || exit 0
image_id=sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb
[[ "${FAILURE:-}" != image-id ]] || image_id=sha256:ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff
printf 'source=%s\nimage=dataagent/app:fixture-release\nimageId=%s\n' "$revision" "$image_id" > "$DATAAGENT_BUILD_RESULT_FILE"
'''


class DeploymentTest(unittest.TestCase):
    def setUp(self):
        self.root = Path(tempfile.mkdtemp(prefix="formal-release-"))
        self.repo = self.root / "repo"
        self.repo.mkdir()
        git(self.repo, "init", "-b", "develop-2.0")
        git(self.repo, "config", "user.name", "fixture")
        git(self.repo, "config", "user.email", "fixture@example.invalid")
        self.module = self.repo / "agentscope-dataagent-standalone"
        self.scripts = self.module / "docker"
        self.scripts.mkdir(parents=True)
        for name in ("deploy-formal.sh", "update-source.sh"):
            shutil.copyfile(SCRIPTS / name, self.scripts / name)
        executable(self.scripts / "build-from-source.sh", BUILD)
        (self.scripts / "formal-app.py").write_text("# Controller invocation is mocked, not reimplemented.\n")
        (self.repo / "version").write_text("old\n")
        git(self.repo, "add", ".")
        git(self.repo, "commit", "-m", "base")
        self.old = git(self.repo, "rev-parse", "HEAD")
        (self.root / "old").write_text(self.old)
        git(self.root, "clone", "--bare", str(self.repo), str(self.root / "origin.git"))
        git(self.repo, "remote", "add", "origin", str(self.root / "origin.git"))
        self.target = self.old
        self.base = self.root / "formal"
        self.base.mkdir()
        (self.base / ".dataagent-app").write_text(str(self.base) + "|dataagent66-formal-app\n")
        self.cache = self.root / "cache"
        (self.cache / "hub").mkdir(parents=True)
        (self.base / "compose.env").write_text(
            f"APP_BASE={self.base}\nAPP_IMAGE={OLD}\nDB_MODE=external\nAPP_PORT=18080\nAPP_BIND_IP=127.0.0.1\nAPP_CACHE={self.cache}\n")
        (self.base / "runtime.env").write_text("PRIVATE_SENTINEL=preserve\n")
        self.bin = self.root / "bin"
        self.bin.mkdir()
        executable(self.bin / "docker", DOCKER)
        executable(self.bin / "sudo", '#!/bin/sh\nexec "$@"\n')
        executable(self.bin / "curl", '#!/bin/sh\n[ "${FAILURE:-}" != health ] || exit 22\nprintf \'{"status":"UP"}\\n\'\n')
        executable(self.bin / "sleep", '#!/bin/sh\nexit 0\n')
        executable(self.bin / "git", r'''#!/bin/bash
if [[ "$1" == ls-remote ]]; then
 printf '%s' "${HTTP_PROXY:-unset}" > "$CASE_DIR/probe-proxy"
 [[ "${FAILURE:-}" != probe ]] || { echo 'https://user:fixture-password@example.invalid unavailable' >&2; exit 22; }
 [[ "${FAILURE:-}" != probe-timeout ]] || exit 124
 [[ "${FAILURE:-}" != wrong-ref ]] || { printf '%040d\trefs/heads/other\n' 1; exit 0; }
fi
if [[ "$1" == fetch && "${FAILURE:-}" == fetch ]]; then echo fetch >> "$CASE_DIR/events"; exit 22; fi
exec /usr/bin/git "$@"
''')
        self.sock = socket.socket(socket.AF_UNIX)
        self.sock.bind(str(self.root / "docker.sock"))
        self.env = dict(os.environ, CASE_DIR=str(self.root), HOME=str(self.root / "home"),
                        PATH=str(self.bin) + ":" + os.environ["PATH"],
                        DATAAGENT_FORMAL_APP_DIR=str(self.base), DATAAGENT_COMPOSE_PLUGIN=str(self.bin / "docker"),
                        DATAAGENT_DOCKER_SOCKET=str(self.root / "docker.sock"),
                        HTTP_PROXY="http://fixture.invalid:8080", HTTPS_PROXY="http://fixture.invalid:8080")
        (self.root / "state.json").write_text(json.dumps(dict(image=OLD, health="healthy")))
        (self.root / "events").write_text("")

    def tearDown(self):
        self.sock.close()
        # All files are private fixtures inside the container's /tmp; no host resources exist here.
        shutil.rmtree(self.root)

    def publish(self, self_update=False):
        writer = self.root / "writer"
        git(self.root, "clone", str(self.root / "origin.git"), str(writer))
        git(writer, "config", "user.name", "fixture")
        git(writer, "config", "user.email", "fixture@example.invalid")
        (writer / "version").write_text("new\n")
        if self_update:
            script = writer / "agentscope-dataagent-standalone/docker/deploy-formal.sh"
            script.write_text(script.read_text().replace("phase=build\n", "echo TARGET_ENTRY_LOADED\n    phase=build\n"))
            updater = writer / "agentscope-dataagent-standalone/docker/update-source.sh"
            updater.write_text("# Target updater has a different length.\n" * 100 + updater.read_text().replace(
                "echo '源码已更新", "echo TARGET_UPDATER_ONLY_ON_NEXT_RUN\n    echo '源码已更新"))
        git(writer, "add", ".")
        git(writer, "commit", "-m", "target")
        git(writer, "push", "origin", "develop-2.0")
        self.target = git(writer, "rev-parse", "HEAD")
        (self.root / "target").write_text(self.target)

    def run_entry(self, **extra):
        result = subprocess.run(["bash", str(self.scripts / "deploy-formal.sh")],
                                cwd=self.repo, env=dict(self.env, **extra), text=True, capture_output=True, timeout=30)
        self.output = result.stdout + result.stderr
        self.assertEqual((self.base / "runtime.env").read_text(), "PRIVATE_SENTINEL=preserve\n")
        self.assertEqual(git(self.repo, "symbolic-ref", "--short", "HEAD"), "develop-2.0")
        return result

    def status(self):
        runs = sorted((self.base / "logs/releases").glob("*/status.env"))
        return runs[-1].read_text() if runs else ""

    def test_success_and_self_update_use_target_entry(self):
        self.publish(self_update=True)
        self.assertEqual(self.run_entry().returncode, 0, self.output)
        self.assertIn("TARGET_ENTRY_LOADED", self.output)
        self.assertNotIn("TARGET_UPDATER_ONLY_ON_NEXT_RUN", self.output)
        self.assertIn("status=SUCCESS", self.status())
        self.assertEqual((self.root / "probe-proxy").read_text(), "unset")
        self.assertEqual((self.root / "build-proxy").read_text(), "http://fixture.invalid:8080")
        self.assertEqual((self.root / "events").read_text().splitlines(), ["build", "upgrade", "status"])

    def test_same_revision_skips_build_and_restart(self):
        (self.root / "target").write_text(self.old)
        self.assertEqual(self.run_entry().returncode, 0, self.output)
        self.assertIn("status=SKIPPED", self.status())
        self.assertEqual((self.root / "events").read_text(), "")

    def test_inherit_proxy_mode(self):
        self.publish()
        self.assertEqual(self.run_entry(DATAAGENT_GIT_NETWORK="inherit").returncode, 0, self.output)
        self.assertEqual((self.root / "probe-proxy").read_text(), "http://fixture.invalid:8080")

    def test_dirty_source_preserved(self):
        self.publish()
        (self.repo / "untracked").write_text("preserve")
        self.assertNotEqual(self.run_entry().returncode, 0)
        self.assertEqual(git(self.repo, "rev-parse", "HEAD"), self.old)
        self.assertEqual((self.repo / "untracked").read_text(), "preserve")

    def test_wrong_branch_is_not_switched(self):
        git(self.repo, "checkout", "-b", "personal")
        result = subprocess.run(["bash", str(self.scripts / "deploy-formal.sh")],
                                cwd=self.repo, env=self.env, capture_output=True, timeout=30)
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(git(self.repo, "symbolic-ref", "--short", "HEAD"), "personal")

    def test_diverged_history_is_not_reset(self):
        self.publish()
        (self.repo / "local-only").write_text("preserve")
        git(self.repo, "add", ".")
        git(self.repo, "commit", "-m", "local-only")
        local = git(self.repo, "rev-parse", "HEAD")
        self.assertNotEqual(self.run_entry().returncode, 0)
        self.assertEqual(git(self.repo, "rev-parse", "HEAD"), local)
        self.assertEqual((self.repo / "local-only").read_text(), "preserve")

    def test_wrong_directory_marker_stops(self):
        (self.base / ".dataagent-app").write_text("other")
        self.assertNotEqual(self.run_entry().returncode, 0)
        self.assertEqual((self.root / "events").read_text(), "")

    def test_private_continuation_cannot_be_called_without_held_locks(self):
        run = self.base / "logs/releases/fake"
        run.mkdir(parents=True)
        (run / "context.env").write_text(f"repo={self.repo}\nsource={self.old}\npreviousImage={OLD}\n")
        result = subprocess.run(["bash", str(self.scripts / "deploy-formal.sh"), "--continue", str(run)],
                                cwd=self.repo, env=self.env, capture_output=True, timeout=30)
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual((self.root / "events").read_text(), "")

    def test_missing_compose_plugin_stops_before_build(self):
        self.publish()
        self.assertNotEqual(self.run_entry(DATAAGENT_COMPOSE_PLUGIN=str(self.root / "missing-plugin")).returncode, 0)
        self.assertEqual((self.root / "events").read_text(), "")

    def test_release_lock_blocks_second_publisher(self):
        import fcntl
        with (self.base / ".deploy.lock").open("w") as lock:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
            self.assertNotEqual(self.run_entry().returncode, 0)
        self.assertEqual((self.root / "events").read_text(), "")

    def test_standalone_source_update_lock_is_respected(self):
        import fcntl
        self.publish()
        with (self.repo / ".git/dataagent-source.lock").open("w") as lock:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
            self.assertNotEqual(self.run_entry().returncode, 0)
        self.assertEqual(git(self.repo, "rev-parse", "HEAD"), self.old)
        self.assertEqual((self.root / "events").read_text(), "")

    def test_probe_failure_stops_and_redacts_url_credentials(self):
        self.publish()
        self.assertNotEqual(self.run_entry(FAILURE="probe").returncode, 0)
        self.assertIn("phase=source-update", self.status())
        self.assertEqual(git(self.repo, "rev-parse", "HEAD"), self.old)
        self.assertNotIn("fixture-password", self.output)
        for log in (self.base / "logs/releases").glob("*/deploy.log"):
            self.assertNotIn("fixture-password", log.read_text())

    def test_fetch_retries_are_bounded(self):
        self.publish()
        self.assertNotEqual(self.run_entry(FAILURE="fetch").returncode, 0)
        self.assertEqual((self.root / "events").read_text().splitlines(), ["fetch"] * 3)
        self.assertEqual(git(self.repo, "rev-parse", "HEAD"), self.old)

    def test_timed_out_probe_and_wrong_ref_stop_before_fetch(self):
        self.publish()
        for failure in ("probe-timeout", "wrong-ref"):
            with self.subTest(failure=failure):
                self.assertNotEqual(self.run_entry(FAILURE=failure).returncode, 0)
                self.assertEqual(git(self.repo, "rev-parse", "HEAD"), self.old)
                self.assertEqual((self.root / "events").read_text(), "")

    def test_build_or_record_failures_never_upgrade(self):
        self.publish()
        for failure in ("build", "source", "missing-result", "image-id", "revision", "config-change"):
            with self.subTest(failure=failure):
                (self.root / "events").write_text("")
                self.assertNotEqual(self.run_entry(FAILURE=failure).returncode, 0)
                self.assertNotIn("upgrade", (self.root / "events").read_text())
                self.assertEqual(json.loads((self.root / "state.json").read_text())["image"], OLD)

    def test_controller_failures_report_old_health_or_failed_recovery(self):
        self.publish()
        for failure in ("preflight", "recovered", "recovery-failed"):
            with self.subTest(failure=failure):
                (self.root / "state.json").write_text(json.dumps(dict(image=OLD, health="healthy")))
                self.assertNotEqual(self.run_entry(FAILURE=failure).returncode, 0)
                self.assertIn("status=FAILED", self.status())
                self.assertIn("仍为原镜像且健康" if failure != "recovery-failed" else "未确认健康", self.output)

    def test_inconsistent_final_image_is_not_success(self):
        self.publish()
        self.assertNotEqual(self.run_entry(FAILURE="final-image").returncode, 0)
        self.assertIn("phase=verification", self.status())
        self.assertIn("status=FAILED", self.status())

    def test_invalid_network_mode_is_rejected(self):
        self.publish()
        self.assertNotEqual(self.run_entry(DATAAGENT_GIT_NETWORK="invalid").returncode, 0)
        self.assertEqual((self.root / "events").read_text(), "")

    def test_unhealthy_baseline_is_not_replaced(self):
        self.publish()
        (self.root / "state.json").write_text(json.dumps(dict(image=OLD, health="unhealthy")))
        self.assertNotEqual(self.run_entry().returncode, 0)
        self.assertEqual(git(self.repo, "rev-parse", "HEAD"), self.old)
        self.assertEqual((self.root / "events").read_text(), "")

    def test_health_verification_failure_is_not_success(self):
        self.publish()
        self.assertNotEqual(self.run_entry(FAILURE="health").returncode, 0)
        self.assertIn("status=FAILED", self.status())

    def prepare_real_builder(self):
        shutil.copyfile(SCRIPTS / "build-from-source.sh", self.scripts / "build-from-source.sh")
        for name in ("build-in-container.sh", "build-maven-settings.xml", "application.Dockerfile", "application-entrypoint.sh"):
            (self.scripts / name).write_text("fixture\n")
        (self.repo / "agentscope-core").mkdir()
        (self.repo / "agentscope-core/fixture").touch()
        (self.module / "pom.xml").write_text("fixture\n")
        git(self.repo, "add", ".")
        git(self.repo, "commit", "-m", "real build wrapper fixture")

    def run_real_builder(self, path, **extra):
        env = dict(self.env, DATAAGENT_BUILD_ROOT=str(self.root / "builds"),
                   DATAAGENT_BUILD_RESULT_FILE=str(path), **extra)
        return subprocess.run(["bash", str(self.scripts / "build-from-source.sh"), "HEAD"],
                              cwd=self.repo, env=env, text=True, capture_output=True, timeout=30)

    def test_real_builder_publishes_only_its_successful_result(self):
        self.prepare_real_builder()
        result_file = self.root / "build-result.env"
        result = self.run_real_builder(result_file)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        fields = dict(line.split("=", 1) for line in result_file.read_text().splitlines() if "=" in line)
        self.assertEqual(fields["source"], git(self.repo, "rev-parse", "HEAD"))
        self.assertEqual(fields["imageId"], NEW)
        self.assertTrue(Path(fields["record"]).is_file())
        previous = result_file.read_bytes()
        self.assertNotEqual(self.run_real_builder(result_file).returncode, 0)
        self.assertEqual(result_file.read_bytes(), previous)

    def test_real_builder_static_failure_has_no_result_file(self):
        self.prepare_real_builder()
        result_file = self.root / "build-result.env"
        self.assertNotEqual(self.run_real_builder(result_file, FAILURE="static-check").returncode, 0)
        self.assertFalse(result_file.exists())


if __name__ == "__main__":
    unittest.main(verbosity=2)
