"""Exercise source-build guards in an isolated Linux repository; never deploy a service."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
import zipfile
import shutil

ROOT = Path(__file__).resolve().parents[3]

LINUX_TEST = r'''
import os, pathlib, shutil, subprocess, tempfile, unittest

class Guards(unittest.TestCase):
    def setUp(self):
        self.base = pathlib.Path(tempfile.mkdtemp(prefix='source-build-guard-'))
        self.repo = self.base / 'repo'
        self.scripts = self.repo / 'agentscope-dataagent-standalone/docker'
        self.scripts.mkdir(parents=True)
        (self.repo / 'agentscope-core').mkdir()
        (self.repo / 'agentscope-core/pom.xml').write_text('<project/>')
        (self.repo / 'agentscope-dataagent-standalone/pom.xml').write_text('<project/>')
        for name in ['build-from-source.sh', 'build-in-container.sh', 'build-maven-settings.xml', 'update-source.sh',
                     'application.Dockerfile', 'application-entrypoint.sh']:
            shutil.copy2(pathlib.Path('/scripts') / name, self.scripts / name)
        self.git('init', '-q')
        self.git('config', 'user.name', 'fixture')
        self.git('config', 'user.email', 'fixture@localhost')
        self.git('add', '.')
        self.git('commit', '-qm', 'fixture')
        self.bin = self.base / 'bin'
        self.bin.mkdir()
        self.log = self.base / 'docker.log'
        mock = self.bin / 'docker'
        mock.write_text("""#!/bin/bash
printf '%s\\n' "$*" >> "$TEST_DOCKER_LOG"
if [[ "$1 $2" == 'image inspect' ]]; then
  [[ "${TEST_MISSING_RUNTIME:-0}" == 1 && "$*" == *dataagent/runtime* ]] && exit 1
  [[ "$*" == *--format* ]] && echo linux/amd64
  exit 0
