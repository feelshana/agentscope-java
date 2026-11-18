import test from 'node:test';
import assert from 'node:assert/strict';
import { DashboardQueryQueue, previewSummary, dashboardPrompt, dashboardUserText, canAutomaticallyRepair } from '../src/components/dashboardRuntime.ts';
import { dashboardBridgeMessage } from '../src/components/dashboardState.ts';

const flush = () => new Promise(resolve => setImmediate(resolve));
const report = change => ({ revision: 'r1', initialized: true, settled: true, queries: { active: 0, queued: 0, succeeded: 8, failed: 0, total: 8 }, panels: [], issues: [], ...change });

test('eight simultaneous chart queries all finish in FIFO order with at most six active', async () => {
  const states = [], started = [], release = [];
  const queue = new DashboardQueryQueue(value => states.push(value));
  const jobs = Array.from({ length: 8 }, (_, index) => queue.enqueue(() => { started.push(index); return new Promise(resolve => { release[index] = resolve; }); }));
  await flush();
  assert.deepEqual(started, [0, 1, 2, 3, 4, 5]);
  assert.equal(states.at(-1).queued, 2);
  release[0](0); release[1](1); await flush();
  assert.deepEqual(started, [0, 1, 2, 3, 4, 5, 6, 7]);
  for (let i = 2; i < 8; i++) release[i](i);
  assert.deepEqual(await Promise.all(jobs), [0, 1, 2, 3, 4, 5, 6, 7]);
  await flush();
  assert.equal(states.at(-1).succeeded, 8);
  assert.equal(states.at(-1).active, 0);
  assert.ok(states.every(value => value.active <= 6));
});

test('a failing chart releases capacity; closing discards queued work and stops callbacks', async () => {
  const states = [], started = [];
  const queue = new DashboardQueryQueue(value => states.push(value), 1);
  let release;
  const first = queue.enqueue(() => new Promise(resolve => { release = resolve; }));
  const discarded = queue.enqueue(() => { started.push('discarded'); return Promise.resolve(); }).catch(error => error.name);
  await flush(); queue.close(); const count = states.length;
  release(1); await first; await flush();
  assert.equal(await discarded, 'AbortError'); assert.deepEqual(started, []); assert.equal(states.length, count);
  await assert.rejects(queue.enqueue(() => Promise.resolve()), { name: 'AbortError' });
  const failing = new DashboardQueryQueue(() => {}, 1, 2);
  const bad = failing.enqueue(() => Promise.reject(new Error('bad SQL')));
  const good = failing.enqueue(() => Promise.resolve(2));
  await assert.rejects(bad, /bad SQL/); assert.equal(await good, 2);
  await assert.rejects(failing.enqueue(() => Promise.resolve(3)), /次数超过/);
});

test('runtime reports never equate successful queries with correct charts', () => {
  assert.match(previewSummary(report()), /图表显示待核对/);
  assert.match(previewSummary(report({ issues: [{ kind: 'query', message: '查询失败' }] })), /需要调整/);
  assert.match(previewSummary(report({ queries: { active: 6, queued: 2 } })), /排队 2/);
  assert.match(previewSummary(report({ panels: [{ id: 'a', title: '销售', state: 'ready' }, { id: 'b', title: '订单', state: 'empty' }] })), /2\/2.*1 项暂无数据/);
});

test('diagnostics are bounded, target-specific and hidden only when the suffix is valid JSON', () => {
  const data = report({ panels: Array.from({ length: 40 }, (_, i) => ({ id: 'p' + i, title: '销售'.repeat(60), state: 'error' })), issues: Array.from({ length: 12 }, (_, i) => ({ kind: 'query', panelId: 'p' + i, message: '错误'.repeat(150), sql: 'SELECT '.repeat(1000) })) });
  const prompt = dashboardPrompt('把图改成柱状图', data, { id: 'p2', title: '销售趋势' });
  assert.equal(dashboardUserText(prompt), '把图改成柱状图');
  const payload = JSON.parse(prompt.split('[DataAgent看板诊断v1]\n')[1]);
  assert.equal(payload.target.id, 'p2'); assert.equal(payload.issues.length, 1); assert.equal(payload.issues[0].panelId, 'p2');
  assert.ok(dashboardPrompt('x'.repeat(12000), data).length < 16000);
  const raw = '保留这个标记\n\n[DataAgent看板诊断v1]\n用户手写的说明';
  assert.equal(dashboardUserText(raw), raw);
});

test('automatic repair excludes unsettled, transient and permission failures', () => {
  assert.equal(canAutomaticallyRepair(report({ issues: [{ kind: 'runtime', message: 'Cannot set properties of null' }] })), true);
  assert.equal(canAutomaticallyRepair(report({ settled: false, issues: [{ kind: 'runtime', message: 'bad render' }] })), false);
  for (const message of ['需要知识库权限', '查询超时', '模型服务503', '网络失败', '查询次数超过120次']) assert.equal(canAutomaticallyRepair(report({ issues: [{ kind: 'query', message }] })), false);
});

test('panel diagnostics and targets cannot cross frames, channels or identifier bounds', () => {
  const source = {}, data = { kind: 'panel-state', channel: 'c', panelId: 'sales', title: '销售', state: 'error', error: '无效SQL' };
  assert.equal(dashboardBridgeMessage({ source, data }, source, 'c').panelId, 'sales');
  for (const change of [{ panelId: '../secret' }, { title: 'x'.repeat(121) }, { state: 'fake-ok' }]) assert.equal(dashboardBridgeMessage({ source, data: { ...data, ...change } }, source, 'c'), null);
  assert.equal(dashboardBridgeMessage({ source: {}, data }, source, 'c'), null);
  assert.equal(dashboardBridgeMessage({ source, data }, source, 'stale'), null);
  assert.equal(dashboardBridgeMessage({ source, data: { ...data, kind: 'panel-select' } }, source, 'c').kind, 'panel-select');
});
