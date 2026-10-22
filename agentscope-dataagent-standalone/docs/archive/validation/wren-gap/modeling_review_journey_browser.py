"""Check numbered lists, publication guidance and readable SQL in the isolated service."""
from pathlib import Path
import live_validation as live
source = Path(__file__).with_name('guided_browser_check.py').read_text(encoding='utf-8')
exec(source[:source.index('\ntry:\n    for _ in range(60):')])
OUT = ROOT/'docs/validation/modeling-review-journey'
OUT.mkdir(exist_ok=True)
gid = json.loads((ROOT/'docs/validation/question-first/browser-checks.json').read_text(encoding='utf-8'))['group_id']
live.BASE = 'http://127.0.0.1:8085'
client = live.Client('bob')
checks = {}
try:
    for _ in range(60):
        try: target = http('http://127.0.0.1:9263/json/new?about:blank', 'PUT'); break
        except Exception: time.sleep(.5)
    cdp = CDP(target['webSocketDebuggerUrl'])
    cdp.call('Runtime.enable'); cdp.call('Page.enable')
    cdp.call('Emulation.setDeviceMetricsOverride', {'width':1440,'height':1000,'deviceScaleFactor':1,'mobile':False})
    cdp.call('Page.navigate', {'url': live.BASE+'/login'})
    cdp.wait("document.readyState==='complete'")
    cdp.evaluate("localStorage.setItem('claw_token',"+json.dumps(client.token)+");true")
    cdp.call('Page.navigate', {'url':live.BASE+'/configure/modeling/'+gid+'?asset=questions'})
    cdp.wait("document.querySelectorAll('nav[aria-label=\"分析问题列表\"] li').length===2")
    checks['numbered_list'] = cdp.evaluate("document.querySelector('nav[aria-label=\"分析问题列表\"] li').innerText.startsWith('1.')")
    cdp.evaluate("document.querySelectorAll('nav[aria-label=\"分析问题列表\"] button')[1].click();true")
    cdp.wait("document.querySelectorAll('nav[aria-label=\"分析问题列表\"] button')[1].getAttribute('aria-pressed')==='true'")
    checks['selected_detail_only'] = cdp.evaluate("[...document.querySelectorAll('button')].filter(b=>b.textContent.trim()==='对话完善').length===1")
    cdp.evaluate("[...document.querySelectorAll('summary')].find(s=>s.textContent.includes('查看问题查询 SQL')).click();true")
    cdp.wait("[...document.querySelectorAll('button')].some(b=>b.checkVisibility()&&b.textContent.trim()==='放大阅读')")
    checks['readable_font'] = cdp.evaluate("[...document.querySelectorAll('pre')].some(p=>p.checkVisibility()&&getComputedStyle(p).fontSize==='15px')")
    cdp.click('放大阅读')
    checks['enlarged_dialog'] = cdp.evaluate("!!document.querySelector('dialog[open] code')")
    cdp.screenshot('enlarged-query-sql'); cdp.click('关闭')
    flow = client.request('GET',live.modeling_path(gid)+'/workflow')
    if flow['draftChanged']:
        assert flow['canPublish'], flow
        cdp.click('全部确认完成 → 第四步：发布')
        cdp.wait("new URLSearchParams(location.search).get('asset')==='publish'")
        checks['publication_shortcut'] = True
        cdp.wait("[...document.querySelectorAll('button')].some(b=>b.textContent.trim()==='发布'&&!b.disabled)")
        cdp.click('发布')
        cdp.wait("document.body.innerText.includes('发布成功')",timeout=180)
    cdp.click('3 · 验证与确认')
    cdp.wait("document.querySelectorAll('nav[aria-label=\"分析问题列表\"] li').length===2")
    cdp.wait("document.querySelector('nav[aria-label=\"分析问题列表\"]').innerText.includes('已发布 · 问数可用')")
    checks['published_questions_visible'] = all(r['published']['current'] for r in client.request('GET',live.modeling_path(gid)+'/questions'))
    checks['published_next_step_query'] = cdp.evaluate("document.body.innerText.includes('开始问数')")
    cdp.screenshot('published-question-list')
    events = client.stream('/api/agents/modeling-agent/chat/stream', {'message':'独立界面确认测试：只用 write_file 在 knowledge/rules/ui_confirmation_probe.md 写入正文「测试规则：金额单位为元」，reason 为「测试单次确认写入」，不要修改问题或其他模型，写入后停止。', 'sessionKey':'modeling-'+gid, 'groupIds':[gid]}, 'review-journey-hitl')
    hitl = next(e for e in reversed(events) if e.get('type') == 'hitl_request')
    call = hitl['toolCalls'][0]
    assert call['name'] == 'write_file' and call['input']['path'] == 'knowledge/rules/ui_confirmation_probe.md', call
    cdp.call('Page.navigate', {'url':live.BASE+'/configure/modeling/'+gid+'?asset=modeling'})
    cdp.wait("[...document.querySelectorAll('button')].some(b=>b.textContent.trim()==='采用并写入'&&!b.disabled)",timeout=180)
    checks['write_yaml_font'] = cdp.evaluate("[...document.querySelectorAll('pre')].some(p=>p.checkVisibility()&&getComputedStyle(p).fontSize==='15px')")
    cdp.evaluate("window.confirmCount=0;window.savedFetch=window.fetch;window.fetch=(...args)=>{if(String(args[0]).endsWith('/chat/confirm'))window.confirmCount++;return window.savedFetch(...args)};true")
    cdp.click('采用并写入')
    cdp.wait("(async()=>{const r=await fetch('/api/dataset-groups/"+gid+"/modeling/workspace/file?path=knowledge%2Frules%2Fui_confirmation_probe.md',{headers:{Authorization:'Bearer '+localStorage.getItem('claw_token')}});return r.ok&&(await r.json()).content.includes('金额单位为元')})()",timeout=180)
    checks['single_click_writes'] = cdp.evaluate('window.confirmCount===1')
    checks['same_proposal_not_asked_again'] = not any(t['id'] == call['id'] for t in client.request('GET','/api/agents/modeling-agent/chat/session?'+urllib.parse.urlencode({'sessionKey':'modeling-'+gid})).get('pendingToolCalls', []))
    cdp.screenshot('single-click-written')
    cdp.call('Emulation.setDeviceMetricsOverride', {'width':760,'height':1000,'deviceScaleFactor':1,'mobile':False})
    checks['narrow_no_overflow'] = cdp.evaluate('document.documentElement.scrollWidth<=innerWidth+1')
    checks['no_runtime_exceptions'] = not cdp.errors
    assert all(checks.values()), checks
finally:
    (OUT/'browser-checks.json').write_text(json.dumps({'groupId':gid,'checks':checks},ensure_ascii=False,indent=2),encoding='utf-8')
    try: cdp.call('Browser.close')
    except Exception: browser.terminate()
print(json.dumps(checks),flush=True)
