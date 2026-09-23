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
  display: 'flex',
  alignItems: 'flex-start',
  gap: 16,
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
          <div style={{ flex: 1 }}>
            <div
              style={{
                display: 'flex',
                alignItems: 'center',
                gap: 12,
                marginBottom: 8,
              }}
            >
              <span
                style={{
                  fontSize: '1.2rem',
                  fontWeight: 700,
                  color: 'var(--da-text)',
                }}
              >
                {dataSource.name}
              </span>
              <span
                style={{
                  display: 'inline-block',
                  padding: '3px 12px',
                  borderRadius: 6,
                  fontSize: '0.72rem',
                  fontWeight: 700,
                  letterSpacing: '0.04em',
                  background: 'rgba(37, 99, 235, 0.08)',
                  color: '#2563eb',
                }}
              >
                {dataSource.kind.toUpperCase()}
              </span>
            </div>
            <div style={{ fontSize: '0.82rem', color: 'var(--da-text-muted)' }}>
              {schemas.length} 个数据库 · {tables.length} 个数据表
            </div>
          </div>
          <button
            className="da-btn"
            onClick={onClose}
            style={{ padding: '8px 18px', fontSize: '0.85rem', flexShrink: 0 }}
          >
            关闭
          </button>
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
        <div style={{ display: 'flex', flex: 1, minHeight: 0 }}>
          {/* Left: databases */}
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
          {/* Middle: tables */}
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
                  style={itemStyle(table === t.name)}
                  onClick={() => openTable(t.name)}
                  onMouseEnter={e => {
                    if (table !== t.name) {
                      e.currentTarget.style.background = 'var(--da-surface-sunken)';
                    }
                  }}
                  onMouseLeave={e => {
                    if (table !== t.name) {
                      e.currentTarget.style.background = 'transparent';
                    }
                  }}
                >
                  <div>{t.name}</div>
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
                </div>
              ))
            )}
          </div>
          {/* Right: columns */}
          <div style={colLastStyle}>
            <div style={sectionTitleStyle}>
              字段信息
              {table && (
                <span style={{ color: 'var(--da-text-muted)', fontWeight: 400 }}>
                  {' '}
                  · {table}
                </span>
              )}
            </div>
            {table ? (
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
                请选择数据表查看字段信息
              </div>
            )}
          </div>
        </div>
      </div>
    </div>
  );
}
