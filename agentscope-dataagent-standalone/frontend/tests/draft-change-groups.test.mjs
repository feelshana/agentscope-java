import test from 'node:test';
import assert from 'node:assert/strict';
import { draftChangeGroups } from '../src/components/modeling/draftChangeGroups.ts';

test('draft changes count the business object once while retaining its file changes', () => {
  const files = [
    { path: 'knowledge/questions/q-sales.yml', category: 'EXAMPLE', kind: 'MODIFIED' },
    { path: 'views/daily_sales/metadata.yml', category: 'OTHER', kind: 'ADDED' },
    { path: 'knowledge/sql/q-sales.md', category: 'EXAMPLE', kind: 'REMOVED' },
    { path: 'views/daily_sales/query.sql', category: 'OTHER', kind: 'ADDED' },
    { path: 'models/orders/metadata.yml', category: 'MODEL', kind: 'MODIFIED' },
    { path: 'raw/docs/销售口径.md', category: 'DOCUMENT', kind: 'ADDED' },
  ];
  const groups = draftChangeGroups(files, new Map([['q-sales', '销售额是多少？']]));
  assert.deepEqual(groups.map(({ category, title }) => [category, title]), [
    ['问答', '销售额是多少？'], ['公共视图', 'daily_sales'], ['模型', 'orders'], ['业务文档', '销售口径.md'],
  ]);
  assert.deepEqual(groups.map(({ files: entries }) => entries), [[files[0], files[2]], [files[1], files[3]], [files[4]], [files[5]]]);
});
