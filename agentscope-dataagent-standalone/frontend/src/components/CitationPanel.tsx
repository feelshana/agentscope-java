import React, { useState } from 'react';

export interface CitationToolEntry {
  name: string;
  input?: string;
  result?: string;
}

interface Entry {
  chart?: boolean;
  query?: { purpose: string; sql?: string; source?: string };
}

export default function CitationPanel({
  tools,
}: {
  tools: CitationToolEntry[];
}) {
  const [open, setOpen] = useState(false);
  const entries: Entry[] = [];
  for (const t of tools) {
    const n = (t.name ?? '').toLowerCase();

    if ((n === 'wren_run_sql' || n === 'wren_query_cube') && t.result && !t.result.startsWith('error:')) {
      const purpose =
        t.result.match(/\*\*查询问题：\*\*\s*([^\n]+)/)?.[1]?.trim()
        ?? t.result.match(/\*\*Cube：\*\*\s*([^\n]+)/)?.[1]?.trim()
        ?? '语义查询';
      const source = t.result.match(/\*\*知识库：\*\*\s*([^\n]+)/)?.[1]?.trim();
      const sql = t.result.match(/```sql\s*\n([\s\S]*?)```/)?.[1]?.trim();
      entries.push({ query: { purpose, sql, source } });
    } else if (n === 'render_chart') {
      entries.push({ chart: true });
    }
  }
  if (entries.length === 0) return null;

  return (
    <div style={{ marginTop: 8 }}>
      <button
        type="button"
        onClick={() => setOpen(o => !o)}
        style={{
          background: 'transparent',
          border: '1px solid var(--da-border)',
          borderRadius: 8,
          padding: '4px 10px',
          fontSize: '0.75rem',
          color: 'var(--da-text-3)',
          cursor: 'pointer',
        }}
      >
        {open ? '▾' : '▸'} 来源 ({entries.length})
      </button>
      {open && (
        <div
          style={{
            marginTop: 6,
            border: '1px solid var(--da-border)',
            borderRadius: 8,
            padding: 8,
            background: 'var(--da-app-bg)',
            display: 'flex',
            flexDirection: 'column',
            gap: 6,
          }}
        >
          {entries.map((e, i) => (
            <div key={i} style={{ fontSize: '0.75rem', color: 'var(--da-text-2)' }}>
              {e.query ? (
                <>
                  <span style={{ fontWeight: 600 }}>{e.query.purpose || '数据查询'}</span>
                  {e.query.source && (
                    <span style={{ color: 'var(--da-text-muted)' }}>
                      {' '}· {e.query.source}
                    </span>
                  )}
                  {e.query.sql && (
                    <div
                      style={{
                        fontFamily: 'ui-monospace, Menlo, monospace',
                        fontSize: '0.7rem',
                        color: 'var(--da-text-3)',
                        whiteSpace: 'pre-wrap',
                        marginTop: 2,
                      }}
                    >
                      {e.query.sql.length > 220 ? e.query.sql.slice(0, 220) + '…' : e.query.sql}
                    </div>
                  )}
                </>
              ) : (
                <span>图表</span>
              )}
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
