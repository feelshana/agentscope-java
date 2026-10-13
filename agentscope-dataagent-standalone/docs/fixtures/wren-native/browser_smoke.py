"""Browser interaction checks against the built frontend with isolated API fixtures.

This does not call a live LLM, change user data, or replace backend acceptance.
Requires Playwright and an installed Edge (or --browser executable).
"""
import argparse
import functools
import json
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
import threading
from urllib.parse import urlparse, parse_qs

from playwright.sync_api import sync_playwright, expect


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--browser', default='C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe')
    parser.add_argument('--static', default='src/main/resources/static')
    parser.add_argument('--output', default='target/wren-native-browser')
    args = parser.parse_args()
    output = Path(args.output)
    output.mkdir(parents=True, exist_ok=True)

    class Handler(SimpleHTTPRequestHandler):
        def do_GET(self):
            if not Path(self.translate_path(self.path)).is_file():
                self.path = '/index.html'
            super().do_GET()

        def log_message(self, *_):
            pass

    server = ThreadingHTTPServer(('127.0.0.1', 0), functools.partial(Handler, directory=args.static))
    threading.Thread(target=server.serve_forever, daemon=True).start()
    calls, errors, checks = [], [], []
    cube = 'name: sales\nbase_object: valid_orders\nmeasures:\n  - name: paying_customers\n    expression: COUNT(DISTINCT customer_id)\n'
    files = [{'path': 'cubes/sales/metadata.yml', 'content': cube}]
    published = False
    uploaded = False

    def api(route):
        nonlocal published, uploaded
        req = route.request
        url = urlparse(req.url)
        path = url.path
        calls.append({'path': path, 'method': req.method, 'body': req.post_data if 'chat/' in path else None})
        result = {}
        if path.endswith('/chat/stream'):
            frames = [dict(type='token', data='已检查测试草稿，可以前往检查与发布。'), dict(type='done', sessionKey='modeling-fixture')]
            route.fulfill(content_type='text/event-stream', body=''.join('data: '+json.dumps(x, ensure_ascii=False)+'\n\n' for x in frames))
            return
        if path.endswith('/chat/session'):
            result = dict(exists=False, sessionKey='modeling-fixture', pendingToolCalls=[])
        elif path == '/api/agents/data-agent':
            result = dict(id='data-agent', name='数据助手', tier='OWNER')
        elif path.endswith('/knowledge') and req.method == 'PUT':
            uploaded = True
            route.fulfill(content_type='text/plain; charset=utf-8', body='营收排除内部客户。')
            return
        elif path == '/api/dataset-groups/fixture':
            result = dict(group=dict(id='fixture', name='隔离验收知识库'), datasets=[], knowledge='营收排除内部客户。' if uploaded else '')
        elif path.endswith('/modeling/workflow'):
            result = dict(groupId='fixture', stage='COMPLETE' if published else 'PUBLICATION', mdlState='PUBLISHED' if published else 'DIRTY', publishedVersion=2 if published else 1, queryAvailable=True, draftChanged=not published, engineeringStatus='PASSED', engineeringCheckedAt=None, questionSummary=dict(total=0, confirmed=0, incomplete=0, needsValidation=0, awaitingConfirmation=0), changedAssets=[], blockers=[], nextAction=dict(type='PUBLISH', label='发布', message='检查通过'), canPublish=True)
        elif path.endswith('/mdl/view'):
            result = dict(state='DIRTY', version=1, publishedAt=None, changed=True, models=[], derivedModels=[], relations=[], views=[], cubes=[dict(name='sales', baseModel='valid_orders', description='客户指标', measures=[dict(name='paying_customers', expression='COUNT(DISTINCT customer_id)', type='BIGINT')], dimensions=[], timeDimensions=[], path=files[0]['path'])], issues=[])
        elif path.endswith('/mdl/publish'):
            published = True
            result = dict(ok=True, issues=[], output='official build passed', mdlState='PUBLISHED', mdlVersion=2, mdlPublishedAt='2026-10-11T12:00:00Z')
        elif path.endswith('/mdl'):
            result = dict(files=files, publishedFiles=files if published else [], issues=[], mdlState='PUBLISHED' if published else 'DIRTY', mdlVersion=2 if published else 1, mdlPublishedAt=None, changed=not published)
        elif path.endswith('/workspace/file'):
            result = dict(path=parse_qs(url.query).get('path', [''])[0], content=cube)
        elif path.endswith('/modeling'):
            result = dict(group=dict(mdlState='DIRTY', mdlVersion=1), datasets=[], relations=[], cubes=[], views=[], terms=[])
        route.fulfill(content_type='application/json', body=json.dumps(result, ensure_ascii=False))

    try:
        with sync_playwright() as pw:
            browser = pw.chromium.launch(executable_path=args.browser, headless=True)
            page = browser.new_page(viewport={'width': 1440, 'height': 1000})
            page.on('pageerror', lambda error: errors.append(str(error)))
            page.add_init_script("localStorage.setItem('claw_token', 'isolated-browser-fixture')")
            page.route('**/api/**', api)
            page.goto(f'http://127.0.0.1:{server.server_port}/configure/modeling/fixture')
            expect(page.get_by_role('button', name='＋ 批量填写问题', exact=True)).to_be_visible()
            expect(page.get_by_role('region', name='对话建模工作区')).to_be_hidden()
            checks.append('初始只展示资料和可选问题，对话隐藏')
            page.locator('input[type=file]').set_input_files({'name': 'business.md', 'mimeType': 'text/markdown', 'buffer': '营收排除内部客户。'.encode()})
            expect(page.get_by_text('business.md', exact=False).first).to_be_visible()
            checks.append('资料上传显示文件及成功状态')
            page.get_by_role('button', name='＋ 批量填写问题', exact=True).click()
            page.get_by_label('第 1 个问题', exact=True).fill('2025年付费客户数是多少')
            page.get_by_role('button', name='＋ 增加一行', exact=True).click()
            expect(page.get_by_label('第 4 个问题', exact=True)).to_be_visible()
            page.get_by_role('button', name='提交问题，开始建模', exact=True).click()
            expect(page.get_by_text('已检查测试草稿，可以前往检查与发布。', exact=False).first).to_be_visible()
            assert sum(c['path'].endswith('/chat/stream') for c in calls) == 1
            checks.append('多行问题提交一次并进入宽对话')
            page.get_by_role('button', name='请助手检查草稿', exact=True).click()
            expect(page.get_by_text('已检查测试草稿，可以前往检查与发布。', exact=False)).to_have_count(2)
            assert sum(c['path'].endswith('/chat/stream') for c in calls) == 2
            checks.append('连续追加请求不会因重复编号漏发')
            page.get_by_role('button', name='查看语义模型', exact=True).click()
            page.get_by_label('语义资产类型').select_option('cubes')
            expect(page.get_by_text('sales', exact=True).first).to_be_visible()
            expect(page.get_by_text('COUNT(DISTINCT customer_id)', exact=False).first).to_be_visible()
            checks.append('Cube 目录和实际 YAML 可查看')
            page.get_by_role('button', name='2 · 对话建模', exact=True).click()
            expect(page.get_by_text('已检查测试草稿，可以前往检查与发布。', exact=False)).to_have_count(2)
            assert sum(c['path'].endswith('/chat/stream') for c in calls) == 2
            checks.append('切换资产再返回保留对话且不重发')
            page.get_by_role('button', name='下一步：检查与发布 →', exact=True).click()
            page.get_by_role('button', name='发布', exact=True).click()
            expect(page.get_by_text('发布成功：v2', exact=False)).to_be_visible()
            expect(page.get_by_role('button', name='用已发布模型问数 →', exact=True)).to_be_visible()
            checks.append('零预设验收也能发布，显示版本和问数入口')
            page.screenshot(path=str(output / 'published.png'), full_page=True)
            assert not errors, errors
            browser.close()
    finally:
        server.shutdown()
        (output / 'report.json').write_text(json.dumps(dict(checks=checks, errors=errors, calls=calls), ensure_ascii=False, indent=2), encoding='utf-8')
    print(f'Browser checks passed: {len(checks)} (mock API, no live LLM)')


if __name__ == '__main__':
    main()
