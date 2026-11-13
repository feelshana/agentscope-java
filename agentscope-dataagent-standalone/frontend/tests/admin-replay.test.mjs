import test from 'node:test';
import assert from 'node:assert/strict';
import React from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import AnswerContent from '../src/components/AnswerContent.tsx';
import KnowledgeScopeLabel from '../src/components/KnowledgeScopeLabel.tsx';
import { fileUrl } from '../src/api/artifacts.ts';
import { normalizeChartReferences } from '../src/components/answerRefs.ts';

globalThis.window = { location: new URL('https://dataagent.test') };

test('read-only history never follows links or loads unapproved external images', () => {
  const html = renderToStaticMarkup(React.createElement(AnswerContent, { readOnly: true },
    '[外部页面](https://outside.test) ![外部图](https://outside.test/track.png)\n\n| 指标 | 值 |\n| --- | --- |\n| 销量 | 10 |'));
  assert.doesNotMatch(html, /href=|<img/);
  assert.match(html, /暂不可查看/);
  assert.match(html, /<table>/);
});

test('knowledge snapshots preserve names and distinguish ordinary chat from missing history', () => {
  const label = scope => renderToStaticMarkup(React.createElement(KnowledgeScopeLabel, { scope, showUnknown: true }));
  assert.match(label({ groupIds: ['old'], groupNames: { old: '当时的知识库' }, allGroups: false }), /当时的知识库/);
  assert.match(label({ groupIds: [], groupNames: {}, allGroups: false }), /普通聊天/);
  assert.match(label(undefined), /知识库未记录/);
});

test('history image authorization uses only the dedicated endpoint at each deployment prefix', () => {
  const url = '/api/admin/conversation-history/user-a/artifacts/91a3378a-f591-4f39-81e9-86b4e4a92f50?sessionKey=abc';
  const prefix = (process.env.DATAAGENT_TEST_BASE_PATH ?? '/').replace(/\/$/, '');
  assert.equal(fileUrl(prefix + url), url);
  assert.equal(fileUrl(url + '&token=secret'), url);
  assert.equal(fileUrl('//outside.test' + url), null);
  assert.equal(fileUrl('/api/admin/users'), null);
});

test('readonly answer displays inline chart through the shared markdown renderer', () => {
  const charts = new Map([['saved-chart', React.createElement('div', { 'data-chart': 'saved' }, '已保存图表')]]);
  const html = renderToStaticMarkup(React.createElement(AnswerContent, { readOnly: true, charts }, '![趋势](chart://saved-chart)'));
  assert.match(html, /data-chart="saved"/);
  assert.doesNotMatch(html, /<img/);
});

test('bare saved chart references render once without rewriting code blocks or unknown ids', () => {
  const charts = new Map([['saved-chart', React.createElement('div', { 'data-chart': 'saved' }, '已保存图表')]]);
  const html = renderToStaticMarkup(React.createElement(AnswerContent, { readOnly: true, charts }, '销售趋势\n\nchart://saved-chart\n\n结果说明'));
  assert.equal((html.match(/data-chart="saved"/g) ?? []).length, 1);
  assert.match(html, /claw-md-chart/);
  assert.doesNotMatch(html, /<p><div/);
  assert.equal(normalizeChartReferences('chart://missing', charts.keys()), 'chart://missing');
  assert.equal(normalizeChartReferences('```\nchart://saved-chart\n```', charts.keys()), '```\nchart://saved-chart\n```');
  const missing = renderToStaticMarkup(React.createElement(AnswerContent, { readOnly: true }, '![丢失图](chart://missing)'));
  assert.match(missing, /图表未保存或暂不可查看/);
});
