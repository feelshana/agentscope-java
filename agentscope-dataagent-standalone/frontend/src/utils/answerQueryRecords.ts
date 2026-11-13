import { inputFields, inspectResult } from './toolInspectorPresentation';
import { officialToolName, record } from './wrenToolPresentation';

export interface QueryToolEntry { id: string; name: string; input?: string; result?: string }
export interface AnswerQueryRecord {
  tool: QueryToolEntry;
  state: 'unconfirmed' | 'returned' | 'success' | 'error';
  sql: string; groupId?: string; queryId?: string; queriedAt?: string;
  rows?: number; returnedRows?: number; limit?: number;
  truncated: boolean; historyTruncated: boolean;
}
const text = (value: unknown) => typeof value === 'string' && value.trim() ? value : undefined;
const count = (value: unknown) => typeof value === 'number' && Number.isSafeInteger(value) && value >= 0 ? value : undefined;

/** Present execution records, never infer business correctness or missing historical metadata. */
export function answerQueryRecords(tools: QueryToolEntry[]): AnswerQueryRecord[] {
  const out: AnswerQueryRecord[] = [];
  const seen = new Set<string>();
  for (const tool of tools) {
    const actual = officialToolName(tool.name, tool.input);
    if (!['run_sql', 'query_cube', 'wren_run_sql', 'wren_query_cube'].includes(actual)) continue;
    const display = inspectResult(actual, tool.result);
    const receipt = display.receipt;
    const input = inputFields(tool.input);
    const args = record(input.arguments) ? input.arguments : input;
    const legacySql = display.text.match(/```sql\s*\n([\s\S]*?)```/)?.[1]?.trim();
    const sql = text(receipt?.sql) ?? text(args.sql) ?? legacySql ?? '';
    const queryId = text(receipt?.queryId);
    const groupId = text(receipt?.groupId);
    if (queryId) {
      const key = JSON.stringify([groupId, queryId, sql]);
      if (seen.has(key)) continue;
      seen.add(key);
    }
    const data = record(display.data) ? display.data : undefined;
    const rows = Array.isArray(data?.rows) ? data.rows.length : undefined;
    const reportedRows = count(data?.row_count);
    const date = text(receipt?.queriedAt);
    out.push({ tool, state: display.state, sql, groupId, queryId,
      queriedAt: date && Number.isFinite(Date.parse(date)) ? date : undefined,
      rows, returnedRows: reportedRows !== undefined && rows !== undefined && reportedRows >= rows ? reportedRows : rows,
      limit: count(receipt?.limit), truncated: data?.truncated === true,
      historyTruncated: display.historyTruncated });
  }
  return out;
}

/** A failed answer belongs to its nearest preceding user message, including restored history. */
export function userMessageBefore<T extends { role: string; text: string }>(messages: T[], index: number): T | undefined {
  for (let i = index - 1; i >= 0; i--) if (messages[i].role === 'user') return messages[i];
  return undefined;
}

export function questionBefore(messages: { role: string; text: string }[], index: number): string {
  return userMessageBefore(messages, index)?.text ?? '';
}
export function queryRecordStatus(item: AnswerQueryRecord): string {
  if (item.state === 'error') return '查询未完成';
  if (item.state === 'unconfirmed') return '未收到结果';
  if (item.historyTruncated) return '历史记录不完整';
  if (item.rows !== undefined) return `返回 ${item.returnedRows} 行`;
  return '已保存返回内容';
}
