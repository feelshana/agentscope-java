import React, { useCallback, useEffect, useState } from 'react';
import { useParams } from 'react-router-dom';
import { KnowledgeBreadcrumb } from '../../components/KnowledgeWorkspace';
import Icon from '../../components/Icon';
import {
  Dataset,
  DatasetColumn,
  getGroupDetail,
  getPreview,
  Preview,
  updateColumns,
  updateDatasetDescription,
} from '../../api/datasets';

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
  width: 'min(1120px, 94vw)',
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
              准确的字段信息将帮助红海DataAgent更好地回答你的问题
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
          <table style={{ width: '100%', borderCollapse: 'collapse', tableLayout: 'fixed' }}><colgroup><col style={{ width: '22%' }} /><col style={{ width: '17%' }} /><col style={{ width: '16%' }} /><col style={{ width: '45%' }} /></colgroup>
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
                        disabled
                        title="数据类型只读；此接口仅支持修改字段说明"
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
                      <textarea
                        rows={3}
                        aria-label={`${c.name} 字段描述`}
                        className="da-input"
                        value={edits[c.name] ?? c.description ?? ''}
                        onChange={e => onChange(c.name, e.target.value, typeEdits[c.name] ?? c.sqlType)}
                        placeholder="请输入字段描述"
                        style={{
                          width: '100%', padding: '6px 8px', borderRadius: 6,
                          border: '1px solid var(--da-border-strong)', fontSize: '0.82rem',
                          boxSizing: 'border-box', resize: 'vertical', lineHeight: 1.6,
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
  const [groupName, setGroupName] = useState('知识库');
  const [tab, setTab] = useState<'preview' | 'fields' | 'technical'>('preview');
  const [loading, setLoading] = useState(true);
  const [dataset, setDataset] = useState<Dataset | null>(null);
  const [preview, setPreview] = useState<Preview | null>(null);
  const [edits, setEdits] = useState<Record<string, string>>({});
  const [typeEdits, setTypeEdits] = useState<Record<string, string>>({});
  const [error, setError] = useState<string | null>(null);
  const [saved, setSaved] = useState(false);
  const [busy, setBusy] = useState(false);
  const [modalOpen, setModalOpen] = useState(false);
  const [descriptionOpen, setDescriptionOpen] = useState(false);
  const [descriptionDraft, setDescriptionDraft] = useState('');
  const [descriptionError, setDescriptionError] = useState<string | null>(null);
  const [savedMessage, setSavedMessage] = useState('');

  const load = useCallback(async () => {
    setLoading(true);
    setDataset(null);
    setPreview(null);
    try {
      const detail = await getGroupDetail(groupId);
      setGroupName(detail.group.name);
      const ds = detail.datasets.find(d => d.id === datasetId) ?? null;
      setDataset(ds);
      if (ds) {
        const descInit: Record<string, string> = {};
        const typeInit: Record<string, string> = {};
        for (const c of ds.columns) {
          descInit[c.name] = c.description ?? c.originalName ?? '';
          typeInit[c.name] = c.sqlType;
        }
        setEdits(descInit);
        setTypeEdits(typeInit);
        setPreview(await getPreview(ds.id, 20));
      }
      setError(ds ? null : '数据集不存在或已被删除');
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally { setLoading(false); }
  }, [groupId, datasetId]);

  useEffect(() => {
    setTab('preview');
    setSaved(false);
    setModalOpen(false);
    setDescriptionOpen(false);
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
      setSavedMessage('字段说明已保存');
      setModalOpen(false);
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

  async function saveDescription() {
    if (!dataset || busy) return;
    setBusy(true);
    setDescriptionError(null);
    setSaved(false);
    try {
      const updated = await updateDatasetDescription(dataset.id, descriptionDraft);
      setDataset(updated);
      setDescriptionOpen(false);
      setSavedMessage('数据集描述已保存');
      setSaved(true);
    } catch (e) {
      setDescriptionError(e instanceof Error ? e.message : String(e));
    } finally { setBusy(false); }
  }

  const originLabel = dataset?.origin === 'datasource' ? '数据源关联' : dataset?.origin === 'derived' ? '派生数据' : '文件上传';
  return (
    <div className="kw-page">
      <KnowledgeBreadcrumb items={[{ label: '返回对话', to: '/chat' }, { label: '知识库', to: '/configure/datasets' }, { label: groupName, to: `/configure/datasets/${groupId}` }, { label: dataset?.name ?? '数据集' }]} />
      <div className="kw-scroll"><div className="kw-content">
        <div className="kw-pagehead">
          <div className="kw-heading"><div className="kw-datasethead"><div className="kw-icon"><Icon name="table" /></div><div><h1>{dataset?.name ?? '数据集'}</h1><p>{originLabel}的数据集</p></div></div>
            <p style={{ marginTop: 17 }}>{dataset?.description || '暂无说明'}</p>
            <div className="kw-infochips"><span><Icon name="table" size="sm" />{dataset?.rowCount.toLocaleString() ?? '—'} 行</span><span>{dataset?.columns.length ?? '—'} 个字段</span>{dataset?.sourceFileName && <span><Icon name={dataset.origin === 'datasource' ? 'database' : 'file'} size="sm" />{dataset.sourceFileName}</span>}</div>
          </div>
          <div className="kw-actions"><button className="da-btn" disabled={!dataset || busy} onClick={() => { setDescriptionDraft(dataset?.description ?? ''); setDescriptionError(null); setDescriptionOpen(true); }}><Icon name="edit" size="sm" />编辑数据集描述</button></div>
        </div>
        {error && <div role="alert" className="kw-alert">{error}</div>}
        {saved && <div role="status" style={{ color: 'var(--da-success)', fontSize: 12, marginBottom: 20 }}>{savedMessage}</div>}
        <div className="kw-tabs" role="tablist" aria-label="数据集信息">{([{ key: 'preview', label: '数据预览' }, { key: 'fields', label: '字段信息' }, { key: 'technical', label: '技术信息' }] as const).map(t => <button key={t.key} role="tab" aria-selected={tab === t.key} className={tab === t.key ? 'active' : ''} onClick={() => setTab(t.key)}>{t.label}</button>)}</div>
        {loading && <div className="kw-empty">加载数据集…</div>}
        {!loading && dataset && tab === 'preview' && <>
          <div className="kw-toolbar"><span style={{ fontSize: 12 }}>数据预览</span><span className="kw-count">前 {preview?.rows.length ?? 0} 行</span><span className="kw-spacer" /><span className="kw-count">悬停字段查看说明 · 横向滚动查看全部字段</span></div>
          {preview && preview.rows.length > 0 ? <div className="kw-tablebox"><div className="kw-table-scroll" style={{ maxHeight: 520 }}><table className="kw-table kw-preview"><thead><tr><th className="rownum">#</th>{preview.columns.map(c => <th key={c.name} title={`${c.description || '暂无字段说明'} · ${c.sqlType}`}>{c.originalName || c.name}{c.originalName && c.originalName !== c.name && <small>{c.name}</small>}</th>)}</tr></thead><tbody>{preview.rows.map((row, i) => <tr key={i}><td className="rownum">{i + 1}</td>{row.map((v, j) => <td key={j} className={/^(decimal|float|double|numeric|real)/i.test(preview.columns[j]?.sqlType ?? '') ? 'numeric' : ''} title={v == null ? 'NULL' : String(v)}>{v == null ? <span style={{ color: 'var(--kw-muted)' }}>NULL</span> : String(v)}</td>)}</tr>)}</tbody></table></div><div className="kw-tablebottom"><span>显示 {preview.rows.length} 行 / 共 {dataset.rowCount.toLocaleString()} 行</span><span>保持原始数据值，未做精度转换</span></div></div> : <div className="kw-empty">{error ? '数据预览暂不可用' : '暂无数据行'}</div>}
        </>}
        {!loading && dataset && tab === 'fields' && <>
          <div className="kw-toolbar"><span className="kw-count">字段含义与类型集中管理，不占用数据预览表头。</span><span className="kw-spacer" /><button className="da-btn da-btn-sm" disabled={busy} onClick={() => setModalOpen(true)}><Icon name="edit" size="sm" />编辑字段说明</button></div>
          <div className="kw-tablebox"><div className="kw-table-scroll"><table className="kw-table"><thead><tr><th>字段名称</th><th>原字段名称</th><th>数据类型</th><th>业务说明</th></tr></thead><tbody>{dataset.columns.map(c => <tr key={c.name}><td><code className="kw-code">{c.name}</code></td><td>{c.originalName || '—'}</td><td><span className="kw-type">{c.sqlType}</span></td><td style={{ whiteSpace: 'normal', minWidth: 240 }}>{c.description || '暂无说明'}</td></tr>)}</tbody></table></div></div>
        </>}
        {!loading && dataset && tab === 'technical' && <div className="kw-technical"><div><span>所属知识库</span><span>{groupName}</span></div><div><span>来源类型</span><span>{originLabel}</span></div><div><span>来源文件 / 表</span><span>{dataset.sourceFileName || '—'}</span></div><div><span>物理库表</span><code className="kw-code">{[dataset.schemaName, dataset.tableName].filter(Boolean).join('.')}</code></div><div><span>记录 / 字段</span><span>{dataset.rowCount.toLocaleString()} 行 / {dataset.columns.length} 个字段</span></div><div><span>版本</span><span>v{dataset.currentVersion ?? 1}</span></div></div>}
      </div></div>
      {modalOpen && dataset && <FieldEditModal columns={dataset.columns} edits={edits} typeEdits={typeEdits} onChange={handleFieldChange} onSave={handleSave} onClose={() => { if (!busy) setModalOpen(false); }} busy={busy} />}
      {descriptionOpen && dataset && <div style={overlayStyle} onClick={() => { if (!busy) setDescriptionOpen(false); }}>
        <form role="dialog" aria-modal="true" aria-labelledby="dataset-description-title" style={{ ...modalStyle, width: 'min(600px, 90vw)' }} onClick={e => e.stopPropagation()} onSubmit={e => { e.preventDefault(); void saveDescription(); }}>
          <div style={modalHeaderStyle}><div><strong id="dataset-description-title">编辑数据集描述</strong><p style={{ fontSize: 13, color: 'var(--da-text-muted)', marginBottom: 0 }}>描述数据集的业务含义，帮助智能体理解和选用数据。</p></div></div>
          <div style={modalBodyStyle}><label htmlFor="dataset-description">{dataset.name}</label><textarea id="dataset-description" autoFocus className="da-input" value={descriptionDraft} disabled={busy} onChange={e => setDescriptionDraft(e.target.value)} rows={6} style={{ width: '100%', boxSizing: 'border-box', resize: 'vertical', marginTop: 12 }} />{descriptionError && <div role="alert" className="kw-alert">{descriptionError}</div>}</div>
          <div style={modalFooterStyle}><button type="button" className="da-btn" disabled={busy} onClick={() => setDescriptionOpen(false)}>取消</button><button type="submit" className="da-btn da-btn-primary" disabled={busy}>{busy ? '保存中…' : '保存'}</button></div>
        </form>
      </div>}
    </div>
  );
}
