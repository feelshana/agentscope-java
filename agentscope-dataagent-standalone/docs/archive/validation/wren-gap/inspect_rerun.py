"""Compact inspection of current sandbox rerun, retaining full SSE elsewhere."""
import json,sys
from pathlib import Path
sys.stdout.reconfigure(encoding='utf-8')
root=Path(__file__).resolve().parent
for file in sorted(root.glob('live-*-sse.jsonl')):
    if 'modeling' in file.name: continue
    events=[json.loads(line) for line in file.read_text(encoding='utf-8').splitlines() if line]
    answer=''.join(str(e.get('data','')) for e in events if e.get('type')=='token')
    print('\nCASE',file.stem,'events',len(events),'done',any(e.get('type')=='done' for e in events))
    print('ANSWER',answer[-2200:])
    for event in events:
        if event.get('type')=='tool_result':
            print('RESULT',event.get('toolName'),str(event.get('toolResult'))[:2400])
        elif event.get('type')=='error':print('ERROR',event)
