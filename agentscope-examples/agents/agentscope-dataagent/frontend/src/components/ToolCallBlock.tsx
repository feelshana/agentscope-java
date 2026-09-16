import React from 'react';
import Icon, { IconName } from './Icon';
import {
  parseSemantic,
  stageSummary,
  stageIcon,
  isFailed,
  isSemanticTool,
} from '../utils/semanticTools';

export interface ToolInspectPayload {
  id: string;
  name: string;
  input?: string;
  result?: string;
}

interface Props {
  toolName: string;
  toolCallId: string;
  input?: string;
  result?: string;
  /** Opens the tool's input/result in the side inspector panel. */
  onInspect?: (t: ToolInspectPayload) => void;
}

const INPUT_LIMIT = 2000;

function truncate(text: string): string {
  return text.length <= INPUT_LIMIT ? text : `${text.slice(0, INPUT_LIMIT)}\n…(truncated)`;
}

/**
 * Pretty-prints a tool-input JSON string so SQL and chart specs inside the
 * argument map are human-readable when inspected.
 */
export function prettyInput(text: string): string {
  try {
    const parsed = JSON.parse(text);
    if (typeof parsed === 'object' && parsed !== null) {
      return truncate(JSON.stringify(parsed, null, 2));
    }
  } catch { /* not JSON — fall through and show the raw text */ }
  return truncate(text);
}

/** Unescapes double-encoded newline/quote sequences so prose and tables render. */
function unescapeText(s: string): string {
  return s.replace(/\\r\n/g, '\n').replace(/\\n/g, '\n').replace(/\\t/g, '  ').replace(/\\"/g, '"');
}

/**
 * Turns a tool result into readable markdown. JSON objects become labelled sections
 * (key: value) with unescaped inner text; anything else is unescaped raw text.
 */
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
  } catch { /* not JSON — fall through */ }
  return unescapeText(text);
}

/** Per-tool-type icons (SVG via Icon component). */
const TOOL_ICONS: Array<[string, IconName]> = [
  ['render_chart', 'chart'],
  ['run_python', 'code'],
  ['execute_sql', 'database'],
  ['run_sql', 'database'],
  ['query_sql', 'database'],
  ['sql', 'database'],
  ['list_tables', 'table'],
  ['describe_table', 'table'],
  ['schema', 'table'],
  ['search', 'search'],
  ['read_file', 'file'],
  ['write_file', 'edit'],
  ['bash', 'settings'],
];

function toolIcon(name: string): IconName {
  if (isSemanticTool(name)) return stageIcon(name) as IconName;
  const lower = name.toLowerCase();
  for (const [key, icon] of TOOL_ICONS) {
    if (lower.includes(key)) return icon;
  }
  return 'settings';
}

export default function ToolCallBlock({
  toolName,
  toolCallId,
  input,
  result,
  onInspect,
}: Props) {
  const running = result === undefined;
  const sem = isSemanticTool(toolName) ? parseSemantic(result) : null;
  const failed = !running && isFailed(sem);
  const label = sem ? stageSummary(toolName, sem) : toolName;

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
          {running ? (
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
