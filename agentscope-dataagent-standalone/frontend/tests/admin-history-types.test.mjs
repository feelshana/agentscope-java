import test from 'node:test';
import assert from 'node:assert/strict';
import React from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { historyCount, historyAssistantLabel, historyTypes, collapseHistoryMessage } from '../src/pages/admin/historyPresentation.ts';
import HistoryUserMessage from '../src/pages/admin/HistoryUserMessage.tsx';
import { getHistoryOverview, historyUsers, exportHistory } from '../src/api/adminHistory.ts';

globalThis.window = { location: new URL('https://dataagent.test') };
globalThis.sessionStorage = { getItem: () => 'fixture-token' };

test('assistant categories and count wording keep query metrics distinct from other USER messages', () => {
  assert.deepEqual(historyTypes.map(t => t.value), ['query', 'modeling', 'dashboard', 'all']);
  assert.equal(historyCount('query').label, '提问数');
  assert.equal(historyCount('modeling').label, '对话轮次');
  assert.equal(historyCount('dashboard').cellUnit, '轮');
  assert.equal(historyCount('all').label, '用户消息数');
  assert.equal(historyAssistantLabel('other'), '其他助手');
  assert.equal(historyAssistantLabel(undefined), '其他助手');
});

test('long template messages are collapsed and historical HTML stays inert text', () => {
  const source = '<script>alert(1)</script>' + '模板'.repeat(700);
  const markup = renderToStaticMarkup(React.createElement(HistoryUserMessage, { text: source }));
  assert.match(markup, /<details/);
  assert.match(markup, /展开完整用户消息/);
  assert.doesNotMatch(markup, /<details[^>]* open|<script>/);
  assert.match(markup, /&lt;script&gt;/);
  assert.equal(collapseHistoryMessage('正常提问'), false);
  assert.equal(collapseHistoryMessage('line\n'.repeat(20)), true);
  assert.doesNotMatch(renderToStaticMarkup(React.createElement(HistoryUserMessage, { text: '正常提问' })), /<details/);
});

test('overview per-user statistics and exports send the same assistant and search filter', async () => {
  const paths = [];
  const original = globalThis.fetch;
  globalThis.fetch = async (path) => {
    paths.push(new URL(path, 'https://dataagent.test'));
    return new Response('{}', { status: 200, headers: { 'Content-Type': 'application/json' } });
  };
  try {
    const query = { assistantType: 'dashboard', userId: 'user-a', query: '零售', from: '2026-11-01', to: '2026-11-17', sort: 'activity', direction: 'desc', page: 2, pageSize: 10 };
    await getHistoryOverview(query); await historyUsers(query, 0); await exportHistory(query);
    assert.equal(paths.length, 3);
    for (const url of paths) {
      assert.equal(url.searchParams.get('assistantType'), 'dashboard');
      assert.equal(url.searchParams.get('userId'), 'user-a');
      assert.equal(url.searchParams.get('query'), '零售');
      assert.equal(url.searchParams.get('from'), '2026-11-01');
    }
  } finally { globalThis.fetch = original; }
});
