import React, { useState } from 'react';

export interface CitationToolEntry {
  name: string;
  input?: string;
  result?: string;
}

interface Entry {
  chart?: boolean;
  query?: { purpose: string; sql: string; rowCount: number; artifactId: string };
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

    if (n === 'query_structured_data' && t.result) {
      try {
        const parsed = JSON.parse(t.result) as Record<string, unknown>;
        if (parsed.status !== 'FAILED' && Array.isArray(parsed.results)) {
          for (const r of parsed.results as Record<string, unknown>[]) {
            entries.push({
              query: {
                purpose: (r.purpose as string) ?? '',
                sql: (r.sql as string) ?? '',
                rowCount: (r.rowCount as number) ?? 0,
                artifactId: (r.artifactId as string) ?? '',
              },
            });
          }
        }
      } catch { /* malformed result */ }
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
                  <span style={{ color: 'var(--da-text-muted)' }}>
                    {' '}· {e.query.rowCount} 行
                  </span>
                  <span style={{ color: 'var(--da-text-muted)', fontFamily: 'monospace', fontSize: '0.65rem' }}>
                    {' '}· {e.query.artifactId}
                  </span>
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
                <span>图表（render_chart）</span>
              )}
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
