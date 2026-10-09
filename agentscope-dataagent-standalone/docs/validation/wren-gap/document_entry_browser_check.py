"""Verify visible document-first modeling with actual multipart uploads on the test fixture."""
from pathlib import Path
source=Path(__file__).with_name('guided_browser_check.py').read_text(encoding='utf-8')
exec(source[:source.index('\ntry:\n    for _ in range(60):')])
OUT=ROOT/'docs/validation/document-entry'
OUT.mkdir(parents=True,exist_ok=True)
fixture=OUT/'business-document.txt'
fixture.write_text('业务文档交互验证：有效订单为已支付或完成且未删除的订单，销售额使用人民币元。不扣退款。',encoding='utf-8')
invalid=OUT/'invalid-document.docx'
invalid.write_bytes(b'This is an intentionally invalid DOCX test fixture.')
checks={}
def api(path):
    return cdp.evaluate("(async()=>{const r=await fetch("+json.dumps(path)+",{headers:{Authorization:'Bearer '+localStorage.getItem('claw_token')}});if(!r.ok)throw new Error('API failed');return await r.json()})()")
def upload_file(path):
    root=cdp.call('DOM.getDocument')['root']['nodeId']
    node=cdp.call('DOM.querySelector',{'nodeId':root,'selector':'input[type=file][accept=".docx,.md,.txt"]'})['nodeId']
    assert node
    cdp.call('DOM.setFileInputFiles',{'nodeId':node,'files':[str(path)]})
try:
    for _ in range(60):
        try:
            with urllib.request.urlopen('http://127.0.0.1:8085/actuator/health',timeout=2) as response:
                if response.status==200:break
        except Exception:time.sleep(0.5)
    for _ in range(60):
        try:target=http('http://127.0.0.1:9263/json/new?about:blank','PUT');break
        except Exception:time.sleep(0.5)
    cdp=CDP(target['webSocketDebuggerUrl'])
    cdp.call('Runtime.enable');cdp.call('Page.enable');cdp.call('DOM.enable')
    cdp.call('Emulation.setDeviceMetricsOverride',{'width':1680,'height':1000,'deviceScaleFactor':1,'mobile':False})
    cdp.call('Page.navigate',{'url':'http://127.0.0.1:8085/login'})
    cdp.wait("document.readyState==='complete'")
    cdp.evaluate("""(async()=>{const r=await fetch('/api/auth/login',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({username:'bob',password:'bob'})});if(!r.ok)throw new Error('Login failed');localStorage.setItem('claw_token',(await r.json()).token);return true;})()""")
    cdp.call('Page.navigate',{'url':'http://127.0.0.1:8085/configure/modeling/'+gid+'?asset=modeling'})
    cdp.wait("document.body.innerText.includes('业务文档准备')||!!document.querySelector('[aria-label=\"业务文档准备\"]')")
    cdp.wait("document.body.innerText.includes('本次分析问题')")
    old=api('/api/dataset-groups/'+gid+'/knowledge')['content']
    (OUT/'before-upload.png').write_bytes(base64.b64decode(cdp.call('Page.captureScreenshot',{'format':'png'})['data']))
    checks['document_entry_above_conversation']=cdp.evaluate("""(()=>{const a=document.querySelector('[aria-label="业务文档准备"]').getBoundingClientRect();const t=document.querySelector('.modeling-chat-main textarea').getBoundingClientRect();return a.y<t.y&&a.y<350;})()""")
    checks['model_entry_prominent']=cdp.evaluate("document.body.innerText.includes('查看语义模型')&&document.body.innerText.includes('模型 · Cube · 视图 · MDL')")
    checks['duplicate_composer_upload_removed']=not cdp.evaluate("[...document.querySelectorAll('button')].some(b=>b.textContent.includes('添加业务文档（可选）'))")
    cdp.call('Network.enable')
    cdp.call('Network.emulateNetworkConditions',{'offline':False,'latency':800,'downloadThroughput':1000000,'uploadThroughput':1000000})
    upload_file(fixture)
    cdp.wait("document.body.innerText.includes('正在上传：business-document.txt')")
    checks['upload_progress_and_duplicate_guard']=cdp.evaluate("[...document.querySelectorAll('button')].some(b=>b.textContent.trim()==='正在上传…'&&b.disabled)")
    cdp.screenshot('uploading')
    cdp.wait("document.body.innerText.includes('上传成功：business-document.txt')")
    checks['success_filename_visible']=True
    checks['actual_server_content_saved']='业务文档交互验证' in api('/api/dataset-groups/'+gid+'/knowledge')['content']
    cdp.evaluate("""(()=>{const d=[...document.querySelectorAll('details')].find(d=>d.querySelector('summary')?.textContent==='查看已保存的业务文档');d.open=true;return true;})()""")
    checks['saved_document_preview']=cdp.evaluate("document.body.innerText.includes('业务文档交互验证')")
    cdp.screenshot('upload-success')
    cdp.call('Network.emulateNetworkConditions',{'offline':False,'latency':0,'downloadThroughput':-1,'uploadThroughput':-1})
    cdp.call('Page.reload')
    cdp.wait("document.body.innerText.includes('当前知识库已有业务文档')")
    checks['reload_recovers_saved_document']=True
    upload_file(invalid)
    cdp.wait("document.body.innerText.includes('上传失败：invalid-document.docx')")
    checks['failure_is_explicit']=True
    checks['failed_upload_keeps_saved_content']='业务文档交互验证' in api('/api/dataset-groups/'+gid+'/knowledge')['content']
    cdp.screenshot('upload-failure')
    assert cdp.evaluate("(() => {const b=[...document.querySelectorAll('button')].find(b=>b.textContent.includes('查看语义模型'));if(!b)return false;b.click();return true;})()")
    cdp.wait("new URLSearchParams(location.search).get('asset')==='schema'")
    checks['model_entry_navigation']=True
    cdp.click('← 返回对话建模')
    cdp.wait("document.body.innerText.includes('业务文档已准备')")
    checks['return_preserves_document_feedback']=True
    # Restore the pre-existing document on the isolated test fixture only.
    cdp.evaluate("(async()=>{const f=new FormData();f.append('file',new Blob(["+json.dumps(old)+"],{type:'text/plain'}),'restored-test-document.txt');const r=await fetch('/api/dataset-groups/"+gid+"/knowledge',{method:'PUT',headers:{Authorization:'Bearer '+localStorage.getItem('claw_token')},body:f});if(!r.ok)throw new Error('Restore failed');return true})()")
    checks['no_runtime_exceptions']=not cdp.errors
    (OUT/'browser-checks.json').write_text(json.dumps(checks,ensure_ascii=False,indent=2),encoding='utf-8')
    print(json.dumps(checks),flush=True)
    assert all(checks.values()),checks
finally:
    try:cdp.call('Browser.close')
    except Exception:browser.terminate()
