import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import ts from 'typescript';

function moduleUrl(path, rewrite = s => s) {
  const source = rewrite(readFileSync(new URL(path, import.meta.url), 'utf8'));
  const { outputText } = ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2022 } });
  return `data:text/javascript;base64,${Buffer.from(outputText).toString('base64')}`;
}
const wren = moduleUrl('../src/utils/wrenToolPresentation.ts');
const { decodeToolValue, inspectResult, inputFields, tableData, valueText } = await import(moduleUrl(
  '../src/utils/toolInspectorPresentation.ts', s => s.replace("'./wrenToolPresentation'", JSON.stringify(wren)),
));

test('nested JSON strings and native text arrays are decoded', () => {
  assert.deepEqual(decodeToolValue(JSON.stringify([JSON.stringify({ chartId: 'test-chart' })])), { chartId: 'test-chart' });
});
test('SQL escapes remain literal source characters', () => {
  const sql = String.raw`SELECT 'a\nb' AS text_value, 'C:\temp' AS path`;
  assert.equal(inputFields(JSON.stringify({ arguments: { sql } })).arguments.sql, sql);
  assert.equal(inspectResult('read_file', JSON.stringify(sql)).text, sql);
});
test('quoted render_chart JSON produces a structured preview payload', () => {
  const result = inspectResult('render_chart', JSON.stringify(JSON.stringify({ chart: 'echarts', chartId: 'id' })));
  assert.equal(result.data.chartId, 'id');
  assert.equal(result.state, 'returned');
});
test('missing receipt and empty returned string have distinct states', () => {
  assert.equal(inspectResult('run_python', undefined).state, 'unconfirmed');
  assert.equal(inspectResult('read_file', '').state, 'returned');
});
test('CLI exit codes and skill failures are not marked successful', () => {
  assert.equal(inspectResult('wren_cli', JSON.stringify('退出码：0\nValid')).state, 'success');
  assert.equal(inspectResult('wren_cli', JSON.stringify('退出码：2\nInvalid')).state, 'error');
  assert.equal(inspectResult('run_python', '### exit_code: -1\nTimeout').state, 'error');
  assert.equal(inspectResult('load_skill_through_path', JSON.stringify('Skill not found: missing')).state, 'error');
});
test('MCP text envelopes preserve explicit error metadata', () => {
  const result = inspectResult('wren_call_tool', JSON.stringify({ isError: true, content: [{ type: 'text', text: 'connection lost' }] }));
  assert.equal(result.state, 'error');
  assert.equal(result.text, 'connection lost');
});
test('query receipts retain executed SQL and valid empty rows', () => {
  const sql = 'SELECT COUNT(*) FROM orders';
  const result = inspectResult('wren_call_tool', '[WREN_QUERY_RECEIPT]' + JSON.stringify({ sql }) + '\n' + JSON.stringify({ rows: [], columns: ['n'] }));
  assert.equal(result.receipt.sql, sql);
  assert.equal(result.state, 'success');
  assert.deepEqual(tableData(result.data).rows, []);
});
test('appended artifact notes remain available', () => {
  const result = inspectResult('wren_call_tool', '{"rows":[{"n":100}]}\n[下载查询数据](/api/artifacts/test/content)');
  assert.equal(result.data.rows[0].n, 100);
  assert.match(result.note, /下载查询数据/);
});
test('backend truncation is distinct from a normal empty result', () => {
  const result = inspectResult('wren_call_tool', '{"rows":[],"columns":["n"],"truncated":true}');
  assert.equal(result.historyTruncated, false);
  assert.equal(tableData(result.data).truncated, true);
});
test('malformed JSON alone does not invent history truncation', () => {
  const result = inspectResult('wren_call_tool', '{"rows":[');
  assert.equal(result.historyTruncated, false);
  assert.equal(result.state, 'returned');
  assert.equal(result.note, '{"rows":[');
});
test('explicit evicted and truncated markers are retained', () => {
  assert.equal(inspectResult('wren_call_tool', 'Tool output was too large (10,000 chars) and has been saved to `path`.').historyTruncated, true);
  assert.equal(inspectResult('read_file', 'partial\n[TRUNCATED]').historyTruncated, true);
});
test('object rows follow declared column order and preserve leading zeros', () => {
  const data = tableData({ columns: [{ name: 'code' }, { name: 'n' }], rows: [{ n: 0, code: '000001' }] });
  assert.deepEqual(data.rows, [['000001', 0]]);
});
test('empty columns derive object keys rather than losing values', () => {
  assert.deepEqual(tableData({ columns: [], rows: [{ a: false, b: null }] }), { columns: ['a', 'b'], rows: [[false, null]], truncated: false });
});
test('array rows receive neutral column labels only when absent', () => {
  assert.deepEqual(tableData({ rows: [[1, 2]] }).columns, ['列 1', '列 2']);
});
test('NULL, false, zero and empty strings are not conflated', () => {
  assert.equal(valueText(null), 'NULL'); assert.equal(valueText(undefined), '—');
  assert.equal(valueText(false), 'false'); assert.equal(valueText(0), '0'); assert.equal(valueText(''), '');
});
test('ordinary content mentioning errors is not treated as execution failure', () => {
  assert.equal(inspectResult('read_file', 'A guide explains error handling.').state, 'returned');
  assert.equal(inspectResult('wren_call_tool', '{"rows":[{"error":"business label"}]}').state, 'success');
});
