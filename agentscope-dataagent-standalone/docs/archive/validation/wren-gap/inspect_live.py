"""Read test workspace details and check non-mutating validation gates."""
import hashlib
import json
import sys
from pathlib import Path
import urllib.parse
from live_validation import Client, ROOT, load_state, group_path, modeling_path

sys.stdout.reconfigure(encoding="utf-8")
client=Client("bob")
state=load_state()
group=state["group_id"]
base=modeling_path(group)
preview=client.request("GET",base+"/mdl")
(ROOT/"live-final-preview.json").write_text(json.dumps(preview,ensure_ascii=False,indent=2),encoding="utf-8")
for file in preview.get("files",[]):
    if file.get("path") in ("relationships.yml","models/event/metadata.yml","views/valid_order/sql.yml","views/order_net_revenue/sql.yml"):
        print(file["path"],file.get("content",""))
checks=[]
cases=[
    ("invalid_yaml",{"toolName":"write_file","input":{"path":"cubes/invalid_probe/metadata.yml","content":"name: [invalid","reason":"独立测试非法YAML应被拒绝"}}),
    ("platform_file",{"toolName":"write_file","input":{"path":"wren_project.yml","content":"name: forbidden_probe","reason":"独立测试平台文件应被拒绝"}}),
    ("path_escape",{"toolName":"write_file","input":{"path":"../outside_probe.yml","content":"name: forbidden_probe","reason":"独立测试目录越界应被拒绝"}}),
]
before=hashlib.sha256(json.dumps(preview.get("files"),sort_keys=True).encode()).hexdigest()
for name,body in cases:
    try:
        response=client.request("POST",base+"/workspace/preview",body)
        checks.append({"name":name,"status":"PASS" if response.get("ok") is False else "FAIL","response":response})
    except Exception as error:
        checks.append({"name":name,"status":"PASS" if any(code in str(error) for code in ("HTTP 400","HTTP 403","HTTP 404")) else "ERROR","error":str(error)})
after=client.request("GET",base+"/mdl")
unchanged=before==hashlib.sha256(json.dumps(after.get("files"),sort_keys=True).encode()).hexdigest()
checks.append({"name":"preview_does_not_mutate_workspace","status":"PASS" if unchanged else "FAIL"})
(ROOT/"live-write-gate-results.json").write_text(json.dumps(checks,ensure_ascii=False,indent=2),encoding="utf-8")
print(json.dumps(checks,ensure_ascii=False))
