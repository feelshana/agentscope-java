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
  finished?: boolean;
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
  const slash = String.fromCharCode(92);
  const lineFeed = String.fromCharCode(10);
  return s
    .split(`${slash}r${slash}n`).join(lineFeed)
    .split(`${slash}n`).join(lineFeed)
    .split(`${slash}t`).join('  ')
    .split(`${slash}"`).join('"');
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
  wren_run_sql: '查询语义模型',
  wren_query_cube: '查询业务指标',
  wren_describe_model: '查看语义模型',
  retrieve_evidence: '检索业务规则',
  read_knowledge: '读取业务规则',
  fetch_query_result: '复用查询结果',
  render_chart: '生成图表',
  run_python: '沙箱中执行 Python',
};

const TC_TOOL_ICONS: Record<string, IconName> = {
  wren_run_sql: 'database',
  wren_query_cube: 'database',
  wren_describe_model: 'search',
  retrieve_evidence: 'file',
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

function isToolError(result: string | undefined): boolean {
  return typeof result === 'string' && result.startsWith('error:');
}

export default function ToolCallBlock({
  finished = false,
  toolName,
  toolCallId,
  input,
  result,
  onInspect,
}: Props) {
  const unconfirmed = finished && result === undefined;
  const running = !finished && result === undefined;
  const failed = !running && isToolError(result);
  const label = toolLabel(toolName);

  return (
    <div className="da-toolcall">
      <button
        type="button"
        className="da-toolcall-head"
        onClick={() => onInspect?.({ id: toolCallId, name: toolName, input, result })}
        title="在侧栏查看输入与结果"
        aria-label={`查看 ${toolName} 工具详情`}
      >
        <span style={{ color: 'var(--da-text-muted)', display: 'inline-flex' }}>
          <Icon name={toolIcon(toolName)} size="sm" />
        </span>
        <span className="da-toolcall-name">{label}</span>
        <span className={`da-toolcall-status${failed ? ' failed' : ''}`}>
          {unconfirmed ? '结果未确认' : running ? (
            <>
              <span className="da-dot" />运行中
            </>
          ) : failed ? (
            <>
              <Icon name="close" size="sm" />失败
            </>
          ) : (
            <Icon name="check" size="sm" />
          )}
        </span>
        <span className="da-trace-chevron">
          <Icon name="chevron" size="sm" />
        </span>
      </button>
    </div>
  );
}
