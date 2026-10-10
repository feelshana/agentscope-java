"""Exercise review evidence and confirmation with real Wren on the isolated test server."""
from pathlib import Path
import uuid
source=Path(__file__).with_name('guided_browser_check.py').read_text(encoding='utf-8')
exec(source[:source.index('\ntry:\n    for _ in range(60):')])
OUT=ROOT/'docs/validation/question-review'
OUT.mkdir(parents=True,exist_ok=True)
checks={}
probe='review_probe_'+uuid.uuid4().hex[:8]
question='验收依据与确认流程测试'
view_sql='SELECT COUNT(*) AS total FROM "order" WHERE status = \'PAID\' AND is_deleted = 0'
workspace=ROOT/'.tmp-question-acceptance/mdl'/gid/'workspace'
created=[]
token=None
def api(path,body=None):
    headers={'Content-Type':'application/json'}
    if token:headers['Authorization']='Bearer '+token
    request=urllib.request.Request('http://127.0.0.1:8085'+path,
        data=None if body is None else json.dumps(body).encode(),headers=headers)
    with urllib.request.urlopen(request,timeout=180) as r:return json.load(r)
def fixture(relative,value):
    p=workspace/relative
    assert not p.exists(),p
    p.parent.mkdir(parents=True,exist_ok=True)
    p.write_text(json.dumps(value,ensure_ascii=False),encoding='utf-8')
    created.append(p)
def card():
    return '[...document.querySelectorAll(".da-card")].find(x=>x.querySelector("strong")?.textContent==='+json.dumps(question)+')'
def click_card(label):
    assert cdp.evaluate('(()=>{const c='+card()+';const b=[...c.querySelectorAll("button")].find(b=>b.textContent.trim()==='+json.dumps(label)+');if(!b||b.disabled)return false;b.click();return true})()'),label
