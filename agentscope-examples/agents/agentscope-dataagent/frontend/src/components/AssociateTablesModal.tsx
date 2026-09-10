import React, { useEffect, useState } from 'react';
import {
  associateTables,
  ExternalDataSource,
  listColumns,
  listDataSources,
  listSchemas,
  listTables,
  SchemaColumn,
  SchemaTable,
} from '../api/datasources';

const overlayStyle: React.CSSProperties = {
  position: 'fixed',
  inset: 0,
  background: 'rgba(15,23,42,0.5)',
  display: 'flex',
  alignItems: 'center',
  justifyContent: 'center',
  zIndex: 85,
};

const shellStyle: React.CSSProperties = {
  background: '#ffffff',
  borderRadius: 12,
  width: 'min(1000px, 94vw)',
  maxHeight: '88vh',
  display: 'flex',
  flexDirection: 'column',
  overflow: 'hidden',
};

const headStyle: React.CSSProperties = {
  padding: '14px 20px',
  borderBottom: '1px solid #e2e8f0',
};

const bodyStyle: React.CSSProperties = {
  padding: 16,
  overflow: 'auto',
  flex: 1,
  minHeight: 0,
};

const footStyle: React.CSSProperties = {
  padding: '12px 20px',
  borderTop: '1px solid #e2e8f0',
  display: 'flex',
  gap: 10,
  alignItems: 'center',
};

const primaryBtn: React.CSSProperties = {
  padding: '8px 16px',
  borderRadius: 8,
  border: '1px solid #2563eb',
  background: '#2563eb',
  color: '#ffffff',
  fontSize: '0.85rem',
  fontWeight: 600,
  cursor: 'pointer',
};

const ghostBtn: React.CSSProperties = {
  padding: '7px 12px',
  borderRadius: 8,
  border: '1px solid #cbd5e1',
  background: '#ffffff',
  color: '#475569',
  fontSize: '0.82rem',
  cursor: 'pointer',
};

const colStyle: React.CSSProperties = {
  flex: 1,
  minHeight: 0,
  overflow: 'auto',
  padding: 10,
  borderRight: '1px solid #f1f5f9',
};

const itemStyle = (active: boolean): React.CSSProperties => ({
  padding: '6px 10px',
  borderRadius: 6,
  cursor: 'pointer',
  fontSize: '0.82rem',
  background: active ? '#eef2ff' : 'transparent',
  color: active ? '#3730a3' : '#0f172a',
  display: 'flex',
  alignItems: 'center',
  gap: 8,
});

const thStyle: React.CSSProperties = {
  textAlign: 'left',
  padding: '6px 8px',
  borderBottom: '1px solid #e2e8f0',
  fontSize: '0.75rem',
  color: '#64748b',
  fontWeight: 600,
};

const tdStyle: React.CSSProperties = {
  padding: '6px 8px',
  borderBottom: '1px solid #f1f5f9',
  fontSize: '0.8rem',
  color: '#0f172a',
};

