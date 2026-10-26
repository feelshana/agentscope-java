import { isWrenTool, parseWrenResult, record } from './wrenToolPresentation';

export type InspectorState = 'unconfirmed' | 'returned' | 'success' | 'error';

// Decode transport envelopes only; never replace escapes inside source code or SQL literals.
export function decodeToolValue(raw: string | undefined): unknown {
  let value: unknown = raw;
  for (let i = 0; i < 6; i++) {
    if (typeof value === 'string') {
      try { value = JSON.parse(value); } catch { break; }
    } else if (Array.isArray(value) && value.length === 1 && typeof value[0] === 'string') {
      value = value[0];
    } else break;
  }
  return value;
}

export function displayText(value: unknown): string {
  if (value === undefined) return '';
  if (typeof value === 'string') return value;
  return JSON.stringify(value, null, 2);
}

function hasError(value: unknown): boolean {
  return record(value) && (value.isError === true || value.ok === false || value.success === false
    || value.status === 'error' || value.status === 'failed'
    || (value.error !== undefined && value.error !== null && value.error !== false && value.error !== ''));
}

export interface InspectorResult {
  text: string;
  data?: unknown;
  receipt?: Record<string, unknown>;
  note: string;
  state: InspectorState;
  historyTruncated: boolean;
}

export function inspectResult(name: string, raw: string | undefined): InspectorResult {
  if (raw === undefined) return { text: '', note: '', state: 'unconfirmed', historyTruncated: false };
  let value = decodeToolValue(raw);
  let envelopeError = hasError(value);
  if (record(value) && Array.isArray(value.content) && value.content.every(v => record(v) && v.type === 'text')) {
    value = decodeToolValue(value.content.map(v => (v as Record<string, unknown>).text).join('\n'));
  }
  const text = displayText(value);
  const parsed = parseWrenResult(text);
  envelopeError ||= parsed.error || hasError(parsed.data) || hasError(value);
  const cliExit = text.match(/^(?:退出码\s*[:：]|### exit_code:)\s*(-?\d+)/m);
  const textError = /^(?:error\s*:|Skill not found\b|ToolExecutionException\b)/i.test(text.trim());
  const failed = envelopeError || textError || (cliExit !== null && Number(cliExit[1]) !== 0);
  const historyTruncated = /Tool output was too large .*saved to|\[tool.result.evicted\]/i.test(text)
    || /(?:\n|^)[…\.]{1,3}\s*(?:[（\[](?:已截断|truncated)[）\]])\s*$/i.test(text)
    || /(?:\n|^)\[TRUNCATED\]\s*$/i.test(text);
  const structured = record(parsed.data) || Array.isArray(parsed.data);
  const successful = (cliExit !== null && Number(cliExit[1]) === 0)
    || (record(parsed.data) && Array.isArray(parsed.data.rows))
    || (isWrenTool(name) && structured);
  return { text, data: parsed.data, receipt: parsed.receipt, note: parsed.note,
    state: failed ? 'error' : historyTruncated ? 'returned' : successful ? 'success' : 'returned', historyTruncated };
}

export function inputFields(raw: string | undefined): Record<string, unknown> {
  const value = decodeToolValue(raw);
  return record(value) ? value : {};
}

export function tableData(data: unknown): { columns: string[]; rows: unknown[][]; truncated: boolean } | null {
  if (!record(data) || !Array.isArray(data.rows)) return null;
  const rows = data.rows;
  const columns = Array.isArray(data.columns) && data.columns.length > 0
    ? data.columns.map(c => record(c) ? String(c.name ?? c.label ?? '') : String(c))
    : Array.from(new Set(rows.flatMap(r => record(r) ? Object.keys(r) : [])));
  if (!columns.length && rows.length) {
    const width = rows.reduce<number>((max, r) => Math.max(max, Array.isArray(r) ? r.length : 1), 0);
    for (let i = 0; i < width; i++) columns.push(`列 ${i + 1}`);
  }
  return { columns, rows: rows.map(r => record(r) ? columns.map(c => r[c]) : Array.isArray(r) ? r : [r]), truncated: data.truncated === true };
}

export function valueText(value: unknown): string {
  if (value === null) return 'NULL';
  if (value === undefined) return '—';
  if (typeof value === 'object') return JSON.stringify(value);
  return String(value);
}
