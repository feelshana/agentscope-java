import test from 'node:test';
import assert from 'node:assert/strict';
import { formatReadableSql } from '../src/components/formatReadableSql.ts';

test('expanded Chinese CTEs gain readable nesting and column lines', async () => {
  const source = 'WITH 订单 AS (SELECT `来源`.order_id, `来源`.amount FROM (SELECT order_id, amount FROM data_agent.orders) AS `来源`) SELECT order_id, SUM(amount) AS 销售额 FROM 订单 GROUP BY order_id';
  const result = await formatReadableSql(source);
  assert.ok(result.split('\n').length > 12, result);
  assert.match(result, /\n\s+SELECT/);
  assert.match(result, /\n\s+`来源`\.order_id,/);
  assert.match(result, /GROUP BY\n/);
  assert.ok(result.includes('data_agent.orders'));
});

test('formatting preserves quoted text, comments and query boundaries', async () => {
  const source = `SELECT "订单日期", 'a  b '' AND SELECT' AS note FROM "订单" WHERE "状态" <> '已取消' AND amount >= 10; -- 保留业务说明\nSELECT '第二段';`;
  const result = await formatReadableSql(source);
  for (const literal of ['"订单日期"', "'a  b '' AND SELECT'", "'已取消'", '-- 保留业务说明', "'第二段'"]) assert.ok(result.includes(literal), result);
  assert.equal(result.split(';').length, source.split(';').length);
  assert.match(result, />= 10/);
});

test('unparseable SQL remains available verbatim', async () => {
  const source = "SELECT 'unterminated literal";
  assert.equal(await formatReadableSql(source), source);
});
