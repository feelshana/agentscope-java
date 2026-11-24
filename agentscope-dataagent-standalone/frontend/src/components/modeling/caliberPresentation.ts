import type { CaliberKey, CaliberRow, CaliberScope, CaliberTable } from '../../api/calibers';

const SOURCE: Record<string, string> = { document: '文档', chat: '对话', edit: '手工编辑' };

/** The caliber turn copies a document's caliber table; only this purpose opens calibers/ (spec149). */
export function caliberRequest(table: CaliberTable): string {
  const names = table.status === 'OUTDATED' && table.changedDocuments.length ? table.changedDocuments : table.documents;
  const documents = names.map(d => `《${d}》`).join('、');
  return table.listPath
    ? `请按业务文档${documents}更新模型 ${table.model} 的口径清单 ${table.listPath}：只改与文档有差异的口径，其余保持原样。`
    : `请按业务文档${documents}为模型 ${table.model} 起草口径清单。`;
}

/** Short source label; the document name goes into the cell's title. */
export function caliberSource(row: Pick<CaliberRow, 'source'>): string {
  if (!row.source) return '—';
  return SOURCE[row.source.kind] ?? row.source.kind;
}

/** "咪咕视频（视频、咪咕视频APP）", or a prompt to fill it in: tables are chosen by it (spec149 P5). */
export function keyText(key: CaliberKey | null): string {
  if (!key) return '未写（多张表时问数靠它选表）';
  return key.synonyms.length ? `${key.name}（${key.synonyms.join('、')}）` : key.name;
}

/** "省份 province_name：汇总 全国，未指定时取 全国，取值 浙江、广东". */
export function scopeText(scope: CaliberScope): string {
  const parts = [`汇总 ${scope.total ?? '未写'}`, `未指定时取 ${scope.defaultValue ?? '未写'}`];
  if (scope.values.length) parts.push(`取值 ${scope.values.join('、')}`);
  return `${scope.label ?? scope.name} ${scope.column}（维度 ${scope.name}）：${parts.join('，')}`;
}

const UNRESTRICTED = '不限';

/**
 * One line per distinct condition: columns with the same value share a line ("a、b = 全部"), so
 * the conditions that tell calibers apart are not buried under repeated summary values.
 */
export function conditionLines(row: Pick<CaliberRow, 'where'>): { column: string; text: string; unrestricted: boolean }[] {
  const lines: { columns: string[]; text: string; unrestricted: boolean }[] = [];
  for (const [column, values] of Object.entries(row.where)) {
    const unrestricted = values.length === 1 && values[0] === UNRESTRICTED;
    const text = unrestricted ? '不限' : `= ${values.join(' 或 ')}`;
    const same = lines.find(l => l.text === text);
    if (same) same.columns.push(column); else lines.push({ columns: [column], text, unrestricted });
  }
  return lines.map(l => ({ column: l.columns.join('、'), text: l.text, unrestricted: l.unrestricted }));
}

/** How many calibers of a table passed review and verification. */
export function reviewProgress(table: Pick<CaliberTable, 'rows'>): { passed: number; total: number } {
  return { passed: table.rows.filter(r => r.review === 'PASSED').length, total: table.rows.length };
}
