"""Exercise the built SPA against mocked API authentication responses."""
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


root = Path('src/main/resources/static')
output = Path('target/auth-session-browser')
output.mkdir(parents=True, exist_ok=True)
server = ThreadingHTTPServer(('127.0.0.1', 0), functools.partial(SpaHandler, directory=str(root)))
threading.Thread(target=server.serve_forever, daemon=True).start()
checks = []
try:
    with sync_playwright() as pw:
        browser = pw.chromium.launch(channel='msedge', headless=True)
        for scenario in ['cached401', 'api401', 'network', 'options-network', 'no-token']:
            context = browser.new_context()
            if scenario != 'no-token':
                context.add_init_script("sessionStorage.setItem('dataagent_token', 'test-cached-token')")
            page = context.new_page()
            calls = []

            def respond(route):
                path = route.request.url.split('/api/', 1)[1]
                calls.append(path)
                if path == 'auth/options' and scenario != 'options-network':
                    route.fulfill(json={'passwordLoginEnabled': True, 'biLoginEnabled': False})
                elif scenario in ['network', 'options-network']:
                    route.abort('failed')
                elif scenario == 'api401' and path == 'auth/me':
                    route.fulfill(json={'userId': 'admin', 'username': 'admin', 'roles': ['admin']})
                else:
                    route.fulfill(status=401, json={'message': 'Unauthorized'})

            page.route('**/api/**', respond)
            page.goto(f'http://127.0.0.1:{server.server_port}/configure/datasets')
            if scenario in ['network', 'options-network']:
                expect(page.get_by_role('button', name='重试', exact=True)).to_be_visible()
                assert page.evaluate("sessionStorage.getItem('dataagent_token')") == 'test-cached-token'
                assert '/configure/datasets' in page.url
                if scenario == 'options-network':
                    expect(page.get_by_role('textbox', name='用户名')).to_have_count(0)
            else:
                expect(page).to_have_url(f'http://127.0.0.1:{server.server_port}/login')
                assert page.evaluate("sessionStorage.getItem('dataagent_token')") is None
                if scenario != 'no-token':
                    expect(page.get_by_text('登录状态已失效，请重新登录', exact=True)).to_be_visible()
                else:
                    expect(page.get_by_text('登录状态已失效，请重新登录', exact=True)).to_have_count(0)
                if scenario == 'cached401':
                    assert all(path in ['auth/options', 'auth/me'] for path in calls), calls
                if scenario == 'api401':
                    assert any(path not in ['auth/options', 'auth/me'] for path in calls), calls
            page.screenshot(path=str(output / f'{scenario}.png'))
            checks.append(scenario)
            context.close()
        browser.close()
finally:
    server.shutdown()
    server.server_close()
(output / 'report.json').write_text(json.dumps({'passed': True, 'checks': checks}, indent=2), encoding='utf-8')
print(json.dumps({'passed': True, 'checks': len(checks)}))
