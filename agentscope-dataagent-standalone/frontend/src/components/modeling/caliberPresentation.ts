import type { CaliberColumn, CaliberKey, CaliberRow, CaliberRowEdit, CaliberScope, CaliberTable } from '../../api/calibers';

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

// spec153: the caliber page in steps (draft → review → verify → publish).

export type CaliberStage = 'draft' | 'review' | 'verify' | 'ready';
export type CaliberStep = 'review' | 'verify';
/** Where a next-step button leads: a step of this page, a caliber turn, the documents page or the release. */
export type CaliberAction = CaliberStep | 'draft' | 'document' | 'publish';
type StageTable = Pick<CaliberTable, 'listPath' | 'status' | 'errors' | 'rows' | 'conflicts' | 'scenarioReviewed'>;

/** The one stage a table is in; a caliber judged wrong goes back to review to be changed. */
export function caliberStage(table: StageTable): CaliberStage {
  if (!table.listPath || table.status === 'OUTDATED' || table.errors.length) return 'draft';
  if (!table.scenarioReviewed || table.conflicts.length || table.rows.some(r => r.review === 'REVIEW_PENDING' || r.review === 'PROBLEM')) return 'review';
  if (table.rows.some(r => r.review === 'VERIFY_PENDING')) return 'verify';
  return 'ready';
}

/** Review counts the table description as one item; verification counts calibers judged as expected. */
export function stepProgress(table: Pick<CaliberTable, 'rows' | 'scenarioReviewed' | 'scenario'>) {
  const hasScenario = table.scenario !== null;
  const reviewed = table.rows.filter(r => r.review !== 'REVIEW_PENDING').length + (hasScenario && table.scenarioReviewed ? 1 : 0);
  return {
    reviewed, reviewTotal: table.rows.length + (hasScenario ? 1 : 0),
    passed: table.rows.filter(r => r.review === 'PASSED').length,
    problems: table.rows.filter(r => r.review === 'PROBLEM').length,
    verifyTotal: table.rows.length,
  };
}

/** The single next button of a table card. */
export function nextStep(table: StageTable & Partial<Pick<CaliberTable, 'published'>>): { label: string; step: CaliberAction } {
  if (table.status === 'NO_DOCUMENT') return { label: '上传业务文档', step: 'document' };
  const stage = caliberStage(table);
  if (stage === 'draft') return table.errors.length && table.status !== 'OUTDATED' && table.listPath
    ? { label: '查看清单问题', step: 'review' }
    : { label: table.listPath ? '按文档更新' : '起草口径清单', step: 'draft' };
  if (stage === 'review') {
    const started = table.scenarioReviewed || table.rows.some(r => r.review !== 'REVIEW_PENDING');
    return { label: table.rows.some(r => r.review === 'PROBLEM') ? '修改有问题的口径' : started ? '继续核对' : '开始核对', step: 'review' };
  }
  if (stage === 'verify') return { label: '开始验证', step: 'verify' };
  // Finished but not yet what query uses: the next step is the knowledge base's release.
  return table.published === 'CURRENT' ? { label: '查看口径', step: 'review' } : { label: '去发布', step: 'publish' };
}

/** How many tables wait in each stage, for the overview counters. */
export function stageCounts(tables: StageTable[]): Record<CaliberStage, number> {
  const out: Record<CaliberStage, number> = { draft: 0, review: 0, verify: 0, ready: 0 };
  for (const t of tables) out[caliberStage(t)]++;
  return out;
}

export const SCENARIO_ITEM = '__scenario';
export type QueueFilter = 'todo' | 'problem' | 'done' | 'all';
export type QueueState = 'todo' | 'problem' | 'conflict' | 'done';

/** A caliber's place in the review queue: conflicts and wrong verdicts need a change first. */
export function queueState(row: Pick<CaliberRow, 'name' | 'review'>, conflicted: ReadonlySet<string>): QueueState {
  if (conflicted.has(row.name)) return 'conflict';
  if (row.review === 'PROBLEM') return 'problem';
  return row.review === 'REVIEW_PENDING' ? 'todo' : 'done';
}

const FILTERS: Record<QueueFilter, (state: QueueState) => boolean> = {
  todo: s => s !== 'done', problem: s => s === 'problem' || s === 'conflict', done: s => s === 'done', all: () => true,
};

type QueueTable = Pick<CaliberTable, 'rows' | 'conflicts' | 'scenario' | 'scenarioReviewed'>;

