import test from 'node:test';
import assert from 'node:assert/strict';
import { confirmationRequest, restoreModelingHistory } from '../src/components/modeling/modelingHistory.ts';

const time = Date.parse('2026-11-20T08:00:00Z');
const entry = (id, role, content, extra) => ({ id, role, content, timestampMs: time, ...extra });
const text = (id, content) => entry(id, 'ASSISTANT', content);
const tool = (id, call, fields) => entry(id, 'TOOL', null, { toolName: 'wren_cli', toolCallId: call, ...fields });

test('recovers the user choices from a saved confirmation request without exposing its routing instructions', () => {
  const decisions = [
    { question: '哪些订单计入销售额？', answer: '排除退款订单。' },
    { question: '客户怎么关联？', answer: '按客户编号关联。\n保留无匹配的订单。' },
  ];
  const [user] = restoreModelingHistory([entry('confirmed', 'USER', confirmationRequest(decisions))]);
  assert.deepEqual(user.confirmation, decisions);
  const [manual] = restoreModelingHistory([entry('manual', 'USER', '我确定了以下口径，按北京时间统计。')]);
  assert.equal(manual.confirmation, undefined);
});

test('restores narration, tools and final answer as one automated modeling reply', () => {
  const messages = restoreModelingHistory([
    entry('request', 'USER', '请加载官方 Wren 技能，按官方 Auto-pilot 流程执行'),
    text('narration', '正在核对模型。'),
    tool('call-a', 'a', { toolInput: '{"command":"validate"}' }),
    tool('call-b', 'b', { toolInput: '{"command":"build"}' }),
    tool('result-a', 'a', { toolResult: '' }),
    tool('result-b', 'b', { toolResult: 'error: 文件缺失' }),
    text('answer', '需要补齐文件。'),
  ]);
  assert.deepEqual(messages.map(m => m.role), ['user', 'assistant']);
  assert.equal(messages[0].segments[0].text, '开始自动建模');
  const reply = messages[1];
  assert.equal(reply.startedAt, new Date(time).toISOString());
  assert.deepEqual(reply.segments.map(s => s.kind), ['text', 'tool', 'tool', 'text']);
  assert.equal(reply.segments[1].frame.state, 'done');
  assert.equal(reply.segments[2].frame.state, 'failed');
  assert.equal(reply.segments.at(-1).text, '需要补齐文件。');
});

test('preserves a partial page and only uses the newest user turn for draft recovery', () => {
  const messages = restoreModelingHistory([
    tool('unmatched', null, { toolResult: 'ok' }), text('partial', '旧答复。'),
    entry('u1', 'USER', '改规则'), text('a1', '规则已修改。'),
    entry('u2', 'USER', '补字段说明'), text('a2', '字段说明已修改。'),
  ]);
  assert.deepEqual(messages.map(m => m.id), ['unmatched', 'u1', 'a1', 'u2', 'a2']);
  assert.equal(messages[0].segments[0].frame.state, 'done');
  assert.deepEqual(messages.filter(m => m.startedAt).map(m => m.id), ['a2']);
});

test('attaches pending tool approvals to the restored reply without a new message', () => {
  const hitl = { replyId: 'approval', toolCalls: [{ id: 'b', name: 'wren_cli', input: {} }] };
  const [user, reply, ...rest] = restoreModelingHistory([
    entry('request', 'USER', '完善模型。'),
    text('narration', '正在检查。'),
    tool('without-result', 'a', { toolInput: '{}' }),
    tool('waiting', 'b', { toolInput: '{}' }),
  ], hitl);
  assert.equal(user.role, 'user');
  assert.equal(reply.hitl, hitl);
  assert.deepEqual(reply.segments.slice(1).map(s => s.frame.state), ['unrecorded', 'waiting']);
  assert.equal(rest.length, 0);
});
