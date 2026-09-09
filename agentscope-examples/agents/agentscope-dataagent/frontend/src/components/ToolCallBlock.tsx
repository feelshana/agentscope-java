import React, { useState } from 'react';
import Markdown from './Markdown';

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
    background: '#f8fafc',
    border: '1px solid #e2e8f0',
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
    background: '#eef2ff',
    borderBottom: '1px solid #e2e8f0',
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
    border: '2px solid #c7d2fe',
    borderTop: '2px solid #6366f1',
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
  name: { color: '#3730a3', fontWeight: 600 },
  running: { color: '#94a3b8', fontSize: '0.78rem', fontStyle: 'italic' },
  id: {
    color: '#94a3b8',
    marginLeft: 'auto',
    fontSize: '0.78rem',
    fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace',
  },
  arrow: { color: '#94a3b8', fontSize: '0.72rem', fontWeight: 700 },
  section: {
    padding: '0.85rem 1rem',
    borderTop: '1px solid #e2e8f0',
    color: '#334155',
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
    borderTop: '1px solid #e2e8f0',
    color: '#334155',
    maxHeight: 420,
    overflowY: 'auto',
    fontSize: '0.9rem',
    lineHeight: 1.55,
  },
  label: {
    color: '#94a3b8',
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

/** Per-tool-type icons shown next to the tool name in the header. */
const TOOL_ICONS: Array<[string, string]> = [
  ['render_chart', '📊'],
  ['execute_sql', '🗄️'],
  ['run_sql', '🗄️'],
  ['query_sql', '🗄️'],
  ['sql', '🗄️'],
  ['list_tables', '📋'],
  ['describe_table', '📋'],
  ['schema', '📋'],
  ['search', '🔍'],
  ['read_file', '📄'],
  ['write_file', '✍️'],
  ['bash', '⚙️'],
];

function toolIcon(name: string): string {
  const lower = name.toLowerCase();
  for (const [key, icon] of TOOL_ICONS) {
    if (lower.includes(key)) return icon;
  }
  return '🔧';
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
      <div style={s.wrapper}>
        <div style={s.header} onClick={() => setOpen(o => !o)}>
          <span style={s.status}>
            {running ? <span style={s.spinner} /> : <span style={s.check}>✓</span>}
          </span>
          <span style={s.icon}>{toolIcon(toolName)}</span>
          <span style={s.name}>Tool: {toolName}</span>
          {running && <span style={s.running}>Running…</span>}
          <span style={s.arrow}>{open ? '▼' : '▶'}</span>
          <span style={s.id}>{toolCallId.slice(0, 10)}</span>
        </div>
        {open && (
          <>
            {input && (
              <div style={s.section}>
                <div style={s.label}>input</div>
                {prettyInput(input)}
              </div>
            )}
            {result && (
              <div style={s.resultSection}>
                {input && <div style={s.label}>result</div>}
                <Markdown>{result}</Markdown>
              </div>
            )}
          </>
        )}
      </div>
    </>
  );
}
