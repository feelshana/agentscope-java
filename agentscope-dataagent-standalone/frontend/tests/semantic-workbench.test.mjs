import test from 'node:test';
import assert from 'node:assert/strict';
import {
  brokenRefs, canonicalText, filterText, parseRuleFile, ruleRefs, sectionOf, termText,
} from '../src/components/modeling/ruleText.ts';
import { cardRows, focusOf, joinEnds, layoutOverview, layoutWorkbench, roleOf } from '../src/components/modeling/workbenchLayout.ts';

const models = [
  { name: '演示订单', columns: [{ name: 'order_id' }, { name: 'customer_id' }, { name: 'order_status' }, { name: 'net_revenue' }] },
  { name: '演示客户', columns: [{ name: 'customer_id' }, { name: 'region' }, { name: 'member_level' }] },
];

test('rules are read by section and bullets keep the text the backend matches', () => {
  const md = '# 业务规则\n\n## Default filters\n\n- 统计「订单数、销售额」时：排除已取消订单（例外：用户明确要求包含时不排除）\n- 营收排除内部账号\n  内部账号见 dept 字段\n\n## 区域划分\n\n- 华东含上海、江苏\r\n';
  const rules = parseRuleFile('knowledge/rules/general.md', md);
  assert.equal(rules.length, 3);
  assert.deepEqual(rules[0].filter, { scope: '订单数、销售额', condition: '排除已取消订单', exception: '用户明确要求包含时不排除' });
  assert.equal(rules[1].filter, undefined, 'a bullet outside the shape stays raw');
  assert.equal(rules[1].text, '营收排除内部账号\n内部账号见 dept 字段');
  assert.equal(rules[2].heading, '区域划分');
  assert.equal(sectionOf(rules[2].heading), null, 'a self-made heading is 未归类');
  assert.equal(sectionOf('Naming conventions').label, '术语');
});

test('the page shapes round-trip through the parser', () => {
  const filter = filterText({ scope: '有效订单', condition: "排除已取消订单（演示订单.order_status <> '已取消'）", exception: '' });
  const [f] = parseRuleFile('p.md', `## Default filters\n- ${filter}\n`);
  assert.deepEqual(f.filter, { scope: '有效订单', condition: "排除已取消订单（演示订单.order_status <> '已取消'）", exception: '' });

  const term = termText({ term: '金卡客户', synonyms: ['金卡会员', '金卡客户', ' '], meaning: "演示客户.member_level = '金卡会员'" });
  assert.equal(term, `"金卡客户"（同义词："金卡会员"）= 演示客户.member_level = '金卡会员'`);
  const [t] = parseRuleFile('p.md', `## Naming conventions\n- ${term}\n`);
  assert.deepEqual(t.term, { term: '金卡客户', synonyms: ['金卡会员'], meaning: "演示客户.member_level = '金卡会员'" });

  const canonical = canonicalText({ scope: '客户订单分析', target: '客户订单明细' });
  const [c] = parseRuleFile('p.md', `## Canonical tables\n- ${canonical}\n`);
  assert.deepEqual(c.canonical, { scope: '客户订单分析', target: '客户订单明细' });
});

test('referenced fields are qualified names or unique bare columns', () => {
  assert.deepEqual(ruleRefs("演示订单.order_status <> '已取消'", models), ['演示订单.order_status']);
  assert.deepEqual(ruleRefs('按 region 统计', models), ['演示客户.region']);
  // customer_id belongs to both models, so a bare mention names neither.
  assert.deepEqual(ruleRefs('按 customer_id 关联', models), []);
  assert.deepEqual(brokenRefs('演示订单.paid 与 演示订单.order_id', models), ['演示订单.paid']);
});

