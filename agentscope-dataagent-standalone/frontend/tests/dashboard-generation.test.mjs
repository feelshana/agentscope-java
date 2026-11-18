import test from 'node:test';
import assert from 'node:assert/strict';
import { generationActive, generationMessages } from '../src/components/dashboardRuntime.ts';
import { dashboardMessages } from '../src/components/dashboardState.ts';

const task = change => ({ id: 'r1', status: 'RUNNING', message: '生成概览', answer: '正在生成', startedAt: '2026-11-17T10:00:00Z', ...change });
test('restored running task has one user turn and a growing bounded reply', () => {
  const current = task();
  const local = [{ id: 'generation-user-r1', role: 'user', text: current.message }];
  assert.equal(generationMessages(local, current).length, 2);
  assert.equal(generationMessages(local, { ...current, answer: '页面文件已写入' })[1].text, '页面文件已写入');
  assert.equal(generationActive(current), true);
  for (const status of ['SUCCEEDED', 'FAILED', 'CANCELLED', 'INTERRUPTED']) assert.equal(generationActive(task({ status })), false);
});
test('native completed transcript wins; repeating an older question does not remove its answer', () => {
  const recent = [{ id: 'u', role: 'user', text: '生成概览', timestampMs: Date.parse('2026-11-17T10:00:01Z') }, { id: 'a', role: 'assistant', text: '完整的原生回复' }];
  assert.deepEqual(generationMessages(recent, task({ status: 'SUCCEEDED', answer: '较短快照' })), recent);
  const old = recent.map(item => ({ ...item, timestampMs: Date.parse('2026-11-16T10:00:01Z') }));
  assert.equal(generationMessages(old, task()).length, 4);
});
test('compaction context is hidden by reserved identity, not by ordinary user wording', () => {
  const history = [{ id: 'u', role: 'USER', content: '请解释summary是什么意思' }, { id: '__compaction_summary__:generated', role: 'USER', content: 'private summary' }, { id: 'a', role: 'ASSISTANT', content: '它是摘要' }];
  assert.deepEqual(dashboardMessages(history), [{ id: 'u', role: 'user', text: '请解释summary是什么意思' }, { id: 'a', role: 'assistant', text: '它是摘要' }]);
});
