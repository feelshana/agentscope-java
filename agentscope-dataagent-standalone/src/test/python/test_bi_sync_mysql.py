"""Opt-in, real isolated MySQL5.7 to MySQL8 tests. No existing database is used."""
from datetime import date, datetime
import contextlib
import io
import json
import importlib.util
import os
from pathlib import Path
import secrets
import subprocess
import time
import unittest
from unittest.mock import patch
import uuid

ROOT = Path(__file__).resolve().parents[3]
spec = importlib.util.spec_from_file_location("bi_sync_real", ROOT / "deploy/bi-sync/bi_sync.py")
sync = importlib.util.module_from_spec(spec)
spec.loader.exec_module(sync)


@unittest.skipUnless(os.environ.get("DATAAGENT_BI_SYNC_INTEGRATION") == "1", "isolated Docker/MySQL opt-in")
class BiSyncMysqlTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        import MySQLdb
        cls.driver = MySQLdb
        cls.token = uuid.uuid4().hex[:10]
        cls.password = secrets.token_hex(24)
        cls.folder = ROOT / "target" / ("bi-sync-integration-" + cls.token)
        cls.folder.mkdir(parents=True)
        cls.env = cls.folder / "mysql.env"
        cls.env.write_text("MYSQL_ROOT_PASSWORD=" + cls.password + "\n")
        os.chmod(cls.env, 0o600)
        cls.containers = []
        cls.ports = []
        for suffix, image in (("source", "mysql:5.7.44"), ("target", "mysql:8.4.5")):
            name = "dataagent-bi-" + cls.token + "-" + suffix
            subprocess.run(["docker", "run", "-d", "--pull", "never", "--name", name,
                            "--label", "dataagent.bi-test=" + cls.token,
                            "--env-file", str(cls.env), "-p", "127.0.0.1::3306", image],
                           check=True, capture_output=True, timeout=30)
            cls.containers.append(name)
            raw = subprocess.check_output(["docker", "port", name, "3306/tcp"], timeout=10).decode().strip()
            port = int(raw.rsplit(":", 1)[1])
            cls.ports.append(port)
            last_error = None
            for _ in range(60):
                try:
                    con = cls.admin(port)
                    con.close()
                    last_error = None
                    break
                except Exception as error:
                    last_error = error
                    time.sleep(1)
            if last_error:
                raise RuntimeError("isolated database startup failed")
        print("Isolated source=5.7.44 target=8.4.5; test resource tag=" + cls.token, flush=True)

    @classmethod
    def admin(cls, port):
        return cls.driver.connect(host="127.0.0.1", port=port, user="root", passwd=cls.password,
                                  charset="utf8mb4", connect_timeout=2)

    @classmethod
    def tearDownClass(cls):
        # Verify ownership before stopping only the containers created by this class; retain all data.
        for name in cls.containers:
            label = subprocess.check_output(["docker", "inspect", "--format",
                      '{{index .Config.Labels "dataagent.bi-test"}}', name], timeout=10).decode().strip()
            if label != cls.token:
                raise RuntimeError("test container ownership mismatch")
            subprocess.run(["docker", "stop", "--time", "10", name], capture_output=True, timeout=30, check=True)
        print("Stopped own test containers; data and private fixture retained.", flush=True)

    def setUp(self):
        # Every schema below lives only in the two uniquely labelled containers created above.
        self.source = self.admin(self.ports[0])
        self.target = self.admin(self.ports[1])
        for con, database in ((self.source, "easybi"), (self.target, "data_agent")):
            with con.cursor() as cursor:
                cursor.execute("DROP DATABASE IF EXISTS " + database)
                cursor.execute("CREATE DATABASE " + database + " CHARACTER SET utf8mb4")
                cursor.execute("USE " + database)
        with self.source.cursor() as cursor:
            for table, (_, spec) in sync.TABLES.items():
                cols = ["`" + ("NAME" if table == "d_report" and n == "name" else n) + "` "
                        + ("int(1)" if n == "state" else t) + (" PRIMARY KEY" if n == "id" else " NULL")
                        for n, t in (part.split(":") for part in spec.split(","))]
                if table == "sys_operator":
                    cols += ["password varchar(100)", "phone varchar(20)", "email varchar(50)"]
                if table in sync.ASSET_TABLES:
                    cols += ["config_info longtext", "page_config_info longtext", "temp_page_config_info longtext"]
                    if table == "d_dashboard":
                        cols += ["setting text"]
                if table in sync.LOGS:
                    project = sync.LOG_PROJECTS[table]
                    cols += [project + " varchar(32)", "operator_project_id varchar(32)",
                             "log_type int", "has_permission int",
                             "KEY idx_projectId_time (" + project + ",log_time)"]
                cursor.execute("CREATE TABLE " + table + " (" + ",".join(cols) + ") ENGINE=InnoDB")
            cursor.execute("CREATE USER IF NOT EXISTS 'reader'@'%%' IDENTIFIED BY %s", (self.password,))
            cursor.execute("GRANT SELECT ON easybi.* TO 'reader'@'%'")
        self.source.commit()
        self.config = dict(version=1, start_date="2026-01-01", batch_size=1,
                           source=dict(host="127.0.0.1", port=self.ports[0], user="reader",
                                       password=self.password, database="easybi"),
                           target=dict(host="127.0.0.1", port=self.ports[1], user="root",
                                       password=self.password, database="data_agent"))
        self.seed()

    def tearDown(self):
        self.source.close()
        self.target.close()

    def seed(self):
        with self.source.cursor() as cursor:
            cursor.execute("INSERT INTO sys_project(id,name,leader) VALUES('p1','虚构项目','负责人')")
            cursor.execute("INSERT INTO sys_operator(id,loginname,name,company,dept,posts,state,createtime,password,phone) "
                           "VALUES('o1','demo','虚构人员','测试公司','测试部','分析师',1,'2026-01-01','EXCLUDED','EXCLUDED')")
            cursor.execute("INSERT INTO sys_project_operator_relation(id,operatorid,projectid) VALUES('rel1','o1','p1')")
            for table in ("d_report", "d_dashboard"):
                cursor.execute("INSERT INTO " + table + "(id,name,org_id,creator,state) VALUES('asset1','演示对象','p1','o1',1)")
            for table in sync.LOGS:
                obj = "report_id" if table == "d_report_log" else "dashboard_id"
                project = "report_project_id" if table == "d_report_log" else "dashboard_project_id"
                for id_, at, type_ in (("z", "2026-01-01 10:00:00", 0),
                                        ("x", "2026-01-01 10:00:00", 1),
                                        ("a", "2026-01-03 01:00:00", 2)):
                    cursor.execute("INSERT INTO " + table + "(id,log_time," + obj + "," + project
                                   + ",operator_id,operator_project_id,log_type,has_permission) "
                                   "VALUES(%s,%s,'asset1','p1','o1','p1',%s,0)", (id_, at, type_))
        self.source.commit()

    def run_sync(self, action="sync", until=date(2026, 1, 4)):
        # Operator observations must start a fresh snapshot after init creates new tables.
        self.target.rollback()
        try:
            sync.run(self.config, action, until)
        finally:
            self.target.rollback()


    def legacy_ready(self):
        legacy = dict(sync.TABLES)
        for name in sync.ASSET_TABLES:
            legacy[name] = (sync.TABLES[name][0], "id:varchar(36),name:varchar(100),org_id:varchar(32)")
        with patch.object(sync, "TABLES", legacy):
            self.run_sync("init")
            self.run_sync()
        self.target.rollback()

    def control_snapshot(self):
        self.target.rollback()
        with self.target.cursor() as cursor:
            cursor.execute("SELECT name,value FROM bi_sync_control ORDER BY name")
            return dict(cursor.fetchall())

    def test_asset_migration_backfill_preserves_all_log_progress_and_data(self):
        self.legacy_ready()
        before = self.control_snapshot()
        with self.target.cursor() as cursor:
            cursor.execute("SELECT * FROM bi_report_log ORDER BY id")
            logs = cursor.fetchall()
        with self.assertRaises(ValueError):
            self.run_sync("migrate")
        self.run_sync("pause")
        self.run_sync("migrate")
        self.run_sync("migrate")
        with self.assertRaises(ValueError):
            self.run_sync("resume")
        with self.source.cursor() as cursor:
            cursor.execute("UPDATE d_report SET description=%s,requester='biz',type=2,agent_name='测试助手',"
                           "update_config_time='2026-01-02',config_info='EXCLUDED'", ("描述" * 10000,))
            cursor.execute("UPDATE d_dashboard SET model_ids=%s,min_height=123.5,creator='o1',"
                           "setting='EXCLUDED',temp='原始标记'", ("m" * 4000,))
        self.source.commit()
        with patch.object(sync, "sync_log", side_effect=AssertionError("must not revisit logs")):
            self.run_sync("refresh-basics")
        self.run_sync("migrate")  # A completed retry must not invalidate the completed backfill.
        self.assertEqual(sync.state(self.target, "asset_fields_ready"), "true")
        self.run_sync("resume")
        after = self.control_snapshot()
        self.assertEqual({k: after[k] for k in before}, before)
        self.assertEqual(after["schema_version"], sync.ASSET_SCHEMA)
        with self.target.cursor() as cursor:
            cursor.execute("SELECT * FROM bi_report_log ORDER BY id")
            self.assertEqual(cursor.fetchall(), logs)
            cursor.execute("SELECT description,requester,type,agent_name,update_config_time FROM bi_report")
            row = cursor.fetchone()
            self.assertEqual(row[:4], ("描述" * 10000, "biz", 2, "测试助手"))
            self.assertEqual(row[4], datetime(2026, 1, 2))
            cursor.execute("SELECT model_ids,min_height,temp FROM bi_dashboard")
            self.assertEqual(cursor.fetchone(), ("m" * 4000, 123.5, "原始标记"))
            for name, length in (("bi_report", 23), ("bi_dashboard", 39)):
                cursor.execute("SHOW COLUMNS FROM " + name)
                fields = cursor.fetchall()
                self.assertEqual(len(fields), length)
                self.assertTrue(all(c[2] == "YES" for c in fields[3:]))
                self.assertFalse({"config_info","page_config_info","temp_page_config_info","setting"}
                                 .intersection(c[0] for c in fields))

    def test_asset_migration_partial_ddl_retry_and_old_status(self):
        self.legacy_ready()
        self.run_sync("pause")
        with contextlib.redirect_stdout(io.StringIO()) as output:
            self.run_sync("status")
        self.assertTrue(json.loads(output.getvalue())["paused"])
        with patch.object(sync, "inspect_source", side_effect=AssertionError("paused old schema must skip")):
            self.run_sync()
        original = sync.field_ddl
        def fail_dashboard(source, name, kind):
            if source == "d_dashboard":
                raise RuntimeError("second table DDL failed")
            return original(source, name, kind)
        with patch.object(sync, "field_ddl", fail_dashboard), self.assertRaises(RuntimeError):
            self.run_sync("migrate")
        self.assertEqual(len(sync.table_fields(self.target, "bi_report")), 23)
        self.assertEqual(len(sync.table_fields(self.target, "bi_dashboard")), 3)
        self.assertEqual(sync.state(self.target, "paused"), "true")
        with self.assertRaises(ValueError):
            self.run_sync("resume")
        self.run_sync("migrate")
        self.run_sync("refresh-basics")
        self.run_sync("resume")

    def test_asset_migration_checks_source_and_all_targets_before_ddl(self):
        self.legacy_ready()
        self.run_sync("pause")
        with self.source.cursor() as cursor:
            cursor.execute("ALTER TABLE d_report MODIFY requester int")
        self.source.commit()
        with self.assertRaises(ValueError):
            self.run_sync("migrate")
        self.assertEqual(len(sync.table_fields(self.target, "bi_report")), 3)
        with self.source.cursor() as cursor:
            cursor.execute("ALTER TABLE d_report MODIFY requester varchar(36)")
            cursor.execute("ALTER TABLE d_dashboard DROP COLUMN description")
        self.source.commit()
        with self.assertRaises(ValueError):
            self.run_sync("migrate")
        self.assertEqual(len(sync.table_fields(self.target, "bi_report")), 3)

    def test_asset_migration_rejects_unknown_target_wrong_owner_and_concurrency(self):
        self.legacy_ready()
        self.run_sync("pause")
        self.assertEqual(sync.scalar(self.target, "SELECT GET_LOCK('dataagent-bi-sync-v1',0)"), 1)
        try:
            with self.assertRaises(ValueError):
                self.run_sync("migrate")
            with self.assertRaises(ValueError):
                self.run_sync("resume")
        finally:
            sync.scalar(self.target, "SELECT RELEASE_LOCK('dataagent-bi-sync-v1')")
        with self.target.cursor() as cursor:
            cursor.execute("ALTER TABLE bi_dashboard ADD unexpected int")
        with self.assertRaises(ValueError):
            self.run_sync("migrate")
        self.assertEqual(len(sync.table_fields(self.target, "bi_report")), 3)
        sync.save_state(self.target, "source", "wrong-source")
        self.target.commit()
        with self.assertRaises(ValueError):
            self.run_sync("migrate")

    def test_basic_backfill_failure_keeps_pause_and_all_previous_rows(self):
        self.legacy_ready()
        self.run_sync("pause")
        self.run_sync("migrate")
        original = sync.write_rows
        def fail_last(target, source, rows, batch):
            original(target, source, rows, batch)
            if source == "sys_project_operator_relation":
                raise RuntimeError("injected basic refresh failure")
        with self.source.cursor() as cursor:
            cursor.execute("UPDATE d_report SET name='未提交的新名字'")
        self.source.commit()
        with patch.object(sync, "write_rows", fail_last), self.assertRaises(RuntimeError):
            self.run_sync("refresh-basics")
        self.assertEqual(sync.scalar(self.target, "SELECT name FROM bi_report"), "演示对象")
        self.assertEqual(sync.state(self.target, "asset_fields_ready"), "false")
        self.assertEqual(sync.state(self.target, "paused"), "true")
        self.assertEqual(sync.state(self.target, "success_until"), "2026-01-04")
        with self.assertRaises(ValueError):
            self.run_sync("resume")
        self.run_sync("refresh-basics")
        self.run_sync("resume")

    def test_initial_idempotent_incremental_and_verify(self):
        self.run_sync("check")
        self.assertFalse(sync.control_exists(self.target))
        self.run_sync("init")
        self.run_sync()
        self.run_sync()
        self.run_sync("verify")
        self.assertEqual(sync.scalar(self.target, "SELECT COUNT(*) FROM bi_report_log"), 3)
        self.assertEqual(sum(len(sync.columns(t)) for t in sync.TABLES), 95)
        with self.target.cursor() as cursor:
            cursor.execute("SHOW COLUMNS FROM bi_report_log")
            self.assertEqual([r[0] for r in cursor.fetchall()], ["id", "log_time", "report_id", "operator_id"])
            cursor.execute("SHOW INDEX FROM bi_report_log")
            self.assertTrue({"PRIMARY", "ix_time", "ix_object", "ix_operator"}.issubset({r[2] for r in cursor.fetchall()}))
        self.assertEqual(sync.state(self.target, "success_until"), "2026-01-04")
        self.target.commit()
        with self.source.cursor() as cursor:
            cursor.execute("INSERT INTO d_report_log(id,log_time,log_type) VALUES('00','2026-01-04 23:59:59',0)")
            cursor.execute("UPDATE sys_operator SET name='改名后的虚构人员'")
        self.source.commit()
        self.run_sync(until=date(2026, 1, 5))
        self.run_sync("verify", until=date(2026, 1, 5))
        self.target.commit()
        self.assertEqual(sync.scalar(self.target, "SELECT COUNT(*) FROM bi_report_log"), 4)
        self.assertEqual(sync.scalar(self.target, "SELECT name FROM bi_operator"), "改名后的虚构人员")
        with self.target.cursor() as cursor:
            cursor.execute("SHOW COLUMNS FROM bi_operator")
            fields = {r[0] for r in cursor.fetchall()}
            self.assertFalse(fields.intersection({"password", "phone", "email"}))
            self.assertEqual(len(fields), 18)
            self.assertTrue({"bak_name", "passwordlastupdate", "select_area_tip_count"}.issubset(fields))

    def test_interrupted_day_rollback_and_resume(self):
        self.run_sync("init")
        original = sync.write_rows
        def fail_after_write(target, source, rows, batch):
            original(target, source, rows, batch)
            if source == "d_dashboard_log" and rows:
                raise RuntimeError("injected write failure")
        with patch.object(sync, "write_rows", fail_after_write), self.assertRaises(RuntimeError):
            self.run_sync()
        self.assertIsNone(sync.state(self.target, "success_until"))
        self.assertEqual(sync.state(self.target, "bi_report_log"), "2026-01-04")
        self.assertIsNone(sync.state(self.target, "bi_dashboard_log"))
        self.assertEqual(sync.scalar(self.target, "SELECT COUNT(*) FROM bi_dashboard_log"), 0)
        self.target.commit()
        self.run_sync()
        self.run_sync("verify")

    def test_dimension_failure_keeps_all_old_snapshots(self):
        self.run_sync("init")
        self.run_sync()
        with self.source.cursor() as cursor:
            cursor.execute("UPDATE sys_project SET name='不应部分生效'")
        self.source.commit()
        original = sync.write_rows
        def fail(target, source, rows, batch):
            original(target, source, rows, batch)
            if source == "sys_operator":
                raise RuntimeError("injected snapshot failure")
        with patch.object(sync, "write_rows", fail), self.assertRaises(RuntimeError):
            self.run_sync()
        self.target.commit()
        self.assertEqual(sync.scalar(self.target, "SELECT name FROM bi_project"), "虚构项目")

    def test_historical_null_empty_and_quoted_projects_are_not_lost(self):
        with self.source.cursor() as cursor:
            for table in sync.LOGS:
                for i, project in enumerate(("deleted-project", "", None, "project'quoted")):
                    cursor.execute("INSERT INTO " + table + "(id,log_time," + sync.LOG_PROJECTS[table]
                                   + ",log_type) VALUES(%s,'2026-01-02 12:00:00',%s,%s)",
                                   ("orphan" + str(i), project, i % 3))
        self.source.commit()
        self.run_sync("init")
        self.run_sync()
        self.run_sync("verify")
        self.assertEqual(sync.scalar(self.target, "SELECT COUNT(*) FROM bi_report_log"), 7)
        self.assertEqual(sync.scalar(self.target, "SELECT COUNT(*) FROM bi_dashboard_log"), 7)
        self.assertEqual(sum(sync.journal_counts(self.target, "d_report_log", date(2026, 1, 1), date(2026, 1, 4)).values()), 7)

    def test_committed_project_days_resume_without_double_counting(self):
        self.run_sync("init")
        original = sync.write_rows
        def fail_on_later_day(target, name, rows, batch):
            original(target, name, rows, batch)
            if name == "d_dashboard_log" and any(r[1].date() == date(2026, 1, 3) for r in rows):
                raise RuntimeError("injected later-day failure")
        with patch.object(sync, "write_rows", fail_on_later_day), self.assertRaises(RuntimeError):
            self.run_sync()
        self.assertEqual(sync.scalar(self.target, "SELECT COUNT(*) FROM bi_dashboard_log"), 2)
        self.assertEqual(sync.project_lower(self.target, "d_dashboard_log", "p1", date(2026, 1, 1)), date(2026, 1, 2))
        self.assertIsNone(sync.state(self.target, "bi_dashboard_log"))
        self.target.rollback()
        with patch.object(sync, "sync_project", wraps=sync.sync_project) as resume:
            self.run_sync()
            calls = resume.call_args_list
        self.assertEqual(len(calls), 1)
        self.assertEqual(calls[0].args[4], date(2026, 1, 2))
        self.run_sync("verify")
        self.assertEqual(sync.journal_counts(self.target, "d_dashboard_log", date(2026, 1, 1), date(2026, 1, 4)),
                         {date(2026, 1, 1): 2, date(2026, 1, 3): 1})

    def test_new_project_and_missed_days_are_caught_up(self):
        self.run_sync("init")
        self.run_sync()
        with self.source.cursor() as cursor:
            for table in sync.LOGS:
                cursor.execute("INSERT INTO " + table + "(id,log_time," + sync.LOG_PROJECTS[table]
                               + ",log_type) VALUES('next','2026-01-05 00:00:00','new-project',2)")
        self.source.commit()
        self.run_sync(until=date(2026, 1, 7))
        self.run_sync("verify", until=date(2026, 1, 7))
        self.assertEqual(sync.scalar(self.target, "SELECT COUNT(*) FROM bi_report_log"), 4)
        self.assertEqual(sync.state(self.target, "success_until"), "2026-01-07")

    def test_source_requires_existing_project_time_index(self):
        with self.source.cursor() as cursor:
            cursor.execute("ALTER TABLE d_report_log DROP INDEX idx_projectId_time")
        with self.assertRaisesRegex(ValueError, "项目\\+时间索引"):
            self.run_sync("check")

    def test_old_task_owner_is_not_silently_reused(self):
        self.run_sync("init")
        sync.save_state(self.target, "owner", "dataagent-bi-sync-v1")
        self.target.commit()
        with self.assertRaises(ValueError):
            self.run_sync()

    def test_verify_detects_target_count_damage(self):
        self.run_sync("init")
        self.run_sync()
        with self.target.cursor() as cursor:
            cursor.execute("DELETE FROM bi_report_log WHERE id='x'")
        self.target.commit()
        with self.assertRaisesRegex(ValueError, "逐日计数"):
            self.run_sync("verify")

    def test_execution_status_source_failure_redaction_and_offline_pause(self):
        self.run_sync("init")
        self.run_sync()
        result = json.loads(sync.state(self.target, "last_run"))
        self.assertEqual(result["outcome"], "SUCCESS")
        self.assertGreaterEqual(result["duration_ms"], 0)
        self.assertIsNotNone(sync.state(self.target, "last_success_at"))
        self.target.rollback()
        def source_unavailable(endpoint, readonly=False):
            if endpoint["database"] == "easybi":
                raise RuntimeError("password=NEVER_PERSIST_CONNECTION_TEXT")
            return sync.connect(endpoint, readonly)
        with self.assertRaises(RuntimeError):
            sync.run(self.config, "sync", date(2026, 1, 5), source_unavailable)
        result = json.loads(sync.state(self.target, "last_run"))
        self.assertEqual(result["outcome"], "FAILED")
        self.assertEqual(result["failure_phase"], "SOURCE_CONNECT")
        self.assertNotIn("NEVER_PERSIST", json.dumps(result))
        self.assertEqual(sync.state(self.target, "success_until"), "2026-01-04")
        self.target.rollback()
        sync.run(self.config, "pause", date(2026, 1, 5), source_unavailable)
        sync.run(self.config, "sync", date(2026, 1, 5), source_unavailable)
        with contextlib.redirect_stdout(io.StringIO()) as output:
            sync.run(self.config, "status", date(2026, 1, 5), source_unavailable)
        view = json.loads(output.getvalue())
        self.assertTrue(view["paused"])
        self.assertFalse(view["running"])
        self.assertEqual(view["last_run"]["outcome"], "FAILED")
        sync.run(self.config, "resume", date(2026, 1, 5), source_unavailable)
        self.run_sync(until=date(2026, 1, 5))
        self.run_sync("verify", until=date(2026, 1, 5))

    def test_pause_inside_batch_rolls_back_current_day_and_resumes(self):
        self.run_sync("init")
        original = sync.write_rows
        requested = []
        def pause_during_write(target, name, rows, batch):
            original(target, name, rows, batch)
            if name == "d_dashboard_log" and rows and not requested:
                requested.append(True)
                con = sync.connect(self.config["target"])
                try:
                    sync.save_state(con, "paused", "true")
                    con.commit()
                finally:
                    con.close()
        with patch.object(sync, "write_rows", pause_during_write):
            self.run_sync()
        self.assertEqual(json.loads(sync.state(self.target, "last_run"))["outcome"], "PAUSED")
        self.assertEqual(sync.scalar(self.target, "SELECT COUNT(*) FROM bi_dashboard_log"), 0)
        self.assertIsNone(sync.state(self.target, "bi_dashboard_log"))
        self.assertIsNone(sync.scalar(self.target, "SELECT IS_USED_LOCK('dataagent-bi-sync-v1')"))
        self.target.rollback()
        sync.run(self.config, "resume", date(2026, 1, 4))
        self.run_sync()
        self.run_sync("verify")
        self.assertEqual(sync.scalar(self.target, "SELECT COUNT(*) FROM bi_dashboard_log"), 3)

    def test_status_reports_running_without_source_connection(self):
        self.run_sync("init")
        self.assertEqual(sync.scalar(self.target, "SELECT GET_LOCK('dataagent-bi-sync-v1',0)"), 1)
        try:
            with contextlib.redirect_stdout(io.StringIO()) as output:
                self.run_sync("status")
            self.assertTrue(json.loads(output.getvalue())["running"])
        finally:
            sync.scalar(self.target, "SELECT RELEASE_LOCK('dataagent-bi-sync-v1')")

    def test_unowned_target_and_source_write_rejected(self):
        with self.target.cursor() as cursor:
            cursor.execute("CREATE TABLE bi_project(id int)")
        with self.assertRaises(ValueError):
            self.run_sync("init")
        self.assertFalse(sync.control_exists(self.target))
        con = sync.connect(self.config["source"], readonly=True)
        try:
            with self.assertRaises(Exception):
                with con.cursor() as cursor:
                    cursor.execute("DELETE FROM sys_operator")
        finally:
            con.close()
        self.assertEqual(sync.scalar(self.source, "SELECT COUNT(*) FROM sys_operator"), 1)

    def test_overlapping_job_and_changed_source_rejected(self):
        self.run_sync("init")
        self.assertEqual(sync.scalar(self.target, "SELECT GET_LOCK('dataagent-bi-sync-v1',0)"), 1)
        try:
            with self.assertRaises(ValueError):
                self.run_sync()
        finally:
            sync.scalar(self.target, "SELECT RELEASE_LOCK('dataagent-bi-sync-v1')")
        sync.save_state(self.target, "source", "different-origin")
        self.target.commit()
        with self.assertRaises(ValueError):
            self.run_sync()

    def test_formal_preflight_uses_jdbc_database_and_requires_admin(self):
        path = ROOT / "docker/database-preflight.py"
        module_spec = importlib.util.spec_from_file_location("actual_db_preflight", path)
        preflight = importlib.util.module_from_spec(module_spec)
        module_spec.loader.exec_module(preflight)
        with self.target.cursor() as cursor:
            cursor.execute("CREATE DATABASE IF NOT EXISTS data_agent_platform")
            cursor.execute("CREATE TABLE data_agent_platform.dataagent_user(username varchar(32), roles_csv varchar(64))")
            cursor.execute("INSERT INTO data_agent_platform.dataagent_user VALUES('fixture','user')")
        self.target.commit()
        env = {"DATAAGENT_REQUIRE_PLATFORM_USERS": "true"}
        for prefix, database in (("DATAAGENT_", "data_agent_platform"), ("DATAAGENT_DATASET_", "data_agent")):
            env.update({prefix + "DB_URL": "jdbc:mysql://127.0.0.1:" + str(self.ports[1]) + "/" + database,
                        prefix + "DB_USER": "root", prefix + "DB_PASSWORD": self.password})
        with patch.dict(os.environ, env):
            with self.assertRaises(ValueError):
                preflight.check_databases()
            with self.target.cursor() as cursor:
                cursor.execute("UPDATE data_agent_platform.dataagent_user SET roles_csv='user,admin'")
            self.target.commit()
            preflight.check_databases()


if __name__ == "__main__":
    unittest.main()
