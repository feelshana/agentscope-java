import test from 'node:test';
import assert from 'node:assert/strict';
import { caliberRequest, caliberSource, conditionLines, reviewProgress } from '../src/components/modeling/caliberPresentation.ts';

const table = (over) => ({ model: 'vip_m', listPath: null, subject: null, status: 'DRAFTABLE', documents: ['会员.md', '体育.md'],
  changedDocuments: [], viewName: null, cubeName: null, errors: [], rows: [], conflicts: [], ...over });

test('a new list is drafted from every declaring document', () => {
  assert.equal(caliberRequest(table({})), '请按业务文档《会员.md》、《体育.md》为模型 vip_m 起草口径清单。');
});
test('an outdated list is updated only from the changed documents and keeps other rows', () => {
  const text = caliberRequest(table({ listPath: 'calibers/vip_m.yml', status: 'OUTDATED', changedDocuments: ['体育.md'] }));
  assert.match(text, /《体育\.md》更新模型 vip_m 的口径清单 calibers\/vip_m\.yml/);
  assert.doesNotMatch(text, /会员\.md/);
  assert.match(text, /其余保持原样/);
});
test('row sources name the document they came from', () => {
  assert.equal(caliberSource({ source: { kind: 'document', document: '会员.md' } }), '文档');
  assert.equal(caliberSource({ source: { kind: 'edit', document: null } }), '手工编辑');
  assert.equal(caliberSource({ source: null }), '—');
});
test('conditions render one column per line and mark unrestricted columns', () => {
  assert.deepEqual(conditionLines({ where: { vip_type_2: ['不限'], vip_type_3: ['钻石会员', '黄金会员'] } }), [
    { column: 'vip_type_2', text: '不限', unrestricted: true },
    { column: 'vip_type_3', text: '= 钻石会员 或 黄金会员', unrestricted: false },
  ]);
});
test('columns with the same value share one line in their first position', () => {
  assert.deepEqual(conditionLines({ where: { a: ['全部'], b: ['体育赛事'], c: ['全部'], d: ['不限'], e: ['不限'] } }), [
    { column: 'a、c', text: '= 全部', unrestricted: false },
    { column: 'b', text: '= 体育赛事', unrestricted: false },
    { column: 'd、e', text: '不限', unrestricted: true },
  ]);
});
test('review progress counts passed calibers only', () => {
  assert.deepEqual(reviewProgress({ rows: [{ review: 'PASSED' }, { review: 'VERIFY_PENDING' }, { review: 'PROBLEM' }] }), { passed: 1, total: 3 });
});

test('table selection keys and scope dimensions read as one line each (spec149 P5)', async () => {
  const { keyText, scopeText } = await import('../src/components/modeling/caliberPresentation.ts');
  assert.equal(keyText({ name: '咪咕视频', synonyms: ['视频'] }), '咪咕视频（视频）');
  assert.equal(keyText({ name: '会员', synonyms: [] }), '会员');
  assert.match(keyText(null), /未写/);
  assert.equal(scopeText({ column: 'province_name', name: 'province', label: '省份', total: '全国', defaultValue: '全国', values: ['全国', '浙江'] }),
    '省份 province_name（维度 province）：汇总 全国，未指定时取 全国，取值 全国、浙江');
  assert.match(scopeText({ column: 'channel_type', name: 'channel', label: null, total: null, defaultValue: '全部', values: [] }), /^channel channel_type（维度 channel）：汇总 未写，未指定时取 全部$/);
});

