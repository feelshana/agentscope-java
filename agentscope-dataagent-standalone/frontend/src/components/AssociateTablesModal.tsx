import React, { useEffect, useId, useMemo, useRef, useState } from 'react';
import type { AssociationState } from './AssociationProgress';
import { tourSignal } from '../state/guidedTour';
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
import './AssociateTablesModal.css';

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
  borderRadius: 12,
  width: 'min(1400px, 96vw)',
  height: 'min(800px, 90dvh)',
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

const sectionTitleStyle: React.CSSProperties = {
  fontSize: '0.88rem',
  fontWeight: 600,
  color: 'var(--da-text)',
  padding: '16px 0 10px',
  borderBottom: '1px solid var(--da-border)',
  marginBottom: 12,
  overflowWrap: 'anywhere',
};

const itemStyle = (active: boolean): React.CSSProperties => ({
  padding: '9px 12px',
  borderRadius: 8,
  cursor: 'pointer',
  fontSize: '0.85rem',
  background: active ? 'rgba(91, 61, 245, 0.06)' : 'transparent',
  color: active ? 'var(--da-primary)' : 'var(--da-text)',
  fontWeight: active ? 500 : 400,
  transition: 'background 0.12s ease',
  marginBottom: 2,
});

export default function AssociateTablesModal({
  groupId,
  onClose,
  onAssociated,
  onError,
  onAssociateStart,
  onProgress,
  preferBuiltin = false,
  initialSourceId,
}: {
  groupId: string;
  /** spec105: during the guided tour the demo source and its tables start out selected. */
  preferBuiltin?: boolean;
  initialSourceId?: string;
  onClose: () => void;
  onAssociated: () => void;
  onError?: (message: string) => void;
  onAssociateStart?: (tables: string[]) => void;
  /** Per-table sampling progress; tables are associated one request at a time. */
  onProgress?: (state: AssociationState) => void;
}) {
  const [sources, setSources] = useState<ExternalDataSource[]>([]);
  const [sourceId, setSourceId] = useState<string | null>(null);
  const [schemas, setSchemas] = useState<string[]>([]);
  const [schema, setSchema] = useState<string | null>(null);
  const [tables, setTables] = useState<SchemaTable[] | null>(null);
  const [tableSearch, setTableSearch] = useState('');
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [viewTable, setViewTable] = useState<string | null>(null);
  const [columns, setColumns] = useState<SchemaColumn[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const mountedRef = useRef(true);
  const columnRequest = useRef(0);
  const tableSearchRef = useRef<HTMLInputElement>(null);
  const tableListRef = useRef<HTMLDivElement>(null);
  const sourceInputId = useId();
  const searchTerm = tableSearch.trim().toLowerCase();
  const matchingTables = useMemo(() => {
    if (!tables) return [];
    if (!searchTerm) return tables;
    return tables.filter(table =>
      table.name.toLowerCase().includes(searchTerm) || table.comment?.toLowerCase().includes(searchTerm),
    );
  }, [tables, searchTerm]);

  useEffect(() => {
    mountedRef.current = true;
    return () => {
      mountedRef.current = false;
    };
  }, []);

  useEffect(() => {
    let active = true;
    listDataSources()
      .then(list => {
        if (!active) return;
        setSources(list);
        const selected = list.find(s => s.id === initialSourceId && s.enabled !== false) || (preferBuiltin ? list.find(s => s.builtin && s.enabled !== false) : undefined);
        if (selected) setSourceId(selected.id);
      })
      .catch(e => { if (active) setError(e instanceof Error ? e.message : String(e)); });
    return () => { active = false; };
  }, [preferBuiltin, initialSourceId]);

  useEffect(() => {
    if (!sourceId) return;
    let active = true;
    listSchemas(sourceId)
      .then(s => {
        if (!active) return;
        setSchemas(s);
        setSchema(s[0] ?? null);
      })
      .catch(e => { if (active) setError(e instanceof Error ? e.message : String(e)); });
    return () => { active = false; };
  }, [sourceId]);

  const preselectTables = preferBuiltin && sources.some(source => source.id === sourceId && source.builtin);
  useEffect(() => {
    if (!sourceId || !schema) return;
    let active = true;
    listTables(sourceId, schema)
      .then(list => {
        if (!active) return;
        setTables(list);
        if (preselectTables) setSelected(new Set(list.map(t => t.name)));
      })
      .catch(e => {
        if (!active) return;
        setTables([]);
        setError(e instanceof Error ? e.message : String(e));
      });
    return () => { active = false; };
  }, [sourceId, schema, preselectTables]);

  function changeSchema(nextSchema: string | null) {
    columnRequest.current++;
    setSchema(nextSchema);
    setTables(null);
    setTableSearch('');
    setSelected(new Set());
    setViewTable(null);
    setColumns(null);
    setError(null);
  }

  function changeTableSearch(value: string) {
    setTableSearch(value);
    if (tableListRef.current) tableListRef.current.scrollTop = 0;
  }

  async function viewColumns(t: string) {
    if (!sourceId || !schema) return;
    const request = ++columnRequest.current;
    setViewTable(t);
    setColumns(null);
    setError(null);
    try {
      const result = await listColumns(sourceId, schema, t);
      if (mountedRef.current && request === columnRequest.current) setColumns(result);
    } catch (e) {
      if (!mountedRef.current || request !== columnRequest.current) return;
      setColumns([]);
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
    tourSignal('sample-started', { groupId });
    onAssociateStart?.(tableNames);
    onClose();
    setBusy(true);
    setError(null);
    void associateWithProgress(sourceId, schema, tableNames).finally(() => {
      if (mountedRef.current) setBusy(false);
    });
  }

  /** One request per table so the page can show real sampling progress; publish once at the end. */
  async function associateWithProgress(dataSourceId: string, schemaName: string, tableNames: string[]) {
    const state: AssociationState = { tables: tableNames.map(name => ({ name, status: 'waiting' })), phase: 'sampling' };
    const report = () => onProgress?.({ phase: state.phase, tables: state.tables.map(t => ({ ...t })) });
    report();
    for (const step of state.tables) {
      step.status = 'sampling'; report();
      try {
        await associateTables(groupId, { dataSourceId, schema: schemaName, tables: [step.name], publish: false });
        step.status = 'done';
      } catch (e) {
        step.status = 'failed'; step.error = e instanceof Error ? e.message : String(e);
      }
      report();
    }
    const failures = state.tables.filter(t => t.status === 'failed');
    if (failures.length === state.tables.length) {
      state.phase = 'done'; report();
      onError?.(failures.map(f => `${f.name}：${f.error}`).join('；'));
      return;
    }
    state.phase = 'publishing'; report();
    try {
      await associateTables(groupId, { dataSourceId, schema: schemaName, tables: [], publish: true });
    } catch (e) {
      state.phase = 'done'; report();
      onError?.(`表已关联，但基础模型生成失败：${e instanceof Error ? e.message : String(e)}`);
      return;
    }
    state.phase = 'done'; report();
    onAssociated();
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
              htmlFor={sourceInputId}
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
              id={sourceInputId}
              className="da-input"
              value={sourceId ?? ''}
              onChange={e => {
                changeSchema(null);
                setSchemas([]);
                setSourceId(e.target.value || null);
              }}
              style={{ padding: '10px 12px', fontSize: '0.85rem' }}
            >
              <option value="">请选择数据源…</option>
              {sources.map(s => (
                <option key={s.id} value={s.id} disabled={s.enabled === false}>
                  {s.builtin ? `${s.name}（系统内置，只读）` : `${s.name} · ${s.creatorName || s.creatorAccount || s.kind.toUpperCase()} · ${s.role === 'USE' ? '可使用' : '可管理'}${s.enabled === false ? ' · 已停用' : ''}`}
                </option>
              ))}
            </select>
          </div>
          <div className="associate-tables-layout">
            <div className="associate-tables-pane associate-tables-schemas">
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
                <div className="associate-tables-schema-list">{schemas.map(s => (
                  <button
                    key={s}
                    type="button"
                    className="associate-tables-schema"
                    aria-pressed={schema === s}
                    style={itemStyle(schema === s)}
                    onClick={() => { if (schema !== s) changeSchema(s); }}
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
                  </button>
                ))}</div>
              )}
            </div>
            <div className="associate-tables-pane associate-tables-table-pane">
              <div style={sectionTitleStyle}>
                数据表
                {schema && (
                  <span style={{ color: 'var(--da-text-muted)', fontWeight: 400 }}>
                    {' '}
                    · {schema}
                  </span>
                )}
              </div>
              <div className="associate-tables-search">
                <input
                  ref={tableSearchRef}
                  type="text"
                  className="da-input"
                  aria-label="搜索当前数据库的表名或备注"
                  placeholder="搜索表名或备注"
                  value={tableSearch}
                  disabled={!schema}
                  onChange={e => changeTableSearch(e.target.value)}
                />
                {tableSearch && (
                  <button
                    type="button"
                    className="da-btn da-btn-sm"
                    aria-label="清除表搜索"
                    onClick={() => {
                      changeTableSearch('');
                      tableSearchRef.current?.focus();
                    }}
                  >
                    清除
                  </button>
                )}
              </div>
              {tables && (
                <div className="associate-tables-count" role="status">
                  {searchTerm ? `找到 ${matchingTables.length} 张 · 共 ${tables.length} 张` : `共 ${tables.length} 张表`}
                </div>
              )}
              <div className="associate-tables-list" ref={tableListRef}>
                {matchingTables.length === 0 ? (
                  <div className="associate-tables-empty">
                    {!schema ? '请选择数据库' : !tables ? '正在加载数据表…' : searchTerm ? '没有匹配的数据表' : '暂无数据表'}
                  </div>
                ) : matchingTables.map(t => (
                  <div
                    key={t.name}
                    className="associate-tables-row"
                    style={{ ...itemStyle(selected.has(t.name)), cursor: 'default' }}
                  >
                    <label className="associate-tables-choice">
                      <input
                        type="checkbox"
                        aria-label={`选择表 ${t.name}`}
                        checked={selected.has(t.name)}
                        onChange={() => toggle(t.name)}
                      />
                      <span className="associate-tables-name">{t.name}</span>
                      {t.comment && (
                        <span className="associate-tables-comment">{t.comment}</span>
                      )}
                    </label>
                    <button
                      type="button"
                      aria-label={`查看 ${t.name} 的字段`}
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
                ))}
              </div>
            </div>
            <div className="associate-tables-pane">
              <div style={sectionTitleStyle}>
                字段信息
                {viewTable && (
                  <span style={{ color: 'var(--da-text-muted)', fontWeight: 400 }}>
                    {' '}
                    · {viewTable}
                  </span>
                )}
              </div>
              {viewTable && columns?.length ? (
                <div
                  style={{
                    borderRadius: 8,
                    border: '1px solid var(--da-border)',
                    overflow: 'hidden',
                  }}
                >
                  <table
                    className="associate-tables-fields"
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
                                display: 'inline-block',
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
                  {!viewTable ? '点击「查看」查看字段信息' : !columns ? '正在加载字段…' : '暂无字段信息'}
                </div>
              )}
            </div>
          </div>
        </div>
        <div style={footStyle}>
          <button
            className="da-btn da-btn-primary"
            data-tour="demo-associate"
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
