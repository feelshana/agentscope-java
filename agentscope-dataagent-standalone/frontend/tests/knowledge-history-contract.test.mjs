import test, { afterEach } from 'node:test';
import assert from 'node:assert/strict';
import { getHistoryOverview, historyUsers, exportHistory } from '../src/api/adminHistory.ts';
import { updateColumns, getGroupDetail, getPreview } from '../src/api/datasets.ts';
const names = ['window', 'sessionStorage', 'fetch'];
const originals = new Map(names.map(name => [name, Object.getOwnPropertyDescriptor(globalThis, name)]));
afterEach(() => { for (const [name, descriptor] of originals) { if (descriptor) Object.defineProperty(globalThis, name, descriptor); else delete globalThis[name]; } });
function setup() {
  globalThis.window = { location: new URL(`http://localhost${process.env.DATAAGENT_TEST_BASE_PATH}admin/conversation-history`) };
  globalThis.sessionStorage = { getItem: () => 'local-credential' };
}
const query = { userId: 'user/甲', query: '冷启 & 100%', from: '2026-11-01', to: '2026-11-11', sort: 'questions', direction: 'asc', page: 2, pageSize: 10 };
test('admin page and user totals keep the same filters, prefix, credential and cancellation', async () => {
  setup(); const calls = []; const signal = new AbortController().signal;
  globalThis.fetch = async (input, init) => { calls.push([new URL(input), init]); return Response.json({ items: [], totals: { sessions: 0, questions: 0, users: 0 } }); };
  await getHistoryOverview(query, signal); await historyUsers(query, 1, signal);
  for (const [url, init] of calls) {
    assert.ok(url.pathname.startsWith(`${process.env.DATAAGENT_TEST_BASE_PATH}api/admin/conversation-history/`));
    for (const key of ['userId', 'query', 'from', 'to']) assert.equal(url.searchParams.get(key), query[key]);
    assert.equal(init.headers.Authorization, 'Bearer local-credential'); assert.equal(init.signal, signal); assert.equal(init.cache, 'no-store');
  }
  assert.equal(calls[1][0].searchParams.get('page'), '1');
});
test('CSV refuses incomplete legacy indexing instead of treating an error as a download', async () => {
  setup(); globalThis.fetch = async () => new Response(null, { status: 409 });
  await assert.rejects(exportHistory(query), /索引还在整理/);
});
test('field metadata keeps labels separate from physical identifiers and reads can abort', async () => {
  setup(); const calls = []; const signal = new AbortController().signal;
  globalThis.fetch = async (input, init) => { calls.push([new URL(input), init]); return Response.json({ columns: [] }); };
  await updateColumns('ds/甲', [{ name: 'customer_id', displayName: '客户编号', description: '客户唯一标识' }]);
  await getGroupDetail('g1', signal); await getPreview('ds/甲', 20, signal);
  assert.deepEqual(JSON.parse(calls[0][1].body), [{ name: 'customer_id', displayName: '客户编号', description: '客户唯一标识' }]);
  assert.ok(calls[0][0].pathname.endsWith('ds%2F%E7%94%B2/columns'));
  assert.equal(calls[1][1].signal, signal); assert.equal(calls[2][1].signal, signal);
});