test('a list ships whole, so every unfinished caliber is named as a blocker', async () => {
  const { publishBlockers } = await import('../src/components/modeling/caliberPresentation.ts');
  const rows = [{ name: '咪咕视频会员总体', review: 'VERIFY_PENDING' }, { name: '足球通', review: 'PASSED' },
    { name: '篮球通', review: 'PROBLEM' }, { name: '中超会员', review: 'REVIEW_PENDING' }, { name: 'UFC会员', review: 'PASSED' }];
  assert.deepEqual(publishBlockers({ rows, conflicts: [{ caliber: 'UFC会员' }], scenarioReviewed: false, errors: [] }),
    ['表说明待核查', '咪咕视频会员总体（待验证）', '篮球通（有问题）', '中超会员（待核查）', 'UFC会员（冲突）']);
  assert.deepEqual(publishBlockers({ rows: [{ name: '足球通', review: 'PASSED' }], conflicts: [], scenarioReviewed: true, errors: [] }), []);
});
test('the published state says what query uses for the table now', async () => {
  const { publishedText } = await import('../src/components/modeling/caliberPresentation.ts');
  assert.match(publishedText({ published: 'NONE', publishable: false }), /不能发布/);
  assert.match(publishedText({ published: 'NONE', publishable: true }), /下次发布后/);
  assert.match(publishedText({ published: 'OLDER', publishable: false }), /不能发布.*上一版/);
  assert.match(publishedText({ published: 'CURRENT', publishable: true }), /^已发布/);
});
test('cases that can be judged but have no verdict are listed', async () => {
  const { undecided } = await import('../src/components/modeling/caliberPresentation.ts');
  const checks = [{ caliber: '咪咕视频会员总体', error: null }, { caliber: '足球通', error: null }, { caliber: '篮球通', error: '展开失败' }];
  assert.deepEqual(undecided(checks, { 足球通: 'EXPECTED' }), ['咪咕视频会员总体']);
  assert.deepEqual(undecided(null, {}), []);
});

test('finished document turns queue a caliber turn per table a document declares (spec149 P6)', async () => {
  const { autoDraftable, caliberLabel, unfinishedLists } = await import('../src/components/modeling/caliberPresentation.ts');
  const tables = [
    table({ model: 'a', status: 'DRAFTABLE', documents: ['a.md'] }),
    table({ model: 'b', status: 'DRAFTABLE', documents: [] }),
    table({ model: 'c', status: 'OUTDATED', listPath: 'calibers/c.yml', documents: ['c.md'], publishable: true }),
    table({ model: 'd', status: 'CURRENT', listPath: 'calibers/d.yml', documents: ['d.md'], publishable: false }),
  ];
  assert.deepEqual(autoDraftable(tables).map(t => t.model), ['a', 'c']);
  assert.equal(caliberLabel(tables[0]), '为 a 起草口径清单');
  assert.equal(caliberLabel(tables[2]), '按文档更新 c 的口径清单');
  assert.deepEqual(unfinishedLists(tables).map(t => t.model), ['d']);
});

test('the row edit form starts from the row and refuses what cannot compile (spec153 P1.5)', async () => {
  const { rowEditOf, columnText, splitWords, editProblems, rowEditChanged } = await import('../src/components/modeling/caliberPresentation.ts');
  const r = { name: '咪咕视频APP', group: '咪咕视频', synonyms: ['咪咕视频APP', '视频APP'], where: { client_type: ['咪咕视频'], pid1: ['小屏'] },
    note: null, pending: '待定', combine: null, unit: '人' };
  const edit = rowEditOf(r);
  assert.deepEqual(edit, { group: '咪咕视频', synonyms: ['视频APP'], where: { client_type: ['咪咕视频'], pid1: ['小屏'] }, unit: '人', note: '', pending: '待定', combine: false });
  assert.equal(rowEditChanged(r, edit), false);
  edit.where.client_type.push('咪咕视讯');
  assert.equal(rowEditChanged(r, edit), true);
  assert.equal(r.where.client_type.length, 1);
  assert.match(editProblems(edit)[0], /合并统计/);
  assert.deepEqual(editProblems({ ...edit, combine: true }), []);
  assert.deepEqual(editProblems({ ...edit, where: {} }), ['至少要有一个取数条件']);
  assert.deepEqual(editProblems({ ...edit, where: { a: [' '] } }), ['条件 a 至少要有一个取值']);
  assert.equal(columnText([{ name: 'client_type', label: '产品', values: null }], 'CLIENT_TYPE'), '产品（CLIENT_TYPE）');
  assert.equal(columnText([], 'pid1'), 'pid1');
  assert.deepEqual(splitWords('视频、 咪咕视频，视频\nAPP'), ['视频', '咪咕视频', 'APP']);
});

