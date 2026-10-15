"""Browser checks for the actual tool components, with no backend/user data changes."""
import functools
import json
import threading
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

from playwright.sync_api import sync_playwright, expect

root = Path('target/tool-presentation')
server = ThreadingHTTPServer(('127.0.0.1', 0), functools.partial(SimpleHTTPRequestHandler, directory=str(root)))
threading.Thread(target=server.serve_forever, daemon=True).start()
errors = []
checks = []
try:
    with sync_playwright() as pw:
        browser = pw.chromium.launch(executable_path='C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe', headless=True)
        page = browser.new_page(viewport={'width': 1280, 'height': 950})
        page.on('pageerror', lambda error: errors.append(str(error)))
        page.goto(f'http://127.0.0.1:{server.server_port}')
        panel = page.locator('aside')
        expect(panel.get_by_text('查看指标定义', exact=True)).to_be_visible()
        expect(panel.get_by_text('SUM(amount)', exact=True)).to_be_visible()
        expect(panel.get_by_text('基础模型 / 视图', exact=True)).to_be_visible()
        checks.append('double-encoded Cube rendered as structured fields')
        page.screenshot(path=str(root / 'cube.png'), full_page=True)
        page.get_by_role('button', name='recall', exact=True).click()
        expect(panel.locator('.da-wren-match')).to_have_count(2)
        expect(panel.get_by_text('SELECT SUM(amount) FROM orders', exact=True)).to_be_visible()
        expect(panel.get_by_text('SELECT COUNT(*) FROM orders', exact=True)).to_be_visible()
        checks.append('official nl_query/sql_query matches displayed')
        page.screenshot(path=str(root / 'recall.png'), full_page=True)
        page.get_by_role('button', name='rows', exact=True).click()
        expect(panel.locator('tbody tr')).to_have_count(2)
        expect(panel.get_by_role('cell', name='NULL', exact=True)).to_be_visible()
        expect(panel.get_by_text('已生成分析数据文件。', exact=True)).to_be_visible()
        expect(panel.get_by_text('返回 2 行', exact=False)).to_contain_text('未完整展示')
        panel.get_by_text('本次执行 SQL', exact=True).click()
        expect(panel.get_by_text("SELECT 'a\\nb' FROM orders", exact=True)).to_be_visible()
        checks.append('receipt, null, truncation and artifact note preserved')
        page.get_by_role('button', name='empty', exact=True).click()
        expect(panel.get_by_text('未找到匹配的问答', exact=True)).to_be_visible()
        checks.append('empty recall distinguished from an error')
        page.get_by_role('button', name='error', exact=True).click()
        expect(panel.get_by_role('alert')).to_have_text('召回失败，请重试')
        checks.append('JSON-encoded errors rendered explicitly')
        page.get_by_role('button', name='malformed', exact=True).click()
        expect(panel.get_by_text('{"broken":', exact=True).first).to_be_visible()
        panel.get_by_text('查看原始结果', exact=True).click()
        expect(panel.locator('.da-wren-raw code')).to_have_text('{"broken":')
        checks.append('malformed results retain original content')
        assert not errors, errors
        (root / 'browser-report.json').write_text(json.dumps({'passed': True, 'checks': checks, 'errors': errors}, ensure_ascii=False, indent=2), encoding='utf-8')
        browser.close()
finally:
    server.shutdown()
print(json.dumps({'passed': True, 'checks': len(checks)}))
