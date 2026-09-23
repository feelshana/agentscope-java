import React from 'react';
import Icon, { IconName } from './Icon';

export interface ToolInspectPayload {
  id: string;
  name: string;
  input?: string;
  result?: string;
  pythonTools?: { input?: string; result?: string }[];
}

interface Props {
  toolName: string;
  toolCallId: string;
  input?: string;
  result?: string;
  onInspect?: (t: ToolInspectPayload) => void;
}

const INPUT_LIMIT = 2000;

function truncate(text: string): string {
  return text.length <= INPUT_LIMIT ? text : `${text.slice(0, INPUT_LIMIT)}\n…（已截断）`;
}

export function prettyInput(text: string): string {
  try {
    const parsed = JSON.parse(text);
    if (typeof parsed === 'object' && parsed !== null) {
      return truncate(JSON.stringify(parsed, null, 2));
    }
  } catch { /* not JSON */ }
  return truncate(text);
}

function unescapeText(s: string): string {
  return s.replace(/\\r\n/g, '\n').replace(/\\n/g, '\n').replace(/\\t/g, '  ').replace(/\\"/g, '"');
}

export function formatResult(text: string): string {
  try {
    const parsed = JSON.parse(text);
    if (parsed && typeof parsed === 'object' && !Array.isArray(parsed)) {
      return Object.entries(parsed as Record<string, unknown>)
        .map(([k, v]) => {
          const val = typeof v === 'string' ? unescapeText(v) : JSON.stringify(v, null, 2);
          return `**${k}**\n\n${val}`;
        })
        .join('\n\n---\n\n');
    }
    if (Array.isArray(parsed)) return `\`\`\`json\n${JSON.stringify(parsed, null, 2)}\n\`\`\``;
  } catch { /* not JSON */ }
  return unescapeText(text);
}

const TC_TOOL_LABELS: Record<string, string> = {
  query_structured_data: '查询数据',
  read_knowledge: '读取业务规则',
  fetch_query_result: '复用查询结果',
  render_chart: '生成图表',
  run_python: '沙箱中执行 Python',
  prepare_data_context: '准备字段与规则',
  run_sql: '执行 SQL',
};

const TC_TOOL_ICONS: Record<string, IconName> = {
  query_structured_data: 'database',
  read_knowledge: 'file',
  fetch_query_result: 'search',
  render_chart: 'chart',
  run_python: 'code',
};

function toolLabel(name: string): string {
  return TC_TOOL_LABELS[name] ?? name;
}

function toolIcon(name: string): IconName {
  return TC_TOOL_ICONS[name] ?? 'settings';
}

function isToolError(name: string, result: string | undefined): boolean {
  if (result === undefined) return false;
  if (name === 'query_structured_data') {
    try {
      const parsed = JSON.parse(result);
      return parsed?.status === 'FAILED' || parsed?.status === 'PARTIAL';
    } catch { return false; }
  }
  return typeof result === 'string' && result.startsWith('error:');
}

export default function ToolCallBlock({
  toolName,
  toolCallId,
  input,
  result,
  onInspect,
}: Props) {
  const running = result === undefined;
  const failed = !running && isToolError(toolName, result);
  const label = toolLabel(toolName);
  let summary = '';
  let partial = false;
  if (toolName === 'query_structured_data' && result) {
    try {
      const data = JSON.parse(result);
      partial = data.status === 'PARTIAL';
      const results = data.results as { rowCount: number; columnCount: number; truncated: boolean }[];
      summary = results?.map(r => `${r.rowCount} 行 × ${r.columnCount} 列${r.truncated ? '（截断）' : ''}`).join('；') ?? '';
    } catch { /* historical non-JSON output */ }
  }

  return (
    <div className="da-toolcall">
      <button
        type="button"
        className="da-toolcall-head"
        onClick={() => onInspect?.({ id: toolCallId, name: toolName, input, result })}
        title="在侧栏查看输入与结果"
        aria-label={`查看 ${toolName} 工具详情`}
      >
        <span style={{ color: 'var(--da-text-3)', display: 'inline-flex', flexShrink: 0 }}>
          <Icon name={toolIcon(toolName)} size="sm" />
        </span>
        <span className="da-toolcall-name">{label}</span>
        {summary && <span className="da-toolcall-summary">· {summary}</span>}
        <span className={`da-toolcall-status${failed ? ' failed' : ''}`}>
          {running ? (
            <>
              <span className="da-dot" />
            </>
          ) : failed ? (
            <Icon name="close" size="sm" />
          ) : (
            <span style={{ color: '#22c55e' }}>
              <Icon name="check" size="sm" />
            </span>
          )}
        </span>
      </button>
    </div>
  );
}
