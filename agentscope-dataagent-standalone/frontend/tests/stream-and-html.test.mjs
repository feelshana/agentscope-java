import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import ts from 'typescript';

// Compile the actual small TS modules in memory; no browser, server, or added test dependency.
function moduleUrl(path, rewrite = source => source) {
  const source = rewrite(readFileSync(new URL(path, import.meta.url), 'utf8'));
  const { outputText } = ts.transpileModule(source, {
    compilerOptions: { module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2022 },
  });
  return `data:text/javascript;base64,${Buffer.from(outputText).toString('base64')}`;
}
const auth = moduleUrl('../src/api/auth.ts');
const { readEventStream } = await import(moduleUrl('../src/api/chat.ts', s => s.replace("'./auth'", JSON.stringify(auth))));
const { escapeHtml } = await import(moduleUrl('../src/utils/html.ts'));
const encoder = new TextEncoder();
function response(text) {
  const bytes = encoder.encode(text);
  return new Response(new ReadableStream({ start(controller) {
    // Deliberately split UTF-8 characters and CRLF boundaries across chunks.
    for (const byte of bytes) controller.enqueue(new Uint8Array([byte]));
    controller.close();
  } }));
}
async function collect(stream) { const result = []; for await (const event of stream) result.push(event); return result; }

test('CRLF, multi-line JSON, Chinese UTF-8 and terminal frame without blank line', async () => {
  const events = await collect(readEventStream(response(': heartbeat\r\n\r\ndata: {"type":"token",\r\ndata: "data":"中文"}\r\n\r\ndata: {"type":"done"}'), 'failed'));
  assert.deepEqual(events, [{ type: 'token', data: '中文' }, { type: 'done' }]);
});
test('EOF without terminal event is an interruption, not success', async () => {
  await assert.rejects(collect(readEventStream(response('data: {"type":"token","data":"partial"}\n\n'), 'failed')), /连接已中断/);
});
test('invalid frame fails explicitly and server error is a terminal event', async () => {
  await assert.rejects(collect(readEventStream(response('data: {broken}\n\n'), 'failed')), /数据格式错误/);
  assert.deepEqual(await collect(readEventStream(response('data: {"type":"error","error":"busy"}\n\n'), 'failed')), [{ type: 'error', error: 'busy' }]);
});
test('early consumer exit cancels the reader', async () => {
  let cancelled = false;
  const res = new Response(new ReadableStream({
    start(c) { c.enqueue(encoder.encode('data: {"type":"token","data":"one"}\n\n')); },
    cancel() { cancelled = true; },
  }));
  for await (const event of readEventStream(res, 'failed')) { assert.equal(event.type, 'token'); break; }
  assert.equal(cancelled, true);
});
test('dataset and model text cannot introduce tooltip markup', () => {
  assert.equal(escapeHtml('<img src=x onerror="alert(1)"> & \'test\''), '&lt;img src=x onerror=&quot;alert(1)&quot;&gt; &amp; &#39;test&#39;');
  assert.equal(escapeHtml('营业收入（万元）'), '营业收入（万元）');
  assert.equal(escapeHtml(null), '');
});
