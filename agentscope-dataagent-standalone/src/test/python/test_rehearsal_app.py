"""Real application-image deployment checks on an isolated MySQL and persistent root."""
import json
import os
from pathlib import Path
import socket
import subprocess
import tempfile
import unittest
import urllib.request
import uuid

ROOT = Path(__file__).resolve().parents[3]


@unittest.skipUnless(os.environ.get("DATAAGENT_APP_REHEARSAL_TESTS") == "1", "real application/Docker opt-in")
class RehearsalAppTest(unittest.TestCase):
    def test_real_image_login_persistence_and_preflight_failure(self):
        suffix = uuid.uuid4().hex[:8]
        folder = "/var/lib/dataagent-app-test-" + suffix
        db_project = "dataagent-db-test-" + suffix
        app_project = "dataagent-app-test-" + suffix
        runtime = os.environ.get("DATAAGENT_TEST_RUNTIME_IMAGE", "dataagent/runtime:20261028")
        app = os.environ["DATAAGENT_TEST_APPLICATION_IMAGE"]
        mysql = os.environ["DATAAGENT_TEST_MYSQL_IMAGE"]
        plugin = Path(os.environ["DATAAGENT_TEST_COMPOSE_PLUGIN"]).resolve()
        cache = os.environ["DATAAGENT_TEST_MODEL_CACHE"]
        with socket.socket() as probe:
            probe.bind(("127.0.0.1", 0))
            port = probe.getsockname()[1]
        common = ["docker", "run", "--rm", "--network", "none", "--label", "dataagent.test=" + suffix,
                  "-v", folder + ":" + folder,
                  "--mount", "type=bind,source=/var/run/docker.sock,target=/var/run/docker.sock",
                  "--mount", "type=bind,source=" + ROOT.as_posix() + ",target=/module,readonly",
                  "--mount", "type=bind,source=" + plugin.as_posix()
                  + ",target=/usr/local/lib/docker/cli-plugins/docker-compose,readonly",
                  "-e", "DATAAGENT_DOCKER_SUDO=false", "-e", "DATAAGENT_SOURCE_MODULE=/module",
                  "-e", "DATAAGENT_REHEARSAL_DB_DIR=" + folder + "/db",
                  "-e", "DATAAGENT_REHEARSAL_DB_PROJECT=" + db_project,
                  "-e", "DATAAGENT_REHEARSAL_MYSQL_IMAGE=" + mysql,
                  "-e", "DATAAGENT_REHEARSAL_APP_DIR=" + folder + "/app",
                  "-e", "DATAAGENT_REHEARSAL_APP_PROJECT=" + app_project,
                  "-e", "DATAAGENT_APP_IMAGE=" + app,
                  "-e", "DATAAGENT_MODEL_CACHE=" + folder + "/cache",
                  "-e", "DATAAGENT_BIND_IP=127.0.0.1", "-e", "DATAAGENT_PORT=" + str(port),
                  "-e", "DATAAGENT_RUNTIME_CPUS=2", "-e", "DATAAGENT_RUNTIME_MEMORY=6g"]
        setup = subprocess.run(common + ["--mount", "type=bind,source=" + cache + ",target=/oldcache,readonly",
                               "--entrypoint", "python", runtime, "-c",
                               'import os,pathlib,shutil; p=pathlib.Path("' + folder + '"); shutil.copytree("/oldcache",p/"cache"); os.chown(p,1000,1000); print(os.stat("/var/run/docker.sock").st_gid)'],
                               capture_output=True, timeout=90)
        self.assertEqual(setup.returncode, 0, setup.stderr.decode())
        group = setup.stdout.decode().strip().splitlines()[-1]
        common += ["--user", "1000:1000", "--group-add", group, "--entrypoint", "bash", runtime]

        def run(script, action, *extra, check=True):
            result = subprocess.run(common + ["/module/docker/" + script, action, *extra], capture_output=True, timeout=360)
            if check and result.returncode:
                logs = subprocess.run(common + ["/module/docker/rehearsal-app.sh", "logs"], capture_output=True, timeout=20)
                self.fail((result.stdout + result.stderr + logs.stdout + logs.stderr).decode())
            return result

        def login(username):
            request = urllib.request.Request("http://127.0.0.1:" + str(port) + "/api/auth/bi-account-login",
                                             data=json.dumps({"username": username}).encode(),
                                             headers={"Content-Type": "application/json"})
            with urllib.request.urlopen(request, timeout=20) as response:
                return json.load(response)

        def app_container():
            return subprocess.check_output(["docker", "ps", "-q", "--filter", "label=com.docker.compose.project=" + app_project]).decode().strip()

        try:
            run("rehearsal-mysql.sh", "prepare")
            run("rehearsal-mysql.sh", "start")
            run("rehearsal-app.sh", "prepare")
            before = subprocess.check_output(common + ["-c", 'sha256sum "$DATAAGENT_REHEARSAL_APP_DIR/runtime.env"'])
            run("rehearsal-app.sh", "prepare")
            self.assertEqual(before, subprocess.check_output(common + ["-c", 'sha256sum "$DATAAGENT_REHEARSAL_APP_DIR/runtime.env"']))
            run("rehearsal-app.sh", "start")
            first = login("deploy_probe_" + suffix)
            self.assertEqual(first["roles"], ["user"])
            uid = first["userId"]
            token = first["token"]
            container = app_container()
            self.assertTrue(container)
            bad = run("rehearsal-app.sh", "upgrade", "nonexistent/deploy-fixture:missing", check=False)
            self.assertNotEqual(bad.returncode, 0)
            self.assertEqual(app_container(), container)
            with tempfile.TemporaryDirectory(prefix="dataagent-image-fixture-") as build_dir:
                dockerfile = Path(build_dir) / "Dockerfile"
                fixtures = {}
                for label, opts in [("bad", "-InvalidDeploymentFixtureFlag"),
                                    ("good", "-Xms256m -Xmx1024m -Ddataagent.deploy.fixture=1")]:
                    fixture = "dataagent/app-deploy-test:" + suffix + "-" + label
                    dockerfile.write_bytes(("FROM " + app + "\nENV JAVA_OPTS=\"" + opts + "\"\n").encode())
                    build = subprocess.run(["docker", "build", "--pull=false", "--network", "none", "-q", "-t", fixture, build_dir], capture_output=True, timeout=40)
                    self.assertEqual(build.returncode, 0, build.stderr.decode())
                    fixtures[label] = fixture
            failed = run("rehearsal-app.sh", "upgrade", fixtures["bad"], check=False)
            self.assertNotEqual(failed.returncode, 0)
            self.assertIn("已恢复原镜像", failed.stderr.decode())
            self.assertEqual(login("deploy_probe_" + suffix)["userId"], uid)
            run("rehearsal-app.sh", "upgrade", fixtures["good"])
            run("rehearsal-app.sh", "rollback")
            actual_image = subprocess.check_output(["docker", "inspect", "--format", "{{.Image}}", app_container()]).strip()
            expected_image = subprocess.check_output(["docker", "image", "inspect", "--format", "{{.Id}}", app]).strip()
            self.assertEqual(actual_image, expected_image)
            subprocess.run(common + ["-c", 'printf persistent-state > "$DATAAGENT_REHEARSAL_APP_DIR/data/artifacts/deploy-test.txt"'], check=True, capture_output=True)
            run("rehearsal-app.sh", "stop")
            run("rehearsal-app.sh", "start")
            self.assertEqual(login("deploy_probe_" + suffix)["userId"], uid)
            request = urllib.request.Request("http://127.0.0.1:" + str(port) + "/api/auth/me", headers={"Authorization": "Bearer " + token})
            with urllib.request.urlopen(request, timeout=20) as response:
                self.assertEqual(json.load(response)["userId"], uid)
            self.assertEqual(before, subprocess.check_output(common + ["-c", 'sha256sum "$DATAAGENT_REHEARSAL_APP_DIR/runtime.env"']))
            result = subprocess.check_output(common + ["-c", 'cat "$DATAAGENT_REHEARSAL_APP_DIR/data/artifacts/deploy-test.txt"'])
            self.assertEqual(result, b"persistent-state")
            print("Verified real Java image, schema validation, non-root/socket group, offline vectors, sibling sandbox, first BI user, JWT/state persistence, failed preflight preserves old service, failed JVM recovers old image, successful upgrade and explicit rollback; project=" + app_project, flush=True)
        finally:
            run("rehearsal-app.sh", "stop", check=False)
            ids = subprocess.check_output(["docker", "ps", "-q", "--filter", "label=com.docker.compose.project=" + db_project]).decode().split()
            if ids:
                subprocess.run(["docker", "stop", "--time", "30", *ids], capture_output=True, timeout=50)
            print("Rehearsal data/config/images retained: " + folder, flush=True)


if __name__ == "__main__":
    unittest.main()
