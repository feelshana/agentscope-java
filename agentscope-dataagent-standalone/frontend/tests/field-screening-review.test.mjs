import test from 'node:test';
import assert from 'node:assert/strict';
import { getScreeningTable } from '../src/api/fieldScreening.ts';
import { canConfirmTable, confirmScreeningSelection, needsFieldReview } from '../src/components/modeling/fieldScreeningActions.ts';

const status = (datasetId, confirmed = false) => ({ datasetId, name: datasetId, total: 1, included: 1, confirmed });
const field = { name: 'name', role: 'BUSINESS', userDescribed: false, included: true };
const table = (datasetId, fields = [field]) => ({ datasetId, confirmed: false, fields });

function mockApi(t, handler) {
  const location = new URL('https://dataagent.test/');
  globalThis.window = { location };
  globalThis.sessionStorage = { getItem: () => null };
  t.mock.method(globalThis, 'fetch', async (url, options) => {
    const endpoint = new URL(String(url), location).pathname.split('/fields')[1];
    return handler(endpoint, options);
  });
}

test('only completely included tables without unresolved fields can skip manual review', () => {
  const unknown = { ...field, role: 'UNKNOWN' };
  assert.ok(needsFieldReview(unknown));
  assert.ok(canConfirmTable(table('a')));
  assert.ok(canConfirmTable(table('a', [{ ...unknown, userDescribed: true }])));
  assert.equal(canConfirmTable(table('a', [unknown])), false);
  assert.equal(canConfirmTable(table('a', [{ ...field, included: false }])), false);
  assert.equal(canConfirmTable(table('a', [])), false);
});

test('a stale or unresolved table prevents all quick-confirm writes', async t => {
  const visible = [status('a'), status('b')];
  const calls = [];
  let added = false;
  mockApi(t, (endpoint, options) => {
    calls.push([endpoint, options.method]);
    if (!endpoint) return Response.json(added ? [...visible, status('c')] : visible);
    return Response.json(table(endpoint.slice(1), [{ ...field, role: endpoint === '/b' ? 'UNKNOWN' : 'BUSINESS' }]));
  });
  await assert.rejects(confirmScreeningSelection('library', visible, () => true), /需要重新核对/);
  added = true;
  await assert.rejects(confirmScreeningSelection('library', visible, () => true), /已变化/);
  assert.ok(calls.every(([, method]) => !method));
});

test('quick confirmation uses the current choices and retries skip successfully saved tables', async t => {
  let firstSaved = false;
  let fail = true;
  const saves = [];
  mockApi(t, (endpoint, options) => {
    if (!endpoint) return Response.json([status('a', firstSaved), status('b')]);
    const id = endpoint.slice(1);
    if (options.method !== 'PUT') return Response.json(table(id));
    assert.deepEqual(JSON.parse(options.body), { updates: [], confirm: true, force: false });
    saves.push(id);
    if (id === 'b' && fail) return Response.json({ message: '保存失败' }, { status: 503 });
    if (id === 'a') firstSaved = true;
    return Response.json({ table: { ...table(id), confirmed: true }, references: [] });
  });
  const visible = [status('a'), status('b')];
  await assert.rejects(confirmScreeningSelection('library', visible, () => true), /保存失败/);
  fail = false;
  await confirmScreeningSelection('library', visible, () => true);
  assert.deepEqual(saves, ['a', 'b', 'b']);
});

test('the editor shares a pending classification request and later reads fetch again', async t => {
  let count = 0;
  mockApi(t, () => { count++; return Response.json(table('a')); });
  await Promise.all([getScreeningTable('library', 'a'), getScreeningTable('library', 'a')]);
  assert.equal(count, 1);
  await getScreeningTable('library', 'a');
  assert.equal(count, 2);
});
