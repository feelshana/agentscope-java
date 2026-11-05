/**
 * spec117 业务规则: rules stay Markdown bullets under `## ` sections of knowledge/rules/*.md (the
 * only source of truth). The page writes fixed sentence shapes and reads them back into columns;
 * a bullet outside the shapes keeps its raw text and stays editable.
 */

export type RuleKind = 'filter' | 'term' | 'canonical' | 'other';

export interface RuleSection {
  /** The `## ` heading as written in the file. */
  heading: string;
  label: string;
  kind: RuleKind;
  /** Headings the official usage skill routes to (see SemanticContextPanel). */
  official: boolean;
  hint: string;
}

export interface FilterFields { scope: string; condition: string; exception: string }
export interface TermFields { term: string; synonyms: string[]; meaning: string }
export interface CanonicalFields { scope: string; target: string }

export interface RuleItem {
  path: string;
  /** null for bullets written before any `## ` heading. */
  heading: string | null;
  /** The bullet text exactly as the backend matches it (continuation lines joined by \n). */
  text: string;
  kind: RuleKind;
  filter?: FilterFields;
  term?: TermFields;
  canonical?: CanonicalFields;
}

export const SECTIONS: RuleSection[] = [
  { heading: 'Default filters', label: '默认过滤', kind: 'filter', official: true, hint: '统计时默认排除或只保留哪些记录；用户明确要求时可以放开' },
  { heading: 'Naming conventions', label: '术语', kind: 'term', official: true, hint: '业务说法对应哪张表、字段或条件' },
  { heading: 'Canonical tables', label: '指定取数表', kind: 'canonical', official: true, hint: '某类问题固定从哪张表或视图取数，例如：统计会员消费时默认用"客户订单明细"视图，不再自己拼表' },
  { heading: 'Metrics', label: '指标口径', kind: 'other', official: true, hint: '指标的计算方式（升级 Wren 后可转为指标）' },
  { heading: 'External identifiers', label: '外部编码', kind: 'other', official: true, hint: '外部系统编码与本地字段的对应' },
  { heading: 'Currency', label: '货币', kind: 'other', official: true, hint: '金额的币种与单位' },
];
const BY_HEADING = new Map(SECTIONS.map(s => [s.heading, s]));
export const sectionOf = (heading: string | null): RuleSection | null => heading ? BY_HEADING.get(heading.trim()) ?? null : null;

const isBullet = (line: string) => line.startsWith('- ') || line.startsWith('* ');
const isContinuation = (line: string) => line.startsWith('  ') && line.trim() !== '';

/** Same reading as DraftItemService#bulletText, so `text` round-trips as `original`. */
export function parseRuleFile(path: string, content: string): RuleItem[] {
  const out: RuleItem[] = [];
  const lines = content.split('\n').map(l => l.replace(/\r$/, ''));
  let heading: string | null = null;
  for (let i = 0; i < lines.length; i++) {
    const line = lines[i];
    const h = /^##\s+(.+?)\s*$/.exec(line);
    if (h) { heading = h[1]; continue; }
    if (/^#\s/.test(line)) continue;
    if (!isBullet(line)) continue;
    const parts = [line.slice(2).trim()];
    while (i + 1 < lines.length && isContinuation(lines[i + 1])) parts.push(lines[++i].trim());
    out.push(readRule(path, heading, parts.join('\n')));
  }
  return out;
}

const FILTER = /^统计「(.+?)」时：([\s\S]+?)(?:（例外：([\s\S]+?)）)?[。.]?$/;
const CANONICAL = /^统计「(.+?)」时默认使用\s*([\s\S]+?)[。.]?$/;
// Mirrors BusinessTermService.ENTRY (spec100): "术语"（同义词："甲"、"乙"）= 解释
const TERM = /^"([^"]+)"\s*(?:[（(]\s*同义词\s*[:：]\s*([^）)]*)[）)])?\s*[=＝]\s*([\s\S]+)$/;

