"""Launch the built application with isolated metadata/workspaces and probe startup only."""
import json
import os
import re
from pathlib import Path
import socket
import subprocess
import time
import urllib.request

base = Path.cwd()
root = base / 'target/platform-integration-service'
root.mkdir(parents=True, exist_ok=True)
with socket.socket() as sock:
    sock.bind(('127.0.0.1', 0))
    port = sock.getsockname()[1]
settings = subprocess.run(['java', '-XshowSettings:properties', '-version'], capture_output=True, text=True)
java_home = re.search(r'(?m)^\s*java.home = (.+)$', settings.stderr).group(1).strip()
java_executable = str(Path(java_home) / 'bin' / ('java.exe' if os.name == 'nt' else 'java'))
command = [java_executable, '-Duser.home='+str(root/'home'), '-jar', str(base/'target/agentscope-dataagent-2.0.3-SNAPSHOT-exec.jar'),
           '--dataagent.sandbox.instance-id=platform-integration-isolated', '--server.address=127.0.0.1', '--server.port='+str(port), '--spring.datasource.url=jdbc:h2:mem:platform_integration;DB_CLOSE_DELAY=-1',
           '--spring.datasource.driver-class-name=org.h2.Driver', '--spring.datasource.username=sa', '--spring.datasource.password=', '--spring.sql.init.platform=h2',
           '--dataagent.workspace='+str(root/'workspace'), '--dataagent.shared-root='+str(root/'shared'),
           '--dataagent.artifacts.directory='+str(root/'artifacts'), '--dataagent.wren.mdl-home='+str(root/'mdl'),
           '--dataagent.wren.executable='+os.environ.get('WREN_TEST_EXECUTABLE', 'wren'),
           '--dataagent.seed-demo-users=true', '--dataagent.jwt.secret=isolated-platform-integration-test-key-2026', '--dataagent.history-retention.enabled=false', '--dataagent.session.redis.enabled=false', '--dataagent.marketplace.enabled=false']
result = {'port': port, 'loadedBuiltJar': True, 'isolatedMetadata': True, 'health': False, 'frontend': False}
with (root/'service.log').open('w', encoding='utf-8') as log:
    process = subprocess.Popen(command, env={**os.environ, 'PYTHONUTF8':'1', 'PYTHONIOENCODING':'utf-8'}, stdin=subprocess.DEVNULL, stdout=log, stderr=subprocess.STDOUT, creationflags=subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0)
    result['pid'] = process.pid
    try:
        for _ in range(120):
            if process.poll() is not None:
                result['exit'] = process.returncode
                break
            try:
                with urllib.request.urlopen(f'http://127.0.0.1:{port}/actuator/health', timeout=2) as response:
                    result['healthResponse'] = json.loads(response.read())
                    result['health'] = result['healthResponse'].get('status') == 'UP'
                with urllib.request.urlopen(f'http://127.0.0.1:{port}/', timeout=2) as response:
                    result['frontend'] = '<html' in response.read().decode('utf-8').lower()
                if result['health'] and result['frontend']:
                    break
            except Exception:
                pass
            time.sleep(1)
        if result['health']:
            import urllib.error
            tokens = {}
            def request(method, path, user=None, data=None, expected=200):
                headers = {'Content-Type':'application/json'}
                if user: headers['Authorization'] = 'Bearer '+tokens[user]
                req = urllib.request.Request(f'http://127.0.0.1:{port}'+path, data=None if data is None else json.dumps(data).encode(), headers=headers, method=method)
                try:
                    with urllib.request.urlopen(req, timeout=20) as response:
                        code=response.status; raw=response.read()
                except urllib.error.HTTPError as error:
                    code=error.code;raw=error.read()
                assert code in ([expected] if isinstance(expected,int) else expected), (method,path,code,raw.decode()[:200])
                result.setdefault('checks',[]).append({'method':method,'path':path,'status':code})
                return json.loads(raw) if raw else None
            for user in ['admin','alice','bob']:
                login=request('POST','/api/auth/login',data={'username':user,'password':user})
                tokens[user]=login['token']
            g=request('POST','/api/dataset-groups','alice',{'name':'shared_test','description':'integration'},[200,201])['id']
            request('GET','/api/dataset-groups/'+g,'bob',expected=404)
            request('GET','/api/dataset-groups/'+g,'admin')
            request('PUT',f'/api/dataset-groups/{g}/members/bob','alice',{'role':'QUERY'},[200,204])
            cards=request('GET','/api/dataset-groups/page','bob')
            assert any(c['id']==g and c['canQuery'] and not c['canManage'] for c in cards['items'])
            request('GET','/api/dataset-groups/'+g,'bob',expected=403)
            request('GET',f'/api/dataset-groups/{g}/members','bob',expected=403)
            request('GET',f'/api/dataset-groups/{g}/modeling/sql-examples','bob',expected=403)
            request('PUT',f'/api/dataset-groups/{g}/members/bob','alice',{'role':'MANAGE'},[200,204])
            request('GET','/api/dataset-groups/'+g,'bob')
            request('PUT','/api/dataset-groups/'+g,'bob',{'name':'shared_updated','description':'managed'})
            request('GET',f'/api/dataset-groups/{g}/members','bob')
            request('DELETE',f'/api/dataset-groups/{g}/members/bob','alice',expected=[200,204])
            request('GET','/api/dataset-groups/'+g,'bob',expected=404)
            assert request('GET','/api/dataset-groups/page','bob')['total']==0
            result['aclHttp']=True
    except Exception as error:
        result['error']=str(error)
    finally:
        process.terminate()
        try:
            process.wait(timeout=15)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait(timeout=5)
result['processStopped'] = process.poll() is not None
(root/'report.json').write_text(json.dumps(result, indent=2), encoding='utf-8')
raise SystemExit(0 if result['health'] and result['frontend'] and result.get('aclHttp') else 1)

