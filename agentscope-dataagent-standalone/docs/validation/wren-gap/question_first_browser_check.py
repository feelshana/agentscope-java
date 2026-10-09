"""Verify question-first intake, native HITL and real Wren in an isolated fixture."""
from pathlib import Path
import uuid
import csv
import live_validation as live
source=Path(__file__).with_name('guided_browser_check.py').read_text(encoding='utf-8')
exec(source[:source.index('\ntry:\n    for _ in range(60):')])
OUT=ROOT/'docs/validation/question-first'
OUT.mkdir(parents=True,exist_ok=True)
live.BASE='http://127.0.0.1:8085'
client=live.Client('bob')
checks={}
group=client.request('POST','/api/dataset-groups',{'name':'question-first-'+uuid.uuid4().hex[:8],'description':'独立测试：批量问题、统一验收、原生 HITL'})
gid=group['id']
base=live.modeling_path(gid)
(OUT/'state.json').write_text(json.dumps({'group_id':gid}),encoding='utf-8')
client.request('POST','/api/datasets?'+urllib.parse.urlencode({'groupId':gid,'name':'order'}),multipart=[('file',live.ROOT/'ds_order.csv')],timeout=300)
for _ in range(60):
    workflow=client.request('GET',base+'/workflow')
    if workflow['queryAvailable']:break
    time.sleep(1)
