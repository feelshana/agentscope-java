import test from 'node:test';
import assert from 'node:assert/strict';
import { assetDescription, assetRows, assetTask } from '../src/components/modeling/assetCatalog.ts';

test('an edited or removed draft retains the published version in the catalog', () => {
  const published = { kind: 'view', name: 'sales', status: 'PUBLISHED', hash: 'online' };
  const draft = { ...published, status: 'REVIEW_PENDING', hash: 'draft' };
  const [editing] = assetRows({ draft: [draft], published: [published], incompleteViews: [] });
  assert.equal(assetTask(editing), 'REVIEW_PENDING');
  assert.equal(editing.published.hash, 'online');
  const [removing] = assetRows({ draft: [], published: [published], incompleteViews: [] });
  assert.equal(assetTask(removing), 'REMOVAL_PENDING');
  assert.equal(removing.published, published);
  const [incomplete] = assetRows({ draft: [], published: [published], incompleteViews: ['sales'] });
  assert.equal(assetTask(incomplete), 'INCOMPLETE');
});

test('business headings keep continuation lines and preserve free-form descriptions', () => {
  const description = assetDescription('业务名称：月度会员数\n用途：核对会员规模\n\n粒度：月份 × 会员口径\n风险：口径重叠\n- 人数不可相加');
  assert.equal(description.title, '月度会员数');
  assert.deepEqual(description.sections, [
    { heading: '业务用途', text: '核对会员规模' },
    { heading: '数据粒度', text: '月份 × 会员口径' },
    { heading: '使用限制', text: '口径重叠\n- 人数不可相加' },
  ]);
  assert.deepEqual(assetDescription('旧版自由格式说明').sections, [{ heading: '说明', text: '旧版自由格式说明' }]);
  assert.equal(assetDescription('用途：按月查看在订会员数，支持多个会员口径。').title, '按月查看在订会员数');
});