test('the verification page shows only the WHERE clause of a caliber segment', async () => {
  const { whereClause } = await import('../src/components/modeling/caliberPresentation.ts');
  const segment = "SELECT '咪咕视频APP' AS caliber_name, 'where 说明' AS caliber_note, cnt\nFROM t\nWHERE company_name = '咪咕视讯'\n  AND pid1 = '小屏'";
  assert.equal(whereClause(segment), "WHERE company_name = '咪咕视讯'\n  AND pid1 = '小屏'");
  assert.equal(whereClause('SELECT 1 FROM t'), null);
});

// spec153: the caliber page in steps.
const listed = (over) => table({ listPath: 'calibers/m.yml', status: 'CURRENT', scenarioReviewed: true, scenario: null,
  scopeIssues: [], ruleWarnings: [], published: 'NONE', publishable: false, ...over });
const row = (name, review, over) => ({ name, review, group: null, valueIssues: [], ...over });
const scenario = (over) => ({ business: { name: '咪咕视频', synonyms: [] }, audience: { name: '会员', synonyms: [] },
  time: { column: 'stat_month', grain: 'month', format: 'yyyyMM' }, measures: [{ column: 'cnt', name: 'cnt', label: '会员数', isDefault: true }], ...over });

test('each table is in exactly one stage and a wrong verdict sends it back to review (spec153)', async () => {
  const { caliberStage, nextStep, stageCounts } = await import('../src/components/modeling/caliberPresentation.ts');
  assert.equal(caliberStage(table({})), 'draft');
  assert.equal(caliberStage(listed({ status: 'OUTDATED' })), 'draft');
  assert.equal(caliberStage(listed({ rows: [row('a', 'PASSED'), row('b', 'REVIEW_PENDING')] })), 'review');
  assert.equal(caliberStage(listed({ scenarioReviewed: false, rows: [row('a', 'PASSED')] })), 'review');
  assert.equal(caliberStage(listed({ rows: [row('a', 'PASSED'), row('b', 'PROBLEM')] })), 'review');
  assert.equal(caliberStage(listed({ rows: [row('a', 'PASSED'), row('b', 'VERIFY_PENDING')] })), 'verify');
  assert.equal(caliberStage(listed({ rows: [row('a', 'PASSED')] })), 'ready');
  assert.deepEqual(nextStep(table({})), { label: '起草口径清单', step: 'draft' });
  // A caliber knowledge base lists tables without a document; their next step is the upload.
  assert.deepEqual(nextStep(table({ status: 'NO_DOCUMENT', documents: [] })), { label: '上传业务文档', step: 'document' });
  assert.equal(caliberStage(table({ status: 'NO_DOCUMENT', documents: [] })), 'draft');
  assert.deepEqual(nextStep(listed({ status: 'OUTDATED' })), { label: '按文档更新', step: 'draft' });
  assert.deepEqual(nextStep(listed({ scenarioReviewed: false, rows: [row('a', 'REVIEW_PENDING')] })), { label: '开始核对', step: 'review' });
  assert.deepEqual(nextStep(listed({ rows: [row('a', 'PASSED'), row('b', 'REVIEW_PENDING')] })), { label: '继续核对', step: 'review' });
  assert.deepEqual(nextStep(listed({ rows: [row('b', 'PROBLEM')] })), { label: '修改有问题的口径', step: 'review' });
  assert.deepEqual(nextStep(listed({ rows: [row('b', 'VERIFY_PENDING')] })), { label: '开始验证', step: 'verify' });
  assert.deepEqual(nextStep(listed({ rows: [row('b', 'PASSED')], published: 'NONE' })), { label: '去发布', step: 'publish' });
  assert.deepEqual(nextStep(listed({ rows: [row('b', 'PASSED')], published: 'OLDER' })), { label: '去发布', step: 'publish' });
  assert.deepEqual(nextStep(listed({ rows: [row('b', 'PASSED')], published: 'CURRENT' })), { label: '查看口径', step: 'review' });
  assert.deepEqual(stageCounts([table({}), listed({ rows: [row('b', 'VERIFY_PENDING')] }), listed({ rows: [] })]), { draft: 1, review: 0, verify: 1, ready: 1 });
});

