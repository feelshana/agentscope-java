"""Verify fixture fidelity and preview protection without mutating workspace."""
import json
import sys
import urllib.parse
from live_validation import Client, ROOT, load_state, modeling_path

sys.stdout.reconfigure(encoding="utf-8")
state=load_state()
client=Client("bob")
group=state["group_id"]
results=[]
event=state["datasets"]["event"]
preview=client.request("GET",f'/api/datasets/{event["id"]}/preview?limit=3')
(ROOT/"live-event-preview.json").write_text(json.dumps(preview,ensure_ascii=False,indent=2),encoding="utf-8")
results.append({"name":"import_code_leading_zeros","expected":"000123","result":preview})
path=modeling_path(group)
files=client.request("GET",path+"/mdl")["files"]
platform=next(file for file in files if file["path"]=="wren_project.yml")
response=client.request("POST",path+"/workspace/preview",{"toolName":"write_file","input":{
    "path":"wren_project.yml","content":platform["content"],"reason":"只读预检：平台文件原文，不应允许写工具修改"}})
results.append({"name":"valid_platform_file_preview","status":"FAIL" if response.get("ok") else "PASS",
                "note":"Preview only; no file was written. Actual write tool protection must be checked separately.",
                "response":response})
overview=client.request("GET",path)
view=client.request("GET",path+"/mdl/view")
results.append({"name":"confirmed_relation_consistency","db_confirmed":[r for r in overview.get("relations",[]) if r.get("status")=="CONFIRMED"],
                "workspace_relations":view.get("relations",[])})
(ROOT/"live-extra-results.json").write_text(json.dumps(results,ensure_ascii=False,indent=2),encoding="utf-8")
print(json.dumps(results,ensure_ascii=False))
