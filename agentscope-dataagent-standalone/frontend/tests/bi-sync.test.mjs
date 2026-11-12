import test from 'node:test';
import assert from 'node:assert/strict';
import React from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { BiSyncStatusPanel, syncDuration, syncTime } from '../src/components/BiSyncStatusPanel.tsx';
import { BiExtraSyncStatusPanel } from '../src/components/BiExtraSyncStatusPanel.tsx';

const fixture = {
  state: 'SUCCESS', message: '最近一次同步已完成', running: false, paused: false,
  freshness: 'CURRENT', completedThrough: '2026-11-02', reportThrough: '2026-11-02', dashboardThrough: '2026-11-02',
  lastSuccessAt: '2026-11-02T18:03:00Z', lastRun: { outcome: 'SUCCESS', startedAt: '2026-11-02T18:00:00Z',
    finishedAt: '2026-11-02T18:03:00Z', durationMs: 180000, message: null },
};

test('sync status shows human cutoff and operations without inventing write controls', () => {
  const html = renderToStaticMarkup(React.createElement(BiSyncStatusPanel, { data: fixture }));
  assert.match(html, /2026-11-02/);
  assert.match(html, /已更新至昨天/);
  assert.match(html, /不能区分查看与导出/);
  assert.doesNotMatch(html, /<button|<input|password|log_type/);
});

test('paused and failed states preserve previous data cutoff and surface readable diagnostics', () => {
  const html = renderToStaticMarkup(React.createElement(BiSyncStatusPanel, { data: { ...fixture,
    state: 'PAUSED', paused: true, freshness: 'STALE', message: '同步已暂停，已有数据和进度保留。' } }));
  assert.match(html, /已暂停/); assert.match(html, /数据待更新/); assert.match(html, /2026-11-02/);
  const failed = renderToStaticMarkup(React.createElement(BiSyncStatusPanel, { data: { ...fixture,
    state: 'FAILED', lastRun: { ...fixture.lastRun, message: '<script>unsafe</script>' } } }));
  assert.match(failed, /role="alert"/); assert.doesNotMatch(failed, /<script>/);
});

test('uninitialized status does not display fake successful times or counts', () => {
  const html = renderToStaticMarkup(React.createElement(BiSyncStatusPanel, { data: { ...fixture,
    state: 'NOT_INITIALIZED', completedThrough: null, reportThrough: null, dashboardThrough: null,
    lastSuccessAt: null, lastRun: null, freshness: 'NONE', message: '尚未初始化' } }));
  assert.match(html, /待初始化/); assert.match(html, /尚未完成首次同步/); assert.match(html, /尚无执行记录/);
  assert.equal(syncTime(null), '—'); assert.equal(syncDuration(null), '—');
  assert.equal(syncDuration(1234), '1.2 秒'); assert.match(syncTime('2026-11-02T18:00:00Z'), /2026.*11.*03/);
});

test('extra task distinguishes verification, snapshots and gaps from a complete mirror', () => {
  const data = { state: 'SUCCESS_WITH_GAPS', message: '部分操作完成', running: false, paused: false,
    hasGaps: true, action: 'VERIFY', lastRun: fixture.lastRun,
    tables: [{ name: 'bi_page_click_log', label: '页面点击日志', snapshot: false, state: 'GAPS',
      requestedFrom: '2026-01-01', availableFrom: '2026-10-01', coverageFrom: '2026-10-01',
      processedThrough: '2026-11-11', refreshedAt: null, rowCount: null, gapCount: 1,
      gaps: [{ from: '2026-01-01', through: '2026-09-30', reason: '源历史已不可用' }] },
    { name: 'bi_iam_user', label: 'IAM用户', snapshot: true, state: 'CURRENT', refreshedAt: '2026-11-12T00:00:00Z',
      rowCount: 42, gapCount: 0, gaps: [] }],
  };
  const html = renderToStaticMarkup(React.createElement(BiExtraSyncStatusPanel, { data }));
  assert.match(html, /数据核验（非同步）/); assert.match(html, /处理至 2026-11-11/);
  assert.match(html, /2026-09-30/); assert.match(html, /源历史已不可用/);
  assert.match(html, /42 行（最近快照）/); assert.doesNotMatch(html, /<button|<input|数据已完整同步至/);
  const empty = renderToStaticMarkup(React.createElement(BiExtraSyncStatusPanel, {
    data: { ...data, state: 'NOT_INITIALIZED', lastRun: null, tables: [], hasGaps: false },
  }));
  assert.match(empty, /待初始化/); assert.doesNotMatch(empty, /逐表同步情况|最近执行成功/);
});
