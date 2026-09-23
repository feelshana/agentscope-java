import React, { useEffect, useRef, useState } from 'react';
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
  background: 'rgba(24, 24, 27, 0.32)',
  display: 'flex',
  alignItems: 'center',
  justifyContent: 'center',
  zIndex: 85,
};

const shellStyle: React.CSSProperties = {
  background: 'var(--da-surface)',
  borderRadius: 16,
  width: 'min(1400px, 96vw)',
  maxHeight: '90vh',
  minHeight: 650,
  display: 'flex',
  flexDirection: 'column',
  overflow: 'hidden',
  boxShadow: 'var(--da-shadow-pop)',
  border: '1px solid var(--da-border)',
};

const headStyle: React.CSSProperties = {
  padding: '20px 28px',
  borderBottom: '1px solid var(--da-border)',
};

const bodyStyle: React.CSSProperties = {
  padding: '0 8px',
  overflow: 'hidden',
  flex: 1,
  minHeight: 0,
  display: 'flex',
  flexDirection: 'column',
};

const footStyle: React.CSSProperties = {
  padding: '14px 28px',
  borderTop: '1px solid var(--da-border)',
  display: 'flex',
  gap: 10,
  alignItems: 'center',
  background: 'var(--da-surface-sunken)',
};

const colStyle: React.CSSProperties = {
  flex: '0 0 220px',
  minWidth: 0,
  overflow: 'auto',
  padding: '0 20px 20px',
  borderRight: '1px solid var(--da-border)',
};

const colMidStyle: React.CSSProperties = {
  flex: '0 0 300px',
  minWidth: 0,
  overflow: 'auto',
  padding: '0 20px 20px',
  borderRight: '1px solid var(--da-border)',
};

const colLastStyle: React.CSSProperties = {
  flex: 1,
  minWidth: 0,
  overflow: 'auto',
  padding: '0 20px 20px',
};

const sectionTitleStyle: React.CSSProperties = {
  fontSize: '0.88rem',
  fontWeight: 600,
  color: 'var(--da-text)',
  padding: '16px 0 10px',
  borderBottom: '1px solid var(--da-border)',
  marginBottom: 12,
};

const itemStyle = (active: boolean): React.CSSProperties => ({
  padding: '9px 12px',
  borderRadius: 8,
  cursor: 'pointer',
  fontSize: '0.85rem',
  background: active ? 'rgba(79, 70, 229, 0.06)' : 'transparent',
  color: active ? 'var(--da-primary)' : 'var(--da-text)',
  fontWeight: active ? 500 : 400,
  transition: 'background 0.12s ease',
  marginBottom: 2,
});

