import React, { useState } from 'react';
import { parseSemantic } from '../utils/semanticTools';

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
  semantic?: { groupName: string; sql: string; rowCount: number };
}

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
    const sem = parseSemantic(t.result);

    if (sem?.stage === 'query' && sem.status === 'success') {
      const d = sem.data as Record<string, unknown>;
      entries.push({
        semantic: {
          groupName: (d.groupName as string) ?? '',
          sql: (d.semanticSql as string) ?? '',
          rowCount: (d.returnedRowCount as number) ?? 0,
        },
      });
    } else if (n.includes('run_sql_preview') || n.includes('describe_table')) {
      let input: { source_id?: string; sql?: string } = {};
      try { input = JSON.parse(t.input ?? '{}'); } catch { input = {}; }
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
              {e.semantic ? (
                <>
                  <span style={{ fontWeight: 600 }}>
                    {e.semantic.groupName || '语义模型'}
                  </span>
                  <span style={{ color: 'var(--da-text-muted)' }}>
                    {' '}· {e.semantic.rowCount} 行 · 语义 SQL 查询
                  </span>
                  {e.semantic.sql && (
                    <div
                      style={{
                        fontFamily: 'ui-monospace, Menlo, monospace',
                        fontSize: '0.7rem',
                        color: 'var(--da-text-3)',
                        whiteSpace: 'pre-wrap',
                        marginTop: 2,
                      }}
                    >
                      {e.semantic.sql.length > 220 ? e.semantic.sql.slice(0, 220) + '…' : e.semantic.sql}
                    </div>
                  )}
                </>
              ) : e.chart ? (
                <span>📊 图表（render_chart）</span>
              ) : (
                <>
                  <span style={{ fontWeight: 600 }}>{e.dataset ?? '未知数据集'}</span>
                  {e.table && <span style={{ color: 'var(--da-text-muted)' }}> · {e.table}</span>}
                  {e.sql && (
                    <div
                      style={{
                        fontFamily: 'ui-monospace, Menlo, monospace',
                        fontSize: '0.7rem',
                        color: 'var(--da-text-3)',
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