/** Queue items in list order, the table description first; group headings follow the rows' groups. */
export function reviewQueue(table: QueueTable, filter: QueueFilter) {
  const conflicted = new Set(table.conflicts.map(c => c.caliber));
  const items: { id: string; name: string; group: string | null; state: QueueState }[] = [];
  if (table.scenario) items.push({ id: SCENARIO_ITEM, name: '表说明', group: null, state: table.scenarioReviewed ? 'done' : 'todo' });
  for (const r of table.rows) items.push({ id: r.name, name: r.name, group: r.group ?? '未分组', state: queueState(r, conflicted) });
  return items.filter(i => FILTERS[filter](i.state));
}

/** The item after {@code current} that is still to review, wrapping around; null when none is left. */
export function nextTodo(table: QueueTable, current: string | null): string | null {
  const all = reviewQueue(table, 'all');
  const start = Math.max(0, all.findIndex(i => i.id === current));
  for (let k = 1; k <= all.length; k++) {
    const item = all[(start + k) % all.length];
    if (item.state === 'todo') return item.id;
  }
  return null;
}

/** One row of the table selection directory (business × audience → table). */
export function selectionDirectory(tables: CaliberTable[]) {
  return tables.filter(t => t.scenario).map(t => {
    const s = t.scenario!;
    const measure = s.measures.find(m => m.isDefault);
    return {
      model: t.model, business: s.business?.name ?? '未写', audience: s.audience?.name ?? '未写',
      metric: measure ? measure.label ?? measure.column : '无，先问', hasDefault: !!measure,
      grain: s.time ? (s.time.grain === 'month' ? '按月' : '按日') : '—',
    };
  });
}

export interface CheckItem { title: string; detail: string; model?: string; step?: Exclude<CaliberAction, 'publish'> }

/**
 * spec153 publish check: blocking items stop the release; hints never do (a value missing from test
 * data is only a hint, the document wins).
 */
export function publishCheck(tables: CaliberTable[]): { blocking: CheckItem[]; hints: CheckItem[]; ready: CheckItem[] } {
  const blocking: CheckItem[] = []; const hints: CheckItem[] = []; const ready: CheckItem[] = [];
  for (const t of tables) {
    const name = t.subject ?? t.model;
    const p = stepProgress(t);
    if (t.status === 'NO_DOCUMENT') {
      blocking.push({ title: `${name}：还没有业务文档`, detail: '口径问数知识库的每张表都要有口径清单；上传写明“适用模型”的业务文档后由助手起草。', model: t.model, step: 'document' });
      continue;
    }
    if (!t.listPath) {
      blocking.push({ title: `${name}：还没有口径清单`, detail: '由助手照抄业务文档中的口径表起草。', model: t.model, step: 'draft' });
      continue;
    }
    if (t.status === 'OUTDATED') blocking.push({ title: `${name}：文档有更新`,
      detail: `文档${t.changedDocuments.map(d => `《${d}》`).join('、')}在上次起草后有变化，需按文档更新。`, model: t.model, step: 'draft' });
    if (t.errors.length) blocking.push({ title: `${name}：清单有错误`, detail: t.errors.join('；'), model: t.model, step: 'review' });
    if (t.conflicts.length) blocking.push({ title: `${name}：${t.conflicts.length} 个口径有冲突`,
      detail: `文档版本与人改过的版本不同：${t.conflicts.map(c => c.caliber).join('、')}。`, model: t.model, step: 'review' });
    const unreviewed = p.reviewTotal - p.reviewed;
    if (unreviewed > 0) blocking.push({ title: `${name}：${unreviewed} 项未核对`,
      detail: t.scenario && !t.scenarioReviewed ? '包括表说明。' : '核对后才能验证。', model: t.model, step: 'review' });
    if (p.problems) blocking.push({ title: `${name}：${p.problems} 个口径验证不符合`, detail: '已退回核对，修改后重新核对和验证。', model: t.model, step: 'review' });
    const unjudged = t.rows.filter(r => r.review === 'VERIFY_PENDING').length;
    if (unjudged) blocking.push({ title: `${name}：${unjudged} 个口径待验证`, detail: '每个口径一道验证问题，由你判定是否符合。', model: t.model, step: 'verify' });
    const values = t.rows.reduce((n, r) => n + r.valueIssues.length, 0) + t.scopeIssues.length;
    if (values) hints.push({ title: `${name}：${values} 个取值未在数据中找到`, detail: '当前数据可能不全，以文档为准，不影响发布。', model: t.model, step: 'review' });
    if (t.scenario && !t.scenario.measures.some(m => m.isDefault)) hints.push({ title: `${name}：没有默认指标`,
      detail: '问数时没说指标，助手会先问用哪个。', model: t.model, step: 'review' });
    for (const w of t.ruleWarnings) hints.push({ title: `${name}：规则提示`, detail: w, model: t.model, step: 'review' });
    if (caliberStage(t) === 'ready') ready.push({ title: `${name}：口径已完成`, detail: `${t.rows.length} 个口径已核对并验证。` });
  }
  const seen = new Map<string, string>();
  let overlap = false;
  for (const d of selectionDirectory(tables)) {
    const key = `${d.business} × ${d.audience}`;
    const other = seen.get(key);
    if (other) {
      overlap = true;
      hints.push({ title: `选表目录重叠：${key}`, detail: `${other} 与 ${d.model} 的业务和统计对象相同，问数时无法区分。`, model: d.model, step: 'review' });
    } else seen.set(key, d.model);
  }
  if (seen.size && !overlap) ready.push({ title: '选表目录无重叠', detail: '每个“业务 × 统计对象”只对应一张表。' });
  return { blocking, hints, ready };
}