export default function AssociateTablesModal({
  groupId,
  onClose,
  onAssociated,
  onAssociateStart,
}: {
  groupId: string;
  onClose: () => void;
  onAssociated: () => void;
  onAssociateStart?: (tables: string[]) => void;
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
  const mountedRef = useRef(true);

  useEffect(() => {
    return () => {
      mountedRef.current = false;
    };
  }, []);

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

  function handleAssociate() {
    if (!sourceId || !schema || selected.size === 0) {
      setError('请选择数据源、库与至少一张表');
      return;
    }
    const tableNames = Array.from(selected);
    onAssociateStart?.(tableNames);
    onClose();
    setBusy(true);
    setError(null);
    associateTables(groupId, {
      dataSourceId: sourceId,
      schema,
      tables: tableNames,
    })
      .then(() => {
        onAssociated();
      })
      .catch(e => {
        if (mountedRef.current) {
          setError(e instanceof Error ? e.message : String(e));
        }
      })
      .finally(() => {
        if (mountedRef.current) {
          setBusy(false);
        }
      });
  }

  return (
    <div style={overlayStyle} onClick={onClose}>
      <div style={shellStyle} onClick={e => e.stopPropagation()}>
        <div style={headStyle}>
          <div style={{ fontSize: '1.1rem', fontWeight: 700, color: 'var(--da-text)' }}>
            从数据源关联
          </div>
          <div style={{ fontSize: '0.85rem', color: 'var(--da-text-3)', marginTop: 6 }}>
            关联数据源上的重要库表，Agent 将为您提供数据查询、分析、预测等回答
          </div>
        </div>
        {error && (
          <div
            style={{
              color: 'var(--da-danger)',
              fontSize: '0.85rem',
              padding: '12px 28px',
              background: 'rgba(225, 29, 72, 0.06)',
              borderBottom: '1px solid rgba(225, 29, 72, 0.18)',
            }}
          >
            {error}
          </div>
        )}
        <div style={bodyStyle}>
          <div style={{ padding: '16px 20px 0' }}>
            <label
              style={{
                display: 'block',
                fontSize: '0.8rem',
                fontWeight: 600,
                color: 'var(--da-text-2)',
                marginBottom: 8,
              }}
            >
              选择数据源
            </label>
            <select
              className="da-input"
              value={sourceId ?? ''}
              onChange={e => setSourceId(e.target.value || null)}
              style={{ minWidth: 280, padding: '10px 12px', fontSize: '0.85rem' }}
            >
              <option value="">请选择数据源…</option>
              {sources.map(s => (
                <option key={s.id} value={s.id}>
                  {s.name} ({s.kind.toUpperCase()})
                </option>
              ))}
            </select>
          </div>
          <div style={{ display: 'flex', flex: 1, minHeight: 0 }}>
            <div style={colStyle}>
              <div style={sectionTitleStyle}>数据库</div>
              {schemas.length === 0 ? (
                <div
                  style={{
                    color: 'var(--da-text-muted)',
                    fontSize: '0.85rem',
                    padding: '32px 0',
                    textAlign: 'center',
                  }}
                >
                  暂无数据库
                </div>
              ) : (
                schemas.map(s => (
                  <div
                    key={s}
                    style={itemStyle(schema === s)}
                    onClick={() => setSchema(s)}
                    onMouseEnter={e => {
                      if (schema !== s) {
                        e.currentTarget.style.background = 'var(--da-surface-sunken)';
                      }
                    }}
                    onMouseLeave={e => {
                      if (schema !== s) {
                        e.currentTarget.style.background = 'transparent';
                      }
                    }}
                  >
                    {s}
                  </div>
                ))
              )}
            </div>
            <div style={colMidStyle}>
              <div style={sectionTitleStyle}>
                数据表
                {schema && (
                  <span style={{ color: 'var(--da-text-muted)', fontWeight: 400 }}>
                    {' '}
                    · {schema}
                  </span>
                )}
              </div>
              {tables.length === 0 ? (
                <div
                  style={{
                    color: 'var(--da-text-muted)',
                    fontSize: '0.85rem',
                    padding: '32px 0',
                    textAlign: 'center',
                  }}
                >
                  暂无数据表
                </div>
              ) : (
                tables.map(t => (
                  <div
                    key={t.name}
                    style={{
                      ...itemStyle(selected.has(t.name)),
                      display: 'flex',
                      alignItems: 'center',
                      gap: 10,
                      cursor: 'default',
                    }}
                  >
                    <input
                      type="checkbox"
                      checked={selected.has(t.name)}
                      onChange={() => toggle(t.name)}
                      style={{ width: 16, height: 16, cursor: 'pointer' }}
                    />
                    <span style={{ flex: 1, minWidth: 0 }}>
                      <div style={{ fontWeight: selected.has(t.name) ? 500 : 400 }}>{t.name}</div>
                      {t.comment && (
                        <div
                          style={{
                            fontSize: '0.75rem',
                            color: 'var(--da-text-muted)',
                            marginTop: 3,
                          }}
                        >
                          {t.comment}
                        </div>
                      )}
                    </span>
                    <button
                      style={{
                        background: 'transparent',
                        border: 'none',
                        color: 'var(--da-primary)',
                        cursor: 'pointer',
                        fontSize: '0.8rem',
                        padding: '4px 8px',
                        borderRadius: 4,
                        transition: 'background 0.15s ease',
                        flexShrink: 0,
                      }}
                      onClick={() => viewColumns(t.name)}
                      onMouseEnter={e => {
                        e.currentTarget.style.background = 'var(--da-primary-subtle)';
                      }}
                      onMouseLeave={e => {
                        e.currentTarget.style.background = 'transparent';
                      }}
                    >
                      查看
                    </button>
                  </div>
                ))
              )}
            </div>
            <div style={colLastStyle}>
              <div style={sectionTitleStyle}>
                字段信息
                {viewTable && (
                  <span style={{ color: 'var(--da-text-muted)', fontWeight: 400 }}>
                    {' '}
                    · {viewTable}
                  </span>
                )}
              </div>
              {viewTable ? (
                <div
                  style={{
                    borderRadius: 8,
                    border: '1px solid var(--da-border)',
                    overflow: 'hidden',
                  }}
                >
                  <table
                    style={{
                      width: '100%',
                      borderCollapse: 'collapse',
                      fontSize: '0.85rem',
                    }}
                  >
                    <thead>
                      <tr
                        style={{
                          background: 'var(--da-surface-sunken)',
                        }}
                      >
                        <th
                          style={{
                            padding: '10px 14px',
                            textAlign: 'left',
                            fontSize: '0.8rem',
                            fontWeight: 600,
                            color: 'var(--da-text-3)',
                            borderBottom: '1px solid var(--da-border)',
                          }}
                        >
                          列名
                        </th>
                        <th
                          style={{
                            padding: '10px 14px',
                            textAlign: 'left',
                            fontSize: '0.8rem',
                            fontWeight: 600,
                            color: 'var(--da-text-3)',
                            borderBottom: '1px solid var(--da-border)',
                          }}
                        >
                          类型
                        </th>
                        <th
                          style={{
                            padding: '10px 14px',
                            textAlign: 'left',
                            fontSize: '0.8rem',
                            fontWeight: 600,
                            color: 'var(--da-text-3)',
                            borderBottom: '1px solid var(--da-border)',
                          }}
                        >
                          描述
                        </th>
                      </tr>
                    </thead>
                    <tbody>
                      {columns.map(c => (
                        <tr
                          key={c.name}
                          style={{
                            borderBottom: '1px solid var(--da-border)',
                          }}
                        >
                          <td
                            style={{
                              padding: '11px 14px',
                              fontWeight: 500,
                              color: 'var(--da-text)',
                              verticalAlign: 'top',
                            }}
                          >
                            {c.name}
                          </td>
                          <td
                            style={{
                              padding: '11px 14px',
                              verticalAlign: 'top',
                            }}
                          >
                            <span
                              style={{
                                fontFamily: 'var(--da-mono)',
                                fontSize: '0.78rem',
                                padding: '2px 8px',
                                borderRadius: 4,
                                background: 'var(--da-surface-sunken)',
                                color: 'var(--da-text-2)',
                                border: '1px solid var(--da-border)',
                                whiteSpace: 'nowrap',
                              }}
                            >
                              {c.type}
                            </span>
                          </td>
                          <td
                            style={{
                              padding: '11px 14px',
                              color: 'var(--da-text-2)',
                              verticalAlign: 'top',
                            }}
                          >
                            {c.description ?? '-'}
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              ) : (
                <div
                  style={{
                    color: 'var(--da-text-muted)',
                    fontSize: '0.85rem',
                    padding: '48px 0',
                    textAlign: 'center',
                  }}
                >
                  点击"查看"查看字段信息
                </div>
              )}
            </div>
          </div>
        </div>
        <div style={footStyle}>
          <button
            className="da-btn da-btn-primary"
            onClick={handleAssociate}
            disabled={busy}
            style={{
              display: 'inline-flex',
              alignItems: 'center',
              gap: 8,
              padding: '9px 20px',
              fontSize: '0.85rem',
              opacity: busy ? 0.75 : 1,
            }}
          >
            {busy && (
              <svg
                width="14"
                height="14"
                viewBox="0 0 16 16"
                style={{ animation: 'da-spin 0.8s linear infinite' }}
              >
                <circle
                  cx="8"
                  cy="8"
                  r="6"
                  fill="none"
                  stroke="currentColor"
                  strokeWidth="2"
                  strokeDasharray="28"
                  strokeDashoffset="8"
                  strokeLinecap="round"
                />
              </svg>
            )}
            {busy ? '关联中…' : `关联已选库表 (${selected.size})`}
          </button>
          <button
            className="da-btn"
            onClick={onClose}
            style={{ padding: '9px 20px', fontSize: '0.85rem' }}
          >
            取消
          </button>
        </div>
      </div>
    </div>
  );
}