test('the review queue puts the table description first and moves to the next item to do (spec153)', async () => {
  const { reviewQueue, nextTodo, stepProgress, SCENARIO_ITEM } = await import('../src/components/modeling/caliberPresentation.ts');
  const t = listed({ scenario: scenario(), scenarioReviewed: false, conflicts: [{ caliber: 'c' }],
    rows: [row('a', 'PASSED', { group: '会员类型' }), row('b', 'REVIEW_PENDING'), row('c', 'REVIEW_PENDING'), row('d', 'PROBLEM'), row('e', 'REVIEW_PENDING')] });
  assert.deepEqual(reviewQueue(t, 'all').map(i => [i.id, i.state]),
    [[SCENARIO_ITEM, 'todo'], ['a', 'done'], ['b', 'todo'], ['c', 'conflict'], ['d', 'problem'], ['e', 'todo']]);
  assert.equal(reviewQueue(t, 'all')[1].group, '会员类型');
  assert.equal(reviewQueue(t, 'all')[2].group, '未分组');
  assert.deepEqual(reviewQueue(t, 'todo').map(i => i.id), [SCENARIO_ITEM, 'b', 'c', 'd', 'e']);
  assert.deepEqual(reviewQueue(t, 'problem').map(i => i.id), ['c', 'd']);
  assert.deepEqual(reviewQueue(t, 'done').map(i => i.id), ['a']);
  assert.equal(nextTodo(t, 'b'), 'e');
  assert.equal(nextTodo(t, 'e'), SCENARIO_ITEM);
  assert.equal(nextTodo(listed({ rows: [row('a', 'PASSED')] }), 'a'), null);
  assert.deepEqual(stepProgress(t), { reviewed: 2, reviewTotal: 6, passed: 1, problems: 1, verifyTotal: 5 });
});

test('the publish check blocks unfinished lists and only hints at missing values (spec153)', async () => {
  const { publishCheck, caliberReleaseBlocker, selectionDirectory } = await import('../src/components/modeling/caliberPresentation.ts');
  const unfinished = listed({ model: 'vip', subject: '会员规模', scenario: scenario({ measures: [{ column: 'cnt', name: 'cnt', label: '会员数', isDefault: false }] }),
    rows: [row('a', 'REVIEW_PENDING', { valueIssues: [{ column: 'vip_type', value: '篮球通会员', kind: 'missing' }] }), row('b', 'VERIFY_PENDING'), row('c', 'PROBLEM')] });
  const done = listed({ model: 'active', subject: '活跃用户', scenario: scenario({ audience: { name: '活跃用户', synonyms: [] } }), rows: [row('x', 'PASSED')] });
  const check = publishCheck([unfinished, done, table({ model: 'new', documents: ['n.md'] }),
    table({ model: 'bare', status: 'NO_DOCUMENT', documents: [] })]);
  assert.deepEqual(check.blocking.map(i => [i.title, i.step]), [
    ['会员规模：1 项未核对', 'review'], ['会员规模：1 个口径验证不符合', 'review'], ['会员规模：1 个口径待验证', 'verify'],
    ['new：还没有口径清单', 'draft'], ['bare：还没有业务文档', 'document']]);
  assert.deepEqual(check.hints.map(i => i.title), ['会员规模：1 个取值未在数据中找到', '会员规模：没有默认指标']);
  assert.deepEqual(check.ready.map(i => i.title), ['活跃用户：口径已完成', '选表目录无重叠']);
  assert.equal(caliberReleaseBlocker([done]), null);
  assert.equal(caliberReleaseBlocker([]), null);
  assert.equal(caliberReleaseBlocker([unfinished]), '口径未完成：会员规模：1 项未核对 等 3 项');
  assert.deepEqual(selectionDirectory([unfinished]).map(d => [d.business, d.audience, d.metric, d.grain]), [['咪咕视频', '会员', '无，先问', '按月']]);
  const twin = listed({ model: 'vip2', scenario: scenario(), rows: [row('y', 'PASSED')] });
  const overlap = publishCheck([listed({ model: 'vip1', scenario: scenario(), rows: [row('z', 'PASSED')] }), twin]);
  assert.match(overlap.hints[0].title, /选表目录重叠：咪咕视频 × 会员/);
  assert.ok(!overlap.ready.some(i => i.title === '选表目录无重叠'));
});
