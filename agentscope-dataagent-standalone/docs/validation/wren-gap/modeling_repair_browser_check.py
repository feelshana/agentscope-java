"""Verify compact layout and a controlled failed proposal with a real native HITL resume."""
from pathlib import Path
import uuid
source=Path(__file__).with_name('guided_browser_check.py').read_text(encoding='utf-8')
exec(source[:source.index('\ntry:\n    for _ in range(60):')])
OUT=ROOT/'docs/validation/modeling-repair'
OUT.mkdir(parents=True,exist_ok=True)
checks={}
session='repair-probe-'+uuid.uuid4().hex[:10]
token=None
def api(path, body=None, raw=False):
    headers={'Content-Type':'application/json'}
    if token: headers['Authorization']='Bearer '+token
    request=urllib.request.Request('http://127.0.0.1:8085'+path,
        data=None if body is None else json.dumps(body).encode(),headers=headers)
    with urllib.request.urlopen(request,timeout=180) as response:
        data=response.read().decode()
        return data if raw else json.loads(data)
try:
    for _ in range(60):
        try:
            http('http://127.0.0.1:8085/actuator/health')
            break
        except Exception: time.sleep(.5)
    token=api('/api/auth/login',{'username':'bob','password':'bob'})['token']
    before=api('/api/dataset-groups/'+gid+'/modeling/questions')
    mdl=api('/api/dataset-groups/'+gid+'/modeling/mdl/view')
    name=next((m['modelName'] for m in mdl['models'] if 'order' in m['modelName'].lower()),mdl['models'][0]['modelName'])
    question_id='repair_probe_'+uuid.uuid4().hex[:6]
    prompt=('新增一个可选测试问题：原始订单表共有多少条记录？口径已确认：统计 '+name+
        ' 全表记录数，不增加过滤。请只用 write_file 提交 knowledge/questions/'+question_id+
        '.yml 的完整提案，SQL 用 SELECT COUNT(*) AS total FROM "'+name+
        '"，required=false。不要改现有模型、问题或创建视图，不要发布。')
    response=api('/api/agents/modeling-agent/chat/stream',
        {'message':prompt,'sessionKey':session,'groupIds':[gid]},raw=True)
    (OUT/'initial-stream.txt').write_text(response,encoding='utf-8')
    pending=api('/api/agents/modeling-agent/chat/session?sessionKey='+session)
    call=next(c for c in pending['pendingToolCalls'] if c['name']=='write_file' and 'knowledge/questions/' in c['input']['path'])
    bad_content='question: 原始订单表共有多少条记录\ndefinition: 全表记录计数\nsql: SELECT COUNT(*) AS total FROM "'+name+'" LIMIT 1\nrequired: false\n'
    bad_preview=api('/api/dataset-groups/'+gid+'/modeling/workspace/preview',
        {'toolName':'write_file','input':dict(call['input'],content=bad_content)})
    checks['real_preview_rejects_limit']=not bad_preview['ok'] and 'LIMIT/OFFSET' in bad_preview['error']
    (OUT/'failed-preview.json').write_text(json.dumps(bad_preview,ensure_ascii=False,indent=2),encoding='utf-8')
    for _ in range(60):
        try:target=http('http://127.0.0.1:9263/json/new?about:blank','PUT');break
        except Exception:time.sleep(.5)
    cdp=CDP(target['webSocketDebuggerUrl'])
    cdp.call('Runtime.enable');cdp.call('Page.enable')
    cdp.call('Emulation.setDeviceMetricsOverride',{'width':1440,'height':900,'deviceScaleFactor':1,'mobile':False})
    cdp.call('Page.navigate',{'url':'http://127.0.0.1:8085/login'})
    cdp.wait("document.readyState==='complete'")
    cdp.evaluate("localStorage.setItem('claw_token',"+json.dumps(token)+");true")
    # Only substitute the displayed draft. The session, tool ID, preview and denial API are real.
    injected="""(() => {
      const original=window.fetch.bind(window);
      window.fetch=async (url,options) => {
        const parsed=new URL(url,location.origin);
        if(parsed.pathname==='/api/agents/modeling-agent/chat/session'){
          parsed.searchParams.set('sessionKey',SESSION);
          const r=await original(parsed,options);const value=await r.json();
          for(const c of value.pendingToolCalls||[]) if(c.id===CALL) c.input.content=CONTENT;
          return new Response(JSON.stringify(value),{status:r.status,headers:{'Content-Type':'application/json'}});
        }
        if(parsed.pathname==='/api/agents/modeling-agent/chat/confirm'){
          options={...options,body:JSON.stringify({...JSON.parse(options.body),sessionKey:SESSION})};
          window.repairRequest=JSON.parse(options.body);
          const r=await original(url,options);
          r.clone().text().then(text=>{window.repairResponse=text;});
          return r;
        }
        return original(url,options);
      };
    })()"""
    injected=injected.replace('SESSION',json.dumps(session)).replace('CALL',json.dumps(call['id'])).replace('CONTENT',json.dumps(bad_content))
    cdp.call('Page.addScriptToEvaluateOnNewDocument',{'source':injected})
    cdp.call('Page.navigate',{'url':'http://127.0.0.1:8085/configure/modeling/'+gid+'?asset=modeling'})
    cdp.wait("document.body.innerText.includes('让助手修正')",timeout=120)
    measurements=cdp.evaluate("""(()=>{const d=document.querySelector('[aria-label="业务文档准备"]').getBoundingClientRect();const c=document.querySelector('.modeling-chat-main').getBoundingClientRect();const p=document.querySelector('.modeling-progress-column').getBoundingClientRect();return {documentHeight:d.height,chatWidth:c.width,sidebarWidth:p.width,chatHeight:c.height}})()""")
    checks['document_strip_under_90px']=measurements['documentHeight']<=90
    checks['conversation_wider_than_progress']=measurements['chatWidth']>measurements['sidebarWidth']*2
    checks['adopt_disabled_on_failed_preview']=cdp.evaluate("[...document.querySelectorAll('button')].some(b=>b.textContent.trim()==='采用并写入'&&b.disabled)")
    cdp.screenshot('failed-proposal-desktop')
    cdp.click('查看业务文档')
    cdp.wait("!!document.querySelector('dialog[open]')")
    checks['document_dialog_no_layout_shift']=cdp.evaluate("document.querySelector('[aria-label=\"业务文档准备\"]').getBoundingClientRect().height")==measurements['documentHeight']
    cdp.screenshot('document-dialog')
    cdp.click('关闭文档')
    checks['document_dialog_closes']=cdp.evaluate("!document.querySelector('dialog[open]')")
    cdp.click('让助手修正')
    cdp.wait("!!window.repairResponse",timeout=180)
    request=cdp.evaluate('window.repairRequest')
    native_response=cdp.evaluate('window.repairResponse')
    checks['repair_denies_real_call_with_error']=request['confirmed']==False and request['toolCallId']==call['id'] and 'LIMIT/OFFSET' in request['feedback']
    (OUT/'repair-request.json').write_text(json.dumps(request,ensure_ascii=False,indent=2),encoding='utf-8')
    (OUT/'repair-stream.txt').write_text(native_response,encoding='utf-8')
    revised=api('/api/agents/modeling-agent/chat/session?sessionKey='+session)
    new_call=next(c for c in revised['pendingToolCalls'] if c['name']=='write_file')
    checks['native_resume_proposes_new_call']=new_call['id']!=call['id'] and 'LIMIT 1' not in new_call['input'].get('content','')
    valid_preview=api('/api/dataset-groups/'+gid+'/modeling/workspace/preview',{'toolName':new_call['name'],'input':new_call['input']})
    checks['repaired_proposal_passes_real_preview']=valid_preview['ok']
    checks['no_question_write_without_approval']=api('/api/dataset-groups/'+gid+'/modeling/questions')==before
    (OUT/'repaired-preview.json').write_text(json.dumps(valid_preview,ensure_ascii=False,indent=2),encoding='utf-8')
    (OUT/'browser-checks.json').write_text(json.dumps({'session':session,'group_id':gid,'measurements':measurements,'checks':checks},ensure_ascii=False,indent=2),encoding='utf-8')
    assert checks['native_resume_proposes_new_call'], 'Native rejection did not produce a new proposal'
    cdp.wait("document.body.innerText.includes('预检通过')",timeout=120)
    cdp.screenshot('repaired-proposal-desktop')
    cdp.call('Emulation.setDeviceMetricsOverride',{'width':760,'height':1000,'deviceScaleFactor':1,'mobile':False})
    checks['narrow_no_horizontal_overflow']=cdp.evaluate('document.documentElement.scrollWidth<=innerWidth+1')
    cdp.screenshot('narrow-screen')
    checks['no_runtime_exceptions']=not cdp.errors
    (OUT/'browser-checks.json').write_text(json.dumps({'session':session,'group_id':gid,'measurements':measurements,'checks':checks},ensure_ascii=False,indent=2),encoding='utf-8')
    print(json.dumps(checks),flush=True)
    assert all(checks.values()),checks
finally:
    try:cdp.call('Browser.close')
    except Exception:browser.terminate()
