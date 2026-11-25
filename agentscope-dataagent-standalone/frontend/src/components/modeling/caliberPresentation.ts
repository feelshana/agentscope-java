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

const BLOCKING: Record<string, string> = { REVIEW_PENDING: '待核查', VERIFY_PENDING: '待验证', PROBLEM: '有问题' };

/**
 * Why the draft list cannot ship: a list publishes only as a whole, and an unfinished list blocks
 * the knowledge base's release, since a table with a list is queried only through its calibers.
 */
export function publishBlockers(table: Pick<CaliberTable, 'rows' | 'conflicts' | 'scenarioReviewed' | 'errors'>): string[] {
  const out: string[] = [];
  if (table.errors.length) out.push('清单有错误');
  if (!table.scenarioReviewed) out.push('表说明待核查');
  const conflicted = new Set(table.conflicts.map(c => c.caliber));
  for (const r of table.rows)
    if (conflicted.has(r.name)) out.push(`${r.name}（冲突）`);
    else if (BLOCKING[r.review]) out.push(`${r.name}（${BLOCKING[r.review]}）`);
  return out;
}

/** What query uses for this table now, in the person's words. */
export function publishedText(table: Pick<CaliberTable, 'published' | 'publishable'>): string {
  if (table.published === 'CURRENT') return '已发布，问数按口径名经指标取数';
  if (table.published === 'OLDER') return table.publishable ? '有改动待发布，问数暂用已发布的上一版口径' : '有改动未完成核查验证，补完前知识库不能发布，问数继续用已发布的上一版口径';
  return table.publishable ? '尚未发布，下次发布后问数按口径取数' : '尚未发布，补完核查验证前知识库不能发布';
}

/** Verification cases that can be judged but have no verdict yet. */
export function undecided(checks: { caliber: string; error: string | null }[] | null, verdicts: Record<string, string>): string[] {
  return (checks ?? []).filter(c => !c.error && !verdicts[c.caliber]).map(c => c.caliber);
}

/** The label the conversation shows for a caliber turn the platform requested. */
export function caliberLabel(table: Pick<CaliberTable, 'model' | 'listPath'>): string {
  return table.listPath ? `按文档更新 ${table.model} 的口径清单` : `为 ${table.model} 起草口径清单`;
}

/**
 * spec149 P6: tables a finished document or modeling turn leaves for a caliber turn: declared by
 * a document, and with no list yet or a document changed since the last draft.
 */
export function autoDraftable(tables: CaliberTable[]): CaliberTable[] {
  return tables.filter(t => t.documents.length > 0 && (t.status === 'DRAFTABLE' || t.status === 'OUTDATED'));
}

/** Tables whose draft list blocks the next release. */
export function unfinishedLists(tables: CaliberTable[]): CaliberTable[] {
  return tables.filter(t => t.listPath && !t.publishable);
}

/** How many calibers of a table passed review and verification. */
export function reviewProgress(table: Pick<CaliberTable, 'rows'>): { passed: number; total: number } {
  return { passed: table.rows.filter(r => r.review === 'PASSED').length, total: table.rows.length };
}
