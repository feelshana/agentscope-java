import React, { useState } from 'react';
import { Dataset } from '../api/datasets';

const S: Record<string, React.CSSProperties> = {
  container: {
    height: '100%',
    overflow: 'auto',
    padding: '16px 20px',
  },
  table: {
    width: '100%',
    borderCollapse: 'collapse' as const,
    fontSize: '0.82rem',
    fontFamily: '-apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif',
  },
  th: {
    textAlign: 'left' as const,
    padding: '10px 12px',
    borderBottom: '2px solid #e2e8f0',
    color: '#64748b',
    fontWeight: 600,
    fontSize: '0.75rem',
    textTransform: 'uppercase' as const,
    letterSpacing: '0.05em',
    position: 'sticky' as const,
    top: 0,
    background: '#fff',
    zIndex: 1,
  },
  td: {
    padding: '10px 12px',
    borderBottom: '1px solid #f1f5f9',
    verticalAlign: 'top' as const,
  },
  tableRow: {
    cursor: 'pointer',
    transition: 'background 0.15s',
  },
  tableRowHover: {
    background: '#f8fafc',
  },
  tableRowActive: {
    background: '#f0f9ff',
  },
  toggle: {
    width: 20,
    height: 20,
    display: 'inline-flex',
    alignItems: 'center',
    justifyContent: 'center',
    borderRadius: 4,
    marginRight: 8,
    color: '#64748b',
    fontSize: '0.7rem',
    transition: 'transform 0.15s',
  },
  tableName: {
    fontWeight: 600,
    color: '#0f172a',
  },
  tableMeta: {
    color: '#94a3b8',
    fontSize: '0.75rem',
    marginLeft: 8,
  },
  fieldName: {
    fontFamily: 'ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace',
    color: '#0f172a',
    fontWeight: 500,
    fontSize: '0.8rem',
  },
  fieldType: {
    color: '#6366f1',
    fontSize: '0.75rem',
    fontFamily: 'ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace',
    background: '#eef2ff',
    padding: '2px 6px',
    borderRadius: 4,
    whiteSpace: 'nowrap' as const,
  },
  fieldDesc: {
    color: '#64748b',
    fontSize: '0.78rem',
    lineHeight: 1.5,
  },
  empty: {
    display: 'flex',
    alignItems: 'center',
    justifyContent: 'center',
    height: '100%',
    color: '#94a3b8',
    fontSize: '0.9rem',
  },
};

export default function SchemaTreeView({ datasets }: { datasets: Dataset[] }) {
  const [open, setOpen] = useState<Set<string>>(new Set(datasets.map(d => d.id)));
  const [hoveredRow, setHoveredRow] = useState<string | null>(null);

  function toggle(id: string) {
    setOpen(prev => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  }

  if (datasets.length === 0) {
    return <div style={S.empty}>暂无数据集。</div>;
  }

  return (
    <div style={S.container}>
      <table style={S.table}>
        <thead>
          <tr>
            <th style={{ ...S.th, width: '40%' }}>字段名</th>
            <th style={{ ...S.th, width: '15%' }}>类型</th>
            <th style={S.th}>说明</th>
          </tr>
        </thead>
        <tbody>
          {datasets.map(d => {
            const isOpen = open.has(d.id);
            return (
              <React.Fragment key={d.id}>
                <tr
                  style={{
                    ...S.tableRow,
                    ...(hoveredRow === `table-${d.id}` ? S.tableRowHover : {}),
                  }}
                  onClick={() => toggle(d.id)}
                  onMouseEnter={() => setHoveredRow(`table-${d.id}`)}
                  onMouseLeave={() => setHoveredRow(null)}
                >
                  <td colSpan={3} style={S.td}>
                    <div style={{ display: 'flex', alignItems: 'center' }}>
                      <span
                        style={{
                          ...S.toggle,
                          transform: isOpen ? 'rotate(0deg)' : 'rotate(-90deg)',
                        }}
                      >
                        ▼
                      </span>
                      <span style={S.tableName}>📄 {d.name}</span>
                      <span style={S.tableMeta}>
                        {d.rowCount.toLocaleString()} 行 · {d.columns.length} 列
                      </span>
                    </div>
                  </td>
                </tr>
                {isOpen &&
                  d.columns.map(c => (
                    <tr
                      key={c.name}
                      style={{
                        ...S.tableRow,
                        ...(hoveredRow === `field-${d.id}-${c.name}` ? S.tableRowHover : {}),
                      }}
                      onMouseEnter={() => setHoveredRow(`field-${d.id}-${c.name}`)}
                      onMouseLeave={() => setHoveredRow(null)}
                    >
                      <td style={{ ...S.td, paddingLeft: 40 }}>
                        <span style={S.fieldName}>{c.name}</span>
                      </td>
                      <td style={S.td}>
                        <span style={S.fieldType}>{c.sqlType}</span>
                      </td>
                      <td style={S.td}>
                        <span style={S.fieldDesc}>{c.description || c.originalName || '-'}</span>
                      </td>
                    </tr>
                  ))}
              </React.Fragment>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}
