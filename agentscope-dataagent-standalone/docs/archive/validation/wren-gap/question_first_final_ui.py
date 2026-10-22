"""Check append intake and responsive layout on the final package."""
from pathlib import Path
import live_validation as live
source=Path(__file__).with_name('guided_browser_check.py').read_text(encoding='utf-8')
exec(source[:source.index('\ntry:\n    for _ in range(60):')])
OUT=ROOT/'docs/validation/question-first'
state=json.loads((OUT/'browser-checks.json').read_text(encoding='utf-8'))
gid=state['group_id'];checks=state['checks']
live.BASE='http://127.0.0.1:8085'
for _ in range(60):
    try:http(live.BASE+'/actuator/health');break
    except Exception:time.sleep(1)
client=live.Client('bob')
base=live.modeling_path(gid)
try:
    for _ in range(60):
        try:target=http('http://127.0.0.1:9263/json/new?about:blank','PUT');break
        except Exception:time.sleep(.5)
    cdp=CDP(target['webSocketDebuggerUrl'])
    cdp.call('Runtime.enable');cdp.call('Page.enable')
    cdp.call('Emulation.setDeviceMetricsOverride',{'width':1440,'height':1000,'deviceScaleFactor':1,'mobile':False})
    cdp.call('Page.navigate',{'url':'http://127.0.0.1:8085/login'})
    cdp.wait("document.readyState==='complete'")
    cdp.evaluate("localStorage.setItem('claw_token',"+json.dumps(client.token)+");true")
    cdp.call('Page.navigate',{'url':'http://127.0.0.1:8085/configure/modeling/'+gid+'?asset=modeling'})
    cdp.wait("[...document.querySelectorAll('button')].some(b=>b.checkVisibility()&&b.textContent.trim()==='＋ 添加问题')")
    checks['final_doc_copy_matches_intake']=cdp.evaluate("document.body.innerText.includes('没有文档也可以先提交分析问题')")
    cdp.screenshot('confirmed-question-workbench')
    cdp.click('＋ 添加问题')
    cdp.wait("!!document.querySelector('dialog[open] textarea[aria-label=\"分析问题列表\"]')")
    cdp.evaluate("(()=>{const t=document.querySelector('dialog[open] textarea');Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype,'value').set.call(t,'追加问题一\\n追加问题二');t.dispatchEvent(new Event('input',{bubbles:true}));return true})()")
    cdp.wait("document.querySelector('dialog[open]').innerText.includes('2 个问题')")
    checks['append_uses_multi_question_form']=True
    cdp.click('取消')
    checks['cancel_append_does_not_save']=len(client.request('GET',base+'/questions'))==2
    cdp.call('Emulation.setDeviceMetricsOverride',{'width':760,'height':1000,'deviceScaleFactor':1,'mobile':False})
    checks['narrow_no_horizontal_overflow']=cdp.evaluate('document.documentElement.scrollWidth<=innerWidth+1')
    cdp.screenshot('narrow-workbench')
    checks['final_no_runtime_exceptions']=not cdp.errors
    reviews=client.request('GET',base+'/questions')
    workflow=client.request('GET',base+'/workflow')
    (OUT/'final-reviews.json').write_text(json.dumps(reviews,ensure_ascii=False,indent=2),encoding='utf-8')
    (OUT/'final-workflow.json').write_text(json.dumps(workflow,ensure_ascii=False,indent=2),encoding='utf-8')
    assert all(checks.values()),checks
finally:
    (OUT/'browser-checks.json').write_text(json.dumps({'group_id':gid,'checks':checks},ensure_ascii=False,indent=2),encoding='utf-8')
    try:cdp.call('Browser.close')
    except Exception:browser.terminate()
print(json.dumps(checks),flush=True)
