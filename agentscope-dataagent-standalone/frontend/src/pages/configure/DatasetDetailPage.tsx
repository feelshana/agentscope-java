import { Fragment, useEffect, useMemo, useRef, useState, useLayoutEffect, useCallback } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { KnowledgeBreadcrumb } from '../../components/KnowledgeWorkspace';
import Icon from '../../components/Icon';
import DatasetFieldInfo from './DatasetFieldInfo';
import { Dataset, DatasetColumn, Preview, deleteDataset, getGroupDetail, getPreview, updateColumns, updateDatasetDescription, refreshDatasetSample } from '../../api/datasets';
import '../../detail-surfaces.css';
import './KnowledgeDetails.css';

type Tab = 'preview' | 'fields' | 'technical';
const numeric = (c: DatasetColumn | undefined) => /^(tinyint|smallint|int|bigint|decimal|float|double|numeric|real)/i.test(c?.sqlType ?? '');
const label = (c: DatasetColumn) => c.displayName?.trim() || c.originalName || c.name;

export default function DatasetDetailPage() {
  const { groupId = '', datasetId = '' } = useParams();
  const navigate = useNavigate();
  const [groupName, setGroupName] = useState('知识库');
  const [canManage, setCanManage] = useState(false);
  const [dataset, setDataset] = useState<Dataset | null>(null);
  const [preview, setPreview] = useState<Preview | null>(null);
  const [tab, setTab] = useState<Tab>('preview');
  const [loading, setLoading] = useState(true);
  const [previewLoading, setPreviewLoading] = useState(false);
  const [previewError, setPreviewError] = useState('');
  const [error, setError] = useState('');
  const [message, setMessage] = useState('');
  const [busy, setBusy] = useState(false);
  const [search, setSearch] = useState('');
  const [editField, setEditField] = useState<DatasetColumn | null>(null);
  const [description, setDescription] = useState('');
  const [descriptionEdit, setDescriptionEdit] = useState(false);
  const [descriptionDraft, setDescriptionDraft] = useState('');
  const [info, setInfo] = useState<{ column: DatasetColumn; anchor: HTMLButtonElement } | null>(null);
  const closeInfo = useCallback(() => setInfo(null), []);
  const previewCard = useRef<HTMLDivElement>(null);
  const generation = useRef(0);
  const dirty = !!editField && description !== (editField.description ?? '')
    || descriptionEdit && descriptionDraft !== (dataset?.description ?? '');

  useEffect(() => {
    const current = ++generation.current;
    const controller = new AbortController();
    setDataset(null); setPreview(null); setCanManage(false); setLoading(true); setError(''); setBusy(false); setPreviewError('');
    setTab('preview'); setEditField(null); setDescriptionEdit(false); setInfo(null); setSearch(''); setMessage('');
    getGroupDetail(groupId, controller.signal).then(detail => {
      if (current !== generation.current) return;
      setGroupName(detail.group.name); setCanManage(detail.group.canManage);
      const ds = detail.datasets.find(d => d.id === datasetId);
      if (!ds) throw new Error('数据集不存在或已被删除');
      setDataset(ds);
    }).catch(e => { if (current === generation.current) setError((e as Error).message); })
      .finally(() => { if (current === generation.current) setLoading(false); });
    return () => { generation.current++; controller.abort(); };
  }, [groupId, datasetId]);

  useEffect(() => {
    if (!dataset) return;
    const current = generation.current;
    const controller = new AbortController();
    setPreviewLoading(true); setPreviewError('');
    getPreview(dataset.id, 20, controller.signal).then(data => { if (current === generation.current && !controller.signal.aborted) setPreview(data); })
      .catch(e => { if (current === generation.current) setPreviewError((e as Error).message); })
      .finally(() => { if (current === generation.current) setPreviewLoading(false); });
    return () => controller.abort();
  }, [dataset?.id]);

  useEffect(() => {
    if (!dirty) return;
    const warn = (e: BeforeUnloadEvent) => { e.preventDefault(); e.returnValue = ''; };
    window.addEventListener('beforeunload', warn);
    return () => window.removeEventListener('beforeunload', warn);
  }, [dirty]);

  useLayoutEffect(() => {
    const card = previewCard.current;
    if (tab !== 'preview' || !card) return;
    const resize = () => {
      const bottomSpace = window.innerWidth <= 760 ? 28 : 56;
      const height = Math.max(280, window.innerHeight - card.getBoundingClientRect().top - bottomSpace);
      card.style.setProperty('--kd-preview-height', `${Math.round(height)}px`);
    };
    resize();
    const observer = new ResizeObserver(resize);
    const scroll = card.closest('.kw-scroll');
    const header = scroll?.querySelector('.kd-head');
    if (scroll) observer.observe(scroll);
    if (header) observer.observe(header);
    window.addEventListener('resize', resize);
    return () => { observer.disconnect(); window.removeEventListener('resize', resize); };
  }, [tab, dataset?.id, dataset?.description, descriptionEdit, error, message]);

  function abandon() { return !dirty || window.confirm('有尚未保存的修改，放弃这些修改吗？'); }
  function switchTab(next: Tab) {
    if (busy || (next !== tab && !abandon())) return;
    setEditField(null); setDescriptionEdit(false); setSearch(''); setTab(next); setError('');
  }
  function startEdit(c: DatasetColumn) {
    if (busy || !abandon()) return;
    setDescriptionEdit(false); setEditField(c); setDescription(c.description ?? ''); setError('');
  }
  async function saveField() {
    if (!dataset || !editField || busy) return;
    const current = generation.current;
    setBusy(true); setError('');
    try {
      const updated = await updateColumns(dataset.id, [{ name: editField.name, description }]);
      if (current !== generation.current) return;
      setDataset(updated); setEditField(null); setMessage('字段资料已保存。语义模型中的业务口径请在建模工作台核对后发布。');
    } catch (e) { if (current === generation.current) setError((e as Error).message); }
    finally { if (current === generation.current) setBusy(false); }
  }
  async function saveDescription() {
    if (!dataset || busy) return;
    setBusy(true); setError(''); const current = generation.current;
    try { const result = await updateDatasetDescription(dataset.id, descriptionDraft); if (current === generation.current) { setDataset(result); setDescriptionEdit(false); setMessage('数据集描述已保存'); } }
    catch (e) { if (current === generation.current) setError((e as Error).message); }
    finally { if (current === generation.current) setBusy(false); }
  }
  async function refreshSample() {
    if (!dataset || busy || !abandon()) return;
    setBusy(true); setError(''); const current = generation.current;
    try {
      const result = await refreshDatasetSample(dataset.id);
      if (current !== generation.current) return;
      setDataset(result);
      const nextPreview = await getPreview(dataset.id, 20);
      if (current === generation.current) { setPreview(nextPreview); setMessage('样本已刷新，业务模型重新发布后用于问数'); }
    } catch (e) { if (current === generation.current) setError((e as Error).message); }
    finally { if (current === generation.current) setBusy(false); }
  }
  async function remove() {
    if (!dataset || !canManage || busy || !abandon()) return;
    const external = dataset.origin === 'datasource';
    if (!window.confirm(external ? '移除关联？数据库中的原始表和数据会保留。' : '删除数据集及其上传的物理数据？此操作无法撤销。')) return;
    setBusy(true); setError('');
    const current = generation.current;
    try { await deleteDataset(dataset.id); if (current === generation.current) navigate(`/configure/datasets/${groupId}`); }
    catch (e) { setError((e as Error).message); setBusy(false); }
  }
  const rows = useMemo(() => preview?.rows.map((values, index) => ({ values, index })).filter(row => row.values.some(value => String(value ?? '').toLowerCase().includes(search.trim().toLowerCase()))) ?? [], [preview, search]);
  const fields = useMemo(() => dataset?.columns.filter(c => [c.name, c.originalName, c.sqlType, c.description].join(' ').toLowerCase().includes(search.trim().toLowerCase())) ?? [], [dataset, search]);
  const sourceLabel = dataset?.origin === 'datasource' ? '数据源关联' : dataset?.origin === 'derived' ? '派生数据' : '文件上传';
  const columns = preview?.columns.map(c => dataset?.columns.find(d => d.name === c.name) ?? c) ?? [];
  return <div className="kw-page kd-page">
    <KnowledgeBreadcrumb items={[{ label: '知识库', to: '/configure/datasets' }, { label: groupName, to: `/configure/datasets/${groupId}` }, { label: dataset?.name ?? '数据集' }]} />
    <div className="kw-scroll"><div className="kw-content">
      <header className="kd-head"><div className="kd-heading"><div className="kd-title"><span className="kd-tile"><Icon name="table" /></span><div><h1>{dataset?.name ?? (loading ? '加载数据集…' : '数据集')}</h1><div className="kd-meta"><span>{sourceLabel}</span><span>·</span><span>{dataset?.rowCount == null ? '总行数未统计' : dataset.rowCount.toLocaleString() + ' 行'}</span><span>· {dataset?.columns.length ?? '—'} 个字段</span>{dataset && <span>· v{dataset.currentVersion ?? 1}</span>}</div></div></div>
        {!descriptionEdit ? <div className="kd-description"><p>{dataset?.description || '暂无说明'}</p>{canManage && <button className="dw-text" disabled={busy} onClick={() => { if (abandon()) { setEditField(null); setDescriptionDraft(dataset?.description ?? ''); setDescriptionEdit(true); } }}>{dataset?.description ? '编辑' : '添加说明'}</button>}</div>
          : <form className="kd-description-form" onSubmit={e => { e.preventDefault(); void saveDescription(); }}><label>业务说明<textarea className="da-input" autoFocus rows={3} maxLength={10000} disabled={busy} value={descriptionDraft} onChange={e => setDescriptionDraft(e.target.value)} /></label><div className="kw-actions"><button type="button" className="da-btn" disabled={busy} onClick={() => { if (abandon()) setDescriptionEdit(false); }}>取消</button><button className="da-btn da-btn-primary" disabled={busy}>保存修改</button></div></form>}
      </div><div className="kw-actions">{canManage && dataset?.origin === 'datasource' && <button className="da-btn" disabled={busy} onClick={() => void refreshSample()}><Icon name="refresh" size="sm" />{busy ? '处理中…' : '刷新样本'}</button>}</div></header>
      {dataset?.origin === 'datasource' && <div className="kd-notice" role="status"><Icon name="info" size="sm" /><span>{dataset.sampling?.status === 'SUCCESS' ? `已采样 ${dataset.sampling.sampledRows} 行，仅为观察值，非完整枚举；采集时间：${new Date(dataset.sampling.sampledAt).toLocaleString('zh-CN')}` : dataset.sampling?.status === 'FAILED' ? '采样失败，已保留表结构，可手动重试' : dataset.sampling?.status === 'DISABLED' ? '采样已关闭' : '尚无有效采样摘要'}。总行数未统计。</span></div>}
      {error && !editField && <div className="kw-alert" role="alert">{error}</div>}{message && <div className="kd-notice success" role="status">{message}</div>}
      {dataset && <><div className="kd-tabline"><div className="kd-tabs" role="tablist" aria-label="数据集信息">{([{ key: 'preview', title: '数据预览' }, { key: 'fields', title: '字段信息' }, { key: 'technical', title: '技术信息' }] as const).map(t => <button key={t.key} id={`kd-tab-${t.key}`} role="tab" aria-selected={tab === t.key} aria-controls="kd-panel" onClick={() => switchTab(t.key)}>{t.title}{t.key === 'fields' && <small>{dataset.columns.length}</small>}</button>)}</div>
        {tab !== 'technical' && <><span className="kd-meta">{tab === 'preview' ? `当前样本 ${preview?.rows.length ?? 0} 行` : `共 ${dataset.columns.length} 个字段`}</span><label className="kw-search"><Icon name="search" size="sm" /><input aria-label={tab === 'preview' ? '在当前样本中查找' : '搜索字段'} placeholder={tab === 'preview' ? '在当前样本中查找' : '搜索字段名称、原字段名或说明'} disabled={!!editField || descriptionEdit} value={search} onChange={e => setSearch(e.target.value)} /></label></>}
      </div><section id="kd-panel" role="tabpanel" aria-labelledby={`kd-tab-${tab}`}>
        {tab === 'preview' && <div ref={previewCard} className="kd-table-card kd-preview-card">{previewLoading ? <div className="kw-empty" role="status">加载数据预览…</div> : previewError ? <div className="kw-empty" role="alert">{previewError}</div> : <div className="kw-table-scroll kd-preview-scroll"><table className="kw-table kw-preview"><thead><tr><th className="rownum">#</th>{columns.map(c => <th key={c.name} className={numeric(c) ? 'numeric' : ''}><div className="kd-field-head"><div><strong>{label(c)}</strong><small>{c.name}</small></div><button className="kd-info" aria-label={`查看 ${label(c)} 的字段说明`} aria-expanded={info?.column.name === c.name} aria-controls={info?.column.name === c.name ? 'kd-field-help' : undefined} onClick={e => setInfo(info?.anchor === e.currentTarget ? null : { column: c, anchor: e.currentTarget })}><Icon name="info" size="sm" /></button></div></th>)}</tr></thead><tbody>{rows.map(row => <tr key={row.index}><td className="rownum">{row.index + 1}</td>{row.values.map((v, j) => <td key={j} className={numeric(columns[j]) ? 'numeric' : ''} title={v == null ? 'NULL' : String(v)}>{v == null ? <span className="kd-muted">NULL</span> : String(v)}</td>)}</tr>)}</tbody></table>{!rows.length && <div className="kw-empty">{search ? '当前样本没有匹配的数据' : '暂无数据行'}</div>}</div>}<footer className="kw-tablebottom"><span>显示 {rows.length} 行 · {dataset.rowCount == null ? '总行数未统计' : `总计 ${dataset.rowCount.toLocaleString()} 行`}</span><span>保留原始数据值 · 点击字段旁的图标查看说明</span></footer></div>}
        {tab === 'fields' && <div className="kd-table-card"><div className="kw-table-scroll"><table className="kw-table kd-fields"><thead><tr><th>字段名称</th><th>原字段名称</th><th>数据类型</th><th>业务说明</th></tr></thead><tbody>{fields.map(c => <Fragment key={c.name}><tr><td><code>{c.name}</code></td><td>{c.originalName || <span className="kd-muted">—</span>}</td><td><span className="kw-type" title="数据类型只读，此接口不修改物理表类型">{c.sqlType}</span></td><td><div className="kd-field-description"><span>{c.description || <span className="kd-muted">待补充说明</span>}</span>{canManage && <button className="kd-info kd-edit-field" disabled={busy} aria-label={`编辑 ${c.name} 的业务说明`} onClick={() => startEdit(c)}><Icon name="edit" size="sm" /></button>}</div></td></tr>
          {editField?.name === c.name && <tr className="kd-edit-row"><td colSpan={4}><form onSubmit={e => { e.preventDefault(); void saveField(); }} onKeyDown={e => { if (e.nativeEvent.isComposing || busy) return; if (e.key === 'Escape') { e.preventDefault(); if (abandon()) setEditField(null); } if (e.key === 'Enter' && (e.ctrlKey || e.metaKey)) { e.preventDefault(); void saveField(); } }}><div className="kd-edit-grid"><div className="kd-field-readonly"><strong>字段信息</strong><dl><div><dt>字段名称</dt><dd><code>{c.name}</code></dd></div><div><dt>原字段名称</dt><dd>{c.originalName || '—'}</dd></div><div><dt>数据类型</dt><dd><span className="kw-type">{c.sqlType}</span></dd></div></dl><small>名称和类型只读，仅保存业务说明。</small></div><label>业务说明<textarea className="da-input" autoFocus rows={3} maxLength={10000} disabled={busy} value={description} onChange={e => setDescription(e.target.value)} placeholder="字段的业务含义、单位或取值说明" /></label></div>{error && <div className="kw-alert" role="alert">{error}</div>}<div className="kw-actions"><span className="kd-muted">Ctrl / ⌘ + Enter 保存 · Esc 取消</span><button type="button" className="da-btn" disabled={busy} onClick={() => { if (abandon()) setEditField(null); }}>取消</button><button className="da-btn da-btn-primary" disabled={busy}>{busy ? '保存中…' : '保存修改'}</button></div></form></td></tr>}</Fragment>)}</tbody></table>{!fields.length && <div className="kw-empty">没有匹配的字段</div>}</div></div>}
        {tab === 'technical' && <div className="kd-technical"><div className="kd-table-card"><h2>数据集信息</h2><dl>{[['所属知识库', groupName], ['来源类型', sourceLabel], ['来源文件 / 表', dataset.sourceFileName || '—'], ['物理库表', [dataset.schemaName, dataset.tableName].filter(Boolean).join('.')], ['规模', `${dataset.rowCount == null ? '总行数未统计' : dataset.rowCount.toLocaleString() + ' 行'} · ${dataset.columns.length} 字段`], ['版本', `v${dataset.currentVersion ?? 1}`]].map(([k, v]) => <div key={k}><dt>{k}</dt><dd>{v}</dd></div>)}</dl></div><aside><section className="kd-table-card"><h2><Icon name="info" size="sm" />样本与数据</h2><p>{dataset.origin === 'datasource' ? '当前仅展示采样记录，总行数未统计。刷新样本不会修改外部数据库的原表。' : `文件导入后保留原始字段和数据值，当前展示前 ${preview?.rows.length ?? 0} 行。更新数据请在知识库中重新上传文件。`}</p>{canManage && (dataset.origin === 'datasource' ? <button className="da-btn" disabled={busy} onClick={() => void refreshSample()}>刷新样本</button> : <button className="da-btn" onClick={() => navigate(`/configure/datasets/${groupId}?upload=1`)}><Icon name="upload" size="sm" />上传更新文件</button>)}</section>{canManage && <section className="kd-table-card kd-danger"><h2><Icon name="info" size="sm" />{dataset.origin === 'datasource' ? '移除关联' : '删除数据集'}</h2><p>{dataset.origin === 'datasource' ? '从知识库移除关联，外部数据库中的原始表保留。' : '删除后将失去该数据集及其字段说明，请先确认相关模型和问答的依赖。'}</p><button className="da-btn" disabled={busy} onClick={() => void remove()}>{dataset.origin === 'datasource' ? '移除关联' : '删除数据集'}</button></section>}</aside></div>}
      </section></>}
    </div></div>
    {info && <DatasetFieldInfo column={info.column} anchor={info.anchor} close={closeInfo} />}
  </div>;
}
