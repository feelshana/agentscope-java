"""Collect current rerun evidence; completion is not correctness."""
import json,sys
from pathlib import Path
sys.stdout.reconfigure(encoding='utf-8')
root=Path(__file__).resolve().parent
cases=[]
for file in sorted(root.glob('live-*-sse.jsonl')):
    if 'modeling' in file.name:continue
    events=[json.loads(line) for line in file.read_text(encoding='utf-8').splitlines() if line]
    entry={'id':file.stem.removeprefix('live-').removesuffix('-sse'),'done':any(e.get('type')=='done' for e in events),'errors':[e.get('error') for e in events if e.get('type')=='error'],'answer':''.join(str(e.get('data','')) for e in events if e.get('type')=='token'),'tools':[{'name':e.get('toolName'),'input':e.get('toolInput'),'result':e.get('toolResult')} for e in events if e.get('type') in ('tool_call','tool_result')]}
    cases.append(entry)
    print(entry['id'],'DONE',entry['done'],'ERRORS',entry['errors'])
    if entry['id'] not in ('Q01','Q02','Q03','Q04','Q05','Q06','python'):print(entry['answer'][-2600:])
(root/'sandbox-rerun-evidence.json').write_text(json.dumps(cases,ensure_ascii=False,indent=2),encoding='utf-8')
