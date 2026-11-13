import test from 'node:test';
import assert from 'node:assert/strict';
import { answerQueryRecords, questionBefore, queryRecordStatus } from '../src/utils/answerQueryRecords.ts';
const receipt = (extra = {}, data = { columns: ['n'], rows: [[0]] }) => '[WREN_QUERY_RECEIPT]' + JSON.stringify({ groupId: 'kb', sql: String.raw`SELECT 'a\nb'`, ...extra }) + '\n' + JSON.stringify(data);
const tool = (result, extra = {}) => ({ id: 'call', name: 'run_sql', result, ...extra });

test('native and wrapped queries preserve literal SQL and transport envelopes', () => {
  const sql = String.raw`SELECT 'a\nb', 'C:\temp'`;
  const raw = receipt({ sql });
  for (const t of [tool(raw), tool(JSON.stringify([raw])), tool(JSON.stringify({ content: [{ type: 'text', text: raw }] }), { name: 'wren_call_tool', input: '{"tool_name":"run_sql"}' })]) {
    const [r] = answerQueryRecords([t]);
    assert.equal(r.sql, sql); assert.equal(r.state, 'success'); assert.equal(r.rows, 1);
  }
});
test('empty, failed, pending, evicted and opaque returns have distinct states', () => {
  const entries = answerQueryRecords([tool(receipt({}, { columns: ['n'], rows: [] })), tool('Error: denied'), tool(undefined), tool('[tool.result.evicted]'), tool('old opaque response')]);
  assert.deepEqual(entries.map(queryRecordStatus), ['返回 0 行', '查询未完成', '未收到结果', '历史记录不完整', '已保存返回内容']);
});
test('error envelopes are not certified by their nested rows', () => {
  const [r] = answerQueryRecords([tool(JSON.stringify({ isError: true, content: [{ type: 'text', text: receipt() }] }))]);
  assert.equal(r.state, 'error');
});
test('cache merges use recorded identity and scope, not equal SQL', () => {
  const entries = answerQueryRecords([tool(receipt({ queryId: 'q1' })), tool(receipt({ queryId: 'q1' }), { id: 'cached' }), tool(receipt({ queryId: 'q2' }), { id: 'new' })]);
  assert.deepEqual(entries.map(x => x.queryId), ['q1', 'q2']);
  assert.equal(answerQueryRecords([tool(receipt()), tool(receipt(), { id: 'old' })]).length, 2);
  assert.equal(answerQueryRecords([tool(receipt({ queryId: 'q', groupId: 'a' })), tool(receipt({ queryId: 'q', groupId: 'b' }))]).length, 2);
});
test('samples, tool truncation and request limits are independent', () => {
  const [r] = answerQueryRecords([tool(receipt({ limit: 1000 }, { rows: [[null, '', 0]], row_count: 250, truncated: true }))]);
  assert.equal(r.rows, 1); assert.equal(r.returnedRows, 250); assert.equal(r.truncated, true); assert.equal(r.limit, 1000);
  assert.equal(r.historyTruncated, false);
  assert.equal(answerQueryRecords([tool(receipt({}, { rows: [[], []], row_count: -1 }))])[0].returnedRows, 2);
});
test('timestamps remain unknown when missing or invalid', () => {
  assert.equal(answerQueryRecords([tool(receipt())])[0].queriedAt, undefined);
  assert.equal(answerQueryRecords([tool(receipt({ queriedAt: 'bad date' }))])[0].queriedAt, undefined);
  assert.equal(answerQueryRecords([tool(receipt({ queriedAt: '2026-11-13T01:00:00Z' }))])[0].queriedAt, '2026-11-13T01:00:00Z');
});
test('legacy SQL remains readable and non-query tools never create records', () => {
  assert.equal(answerQueryRecords([tool('```sql\nSELECT 1\n```', { name: 'wren_run_sql' })])[0].sql, 'SELECT 1');
  assert.equal(answerQueryRecords([tool('old response')])[0].sql, '');
  assert.equal(answerQueryRecords([tool('{}', { name: 'render_chart' }), tool('### exit_code: 0', { name: 'run_python' }), tool('{}', { name: 'describe_model' }), tool('{}', { name: 'wren_call_tool', input: '{"tool_name":"dry_plan"}' })]).length, 0);
});
test('recovery targets the question before that answer, never a later unrelated question', () => {
  const messages = [{ role: 'user', text: '原问题' }, { role: 'assistant', text: '中间结果' }, { role: 'system', text: '' }, { role: 'assistant', text: '失败' }, { role: 'user', text: '另一个问题' }];
  assert.equal(questionBefore(messages, 3), '原问题'); assert.equal(questionBefore(messages, 1), '原问题');
  assert.equal(questionBefore(messages, 0), '');
});
