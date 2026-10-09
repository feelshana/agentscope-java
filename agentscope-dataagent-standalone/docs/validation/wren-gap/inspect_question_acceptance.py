"""Print fixture-only evidence; never print service configuration or credentials."""
import csv
import json
from pathlib import Path
root = Path(__file__).resolve().parents[3]
out = Path(__file__).resolve().parent / "question-acceptance"
state = json.loads((out / "state.json").read_text(encoding="utf-8"))
gid = state["group_id"]
receipt = root / ".tmp-question-acceptance/mdl" / gid / "workspace/.platform/questions/total_sales.json"
if receipt.exists():
    r = json.loads(receipt.read_text(encoding="utf-8"))
    print(json.dumps({"status": r["status"], "result": r["result"]}, ensure_ascii=False))
rows = list(csv.DictReader((out.parent / "ds_order.csv").open(encoding="utf-8-sig")))
expected = sum(int(r["amount_cents"]) for r in rows if r["order_time"].startswith("2025-")
    and r["status"] in {"PAID", "COMPLETED"} and r["is_deleted"] == "0") / 100
print(json.dumps({"fixture_oracle_yuan": expected}))