try:
    for _ in range(60):
        try:
            api('/actuator/health');break
        except Exception:time.sleep(.5)
    token=api('/api/auth/login',{'username':'bob','password':'bob'})['token']
    before=api('/api/dataset-groups/'+gid+'/modeling/questions')
    fixture('views/'+probe+'/metadata.yml',{'name':probe,'properties':{'description':'有效已支付订单计数，排除软删除'}})
    fixture('views/'+probe+'/sql.yml',{'statement':view_sql})
    fixture('knowledge/questions/'+probe+'.yml',{'question':question,'definition':'已支付且未软删除订单数；单位条','sql':'SELECT total FROM '+probe,'required':False})
    validated=api('/api/dataset-groups/'+gid+'/modeling/questions/'+probe+'/validate',{})
    checks['real_wren_execution']=validated['status']=='EXECUTED' and validated['validation']['result']['rows'][0]['total']>0
    checks['receipt_has_view_definition']=any(v['name']==probe and v['sql']==view_sql for v in validated['validation']['evidence']['views'])
    (OUT/'execution.json').write_text(json.dumps(validated,ensure_ascii=False,indent=2),encoding='utf-8')
    for _ in range(60):
        try:target=http('http://127.0.0.1:9263/json/new?about:blank','PUT');break
        except Exception:time.sleep(.5)
    cdp=CDP(target['webSocketDebuggerUrl'])
    cdp.call('Runtime.enable');cdp.call('Page.enable')
    cdp.call('Emulation.setDeviceMetricsOverride',{'width':1440,'height':1000,'deviceScaleFactor':1,'mobile':False})
    cdp.call('Page.navigate',{'url':'http://127.0.0.1:8085/login'})
    cdp.wait("document.readyState==='complete'")
    cdp.evaluate("localStorage.setItem('claw_token',"+json.dumps(token)+");true")
    url='http://127.0.0.1:8085/configure/modeling/'+gid
    cdp.call('Page.navigate',{'url':url+'?asset=modeling'})
    cdp.wait("document.body.innerText.includes('本次分析问题')")
    cdp.click('＋ 添加问题')
    cdp.wait("!!document.querySelector('dialog[open]')")
    checks['adding_not_saved_label']=cdp.evaluate("document.querySelector('dialog').innerText.includes('尚未保存')")
    values='每月有效营收是多少？\n各地区付费客户数是多少？'
    cdp.evaluate("(()=>{const t=document.querySelector('dialog textarea');Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype,'value').set.call(t,"+json.dumps(values)+");t.dispatchEvent(new Event('input',{bubbles:true}));return true})()")
    cdp.wait("document.querySelector('dialog').innerText.includes('2 个问题')")
    cdp.click('带入对话，继续建模')
    cdp.wait("document.querySelector('.modeling-chat-main textarea').value.includes('各地区付费客户数是多少')")
    checks['requirements_add_prefills_multiple']=True
    checks['prefill_does_not_write']=len(api('/api/dataset-groups/'+gid+'/modeling/questions'))==len(before)+1
    cdp.click('3 · 验证与确认')
    cdp.wait("document.body.innerText.includes('验证已有问题并确认结果')")
    cdp.click('＋ 添加问题')
    cdp.wait("!!document.querySelector('dialog[open]')")
    cdp.click('取消')
    checks['acceptance_has_same_add_entry']=True
    cdp.wait('!!('+card()+')')
    cdp.evaluate('(()=>{const c='+card()+';for(const d of c.querySelectorAll("details")) if(d.querySelector("summary")?.textContent.includes("SQL"))d.open=true;return true})()')
    cdp.wait("document.body.innerText.includes('本次验证时的全部视图定义目录')")
    checks['same_card_shows_view_sql']=cdp.evaluate(card()+'.innerText.includes('+json.dumps(view_sql)+')')
    cdp.screenshot('view-evidence')
    # Controlled UI failure: the following attempt does not reach or change the backend.
    cdp.evaluate("(()=>{const original=fetch.bind(window);window.failReviewOnce=true;window.fetch=async(url,o)=>{if(window.failReviewOnce&&String(url).includes('/"+probe+"/decision')){window.failReviewOnce=false;return new Response(JSON.stringify({message:'测试：确认请求失败'}),{status:409,headers:{'Content-Type':'application/json'}})}return original(url,o)};return true})()")
    click_card('口径与结果正确，确认')
    cdp.wait("document.body.innerText.includes('测试：确认请求失败')")
    checks['failed_confirmation_not_reported_saved']=not cdp.evaluate(card()+'.innerText.includes("已确认并保存")')
    click_card('口径与结果正确，确认')
    cdp.wait(card()+'?.innerText.includes("已确认并保存")')
    checks['confirmation_button_removed']=cdp.evaluate('![...('+card()+').querySelectorAll("button")].some(b=>b.textContent.includes("口径与结果正确"))')
    checks['confirmation_has_person_and_time']=cdp.evaluate(card()+'.innerText.includes("确认人：bob") && '+card()+'.innerText.includes("确认时间：")')
    cdp.wait("document.body.innerText.includes('下一步：')")
    checks['next_step_visible']=True
    cdp.screenshot('confirmed-next-step')
    cdp.call('Page.reload')
    cdp.wait(card()+'?.innerText.includes("已确认并保存")')
    checks['confirmation_survives_refresh']=True
    sqlfile=workspace/'views'/probe/'sql.yml'
    sqlfile.write_text(json.dumps({'statement':view_sql+' AND amount_cents >= 0'}),encoding='utf-8')
    cdp.call('Page.reload')
    cdp.wait(card()+'?.innerText.includes("已过期，需重验")')
    cdp.evaluate('(()=>{const c='+card()+';for(const d of c.querySelectorAll("details")) if(d.querySelector("summary")?.textContent.includes("SQL"))d.open=true;return true})()')
    checks['stale_keeps_old_definition']=cdp.evaluate(card()+'.innerText.includes('+json.dumps(view_sql)+') && !'+card()+'.innerText.includes("AND amount_cents >= 0")')
    checks['stale_cannot_confirm']=cdp.evaluate('[...('+card()+').querySelectorAll("button")].find(b=>b.textContent.includes("口径与结果正确")).disabled')
    cdp.screenshot('stale-original-evidence')
    cdp.call('Emulation.setDeviceMetricsOverride',{'width':760,'height':1000,'deviceScaleFactor':1,'mobile':False})
    checks['narrow_no_horizontal_overflow']=cdp.evaluate('document.documentElement.scrollWidth<=innerWidth+1')
    cdp.screenshot('narrow-review')
    checks['no_runtime_exceptions']=not cdp.errors
    assert all(checks.values()),checks
finally:
    # Remove only this run's uniquely named fixtures from the isolated acceptance workspace.
    for path in created:
        path.unlink(missing_ok=True)
    for path in [workspace/('.platform/questions/'+probe+'.json'),workspace/('knowledge/sql/'+probe+'.md')]:
        path.unlink(missing_ok=True)
    d=workspace/'views'/probe
    if d.exists() and not any(d.iterdir()):d.rmdir()
    (OUT/'browser-checks.json').write_text(json.dumps({'group_id':gid,'question_id':probe,'checks':checks},ensure_ascii=False,indent=2),encoding='utf-8')
    try:cdp.call('Browser.close')
    except Exception:browser.terminate()
print(json.dumps(checks),flush=True)
