"""Exercise the real workbench in headless Edge using CDP and the Python standard library."""
import base64
import hashlib
import json
import os
from pathlib import Path
import socket
import struct
import subprocess
import sys
import time
import urllib.parse
import urllib.request

sys.stdout.reconfigure(encoding="utf-8")
ROOT = Path(__file__).resolve().parents[3]
OUT = Path(__file__).resolve().parent / "guided-workflow"
state = json.loads((OUT / "state.json").read_text(encoding="utf-8"))
gid = state["group_id"]
profile = ROOT / ".tmp-question-acceptance/browser-profile"
profile.mkdir(exist_ok=True)
browser = subprocess.Popen([
    r"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe",
    "--headless=new", "--disable-gpu", "--no-first-run", "--no-default-browser-check",
    "--remote-debugging-port=9263", "--remote-debugging-address=127.0.0.1",
    "--user-data-dir=" + str(profile), "about:blank"],
    stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
    creationflags=subprocess.CREATE_NO_WINDOW)

def http(url, method="GET"):
    with urllib.request.urlopen(urllib.request.Request(url, method=method), timeout=10) as response:
        return json.loads(response.read())

class CDP:
    def __init__(self, url):
        parsed = urllib.parse.urlsplit(url)
        self.socket = socket.create_connection((parsed.hostname, parsed.port), timeout=120)
        key = base64.b64encode(os.urandom(16)).decode()
        request = ("GET " + parsed.path + " HTTP/1.1\r\nHost: " + parsed.netloc +
                   "\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Key: " + key +
                   "\r\nSec-WebSocket-Version: 13\r\n\r\n")
        self.socket.sendall(request.encode())
        header = b""
        while not header.endswith(b"\r\n\r\n"):
            header += self.socket.recv(1)
        assert b"101" in header.split(b"\r\n")[0], header
        accept = base64.b64encode(hashlib.sha1((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").encode()).digest())
        assert accept in header, header
        self.counter = 0
        self.errors = []

    def exact(self, size):
        result = b""
        while len(result) < size:
            chunk = self.socket.recv(size - len(result))
            if not chunk:
                raise ConnectionError("Browser socket closed")
            result += chunk
        return result

    def send(self, data, opcode=1):
        mask = os.urandom(4)
        size = len(data)
        head = bytes([0x80 | opcode])
        head += bytes([0x80 | size]) if size < 126 else (
            bytes([0x80 | 126]) + struct.pack("!H", size) if size < 65536 else
            bytes([0x80 | 127]) + struct.pack("!Q", size))
        self.socket.sendall(head + mask + bytes(c ^ mask[i % 4] for i, c in enumerate(data)))

    def receive(self):
        message = b""
        while True:
            a, b = self.exact(2)
            size = b & 127
            if size == 126:
                size = struct.unpack("!H", self.exact(2))[0]
            elif size == 127:
                size = struct.unpack("!Q", self.exact(8))[0]
            mask = self.exact(4) if b & 128 else None
            body = self.exact(size)
            if mask:
                body = bytes(c ^ mask[i % 4] for i, c in enumerate(body))
            opcode = a & 15
            if opcode == 8:
                raise ConnectionError("Browser closed websocket")
            if opcode == 9:
                self.send(body, 10)
                continue
            if opcode == 10:
                continue
            message += body
            if a & 128:
                return json.loads(message)

    def call(self, method, params=None):
        self.counter += 1
        ident = self.counter
        self.send(json.dumps({"id": ident, "method": method, "params": params or {}}).encode())
        while True:
            response = self.receive()
            if response.get("method") == "Runtime.exceptionThrown":
                self.errors.append(response["params"]["exceptionDetails"].get("text", "runtime error"))
            if response.get("id") == ident:
                if "error" in response:
                    raise RuntimeError(response["error"])
                return response.get("result", {})

    def evaluate(self, expression):
        result = self.call("Runtime.evaluate", {"expression": expression, "awaitPromise": True, "returnByValue": True})
        if result.get("exceptionDetails"):
            raise RuntimeError(result["exceptionDetails"])
        return result.get("result", {}).get("value")

    def wait(self, expression, timeout=90):
        until = time.monotonic() + timeout
        while time.monotonic() < until:
            value = self.evaluate(expression)
            if value:
                return value
            time.sleep(0.3)
        raise TimeoutError(expression + " body=" + str(self.evaluate("document.body.innerText"))[:2500])

    def click(self, label):
        literal = json.dumps(label, ensure_ascii=False)
        result = self.evaluate("(() => { const b=[...document.querySelectorAll('button')].find(b=>b.textContent.trim()===" +
                                literal + "); if(!b || b.disabled) return false; b.click(); return true; })()")
        assert result, "Missing or disabled button: " + label

    def screenshot(self, name):
        result = self.call("Page.captureScreenshot", {"format": "png", "captureBeyondViewport": False})
        (OUT / (name + ".png")).write_bytes(base64.b64decode(result["data"]))

try:
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
        const response = await fetch('/api/auth/login', {method:'POST',headers:{'Content-Type':'application/json'},
            body:JSON.stringify({username:'bob',password:'bob'})});
        if(!response.ok) throw new Error('Login failed');
        localStorage.setItem('claw_token', (await response.json()).token); return true;
    })()""")
    cdp.call("Page.navigate", {"url": "http://127.0.0.1:8085/configure/modeling/" + gid})
    cdp.wait("document.body.innerText.includes('当前：')")
    initial = cdp.evaluate("(async()=>{const r=await fetch('/api/dataset-groups/" + gid + "/modeling/workflow',{headers:{Authorization:'Bearer '+localStorage.getItem('claw_token')}});return await r.json()})()")
    base_version = initial["publishedVersion"]
    if "--snapshot" in sys.argv:
        assert initial["stage"] == "COMPLETE" and not initial["draftChanged"]
        cdp.click("4 · 发布")
        cdp.wait("document.body.innerText.includes('与已发布版本一致')")
        cdp.wait("document.body.innerText.includes('澄清缺失口径并确认模型变更')")
        assert cdp.evaluate("[...document.querySelectorAll('button')].find(b=>b.textContent.trim()==='发布').disabled")
        cdp.screenshot("published-settled")
        print(json.dumps({"final_snapshot_verified": True, "published_version": base_version}))
        sys.exit(0)
    if initial["stage"] == "VALIDATION":
        cdp.wait("new URLSearchParams(location.search).get('asset')==='questions'")
        cdp.wait("document.body.innerText.includes('验证已有问题并确认结果')")
        cdp.click("校验模型并验证待验问题")
    cdp.wait("document.body.innerText.includes('当前：业务确认')", timeout=180)
    checks = {}
    if initial["stage"] == "VALIDATION":
        checks["resumes_current_stage_automatically"] = True
        checks["batch_revalidation_after_model_edit"] = True
    body = cdp.evaluate("document.body.innerText")
    for label in ["1 · 数据准备", "2 · 对话建模", "3 · 验证与确认", "4 · 发布"]:
        assert label in body, label
    checks["four_stage_navigation"] = True
    assert "澄清缺失口径并确认模型变更" in body
    assert "按你的口径起草 Cube 指标与维度" not in body
    checks["welcome_matches_guided_workflow"] = True
    assert ("可问数 · 已发布 v" + str(base_version)) in body and "有未发布变更" in body
    checks["published_and_draft_separated"] = True
    cdp.screenshot("awaiting-guide")
    cdp.click("2 · 对话建模")
    cdp.wait("document.body.innerText.includes('通过对话明确业务目标与常用问题')")
    cdp.wait("document.body.innerText.includes('2025年有效订单销售额是多少')")
    assert not cdp.evaluate("[...document.querySelectorAll('button')].some(b=>b.textContent.trim()==='口径与结果正确，确认')")
    checks["requirements_mode_without_acceptance_buttons"] = True
    cdp.click("4 · 发布")
    cdp.wait("document.body.innerText.includes('本次业务模型变更')")
    assert cdp.evaluate("[...document.querySelectorAll('button')].find(b=>b.textContent.trim()==='发布').disabled")
    assert not cdp.evaluate("[...document.querySelectorAll('details')].find(d=>d.querySelector('summary')?.textContent==='查看技术文件差异').open")
    checks["publish_blocked_until_confirmation"] = True
    checks["technical_diff_collapsed"] = True
    cdp.click("3 · 验证与确认")
    cdp.wait("document.body.innerText.includes('验证已有问题并确认结果')")
    cdp.wait("document.body.innerText.includes('3749')")
    body = cdp.evaluate("document.body.innerText")
    assert "2025年有效订单销售额是多少" in body and "3749" in body
    checks["same_question_and_real_result"] = True
    cdp.screenshot("awaiting-result")
    cdp.click("口径与结果正确，确认")
    cdp.wait("document.body.innerText.includes('当前：待发布')")
    checks["human_confirmation_advances_stage"] = True
    cdp.click("4 · 发布")
    cdp.wait("[...document.querySelectorAll('button')].some(b=>b.textContent.trim()==='发布'&&!b.disabled)")
    cdp.screenshot("ready-to-publish")
    cdp.click("发布")
    target_version = str(base_version + 1)
    cdp.wait("document.body.innerText.includes('发布成功：v" + target_version + "')", timeout=180)
    cdp.wait("document.body.innerText.includes('可问数 · 已发布 v" + target_version + "')")
    cdp.wait("document.body.innerText.includes('与已发布版本一致') && !document.body.innerText.includes('发布中…')")
    checks["browser_publication_succeeded"] = True
    cdp.screenshot("published")
    cdp.evaluate("(() => { const s=document.querySelector('select[aria-label=\"模型详情\"]');s.value='mdl';s.dispatchEvent(new Event('change',{bubbles:true}));return true; })()")
    cdp.wait("location.search.includes('asset=mdl')")
    checks["advanced_asset_link_preserved"] = True
    cdp.click("开始问数")
    cdp.wait("location.pathname==='/chat' && new URLSearchParams(location.search).get('groups')===" + json.dumps(gid))
    checks["query_navigation_preserves_group"] = True
    assert not cdp.errors, cdp.errors
    checks["no_runtime_exceptions"] = True
    checks["base_version"] = base_version
    checks["published_version"] = base_version + 1
    (OUT / "browser-checks.json").write_text(json.dumps(checks, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(checks, ensure_ascii=False), flush=True)
finally:
    try:
        cdp.call("Browser.close")
    except Exception:
        browser.terminate()
