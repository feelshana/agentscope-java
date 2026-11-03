import test from 'node:test';
import assert from 'node:assert/strict';
import React from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { BiSyncStatusPanel, syncDuration, syncTime } from '../src/components/BiSyncStatusPanel.tsx';

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
