import test from 'node:test';
import assert from 'node:assert/strict';
import { citedChartIds, queryDataFiles } from '../src/components/answerRefs.ts';

test('charts cited in the answer text are found by id', () => {
  const text = '关键数值\n\n![每日净销售额趋势](chart://2221f038-96b9-440c-a88d-44f81dbe61f0)\n\n整体走势…';
  assert.deepEqual([...citedChartIds(text)], ['2221f038-96b9-440c-a88d-44f81dbe61f0']);
  assert.equal(citedChartIds('没有图表').size, 0);
});

test('saved query results give one download each with their row count', () => {
  const result = '…\n**数据文件：** data/ee80de08dc9e.csv（184 行 × 2 列；run_python 中 pd.read_csv 读取）\n\n[下载查询数据](artifact://abc/ee80de08dc9e.csv)\n';
  assert.deepEqual(queryDataFiles([undefined, 'plain result', result, result]), [{ href: 'artifact://abc/ee80de08dc9e.csv', rows: '184' }]);
});