export function readRule(path: string, heading: string | null, text: string): RuleItem {
  const section = sectionOf(heading);
  const kind: RuleKind = section?.kind ?? 'other';
  const item: RuleItem = { path, heading, text, kind };
  if (kind === 'filter') {
    const m = FILTER.exec(text);
    if (m) item.filter = { scope: m[1].trim(), condition: m[2].trim(), exception: (m[3] ?? '').trim() };
  } else if (kind === 'term') {
    const m = TERM.exec(text);
    if (m) {
      const quoted = [...(m[2] ?? '').matchAll(/"([^"]+)"/g)].map(q => q[1].trim());
      const synonyms = quoted.length ? quoted : splitList(m[2] ?? '');
      item.term = { term: m[1].trim(), synonyms, meaning: m[3].trim() };
    }
  } else if (kind === 'canonical') {
    const m = CANONICAL.exec(text);
    if (m) item.canonical = { scope: m[1].trim(), target: m[2].trim() };
  }
  return item;
}

export const splitList = (raw: string) => [...new Set(raw.split(/[,，、;；\n]/).map(s => s.replace(/"/g, '').trim()).filter(Boolean))];

const oneLine = (value: string) => value.replace(/\s*\n\s*/g, ' ').trim();

export function filterText(f: FilterFields) {
  const exception = oneLine(f.exception);
  return `统计「${oneLine(f.scope)}」时：${oneLine(f.condition)}${exception ? `（例外：${exception}）` : ''}`;
}
export function termText(t: TermFields) {
  const synonyms = t.synonyms.map(s => s.replace(/"/g, '').trim()).filter(s => s && s !== t.term.trim());
  return `"${oneLine(t.term).replace(/"/g, '')}"${synonyms.length ? `（同义词：${synonyms.map(s => `"${s}"`).join('、')}）= ` : ' = '}${oneLine(t.meaning)}`;
}
export function canonicalText(c: CanonicalFields) {
  return `统计「${oneLine(c.scope)}」时默认使用 ${oneLine(c.target)}`;
}

/** A short single line for lists. */
export function ruleSummary(rule: RuleItem) {
  if (rule.filter) return `${rule.filter.scope}：${rule.filter.condition}`;
  if (rule.term) return `"${rule.term.term}" = ${rule.term.meaning}`;
  if (rule.canonical) return `${rule.canonical.scope} → ${rule.canonical.target}`;
  return rule.text.split('\n')[0];
}

/**
 * Fields a rule mentions: every `模型.字段` that exists, plus bare column names that belong to
 * exactly one model (so `region` alone still counts, `id` shared by many does not).
 */
export function ruleRefs(text: string, models: { name: string; columns: { name: string }[] }[]): string[] {
  const refs = new Set<string>();
  const owners = new Map<string, string[]>();
  for (const m of models) for (const c of m.columns) owners.set(c.name, [...(owners.get(c.name) ?? []), m.name]);
  for (const m of models) {
    for (const c of m.columns) {
      if (text.includes(`${m.name}.${c.name}`)) refs.add(`${m.name}.${c.name}`);
    }
  }
  for (const [column, list] of owners) {
    if (list.length !== 1 || column.length < 3) continue;
    const word = new RegExp(`(^|[^\\p{L}\\p{N}_.])${column.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}($|[^\\p{L}\\p{N}_])`, 'u');
    if (word.test(text)) refs.add(`${list[0]}.${column}`);
  }
  return [...refs];
}

/** `模型.字段` written in a rule that no draft model has; shown under 需要处理. */
export function brokenRefs(text: string, models: { name: string; columns: { name: string }[] }[]): string[] {
  const out: string[] = [];
  for (const m of models) {
    for (const hit of text.matchAll(new RegExp(`${m.name.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}\\.([\\p{L}\\p{N}_]+)`, 'gu'))) {
      if (!m.columns.some(c => c.name === hit[1])) out.push(`${m.name}.${hit[1]}`);
    }
  }
  return [...new Set(out)];
}