/** TC-style "从数据源关联" modal: pick source → schema → tables → associate into the KB. */
export default function AssociateTablesModal({
  groupId,
  onClose,
  onAssociated,
}: {
  groupId: string;
  onClose: () => void;
  onAssociated: () => void;
}) {
  const [sources, setSources] = useState<ExternalDataSource[]>([]);
  const [sourceId, setSourceId] = useState<string | null>(null);
  const [schemas, setSchemas] = useState<string[]>([]);
  const [schema, setSchema] = useState<string | null>(null);
  const [tables, setTables] = useState<SchemaTable[]>([]);
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [viewTable, setViewTable] = useState<string | null>(null);
  const [columns, setColumns] = useState<SchemaColumn[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    listDataSources()
      .then(setSources)
      .catch(e => setError(e instanceof Error ? e.message : String(e)));
  }, []);

  useEffect(() => {
    if (!sourceId) return;
    setSchema(null);
    setTables([]);
    setSelected(new Set());
    listSchemas(sourceId)
      .then(s => {
        setSchemas(s);
        if (s.length > 0) setSchema(s[0]);
      })
      .catch(e => setError(e instanceof Error ? e.message : String(e)));
  }, [sourceId]);

  useEffect(() => {
    if (!sourceId || !schema) return;
    setTables([]);
    setSelected(new Set());
    setViewTable(null);
    listTables(sourceId, schema)
      .then(setTables)
      .catch(e => setError(e instanceof Error ? e.message : String(e)));
  }, [sourceId, schema]);

  async function viewColumns(t: string) {
    if (!sourceId || !schema) return;
    setViewTable(t);
    try {
      setColumns(await listColumns(sourceId, schema, t));
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
  }

  function toggle(t: string) {
    setSelected(prev => {
      const next = new Set(prev);
      if (next.has(t)) next.delete(t);
      else next.add(t);
      return next;
    });
  }

  async function handleAssociate() {
    if (!sourceId || !schema || selected.size === 0) {
      setError('请选择数据源、库与至少一张表');
      return;
    }
    setBusy(true);
    setError(null);
    try {
      await associateTables(groupId, {
        dataSourceId: sourceId,
        schema,
        tables: Array.from(selected),
      });
      onAssociated();
      onClose();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  }

  return (
    <div style={overlayStyle} onClick={onClose}>
      <div style={shellStyle} onClick={e => e.stopPropagation()}>
        <div style={headStyle}>
          <div style={{ fontSize: '1rem', fontWeight: 700 }}>从数据源关联</div>
          <div style={{ fontSize: '0.78rem', color: '#64748b', marginTop: 4 }}>
            关联数据源上的重要库表，Agent 将为您提供数据查询、分析、预测等回答
          </div>
        </div>
        {error && (
          <div style={{ color: '#b91c1c', fontSize: '0.85rem', padding: '8px 20px' }}>{error}</div>
        )}
        <div style={bodyStyle}>
          <div style={{ display: 'flex', gap: 10, marginBottom: 12 }}>
            <select
              style={{
                padding: '7px 9px',
                borderRadius: 6,
                border: '1px solid #cbd5e1',
                fontSize: '0.82rem',
                minWidth: 220,
              }}
              value={sourceId ?? ''}
              onChange={e => setSourceId(e.target.value || null)}
            >
              <option value="">选择数据源…</option>
              {sources.map(s => (
                <option key={s.id} value={s.id}>
                  {s.name} ({s.kind})
                </option>
              ))}
            </select>
          </div>
          <div style={{ display: 'flex', flex: 1, minHeight: 320 }}>
            <div style={{ ...colStyle, maxWidth: 220 }}>
              <div style={{ fontSize: '0.78rem', fontWeight: 600, color: '#64748b', marginBottom: 6 }}>
                数据库
              </div>
              {schemas.map(s => (
                <div key={s} style={itemStyle(schema === s)} onClick={() => setSchema(s)}>
                  {s}
                </div>
              ))}
            </div>
            <div style={{ ...colStyle, maxWidth: 320 }}>
              <div style={{ fontSize: '0.78rem', fontWeight: 600, color: '#64748b', marginBottom: 6 }}>
                数据表 {schema ? `· ${schema}` : ''}
              </div>
              {tables.map(t => (
                <div key={t.name} style={itemStyle(false)}>
                  <input
                    type="checkbox"
                    checked={selected.has(t.name)}
                    onChange={() => toggle(t.name)}
                  />
                  <span style={{ flex: 1 }}>{t.name}</span>
                  <button
                    style={{
                      background: 'transparent',
                      border: 'none',
                      color: '#2563eb',
                      cursor: 'pointer',
                      fontSize: '0.78rem',
                      padding: 0,
                    }}
                    onClick={() => viewColumns(t.name)}
                  >
                    查看
                  </button>
                </div>
              ))}
            </div>
            <div style={colStyle}>
              <div style={{ fontSize: '0.78rem', fontWeight: 600, color: '#64748b', marginBottom: 6 }}>
                字段信息 {viewTable ? `· ${viewTable}` : ''}
              </div>
              {viewTable ? (
                <table style={{ width: '100%', borderCollapse: 'collapse' }}>
                  <thead>
                    <tr>
                      <th style={thStyle}>列名</th>
                      <th style={thStyle}>类型</th>
                      <th style={thStyle}>描述</th>
                    </tr>
                  </thead>
                  <tbody>
                    {columns.map(c => (
                      <tr key={c.name}>
                        <td style={tdStyle}>{c.name}</td>
                        <td style={tdStyle}>{c.type}</td>
                        <td style={tdStyle}>{c.description ?? '-'}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              ) : (
                <div style={{ color: '#94a3b8', fontSize: '0.82rem' }}>点击"查看"看字段信息</div>
              )}
            </div>
          </div>
        </div>
        <div style={footStyle}>
          <button style={primaryBtn} onClick={handleAssociate} disabled={busy}>
            关联已选库表 ({selected.size})
          </button>
          <button style={ghostBtn} onClick={onClose}>
            取消
          </button>
        </div>
      </div>
    </div>
  );
}
