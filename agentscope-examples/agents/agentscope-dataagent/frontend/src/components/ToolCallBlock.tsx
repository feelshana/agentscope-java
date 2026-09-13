import React, { useState } from 'react';
import Markdown from './Markdown';
import Icon, { IconName } from './Icon';

interface Props {
  toolName: string;
  toolCallId: string;
  input?: string;
  result?: string;
  /** When true the body is expanded on mount; the parent should remount the component (change key) when the result arrives to trigger auto-expand. */
  defaultOpen?: boolean;
}

const INPUT_LIMIT = 2000;

const s: Record<string, React.CSSProperties> = {
  wrapper: {
    background: 'var(--da-app-bg)',
    border: '1px solid var(--da-border)',
    borderRadius: 9,
    margin: '0.5rem 0',
    overflow: 'hidden',
    fontSize: '0.9rem',
  },
  header: {
    display: 'flex',
    alignItems: 'center',
    gap: 10,
    padding: '0.6rem 0.9rem',
    cursor: 'pointer',
    userSelect: 'none',
    background: 'var(--da-primary-subtle)',
    borderBottom: '1px solid var(--da-border)',
  },
  status: {
    display: 'flex',
    alignItems: 'center',
    justifyContent: 'center',
    width: 18,
    height: 18,
    flexShrink: 0,
  },
  spinner: {
    width: 14,
    height: 14,
    border: '2px solid rgba(79, 70, 229, 0.22)',
    borderTop: '2px solid var(--da-primary)',
    borderRadius: '50%',
    animation: 'tcblock-spin 0.75s linear infinite',
  },
  check: {
    color: '#16a34a',
    fontWeight: 700,
    fontSize: '0.95rem',
    lineHeight: 1,
  },
  icon: { fontSize: '1rem', lineHeight: 1, flexShrink: 0 },
  name: { color: 'var(--da-primary-hover)', fontWeight: 600 },
  running: { color: 'var(--da-text-muted)', fontSize: '0.78rem', fontStyle: 'italic' },
  id: {
    color: 'var(--da-text-muted)',
    marginLeft: 'auto',
    fontSize: '0.78rem',
    fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace',
  },
  arrow: { color: 'var(--da-text-muted)', fontSize: '0.72rem', fontWeight: 700 },
  section: {
    padding: '0.85rem 1rem',
    borderTop: '1px solid var(--da-border)',
    color: 'var(--da-text-2)',
    whiteSpace: 'pre-wrap',
    wordBreak: 'break-all',
    maxHeight: 320,
    overflowY: 'auto',
    fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace',
    fontSize: '0.85rem',
    lineHeight: 1.55,
  },
  resultSection: {
    padding: '0.85rem 1rem',
    borderTop: '1px solid var(--da-border)',
    color: 'var(--da-text-2)',
    maxHeight: 420,
    overflowY: 'auto',
    fontSize: '0.9rem',
    lineHeight: 1.55,
  },
  label: {
    color: 'var(--da-text-muted)',
    fontSize: '0.72rem',
    fontWeight: 600,
    letterSpacing: '0.06em',
    textTransform: 'uppercase',
    marginBottom: 6,
    fontFamily: 'ui-sans-serif, system-ui, sans-serif',
  },
};

function truncate(text: string): string {
  return text.length <= INPUT_LIMIT ? text : `${text.slice(0, INPUT_LIMIT)}\n…(truncated)`;
}

/**
 * Pretty-prints a tool-input JSON string so SQL and chart specs inside the
 * argument map are human-readable when the block is expanded.
 */
function prettyInput(text: string): string {
  try {
    const parsed = JSON.parse(text);
    if (typeof parsed === 'object' && parsed !== null) {
      return truncate(JSON.stringify(parsed, null, 2));
    }
  } catch { /* not JSON — fall through and show the raw text */ }
  return truncate(text);
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
  const lower = name.toLowerCase();
  for (const [key, icon] of TOOL_ICONS) {
    if (lower.includes(key)) return icon;
  }
  return 'settings';
}

/**
 * CSS keyframes injected once per page load. The animation name is unique to
 * avoid clashing with any host-app stylesheets.
 */
const SPIN_STYLE = `@keyframes tcblock-spin { to { transform: rotate(360deg); } }`;

export default function ToolCallBlock({
  toolName,
  toolCallId,
  input,
  result,
  defaultOpen = false,
}: Props) {
  const [open, setOpen] = useState(defaultOpen);
  const running = result === undefined;
  return (
    <>
      <style>{SPIN_STYLE}</style>
      <div className="da-toolcall">
        <div className="da-toolcall-head" onClick={() => setOpen(o => !o)} style={{ cursor: 'pointer' }}>
          <span style={{ color: 'var(--da-primary)', display: 'inline-flex' }}>
            <Icon name={toolIcon(toolName)} size="sm" />
          </span>
          <span className="da-toolcall-name">{toolName}</span>
          <span className="da-toolcall-status">
            {running ? <span className="da-dot" /> : <Icon name="check" size="sm" />}
            {running ? '运行中' : '已完成'}
          </span>
          <span style={{ color: 'var(--da-text-muted)', fontSize: 11 }}>{open ? '▼' : '▶'}</span>
        </div>
        {open && (
          <>
            {input && <div className="da-toolcall-body">{prettyInput(input)}</div>}
            {result && (
              <div className="da-toolcall-body" style={{ fontFamily: 'var(--da-font)' }}>
                <Markdown>{result}</Markdown>
              </div>
            )}
          </>
        )}
      </div>
    </>
  );
}
