"""Opt-in real MySQL rehearsal checks; never modify an existing database or project."""
import json
import os
from pathlib import Path
import subprocess
import unittest
import uuid

ROOT = Path(__file__).resolve().parents[3]


@unittest.skipUnless(os.environ.get("DATAAGENT_MYSQL_REHEARSAL_TESTS") == "1", "real Docker opt-in")
class RehearsalMysqlTest(unittest.TestCase):
    def test_first_install_restart_permissions_and_guards(self):
        suffix = uuid.uuid4().hex[:8]
        project = "dataagent-mysql-test-" + suffix
        folder = "/var/lib/dataagent-mysql-test-" + suffix
        image = os.environ["DATAAGENT_TEST_MYSQL_IMAGE"]
        runtime = os.environ.get("DATAAGENT_TEST_RUNTIME_IMAGE", "dataagent/runtime:20261028")
        plugin = Path(os.environ["DATAAGENT_TEST_COMPOSE_PLUGIN"]).resolve()
        common = ["docker", "run", "--rm", "--network", "none", "--label", "dataagent.test=" + project,
                  "-v", folder + ":" + folder,
                  "--mount", "type=bind,source=/var/run/docker.sock,target=/var/run/docker.sock",
                  "--mount", "type=bind,source=" + ROOT.as_posix() + ",target=/module,readonly",
                  "--mount", "type=bind,source=" + plugin.as_posix()
                  + ",target=/usr/local/lib/docker/cli-plugins/docker-compose,readonly",
                  "-e", "DATAAGENT_DOCKER_SUDO=false", "-e", "DATAAGENT_SOURCE_MODULE=/module",
                  "-e", "DATAAGENT_REHEARSAL_DB_DIR=" + folder + "/db",
                  "-e", "DATAAGENT_REHEARSAL_DB_PROJECT=" + project,
                  "-e", "DATAAGENT_REHEARSAL_MYSQL_IMAGE=" + image,
                  "--entrypoint", "bash", runtime]
        def run(*args, check=True):
            result = subprocess.run(common + list(args), capture_output=True, timeout=300)
            if check:
                self.assertEqual(result.returncode, 0, (result.stdout + result.stderr).decode())
            return result
        def control(action, check=True):
            return run("/module/docker/rehearsal-mysql.sh", action, check=check)
        def sql(user, password_name, query, check=True):
            result = subprocess.run(["docker", "exec", container, "sh", "-ec",
                                     'export MYSQL_PWD="$' + password_name + '"; exec mysql --protocol=socket -u'
                                     + user + ' -Nse "$1"', "sh", query], capture_output=True, timeout=20)
            if check:
                self.assertEqual(result.returncode, 0, result.stderr.decode())
            return result
        container = None
        try:
            control("prepare")
            before = run("-c", 'sha256sum "$DATAAGENT_REHEARSAL_DB_DIR/mysql.env" "$DATAAGENT_REHEARSAL_DB_DIR/app-db.env"').stdout
            control("prepare")
            self.assertEqual(before, run("-c", 'sha256sum "$DATAAGENT_REHEARSAL_DB_DIR/mysql.env" "$DATAAGENT_REHEARSAL_DB_DIR/app-db.env"').stdout)
            denied = run("-c", 'mkdir -p "${DATAAGENT_REHEARSAL_DB_DIR}-foreign"; DATAAGENT_REHEARSAL_DB_DIR="${DATAAGENT_REHEARSAL_DB_DIR}-foreign" bash /module/docker/rehearsal-mysql.sh prepare', check=False)
            self.assertNotEqual(denied.returncode, 0)
            self.assertIn("拒绝覆盖", denied.stderr.decode())
            control("start")
            container = subprocess.check_output(["docker", "ps", "-aq", "--filter", "label=com.docker.compose.project=" + project]).decode().strip()
            self.assertTrue(container)
            info = json.loads(subprocess.check_output(["docker", "inspect", container]))[0]
            self.assertFalse(info["HostConfig"]["PortBindings"])
            self.assertEqual(info["Config"]["Labels"]["dataagent.rehearsal.base"], folder + "/db")
            self.assertEqual(info["State"]["Health"]["Status"], "healthy")
            logs = subprocess.check_output(["docker", "logs", container], stderr=subprocess.STDOUT).decode()
            self.assertIn("MySQL init process done", logs)
            self.assertNotIn("unbound variable", logs)
            remote_probe = 'import MySQLdb,os; ' \
                'c=MySQLdb.connect(host="mysql",user=os.environ["DATAAGENT_DATASET_DB_USER"],passwd=os.environ["DATAAGENT_DATASET_DB_PASSWORD"],db="data_agent"); ' \
                'q=c.cursor(); q.execute("SELECT VERSION(),CURRENT_USER()"); print(q.fetchone()); c.close()'
            remote = run("-c", 'docker run --rm --pull never --network "$DATAAGENT_REHEARSAL_DB_PROJECT"_default --env-file "$DATAAGENT_REHEARSAL_DB_DIR/app-db.env" --entrypoint python "$1" -c "$2"',
                         "sh", runtime, remote_probe)
            self.assertIn("dataagent_dataset@%", remote.stdout.decode())
            self.assertEqual(sql("dataagent_platform", "PLATFORM_PASSWORD", "SELECT COUNT(*) FROM dataagent_platform.dataagent_user").stdout.strip(), b"0")
            denied = sql("dataagent_platform", "PLATFORM_PASSWORD", "CREATE TABLE dataagent_platform.should_not_create(id INT)", check=False)
            self.assertNotEqual(denied.returncode, 0)
            sql("dataagent_dataset", "DATASET_PASSWORD", "CREATE TABLE data_agent.ds_rehearsal_probe(id INT PRIMARY KEY, amount INT); INSERT INTO data_agent.ds_rehearsal_probe VALUES(1,123)")
            self.assertNotEqual(sql("dataagent_dataset", "DATASET_PASSWORD", "SELECT COUNT(*) FROM dataagent_platform.dataagent_user", check=False).returncode, 0)
            subprocess.run(["docker", "stop", "--time", "30", container], check=True, capture_output=True, timeout=50)
            control("start")
            self.assertEqual(sql("dataagent_dataset", "DATASET_PASSWORD", "SELECT amount FROM data_agent.ds_rehearsal_probe WHERE id=1").stdout.strip(), b"123")
            self.assertEqual(before, run("-c", 'sha256sum "$DATAAGENT_REHEARSAL_DB_DIR/mysql.env" "$DATAAGENT_REHEARSAL_DB_DIR/app-db.env"').stdout)
            control("verify")
            self.assertIn("演练库核对通过", control("verify").stdout.decode())
            print("Verified first initialization, 37 tables, separate account grants, Wren Python MySQLdb over Docker DNS, no host port, restart persistence, idempotent secrets, foreign-directory refusal; project=" + project, flush=True)
        finally:
            if container:
                subprocess.run(["docker", "stop", "--time", "30", container], capture_output=True, timeout=50)
            print("Test data and network retained at " + folder, flush=True)


if __name__ == "__main__":
    unittest.main()
