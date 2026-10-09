"""Save reviewed business-result judgments, distinct from transport completion."""
import json
from pathlib import Path
root=Path(__file__).resolve().parent
evidence=json.loads((root/'sandbox-rerun-evidence.json').read_text(encoding='utf-8'))
review={
'Q01':('PASS','Actual Cube result 1050 yuan.'),
'Q02':('PASS','Actual Cube result 6 orders, 2 customers.'),
'Q03':('PASS','Actual grouped SQL: Li Si 700, Zhang San 350.'),
'Q04':('PASS_WITH_REPAIR','Final SQL returns sales 300,700,0,50 and net 230,670,0,50; corrected assumed year and month join.'),
'Q05':('AMBIGUOUS_METRIC_MISMATCH','Oracle expects sales 700; agent chose net revenue 670 and disclosed interpretation. Prompt says effective revenue without defining gross/net.'),
'Q06':('PASS','Six actual order rows total net revenue 950.'),
'Q07':('PASS','Actual anti-join returns customer 3 with no valid orders.'),
'Q08':('PASS','Actual duplicate key P2 has two rows.'),
'Q09':('PASS','Actual aggregate compares correct 1050 and amplified 1950.'),
'Q10':('PASS','Actual full-table SQL returns 10005 events and 100.05 yuan.'),
'Q11':('PASS_WITH_REPAIR','Final qualified SQL returns 1 null plus 1 missing customer, total 2.'),
'Q12':('PASS','Actual SQL average 175 yuan, 6 orders, total 1050.')}
results=[]
for identifier,(status,note) in review.items():
    entry=next(e for e in evidence if e['id']==identifier)
    results.append({'id':identifier,'status':status,'note':note,'done':entry['done'],'errors':entry['errors'],'tool_calls':sum(t['input'] is not None for t in entry['tools'])})
output={'scope':'Single local platform agent run on published v8; reviewed tool results and final answers. Not a controlled official-agent comparison.','completed':12,'matches_original_oracle':11,'metric_ambiguities':1,'cases':results}
(root/'sandbox-rerun-judgments.json').write_text(json.dumps(output,ensure_ascii=False,indent=2),encoding='utf-8')
print('Reviewed 12 completed questions: 11 oracle matches, 1 metric ambiguity.')