assert workflow['queryAvailable'] and workflow['nextAction']['type']=='ADD_QUESTIONS'
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
    cdp.call('Page.navigate',{'url':'http://127.0.0.1:8085/configure/modeling/'+gid})
    cdp.wait("!!document.querySelector('textarea[aria-label=\"分析问题列表\"]')")
    checks['initial_batch_form']=True
    checks['initial_chat_hidden']=cdp.evaluate("!document.querySelector('.modeling-chat-main textarea:not([aria-label])')?.checkVisibility()")
    checks['initial_single_intake']=cdp.evaluate("[...document.querySelectorAll('button')].filter(b=>b.checkVisibility()&&b.textContent.includes('添加问题')).length===0")
    checks['no_classification']=cdp.evaluate("!document.body.innerText.includes('必验') && !document.body.innerText.includes('可选问题')")
    cdp.screenshot('initial-intake')
    cdp.evaluate("(()=>{const original=fetch.bind(window);window.intakeCalls=[];window.fetch=(url,o)=>{if(String(url).includes('/chat/stream'))window.intakeCalls.push(JSON.parse(o.body));return original(url,o)};return true})()")
    question_text='2025年有效订单销售额是多少？口径：order_time在2025自然年，status为PAID或COMPLETED，is_deleted=0，amount_cents除以100，单位元，不扣退款。\n2025年有效订单有多少笔？沿用上述时间和有效订单过滤，不关联客户；请复用一个有效订单视图回答这两个问题。'
    cdp.evaluate("(()=>{const t=document.querySelector('textarea[aria-label=\"分析问题列表\"]');Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype,'value').set.call(t,"+json.dumps(question_text)+");t.dispatchEvent(new Event('input',{bubbles:true}));return true})()")
    cdp.wait("document.body.innerText.includes('2 个问题')")
    cdp.click('提交问题，开始建模')
    cdp.wait("window.intakeCalls.length===1")
    checks['submission_shows_chat']=cdp.evaluate("document.querySelector('.modeling-chat-main textarea:not([aria-label])')?.checkVisibility()")
    checks['single_sse_submission']=cdp.evaluate("window.intakeCalls.length===1 && window.intakeCalls[0].message.includes('销售额') && window.intakeCalls[0].message.includes('多少笔')")
    checks['no_chat_batch_button']=cdp.evaluate("![...document.querySelectorAll('.modeling-chat-main button')].some(b=>b.textContent.includes('批量'))")
    cdp.wait("document.body.innerText.includes('需要确认')",timeout=180)
    cdp.screenshot('native-proposal')
    session=client.request('GET','/api/agents/modeling-agent/chat/session?'+urllib.parse.urlencode({'sessionKey':'modeling-'+gid}))
    assert session['pendingReplyId'] and session['pendingToolCalls']
    session_key=session.get('sessionKey') or 'modeling-'+gid
    cdp.call('Page.reload')
    cdp.wait("document.body.innerText.includes('需要确认')")
    checks['pending_hitl_restores_chat']=True
    cdp.screenshot('pending-restored')
    # Only review and approve writes inside this script's dedicated fixture workspace.
    events=[{'type':'hitl_request','replyId':session['pendingReplyId'],'toolCalls':session['pendingToolCalls']}]
    approved=[]
    for _ in range(16):
        requests=[e for e in events if e.get('type')=='hitl_request']
        if not requests:break
        pending=requests[-1]
        call=pending['toolCalls'][0]
        data=call.get('input',{})
        path=data.get('path','').replace('\\','/')
        assert call['name'] in {'write_file','patch_file','create_view'},call
        assert not data.get('group_id') or data['group_id']==gid,data
        if call['name']=='create_view':
            assert data.get('name') and '/' not in data['name'] and data.get('statement','').lstrip().upper().startswith(('SELECT','WITH')),data
            path='views/'+data['name']
        else:
            assert path.startswith(('knowledge/questions/','views/','cubes/','models/','knowledge/business_rules/')) and '..' not in path,path
            for attempt in range(6):
                preview=client.request('POST',base+'/workspace/preview',{'toolName':call['name'],'input':data})
                if preview['ok'] or 'scratch' not in preview.get('error',''):break
                time.sleep(3)
            assert preview['ok'],preview
        approved.append({'tool':call['name'],'path':path})
        events=client.stream('/api/agents/modeling-agent/chat/confirm',{'sessionKey':session_key,'groupIds':[gid],'replyId':pending['replyId'],'toolCallId':call['id'],'toolName':call['name'],'confirmed':True},'question-first')
        done=[e for e in events if e.get('type')=='done']
        if done and done[-1].get('sessionKey'):session_key=done[-1]['sessionKey']
    assert not any(e.get('type') in {'error','hitl_request'} for e in events),events[-2:]
    reviews=client.request('GET',base+'/questions')
    (OUT/'reviews.json').write_text(json.dumps(reviews,ensure_ascii=False,indent=2),encoding='utf-8')
    (OUT/'approved-writes.json').write_text(json.dumps(approved,ensure_ascii=False,indent=2),encoding='utf-8')
    checks['two_questions_saved_with_sql']=len(reviews)==2 and all(r['question']['sql'] for r in reviews)
    checks['classification_removed_from_api']=all('required' not in r['question'] for r in reviews)
    assets=client.request('GET',base+'/mdl/view')
    checks['question_driven_view_or_cube']=bool(assets.get('views') or assets.get('cubes'))
    assert checks['two_questions_saved_with_sql'],reviews
    # Legacy false fixture proves the same server gates every user question.
    workspace=ROOT/'.tmp-question-acceptance/mdl'/gid/'workspace'
    for p in (workspace/'knowledge/questions').glob('*.yml'):
        content=p.read_text(encoding='utf-8')
        if 'required:' not in content:p.write_text(content+'\nrequired: false\n',encoding='utf-8')
    engineering=client.request('POST',base+'/mdl/validate',timeout=300)
    assert engineering['ok'],engineering
    workflow=client.request('GET',base+'/workflow')
    checks['legacy_false_questions_block_publish']=not workflow['canPublish'] and len([b for b in workflow['blockers'] if b['code'].startswith('QUESTION')])==2
    rows=list(csv.DictReader((live.ROOT/'ds_order.csv').open(encoding='utf-8-sig')))
    valid=[r for r in rows if r['order_time'].startswith('2025-') and r['status'] in {'PAID','COMPLETED'} and r['is_deleted']=='0']
    expected=sorted([sum(int(r['amount_cents']) for r in valid)/100,len(valid)])
    actual=[]
    for i,review in enumerate(reviews):
        qid=review['question']['id']
        executed=client.request('POST',base+'/questions/'+qid+'/validate',{},timeout=300)
        assert executed['status']=='EXECUTED',executed
        result=executed['validation']['result']
        actual.append(float(result['rows'][0][result['columns'][0]]))
        client.request('POST',base+'/questions/'+qid+'/decision',{'validationId':executed['validation']['validationId'],'accepted':True})
        if i==0:checks['one_confirmation_still_blocked']=not client.request('GET',base+'/workflow')['canPublish']
    checks['real_wren_matches_csv_oracle']=sorted(actual)==expected
    checks['all_confirmed_can_publish']=client.request('GET',base+'/workflow')['canPublish']
    cdp.call('Page.navigate',{'url':'http://127.0.0.1:8085/configure/modeling/'+gid+'?asset=modeling'})
    cdp.wait("document.body.innerText.includes('已确认')")
    checks['saved_questions_restore_conversation']=cdp.evaluate("document.querySelector('.modeling-chat-main textarea:not([aria-label])')?.checkVisibility()")
    checks['no_runtime_exceptions']=not cdp.errors
    assert all(checks.values()),checks
finally:
    (OUT/'browser-checks.json').write_text(json.dumps({'group_id':gid,'checks':checks},ensure_ascii=False,indent=2),encoding='utf-8')
    try:cdp.call('Browser.close')
    except Exception:browser.terminate()
print(json.dumps(checks),flush=True)
