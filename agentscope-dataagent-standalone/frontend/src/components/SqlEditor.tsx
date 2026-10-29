import { useMemo, useRef } from 'react';
import type { KeyboardEvent } from 'react';
import Prism from 'prismjs';
import 'prismjs/components/prism-sql';
import { highlightTokens } from './ReadableCode';

const FONT: React.CSSProperties = {
  margin: 0, padding: '10px 12px', border: 0, fontFamily: 'ui-monospace, SFMono-Regular, Consolas, "Courier New", monospace',
  fontSize: 14, lineHeight: '22px', tabSize: 2, whiteSpace: 'pre', overflowWrap: 'normal', letterSpacing: 0, boxSizing: 'border-box',
};

/** Clauses that start a new line at the top nesting level when formatting. */
const CLAUSES = ['select', 'from', 'where', 'group', 'order', 'having', 'limit', 'offset', 'union', 'join', 'left', 'right', 'inner', 'full', 'cross', 'on', 'with'];
const JOIN_PREFIX = ['left', 'right', 'inner', 'full', 'cross'];

/**
 * Puts the main clauses of a query on their own lines. Works on Prism tokens, so text inside
 * strings and comments is never touched; nested parentheses stay on their line.
 */
export function formatSql(sql: string): string {
  const tokens = Prism.tokenize(sql.trim(), Prism.languages.sql);
  let out = '';
  let depth = 0;
  let previousWord = '';
  const text = (t: string | Prism.Token): string =>
    typeof t === 'string' ? t : Array.isArray(t.content) ? t.content.map(text).join('') : text(t.content as string | Prism.Token);
  for (const token of tokens) {
    const value = text(token);
    if (typeof token === 'string') {
      for (const ch of token) { if (ch === '(') depth++; else if (ch === ')') depth = Math.max(0, depth - 1); }
      out += depth === 0 ? token.replace(/\s+/g, ' ') : token;
      continue;
    }
    if (token.type === 'punctuation') {
      if (value === '(') depth++; else if (value === ')') depth = Math.max(0, depth - 1);
    }
    const word = value.toLowerCase();
    const breaks = depth === 0 && (
      (token.type === 'keyword' && CLAUSES.includes(word) && !(word === 'join' && JOIN_PREFIX.includes(previousWord)))
      || (token.type === 'operator' && (word === 'and' || word === 'or')));
    if (breaks && out.trim()) {
      out = out.replace(/[ \t]+$/, '') + '\n' + (word === 'on' || word === 'and' || word === 'or' ? '  ' : '');
    }
    out += token.type === 'keyword' || (token.type === 'operator' && /^[a-z]+$/i.test(value)) ? value.toUpperCase() : value;
    if (token.type === 'keyword' || token.type === 'operator') previousWord = word;
  }
  return out.split('\n').map(line => line.replace(/\s+$/, '')).join('\n').trim();
}

/**
 * SQL text box with syntax colours, line numbers and code-editor keys (Tab indents, Enter keeps
 * the indentation). A transparent textarea sits on top of the highlighted text.
 */
export default function SqlEditor({ value, onChange, rows = 10, autoFocus, maxLength, ariaLabel = 'SQL' }: {
  value: string;
  onChange: (value: string) => void;
  rows?: number;
  autoFocus?: boolean;
  maxLength?: number;
  ariaLabel?: string;
}) {
  const area = useRef<HTMLTextAreaElement>(null);
  const layer = useRef<HTMLPreElement>(null);
  const gutter = useRef<HTMLDivElement>(null);
  const highlighted = useMemo(() => highlightTokens(Prism.tokenize(value, Prism.languages.sql)), [value]);
  const lines = value.split('\n').length;

  const sync = () => {
    const el = area.current;
    if (!el) return;
    if (layer.current) { layer.current.scrollTop = el.scrollTop; layer.current.scrollLeft = el.scrollLeft; }
    if (gutter.current) gutter.current.scrollTop = el.scrollTop;
  };
  const replace = (start: number, end: number, insert: string, caret: number) => {
    const next = value.slice(0, start) + insert + value.slice(end);
    if (maxLength && next.length > maxLength) return;
    onChange(next);
    requestAnimationFrame(() => { area.current?.setSelectionRange(caret, caret); });
  };
  const onKeyDown = (e: KeyboardEvent<HTMLTextAreaElement>) => {
    const el = e.currentTarget;
    const { selectionStart: start, selectionEnd: end } = el;
    if (e.key === 'Tab' && !e.shiftKey) {
      e.preventDefault();
      replace(start, end, '  ', start + 2);
    } else if (e.key === 'Enter' && !e.nativeEvent.isComposing) {
      e.preventDefault();
      const lineStart = value.lastIndexOf('\n', start - 1) + 1;
      const indent = /^[ \t]*/.exec(value.slice(lineStart, start))?.[0] ?? '';
      replace(start, end, '\n' + indent, start + 1 + indent.length);
    }
  };

  const height = rows * 22 + 20;
  return <div style={{ border: '1px solid var(--da-border)', borderRadius: 8, overflow: 'hidden', background: '#f8fafc' }}>
    <div style={{ display: 'flex', alignItems: 'center', gap: 8, padding: '4px 8px', borderBottom: '1px solid var(--da-border)', fontSize: 12, color: 'var(--da-text-muted)', background: '#f1f5f9' }}>
      <span>SQL</span><span style={{ flex: 1 }} /><span>{lines} 行</span>
      <button type="button" className="da-btn da-btn-sm" onClick={() => onChange(formatSql(value))} disabled={!value.trim()}>格式化</button>
    </div>
    <div style={{ display: 'flex', height }}>
      <div ref={gutter} aria-hidden style={{ ...FONT, padding: '10px 8px', overflow: 'hidden', textAlign: 'right', color: '#94a3b8', background: '#f1f5f9', userSelect: 'none', minWidth: 40 }}>
        {Array.from({ length: lines }, (_, n) => <div key={n}>{n + 1}</div>)}
      </div>
      <div style={{ position: 'relative', flex: 1, minWidth: 0 }}>
        <pre ref={layer} aria-hidden style={{ ...FONT, position: 'absolute', inset: 0, overflow: 'hidden', color: '#0f172a', pointerEvents: 'none' }}>
          <code>{highlighted}{value.endsWith('\n') ? ' ' : ''}</code>
        </pre>
        <textarea ref={area} aria-label={ariaLabel} value={value} autoFocus={autoFocus} maxLength={maxLength} spellCheck={false}
          autoCapitalize="off" autoCorrect="off" wrap="off" onChange={e => onChange(e.target.value)} onScroll={sync} onKeyDown={onKeyDown}
          style={{ ...FONT, position: 'absolute', inset: 0, width: '100%', height: '100%', resize: 'none', outline: 'none', overflow: 'auto',
            background: 'transparent', color: 'transparent', caretColor: '#0f172a' }} />
      </div>
    </div>
  </div>;
}
