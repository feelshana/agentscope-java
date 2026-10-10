"""Read-only query checks on the platform's actual published Wren/MySQL snapshot.

This bypasses AgentScope conversation and Docker, not Wren or the published MDL.
"""
import json
import base64
import os
from pathlib import Path
import subprocess
from decimal import Decimal
from live_validation import ROOT, load_state

state=load_state()
home=Path.home()/".agentscope"/"dataagent"/"mdl"/".wren"
project=home.parent/state["group_id"]/"published"
wren=Path("D:/workspace/wren-probe/.venv/Scripts/wren.exe")
marker=project/"wren-source.properties"
profile="dataagent"
if marker.exists():
    for line in marker.read_text(encoding="utf-8").splitlines():
        if line.startswith("profile="):
            profile=line.split("=",1)[1].strip()
env=dict(os.environ,PYTHONUTF8="1",PYTHONIOENCODING="utf-8",WREN_HOME=str(home))
os.environ["WREN_HOME"] = str(home)
from wren.engine import WrenEngine
from wren.profile import list_profiles, expand_profile_secrets
from wren.config import load_config
connection = dict(list_profiles()[profile])
datasource = connection.pop("datasource")
connection = expand_profile_secrets(connection)
manifest = base64.b64encode((project / "target" / "mdl.json").read_bytes()).decode()
engine = WrenEngine(manifest, datasource, connection, config=load_config(home))
cases=[
    ("effective_revenue", 'SELECT SUM(amount_cents)/100.0 AS revenue_yuan FROM valid_order', [[1050.0]]),
    ("orders_and_customers", 'SELECT COUNT(*) AS order_count,COUNT(DISTINCT customer_id) AS paying_customers FROM valid_order', [[6,2]]),
    ("customer_revenue", 'SELECT customer_name,SUM(amount_cents)/100.0 AS revenue_yuan FROM valid_order GROUP BY customer_id,customer_name ORDER BY revenue_yuan DESC', [["李四",700.0],["张三",350.0]]),
    ("net_revenue", 'SELECT SUM(net_cents)/100.0 AS net_revenue_yuan FROM order_net_revenue', [[950.0]]),
    ("events_full_aggregate", 'SELECT COUNT(*) AS event_count,SUM(value_cents)/100.0 AS event_value_yuan FROM event', [[10005,100.05]]),
    ("orphan_customers", 'SELECT COUNT(*) AS orphan_count FROM \"order\" o LEFT JOIN customer c ON o.customer_id=c.customer_id WHERE c.customer_id IS NULL', [[2]]),
    ("duplicate_product_code", 'SELECT product_code,COUNT(*) AS duplicate_count FROM product GROUP BY product_code HAVING COUNT(*)>1', [["P2",2]]),
]
results=[]
for identifier,sql,expected in cases:
    try:
        table = engine.query(sql)
        actual = [list(row.values()) for row in table.to_pylist()]
        def equivalent(left, right):
            if isinstance(left, (int, float, Decimal)) and isinstance(right, (int, float, Decimal)):
                return abs(Decimal(str(left)) - Decimal(str(right))) <= Decimal('0.00000001')
            return left == right
        passed = len(actual) == len(expected) and all(
            len(row) == len(reference) and all(equivalent(a, b) for a, b in zip(row, reference))
            for row, reference in zip(actual, expected)
        )
        record={"id":identifier,"sql":sql,"expected":expected,"actual":actual,
                "status":"PASS" if passed else "MISMATCH"}
    except Exception as error:
        record={"id":identifier,"sql":sql,"expected":expected,"status":"ERROR","error":str(error)}
    results.append(record)
    (ROOT/"live-published-wren-results.json").write_text(json.dumps({"scope":"Actual platform published snapshot, direct Wren SDK with MySQL; not chat E2E.","group_id":state["group_id"],"version":state.get("published_version"),"cases":results},ensure_ascii=False,indent=2,default=str),encoding="utf-8")
    print(identifier,record["status"],flush=True)
engine.close()
