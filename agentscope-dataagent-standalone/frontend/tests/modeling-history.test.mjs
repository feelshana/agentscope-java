import test from 'node:test';
import assert from 'node:assert/strict';
import { confirmationRequest, recentCoachTurns, restoreModelingHistory } from '../src/components/modeling/modelingHistory.ts';
import { coachIntent, coachQuestionRequest, findCoachQuestions, isScopedQuestion, resolveCoachTarget } from '../src/components/modeling/queryCoachScope.ts';

const time = Date.parse('2026-11-20T08:00:00Z');
const entry = (id, role, content, extra) => ({ id, role, content, timestampMs: time, ...extra });
const text = (id, content) => entry(id, 'ASSISTANT', content);
const tool = (id, call, fields) => entry(id, 'TOOL', null, { toolName: 'wren_cli', toolCallId: call, ...fields });

test('a scoped request restores its user-facing label and real question link after reload', () => {
  const request = coachQuestionRequest({ id: 'q-coach-1', question: '每天的净销售额是多少？' },
    '这道题排除退款。', '这道题排除退款。');
  const [user, reply] = restoreModelingHistory([entry('q', 'USER', request.text), text('a', '已更新过滤条件。')]);
  assert.equal(user.segments[0].text, '这道题排除退款。');
  assert.equal(reply.questionId, 'q-coach-1');
  assert.equal(request.purpose, 'coach');
  assert.equal(request.questionId, reply.questionId);
});

test('saved trial and asset verification questions keep their own scope and history link', () => {
  for (const id of ['q-mikgsh5a-1', 'q-asset-mikgsh5a-2']) {
    assert.equal(isScopedQuestion(id), true);
    const request = coachQuestionRequest({ id, question: '销售额是多少？' }, '调整本题', '按支付日汇总');
    const [, reply] = restoreModelingHistory([entry('q', 'USER', request.text), text('a', '已更新。')]);
    assert.equal(reply.questionId, id);
  }
  for (const id of ['q-asset-test', '../q-test-1', 'q-' + 'a'.repeat(64) + '-1']) assert.equal(isScopedQuestion(id), false);
});

test('only clear question intent enters the editing scope and partial titles can require a choice', () => {
  const rows = [{ id: 'q-coach-a', question: '每天的净销售额是多少？' },
    { id: 'q-coach-b', question: '上周的净销售额是多少？' }];
  assert.equal(coachIntent('优化净销售额的全局口径'), undefined);
  assert.deepEqual(coachIntent('添加业务问题：本周的订单数是多少？'), { kind: 'create', text: '本周的订单数是多少？' });
  for (const title of ['帮我优化「每天的净销售额」这道题', '问数优化：每天的净销售额排除退款']) {
    assert.deepEqual(findCoachQuestions(coachIntent(title).text, rows), [rows[0]]);
  }
  assert.equal(findCoachQuestions('优化「净销售额」', rows).length, 2);
});

test('a previous scope cannot claim an ambiguous or unrecognised named question', () => {
  const rows = [{ id: 'a', question: '每天的净销售额' }, { id: 'b', question: '上周的净销售额' }];
  for (const text of ['优化「净销售额」这道题', '优化「客单价」这道题']) {
    assert.equal(resolveCoachTarget(text, rows, rows[0]).question, undefined);
    assert.equal(resolveCoachTarget(text, rows, rows[1], true).question, rows[1]);
  }
  assert.equal(resolveCoachTarget('本题排除「已退款」订单', rows, rows[0]).question, rows[0]);
  assert.equal(resolveCoachTarget('优化「上周的净销售额」这道题', rows, rows[0]).question, rows[1]);
});

test('only explicit question removal commands open the archive flow', () => {
  assert.deepEqual(coachIntent('移除这道题。'), { kind: 'remove', text: '' });
  assert.deepEqual(coachIntent('问数优化：下架示例： 「上周订单数」'), { kind: 'remove', text: '上周订单数' });
  assert.deepEqual(coachIntent('移除业务问题：每天的净销售额是多少？'), { kind: 'remove', text: '每天的净销售额是多少？' });
  assert.notEqual(coachIntent('问数优化：移除这道题 SQL 里的退款过滤')?.kind, 'remove');
  assert.notEqual(coachIntent('如果口径错了，就移除这道题')?.kind, 'remove');
});

test('coach intent gets the last two turns for that question without tool output', () => {
  const scoped = (id, label, reply) => [
    entry(label, 'USER', coachQuestionRequest({ id, question: '净销售额？' }, label, label).text),
    tool(label + '-tool', label, { toolResult: 'Tool data' }), text(label + '-reply', reply),
  ];
  const messages = restoreModelingHistory([
    ...scoped('q-coach-a', '原口径', '旧回复'),
    ...scoped('q-coach-a', '按月汇总', '已修改'),
    ...scoped('q-coach-b', '另一道题', '另一道题的回复'),
    ...scoped('q-coach-a', '核对最新 SQL', '可以核对并确认'),
  ]);
  assert.deepEqual(recentCoachTurns(messages, 'q-coach-a'), [
    { user: '按月汇总', assistant: '已修改' },
    { user: '核对最新 SQL', assistant: '可以核对并确认' },
  ]);
});

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
