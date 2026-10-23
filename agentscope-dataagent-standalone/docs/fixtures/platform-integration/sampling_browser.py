"""Check nullable external row counts and sample refresh in the built SPA with mock APIs."""
import functools
import json
import threading
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from playwright.sync_api import sync_playwright, expect


class SpaHandler(SimpleHTTPRequestHandler):
    def do_GET(self):
        if not Path(self.translate_path(self.path.split('?')[0])).is_file():
            self.path = '/index.html'
        super().do_GET()


server = ThreadingHTTPServer(('127.0.0.1', 0), functools.partial(
    SpaHandler, directory='src/main/resources/static'))
threading.Thread(target=server.serve_forever, daemon=True).start()
output = Path('target/sampling-browser')
output.mkdir(parents=True, exist_ok=True)
dataset = {'id': 'd', 'name': 'orders', 'groupId': 'g', 'tableName': 'orders',
           'schemaName': 'retail', 'rowCount': None, 'origin': 'datasource',
           'columns': [{'name': 'status', 'originalName': 'status', 'sqlType': 'VARCHAR',
                        'description': '订单状态'}], 'sampling': None}
refreshes = []
errors = []
try:
    with sync_playwright() as pw:
        browser = pw.chromium.launch(channel='msedge', headless=True)
        context = browser.new_context()
        context.add_init_script("sessionStorage.setItem('dataagent_token', 'test-sampling-token')")
        page = context.new_page()
        page.on('pageerror', lambda error: errors.append(str(error)))

        def respond(route):
            path = route.request.url.split('/api/', 1)[1].split('?')[0]
            if path == 'auth/options':
                route.fulfill(json={'passwordLoginEnabled': True, 'biLoginEnabled': False})
            elif path == 'auth/me':
                route.fulfill(json={'userId': 'admin', 'username': 'admin', 'roles': ['admin']})
            elif path == 'dataset-groups/g':
                route.fulfill(json={'group': {'id': 'g', 'name': '测试知识库', 'role': 'MANAGE',
                                             'canManage': True, 'canQuery': True}, 'datasets': [dataset]})
            elif path == 'datasets/d/preview':
                route.fulfill(json={'columns': dataset['columns'], 'rows': [['PAID']]})
            elif path.endswith('/sessions/inbox-page'):
                route.fulfill(json={'items': [], 'nextCursor': None, 'hasMore': False})
            elif path == 'datasets/d/sample-refresh':
                refreshes.append(route.request.method)
                if len(refreshes) == 1:
                    dataset['sampling'] = {'status': 'SUCCESS', 'sampledAt': '2026-10-16T00:00:00Z',
                                           'sampledRows': 1000, 'rowLimit': 1000}
                    route.fulfill(json=dataset)
                else:
                    route.fulfill(status=503, json={'message': '样本采集失败，已保留上次成功摘要'})
            else:
                route.fulfill(json=[])

        page.route('**/api/**', respond)
        page.goto(f'http://127.0.0.1:{server.server_port}/configure/datasets/g/table/d')
        try:
            expect(page.get_by_text('显示 1 行 / 总行数未统计', exact=True)).to_be_visible()
        except Exception:
            (output / 'failure.json').write_text(json.dumps({'body': page.locator('body').inner_text(),
                'pageErrors': errors}, ensure_ascii=False), encoding='utf-8')
            raise
        expect(page.get_by_text('PAID', exact=True)).to_be_visible()
        page.get_by_role('button', name='刷新样本', exact=True).click()
        expect(page.get_by_text('样本已刷新，重新发布后用于问数', exact=True)).to_be_visible()
        expect(page.get_by_role('status').filter(has_text='已采样 1000 行')).to_be_visible()
        page.get_by_role('button', name='刷新样本', exact=True).click()
        expect(page.get_by_role('alert')).to_contain_text('保留上次成功摘要')
        expect(page.get_by_role('status').filter(has_text='已采样 1000 行')).to_be_visible()
        page.screenshot(path=str(output / 'sampling.png'))
        assert refreshes == ['POST', 'POST'], refreshes
        assert not errors, errors
        browser.close()
finally:
    server.shutdown()
    server.server_close()
(output / 'report.json').write_text(json.dumps({'passed': True,
    'checks': ['unknown-total', 'refresh-success', 'refresh-failure-retains-sample'],
    'pageErrors': errors}, indent=2), encoding='utf-8')
print(json.dumps({'passed': True, 'checks': 3}))
