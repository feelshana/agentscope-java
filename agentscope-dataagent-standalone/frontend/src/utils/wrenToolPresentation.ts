export function record(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === 'object' && !Array.isArray(value);
}

export const WREN_LABELS: Record<string, string> = {
  run_sql: '查询语义模型', query_cube: '查询业务指标', recall_queries: '召回相似问答',
  get_context: '检索相关模型', get_instructions: '读取业务规则',
  list_models: '查看模型目录', describe_model: '查看模型结构',
  list_cubes: '查看指标目录', describe_cube: '查看指标定义',
  list_knowledge: '查看知识目录', list_stored_queries: '查看已存问答',
  describe_schema: '查看语义结构', get_mdl: '查看语义模型',
  get_data_source: '查看数据源类型', list_functions: '查看可用函数',
  dry_plan: '查看查询计划', dry_run: '校验查询',
};

export function officialToolName(name: string, input?: string): string {
  if (name !== 'wren_call_tool') return name;
  try {
    const parsed: unknown = JSON.parse(input ?? '{}');
    return record(parsed) && typeof parsed.tool_name === 'string' ? parsed.tool_name : name;
  } catch { return name; }
}

export function wrenToolLabel(name: string, input?: string): string | undefined {
  if (name === 'wren_list_tools') return '查看可用工具';
  if (name === 'wren_read_knowledge') return '读取知识内容';
  if (name !== 'wren_call_tool') return undefined;
  const actual = officialToolName(name, input);
  return WREN_LABELS[actual] ?? (actual === name ? '调用知识库工具' : `知识库工具 · ${actual}`);
}

// Parse JSON strings without replacing escapes inside SQL or other source text.
function unwrap(text: string): string {
  for (let i = 0; i < 3; i++) {
    try {
      const parsed: unknown = JSON.parse(text);
      if (typeof parsed !== 'string') break;
      text = parsed;
    } catch { break; }
  }
  return text;
}

export interface WrenDisplayResult {
  data?: unknown;
  receipt?: Record<string, unknown>;
  note: string;
  error: boolean;
}

export function parseWrenResult(raw: string): WrenDisplayResult {
  let text = unwrap(raw).trim();
  let receipt: Record<string, unknown> | undefined;
  const prefix = '[WREN_QUERY_RECEIPT]';
  if (text.startsWith(prefix)) {
    const end = text.indexOf('\n');
    try {
      const parsed: unknown = JSON.parse(text.slice(prefix.length, end < 0 ? undefined : end));
      if (record(parsed)) {
        receipt = parsed;
        text = end < 0 ? '' : text.slice(end + 1).trim();
      }
    } catch { /* Preserve malformed receipts in the original text. */ }
  }
  text = unwrap(text).trim();
  if (text.startsWith('error:')) return { receipt, note: text.slice(6).trim(), error: true };
  try { return { receipt, data: JSON.parse(text) as unknown, note: '', error: false }; }
  catch { /* A query can append an artifact note after its JSON payload. */ }
  if (text.startsWith('{') || text.startsWith('[')) {
    let depth = 0;
    let quoted = false;
    let escaped = false;
    for (let i = 0; i < text.length; i++) {
      const c = text[i];
      if (quoted) {
        if (escaped) escaped = false;
        else if (c === '\\') escaped = true;
        else if (c === '"') quoted = false;
      } else if (c === '"') quoted = true;
      else if (c === '{' || c === '[') depth++;
      else if (c === '}' || c === ']') {
        if (--depth === 0) {
          try {
            return { receipt, data: JSON.parse(text.slice(0, i + 1)) as unknown,
              note: text.slice(i + 1).trim(), error: false };
          } catch { break; }
        }
      }
    }
  }
  return { receipt, note: text, error: false };
}
