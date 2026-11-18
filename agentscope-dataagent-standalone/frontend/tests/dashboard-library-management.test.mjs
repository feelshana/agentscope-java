import test from 'node:test';
import assert from 'node:assert/strict';
import { dashboardNameFromKnowledgeBase, selectDashboards, dashboardViewKey, readDashboardView, saveDashboardView } from '../src/components/dashboardLibraryState.ts';

const base = { groupId: 'g', title: '销售看板', revision: 'v1', publishedRevision: 'v1', canEdit: true, canPublish: true, verified: true, html: null, owned: true, deletionPending: false };
const boards = [
  { ...base, id: 'a', description: '重点区域负责人', updatedAt: '2026-11-15T00:00:00Z', createdAt: '2026-11-10T00:00:00Z' },
  { ...base, id: 'b', title: '会员分析', canPublish: false, owned: false, publishedRevision: null, updatedAt: '2026-11-16T00:00:00Z', createdAt: '2026-11-11T00:00:00Z' },
  { ...base, id: 'c', title: '渠道经营', revision: 'v2', updatedAt: '2026-11-17T00:00:00Z', createdAt: null },
  { ...base, id: 'd', canPublish: false, canEdit: false, owned: false, revision: 'v2', updatedAt: 'invalid', createdAt: null },
  { ...base, id: 'e', deletionPending: true, updatedAt: '2026-11-12T00:00:00Z', createdAt: null },
];
const groups = new Map([['g', { name: '零售业务' }]]);
const select = (scope = 'all', search = '', status = 'all', sort = 'updated') => selectDashboards(boards, groups, scope, search, status, sort).map(x => x.id);

test('scope, source, description and true visible status filters intersect', () => {
  assert.deepEqual(select('mine', '负责人', 'published'), ['a']);
  assert.deepEqual(select('use', '零售', 'draft'), ['b']);
  assert.deepEqual(select('all', '', 'changed'), ['c']);
  assert.deepEqual(select('all', '', 'published'), ['a', 'd']);
  assert.deepEqual(select('all', '', 'deleting'), ['e']);
  assert.deepEqual(select('mine', '没有匹配'), []);
  assert.deepEqual(select('manage'), ['c', 'a', 'e']);
  assert.deepEqual(select('use'), ['b', 'd']);
  const ownUse = [{ ...boards[0], canPublish: false, canEdit: true, owned: true }];
  assert.equal(selectDashboards(ownUse, groups, 'use', '', 'all', 'updated').length, 1);
  assert.equal(selectDashboards(ownUse, groups, 'manage', '', 'all', 'updated').length, 0);
});

test('date ordering keeps unknown legacy creation times last and never mutates input', () => {
  assert.deepEqual(select(), ['c', 'b', 'a', 'e', 'd']);
  assert.deepEqual(select('all', '', 'all', 'created'), ['b', 'a', 'c', 'd', 'e']);
  assert.deepEqual(boards.map(x => x.id), ['a', 'b', 'c', 'd', 'e']);
  const same = [{ ...boards[0], id: 'z' }, { ...boards[0], id: 'a' }];
  assert.deepEqual(selectDashboards(same, groups, 'all', '', 'all', 'name').map(x => x.id), ['a', 'z']);
});

test('view preference is isolated by subject and tolerates blocked storage', () => {
  const token = user => 'header.' + btoa(JSON.stringify({ sub: user })) + '.signature';
  const first = dashboardViewKey(token('first')), second = dashboardViewKey(token('second'));
  assert.notEqual(first, second);
  assert.equal(dashboardViewKey('malformed'), null);
  const previous = Object.getOwnPropertyDescriptor(globalThis, 'localStorage');
  const values = new Map();
  try {
    Object.defineProperty(globalThis, 'localStorage', { configurable: true, value: { getItem: key => values.get(key), setItem: (key, value) => values.set(key, value) } });
    saveDashboardView(first, 'rows');
    assert.equal(readDashboardView(first), 'rows');
    assert.equal(readDashboardView(second), 'grid');
    Object.defineProperty(globalThis, 'localStorage', { configurable: true, get() { throw Error('blocked'); } });
    assert.equal(readDashboardView(first), 'grid');
    assert.doesNotThrow(() => saveDashboardView(first, 'rows'));
  } finally {
    if (previous) Object.defineProperty(globalThis, 'localStorage', previous); else delete globalThis.localStorage;
  }
});


test('knowledge-base name fill trims, respects the title limit and preserves unicode boundaries', () => {
  assert.equal(dashboardNameFromKnowledgeBase('  销售经营  '), '销售经营');
  assert.equal(dashboardNameFromKnowledgeBase('行\n名称'), '行 名称');
  assert.equal(dashboardNameFromKnowledgeBase('长'.repeat(100)).length, 80);
  assert.equal(dashboardNameFromKnowledgeBase('a'.repeat(79) + '😀').length, 79);
});
