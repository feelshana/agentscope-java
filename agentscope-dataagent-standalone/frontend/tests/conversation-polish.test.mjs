import test from 'node:test';
import assert from 'node:assert/strict';
import React from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { isSendKey, isNearBottom, splitFailedAnswer } from '../src/utils/chatInteraction.ts';
import { friendlyError, redactError } from '../src/utils/friendlyError.ts';
import ErrorNotice from '../src/components/ErrorNotice.tsx';

test('Enter confirms IME candidates without submitting, including composition-end legacy events', () => {
  const enter = { key: 'Enter', shiftKey: false };
  assert.equal(isSendKey(enter), true);
  assert.equal(isSendKey({ ...enter, shiftKey: true }), false);
  assert.equal(isSendKey({ ...enter, isComposing: true }), false);
  assert.equal(isSendKey(enter, true), false);
  assert.equal(isSendKey({ ...enter, keyCode: 229 }), false);
  assert.equal(isSendKey({ ...enter, key: 'Escape' }), false);
});

test('scroll threshold treats reading earlier messages separately from the latest edge', () => {
  assert.equal(isNearBottom({ scrollHeight: 2400, clientHeight: 600, scrollTop: 1600 }), false);
  assert.equal(isNearBottom({ scrollHeight: 2400, clientHeight: 600, scrollTop: 1780 }), true);
  assert.equal(isNearBottom({ scrollHeight: 200, clientHeight: 600, scrollTop: 0 }), true);
});

test('an interrupted answer retains its content while diagnostics remain separate', () => {
  assert.deepEqual(splitFailedAnswer('已计算 100 元\n[错误] connection reset'), { answer: '已计算 100 元', error: 'connection reset' });
  assert.deepEqual(splitFailedAnswer('[error] unavailable'), { answer: '', error: 'unavailable' });
  assert.deepEqual(splitFailedAnswer('保留内容\n**[错误]** timeout'), { answer: '保留内容', error: 'timeout' });
  assert.deepEqual(splitFailedAnswer('旧版错误信息'), { answer: '', error: '旧版错误信息' });
});

test('communication failures are not misdiagnosed as bad passwords', () => {
  const error = friendlyError('Communications link failure. Driver has not received any packets', 'datasource');
  assert.equal(error.summary, '暂时无法连接数据库');
  assert.match(error.action, /应用服务器.*主机和端口/);
  assert.doesNotMatch(error.action, /密码错误/);
  assert.equal(friendlyError('Access denied for user fixture', 'datasource').summary, '数据库拒绝了登录');
  assert.equal(friendlyError('请求失败: 401', 'chat').summary, '登录状态已失效');
  assert.equal(friendlyError('图表读取失败，HTTP 403', 'chart').summary, '当前账号没有访问权限');
  assert.equal(friendlyError('图表读取失败，HTTP 503', 'chart').summary, '服务暂时无法响应');
});

test('existing business validation stays readable and does not invent a recovery action', () => {
  assert.equal(friendlyError('请先选择知识库', 'chat').summary, '请先选择知识库');
  assert.equal(friendlyError('字段加载失败，请重试', 'datasource').action, '');
  assert.match(friendlyError('timeout', 'chat').action, /缩小日期或数据范围/);
});

test('diagnostics redact quoted secrets, URL credentials, bearer and known form passwords', () => {
  const raw = 'password="fixture pass" pwd=fixture_pwd&api_key=fixture_api Authorization: Bearer fixture_bearer https://user:fixture_url@db.example/';
  const safe = redactError(raw);
  for (const value of ['fixture pass', 'fixture_pwd', 'fixture_api', 'fixture_bearer', 'fixture_url']) assert.ok(!safe.includes(value));
  assert.equal(redactError('driver echoed sample-value', ['sample-value']), 'driver echoed [已隐藏]');
  const html = renderToStaticMarkup(React.createElement(ErrorNotice, { error: raw, context: 'datasource' }));
  assert.match(html, /<details>/);
  assert.doesNotMatch(html, /<details open/);
  assert.ok(!html.includes('fixture_bearer'));
});
