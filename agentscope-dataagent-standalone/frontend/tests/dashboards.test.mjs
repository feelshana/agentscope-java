import test from 'node:test';
import assert from 'node:assert/strict';
import { dashboardBridgeMessage, dashboardErrorText, dashboardMessages, dashboardStatus, dashboardPublishHint, dashboardReplyParts, dashboardToolProgress } from '../src/components/dashboardState.ts';
import { dashboardThumbnail, rememberDashboardThumbnail, forgetDashboardThumbnails } from '../src/components/dashboardThumbnails.ts';

test('failed writes never claim the page has been updated', () => {
  const failed = dashboardToolProgress('tool_result', 'write_file', "Error: Parameter validation failed for tool 'write_file'");
  assert.match(failed, /未成功/);
  assert.doesNotMatch(failed, /页面已/);
  assert.match(dashboardToolProgress('tool_result', 'write_file', 'Written to apps/dashboard/index.html'), /页面已写入/);
  assert.equal(dashboardToolProgress('token'), null);
});

test('preview rejects other frames, stale channels and malformed or oversized queries', () => {
  const source = {}; const other = {};
  const data = { kind: 'query', channel: 'current', id: 1, sql: 'SELECT 1' };
  assert.equal(dashboardBridgeMessage({ source: other, data }, source, 'current'), null);
  assert.equal(dashboardBridgeMessage({ source, data }, source, 'old'), null);
  assert.equal(dashboardBridgeMessage({ source, data }, null, 'current'), null);
  for (const change of [{ id: 0 }, { id: 1.5 }, { sql: '' }, { sql: 'a'.repeat(20001) }, { kind: 'result' }]) {
    assert.equal(dashboardBridgeMessage({ source, data: { ...data, ...change } }, source, 'current'), null);
  }
  assert.deepEqual(dashboardBridgeMessage({ source, data }, source, 'current'), { kind: 'query', id: 1, sql: 'SELECT 1' });
});

test('history restores one final answer per turn without losing user requests', () => {
  const turn = (id, role, content) => ({ id, role, content });
  const history = [turn('u1', 'USER', '生成看板'), turn('a1', 'ASSISTANT', '先查模型'),
    turn('t1', 'TOOL', 'large private result'), turn('a2', 'ASSISTANT', '已生成'),
    turn('u2', 'USER', '改为条形图'), turn('a3', 'ASSISTANT', '正在修改'), turn('a4', 'ASSISTANT', '修改完成')];
  assert.deepEqual(dashboardMessages(history), [
    { id: 'u1', role: 'user', text: '生成看板' }, { id: 'a2', role: 'assistant', text: '已生成' },
    { id: 'u2', role: 'user', text: '改为条形图' }, { id: 'a4', role: 'assistant', text: '修改完成' },
  ]);
});

test('transient provider failures are readable and do not disclose transport internals', () => {
  assert.equal(dashboardErrorText(new Error('HTTP request failed with status 503: internal model endpoint')),
    '模型服务暂时不可用，本轮尚未完成，请稍后重试。');
  assert.equal(dashboardErrorText('需要日期范围'), '需要日期范围');
});

test('draft, viewer and publish states come from revisions and actual permissions', () => {
  const board = { revision: 'draft', publishedRevision: 'live', canEdit: true, canPublish: true, html: '<html/>', verified: true };
  assert.equal(dashboardStatus(board).kind, 'changed');
  assert.equal(dashboardStatus({ ...board, canEdit: false }).kind, 'published');
  assert.equal(dashboardStatus({ ...board, publishedRevision: null }).kind, 'draft');
  assert.equal(dashboardStatus({ ...board, deletionPending: true }).kind, 'deleting');
  assert.match(dashboardPublishHint({ ...board, deletionPending: true }, false), /清理/);
  assert.equal(dashboardPublishHint(board, false), '');
  assert.match(dashboardPublishHint({ ...board, canPublish: false }, false), /管理权限/);
  assert.match(dashboardPublishHint(board, true), /等待/);
  assert.match(dashboardPublishHint({ ...board, html: null }, false), /先生成/);
  assert.match(dashboardPublishHint({ ...board, verified: false }, false), /尚未检查/);
  const preview = { revision: 'draft', issues: [], settled: true };
  assert.equal(dashboardPublishHint(board, false, preview), '');
  assert.match(dashboardPublishHint(board, false, { ...preview, revision: 'old' }), /正在检查预览/);
  assert.match(dashboardPublishHint(board, false, { ...preview, settled: false }), /正在检查预览/);
  assert.match(dashboardPublishHint(board, false, { ...preview, issues: [{}] }), /预览有错误/);
  for (const [status, text] of [['FAILED', /未通过/], ['STALE', /重新检查/], ['CHECKING', /正在检查草稿/], ['MODEL_CHANGED', /新建看板/]]) {
    assert.match(dashboardPublishHint({ ...board, verified: false, fileCheck: { status } }, false, preview), text);
  }
  assert.match(dashboardPublishHint({ ...board, revision: 'live' }, false), /一致/);
});

