"""Focused unit tests and opt-in isolated MySQL transaction tests. No production endpoints."""
from datetime import date, datetime, timedelta
from decimal import Decimal
from contextlib import redirect_stdout, redirect_stderr
import importlib.util
import io
import json
import os
from pathlib import Path
import secrets
import subprocess
import sys
import tempfile
import time
import unittest
from unittest.mock import MagicMock, patch
import uuid

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "deploy/bi-sync"))
import extra_sync as ext
import extra_schema as schema
import configure_extra

DAY = date(2026, 1, 1)


class ExtraUnitTest(unittest.TestCase):
    def test_verify_persists_result_when_unused_status_connection_would_expire(self):
        import MySQLdb
        target, monitor, source = MagicMock(), MagicMock(), MagicMock()
        connections = {"target": target, "monitor": monitor, "iam": source}
        config = dict(target={}, sources={"iam": {}})
        until = DAY + timedelta(days=1)

        def finish_long_verification(*args):
            monitor.cursor.side_effect = MySQLdb.OperationalError(4031, "idle timeout")

        with (redirect_stdout(io.StringIO()), patch.object(ext, "require_owner"),
              patch.object(ext, "inspect_schema"), patch.object(ext, "inspect_source"),
              patch.object(ext, "scalar", side_effect=["8.0.41", 1, 1]),
              patch.object(ext, "status", return_value={"has_gaps": False}),
              patch.object(ext, "verify_job", side_effect=finish_long_verification)):
            ext.run(config, "verify", until, tables=["bi_iam_user"],
                    connect_fn=lambda endpoint, role: connections[role])

        writes = target.cursor.return_value.__enter__.return_value.execute.call_args_list
        records = {key: json.loads(value) for _, (key, value) in (call.args for call in writes)}
        self.assertEqual("SUCCESS", records["last_run"]["outcome"])
        self.assertEqual(str(until), records["last_verify"]["until"])
        self.assertEqual(["bi_iam_user"], records["last_verify"]["tables"])
        self.assertEqual(2, target.commit.call_count)

    def test_connection_allows_long_queries_and_stream_backpressure(self):
        import MySQLdb
        con = MagicMock()
        endpoint = dict(host="fixture", port=3306, database="platform", user="reader",
                        password="fixture-only")
        with patch.object(MySQLdb, "connect", return_value=con) as connect:
            ext.connect(endpoint, "optimization")
        self.assertEqual(600, connect.call_args.kwargs["read_timeout"])
        self.assertEqual(600, connect.call_args.kwargs["write_timeout"])
        statements = [a.args[0] for a in con.cursor.return_value.__enter__.return_value.execute.call_args_list]
        self.assertIn("SET SESSION net_write_timeout=600", statements)
        self.assertIn("SET SESSION TRANSACTION READ ONLY", statements)

    def test_source_retry_is_bounded_and_never_retries_sql_errors(self):
        import MySQLdb
        job = schema.JOBS["bi_iam_user"]
        source, target, reconnect = MagicMock(), MagicMock(), MagicMock()
        failure = MySQLdb.OperationalError(2013, "private-sentinel")
        source.rollback.side_effect = failure
        reconnect.return_value.rollback.side_effect = failure
        output = io.StringIO()
        with redirect_stdout(output), patch.object(ext.time, "sleep"), self.assertRaises(ext.SourceReadError):
            ext.sync_job(source, target, job, DAY + timedelta(days=1), None, 2, 3,
                         lambda: None, reconnect_source=reconnect)
        self.assertEqual(2, reconnect.call_count)
        target.begin.assert_not_called()
        self.assertNotIn("private-sentinel", output.getvalue())
        source.rollback.side_effect = MySQLdb.OperationalError(1054, "private-sentinel")
        reconnect.reset_mock()
        with self.assertRaises(MySQLdb.OperationalError):
            ext.sync_job(source, target, job, DAY + timedelta(days=1), None, 2, 3,
                         lambda: None, reconnect_source=reconnect)
        reconnect.assert_not_called()

    def test_exact_six_table_projection_and_no_eid_uniqueness(self):
        self.assertEqual([5, 18, 22, 10, 14, 10], [len(j.columns) for j in schema.JOBS.values()])
        sql, args = schema.projection(schema.JOBS["bi_page_click_log"], DAY)
        self.assertNotIn("GROUP BY", sql)
        self.assertNotIn("DISTINCT", sql)
        self.assertNotIn("eid", sql)
        self.assertIn("URL_DECODE(url)", sql)
        self.assertEqual(("2026-01-01 00:00:00", "2026-01-02 00:00:00"), args)
        self.assertIn("mediumtext", schema.JOBS["bi_page_click_log"].ddl())

    def test_report_formulas_preserve_confirmed_divisor_and_sum_of_ratios(self):
        sql, args = schema.projection(schema.JOBS["bi_tenant_optimization_daily"], DAY)
        self.assertEqual(4, sql.count("/1072741824"))
        self.assertNotIn("咪咕总部经分", sql)
        self.assertNotIn("LIMIT", sql)
        sql, args = schema.projection(schema.JOBS["bi_project_capacity_daily"], DAY)
        self.assertIn("SUM(s.storage/q.storage)", sql)
        self.assertNotIn("SUM(s.storage)/SUM(q.storage)", sql)
        self.assertEqual(("20260101",), args)

    def test_structural_partition_parse_and_no_unique_key_assumption(self):
        ddl = ("CREATE TABLE operation_log_detail (event_time datetime) ENGINE=OLAP "
               "DUPLICATE KEY(event_time,app_id) PARTITION BY RANGE(event_time) "
               "(PARTITION p20260808 VALUES [('2026-08-08 00:00:00'), ('2026-08-09 00:00:00')),"
               "PARTITION p20260809 VALUES [('2026-08-09 00:00:00'), ('2026-08-10 00:00:00')))")
        class Cursor:
            def __enter__(self): return self
            def __exit__(self, *a): pass
            def execute(self, sql): self.sql = sql
            def fetchone(self): return "operation_log_detail", ddl
        class Connection:
            def cursor(self): return Cursor()
        self.assertEqual({date(2026, 8, 8), date(2026, 8, 9)}, ext.doris_days(Connection()))
        ddl = ddl.replace("DUPLICATE KEY", "UNIQUE KEY")
        with self.assertRaises(ValueError):
            ext.doris_days(Connection())

    def test_gap_interval_merge_and_successful_backfill_split(self):
        gaps = ext.add_gap([], DAY, DAY + timedelta(days=4), "SOURCE_SNAPSHOT_EMPTY")
        gaps = ext.remove_gap_day(gaps, DAY + timedelta(days=1))
        self.assertEqual([("2026-01-01", "2026-01-02"), ("2026-01-03", "2026-01-05")],
                         [(g["from"], g["until"]) for g in gaps])
        gaps = ext.add_gap(gaps, DAY + timedelta(days=1), DAY + timedelta(days=2), "SOURCE_SNAPSHOT_EMPTY")
        self.assertEqual(1, len(gaps))

    def test_private_config_refuses_overwrite_and_never_echoes_password(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "extra.local.json"
            path.write_text("existing", encoding="utf-8")
            with self.assertRaises(ValueError):
                configure_extra.prepare(path, Path(folder) / "base")
            self.assertEqual("existing", path.read_text())
        out, err = io.StringIO(), io.StringIO()
        with redirect_stdout(out), redirect_stderr(err), patch.object(
                ext, "load_config", side_effect=RuntimeError("private-password-sentinel")):
            self.assertEqual(1, ext.main(["status", "--config", "x", "--base-config", "y"]))
        self.assertNotIn("private-password-sentinel", out.getvalue() + err.getvalue())

    def test_configure_private_file_and_preserve_existing_base(self):
        with tempfile.TemporaryDirectory() as folder:
            path, base = Path(folder) / "extra.local.json", Path(folder) / "base.local.json"
            original = dict(version=1, start_date="2026-01-01", batch_size=1000,
                            source=dict(host="fixture-source", port=3306, database="easybi",
                                        user="fixture", password="fixture-only"),
                            target=dict(host="fixture-target", port=3306, database="data_agent",
                                        user="fixture", password="fixture-only"))
            base.write_text(json.dumps(original), encoding="utf-8")
            os.chmod(base, 0o600)
            before = base.read_bytes()
            configure_extra.prepare(path, base, ask=lambda label: "fixture-only")
            self.assertEqual(before, base.read_bytes())
            parsed = ext.load_config(path, base)
            self.assertEqual(3, parsed["lookback_days"])
            self.assertEqual(5, len(parsed["sources"]))
            if os.name == "posix":
                self.assertEqual(0o600, path.stat().st_mode & 0o777)

    def test_invalid_endpoint_and_batch_rejected_before_any_connection(self):
        with tempfile.TemporaryDirectory() as folder:
            path, base = Path(folder) / "extra.local.json", Path(folder) / "base.local.json"
            base.write_text(json.dumps(dict(
                version=1, start_date="2026-01-01",
                source=dict(host="s", port=3306, database="easybi", user="x", password="fixture"),
                target=dict(host="t", port=3306, database="data_agent", user="x", password="fixture"))))
            os.chmod(base, 0o600)
            configure_extra.prepare(path, base, ask=lambda label: "fixture-only")
            raw = json.loads(path.read_text())
            raw["sources"]["iam"]["database"] = "other"
            path.write_text(json.dumps(raw))
            with self.assertRaises(ValueError):
                ext.load_config(path, base)


@unittest.skipUnless(os.environ.get("DATAAGENT_BI_EXTRA_INTEGRATION") == "1",
                     "isolated Docker MySQL5.7/8 opt-in")
class ExtraMysqlTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        import MySQLdb
        cls.driver = MySQLdb
        cls.token = uuid.uuid4().hex[:10]
        cls.password = secrets.token_hex(24)
        cls.containers, cls.ports = [], []
        cls.addClassCleanup(cls.stop_owned)
        for side, image in (("source", "mysql:5.7.44"), ("target", "mysql:8.4.5")):
            name = "dataagent-bi-extra-" + cls.token + "-" + side
            subprocess.run(["docker", "run", "-d", "--pull", "never", "--name", name,
                            "--label", "dataagent.bi-extra-test=" + cls.token,
                            "-e", "MYSQL_ROOT_PASSWORD", "-p", "127.0.0.1::3306", image],
                           env=dict(os.environ, MYSQL_ROOT_PASSWORD=cls.password),
                           capture_output=True, check=True, timeout=30)
            cls.containers.append(name)
            port = int(subprocess.check_output(["docker", "port", name, "3306/tcp"],
                                               timeout=10).decode().strip().rsplit(":", 1)[1])
            cls.ports.append(port)
            for attempt in range(60):
                try:
                    con = cls.admin(port)
                    con.close()
                    break
                except Exception:
                    if attempt == 59:
                        raise RuntimeError("isolated fixture did not start")
                    time.sleep(1)
        print("Isolated MySQL5.7/8 fixture tag=" + cls.token, flush=True)

    @classmethod
    def admin(cls, port, db=None):
        opts = dict(host="127.0.0.1", port=port, user="root", passwd=cls.password,
                    charset="utf8mb4", connect_timeout=3)
        if db:
            opts["db"] = db
        return cls.driver.connect(**opts)

    @classmethod
    def stop_owned(cls):
        for name in cls.containers:
            label = subprocess.check_output(["docker", "inspect", "--format",
                    '{{index .Config.Labels "dataagent.bi-extra-test"}}', name],
                    timeout=10).decode().strip()
            if label != cls.token:
                raise RuntimeError("fixture ownership mismatch")
            subprocess.run(["docker", "stop", "--time", "10", name],
                           capture_output=True, check=True, timeout=30)
        print("Stopped only owned fixture containers; retained data.", flush=True)

    def setUp(self):
        self.source = self.admin(self.ports[0])
        self.target = self.admin(self.ports[1])
        for db in ("easybi", "iam", "dataanalysis1", "platform", "fenxiyun"):
            self.execute(self.source, "DROP DATABASE IF EXISTS " + db)
            self.execute(self.source, "CREATE DATABASE " + db + " CHARACTER SET utf8mb4")
        self.execute(self.target, "DROP DATABASE IF EXISTS data_agent")
        self.execute(self.target, "CREATE DATABASE data_agent CHARACTER SET utf8mb4")
        self.execute(self.target, "USE data_agent")
        self.execute(self.target, "CREATE TABLE bi_report(id varchar(32) PRIMARY KEY,name text)")
        self.execute(self.target, "INSERT INTO bi_report VALUES('old','old-seven-sentinel')")
        self.execute(self.target, "CREATE TABLE bi_sync_control(name varchar(100),value text)")
        self.execute(self.target, "INSERT INTO bi_sync_control VALUES('success_until','2026-11-05')")
        self.target.commit()
        self.seed_schema()
        self.seed_rows()
        self.execute(self.source, "CREATE USER IF NOT EXISTS 'reader'@'%%' IDENTIFIED BY %s",
                     (self.password,))
        for db in ("easybi", "iam", "dataanalysis1", "platform", "fenxiyun"):
            self.execute(self.source, "GRANT SELECT ON " + db + ".* TO 'reader'@'%'")
        self.source.commit()
        self.config = dict(batch_size=2, lookback_days=3,
                           target=self.endpoint(self.ports[1], "data_agent", "root"),
                           sources={k: self.endpoint(self.ports[0], db, "reader")
                                    for k, db in dict(ext.SOURCE_DATABASES, easybi="easybi").items()})
        self.retained = {DAY, DAY + timedelta(days=1), DAY + timedelta(days=2)}
        self.real_projection = ext.projection
        self.patches = [
            patch.object(ext, "doris_days", side_effect=lambda con: set(self.retained)),
            patch.object(ext, "projection", side_effect=self.mysql_projection)]
        for p in self.patches:
            p.start()
        self.addCleanup(lambda: [p.stop() for p in reversed(self.patches)])

    def tearDown(self):
        self.source.close()
        self.target.close()

    def endpoint(self, port, db, user):
        return dict(host="127.0.0.1", port=port, database=db, user=user, password=self.password)

    @staticmethod
    def execute(con, sql, args=()):
        with con.cursor() as cur:
            cur.execute(sql, args or None)
            return cur.fetchall() if cur.description else ()

    def mysql_projection(self, job, day=None):
        sql, args = self.real_projection(job, day)
        # Transaction tests use a MySQL substitute for Doris; production URL_DECODE is not simulated.
        return sql.replace("URL_DECODE(url) AS url", "url"), args

    def seed_schema(self):
        q = schema.quote
        screen = schema.JOBS["bi_screen"]
        visit = schema.JOBS["bi_screen_visit_log"]
        for table, job in (("d_data_screen", screen), ("sys_big_screen_visit_log", visit)):
            cols = [q("project_Id" if c.name == "project_id" else c.name) + " "
                    + c.sql_type + (" PRIMARY KEY" if c.name == "id" else " NULL")
                    for c in job.columns]
            self.execute(self.source, "CREATE TABLE easybi." + table + "(" + ",".join(cols) + ")")
        self.execute(self.source, """CREATE TABLE iam.user(
            id bigint PRIMARY KEY,username varchar(50),account_name varchar(50),
            spms_account varchar(200),email varchar(200),phonenumber bigint,coop_flag int,
            undertaking_flag int,order_type varchar(20),created_time datetime,updated_time datetime)""")
        self.execute(self.source, """CREATE TABLE iam.duty_person_info(
            id bigint PRIMARY KEY,jg_name varchar(512),bm_name varchar(512),duty_person_posts varchar(1000),
            duty_person varchar(64),duty_person_account varchar(64),wx_company_name varchar(512))""")
        self.execute(self.source, "CREATE TABLE iam.oa_person(account_name varchar(200) PRIMARY KEY,zhzt int)")
        self.execute(self.source, """CREATE TABLE dataanalysis1.project_storage(
            id int PRIMARY KEY AUTO_INCREMENT,project_id int,record_date varchar(32),
            storage bigint,file_num bigint,KEY idx_record_date(record_date))""")
        self.execute(self.source, "CREATE TABLE dataanalysis1.project(id bigint PRIMARY KEY,name varchar(30),leader_id bigint)")
        self.execute(self.source, "CREATE TABLE dataanalysis1.user(id bigint PRIMARY KEY,username varchar(50))")
        self.execute(self.source, "CREATE TABLE dataanalysis1.project_settings(project_id bigint PRIMARY KEY,storage bigint,inodes bigint)")
        columns = []
        for c in schema.JOBS["bi_tenant_optimization_daily"].columns:
            if c.generated:
                continue
            kind = "double" if c.name.endswith("_size") else c.sql_type
            columns.append(q(c.name) + " " + kind + " NULL")
        self.execute(self.source, "CREATE TABLE platform.cluster_data_to_be_optimized("
                     + ",".join(columns) + ",KEY idx_dayid(dayid))")
        self.execute(self.source, """CREATE TABLE fenxiyun.operation_log_detail(
            event_time datetime,app_id varchar(50),module_name varchar(255),project_id varchar(255),
            operator varchar(255),url mediumtext,create_time datetime,eid varchar(255),etp int)""")

    def seed_rows(self):
        self.execute(self.source, """INSERT INTO iam.user VALUES
            (1,'测试人员','sample_wx','sample-spms','sample@example.invalid',13800000000,1,1,
             'test','2020-01-01','2026-01-01')""")
        self.execute(self.source, """INSERT INTO iam.duty_person_info VALUES
            (1,'测试公司','测试部门','不应带出职位','测试责任人','other','外协测试公司')""")
        self.execute(self.source, "INSERT INTO iam.oa_person VALUES('sample_wx',10)")
        self.execute(self.source, "INSERT INTO dataanalysis1.project VALUES(7,'测试项目',1)")
        self.execute(self.source, "INSERT INTO dataanalysis1.user VALUES(1,'负责人')")
        self.execute(self.source, "INSERT INTO dataanalysis1.project_settings VALUES(7,1099511627776,1000)")
        self.execute(self.source, """INSERT INTO dataanalysis1.project_storage(project_id,record_date,storage,file_num)
            VALUES(7,'20260101',549755813888,200),(7,'',999,999)""")
        job = schema.JOBS["bi_tenant_optimization_daily"]
        values = [DAY, "m1", "测试项目", "db", "tbl", "hdfs/path", "sample_wx", "sample_wx",
                  "yes", "no", "yes", "no", 1072741824 * 2.129, 10, 3,
                  1072741824, 2, 1072741824 * 0.5, 3, 0, 0]
        self.execute(self.source, "INSERT INTO platform.cluster_data_to_be_optimized("
                     + ",".join(map(schema.quote, job.names)) + ") VALUES("
                     + ",".join(["%s"] * len(values)) + ")", values)
        self.long_url = "https://example.invalid/" + "页面" * 20000
        for _ in range(2):
            self.execute(self.source, "INSERT INTO fenxiyun.operation_log_detail VALUES"
                         "('2026-01-01 12:00:00','76','测试模块','7','sample_wx',%s,"
                         "'2026-01-01 12:00:01','same-eid',0)", (self.long_url,))
        self.execute(self.source, "INSERT INTO fenxiyun.operation_log_detail VALUES"
                     "('2026-01-01 13:00:00','76',NULL,'7',NULL,NULL,'2026-01-01 13:00:01','same-eid',0)")
        self.execute(self.source, """INSERT INTO easybi.d_data_screen
            (id,name,state,org_id,org_name,create_time,create_user_id,update_user_id,temp)
            VALUES('s1','测试大屏',0,'7','测试项目','2020-01-01','1','1','NO')""")
        self.execute(self.source, """INSERT INTO easybi.sys_big_screen_visit_log
            (id,screen_id,operation,operator_user_name,operate_time)
            VALUES(1,'s1','visit','sample_wx','2026-01-01 14:00:00')""")
        self.source.commit()

    def run_job(self, action, until=DAY + timedelta(days=1), names=None, start=None, hook=None):
        self.target.rollback()
        try:
            with redirect_stdout(io.StringIO()):
                ext.run(self.config, action, until, start, names, after_extract=hook)
        finally:
            self.target.rollback()

    def ready(self):
        self.run_job("init")
        self.run_job("resume")

    def count(self, table):
        self.target.rollback()
        return ext.scalar(self.target, "SELECT COUNT(*) FROM " + schema.quote(table))

    def test_all_six_end_to_end_formulas_long_url_and_old_task_untouched(self):
        self.ready()
        self.run_job("sync")
        self.run_job("verify")
        self.assertEqual([3, 1, 1, 1, 1, 1], [self.count(n) for n in schema.JOBS])
        rows = self.execute(self.target, "SELECT CHAR_LENGTH(url) FROM bi_page_click_log WHERE url IS NOT NULL")
        self.assertEqual([len(self.long_url)] * 2, [r[0] for r in rows])
        row = self.execute(self.target, "SELECT used_storage_tb,storage_usage_pct,file_usage_pct"
                           " FROM bi_project_capacity_daily")[0]
        self.assertEqual((Decimal("0.5000"), Decimal("50.0000"), Decimal("20.0000")), row)
        self.assertEqual(2.12, ext.scalar(self.target, "SELECT total_size FROM bi_tenant_optimization_daily"))
        self.assertIsNone(ext.scalar(self.target, "SELECT post FROM bi_iam_user"))
        self.assertEqual("13800000000", ext.scalar(self.target, "SELECT phonenumber FROM bi_iam_user"))
        self.assertEqual("2020-01-01", str(ext.scalar(self.target, "SELECT DATE(create_time) FROM bi_screen")))
        self.assertEqual("old-seven-sentinel", ext.scalar(self.target, "SELECT name FROM bi_report"))
        self.assertEqual("2026-11-05", ext.scalar(self.target, "SELECT value FROM bi_sync_control"))

    def test_rerun_preserves_real_duplicates_and_includes_late_row(self):
        self.ready()
        self.run_job("sync", names=["bi_page_click_log"])
        self.run_job("sync", names=["bi_page_click_log"])
        self.assertEqual(3, self.count("bi_page_click_log"))
        self.execute(self.source, "INSERT INTO fenxiyun.operation_log_detail VALUES"
                     "('2026-01-01 15:00:00','76','later','7','sample_wx','later',"
                     "'2026-01-02 01:00:00','same-eid',0)")
        self.source.commit()
        self.run_job("sync", names=["bi_page_click_log"])
        self.assertEqual(4, self.count("bi_page_click_log"))

    def test_publish_failure_rolls_back_delete_and_preserves_progress(self):
        self.ready()
        self.run_job("sync", names=["bi_page_click_log"])
        before = ext.state(self.target, "table:bi_page_click_log")
        self.execute(self.target, """CREATE TRIGGER fail_publish BEFORE INSERT ON bi_page_click_log
            FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='fixture failure'""")
        self.target.commit()
        with self.assertRaises(Exception):
            self.run_job("sync", names=["bi_page_click_log"])
        self.assertEqual(3, self.count("bi_page_click_log"))
        self.assertEqual(before, ext.state(self.target, "table:bi_page_click_log"))
        self.assertEqual("FAILED", ext.state(self.target, "last_run")["outcome"])

    def test_source_disconnect_after_partial_stage_reconnects_without_duplicate_live_rows(self):
        self.ready()
        self.run_job("sync", names=["bi_page_click_log"])
        real_read = ext.source_read
        fetched, opened = [], []

        def connection(endpoint, role):
            con = ext.connect(endpoint, role)
            if role == "click":
                opened.append(con)
            return con

        def interrupted(step, operation, *args):
            if step == "source_fetch":
                fetched.append(step)
                if len(fetched) == 2:
                    def disconnect():
                        raise self.driver.OperationalError(2013, "private-sentinel")
                    return real_read(step, disconnect)
            return real_read(step, operation, *args)

        output = io.StringIO()
        with redirect_stdout(output), patch.object(ext, "source_read", side_effect=interrupted), \
                patch.object(ext.time, "sleep"):
            ext.run(self.config, "sync", DAY + timedelta(days=1), tables=["bi_page_click_log"],
                    connect_fn=connection)
        self.assertEqual(2, len(opened))
        self.assertEqual(3, self.count("bi_page_click_log"))
        self.assertEqual(3, self.count(schema.JOBS["bi_page_click_log"].stage))
        self.assertEqual("2026-01-02", ext.state(self.target, "table:bi_page_click_log")["success_until"])
        self.assertEqual("SUCCESS", ext.state(self.target, "last_run")["outcome"])
        self.assertIn("source_fetch", output.getvalue())
        self.assertNotIn("private-sentinel", output.getvalue())

    def test_actual_killed_source_session_retries_current_day(self):
        self.ready()
        real_read = ext.source_read
        opened, killed = [], []

        def connection(endpoint, role):
            con = ext.connect(endpoint, role)
            if role == "optimization":
                opened.append(con)
            return con

        def interrupted(step, operation, *args):
            if step == "source_count_after" and not killed:
                self.execute(self.source, "KILL CONNECTION " + str(opened[-1].thread_id()))
                killed.append(True)
            return real_read(step, operation, *args)

        with redirect_stdout(io.StringIO()), patch.object(ext, "source_read", side_effect=interrupted), \
                patch.object(ext.time, "sleep"):
            ext.run(self.config, "sync", DAY + timedelta(days=1),
                    tables=["bi_tenant_optimization_daily"], connect_fn=connection)
        self.assertEqual(2, len(opened))
        self.assertEqual(1, self.count("bi_tenant_optimization_daily"))
        self.assertEqual("SUCCESS", ext.state(self.target, "last_run")["outcome"])

    def test_target_disconnect_does_not_reconnect_or_mask_error_and_saves_failure(self):
        self.ready()
        self.run_job("sync", names=["bi_page_click_log"])
        before = ext.state(self.target, "table:bi_page_click_log")
        real_read = ext.source_read
        opened, targets, fetched = [], [], []

        def connection(endpoint, role):
            con = ext.connect(endpoint, role)
            if role == "target":
                targets.append(con)
            elif role == "click":
                opened.append(con)
            return con

        def interrupted(step, operation, *args):
            if step == "source_fetch":
                fetched.append(step)
                if len(fetched) == 2:
                    self.execute(self.target, "KILL CONNECTION " + str(targets[-1].thread_id()))
            return real_read(step, operation, *args)

        with redirect_stdout(io.StringIO()), patch.object(ext, "source_read", side_effect=interrupted), \
                self.assertRaises(self.driver.OperationalError) as error:
            ext.run(self.config, "sync", DAY + timedelta(days=1), tables=["bi_page_click_log"],
                    connect_fn=connection)
        self.assertIn(error.exception.args[0], {2006, 2013})
        self.assertEqual(1, len(opened))
        self.assertEqual(3, self.count("bi_page_click_log"))
        self.assertEqual(before, ext.state(self.target, "table:bi_page_click_log"))
        failure = ext.state(self.target, "last_run")
        self.assertEqual("FAILED", failure["outcome"])
        self.assertEqual("bi_page_click_log", failure["failure_phase"])
        self.assertEqual(error.exception.args[0], failure["error_code"])

    def test_ttl_roll_forward_does_not_delete_archived_target_rows(self):
        self.ready()
        self.run_job("sync", names=["bi_page_click_log"])
        self.retained = {DAY + timedelta(days=1), DAY + timedelta(days=2)}
        self.run_job("sync", until=DAY + timedelta(days=2), names=["bi_page_click_log"])
        self.assertEqual(3, self.count("bi_page_click_log"))
        info = ext.state(self.target, "table:bi_page_click_log")
        self.assertEqual("2026-01-03", info["success_until"])
        self.assertEqual([], info["gaps"])
        self.run_job("verify", until=DAY + timedelta(days=2), names=["bi_page_click_log"])

    def test_missing_history_and_empty_monitoring_snapshot_are_explicit(self):
        self.ready()
        self.execute(self.source, "UPDATE platform.cluster_data_to_be_optimized SET dayid='2026-01-02'")
        self.source.commit()
        self.run_job("sync", until=DAY + timedelta(days=2), names=["bi_tenant_optimization_daily"])
        self.assertEqual(1, self.count("bi_tenant_optimization_daily"))
        info = ext.state(self.target, "table:bi_tenant_optimization_daily")
        self.assertEqual("2026-01-02", info["available_from"])
        self.assertEqual("SOURCE_HISTORY_UNAVAILABLE", info["gaps"][0]["reason"])
        self.run_job("sync", until=DAY + timedelta(days=3), names=["bi_tenant_optimization_daily"])
        ledger = ext.state(self.target, "day:bi_tenant_optimization_daily:2026-01-03")
        self.assertEqual("SOURCE_SNAPSHOT_EMPTY", ledger["outcome"])
        self.assertEqual("SUCCESS_WITH_GAPS", ext.state(self.target, "last_run")["outcome"])

    def test_empty_monitoring_rerun_keeps_previous_day_values(self):
        self.ready()
        self.execute(self.source, "INSERT INTO dataanalysis1.project_storage"
                     "(project_id,record_date,storage,file_num) VALUES(7,'20260102',1099511627776,300)")
        self.source.commit()
        self.run_job("sync", until=DAY + timedelta(days=2), names=["bi_project_capacity_daily"])
        self.execute(self.source, "DELETE FROM dataanalysis1.project_storage WHERE record_date='20260102'")
        self.source.commit()
        self.run_job("sync", until=DAY + timedelta(days=2), names=["bi_project_capacity_daily"])
        self.assertEqual(2, self.count("bi_project_capacity_daily"))
        self.assertEqual(Decimal("1.0000"), ext.scalar(self.target,
                         "SELECT used_storage_tb FROM bi_project_capacity_daily WHERE record_date='2026-01-02'"))
        self.assertEqual("SOURCE_SNAPSHOT_EMPTY", ext.state(self.target,
                         "day:bi_project_capacity_daily:2026-01-02")["outcome"])

    def test_pause_during_extract_and_resume_does_not_touch_live(self):
        self.ready()
        self.run_job("sync", names=["bi_page_click_log"])
        def pause(job, target, monitor):
            ext.save(monitor, "paused", True)
            monitor.commit()
        self.run_job("sync", names=["bi_page_click_log"], hook=pause)
        self.assertEqual(3, self.count("bi_page_click_log"))
        self.assertEqual("PAUSED", ext.state(self.target, "last_run")["outcome"])
        self.run_job("resume")
        self.run_job("sync", names=["bi_page_click_log"])
        self.assertEqual(3, self.count("bi_page_click_log"))

    def test_unowned_table_extra_unique_key_and_wrong_source_are_rejected(self):
        self.execute(self.target, "CREATE TABLE bi_page_click_log(id int)")
        self.target.commit()
        with self.assertRaises(ValueError):
            self.run_job("init")
        self.execute(self.target, "DROP TABLE bi_page_click_log")
        self.target.commit()
        self.ready()
        self.execute(self.target, "ALTER TABLE bi_page_click_log ADD UNIQUE KEY unexpected(operator)")
        self.target.commit()
        with self.assertRaises(ValueError):
            self.run_job("sync", names=["bi_page_click_log"])
        self.config["sources"]["iam"] = dict(self.config["sources"]["iam"], host="wrong-source")
        with self.assertRaises(ValueError):
            self.run_job("status")

    def test_missing_source_column_fails_before_live_replacement(self):
        self.ready()
        self.run_job("sync", names=["bi_page_click_log"])
        self.execute(self.source, "ALTER TABLE fenxiyun.operation_log_detail DROP COLUMN module_name")
        self.source.commit()
        with self.assertRaises(ValueError):
            self.run_job("sync", names=["bi_page_click_log"])
        self.assertEqual(3, self.count("bi_page_click_log"))

    def test_migrate_nullable_column_and_retry_init(self):
        self.run_job("init")
        self.execute(self.target, "ALTER TABLE bi_screen DROP COLUMN title")
        self.target.commit()
        with self.assertRaises(ValueError):
            self.run_job("resume")
        self.run_job("migrate")
        self.run_job("init")
        self.run_job("resume")
        self.run_job("sync", names=["bi_screen"])
        self.assertEqual(1, self.count("bi_screen"))

    def test_source_account_is_readonly(self):
        con = ext.connect(self.config["sources"]["iam"], "iam")
        try:
            with self.assertRaises(Exception):
                self.execute(con, "DELETE FROM user")
        finally:
            con.close()

    def test_verify_detects_same_count_source_update(self):
        self.ready()
        self.run_job("sync")
        self.execute(self.source, "UPDATE iam.user SET username='已变更' WHERE id=1")
        self.source.commit()
        with self.assertRaises(ValueError):
            self.run_job("verify", names=["bi_iam_user"])
        self.execute(self.source, "UPDATE dataanalysis1.project_storage SET storage=1 WHERE record_date='20260101'")
        self.source.commit()
        with self.assertRaises(ValueError):
            self.run_job("verify", names=["bi_project_capacity_daily"])

    def test_archived_checksum_is_verified_without_source_replay(self):
        self.ready()
        self.run_job("sync", names=["bi_page_click_log"])
        self.retained = {DAY + timedelta(days=1), DAY + timedelta(days=2)}
        self.run_job("sync", until=DAY + timedelta(days=2), names=["bi_page_click_log"])
        self.execute(self.target, "UPDATE bi_page_click_log SET module_name='tampered' WHERE id>0")
        self.target.commit()
        with self.assertRaises(ValueError):
            self.run_job("verify", until=DAY + timedelta(days=2), names=["bi_page_click_log"])

    def test_concurrent_task_refused_but_pause_can_interrupt(self):
        self.ready()
        lock = self.admin(self.ports[1], "data_agent")
        try:
            self.assertEqual(1, ext.scalar(lock, "SELECT GET_LOCK(%s,0)", (ext.LOCK,)))
            with self.assertRaises(ValueError):
                self.run_job("sync", names=["bi_page_click_log"])
            self.run_job("pause")
            self.assertTrue(ext.state(self.target, "paused"))
        finally:
            ext.scalar(lock, "SELECT RELEASE_LOCK(%s)", (ext.LOCK,))
            lock.close()

    def test_explicit_short_first_run_records_unprocessed_gap(self):
        self.ready()
        self.run_job("sync", until=DAY + timedelta(days=3), start=DAY + timedelta(days=2),
                     names=["bi_page_click_log"])
        info = ext.state(self.target, "table:bi_page_click_log")
        self.assertEqual("NOT_PROCESSED", info["gaps"][0]["reason"])
        self.assertEqual("2026-01-01", info["gaps"][0]["from"])
        self.assertEqual("2026-01-03", info["gaps"][0]["until"])
        with self.assertRaises(ValueError):
            self.run_job("verify", until=DAY + timedelta(days=3), names=["bi_page_click_log"])

    def test_daily_sync_refreshes_exact_lookback_and_catches_up_after_downtime(self):
        self.ready()
        name = "bi_screen_visit_log"
        self.run_job("sync", until=DAY + timedelta(days=3), names=[name])
        self.execute(self.source, "UPDATE easybi.sys_big_screen_visit_log SET operation='changed' WHERE id=1")
        self.source.commit()
        self.run_job("sync", until=DAY + timedelta(days=4), names=[name])
        self.assertEqual("visit", ext.scalar(self.target, "SELECT operation FROM bi_screen_visit_log"))
        self.run_job("sync", until=DAY + timedelta(days=9), names=[name])
        for offset in range(4, 9):
            self.assertIsNotNone(ext.state(self.target, "day:" + name + ":" + str(DAY + timedelta(days=offset))))


if __name__ == "__main__":
    unittest.main()
