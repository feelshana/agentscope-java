import test from 'node:test';
import assert from 'node:assert/strict';
import { CHART_REF, citedChartIds, queryDataFiles } from '../src/components/answerRefs.ts';

test('charts cited in the answer text are found by id', () => {
  const text = '关键数值\n\n![每日净销售额趋势](chart://2221f038-96b9-440c-a88d-44f81dbe61f0)\n\n整体走势…';
  assert.deepEqual([...citedChartIds(text)], ['2221f038-96b9-440c-a88d-44f81dbe61f0']);
  assert.equal(citedChartIds('没有图表').size, 0);
});

test('saved query results give one download each with their row count', () => {
  const result = '…\n**数据文件：** data/ee80de08dc9e.csv（184 行 × 2 列；run_python 中 pd.read_csv 读取）\n\n[下载查询数据](artifact://abc/ee80de08dc9e.csv)\n';
  assert.deepEqual(queryDataFiles([undefined, 'plain result', result, result]), [{ href: 'artifact://abc/ee80de08dc9e.csv', rows: '184' }]);
});

test('a chart id the assistant made up is still a chart reference, never an image', () => {
  // 2026-11-05: an answer cited chart://daily_paid_amount without calling render_chart and the
  // page showed "图片加载中" for good; such a reference is now recognised and simply left out.
  assert.equal(CHART_REF.exec('chart://daily_paid_amount')?.[1], 'daily_paid_amount');
  assert.deepEqual([...citedChartIds('结论\n\n![每日实付金额](chart://daily_paid_amount)\n')], ['daily_paid_amount']);
  assert.equal(CHART_REF.test('https://example.com/a.png'), false);
});
