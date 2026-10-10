"""Verify artifact download through authenticated platform API."""
import json,re,urllib.parse,hashlib,csv,io,sys
from live_validation import Client,ROOT
sys.stdout.reconfigure(encoding='utf-8')
client=Client('bob')
events=[json.loads(line) for line in (ROOT/'live-python_sequential-sse.jsonl').read_text(encoding='utf-8').splitlines()]
answer=''.join(str(e.get('data','')) for e in events if e.get('type')=='token')
match=re.search(r'\]\((/workspace/runpython/[^)]+\.png)\)',answer)
if not match:raise RuntimeError('No actual image artifact reference')
png_path=match.group(1)
paths=[png_path,png_path.removesuffix('.png')+'_data.csv']
output=ROOT/'artifacts';output.mkdir(exist_ok=True)
results=[]
for path in paths:
    try:
        query=urllib.parse.urlencode({'path':path})
        with client.open('GET','/api/agents/data-agent/workspace/file/binary?'+query) as response:
            data=response.read();content_type=response.headers.get('Content-Type')
        target=output/path.rsplit('/',1)[-1];target.write_bytes(data)
        entry={'path':path,'local_file':str(target.relative_to(ROOT)),'http_status':200,'bytes':len(data),'content_type':content_type,'sha256':hashlib.sha256(data).hexdigest()}
        if path.endswith('.csv'):
            rows=list(csv.DictReader(io.StringIO(data.decode('utf-8-sig'))));entry['rows']=rows
            entry['values_match']= [float(row['sales_yuan']) for row in rows]==[300,700,0,50]
            entry['april_mom_blank']=rows[-1].get('环比')==''
        else:entry['png_signature_valid']=data.startswith(b'\x89PNG\r\n\x1a\n')
        results.append(entry)
    except Exception as error:results.append({'path':path,'error':str(error)})
(ROOT/'sandbox-artifact-results.json').write_text(json.dumps(results,ensure_ascii=False,indent=2),encoding='utf-8')
print(json.dumps(results,ensure_ascii=False,indent=2))