test('folded technical details retain every original answer word', () => {
  for (const heading of ['实现说明', '## 实现说明', '**实现说明**', '### 技术说明：']) {
    const text = `已生成经营看板。\n\n${heading}\n这里保留SQL、数据口径与限制。`;
    const parts = dashboardReplyParts(text);
    assert.equal(parts.main, '已生成经营看板。');
    assert.equal(parts.details, `${heading}\n这里保留SQL、数据口径与限制。`);
  }
  assert.deepEqual(dashboardReplyParts('需要先确认指标口径'), { main: '需要先确认指标口径', details: '' });
  const long = `金额轴已改为万元。\n\n${'说明formatter和SQL保持正确。'.repeat(100)}`;
  const parts = dashboardReplyParts(long);
  assert.equal(parts.main, '金额轴已改为万元。');
  assert.equal(`${parts.main}\n\n${parts.details}`, long);
});

test('thumbnail messages cannot cross preview channels or smuggle active documents', () => {
  const source = {}; const image = 'data:image/png;base64,iVBORw0KGgo=';
  const data = { kind: 'thumbnail', channel: 'current', image };
  assert.deepEqual(dashboardBridgeMessage({ source, data }, source, 'current'), { kind: 'thumbnail', image });
  assert.equal(dashboardBridgeMessage({ source: {}, data }, source, 'current'), null);
  assert.equal(dashboardBridgeMessage({ source, data }, source, 'stale'), null);
  for (const image of ['https://example.test/image.png', 'data:image/svg+xml,<svg/>', 'data:text/html,secret', `data:image/png;base64,${'A'.repeat(450000)}`, 'data:image/png;base64,bad\nvalue']) {
    assert.equal(dashboardBridgeMessage({ source, data: { ...data, image } }, source, 'current'), null);
  }
});

test('thumbnail cache is login-scoped, revision-specific and bounded', () => {
  const previous = globalThis.sessionStorage;
  let credential = 'first-test-login';
  globalThis.sessionStorage = { getItem: () => credential };
  const image = 'data:image/png;base64,iVBORw0KGgo=';
  try {
    rememberDashboardThumbnail('one', 'r1', image);
    rememberDashboardThumbnail('other', 'r1', image);
    assert.equal(dashboardThumbnail('one', 'r1'), image);
    forgetDashboardThumbnails('one');
    assert.equal(dashboardThumbnail('one', 'r1'), undefined);
    assert.equal(dashboardThumbnail('other', 'r1'), image);
    rememberDashboardThumbnail('one', 'r1', image);
    assert.equal(dashboardThumbnail('one', 'r2'), undefined);
    credential = 'second-test-login';
    assert.equal(dashboardThumbnail('one', 'r1'), undefined);
    for (let index = 0; index < 20; index++) rememberDashboardThumbnail(`board-${index}`, 'r', image);
    assert.equal(dashboardThumbnail('board-0', 'r'), undefined);
    assert.equal(dashboardThumbnail('board-19', 'r'), image);
    credential = null;
    assert.equal(dashboardThumbnail('board-19', 'r'), undefined);
    rememberDashboardThumbnail('one', 'r1', image);
    credential = 'third-test-login';
    assert.equal(dashboardThumbnail('one', 'r1'), undefined);
  } finally { globalThis.sessionStorage = previous; }
});
