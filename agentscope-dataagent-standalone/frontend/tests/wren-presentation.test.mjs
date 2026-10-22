import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import ts from 'typescript';

const source = readFileSync(new URL('../src/utils/wrenToolPresentation.ts', import.meta.url), 'utf8');
const { outputText } = ts.transpileModule(source, {
  compilerOptions: { module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2022 },
});
const { isWrenTool, wrenToolLabel, parseWrenResult } = await import(
  `data:text/javascript;base64,${Buffer.from(outputText).toString('base64')}`);

test('native names use the same presentation as the legacy gateway', () => {
  for (const name of ['run_sql', 'query_cube', 'get_mdl', 'recall_queries', 'describe_model', 'wren_call_tool'])
    assert.equal(isWrenTool(name), true);
  assert.equal(isWrenTool('run_python'), false);
  assert.equal(isWrenTool('toString'), false);
  assert.equal(wrenToolLabel('run_sql'), '查询语义模型');
});

test('receipt with normal JSON is not truncation, including escaped SQL and wrapped history', () => {
  const receipt = { sql: "SELECT 'a\\nb'", tool: 'run_sql' };
  const payload = { columns: ['n'], rows: [{ n: 3 }], truncated: false };
  const text = `[WREN_QUERY_RECEIPT]${JSON.stringify(receipt)}\n${JSON.stringify(payload)}`;
  for (const raw of [text, JSON.stringify(text), JSON.stringify(JSON.stringify(text))]) {
    const parsed = parseWrenResult(raw);
    assert.deepEqual(parsed.receipt, receipt);
    assert.deepEqual(parsed.data, payload);
    assert.equal(parsed.error, false);
    assert.equal(parsed.note, '');
  }
});

test('official truncation and attachment notes remain visible without corrupting rows', () => {
  const result = parseWrenResult('{"rows":[{"v":null}],"truncated":true}\n结果文件：data.csv');
  assert.equal(result.data.truncated, true);
  assert.equal(result.data.rows[0].v, null);
  assert.equal(result.note, '结果文件：data.csv');
});

test('malformed output is preserved and official Error prefix is recognized', () => {
  assert.equal(parseWrenResult('[partial output').note, '[partial output');
  assert.equal(parseWrenResult('Error: Error executing tool run_sql: invalid').error, true);
  assert.equal(parseWrenResult('"error: 查询失败"').error, true);
});
