import React, { useState } from 'react';

export interface CitationToolEntry {
  name: string;
  input?: string;
  result?: string;
}

export interface DatasetRef {
  name: string;
  tableName: string;
}

interface Entry {
  dataset?: string;
  table?: string;
  sql?: string;
  chart?: boolean;
}

/**
 * Collapsible "来源" panel under an assistant bubble: which datasets/tables/SQL a turn touched,
 * derived from the turn's tool calls (live and history use the same tool entries).
 */
export default function CitationPanel({
  tools,
  datasetMap,
}: {
  tools: CitationToolEntry[];
  datasetMap: Record<string, DatasetRef>;
}) {
  const [open, setOpen] = useState(false);
  const entries: Entry[] = [];
  for (const t of tools) {
    const n = (t.name ?? '').toLowerCase();
    if (n.includes('run_sql_preview') || n.includes('describe_table')) {
      let input: { source_id?: string; sql?: string } = {};
      try {
        input = JSON.parse(t.input ?? '{}');
      } catch {
        input = {};
      }
      const ref = input.source_id ? datasetMap[input.source_id] : undefined;
      entries.push({ dataset: ref?.name, table: ref?.tableName, sql: input.sql });
    } else if (n.includes('render_chart')) {
      entries.push({ chart: true });
    }
  }
  if (entries.length === 0) return null;

  return (
    <div style={{ marginTop: 8 }}>
      <button
        onClick={() => setOpen(o => !o)}
        style={{
          background: 'transparent',
          border: '1px solid #e2e8f0',
          borderRadius: 8,
          padding: '4px 10px',
          fontSize: '0.75rem',
          color: '#64748b',
          cursor: 'pointer',
        }}
      >
        {open ? '▾' : '▸'} 来源 ({entries.length})
      </button>
      {open && (
        <div
          style={{
            marginTop: 6,
            border: '1px solid #e2e8f0',
            borderRadius: 8,
            padding: 8,
            background: '#f8fafc',
            display: 'flex',
            flexDirection: 'column',
            gap: 6,
          }}
        >
          {entries.map((e, i) => (
            <div key={i} style={{ fontSize: '0.75rem', color: '#475569' }}>
              {e.chart ? (
                <span>📊 图表（render_chart）</span>
              ) : (
                <>
                  <span style={{ fontWeight: 600 }}>{e.dataset ?? '未知数据集'}</span>
                  {e.table && <span style={{ color: '#94a3b8' }}> · {e.table}</span>}
                  {e.sql && (
                    <div
                      style={{
                        fontFamily: 'ui-monospace, Menlo, monospace',
                        fontSize: '0.7rem',
                        color: '#64748b',
                        whiteSpace: 'pre-wrap',
                        marginTop: 2,
                      }}
                    >
                      {e.sql.length > 220 ? e.sql.slice(0, 220) + '…' : e.sql}
                    </div>
                  )}
                </>
              )}
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
