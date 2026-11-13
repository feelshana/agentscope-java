import test from 'node:test';
import assert from 'node:assert/strict';
import React from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { DatasetFieldsTable, DatasetPreviewTable, datasetColumnLabel } from '../src/components/DatasetReadOnlyTables';

const columns = [
  { name: 'amount', originalName: '原金额', displayName: '订单金额', sqlType: 'DECIMAL(18,6)', description: '含税金额' },
  { name: 'status', originalName: '原状态', displayName: null, sqlType: 'VARCHAR', description: null },
];

test('shared sample preserves zero, null and original values without interpreting HTML', () => {
  const html = renderToStaticMarkup(React.createElement(DatasetPreviewTable, {
    columns, rows: [{ index: 3, values: ['0.000000', null] }, { index: 4, values: ['-2', '<script>bad()</script>'] }],
  }));
  assert.match(html, /0\.000000/);
  assert.match(html, />NULL</);
  assert.match(html, />4<\/td>/);
  assert.match(html, /&lt;script&gt;/);
  assert.doesNotMatch(html, /<script>/);
  assert.match(html, /订单金额/);
  assert.equal(datasetColumnLabel(columns[1]), '原状态');
});

test('shared fields keep physical and original names distinct and expose no mutation by default', () => {
  const html = renderToStaticMarkup(React.createElement(DatasetFieldsTable, { columns }));
  assert.match(html, /字段名称/);
  assert.match(html, /原字段名称/);
  assert.match(html, /<code>amount<\/code>/);
  assert.match(html, />原金额<\/td>/);
  assert.match(html, /含税金额/);
  assert.match(html, /待补充说明/);
  assert.doesNotMatch(html, /<button|<input|<textarea|订单金额/);
});

test('field-help control exposes selected column while editable extension remains opt-in', () => {
  const html = renderToStaticMarkup(React.createElement(DatasetPreviewTable, { columns, rows: [], activeFieldName: 'amount', onFieldInfo() {} }));
  assert.match(html, /aria-label="查看 订单金额 的字段说明" aria-expanded="true" aria-controls="kd-field-help"/);
  const editable = renderToStaticMarkup(React.createElement(DatasetFieldsTable, {
    columns: [columns[0]], actions: () => React.createElement('button', null, '编辑业务说明'),
    afterRow: () => React.createElement('tr', null, React.createElement('td', { colSpan: 4 }, '现有编辑表单')),
  }));
  assert.match(editable, /编辑业务说明/);
  assert.match(editable, /colSpan="4"|colspan="4"/);
  assert.match(editable, /现有编辑表单/);
});

test('compact fields combine names while retaining business descriptions and types', () => {
  const html = renderToStaticMarkup(React.createElement(DatasetFieldsTable, { columns, compact: true }));
  assert.match(html, /is-compact/);
  assert.match(html, /<strong>订单金额<\/strong><code>amount<\/code>/);
  assert.match(html, /DECIMAL\(18,6\)/);
  assert.match(html, /含税金额/);
  assert.doesNotMatch(html, /原字段名称|<button/);
});
