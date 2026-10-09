"""Execute fixed logical SQL through official Wren on an isolated DuckDB fixture.

This measures semantic execution, not natural-language-to-SQL generation.
"""
import base64
import json
from pathlib import Path
import re
from types import SimpleNamespace

import duckdb
from wren.engine import WrenEngine
from wren.config import WrenConfig
from wren.cube_cli import _build_cube_query
from wren_core import cube_query_to_sql
from wren.mcp_server import _query_with_limit_probe

from generate import TABLES

ROOT = Path(__file__).resolve().parent
DATA = ROOT / "runtime-data"
DATA.mkdir(exist_ok=True)
database = DATA / "gap_fixture.duckdb"
connection = duckdb.connect(str(database))
for name, (columns, rows) in TABLES.items():
    types = ["BIGINT" if any(isinstance(row[i], int) for row in rows) else "VARCHAR"
             for i in range(len(columns))]
    ddl = ",".join(f'"{column}" {kind}' for column, kind in zip(columns, types))
    connection.execute(f'CREATE OR REPLACE TABLE "ds_{name}" ({ddl})')
    connection.executemany(f'INSERT INTO "ds_{name}" VALUES ({",".join("?" for _ in columns)})', rows)
connection.execute('CREATE OR REPLACE TABLE ds_unpublished AS SELECT 999 AS sentinel')
connection.close()

manifest = json.loads((ROOT / "official-project" / "target" / "mdl.json").read_text(encoding="utf-8"))
for model in manifest["models"]:
    model["tableReference"] = {"catalog": "gap_fixture", "schema": "main", "table": "ds_" + model["name"]}
manifest["dataSource"] = "duckdb"
encoded = base64.b64encode(json.dumps(manifest).encode()).decode()
reports = []
with WrenEngine(encoded, "duckdb", {"url": str(DATA), "format": "duckdb"}) as engine:
    for case in json.loads((ROOT / "questions.json").read_text(encoding="utf-8")):
        sql = case["oracle_sql_sqlite"]
        for name in sorted(TABLES, key=len, reverse=True):
            sql = re.sub(r"\bds_" + re.escape(name) + r"\b", '"' + name + '"', sql)
        try:
            table = engine.query(sql)
            actual = [list(row.values()) for row in table.to_pylist()]
            passed = actual == case["expected"]
            reports.append({"id":case["id"], "logical_sql":sql, "actual":actual,
                            "expected":case["expected"], "status":"PASS" if passed else "MISMATCH"})
        except Exception as error:
            reports.append({"id":case["id"], "logical_sql":sql, "status":"ERROR", "error":str(error)})
    caps = []
    cube_results = []
    for name, dimensions, time_dimension in [("total", "", None), ("customer", "customer_name", None), ("month", "", "order_time:month:2025-01-01,2025-05-01")]:
        try:
            cube_query = _build_cube_query("revenue", "valid_revenue_cents", dimensions, time_dimension, [], [], None, None)
            cube_sql = cube_query_to_sql(json.dumps(cube_query), json.dumps(manifest))
            cube_results.append({"name": name, "sql": cube_sql, "actual": engine.query(cube_sql).to_pylist(), "status": "EXECUTED"})
        except Exception as error:
            cube_results.append({"name": name, "status": "ERROR", "error": str(error)})
    calendar_case = next(report for report in reports if report["id"] == "Q04")
    revised_sql = calendar_case["logical_sql"].replace('"customer" c', '"customer" cust').replace('o.customer_id=c.customer_id', 'o.customer_id=cust.customer_id').replace('c.is_internal', 'cust.is_internal')
    try:
        revised_actual = [list(row.values()) for row in engine.query(revised_sql).to_pylist()]
        calendar_repair = {"logical_sql": revised_sql, "actual": revised_actual,
                           "status": "PASS" if revised_actual == calendar_case.get("expected", [["2025-01",300.0],["2025-02",700.0],["2025-03",0],["2025-04",50.0]]) else "MISMATCH"}
    except Exception as error:
        calendar_repair = {"logical_sql": revised_sql, "status": "ERROR", "error": str(error)}
    context = SimpleNamespace(engine=engine)
    for limit in (None, 10000):
        result = _query_with_limit_probe(context, 'SELECT event_id,value_cents FROM "event" ORDER BY event_id', limit)
        caps.append({"limit":limit, "rows":len(result["rows"]), "truncated":result["truncated"],
                     "sum_yuan":sum(row["value_cents"] for row in result["rows"]) / 100})
    try:
        engine.dry_run('SELECT nonexistent_customer_column, SUM(amount_cents) FROM "order" GROUP BY 1')
        invalid = {"status":"UNEXPECTED_PASS"}
    except Exception as error:
        invalid = {"status":"REJECTED", "error":str(error)}
    unmodeled_sql = 'SELECT sentinel FROM gap_fixture.main.ds_unpublished'
    try:
        default_boundary = {"status": "ALLOWED", "actual": engine.query(unmodeled_sql).to_pylist()}
    except Exception as error:
        default_boundary = {"status": "REJECTED", "error": str(error)}
with WrenEngine(encoded, "duckdb", {"url": str(DATA), "format": "duckdb"}, config=WrenConfig(strict_mode=True)) as strict_engine:
    try:
        strict_engine.query(unmodeled_sql)
        strict_boundary = {"status": "UNEXPECTED_PASS"}
    except Exception as error:
        strict_boundary = {"status": "REJECTED", "error": str(error)}
output = {"scope":"Official Wren SDK on local DuckDB; fixed SQL, no LLM, no platform HTTP or Docker.",
          "cases":reports,"calendar_alias_repair":calendar_repair,"official_mcp_limit_probe":caps,"invalid_column_dry_run":invalid,
          "unmodeled_table_default":default_boundary,"unmodeled_table_strict":strict_boundary,"cube_execution":cube_results}
(ROOT / "official-execution-results.json").write_text(json.dumps(output,ensure_ascii=False,indent=2,default=str),encoding="utf-8")
print(json.dumps({"cases":[{"id":r["id"],"status":r["status"]} for r in reports],
                  "calendar_alias_repair": calendar_repair, "caps":caps,"invalid_column_dry_run":invalid},ensure_ascii=False,default=str))