fi
[[ "$1" == run ]] && exit 23
exit 1
""")
        mock.chmod(0o755)
        self.env = dict(os.environ, PATH=str(self.bin)+':'+os.environ['PATH'],
                        DATAAGENT_DOCKER_SUDO='false', DATAAGENT_BUILD_ROOT=str(self.base/'build'),
                        TEST_DOCKER_LOG=str(self.log))

    def git(self, *args):
        return subprocess.check_output(['git', *args], cwd=self.repo, stderr=subprocess.DEVNULL).decode().strip()

    def run_build(self, *args):
        return subprocess.run(['bash', str(self.scripts/'build-from-source.sh'), *args],
                              env=self.env, capture_output=True, text=True, timeout=20)

    def test_dirty_source_rejected_before_docker(self):
        (self.repo/'agentscope-core/pom.xml').write_text('modified')
        r = self.run_build()
        self.assertNotEqual(r.returncode, 0)
        self.assertIn('未提交修改', r.stderr)
        self.assertFalse(self.log.exists())

    def test_uncommitted_control_file_rejected(self):
        self.git('rm', '--cached', 'agentscope-dataagent-standalone/docker/application.Dockerfile')
        self.git('commit', '-qm', 'untrack control')
        r = self.run_build()
        self.assertIn('文件尚未提交', r.stderr)
        self.assertFalse(self.log.exists())

    def test_other_commit_rejected(self):
        old = self.git('rev-parse', 'HEAD')
        (self.repo/'extra').write_text('next')
        self.git('add', 'extra')
        self.git('commit', '-qm', 'next')
        r = self.run_build(old)
        self.assertIn('当前HEAD不同', r.stderr)
        self.assertFalse(self.log.exists())

    def test_ref_argument_not_interpreted_as_code(self):
        r = self.run_build('HEAD; touch /tmp/source-build-injection')
        self.assertIn('完整提交号', r.stderr)
        self.assertFalse(pathlib.Path('/tmp/source-build-injection').exists())
        self.assertFalse(self.log.exists())

    def test_missing_runtime_never_runs_builder(self):
        self.env['TEST_MISSING_RUNTIME'] = '1'
        r = self.run_build()
        self.assertIn('runtime镜像不存在', r.stderr)
        self.assertNotIn('run ', self.log.read_text())

    def test_compilation_failure_never_builds_or_deploys_image(self):
        r = self.run_build()
        self.assertNotEqual(r.returncode, 0)
        log = self.log.read_text()
        self.assertIn('run --rm --init', log)
        self.assertIn('--cpus 2 --memory 6g', log)
        self.assertNotIn('/var/run/docker.sock', log)
        self.assertNotIn('compose', log)
        self.assertNotIn('prune', log)
        self.assertFalse(any(line.startswith('build ') for line in log.splitlines()))
        self.assertFalse(any(line.startswith('stop ') for line in log.splitlines()))

    def update(self):
        self.env['DATAAGENT_SOURCE_BRANCH'] = self.git('symbolic-ref', '--short', 'HEAD')
        return subprocess.run(['bash', str(self.scripts/'update-source.sh')], env=self.env,
                              capture_output=True, text=True, timeout=25)

    def test_update_refuses_untracked_file(self):
        (self.repo/'operator-notes').write_text('keep')
        r = self.update()
        self.assertNotEqual(r.returncode, 0)
        self.assertIn('不覆盖', r.stderr)
        self.assertEqual((self.repo/'operator-notes').read_text(), 'keep')

    def test_failed_fetch_retries_only_three_times(self):
        self.git('remote', 'add', 'origin', str(self.base/'missing.git'))
        before = self.git('rev-parse', 'HEAD')
        r = self.update()
        self.assertNotEqual(r.returncode, 0)
        self.assertEqual(r.stdout.count('拉取指定分支'), 3)
        self.assertEqual(self.git('rev-parse', 'HEAD'), before)

    def test_update_fast_forwards_without_merging(self):
        remote = self.base/'remote.git'
        subprocess.check_call(['git', 'clone', '--bare', '-q', str(self.repo), str(remote)])
        self.git('remote', 'add', 'origin', str(remote))
        other = self.base/'other'
        subprocess.check_call(['git', 'clone', '-q', str(remote), str(other)])
        (other/'new-release').write_text('next')
        subprocess.check_call(['git', 'add', '.'], cwd=other)
        subprocess.check_call(['git', '-c', 'user.name=fixture', '-c', 'user.email=fixture@localhost',
                               'commit', '-qm', 'next'], cwd=other)
        subprocess.check_call(['git', 'push', '-q', 'origin', 'HEAD'], cwd=other)
        r = self.update()
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertEqual((self.repo/'new-release').read_text(), 'next')

    def test_update_does_not_switch_branches(self):
        self.env['DATAAGENT_SOURCE_BRANCH'] = 'another-branch'
        r = subprocess.run(['bash', str(self.scripts/'update-source.sh')], env=self.env,
                           capture_output=True, text=True, timeout=10)
        self.assertNotEqual(r.returncode, 0)
        self.assertIn('不自动切换', r.stderr)

unittest.main(verbosity=2)
'''


@unittest.skipUnless(os.environ.get("DATAAGENT_CONTAINER_TESTS") == "1", "real Docker opt-in")
class SourceBuildTest(unittest.TestCase):
    def test_guards_in_linux(self):
        image = os.environ.get("DATAAGENT_TEST_RUNTIME_IMAGE", "dataagent/runtime:20261028")
        result = subprocess.run(
            ["docker", "run", "--rm", "--network", "none", "-i", "--cpus", "1", "--memory", "512m",
             "--mount", "type=bind,source=" + (ROOT / "docker").as_posix() + ",target=/scripts,readonly",
             "--entrypoint", "python", image, "-"],
            input=LINUX_TEST.encode("utf-8"), capture_output=True, timeout=90,
        )
        self.assertEqual(result.returncode, 0, result.stdout.decode() + result.stderr.decode())
        self.assertIn("Ran 10 tests", result.stderr.decode())


@unittest.skipUnless(os.environ.get("DATAAGENT_CONTAINER_TESTS") == "1"
                     and os.environ.get("DATAAGENT_TEST_APPLICATION_IMAGE"), "application image opt-in")
class ApplicationImageTest(unittest.TestCase):
    def run_image(self, *args):
        image = os.environ["DATAAGENT_TEST_APPLICATION_IMAGE"]
        return subprocess.run(
            ["docker", "run", "--rm", "--pull", "never", "--network", "none", "--read-only",
             "--tmpfs", "/tmp:rw,nosuid,size=256m", "--memory", "1g", "--cpus", "1",
             *args[:-1], image, args[-1]], capture_output=True, timeout=60,
        )

    def test_offline_image_check_without_host_configuration(self):
        result = self.run_image("image-check")
        self.assertEqual(result.returncode, 0, result.stderr.decode())
        self.assertIn("镜像应用文件和依赖预检通过", result.stdout.decode())

    def test_missing_external_configuration_blocks_start(self):
        result = self.run_image("-e", "DATAAGENT_BASE_DIR=/tmp", "run")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("缺少外置conf/application-prod.yml", result.stderr.decode())

    def test_invalid_port_blocks_start(self):
        result = self.run_image("-e", "DATAAGENT_BASE_DIR=/tmp", "-e", "DATAAGENT_PORT=0", "run")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("端口无效", result.stderr.decode())

    def test_private_profile_inside_jar_is_rejected(self):
        with tempfile.TemporaryDirectory(prefix="dataagent-private-profile-test-") as folder:
            fixture = Path(folder)
            (fixture / "bin").mkdir()
            (fixture / "lib").mkdir()
            shutil.copy2(ROOT / "src/main/bin/check-wren.py", fixture / "bin/check-wren.py")
            shutil.copy2(ROOT / "requirements-wren.txt", fixture / "requirements-wren.txt")
            with zipfile.ZipFile(fixture / "lib/dataagent-exec.jar", "w") as archive:
                archive.writestr("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\n")
                archive.writestr("BOOT-INF/classes/application-prod.yml", "# synthetic forbidden fixture\n")
            result = self.run_image("--mount", "type=bind,source=" + fixture.as_posix()
                                    + ",target=/fixture,readonly", "-e", "DATAAGENT_APP_HOME=/fixture", "image-check")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("application-prod.yml", result.stderr.decode())


if __name__ == "__main__":
    unittest.main()
