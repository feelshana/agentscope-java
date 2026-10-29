"""Opt-in real Docker tests; compile a tiny fixture, never the business release."""
import json
import os
from pathlib import Path
import shutil
import socket
import subprocess
import time
import unittest
import uuid

ROOT = Path(__file__).resolve().parents[3]
IMAGE = os.environ.get("DATAAGENT_TEST_RUNTIME_IMAGE", "dataagent/runtime:20261028")
SANDBOX = "agentscope/dataagent-sandbox:20261028"


@unittest.skipUnless(os.environ.get("DATAAGENT_CONTAINER_TESTS") == "1", "real Docker opt-in")
class ContainerRuntimeTest(unittest.TestCase):
    @classmethod
    def command(cls, args, **kwargs):
        return subprocess.run(args, text=True, encoding="utf-8", capture_output=True, timeout=90, **kwargs)

    @classmethod
    def setUpClass(cls):
        cls.token = "dataagent-runtime-test-" + uuid.uuid4().hex[:10]
        cls.base = "/tmp/" + cls.token
        cls.names = []
        cls.artifacts = ROOT.parent / "output" / cls.token
        cls.artifacts.mkdir(parents=True)
        cls.fixture = cls.artifacts / "fixtures"
        release = cls.fixture / "release"
        for name in ("lib", "conf", "bin"):
            (release / name).mkdir(parents=True)
        (release / "conf/application-prod.yml").write_text("# fixture only\n", encoding="utf-8")
        shutil.copy2(ROOT / "requirements-wren.txt", release / "requirements-wren.txt")
        shutil.copy2(ROOT / "src/main/bin/check-wren.py", release / "bin/check-wren.py")
        shutil.copy2(ROOT / "docker/dataagent-container.sh", cls.fixture / "controller.sh")
        jdk = Path(os.environ["DATAAGENT_TEST_JDK_HOME"]) / "bin"
        suffix = ".exe" if os.name == "nt" else ""
        source = ROOT / "src/test/resources/container-runtime/RuntimeFixture.java"
        classes = cls.artifacts / "classes"
        classes.mkdir()
        result = cls.command([str(jdk / ("javac" + suffix)), "--release", "17", "-d", str(classes), str(source)])
        if result.returncode:
            raise RuntimeError(result.stderr)
        result = cls.command([str(jdk / ("jar" + suffix)), "--create", "--file",
                              str(release / "lib/runtime-fixture-exec.jar"), "--main-class",
                              "RuntimeFixture", "-C", str(classes), "."])
        if result.returncode:
            raise RuntimeError(result.stderr)
        prepared = cls.helper("""import pathlib,shutil,os
b=pathlib.Path(os.environ['TEST_BASE'])
shutil.copytree('/fixtures/release',b/'release',dirs_exist_ok=True)
shutil.copy2('/fixtures/controller.sh',b/'controller.sh')
for p in ['data/home/.cache/huggingface/hub','data/workspace/shared','outside','cache-empty']:
 (b/p).mkdir(parents=True,exist_ok=True)
(b/'data/workspace/shared/probe.txt').write_text('same-absolute-path-ok')
for p in [b,*b.rglob('*')]: os.chown(p,1010,1010)
print('fixture prepared')
""", fixtures=True)
        if prepared.returncode:
            raise RuntimeError(prepared.stderr)

    @classmethod
    def helper(cls, code, fixtures=False):
        args = ["docker", "run", "--rm", "--network", "none", "-i", "-v", cls.base + ":" + cls.base,
                "-e", "TEST_BASE=" + cls.base]
        if fixtures:
            args += ["--mount", "type=bind,source=" + cls.fixture.as_posix() + ",target=/fixtures,readonly"]
        args += ["--entrypoint", "python", IMAGE, "-u", "-"]
        return cls.command(args, input=code)

    @classmethod
    def tearDownClass(cls):
        for name in cls.names:
            inspect = cls.command(["docker", "inspect", name])
            if inspect.returncode:
                continue
            info = json.loads(inspect.stdout)[0]
            labels = info["Config"]["Labels"]
            if not (labels.get("dataagent.test") == cls.token or
                    labels.get("dataagent.runtime") == "true" and labels.get("dataagent.base") == cls.base):
                raise AssertionError("Refusing cleanup outside this fixture")
            if info["State"]["Running"]:
                cls.command(["docker", "stop", "--time", "10", name])
            cls.command(["docker", "rm", name])
        # Keep isolated fixture files and logs for diagnosis; never prune other resources.

    def options(self, **overrides):
        env = {"DATAAGENT_BASE_DIR": self.base,
               "DATAAGENT_RELEASE_DIR": self.base + "/release",
               "DATAAGENT_SANDBOX_IMAGE": SANDBOX}
        env.update(overrides)
        args = ["--network", "none", "--init", "--cpus", "2", "--memory", "4g",
                "-v", self.base + ":" + self.base,
                "-v", "/var/run/docker.sock:/var/run/docker.sock"]
        for key, value in env.items():
            args += ["-e", key + "=" + value]
        return args

    def test_check_dependencies_and_daemon(self):
        result = self.command(["docker", "run", "--rm", *self.options(), IMAGE, "check"])
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("运行环境与发布包预检通过", result.stdout)

    def test_bad_port_rejected(self):
        for port in ("0", "65536", "abc", "999999999999999999"):
            with self.subTest(port=port):
                result = self.command(["docker", "run", "--rm", *self.options(DATAAGENT_PORT=port), IMAGE, "check"])
                self.assertNotEqual(result.returncode, 0)
                self.assertIn("端口无效", result.stderr)

    def test_foreign_release_directory_rejected(self):
        result = self.command(["docker", "run", "--rm", *self.options(DATAAGENT_RELEASE_DIR="/tmp"), IMAGE, "check"])
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("发布目录必须直属", result.stderr)

    def test_missing_profile_rejected(self):
        result = self.command(["docker", "run", "--rm", *self.options(DATAAGENT_PROFILE="missing"), IMAGE, "check"])
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("缺少 conf/application-missing.yml", result.stderr)

    def test_missing_cache_rejected(self):
        result = self.command(["docker", "run", "--rm", *self.options(HF_HOME=self.base + "/cache-empty"), IMAGE, "check"])
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("缺少已准备的向量模型缓存", result.stderr)

    def test_multiple_jars_rejected(self):
        self.helper("import os,pathlib; (pathlib.Path(os.environ['TEST_BASE'])/'release/lib/extra-exec.jar').write_bytes(b'fixture')")
        try:
            result = self.command(["docker", "run", "--rm", *self.options(), IMAGE, "check"])
            self.assertNotEqual(result.returncode, 0)
            self.assertIn("有且仅有一个", result.stderr)
        finally:
            self.helper("import os,pathlib; (pathlib.Path(os.environ['TEST_BASE'])/'release/lib/extra-exec.jar').unlink()")

    def test_non_root_socket_group(self):
        result = self.command(["docker", "run", "--rm", "--user", "1010:1010", "--group-add", "0",
                               *self.options(), IMAGE, "check"])
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_sibling_container_same_absolute_shared_path(self):
        code = "import subprocess; subprocess.run(" + repr([
            "docker", "run", "--rm", "--pull", "never", "--network", "none",
            "--label", "dataagent.test=" + self.token,
            "--mount", "type=bind,source=" + self.base + "/data/workspace/shared,target=/shared,readonly",
            SANDBOX, "python", "-c", "from pathlib import Path; assert Path('/shared/probe.txt').read_text()=='same-absolute-path-ok'; print('sibling-shared-ok')"
        ]) + ",check=True)"
        result = self.command(["docker", "run", "--rm", *self.options(), "--entrypoint", "python", IMAGE, "-c", code])
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("sibling-shared-ok", result.stdout)

    def test_controller_rejects_foreign_labels(self):
        name = self.token + "-foreign"
        self.names.append(name)
        result = self.command(["docker", "run", "-d", "--name", name, "--label", "dataagent.test=" + self.token,
                               "--entrypoint", "python", IMAGE, "-c", "import time;time.sleep(120)"])
        self.assertEqual(result.returncode, 0, result.stderr)
        result = self.command(["docker", "run", "--rm", *self.options(), "-e", "DATAAGENT_DOCKER_SUDO=false",
                               "-e", "DATAAGENT_CONTAINER_NAME=" + name,
                               "--entrypoint", "bash", IMAGE, self.base + "/controller.sh", "stop"])
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("拒绝操作", result.stderr)
        info = self.command(["docker", "inspect", "--format", "{{.State.Running}}", name])
        self.assertEqual(info.stdout.strip(), "true")

    def test_controller_start_stop_replace_and_persistent_files(self):
        name = self.token + "-controlled"
        self.names.append(name)
        with socket.socket() as probe:
            probe.bind(("127.0.0.1", 0))
            port = str(probe.getsockname()[1])
        prepared = self.helper("import os,pathlib,shutil; b=pathlib.Path(os.environ['TEST_BASE']); (b/'runtime.env').write_text('DATAAGENT_PROFILE=prod\\n'); shutil.copytree(b/'release',b/'release2')")
        self.assertEqual(prepared.returncode, 0, prepared.stderr)

        def control(action, release="release"):
            return self.command(["docker", "run", "--rm", *self.options(),
                                 "-e", "DATAAGENT_RELEASE_DIR=" + self.base + "/" + release,
                                 "-e", "DATAAGENT_DOCKER_SUDO=false", "-e", "DATAAGENT_CONTAINER_NAME=" + name,
                                 "-e", "DATAAGENT_MODEL_CACHE=" + self.base + "/data/home/.cache/huggingface",
                                 "-e", "DATAAGENT_BIND_IP=127.0.0.1", "-e", "DATAAGENT_PORT=" + port,
                                 "-e", "DATAAGENT_RUNTIME_CPUS=1", "-e", "DATAAGENT_RUNTIME_MEMORY=1g",
                                 "-e", "DATAAGENT_SANDBOX_INSTANCE_ID=" + self.token,
                                 "-e", "DATAAGENT_SANDBOX_IMAGE=" + SANDBOX,
                                 "--entrypoint", "bash", IMAGE, self.base + "/controller.sh", action])

        started = control("start")
        self.assertEqual(started.returncode, 0, started.stderr)
        first = json.loads(self.command(["docker", "inspect", name]).stdout)[0]["Id"]
        configuration = json.loads(self.command(["docker", "inspect", name]).stdout)[0]["Config"]["Env"]
        self.assertIn("DATAAGENT_SANDBOX_INSTANCE_ID=" + self.token, configuration)
        self.assertIn("DATAAGENT_SANDBOX_IMAGE=" + SANDBOX, configuration)
        rejected = control("replace", "release2")
        self.assertNotEqual(rejected.returncode, 0)
        self.assertIn("先 stop", rejected.stderr)
        for release in ("release", "release2"):
            if release == "release2":
                replaced = control("replace", release)
                self.assertEqual(replaced.returncode, 0, replaced.stderr)
                info = json.loads(self.command(["docker", "inspect", name]).stdout)[0]
                self.assertNotEqual(info["Id"], first)
                self.assertIn("DATAAGENT_RELEASE_DIR=" + self.base + "/release2", info["Config"]["Env"])
            deadline = time.monotonic() + 45
            while time.monotonic() < deadline:
                logs = self.command(["docker", "logs", name])
                if "RuntimeFixture READY" in logs.stdout:
                    break
                time.sleep(.5)
            self.assertIn("RuntimeFixture READY", logs.stdout, logs.stderr)
            stopped = control("stop", release)
            self.assertEqual(stopped.returncode, 0, stopped.stderr)
            marker = self.helper("import os,pathlib; p=pathlib.Path(os.environ['TEST_BASE'])/'data/workspace'; assert (p/'fixture-terminated').exists(); print('preserved')")
            self.assertEqual(marker.returncode, 0, marker.stderr)

    def test_java_foreground_term_and_wren_subprocess(self):
        name = self.token + "-java"
        self.names.append(name)
        result = self.command(["docker", "run", "-d", "--name", name, "--label", "dataagent.test=" + self.token,
                               *self.options(), IMAGE])
        self.assertEqual(result.returncode, 0, result.stderr)
        deadline = time.monotonic() + 45
        while time.monotonic() < deadline:
            logs = self.command(["docker", "logs", name])
            if "RuntimeFixture READY" in logs.stdout:
                break
            time.sleep(.5)
        self.assertIn("RuntimeFixture READY", logs.stdout, logs.stderr)
        health = self.command(["docker", "exec", name, "curl", "--fail", "http://127.0.0.1:18080/actuator/health"])
        self.assertEqual(json.loads(health.stdout)["status"], "UP")
        stopped = self.command(["docker", "stop", "--time", "10", name])
        self.assertEqual(stopped.returncode, 0, stopped.stderr)
        status = self.command(["docker", "inspect", name])
        info = json.loads(status.stdout)[0]
        self.assertNotEqual(info["State"]["ExitCode"], 137, "Java required SIGKILL")
        markers = self.helper("import os,pathlib; p=pathlib.Path(os.environ['TEST_BASE'])/'data/workspace'; assert (p/'fixture-ready').read_text()=='java17-wren-child-ok'; assert (p/'fixture-terminated').read_text()=='TERM-received'; print('persistent-markers-ok')")
        self.assertEqual(markers.returncode, 0, markers.stderr)


if __name__ == "__main__":
    unittest.main()
