import React, { useState, useMemo } from 'react';

interface JsonViewerProps {
  data: string;
  maxHeight?: number;
}

type JsonValue = string | number | boolean | null | JsonValue[] | { [key: string]: JsonValue };

function formatValue(value: JsonValue, indent: number = 0): React.ReactNode {
  const indentStr = '  '.repeat(indent);

  if (value === null) {
    return <span className="da-json-null">null</span>;
  }
  if (typeof value === 'boolean') {
    return <span className="da-json-bool">{value.toString()}</span>;
  }
  if (typeof value === 'number') {
    return <span className="da-json-number">{value.toString()}</span>;
  }
  if (typeof value === 'string') {
    // Handle escaped newlines and other escape sequences
    const formatted = value
      .replace(/\\n/g, '\n')
      .replace(/\\t/g, '  ')
      .replace(/\\"/g, '"')
      .replace(/\\\\/g, '\\');
    return <span className="da-json-string">"{formatted}"</span>;
  }
  if (Array.isArray(value)) {
    if (value.length === 0) {
      return <span>[]</span>;
    }
    return (
      <span>
        [
        {value.map((item, i) => (
          <span key={i}>
            {i > 0 && ','}
            {'\n'}{indentStr}  {formatValue(item, indent + 1)}
          </span>
        ))}
        {'\n'}{indentStr}]
      </span>
    );
  }
  if (typeof value === 'object') {
    const entries = Object.entries(value as Record<string, JsonValue>);
    if (entries.length === 0) {
      return <span>{'{}'}</span>;
    }
    return (
      <span>
        {'{'}
        {entries.map(([key, val], i) => (
          <span key={key}>
            {i > 0 && ','}
            {'\n'}{indentStr}  <span className="da-json-key">"{key}"</span>: {formatValue(val, indent + 1)}
          </span>
        ))}
        {'\n'}{indentStr}{'}'}
      </span>
    );
  }
  return null;
}

export default function JsonViewer({ data, maxHeight = 400 }: JsonViewerProps) {
  const [expanded, setExpanded] = useState(true);

  const parsed = useMemo(() => {
    try {
      return JSON.parse(data) as JsonValue;
    } catch {
      return null;
    }
  }, [data]);

  if (parsed === null) {
    return (
      <div className="da-insp-code" style={{ color: 'var(--da-danger)', fontStyle: 'italic' }}>
        JSON 解析失败
      </div>
    );
  }

  return (
    <div className="da-json-viewer">
      <button
        type="button"
        className="da-json-toggle"
        onClick={() => setExpanded(e => !e)}
      >
        <span className={`da-json-chevron${expanded ? ' open' : ''}`}>▶</span>
        {expanded ? '收起' : '展开'} JSON
      </button>
      {expanded && (
        <pre className="da-json-content" style={{ maxHeight }}>
          {formatValue(parsed)}
        </pre>
      )}
    </div>
  );
}
