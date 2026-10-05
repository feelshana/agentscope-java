import type { CSSProperties, ReactNode } from 'react';

/**
 * Minimal YAML syntax highlighter for the modeling asset browser (specs/030): comment lines
 * render green, keys violet, quoted string values amber. Deliberately dependency-free —
 * MDL workspace files are small documents, so a line-oriented tokenizer is enough.
 */
function renderValue(rest: string): ReactNode[] {
  const parts = rest.split(/('[^']*'|"[^"]*")/g);
  return parts.map((part, i) =>
    /^['"]/.test(part) ? (
      <span key={i} style={{ color: '#b45309' }}>
        {part}
      </span>
    ) : (
      <span key={i}>{part}</span>
    ),
  );
}

function renderLine(line: string, index: number): ReactNode {
  if (/^\s*#/.test(line)) {
    return (
      <div key={index} style={{ color: 'var(--da-success, #16a34a)' }}>
        {line}
      </div>
    );
  }
  const m = line.match(/^(\s*(?:- )+)?([^:#]+)(:)(.*)$/);
  if (m) {
    const [, dash, key, colon, rest] = m;
    return (
      <div key={index}>
        {dash}
        <span style={{ color: 'var(--da-primary, #3b6cf6)', fontWeight: 600 }}>{key}</span>
        {colon}
        {renderValue(rest)}
      </div>
    );
  }
  return <div key={index}>{renderValue(line)}</div>;
}

const preStyle: CSSProperties = {
  margin: 0,
  fontFamily: 'Consolas, "Courier New", monospace',
  fontSize: 12,
  lineHeight: 1.8,
  whiteSpace: 'pre-wrap',
  wordBreak: 'break-all',
};

/** Read-only YAML rendering with light syntax coloring; used by AssetYamlBrowser. */
export default function YamlContent({ text }: { text: string }) {
  if (!text.trim()) {
    return <div style={{ ...preStyle, color: 'var(--da-text-3)' }}>（空文件）</div>;
  }
  return <pre style={preStyle}>{text.replace(/\r\n/g, '\n').split('\n').map(renderLine)}</pre>;
}
