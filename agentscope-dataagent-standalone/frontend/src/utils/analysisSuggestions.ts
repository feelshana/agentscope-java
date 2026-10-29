import type { AnalysisMethod } from '../api/chat';
import { parseWrenResult } from './wrenToolPresentation';

/**
 * spec102: which "继续分析" buttons a query answer shows. A fixed rule on the answer's last
 * successful query result, never the model's choice:
 * - attribution: a numeric metric plus a date column spanning at least 2 time units;
 * - anomaly: a daily or hourly series with at least 7 points;
 * - forecast: at least 14 daily, 8 weekly or 12 monthly points (otherwise not shown).
 * Single values, detail lists without a date, failed or empty results show nothing.
 */
export interface QueryTool { name: string; result?: string }

const DATE = /^\d{4}[-/]\d{1,2}(?:[-/]\d{1,2})?(?:[ T](\d{1,2})(?::\d{2}){0,2})?/;
const ID_COLUMN = /(^|_)id$|[a-z]Id$|编号|编码|代码|账号/i;
const QUERY_TOOLS = new Set(['run_sql', 'query_cube', 'wren_run_sql', 'wren_query_cube']);

type Grain = 'hour' | 'day' | 'week' | 'month';

function cell(row: unknown, column: string, index: number): unknown {
  if (Array.isArray(row)) return row[index];
  if (row && typeof row === 'object') return (row as Record<string, unknown>)[column];
  return undefined;
}

function numeric(values: unknown[]): boolean {
  const present = values.filter(v => v !== null && v !== undefined && String(v).trim() !== '');
  return present.length > 0 && present.every(v => Number.isFinite(Number(String(v).replace(/,/g, ''))));
}

function grainOf(values: string[]): Grain {
  if (values.some(v => DATE.exec(v)?.[1] !== undefined)) return 'hour';
  if (values.every(v => /^\d{4}[-/]\d{1,2}$/.test(v))) return 'month';
  const days = [...new Set(values.map(v => Date.parse(v.slice(0, 10).replace(/\//g, '-'))))]
    .filter(Number.isFinite).sort((a, b) => a - b);
  const gaps = days.slice(1).map((d, i) => Math.round((d - days[i]) / 86400000));
  return gaps.length > 0 && gaps.every(g => g === 7) ? 'week' : 'day';
}

const FORECAST_MIN: Record<Grain, number> = { hour: Infinity, day: 14, week: 8, month: 12 };

export function analysisSuggestions(tools: QueryTool[]): AnalysisMethod[] {
  const last = [...tools].reverse().find(t => QUERY_TOOLS.has(t.name) && t.result && !t.result.startsWith('error:'));
  if (!last?.result) return [];
  const parsed = parseWrenResult(last.result);
  if (parsed.error || !parsed.data || typeof parsed.data !== 'object') return [];
  const data = parsed.data as { columns?: unknown[]; rows?: unknown[] };
  const rows = Array.isArray(data.rows) ? data.rows : [];
  const columns = (Array.isArray(data.columns) ? data.columns : [])
    .map(c => typeof c === 'string' ? c : (c as { name?: string })?.name ?? '');
  if (rows.length < 2 || columns.length < 2) return [];
  const sample = rows.slice(0, 50);
  const columnValues = columns.map((c, i) => sample.map(r => cell(r, c, i)));
  const dateIndex = columnValues.findIndex(values => {
    const text = values.filter(v => v != null).map(String);
    return text.length > 0 && text.filter(v => DATE.test(v)).length / text.length >= 0.7;
  });
  const hasMetric = columns.some((c, i) => i !== dateIndex && !ID_COLUMN.test(c) && numeric(columnValues[i]));
  if (!hasMetric || dateIndex < 0) return [];
  const dates = rows.map(r => cell(r, columns[dateIndex], dateIndex)).filter(v => v != null).map(String);
  const points = new Set(dates).size;
  const grain = grainOf(dates);
  const out: AnalysisMethod[] = [];
  if (points >= 2) out.push('attribution');
  if ((grain === 'day' || grain === 'hour') && points >= 7) out.push('anomaly');
  if (points >= FORECAST_MIN[grain]) out.push('forecast');
  return out;
}
