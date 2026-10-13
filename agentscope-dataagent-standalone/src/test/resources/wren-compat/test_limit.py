"""Regression tests using the installed Wren/SQLGlot, without a database."""
import importlib.util
import sys
import unittest
from types import SimpleNamespace
from unittest.mock import patch

import duckdb
from wren.connector import mysql
from wren.mcp_server import _query_with_limit_probe

spec = importlib.util.spec_from_file_location("limit_compat", sys.argv.pop(1))
compat = importlib.util.module_from_spec(spec)
spec.loader.exec_module(compat)


class LimitCompatibilityTest(unittest.TestCase):
    def test_preserves_smaller_count_and_zero(self):
        for count in (0, 1, 3, 50):
            sql = f"SELECT id FROM orders ORDER BY id DESC LIMIT {count}"
            self.assertEqual(compat.apply_limit(sql, 51), sql)

    def test_tightens_count_without_changing_offset_or_sql_text(self):
        for before, after in (
            ("LIMIT 50 OFFSET 2", "LIMIT 4 OFFSET 2"),
            ("LIMIT 2, 50", "LIMIT 2, 4"),
            ("limit /* 中文 */ 50 -- tail", "limit /* 中文 */ 4 -- tail"),
        ):
            sql = "SELECT a.id, b.id FROM a JOIN b ON a.id=b.id ORDER BY a.id "
            self.assertEqual(compat.apply_limit(sql + before, 4), sql + after)

    def test_inner_limit_is_preserved(self):
        sql = "SELECT * FROM (SELECT id FROM orders LIMIT 1) t ORDER BY id"
        self.assertEqual(compat.apply_limit(sql, 5), sql + "\nLIMIT 5")
        self.assertEqual(compat.apply_limit(sql + " LIMIT 50", 5), sql + " LIMIT 5")

    def test_cte_and_union_keep_inner_limits(self):
        for sql in (
            "WITH a AS (SELECT id FROM orders LIMIT 1) SELECT * FROM a LIMIT 10",
            "SELECT 1 AS id UNION ALL SELECT 2 AS id ORDER BY id LIMIT 10",
        ):
            self.assertEqual(compat.apply_limit(sql, 2), sql[:-2] + "2")

    def test_literals_comments_and_terminator(self):
        sql = "SELECT 'LIMIT 500' AS note /* LIMIT 8 */ -- LIMIT 9"
        self.assertEqual(compat.apply_limit(sql, 3), sql + "\nLIMIT 3")
        self.assertEqual(compat.apply_limit("SELECT 1 LIMIT 8;  ", 3), "SELECT 1 LIMIT 3")

    def test_invalid_or_unlocatable_limits_fail(self):
        for sql in ("SELECT 1; SELECT 2", "DELETE FROM orders", "SELECT 1 LIMIT ?"):
            with self.assertRaises(Exception):
                compat.apply_limit(sql, 3)
        with self.assertRaises(Exception):
            compat.apply_limit("SELECT 1", -1)

    def test_result_order_offset_and_nested_meaning(self):
        connection = duckdb.connect()
        try:
            base = "SELECT range AS id FROM range(10) ORDER BY id DESC"
            self.assertEqual(connection.execute(compat.apply_limit(base + " LIMIT 5 OFFSET 2", 3)).fetchall(), [(7,), (6,), (5,)])
            self.assertEqual(connection.execute(compat.apply_limit(base + " LIMIT 0", 3)).fetchall(), [])
            nested = "SELECT SUM(id) AS total FROM (" + base + " LIMIT 2) t"
            self.assertEqual(connection.execute(compat.apply_limit(nested, 3)).fetchone(), (17,))
        finally:
            connection.close()

    def test_official_n_plus_one_probe(self):
        class Engine:
            def query(self, sql, limit):
                connection = duckdb.connect()
                try:
                    return connection.execute(compat.apply_limit(sql, limit)).fetch_arrow_table()
                finally:
                    connection.close()
        ctx = SimpleNamespace(engine=Engine())
        sql = "SELECT range AS id FROM range(10) ORDER BY id LIMIT 8 OFFSET 2"
        result = _query_with_limit_probe(ctx, sql, 3)
        self.assertEqual(result["row_count"], 3)
        self.assertTrue(result["truncated"])
        self.assertEqual([row["id"] for row in result["rows"]], [2, 3, 4])
        result = _query_with_limit_probe(ctx, sql.replace("LIMIT 8", "LIMIT 2"), 3)
        self.assertEqual(result["row_count"], 2)
        self.assertFalse(result["truncated"])

    def test_install_is_version_scoped_and_keeps_existing_fix(self):
        with patch("importlib.metadata.version", return_value="0.16.0"), patch.object(mysql, "_apply_limit") as original:
            compat.install()
            self.assertIs(mysql._apply_limit, original)
            original.assert_not_called()
        fixed = lambda sql, limit: sql
        with patch("importlib.metadata.version", return_value="0.15.0"), patch.object(mysql, "_apply_limit", fixed):
            compat.install()
            self.assertIs(mysql._apply_limit, fixed)

    def test_install_replaces_only_broken_composition(self):
        broken = lambda sql, limit: f"{sql}\nLIMIT {limit}"
        with patch("importlib.metadata.version", return_value="0.15.0"), patch.object(mysql, "_apply_limit", broken):
            compat.install()
            self.assertEqual(mysql._apply_limit("SELECT 1 LIMIT 1", 2), "SELECT 1 LIMIT 1")


if __name__ == "__main__":
    unittest.main()
