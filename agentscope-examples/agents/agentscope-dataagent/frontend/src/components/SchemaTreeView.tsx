import React, { useState } from 'react';
import { Dataset } from '../api/datasets';

const rowStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'center',
  gap: 8,
  padding: '4px 8px',
  borderRadius: 6,
  cursor: 'pointer',
  fontSize: '0.82rem',
  color: '#0f172a',
};

const leafStyle: React.CSSProperties = {
  padding: '3px 8px 3px 28px',
  fontSize: '0.78rem',
  color: '#475569',
  display: 'flex',
  gap: 8,
};

/** Read-only datasets → columns tree (TC "树结构目录" analogue), self-drawn with inline styles. */
export default function SchemaTreeView({ datasets }: { datasets: Dataset[] }) {
  const [open, setOpen] = useState<Set<string>>(new Set(datasets.map(d => d.id)));

  function toggle(id: string) {
    setOpen(prev => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  }

  if (datasets.length === 0) {
    return <div style={{ color: '#94a3b8', fontSize: '0.85rem' }}>暂无数据集。</div>;
  }

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 2 }}>
      {datasets.map(d => (
        <div key={d.id}>
          <div style={rowStyle} onClick={() => toggle(d.id)}>
            <span style={{ width: 12, color: '#94a3b8' }}>{open.has(d.id) ? '▾' : '▸'}</span>
            <span style={{ fontWeight: 600 }}>📄 {d.name}</span>
            <span style={{ color: '#94a3b8', fontSize: '0.72rem' }}>
              {d.rowCount} 行 · {d.columns.length} 列
            </span>
          </div>
          {open.has(d.id) &&
            d.columns.map(c => (
              <div key={c.name} style={leafStyle}>
                <span style={{ fontFamily: 'ui-monospace, Menlo, monospace' }}>{c.name}</span>
                <span style={{ color: '#94a3b8' }}>{c.sqlType}</span>
                <span style={{ color: '#64748b' }}>{c.description || c.originalName}</span>
              </div>
            ))}
        </div>
      ))}
    </div>
  );
}
