import React, { useCallback, useEffect, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import BackToChatHeader from '../../components/BackToChatHeader';
import Icon from '../../components/Icon';
import {
  Dataset,
  DatasetColumn,
  getGroupDetail,
  getPreview,
  Preview,
  updateColumns,
} from '../../api/datasets';

const panelStyle: React.CSSProperties = {
  flex: 1,
  minHeight: 0,
  overflow: 'auto',
  padding: 24,
  display: 'flex',
  flexDirection: 'column',
  gap: 20,
};

const cardStyle: React.CSSProperties = {
  background: 'var(--da-surface)',
  border: '1px solid var(--da-border)',
  borderRadius: 12,
  padding: 20,
};

const thStyle: React.CSSProperties = {
  textAlign: 'left',
  padding: '10px 12px',
  borderBottom: '2px solid var(--da-border)',
  fontSize: '0.78rem',
  color: 'var(--da-text-3)',
  fontWeight: 600,
  verticalAlign: 'top',
  minWidth: 120,
};

const tdStyle: React.CSSProperties = {
  padding: '8px 12px',
  borderBottom: '1px solid var(--da-surface-sunken)',
  fontSize: '0.8rem',
  color: 'var(--da-text)',
  maxWidth: 260,
  overflow: 'hidden',
  textOverflow: 'ellipsis',
  whiteSpace: 'nowrap',
};

const overlayStyle: React.CSSProperties = {
  position: 'fixed',
  inset: 0,
  background: 'rgba(0,0,0,0.35)',
  zIndex: 1000,
  display: 'flex',
  alignItems: 'center',
  justifyContent: 'center',
};

const modalStyle: React.CSSProperties = {
  background: 'var(--da-surface)',
  borderRadius: 16,
  width: 720,
  maxHeight: '80vh',
  display: 'flex',
  flexDirection: 'column',
  boxShadow: '0 20px 60px rgba(0,0,0,0.15)',
};

const modalHeaderStyle: React.CSSProperties = {
  padding: '20px 24px 16px',
  borderBottom: '1px solid var(--da-border)',
  display: 'flex',
  justifyContent: 'space-between',
  alignItems: 'flex-start',
};

const modalBodyStyle: React.CSSProperties = {
  padding: '16px 24px',
  overflowY: 'auto',
  flex: 1,
};

const modalFooterStyle: React.CSSProperties = {
  padding: '16px 24px 20px',
  borderTop: '1px solid var(--da-border)',
  display: 'flex',
  justifyContent: 'flex-end',
  gap: 10,
};

interface FieldEditModalProps {
  columns: DatasetColumn[];
  edits: Record<string, string>;
  typeEdits: Record<string, string>;
  onChange: (name: string, description: string, sqlType: string) => void;
  onSave: () => void;
  onClose: () => void;
  busy: boolean;
}

const BASE_TYPES = ['int', 'bigint', 'float', 'double', 'decimal', 'string', 'varchar', 'text', 'date', 'datetime', 'timestamp', 'boolean'];

function extractBaseType(sqlType: string): string {
  return sqlType.replace(/\(\d+(?:,\s*\d+)?\)/, '').trim().toLowerCase();
}

function FieldEditModal({ columns, edits, typeEdits, onChange, onSave, onClose, busy }: FieldEditModalProps) {
  return (
    <div style={overlayStyle} onClick={onClose}>
      <div style={modalStyle} onClick={e => e.stopPropagation()}>
        <div style={modalHeaderStyle}>
          <div>
            <div style={{ fontSize: '1.05rem', fontWeight: 700, color: 'var(--da-text)' }}>字段信息</div>
            <div style={{ fontSize: '0.8rem', color: 'var(--da-text-muted)', marginTop: 4 }}>
              准确的字段信息将帮助DataAgent更好地回答你的问题
            </div>
          </div>
          <button
            onClick={onClose}
            style={{
              background: 'none', border: 'none', cursor: 'pointer',
              color: 'var(--da-text-muted)', padding: '4px 8px',
              display: 'flex', alignItems: 'center',
            }}
          >
            <Icon name="close" size="sm" />
          </button>
        </div>
        <div style={modalBodyStyle}>
          <table style={{ width: '100%', borderCollapse: 'collapse' }}>
            <thead>
              <tr>
                {['字段名', '原字段名', '字段类型', '字段描述'].map(h => (
                  <th
                    key={h}
                    style={{
                      textAlign: 'left', padding: '8px 10px', fontSize: '0.82rem', fontWeight: 600,
                      color: 'var(--da-text)', borderBottom: '1px solid var(--da-border)',
                    }}
                  >
                    {h}
                  </th>
                ))}
              </tr>
            </thead>
            <tbody>
              {columns.map(c => {
                const currentType = extractBaseType(typeEdits[c.name] ?? c.sqlType);
                const typeOptions = BASE_TYPES.includes(currentType)
                  ? BASE_TYPES
                  : [currentType, ...BASE_TYPES];
                return (
                  <tr key={c.name}>
                    <td style={{ padding: '10px', borderBottom: '1px solid var(--da-surface-sunken)', fontSize: '0.85rem', fontWeight: 500 }}>
                      {c.name}
                    </td>
                    <td style={{ padding: '10px', borderBottom: '1px solid var(--da-surface-sunken)', fontSize: '0.85rem', color: 'var(--da-text-muted)' }}>
                      {c.originalName}
                    </td>
                    <td style={{ padding: '10px', borderBottom: '1px solid var(--da-surface-sunken)' }}>
                      <select
                        value={currentType}
                        onChange={e => onChange(c.name, edits[c.name] ?? c.description ?? '', e.target.value)}
                        style={{
                          width: '100%', padding: '6px 8px', borderRadius: 6,
                          border: '1px solid var(--da-border-strong)', fontSize: '0.82rem',
                          background: 'var(--da-surface)', color: 'var(--da-text)',
                        }}
                      >
                        {typeOptions.map(t => (
                          <option key={t} value={t}>{t}</option>
                        ))}
                      </select>
                    </td>
                    <td style={{ padding: '10px', borderBottom: '1px solid var(--da-surface-sunken)' }}>
                      <input
                        className="da-input"
                        value={edits[c.name] ?? c.description ?? ''}
                        onChange={e => onChange(c.name, e.target.value, typeEdits[c.name] ?? c.sqlType)}
                        placeholder="请输入字段描述"
                        style={{
                          width: '100%', padding: '6px 8px', borderRadius: 6,
                          border: '1px solid var(--da-border-strong)', fontSize: '0.82rem',
                          boxSizing: 'border-box',
                        }}
                      />
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
        <div style={modalFooterStyle}>
          <button
            onClick={onClose}
            style={{
              padding: '8px 20px', borderRadius: 8, border: '1px solid var(--da-border)',
              background: 'var(--da-surface)', color: 'var(--da-text)', fontSize: '0.85rem',
              cursor: 'pointer',
            }}
          >
            取消
          </button>
          <button
            onClick={onSave}
            disabled={busy}
            style={{
              padding: '8px 20px', borderRadius: 8, border: 'none',
              background: busy ? '#a5b4fc' : 'var(--da-primary)', color: '#fff',
              fontSize: '0.85rem', fontWeight: 600, cursor: busy ? 'default' : 'pointer',
            }}
          >
            {busy ? '保存中…' : '保存'}
          </button>
        </div>
      </div>
    </div>
  );
}

export default function DatasetDetailPage() {
  const { groupId = '', datasetId = '' } = useParams();
  const navigate = useNavigate();
  const [dataset, setDataset] = useState<Dataset | null>(null);
  const [preview, setPreview] = useState<Preview | null>(null);
  const [edits, setEdits] = useState<Record<string, string>>({});
  const [typeEdits, setTypeEdits] = useState<Record<string, string>>({});
  const [error, setError] = useState<string | null>(null);
  const [saved, setSaved] = useState(false);
  const [busy, setBusy] = useState(false);
  const [modalOpen, setModalOpen] = useState(false);
  const [descEditing, setDescEditing] = useState(false);
  const [descDraft, setDescDraft] = useState('');

  const load = useCallback(async () => {
    try {
      const detail = await getGroupDetail(groupId);
      const ds = detail.datasets.find(d => d.id === datasetId) ?? null;
      setDataset(ds);
      if (ds) {
        setPreview(await getPreview(ds.id, 20));
        const descInit: Record<string, string> = {};
        const typeInit: Record<string, string> = {};
        for (const c of ds.columns) {
          descInit[c.name] = c.description ?? c.originalName ?? '';
          typeInit[c.name] = c.sqlType;
        }
        setEdits(descInit);
        setTypeEdits(typeInit);
        setDescDraft(ds.description ?? '');
      }
      setError(null);
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
  }, [groupId, datasetId]);

  useEffect(() => {
    load();
  }, [load]);

  async function handleSave() {
    if (!dataset) return;
    setBusy(true);
    setSaved(false);
    try {
      const updates = dataset.columns.map(c => ({
        name: c.name,
        description: edits[c.name] ?? '',
      }));
      await updateColumns(dataset.id, updates);
      setSaved(true);
      setModalOpen(false);
      await load();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  }

  async function handleDescSave() {
    if (!dataset) return;
    setBusy(true);
    try {
      await updateColumns(dataset.id, dataset.columns.map(c => ({
        name: c.name,
        description: edits[c.name] ?? c.description ?? '',
      })));
      setDescEditing(false);
      await load();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  }

  function handleFieldChange(name: string, description: string, sqlType: string) {
    setEdits(prev => ({ ...prev, [name]: description }));
    setTypeEdits(prev => ({ ...prev, [name]: sqlType }));
  }

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100%', minHeight: 0 }}>
      <BackToChatHeader title={dataset?.name ?? '数据集'} subtitle={dataset?.tableName ?? ''} />
      <div style={{ padding: '10px 24px 0' }}>
        <button className="da-btn" onClick={() => navigate(`/configure/datasets/${groupId}`)}>
          ← 返回知识库
        </button>
        {error && (
          <span style={{ color: 'var(--da-danger)', marginLeft: 12, fontSize: '0.8rem' }}>{error}</span>
        )}
        {saved && (
          <span style={{ color: '#047857', marginLeft: 12, fontSize: '0.8rem' }}>
            已保存，agent 立即可见新语义
          </span>
        )}
      </div>
      <div style={panelStyle}>
        {/* Merged card: title + description + basic info */}
        <div style={cardStyle}>
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', marginBottom: 12 }}>
            <div style={{ flex: 1, minWidth: 0 }}>
              <div style={{ fontSize: '1.1rem', fontWeight: 700, color: 'var(--da-text)', marginBottom: 8 }}>
                {dataset?.sourceFileName ?? dataset?.name ?? '数据集'}
              </div>
              {descEditing ? (
                <div style={{ display: 'flex', gap: 8, alignItems: 'flex-start' }}>
                  <textarea
                    value={descDraft}
                    onChange={e => setDescDraft(e.target.value)}
                    rows={3}
                    style={{
                      flex: 1, padding: '8px 10px', borderRadius: 8,
                      border: '1px solid var(--da-border-strong)', fontSize: '0.85rem',
                      color: 'var(--da-text)', resize: 'vertical', fontFamily: 'inherit',
                    }}
                  />
                  <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
                    <button
                      onClick={handleDescSave}
                      disabled={busy}
                      style={{
                        padding: '5px 14px', borderRadius: 6, border: 'none',
                        background: 'var(--da-primary)', color: '#fff', fontSize: '0.78rem',
                        fontWeight: 600, cursor: busy ? 'default' : 'pointer',
                      }}
                    >
                      保存
                    </button>
                    <button
                      onClick={() => { setDescEditing(false); setDescDraft(dataset?.description ?? ''); }}
                      style={{
                        padding: '5px 14px', borderRadius: 6, border: '1px solid var(--da-border)',
                        background: 'var(--da-surface)', color: 'var(--da-text)', fontSize: '0.78rem',
                        cursor: 'pointer',
                      }}
                    >
                      取消
                    </button>
                  </div>
                </div>
              ) : (
                <div style={{ display: 'flex', alignItems: 'flex-start', gap: 8 }}>
                  <div style={{ fontSize: '0.85rem', color: 'var(--da-text-2)', whiteSpace: 'pre-wrap', flex: 1, lineHeight: 1.6 }}>
                    {dataset?.description ?? '—'}
                  </div>
                  <button
                    onClick={() => { setDescEditing(true); setDescDraft(dataset?.description ?? ''); }}
                    title="编辑描述"
                    style={{
                      background: 'none', border: 'none', cursor: 'pointer',
                      color: 'var(--da-text-muted)', flexShrink: 0,
                      padding: '2px 4px', display: 'flex', alignItems: 'center',
                    }}
                  >
                    <Icon name="edit" size="sm" />
                  </button>
                </div>
              )}
            </div>
          </div>

          <div style={{
            display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '6px 20px',
            fontSize: '0.82rem', marginTop: 16, paddingTop: 14,
            borderTop: '1px solid var(--da-border)',
          }}>
            <div>
              <span style={{ color: 'var(--da-text-3)' }}>来源：</span>
              {dataset?.origin === 'datasource' ? '数据源关联' : '文件上传'}
            </div>
            <div>
              <span style={{ color: 'var(--da-text-3)' }}>库.表：</span>
              {dataset?.schemaName}.{dataset?.tableName}
            </div>
            <div>
              <span style={{ color: 'var(--da-text-3)' }}>行数：</span>
              {dataset?.rowCount ?? '-'}
            </div>
            <div>
              <span style={{ color: 'var(--da-text-3)' }}>来源文件：</span>
              {dataset?.sourceFileName ?? '-'}
            </div>
          </div>
        </div>

        {/* Table preview card */}
        <div style={cardStyle}>
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 12 }}>
            <div style={{ fontSize: '0.95rem', fontWeight: 600, color: 'var(--da-text)' }}>
              表格解析（前 {preview?.rows.length ?? 0} 行）
            </div>
            <button
              onClick={() => setModalOpen(true)}
              style={{
                background: 'none', border: 'none', color: 'var(--da-primary)',
                fontSize: '0.82rem', cursor: 'pointer', fontWeight: 500,
                display: 'flex', alignItems: 'center', gap: 4,
              }}
            >
              <Icon name="edit" size="sm" /> 修改字段类型/描述
            </button>
          </div>
          {preview && preview.rows.length > 0 ? (
            <div style={{ overflowX: 'auto' }}>
              <table className="da-table">
                <thead>
                  <tr>
                    {preview.columns.map(c => (
                      <th key={c.name} style={thStyle}>
                        <div style={{ fontWeight: 600, color: 'var(--da-text)', fontSize: '0.82rem', marginBottom: 3 }}>
                          {c.originalName || c.name}
                        </div>
                        <div style={{ color: 'var(--da-text-2)', fontSize: '0.75rem', marginBottom: 4, lineHeight: 1.4 }}>
                          {c.description || '—'}
                        </div>
                        <div style={{
                          display: 'inline-block', padding: '1px 8px', borderRadius: 4,
                          background: '#f0f0ff', color: '#6366f1', fontSize: '0.7rem',
                          fontWeight: 600, marginBottom: 3,
                        }}>
                          {c.sqlType}
                        </div>
                        <div style={{ color: 'var(--da-text-3)', fontSize: '0.7rem', fontFamily: 'monospace' }}>
                          {c.name}
                        </div>
                      </th>
                    ))}
                  </tr>
                </thead>
                <tbody>
                  {preview.rows.map((r, i) => (
                    <tr key={i}>
                      {r.map((v, j) => (
                        <td key={j} style={tdStyle} title={v ?? ''}>
                          {v ?? ''}
                        </td>
                      ))}
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          ) : (
            <div style={{ color: 'var(--da-text-muted)', fontSize: '0.85rem' }}>暂无数据行。</div>
          )}
        </div>

        {/* Field edit modal */}
        {modalOpen && dataset && (
          <FieldEditModal
            columns={dataset.columns}
            edits={edits}
            typeEdits={typeEdits}
            onChange={handleFieldChange}
            onSave={handleSave}
            onClose={() => setModalOpen(false)}
            busy={busy}
          />
        )}
      </div>
    </div>
  );
}
