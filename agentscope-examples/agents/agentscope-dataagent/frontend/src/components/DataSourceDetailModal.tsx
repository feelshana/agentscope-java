import React, { useEffect, useState } from 'react';
import {
  ExternalDataSource,
  listColumns,
  listSchemas,
  listTables,
  SchemaColumn,
  SchemaTable,
} from '../api/datasources';

const overlayStyle: React.CSSProperties = {
  position: 'fixed',
  inset: 0,
  background: 'rgba(24, 24, 27, 0.32)',
  display: 'flex',
  alignItems: 'center',
  justifyContent: 'center',
  zIndex: 90,
};

const shellStyle: React.CSSProperties = {
  background: 'var(--da-surface)',
  borderRadius: 12,
  width: 'min(960px, 94vw)',
  maxHeight: '86vh',
  display: 'flex',
  flexDirection: 'column',
  overflow: 'hidden',
};

const headStyle: React.CSSProperties = {
  padding: '14px 20px',
  borderBottom: '1px solid var(--da-border)',
  display: 'flex',
  alignItems: 'center',
  gap: 12,
};

const colStyle: React.CSSProperties = {
  flex: 1,
  minHeight: 0,
  overflow: 'auto',
  padding: 12,
  borderRight: '1px solid var(--da-surface-sunken)',
};

const itemStyle = (active: boolean): React.CSSProperties => ({
  padding: '6px 10px',
  borderRadius: 6,
  cursor: 'pointer',
  fontSize: '0.82rem',
  background: active ? 'var(--da-primary-subtle)' : 'transparent',
  color: active ? 'var(--da-primary-hover)' : 'var(--da-text)',
});

const thStyle: React.CSSProperties = {
  textAlign: 'left',
  padding: '6px 8px',
  borderBottom: '1px solid var(--da-border)',
  fontSize: '0.75rem',
  color: 'var(--da-text-3)',
  fontWeight: 600,
};

const tdStyle: React.CSSProperties = {
  padding: '6px 8px',
  borderBottom: '1px solid var(--da-surface-sunken)',
  fontSize: '0.8rem',
  color: 'var(--da-text)',
};

/** TC-style 数据源详情 modal: schema list → table list → column info. */
export default function DataSourceDetailModal({
  dataSource,
  onClose,
}: {
  dataSource: ExternalDataSource;
  onClose: () => void;
}) {
  const [schemas, setSchemas] = useState<string[]>([]);
  const [schema, setSchema] = useState<string | null>(null);
  const [tables, setTables] = useState<SchemaTable[]>([]);
  const [table, setTable] = useState<string | null>(null);
  const [columns, setColumns] = useState<SchemaColumn[]>([]);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    listSchemas(dataSource.id)
      .then(s => {
        setSchemas(s);
        if (s.length > 0) setSchema(s[0]);
      })
      .catch(e => setError(e instanceof Error ? e.message : String(e)));
  }, [dataSource.id]);

  useEffect(() => {
    if (!schema) return;
    setTable(null);
    setColumns([]);
    listTables(dataSource.id, schema)
      .then(setTables)
      .catch(e => setError(e instanceof Error ? e.message : String(e)));
  }, [schema, dataSource.id]);

  async function openTable(t: string) {
    if (!schema) return;
    setTable(t);
    try {
      setColumns(await listColumns(dataSource.id, schema, t));
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
  }

  return (
    <div style={overlayStyle} onClick={onClose}>
      <div style={shellStyle} onClick={e => e.stopPropagation()}>
        <div style={headStyle}>
          <span style={{ fontSize: '1rem', fontWeight: 700 }}>数据源详情 · {dataSource.name}</span>
          <span style={{ flex: 1 }} />
          <button
            style={{
              padding: '7px 12px',
              borderRadius: 8,
              border: '1px solid var(--da-border-strong)',
              background: 'var(--da-surface)',
              color: 'var(--da-text-2)',
              fontSize: '0.82rem',
              cursor: 'pointer',
            }}
            onClick={onClose}
          >
            关闭
          </button>
        </div>
        {error && (
          <div style={{ color: 'var(--da-danger)', fontSize: '0.85rem', padding: '8px 20px' }}>{error}</div>
        )}
        <div style={{ display: 'flex', flex: 1, minHeight: 0 }}>
          <div style={{ ...colStyle, maxWidth: 240 }}>
            <div style={{ fontSize: '0.78rem', fontWeight: 600, color: 'var(--da-text-3)', marginBottom: 6 }}>
              数据库
            </div>
            {schemas.map(s => (
              <div key={s} style={itemStyle(schema === s)} onClick={() => setSchema(s)}>
                {s}
              </div>
            ))}
          </div>
          <div style={{ ...colStyle, maxWidth: 280 }}>
            <div style={{ fontSize: '0.78rem', fontWeight: 600, color: 'var(--da-text-3)', marginBottom: 6 }}>
              数据表 {schema ? `· ${schema}` : ''}
            </div>
            {tables.map(t => (
              <div key={t.name} style={itemStyle(table === t.name)} onClick={() => openTable(t.name)}>
                {t.name}
              </div>
            ))}
          </div>
          <div style={colStyle}>
            <div style={{ fontSize: '0.78rem', fontWeight: 600, color: 'var(--da-text-3)', marginBottom: 6 }}>
              字段信息 {table ? `· ${table}` : ''}
            </div>
            {table ? (
              <table className="da-table">
                <thead>
                  <tr>
                    <th>列名</th>
                    <th>类型</th>
                    <th>描述</th>
                  </tr>
                </thead>
                <tbody>
                  {columns.map(c => (
                    <tr key={c.name}>
                      <td>{c.name}</td>
                      <td>{c.type}</td>
                      <td>{c.description ?? '-'}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            ) : (
              <div style={{ color: 'var(--da-text-muted)', fontSize: '0.82rem' }}>请选择数据表</div>
            )}
          </div>
        </div>
      </div>
    </div>
  );
}
