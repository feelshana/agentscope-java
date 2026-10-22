"""Run installed Wren offline probes; preserve raw outputs and versions."""

import argparse
import csv
import importlib.metadata
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys

ROOT = Path(__file__).resolve().parent


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--wren", required=True)
    args = parser.parse_args()
    project = ROOT / "official-project"
    env = dict(os.environ, PYTHONUTF8="1", PYTHONIOENCODING="utf-8", WREN_PROJECT_HOME=str(project))
    reports = []

    def probe(name, arguments, expected=0, cwd=project):
        result = subprocess.run([args.wren, *arguments], cwd=cwd, env=env,
                                capture_output=True, encoding="utf-8", timeout=120)
        reports.append({"name": name, "argv": arguments, "exit_code": result.returncode,
                        "stdout": result.stdout, "stderr": result.stderr,
                        "expectation_met": (result.returncode == 0) if expected == 0 else (result.returncode != 0)})

    probe("version", ["--version"])
    probe("validate", ["context", "validate", "--strict"])
    probe("build", ["context", "build"])
    probe("manifest_deserialization", ["dry-plan", "--sql", "SELECT 1", "-d", "mysql"])
    probe("logical_revenue_plan", ["dry-plan", "--sql",
          "SELECT SUM(CASE WHEN status = 'PAID' AND is_deleted = 0 AND customer_internal = 0 THEN amount_cents ELSE 0 END) FROM \"order\"", "-d", "mysql"])
    probe("cube_total", ["cube", "query", "--cube", "revenue", "--measures", "valid_revenue_cents", "--sql-only"])
    probe("cube_customer", ["cube", "query", "--cube", "revenue", "--measures", "valid_revenue_cents",
                           "--dimensions", "customer_name", "--sql-only"])
    probe("cube_month", ["cube", "query", "--cube", "revenue", "--measures", "valid_revenue_cents",
                        "--time-dimension", "order_time:month:2025-01-01,2025-05-01", "--sql-only"])
    probe("unknown_cube_dimension", ["cube", "query", "--cube", "revenue", "--measures", "valid_revenue_cents",
                                    "--dimensions", "region", "--sql-only"], expected=1)

    # A non-empty but invalid dimension is deliberately not selected by the measure-only probe.
    mutation = ROOT / "mutation-project"
    shutil.copytree(project, mutation, dirs_exist_ok=True)
    file = mutation / "cubes" / "revenue" / "metadata.yml"
    cube = json.loads(file.read_text(encoding="utf-8"))
    cube["dimensions"][0]["expression"] = "nonexistent_customer_column"
    cube["dimensions"][0]["name"] = "nonexistent_customer_column"
    file.write_text(json.dumps(cube), encoding="utf-8")
    probe("bad_dimension_validate", ["context", "validate", "--strict"], cwd=mutation)
    probe("bad_dimension_build", ["context", "build"], cwd=mutation)
    probe("bad_dimension_measure_only", ["cube", "query", "--cube", "revenue",
          "--measures", "valid_revenue_cents", "--sql-only"], cwd=mutation)
    probe("bad_dimension_selected_sqlonly", ["cube", "query", "--cube", "revenue",
          "--measures", "valid_revenue_cents", "--dimensions", "nonexistent_customer_column", "--sql-only"], expected=1, cwd=mutation)
    probe("bad_dimension_expanded_plan", ["dry-plan", "--sql",
          'SELECT nonexistent_customer_column, SUM(amount_cents) FROM "order" GROUP BY 1', "-d", "mysql"],
          cwd=mutation)

    python_report = {"scope": "Local pandas CSV parsing; not Docker run_python"}
    try:
        import pandas as pd
        frame = pd.read_csv(ROOT / "ds_event.csv")
        typed = pd.read_csv(ROOT / "ds_event.csv", dtype={"code": "string"}, keep_default_na=False)
        python_report.update({"pandas_version": pd.__version__, "rows": len(frame),
                              "default_code": str(frame.iloc[0]["code"]),
                              "explicit_code": str(typed.iloc[0]["code"]),
                              "default_null_and_empty_both_na": bool(frame["note"].isna().all()),
                              "full_sum": int(frame.value_cents.sum()) / 100,
                              "first_1000_sum": int(frame.head(1000).value_cents.sum()) / 100,
                              "first_10000_sum": int(frame.head(10000).value_cents.sum()) / 100})
    except ImportError:
        python_report["status"] = "SKIPPED: pandas not installed"

    result = {"scope": "Installed official Wren CLI compilation/planning only; no database or LLM execution.",
              "wren_executable": args.wren, "python_executable": sys.executable,
              "probes": reports, "python_handoff": python_report,
              "platform_e2e": "NOT_RUN", "official_agent_e2e": "NOT_RUN"}
    (ROOT / "official-results.json").write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps([{"name": r["name"], "exit": r["exit_code"], "expectation_met":r["expectation_met"]} for r in reports], ensure_ascii=False))
    print(json.dumps(python_report, ensure_ascii=False))
    if not all(r["expectation_met"] for r in reports):
        sys.exit(1)


if __name__ == "__main__":
    main()