const snapshot = {
  models: [
    { name: '演示订单', description: null, tableRole: 'FACT', derived: false, path: 'models/演示订单/metadata.yml', columns: [
      { name: 'order_id', type: 'VARCHAR', description: '编号', calculated: false, expression: null, primaryKey: true },
      { name: 'customer_id', type: 'VARCHAR', description: null, calculated: false, expression: null, primaryKey: false },
      { name: 'paid_amount', type: 'DECIMAL', description: null, calculated: false, expression: null, primaryKey: false },
      { name: 'order_time', type: 'TIMESTAMP', description: null, calculated: false, expression: null, primaryKey: false },
      { name: 'net_revenue', type: 'DECIMAL', description: null, calculated: true, expression: 'paid_amount - 1', primaryKey: false, semanticType: 'MEASURE' },
    ] },
    { name: '演示客户', description: null, tableRole: 'DIMENSION', derived: false, path: null, columns: [
      { name: 'customer_id', type: 'VARCHAR', description: null, calculated: false, expression: null, primaryKey: true },
      { name: 'region', type: 'VARCHAR', description: null, calculated: false, expression: null, primaryKey: false },
    ] },
  ],
  relations: [{ name: 'r', left: '演示订单', right: '演示客户', joinType: 'MANY_TO_ONE', condition: '演示订单.customer_id = 演示客户.customer_id', sourceColumns: ['customer_id'], targetColumns: ['customer_id'], description: null }],
};

test('a card lists keys and plain columns, then calculated, then related models', () => {
  const rows = cardRows(snapshot.models[0], snapshot.relations);
  assert.deepEqual(rows.map(r => `${r.kind}:${r.name}`), ['pk:order_id', 'plain:customer_id', 'plain:paid_amount', 'plain:order_time', 'calc:net_revenue', 'rel:演示客户']);
  const layout = layoutWorkbench(snapshot, new Set(), {});
  const orders = layout.cards.find(c => c.model.name === '演示订单');
  const customers = layout.cards.find(c => c.model.name === '演示客户');
  assert.ok(orders.x < customers.x, 'the many side is laid out left of the one side');
  assert.equal(layout.edges.length, 1);
  assert.deepEqual(joinEnds('MANY_TO_ONE'), ['N', '1']);
  const moved = layoutWorkbench(snapshot, new Set(), { 演示客户: { x: 900, y: 400 } });
  assert.equal(moved.cards.find(c => c.model.name === '演示客户').x, 900);
});

test('a recorded role wins; otherwise the role is inferred and marked as such', () => {
  const [orders] = snapshot.models;
  const role = name => roleOf(orders, orders.columns.find(c => c.name === name), snapshot.relations);
  assert.deepEqual(role('net_revenue'), { role: 'MEASURE', inferred: false });
  assert.deepEqual(role('customer_id'), { role: 'IDENTIFIER', inferred: true });
  assert.deepEqual(role('paid_amount'), { role: 'MEASURE', inferred: true });
  assert.deepEqual(role('order_time'), { role: 'TIME', inferred: true });
});

test('the overview keeps models folded and the focus splits neighbours by direction', () => {
  const overview = layoutOverview(snapshot, {});
  assert.equal(overview.nodes.length, 2);
  assert.equal(overview.edges.length, 1);
  const orders = overview.nodes.find(n => n.model.name === '演示订单');
  const customers = overview.nodes.find(n => n.model.name === '演示客户');
  assert.ok(orders.x < customers.x);
  const fromOrders = focusOf(snapshot, '演示订单');
  assert.deepEqual(fromOrders.outgoing.map(l => [l.model.name, l.centerColumns, l.otherColumns]), [['演示客户', ['customer_id'], ['customer_id']]]);
  assert.equal(fromOrders.incoming.length, 0);
  const fromCustomers = focusOf(snapshot, '演示客户');
  assert.deepEqual(fromCustomers.incoming.map(l => l.model.name), ['演示订单'], 'many orders point into one customer');
  assert.equal(fromCustomers.outgoing.length, 0);
  assert.equal(focusOf(snapshot, '不存在'), null);
});
