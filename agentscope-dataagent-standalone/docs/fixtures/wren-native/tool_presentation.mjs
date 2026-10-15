/** Parser checks and a browser fixture using the actual tool components. */
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { readFile, mkdir, writeFile } from 'node:fs/promises';
import { resolve } from 'node:path';

const require = createRequire(resolve('frontend/package.json'));
const ts = require('typescript');
const esbuild = require('esbuild');
const source = await readFile('frontend/src/utils/wrenToolPresentation.ts', 'utf8');
const compiled = ts.transpileModule(source, { compilerOptions: { target: ts.ScriptTarget.ES2020, module: ts.ModuleKind.ESNext } }).outputText;
const { parseWrenResult, wrenToolLabel } = await import('data:text/javascript;base64,' + Buffer.from(compiled).toString('base64'));
const cube = { name: 'valid_revenue', base_object: 'valid_orders', measures: [{ name: 'total_revenue', expression: 'SUM(amount)', type: 'DOUBLE' }], dimensions: [{ name: 'customer_type', type: 'TINYINT' }], properties: { description: '有效订单营收，排除内部测试客户。' } };
assert.deepEqual(parseWrenResult(JSON.stringify(JSON.stringify(cube))).data, cube);
const receipt = { question: '全年营收', sql: "SELECT 'a\\nb' FROM orders", groupId: 'fixture' };
const query = '[WREN_QUERY_RECEIPT]' + JSON.stringify(receipt) + '\n' + JSON.stringify({ rows: [{ revenue: null }, { revenue: 99 }], row_count: 2, truncated: true }) + '\n已生成分析数据文件。';
assert.equal(parseWrenResult(query).receipt.sql, receipt.sql);
assert.equal(parseWrenResult(query).data.rows[0].revenue, null);
assert.equal(parseWrenResult(query).note, '已生成分析数据文件。');
assert.equal(parseWrenResult(JSON.stringify('error: 召回失败')).error, true);
assert.equal(parseWrenResult('{"broken":').note, '{"broken":');
assert.equal(wrenToolLabel('wren_call_tool', '{"tool_name":"recall_queries"}'), '召回相似问答');
assert.equal(wrenToolLabel('wren_call_tool', 'partial'), '调用知识库工具');
const cases = [
  { id: 'cube', name: 'wren_call_tool', input: JSON.stringify({ tool_name: 'describe_cube', arguments: { name: 'valid_revenue' } }), result: JSON.stringify(JSON.stringify(cube)) },
  { id: 'recall', name: 'wren_call_tool', input: JSON.stringify({ tool_name: 'recall_queries', arguments: { question: '总营业收入' } }), result: JSON.stringify({ matches: [{ nl_query: '全年总营收是多少', sql_query: 'SELECT SUM(amount) FROM orders', _distance: 0.18, path: 'knowledge/sql/query.md' }, { nl_query: '有效订单有多少笔', sql_query: 'SELECT COUNT(*) FROM orders', path: 'knowledge/sql/liked-query.md' }] }) },
  { id: 'rows', name: 'wren_call_tool', input: '{"tool_name":"run_sql"}', result: query },
  { id: 'empty', name: 'wren_call_tool', input: '{"tool_name":"recall_queries"}', result: '{"matches":[]}' },
  { id: 'error', name: 'wren_call_tool', input: '{"tool_name":"recall_queries"}', result: JSON.stringify('error: 召回失败，请重试') },
  { id: 'malformed', name: 'wren_call_tool', input: '{"tool_name":"get_mdl"}', result: '{"broken":' },
];
const out = resolve('target/tool-presentation');
await mkdir(out, { recursive: true });
await esbuild.build({ stdin: { contents: `
import React, {useState} from 'react';
import {createRoot} from 'react-dom/client';
import ToolInspector from './src/components/ToolInspector';
import ToolCallBlock from './src/components/ToolCallBlock';
import './src/global.css';
const cases = ${JSON.stringify(cases)};
function App() {
 const [tool,setTool] = useState(cases[0]);
 return <main style={{display:'flex',height:'94vh',fontFamily:'system-ui',background:'#fff',gap:24}}>
 <div style={{flex:1,padding:24}}><h2>问数工具结果</h2>{cases.map(t=><div key={t.id} style={{margin:16}}>
 <button onClick={()=>setTool(t)}>{t.id}</button>
 <ToolCallBlock toolName={t.name} toolCallId={t.id} input={t.input} result={t.result} onInspect={setTool}/></div>)}</div>
 <aside style={{width:460,maxWidth:'90vw',border:'1px solid #e2e8f0'}}><ToolInspector key={tool.id} tool={tool} onClose={()=>{}} /></aside>
 </main>;
}
createRoot(document.getElementById('root')).render(<App/>);
`, resolveDir: resolve('frontend'), sourcefile: 'fixture.tsx', loader: 'tsx' }, bundle: true, format: 'esm', outfile: out + '/index.js', define: { 'import.meta.env': '{}' } });
await writeFile(out + '/index.html', '<!doctype html><html lang="zh-CN"><meta charset="utf-8"><link rel="stylesheet" href="index.css"><title>工具展示验收</title><div id="root"></div><script type="module" src="index.js"></script></html>');
await writeFile(out + '/parser-report.json', JSON.stringify({ checks: 8, passed: true }, null, 2));
console.log('8 parser checks passed; browser fixture built at ' + out);
