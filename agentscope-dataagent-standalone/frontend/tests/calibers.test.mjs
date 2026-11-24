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
