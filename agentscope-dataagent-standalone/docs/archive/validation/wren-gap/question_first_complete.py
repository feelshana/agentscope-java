"""Resume only this validation run after browser intake checks."""
import textwrap
import csv
from pathlib import Path
import live_validation as live
source=Path(__file__).with_name('guided_browser_check.py').read_text(encoding='utf-8')
exec(source[:source.index('\ntry:\n    for _ in range(60):')])
OUT=ROOT/'docs/validation/question-first'
state=json.loads((OUT/'browser-checks.json').read_text(encoding='utf-8'))
gid=state['group_id']
checks=state['checks']
live.BASE='http://127.0.0.1:8085'
client=live.Client('bob')
base=live.modeling_path(gid)
session=client.request('GET','/api/agents/modeling-agent/chat/session?'+urllib.parse.urlencode({'sessionKey':'modeling-'+gid}))
session_key=session.get('sessionKey') or 'modeling-'+gid
for _ in range(60):
    try:target=http('http://127.0.0.1:9263/json/new?about:blank','PUT');break
    except Exception:time.sleep(.5)
cdp=CDP(target['webSocketDebuggerUrl'])
cdp.call('Runtime.enable');cdp.call('Page.enable')
cdp.call('Page.navigate',{'url':'http://127.0.0.1:8085/login'})
cdp.wait("document.readyState==='complete'")
cdp.evaluate("localStorage.setItem('claw_token',"+json.dumps(client.token)+");true")
code=Path(__file__).with_name('question_first_browser_check.py').read_text(encoding='utf-8')
reviews=client.request('GET',base+'/questions')
start=code.index('    events=') if session.get('pendingReplyId') else code.index('    # Legacy false fixture')
tail=code[start:code.index('\nfinally:')]
try:
    exec(textwrap.dedent(tail))
finally:
    (OUT/'browser-checks.json').write_text(json.dumps({'group_id':gid,'checks':checks},ensure_ascii=False,indent=2),encoding='utf-8')
    try:cdp.call('Browser.close')
    except Exception:browser.terminate()
print(json.dumps(checks),flush=True)
