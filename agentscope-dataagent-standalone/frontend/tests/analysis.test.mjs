import test from 'node:test';
import assert from 'node:assert/strict';
import React from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { analysisSuggestions } from '../src/utils/analysisSuggestions.ts';
import SqlEditor from '../src/components/SqlEditor.tsx';

function series(points, gap = 1) {
  return Array.from({ length: points }, (_, i) => [
    new Date(Date.UTC(2026, 0, 1 + i * gap)).toISOString().slice(0, 10), i + 10,
  ]);
}
function query(rows, name = 'run_sql') {
  return { name, result: JSON.stringify({ columns: ['日期', '访问次数'], rows }) };
}

test('analysis suggestions follow actual daily point thresholds', () => {
  assert.deepEqual(analysisSuggestions([query(series(2))]), ['attribution']);
  assert.deepEqual(analysisSuggestions([query(series(7))]), ['attribution', 'anomaly']);
  assert.deepEqual(analysisSuggestions([query(series(14))]), ['attribution', 'anomaly', 'forecast']);
});

test('weekly and monthly forecasts use their own minimum sample sizes', () => {
  assert.deepEqual(analysisSuggestions([query(series(8, 7))]), ['attribution', 'forecast']);
  const rows = Array.from({ length: 12 }, (_, i) => [`2025-${String(i + 1).padStart(2, '0')}`, i + 1]);
  assert.deepEqual(analysisSuggestions([query(rows)]), ['attribution', 'forecast']);
});

test('a scalar, an ID-only result, search and an unsuccessful query offer no analysis', () => {
  assert.deepEqual(analysisSuggestions([query(series(1))]), []);
  assert.deepEqual(analysisSuggestions([query(series(20), 'web_search')]), []);
  assert.deepEqual(analysisSuggestions([{ name: 'run_sql', result: 'error: refused' }]), []);
  const rows = series(20);
  assert.deepEqual(analysisSuggestions([{ name: 'run_sql', result: JSON.stringify({ columns: ['日期', 'user_id'], rows }) }]), []);
});

test('follow-ups use the last successful table and tolerate receipt and CSV notes', () => {
  const latest = query(series(7));
  latest.result = `[WREN_QUERY_RECEIPT]{"tool":"run_sql"}\n${latest.result}\n**数据文件：** data/result.csv\n[下载查询数据](/api/artifacts/id)`;
  assert.deepEqual(analysisSuggestions([query(series(20)), latest,
    { name: 'run_sql', result: 'error: failed' }]), ['attribution', 'anomaly']);
});

test('SQL editor respects the disabled state of a non-editable example', () => {
  const markup = renderToStaticMarkup(React.createElement(SqlEditor, {
    value: 'SELECT 1', onChange: () => {}, disabled: true,
  }));
  assert.match(markup, /<textarea[^>]*disabled/);
  assert.match(markup, /<button[^>]*disabled[^>]*>格式化/);
});