// spec153 P1.5: editing one caliber on the review page.

/** The form's starting values: the row as shown, condition order kept. */
export function rowEditOf(row: CaliberRow): CaliberRowEdit {
  return {
    group: row.group ?? '', synonyms: row.synonyms.filter(s => s !== row.name),
    where: Object.fromEntries(Object.entries(row.where).map(([k, v]) => [k, [...v]])),
    unit: row.unit ?? '', note: row.note ?? '', pending: row.pending ?? '', combine: row.combine === 'sum',
  };
}

/** "产品（client_type）", or the column name when the model has no display name. */
export function columnText(columns: CaliberColumn[], name: string): string {
  const label = columns.find(c => c.name.toLowerCase() === name.toLowerCase())?.label;
  return label ? `${label}（${name}）` : name;
}

/** Words typed as "a、b，c" or one per line. */
export function splitWords(text: string): string[] {
  return [...new Set(text.split(/[、，,;；\n]/).map(s => s.trim()).filter(Boolean))];
}

/** What keeps the form from saving, in the order a person would fix it. */
export function editProblems(edit: CaliberRowEdit): string[] {
  const out: string[] = [];
  const entries = Object.entries(edit.where);
  if (!entries.length) out.push('至少要有一个取数条件');
  for (const [column, values] of entries) if (!values.some(v => v.trim())) out.push(`条件 ${column} 至少要有一个取值`);
  if (entries.some(([, v]) => v.length > 1) && !edit.combine)
    out.push('有条件选了多个取值：统计结果表的行不能随意相加，业务确认这些取值可以相加时勾选“合并统计”，否则拆成多个口径');
  return out;
}

/** Whether the form differs from the row as saved. */
export function rowEditChanged(row: CaliberRow, edit: CaliberRowEdit): boolean {
  return JSON.stringify(rowEditOf(row)) !== JSON.stringify(edit);
}

/**
 * The WHERE clause of a caliber's segment in the generated View, or null when it has none: the
 * segment starts with label columns (caliber_name, caliber_condition…) that read like filters but
 * only tag the rows. Quoted text is skipped, so a value containing "where" is not taken for it.
 */
export function whereClause(segment: string): string | null {
  let at = -1;
  let quoted = false;
  for (let i = 0; i < segment.length; i++) {
    const c = segment[i];
    if (c === "'") { quoted = !quoted; continue; }
    if (!quoted && /^where\b/i.test(segment.slice(i, i + 6)) && (i === 0 || /\s/.test(segment[i - 1]))) at = i;
  }
  return at < 0 ? null : segment.slice(at).trim();
}

/** The release blocker shown on the draft bar, or null when every list is finished. */
export function caliberReleaseBlocker(tables: CaliberTable[] | null | undefined): string | null {
  if (!tables?.length) return null;
  const { blocking } = publishCheck(tables);
  if (!blocking.length) return null;
  return blocking.length === 1 ? `口径未完成：${blocking[0].title}` : `口径未完成：${blocking[0].title} 等 ${blocking.length} 项`;
}
