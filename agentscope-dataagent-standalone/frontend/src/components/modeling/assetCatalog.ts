import type { AssetCatalog, AssetKind, SemanticAsset } from '../../api/semanticAssets';

const SECTIONS: Record<string, string> = {
  业务名称: '业务名称', 显示名称: '业务名称', 标题: '业务名称',
  用途: '业务用途', 业务用途: '业务用途', 粒度: '数据粒度', 数据粒度: '数据粒度',
  指标: '指标', 适用范围: '适用范围', 依赖: '数据来源', 数据来源: '数据来源', 来源: '数据来源',
  JOIN: '关联', 关联: '关联', 口径: '统计口径', 统计口径: '统计口径',
  时间列: '时间列', 时间范围: '时间范围', 风险: '使用限制', 限制: '使用限制', 使用限制: '使用限制',
};

export function assetDescription(description: string | null | undefined) {
  const parts: { heading: string; lines: string[] }[] = [];
  let active: typeof parts[number] | undefined;
  for (const line of description?.trim().split(/\r?\n/) ?? []) {
    const header = /^(?:#{1,6}\s+)?(?:\*\*)?([^:：*]+?)(?:\*\*)?\s*[:：]\s*(?:\*\*)?(.*)$/.exec(line.trim());
    const heading = header && SECTIONS[header[1].trim().toUpperCase()];
    if (heading || !active) {
      active = { heading: heading || '说明', lines: [] };
      parts.push(active);
    }
    active.lines.push(header && heading ? header[2] : line);
  }
  const sections = parts.map(part => ({ heading: part.heading, text: part.lines.join('\n').trim() })).filter(part => part.text);
  const purposeLead = sections.find(part => part.heading === '业务用途')?.text.split(/[。；，\n]/, 1)[0].trim();
  return {
    title: sections.find(part => part.heading === '业务名称')?.text.split('\n')[0]
      ?? (purposeLead && purposeLead.length <= 30 ? purposeLead : undefined),
    sections: sections.filter(part => part.heading !== '业务名称'),
  };
}

export type ViewRow = {
  kind: AssetKind;
  name: string;
  draft?: SemanticAsset;
  published?: SemanticAsset;
  incomplete?: boolean;
};

export const assetKey = ({ kind, name }: Pick<ViewRow, 'kind' | 'name'>) => `${kind}:${name}`;
export const assetTask = (row: ViewRow) => {
  if (row.incomplete) return 'INCOMPLETE';
  if (!row.draft) return 'REMOVAL_PENDING';
  return row.draft.status;
};

export function assetRows(catalog: AssetCatalog): ViewRow[] {
  const rows = new Map<string, ViewRow>();
  for (const item of catalog.published) rows.set(assetKey(item), { kind: item.kind, name: item.name, published: item });
  for (const item of catalog.draft) {
    const key = assetKey(item);
    rows.set(key, { ...rows.get(key), kind: item.kind, name: item.name, draft: item });
  }
  for (const name of catalog.incompleteViews) {
    const key = assetKey({ kind: 'view', name });
    rows.set(key, { ...rows.get(key), kind: 'view', name, incomplete: true });
  }
  return [...rows.values()].sort((a, b) => a.name.localeCompare(b.name));
}
