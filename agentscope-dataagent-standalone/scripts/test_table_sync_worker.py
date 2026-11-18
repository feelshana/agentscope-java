"""Unit checks and opt-in integration against an isolated MySQL 5.7/8 pair."""

import datetime as dt
import importlib.util
import os
import pathlib
import subprocess
import unittest
from unittest.mock import MagicMock, Mock, patch


WORKER = pathlib.Path(__file__).resolve().parents[1] / "src/main/resources/table-sync/worker.py"
SPEC = importlib.util.spec_from_file_location("table_sync_worker", WORKER)
worker = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(worker)


class DateFormatsTest(unittest.TestCase):
    def test_manual_migration_only_creates_tables_in_existing_platform_database(self):
        migration = WORKER.parents[4] / "docs/migrations/0152-table-sync-mysql.sql"
        sql = "\n".join(line for line in migration.read_text(encoding="utf-8").splitlines()
                        if not line.startswith("--"))
        tables = ("target", "task", "run", "coverage")
        statements = [statement.strip() for statement in sql.split(";") if statement.strip()]
        self.assertEqual(len(statements), len(tables))
        for table, statement in zip(tables, statements):
            self.assertTrue(statement.startswith(
                f"CREATE TABLE IF NOT EXISTS `data_agent_platform`.`dataagent_table_sync_{table}`"))

    def test_supported_date_formats(self):
        day = dt.date(2026, 10, 19)
        for grain, fmt, kind, expected in (
            ("DAY", "YYYYMMDD", "int", 20261019),
            ("DAY", "YYYYMMDD", "char", "20261019"),
            ("DAY", "YYYY-MM-DD", "varchar", "2026-10-19"),
            ("DAY", "YYYY/MM/DD", "varchar", "2026/10/19"),
            ("MONTH", "YYYYMM", "int", 202610),
            ("MONTH", "YYYYMM", "char", "202610"),
            ("MONTH", "YYYY-MM", "char", "2026-10"),
        ):
            config = {"dateField": "ts", "dateFormat": fmt, "dateKind": kind, "grain": grain}
            with self.subTest(fmt=fmt, kind=kind):
                self.assertEqual(worker.bounds(config, day), (" WHERE `ts`=%s", (expected,)))

    def test_utc_month_bounds(self):
        config = {"dateField": "ts", "dateFormat": "NATIVE", "dateKind": "datetime",
                  "grain": "MONTH", "storageZone": "+00:00"}
        _, args = worker.bounds(config, dt.date(2026, 11, 1))
        self.assertEqual(args, (dt.datetime(2026, 10, 31, 16), dt.datetime(2026, 11, 30, 16)))
        self.assertEqual(worker.previous_period(dt.date(2026, 1, 1), "MONTH"), dt.date(2025, 12, 1))

    def test_due_only_for_the_expected_slot(self):
        task = {"schedule_time": dt.timedelta(hours=2), "schedule_enabled_at": dt.datetime(2026, 10, 19)}
        self.assertTrue(worker.scheduled_due(task, dt.datetime(2026, 11, 1, 2), "MONTH"))
        self.assertFalse(worker.scheduled_due(task, dt.datetime(2026, 11, 2, 2), "MONTH"))
        self.assertTrue(worker.scheduled_due(task, dt.datetime(2026, 11, 2, 2), "DAY"))
        task["schedule_enabled_at"] = dt.datetime(2026, 11, 2, 3)
        self.assertFalse(worker.scheduled_due(task, dt.datetime(2026, 11, 2, 4), "DAY"))

    def test_batches_respect_row_and_byte_limits(self):
        stream = Mock()
        stream.fetchone.side_effect = [(i,) for i in range(1100)] + [None]
        self.assertEqual([len(batch) for batch, _ in worker.batches(stream)], [500, 500, 100])
        stream.fetchone.side_effect = [(b"a" * (worker.BATCH_BYTES // 3),)] * 2 + [None]
        batches = list(worker.batches(stream))
        self.assertEqual(len(batches), 2)
        self.assertTrue(all(size <= worker.BATCH_BYTES for _, size in batches))

    def test_unindexed_period_uses_date_filter_and_rolls_back_on_limit(self):
        config = {"source": {"database": "books"}, "table": "report", "grain": "MONTH",
                  "columns": [{"name": "period_id", "indexed": False}],
                  "dateField": "period_id", "dateKind": "varchar", "dateFormat": "YYYYMM"}
        src, dst = Mock(), MagicMock()
        stream = src.cursor.return_value
        stream.fetchone.side_effect = [("202601",)] * 3 + [None]
        writer = dst.cursor.return_value.__enter__.return_value
        writer.executemany.return_value = 2
        with (patch.object(worker, "PERIOD_LIMIT", 2), patch.object(worker, "BATCH", 2),
              patch.object(worker, "update_phase"), patch.object(worker, "scalar", return_value=None),
              patch.object(worker.time, "sleep")):
            with self.assertRaisesRegex(worker.SyncError, "ROW_LIMIT"):
                worker.copy_period(src, dst, {"id": "task"}, {"id": "run", "kind": "INITIAL"},
                                   config, "mirror", dt.date(2026, 1, 1))
        stream.execute.assert_called_once_with("SELECT /*+ MAX_EXECUTION_TIME(60000) */ `period_id` "
                                               "FROM `books`.`report` WHERE `period_id`=%s LIMIT 3", ("202601",))
        writer.executemany.assert_called_once()
        dst.rollback.assert_called_once()
        dst.commit.assert_not_called()

    def test_date_sample_ignores_other_selected_columns_and_returns_three_rows(self):
        data = {"database": "books", "table": "report", "columns": ["period_id", "create_time"],
                "grain": "MONTH", "dateField": "period_id", "dateFormat": "YYYYMM", "sampleDate": "2026-09-01"}
        columns = [{"name": "period_id", "kind": "varchar", "indexed": False, "type": "varchar(20)",
                    "charset": None, "collation": None, "extra": ""}, {"name": "create_time", "kind": "timestamp"}]
        source = Mock()
        rows = [{"period_id": "202609"}] * 3
        with patch.object(worker, "source_connection", return_value=source), patch.object(
                worker, "table_version", return_value="5.7.44"), patch.object(
                    worker, "source_metadata", return_value=(columns, [])), patch.object(
                        worker, "query", return_value=rows) as query:
            self.assertEqual(worker.explore("test-date", data, None), {"matches": 3, "sampleValues": ["202609"] * 3})
        query.assert_called_once_with(source, "SELECT /*+ MAX_EXECUTION_TIME(30000) */ `period_id` "
                                      "FROM `books`.`report` WHERE `period_id`=%s LIMIT 3", ("202609",))

    def test_failed_write_rolls_back_the_period(self):
        dst = Mock()
        with patch.object(worker, "update_phase"), patch.object(
                worker, "transfer", side_effect=worker.SyncError("ROW_LIMIT")):
            with self.assertRaisesRegex(worker.SyncError, "ROW_LIMIT"):
                worker.copy_period(Mock(), dst, {"id": "task"}, {"id": "run"},
                                   {"source": {"database": "fixture"}}, "mirror", None)
        dst.rollback.assert_called_once()
        dst.commit.assert_not_called()

    def test_missing_control_table_is_reported_without_ddl(self):
        mirror, platform = Mock(), Mock()
        with patch.object(worker, "target_connection", side_effect=[mirror, platform]), patch.object(
                worker, "table_version", return_value="8.0.36"), patch.object(
                    worker, "execute", side_effect=worker.MySQLdb.ProgrammingError(1146, "missing")) as execute:
            with self.assertRaisesRegex(worker.SyncError, "MIGRATION_REQUIRED"):
                worker.check_target({})
            self.assertTrue(execute.call_args.args[1].startswith("SELECT 1 FROM `dataagent_table_sync_"))

    def test_ten_due_schedules_are_queued_even_while_worker_is_busy(self):
        at = dt.datetime(2026, 11, 18, 3)
        ids = [f"task-{i}" for i in range(10)]
        schedule = {"config": '{"grain":"DAY"}', "schedule_time": dt.timedelta(hours=2),
                    "schedule_enabled_at": at - dt.timedelta(days=1)}
        with patch.object(worker, "now", return_value=at), patch.object(
                worker, "query", return_value=[{"id": task_id} for task_id in ids]), patch.object(
                worker, "find_task", side_effect=lambda con, task_id, lock: {**schedule, "id": task_id}), patch.object(
                worker, "scalar", return_value=None), patch.object(
                worker, "one") as load_job, patch.object(worker, "enqueue") as enqueue:
            self.assertEqual(worker.next_job(Mock(), dispatch=False), {})
            self.assertEqual([item.args[1] for item in enqueue.call_args_list], ids)
            self.assertTrue(all(item.args[-1] == at.date() for item in enqueue.call_args_list))
            load_job.assert_not_called()

    def test_held_database_lock_prevents_starting_another_transfer(self):
        with patch.object(worker, "scalar", return_value=0), patch.object(worker, "one") as load_job:
            self.assertEqual(worker.run_job(Mock(), {"id": "queued"}), {})
            load_job.assert_not_called()


@unittest.skipUnless(os.getenv("TABLE_SYNC_ISOLATED_FIXTURE") == "1", "requires isolated 5.7/8 fixture")
class LiveMySqlTest(unittest.TestCase):
    source = {"host": "127.0.0.1", "port": 33571, "database": "fixture_source",
              "username": "fixture_reader", "password": "fixture-reader-148"}
    actor = "fixture"

    def call(self, operation, data):
        return worker.serve(operation, data, self.actor)

    def source_sql(self, sql):
        # Only the disposable source fixture administrator changes the seed data.
        subprocess.run(
            ["docker", "exec", "-i", "-e", "MYSQL_PWD=fixture-local-148",
             "codex-table-sync-148-source", "mysql", "-uroot", "fixture_source"],
            input=sql, text=True, check=True, capture_output=True,
        )

    def setup_task(self, table, columns, grain, field=None, fmt=None, start=None, through=None):
        target = "sync_" + table + "_" + worker.uuid.uuid4().hex[:8]
        payload = {**self.source, "name": table, "table": table, "columns": columns, "grain": grain,
                   "targetTable": target, "dateField": field, "dateFormat": fmt, "from": start, "through": through}
        preview = self.call("preview", payload)
        self.assertIn("CREATE TABLE", preview["ddl"])
        self.assertFalse(preview["targetReady"])
        with self.assertRaisesRegex(worker.SyncError, "TARGET_TABLE_REQUIRED"):
            self.call("create", {**payload, "fingerprint": preview["fingerprint"]})
        subprocess.run(
            ["docker", "exec", "-i", "-e", "MYSQL_PWD=fixture-local-148",
             "codex-table-sync-148-target", "mysql", "-uroot", "data_agent"],
            input=preview["ddl"], text=True, check=True, capture_output=True,
        )
        task = self.call("create", {**payload, "fingerprint": preview["fingerprint"]})
        return task["id"], target, payload

    def process(self, task_id):
        job = self.call("next-job", {})
        self.assertIn("id", job)
        self.call("run-job", {"id": job["id"], "password": self.source["password"]})
        return self.call("detail", {"id": task_id})

    def count(self, target):
        with worker.contextlib.closing(worker.target_connection()) as con:
            return worker.scalar(con, f"SELECT COUNT(*) FROM {worker.qualified(worker.TARGET_DB, target)}")

    def test_daily_initial_rerun_and_gaps(self):
        task_id, target, payload = self.setup_task(
            "sales_day", ["day_key", "value", "label"], "DAY", "day_key", "YYYYMMDD", "2026-10-31", "2026-11-01")
        sample = self.call("test-date", {**payload, "sampleDate": "2026-10-31"})
        self.assertEqual(sample, {"matches": 2, "sampleValues": ["20261031", "20261031"]})
        detail = self.process(task_id)
        self.assertEqual(detail["lastRun"]["state"], "SUCCESS", detail)
        self.assertTrue(detail["initialized"])
        self.assertEqual(self.count(target), 3)
        self.assertEqual(detail["continuousThrough"].isoformat(), "2026-11-01")
        self.call("rerun", {"id": task_id, "from": "2026-10-31", "through": "2026-10-31"})
        detail = self.process(task_id)
        self.assertEqual(detail["lastRun"]["rows_committed"], 2)
        self.assertEqual(self.count(target), 3)
        self.assertNotIn("credential", detail)

    def test_full_reload_source_readonly_and_collision(self):
        task_id, target, payload = self.setup_task("small_dim", ["id", "label"], "FULL")
        detail = self.process(task_id)
        self.assertEqual(detail["lastRun"]["state"], "SUCCESS", detail)
        self.assertEqual(self.count(target), 2)
        self.assertTrue(self.call("preview", payload)["targetReady"])
        self.call("run-now", {"id": task_id})
        detail = self.process(task_id)
        self.assertEqual(detail["lastRun"]["state"], "SUCCESS", detail)
        self.assertEqual(self.count(target), 2)
        with worker.contextlib.closing(worker.source_connection(self.source, self.source["password"])) as con:
            self.assertEqual(worker.scalar(con, "SELECT COUNT(*) FROM small_dim"), 2)
            grants = worker.query(con, "SHOW GRANTS")
            self.assertTrue(any("GRANT SELECT ON" in str(row) for row in grants))

    def test_monthly_format_and_empty_sample(self):
        task_id, target, payload = self.setup_task(
            "sales_month", ["month_key", "value"], "MONTH", "month_key", "YYYY-MM", "2026-09-01", "2026-10-01")
        self.assertEqual(self.call("test-date", {**payload, "sampleDate": "2026-10-01"})["sampleValues"], ["2026-10"])
        self.assertEqual(self.call("test-date", {**payload, "sampleDate": "2026-08-01"})["matches"], 0)
        detail = self.process(task_id)
        self.assertEqual(detail["lastRun"]["state"], "SUCCESS", detail)
        self.assertEqual(self.count(target), 2)
        self.call("schedule", {"id": task_id, "time": "02:00"})
        self.assertEqual(self.call("detail", {"id": task_id})["scheduleTime"], "02:00")
        self.call("schedule", {"id": task_id, "time": None})

    def test_unindexed_month_range_sample_and_rerun(self):
        task_id, target, payload = self.setup_task(
            "noidx_month", ["id", "month_key"], "MONTH", "month_key", "YYYYMM", "2026-09-01", "2026-10-01")
        with patch.object(worker, "PERIOD_LIMIT", 2):
            sample = self.call("test-date", {**payload, "sampleDate": "2026-10-01"})
            self.assertEqual(sample, {"matches": 2, "sampleValues": ["202610", "202610"]})
            detail = self.process(task_id)
        self.assertEqual(detail["lastRun"]["state"], "SUCCESS", detail)
        self.assertEqual(detail["lastRun"]["rows_committed"], 3)
        self.assertEqual(self.count(target), 3)
        self.call("rerun", {"id": task_id, "from": "2026-10-01", "through": "2026-10-01"})
        detail = self.process(task_id)
        self.assertEqual(detail["lastRun"]["state"], "SUCCESS", detail)
        self.assertEqual(detail["lastRun"]["rows_committed"], 2)
        self.assertEqual(self.count(target), 3)

    def test_numeric_month_in_batches_then_empty_rerun(self):
        table = "fixture_month_" + worker.uuid.uuid4().hex[:8]
        digits = "(" + " UNION ALL ".join(
            ["SELECT 0 AS n", *(f"SELECT {i}" for i in range(1, 10))]) + ")"
        self.source_sql(f"CREATE TABLE {table} (id INT PRIMARY KEY, month_key INT NOT NULL, "
                        f"INDEX(month_key)) ENGINE=InnoDB; "
                        f"INSERT INTO {table} SELECT a.n+10*b.n+100*c.n+1000*d.n+10000*e.n+100000*f.n,202610 "
                        f"FROM {digits} a CROSS JOIN {digits} b CROSS JOIN {digits} c CROSS JOIN {digits} d "
                        f"CROSS JOIN (SELECT 0 AS n UNION ALL SELECT 1) e CROSS JOIN (SELECT 0 AS n) f; "
                        f"INSERT INTO {table} VALUES(200000,202609),(200001,202611);")
        self.addCleanup(self.source_sql, f"DROP TABLE {table}")
        task_id, target, payload = self.setup_task(
            table, ["id", "month_key"], "MONTH", "month_key", "YYYYMM", "2026-09-01", "2026-10-01")
        self.assertEqual(self.call("test-date", {**payload, "sampleDate": "2026-10-01"}),
                         {"matches": 3, "sampleValues": ["202610"] * 3})
        detail = self.process(task_id)
        self.assertEqual(detail["lastRun"]["rows_committed"], 20001, detail)
        self.assertEqual(self.count(target), 20001)
        self.source_sql(f"DELETE FROM {table} WHERE month_key=202610")
        rerun = {"id": task_id, "from": "2026-10-01", "through": "2026-10-01"}
        self.call("rerun", rerun)
        detail = self.process(task_id)
        self.assertEqual(detail["lastRun"]["error_code"], "EMPTY_RESULT", detail)
        self.assertEqual(self.count(target), 20001)
        self.call("rerun", {**rerun, "acceptEmpty": True})
        detail = self.process(task_id)
        self.assertEqual(detail["lastRun"]["state"], "SUCCESS", detail)
        self.assertEqual(self.count(target), 1)

    def test_unindexed_daily_resume_after_oversized_period(self):
        table = "fixture_day_" + worker.uuid.uuid4().hex[:8]
        self.source_sql(f"CREATE TABLE {table} (id INT PRIMARY KEY, period_id VARCHAR(20)) ENGINE=InnoDB; "
                        f"INSERT INTO {table} VALUES (1,'20261031'),(2,'20261031'),"
                        "(3,'20261101'),(4,'20261101'),(5,'20261101'),(6,'20261102'),(7,NULL);")
        self.addCleanup(self.source_sql, f"DROP TABLE {table}")
        task_id, target, _ = self.setup_task(
            table, ["id", "period_id"], "DAY", "period_id", "YYYYMMDD", "2026-10-31", "2026-11-02")
        with patch.object(worker, "PERIOD_LIMIT", 2):
            detail = self.process(task_id)
            self.assertEqual(detail["lastRun"]["error_code"], "ROW_LIMIT", detail)
            self.assertEqual(detail["lastRun"]["progress_date"].isoformat(), "2026-11-01")
            self.assertEqual(detail["lastRun"]["rows_committed"], 2)
            self.assertEqual(detail["gaps"][0]["from"].isoformat(), "2026-11-01")
            self.assertEqual(self.count(target), 2)
            self.source_sql(f"DELETE FROM {table} WHERE id=5")
            self.call("run-now", {"id": task_id})
            detail = self.process(task_id)
            self.assertEqual(detail["lastRun"]["state"], "SUCCESS", detail)
            self.assertEqual(detail["lastRun"]["rows_committed"], 3)
            self.assertEqual(detail["continuousThrough"].isoformat(), "2026-11-02")
            self.assertEqual(self.count(target), 5)


if __name__ == "__main__":
    unittest.main()
