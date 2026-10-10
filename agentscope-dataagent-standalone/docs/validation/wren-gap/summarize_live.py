"""Summarize local validation evidence without exposing credentials."""
import json
import sys
from pathlib import Path

root=Path(__file__).resolve().parent
sys.stdout.reconfigure(encoding="utf-8")
for file in sorted(root.glob("live-*-sse.jsonl")):
    records=[json.loads(line) for line in file.read_text(encoding="utf-8").splitlines() if line.strip()]
    tokens="".join(str(e.get("data","")) for e in records if e.get("type")=="token")
    tools=[{"name": e.get("toolName"), "input": e.get("toolInput"),
            "result": str(e.get("toolResult",""))[:6000]}
           for e in records if e.get("type") in ("tool_call","tool_result")]
    summary={"file":file.name,"event_count":len(records),"answer":tokens,"tools":tools,
             "errors":[e for e in records if e.get("type")=="error"],
             "done":[e for e in records if e.get("type")=="done"]}
    (root/(file.stem+"-summary.json")).write_text(json.dumps(summary,ensure_ascii=False,indent=2),encoding="utf-8")
    print(file.name,"events",len(records),"tools",[(e.get("toolName"),e.get("type")) for e in records if e.get("type") in ("tool_call","tool_result")])
    print("answer:",tokens[-2000:])
    for e in records:
        if e.get("type") in ("error","hitl_request"):
            print(json.dumps(e,ensure_ascii=False)[:1500])
