import test from 'node:test';
import assert from 'node:assert/strict';
import { readEventStream } from '../src/api/chat.ts';
import { escapeHtml } from '../src/utils/html.ts';
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

test('capacity, tool and confirmation events retain their metadata before the terminal conversation id', async () => {
  const expected = [
    { type: 'capacity', status: 'queued', position: 2 },
    { type: 'tool_call', toolName: 'wren_call_tool', toolCallId: 'tool-1', requestId: 'request-1' },
    { type: 'tool_result', toolCallId: 'tool-1', toolResult: '退出码：2\n失败' },
    { type: 'hitl_request', requestId: 'confirm-1' },
    { type: 'done', sessionKey: 'conversation-1' },
  ];
  assert.deepEqual(await collect(readEventStream(response(expected.map(event => `data: ${JSON.stringify(event)}\n\n`).join('')), 'failed')), expected);
});

test('HTTP failures preserve the operation fallback or server message', async () => {
  await assert.rejects(collect(readEventStream(Response.json({ message: '请稍后重试' }, { status: 503 }), '请求失败')), /请稍后重试: 503/);
  await assert.rejects(collect(readEventStream(new Response('upstream unavailable', { status: 502 }), '请求失败')), /请求失败: 502/);
  await assert.rejects(collect(readEventStream(new Response(null), '请求失败')), /请求失败: 200/);
});

test('a reader failure after partial output propagates instead of inventing completion', async () => {
  let reads = 0;
  const res = new Response(new ReadableStream({
    pull(controller) {
      if (reads++ === 0) controller.enqueue(encoder.encode('data: {"type":"token","data":"partial"}\n\n'));
      else controller.error(new TypeError('connection reset'));
    },
  }));
  const seen = [];
  await assert.rejects(async () => {
    for await (const event of readEventStream(res, 'failed')) seen.push(event);
  }, /connection reset/);
  assert.deepEqual(seen, [{ type: 'token', data: 'partial' }]);
});
