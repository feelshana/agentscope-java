"""Check the conversation-first workbench against the isolated acceptance service."""
from pathlib import Path
source = Path(__file__).with_name('guided_browser_check.py').read_text(encoding='utf-8')
exec(source[:source.index('\ntry:\n    for _ in range(60):')])
OUT = ROOT / 'docs/validation/conversation-workbench'
OUT.mkdir(parents=True, exist_ok=True)
checks = {}
try:
    for _ in range(60):
        try:
            with urllib.request.urlopen('http://127.0.0.1:8085/actuator/health', timeout=2) as response:
                if response.status == 200:
                    break
        except Exception:
            time.sleep(0.5)
    else:
        raise RuntimeError('Acceptance service did not become ready')
    for _ in range(60):
        try:
            target = http("http://127.0.0.1:9263/json/new?about:blank", "PUT")
            break
        except Exception:
            time.sleep(0.5)
    else:
        raise RuntimeError("Headless Edge did not start")
    cdp = CDP(target["webSocketDebuggerUrl"])
    cdp.call("Runtime.enable")
    cdp.call("Page.enable")
    cdp.call("Emulation.setDeviceMetricsOverride", {"width": 1680, "height": 1000, "deviceScaleFactor": 1, "mobile": False})
    cdp.call("Page.navigate", {"url": "http://127.0.0.1:8085/login"})
    cdp.wait("document.readyState === 'complete'")
    cdp.evaluate("""(async () => {
        const r = await fetch('/api/auth/login', {method:'POST',headers:{'Content-Type':'application/json'},
          body:JSON.stringify({username:'bob',password:'bob'})});
        if(!r.ok) throw new Error('Login failed');
        localStorage.setItem('claw_token',(await r.json()).token);return true;
    })()""")
    cdp.call("Page.navigate", {"url": "http://127.0.0.1:8085/configure/modeling/" + gid})
    cdp.wait("document.body.innerText.includes('批量添加问题')")
    cdp.wait("document.body.innerText.includes('本次分析问题')")
    checks['default_conversation_stage'] = cdp.evaluate("new URLSearchParams(location.search).get('asset')==='modeling'")
    checks['chat_is_main_area'] = cdp.evaluate("""(() => {
        const a=document.querySelector('.modeling-chat-main').getBoundingClientRect();
        const b=document.querySelector('.modeling-progress-column').getBoundingClientRect();
        return a.x<b.x&&a.width>b.width;
    })()""")
    checks['old_quick_actions_removed'] = not cdp.evaluate("""['梳理常用问题','明确业务口径','检查是否可发布'].some(t=>[...document.querySelectorAll('button')].some(b=>b.textContent.trim()===t))""")
    cdp.screenshot('conversation-main')
    before = cdp.evaluate("(async()=>{const r=await fetch('/api/dataset-groups/" + gid + "/modeling/questions',{headers:{Authorization:'Bearer '+localStorage.getItem('claw_token')}});return (await r.json()).length})()")
    cdp.click('批量添加问题')
    cdp.wait("!!document.querySelector('[aria-label=\"预设问题列表\"]')")
    cdp.evaluate("""(() => {
        const t=document.querySelector('[aria-label="预设问题列表"]');
        Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype,'value').set.call(t,'每月有效营收是多少？\\n各地区付费客户数是多少？\\n营收环比增长多少？');
        t.dispatchEvent(new Event('input',{bubbles:true}));return true;
    })()""")
    cdp.screenshot('batch-question-dialog')
    cdp.click('带入对话')
    cdp.wait("document.querySelector('.modeling-chat-main textarea').value.includes('营收环比')")
    checks['multiple_questions_prefilled_not_sent'] = cdp.evaluate("document.querySelector('.modeling-chat-main textarea').value.split('\\n').length>=4 && !document.body.innerText.includes('建模助手思考中')")
    after = cdp.evaluate("(async()=>{const r=await fetch('/api/dataset-groups/" + gid + "/modeling/questions',{headers:{Authorization:'Bearer '+localStorage.getItem('claw_token')}});return (await r.json()).length})()")
    checks['prefill_does_not_write_questions'] = before == after
    cdp.click('查看模型')
    cdp.wait("!!document.querySelector('select[aria-label=\"模型详情\"]')")
    checks['model_assets_reachable'] = cdp.evaluate("""(() => {const values=[...document.querySelector('select[aria-label="模型详情"]').options].map(o=>o.value);return ['schema','derived','cubes','views','glossary','mdl'].every(v=>values.includes(v));})()""")
    for asset in ['cubes','views','mdl']:
        cdp.evaluate("(() => { const s=document.querySelector('select[aria-label=\"模型详情\"]');s.value="+json.dumps(asset)+";s.dispatchEvent(new Event('change',{bubbles:true}));return true; })()")
        cdp.wait("new URLSearchParams(location.search).get('asset')===" + json.dumps(asset))
    cdp.wait("document.body.innerText.includes('工程定义与发布快照')")
    cdp.wait("document.querySelector('select[aria-label=\"工程文件\"]').options.length>1")
    cdp.evaluate("(() => {const s=document.querySelector('select[aria-label=\"工程版本\"]');s.value='published';s.dispatchEvent(new Event('change',{bubbles:true}));return true;})()")
    cdp.wait("document.querySelector('select[aria-label=\"工程版本\"]').value==='published'")
    checks['draft_and_published_snapshots'] = cdp.evaluate("document.body.innerText.includes('当前工作区草稿')&&document.querySelector('select[aria-label=\"工程文件\"]').options.length>1")
    cdp.evaluate("new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(() => resolve(true))))")
    cdp.screenshot('model-detail')
    cdp.click('← 返回对话建模')
    cdp.wait("document.body.innerText.includes('批量添加问题')")
    checks['draft_preserved_after_model_browsing'] = cdp.evaluate("document.querySelector('.modeling-chat-main textarea').value.includes('营收环比')")
    cdp.click('批量添加问题')
    cdp.click('取消')
    checks['cancel_closes_batch_dialog'] = cdp.evaluate("!document.querySelector('[role=dialog]')")
    cdp.call("Emulation.setDeviceMetricsOverride", {"width": 760, "height": 1000, "deviceScaleFactor": 1, "mobile": False})
    checks['narrow_screen_stacks_chat_before_progress'] = cdp.evaluate("""(() => {const a=document.querySelector('.modeling-chat-main').getBoundingClientRect();const b=document.querySelector('.modeling-progress-column').getBoundingClientRect();return a.y<b.y&&a.width>650;})()""")
    cdp.screenshot('narrow-screen')
    checks['no_runtime_exceptions'] = not cdp.errors
    (OUT/'browser-checks.json').write_text(json.dumps(checks,ensure_ascii=False,indent=2),encoding='utf-8')
    print(json.dumps(checks),flush=True)
    assert all(checks.values()),checks
finally:
    try: cdp.call("Browser.close")
    except Exception: browser.terminate()
