import { useEffect, useMemo, useRef, useState, useLayoutEffect, useCallback } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import Icon from '../../components/Icon';
import { datasetColumnLabel, DatasetFieldsTable, DatasetPreviewTable } from '../../components/DatasetReadOnlyTables';
import { type DatasetColumn, type GroupDetail, type Preview, deleteDataset, getPreview, updateColumns, updateDatasetDescription, refreshDatasetSample } from '../../api/datasets';
import { datasetSource } from './KnowledgeWorkspaceOverview';
import { knowledgePath, modelingPath, type KnowledgeModelingHost } from './knowledgeWorkspaceRoutes';
import '../../detail-surfaces.css';
import './KnowledgeDetails.css';

const tabs = [{ key: 'preview', title: '数据预览' }, { key: 'fields', title: '字段信息' }, { key: 'technical', title: '技术信息' }] as const;
type Tab = typeof tabs[number]['key'];
const messageOf = (error: unknown) => error instanceof Error ? error.message : String(error);

export default function DatasetDetailPage({ detail, datasetId, leave, onChanged }: {
  detail: GroupDetail;
  datasetId: string;
  leave: KnowledgeModelingHost['leave'];
  onChanged: () => Promise<void>;
}) {
  const { group } = detail;
  const dataset = detail.datasets.find(item => item.id === datasetId);
  const canManage = group.canManage && !group.deletionPending;
  const navigate = useNavigate();
  const [params, setParams] = useSearchParams();
  const tab = tabs.find(item => item.key === params.get('tab'))?.key ?? 'preview';
  const [preview, setPreview] = useState<Preview | null>(null);
  const [previewLoading, setPreviewLoading] = useState(true);
  const [previewError, setPreviewError] = useState('');
  const [error, setError] = useState('');
  const [message, setMessage] = useState('');
  const [busy, setBusy] = useState(false);
  const [search, setSearch] = useState('');
  const [selectedName, setSelectedName] = useState<string>();
  const [fieldDraft, setFieldDraft] = useState<string | null>(null);
  const [descriptionDraft, setDescriptionDraft] = useState<string | null>(null);
  const [showDescriptions, setShowDescriptions] = useState(false);
  const [viewportCounts, setViewportCounts] = useState({ rows: 0, columnsRight: 0 });
  const previewScroll = useRef<HTMLDivElement>(null);
  const selected = dataset?.columns.find(column => column.name === selectedName) ?? dataset?.columns[0];
  const dirty = (fieldDraft !== null && fieldDraft !== (selected?.description ?? ''))
    || (descriptionDraft !== null && descriptionDraft !== (dataset?.description ?? ''));
  const abandon = useCallback(() => !dirty || window.confirm('有尚未保存的修改，放弃这些修改吗？'), [dirty]);
  const loadedId = dataset?.id;
  const root = knowledgePath(group.id);

  useEffect(() => {
    if (!loadedId || tab !== 'preview') return;
    const controller = new AbortController();
    setPreviewLoading(true); setPreviewError('');
    void getPreview(loadedId, 20, controller.signal).then(value => {
      if (!controller.signal.aborted) setPreview(value);
    }).catch(e => {
      if (!controller.signal.aborted) setPreviewError(messageOf(e));
    }).finally(() => { if (!controller.signal.aborted) setPreviewLoading(false); });
    return () => controller.abort();
  }, [loadedId, tab]);

  const updatePreviewOverflow = useCallback(() => {
    const el = previewScroll.current;
    if (!el) return;
    const bounds = el.getBoundingClientRect();
    const top = Math.max(bounds.top, el.querySelector('thead th')?.getBoundingClientRect().bottom ?? bounds.top);
    const bottom = bounds.top + el.clientHeight;
    const right = bounds.left + el.clientWidth;
    const rows = Array.from(el.querySelectorAll('tbody tr')).filter(row => {
      const rect = row.getBoundingClientRect();
      return rect.top >= top - 1 && rect.bottom <= bottom + 1;
    }).length;
    const columnsRight = Array.from(el.querySelectorAll('thead th:not(.rownum)'))
      .filter(column => column.getBoundingClientRect().right > right + 1).length;
    setViewportCounts(current => current.rows === rows && current.columnsRight === columnsRight ? current : { rows, columnsRight });
  }, []);

  useLayoutEffect(() => {
    const scroll = previewScroll.current;
    if (!scroll) return;
    const observer = new ResizeObserver(updatePreviewOverflow);
    observer.observe(scroll);
    if (scroll.firstElementChild) observer.observe(scroll.firstElementChild);
    updatePreviewOverflow();
    return () => observer.disconnect();
  }, [tab, preview, previewLoading, previewError, search, showDescriptions, updatePreviewOverflow]);

  useLayoutEffect(() => {
    const guard = () => !busy && abandon();
    leave.current = guard;
    return () => { if (leave.current === guard) leave.current = null; };
  }, [leave, busy, abandon]);
  useEffect(() => {
    if (!dirty) return;
    const warn = (e: BeforeUnloadEvent) => { e.preventDefault(); e.returnValue = ''; };
    window.addEventListener('beforeunload', warn);
    return () => window.removeEventListener('beforeunload', warn);
  }, [dirty]);

  function switchTab(next: Tab) {
    if (next === tab || busy || !abandon()) return;
    setFieldDraft(null); setDescriptionDraft(null); setSearch(''); setError('');
    setParams(next === 'preview' ? {} : { tab: next });
  }
  function selectField(column: DatasetColumn) {
    if (busy || column.name === selected?.name || !abandon()) return;
    setSelectedName(column.name); setFieldDraft(null); setError('');
  }
  function openModel() {
    if (busy || !abandon()) return;
    navigate(modelingPath(group.id) + '?asset=semantic&view=graph&mode=board');
  }
  async function saveDescription() {
    if (!canManage || !dataset || busy || descriptionDraft === null) return;
    setBusy(true); setError('');
    try {
      await updateDatasetDescription(dataset.id, descriptionDraft);
      setDescriptionDraft(null); setMessage('数据集说明已保存。');
      await onChanged();
    } catch (e) { setError(messageOf(e)); }
    finally { setBusy(false); }
  }
  async function saveField() {
    if (!canManage || !dataset || !selected || busy || fieldDraft === null) return;
    setBusy(true); setError('');
    try {
      await updateColumns(dataset.id, [{ name: selected.name, description: fieldDraft }]);
      setFieldDraft(null); setMessage('字段说明已保存，可在建模中核对业务口径。');
      await onChanged();
    } catch (e) { setError(messageOf(e)); }
    finally { setBusy(false); }
  }
  async function refreshSample() {
    if (!canManage || !dataset || busy || !abandon()) return;
    setBusy(true); setError(''); setFieldDraft(null); setDescriptionDraft(null);
    try {
      await refreshDatasetSample(dataset.id);
      await onChanged();
      if (tab === 'preview') setPreview(await getPreview(dataset.id, 20));
      setMessage('样本已刷新。');
    } catch (e) { setError(messageOf(e)); }
    finally { setBusy(false); }
  }
  async function remove() {
    if (!canManage || !dataset || busy || !abandon()) return;
    const external = dataset.origin === 'datasource';
    if (!window.confirm(external ? '移除关联？数据库中的原始表和数据会保留。' : '删除数据集及其上传的物理数据？此操作无法撤销。')) return;
    setBusy(true); setError('');
    try { await deleteDataset(dataset.id); await onChanged(); navigate(root + '?section=datasets'); }
    catch (e) { setError(messageOf(e)); }
    finally { setBusy(false); }
  }
  const filter = search.trim().toLowerCase();
  const shownColumnIndices = useMemo(() => preview?.columns.flatMap((column, index) =>
    column.name.toLowerCase() === 'id' && /^(?:tinyint|smallint|int|bigint|mediumint)\b/i.test(column.sqlType) ? [] : [index]) ?? [], [preview]);
  const rows = useMemo(() => preview?.rows.map((values, index) => ({
    values: shownColumnIndices.map(position => values[position] ?? null), index,
  })).filter(row => !filter || row.values.some(value => String(value ?? '').toLowerCase().includes(filter))) ?? [], [preview, filter, shownColumnIndices]);
  const fields = useMemo(() => dataset?.columns.filter(c =>
    [c.name, c.originalName, c.displayName, c.sqlType, c.description].join(' ').toLowerCase().includes(filter)) ?? [], [dataset, filter]);
  if (!dataset) return <div className="kbw-page">
    <div className="kw-alert" role="alert">数据集不存在或已被删除。</div>
    <button className="da-btn" onClick={() => navigate(root + '?section=datasets')}>返回数据集</button>
  </div>;

  const external = dataset.origin === 'datasource';
  const scale = dataset.rowCount == null ? '总行数未统计' : dataset.rowCount.toLocaleString() + ' 行';
  const sampling = dataset.sampling;
  const sampleDate = sampling?.sampledAt ? new Date(sampling.sampledAt).toLocaleString('zh-CN') : '';
  const columns = preview ? shownColumnIndices.map(index => {
    const column = preview.columns[index];
    return dataset.columns.find(item => item.name === column.name) ?? column;
  }) : [];
  const sampledRows = sampling?.status === 'SUCCESS' ? sampling.sampledRows : preview?.rows.length;
  const metadata = [
    ['所属知识库', group.name], ['来源类型', datasetSource(dataset)], ['来源文件 / 表', dataset.sourceFileName || '—'],
    ['物理库表', [dataset.schemaName, dataset.tableName].filter(Boolean).join('.') || '—'],
    ['规模', scale + ' · ' + dataset.columns.length + ' 字段'], ['版本', 'v' + (dataset.currentVersion ?? 1)],
  ];
  const sampleAction = canManage && external && <button type="button" className="da-btn" disabled={busy} onClick={() => void refreshSample()}><Icon name="refresh" size="sm" />刷新样本</button>;
  return <div className="kbw-page kbw-table-detail kd-page" data-tab={tab}>
    <header className="kd-head">
      <div className="kd-heading">
        <div className="kd-title"><span className="kd-tile"><Icon name="table" /></span><div>
          <h1>{dataset.name}</h1><p className="kd-meta">{datasetSource(dataset)} · {dataset.columns.length} 个字段 · v{dataset.currentVersion ?? 1}</p>
        </div></div>
        {descriptionDraft === null ? <div className="kd-description"><p>{dataset.description || '暂无说明'}</p>
          {canManage && <button type="button" className="dw-text" disabled={busy} onClick={() => {
            if (abandon()) { setFieldDraft(null); setDescriptionDraft(dataset.description ?? ''); setError(''); }
          }}>{dataset.description ? '编辑说明' : '添加说明'}</button>}
        </div> : <form className="kd-description-form" onSubmit={event => { event.preventDefault(); void saveDescription(); }}>
          <label>业务说明<textarea className="da-input" autoFocus rows={3} maxLength={10000} disabled={busy} value={descriptionDraft} onChange={event => setDescriptionDraft(event.target.value)} /></label>
          <div className="kw-actions"><button type="button" className="da-btn" disabled={busy} onClick={() => { if (abandon()) setDescriptionDraft(null); }}>取消</button>
            <button className="da-btn da-btn-primary" disabled={busy}>保存修改</button></div>
        </form>}
      </div>
      {sampleAction}
    </header>
    {error && <div className="kw-alert" role="alert">{error}</div>}
    {message && <div className="kd-notice success kd-save-notice" role="status">{message}</div>}
    <div className="kd-tabline">
      <div className="kd-tabs" role="tablist" aria-label="数据集信息">{tabs.map(item =>
        <button key={item.key} type="button" id={'kd-tab-' + item.key} role="tab" aria-selected={tab === item.key} aria-controls="kd-panel" onClick={() => switchTab(item.key)}>
          {item.title}{item.key === 'fields' && <small>{dataset.columns.length}</small>}
        </button>)}</div>
      {tab === 'preview' && <div className="kd-preview-options">
        {external && <div className="kd-sample-summary" role="status"><Icon name="info" size="sm" /><span>
          {sampling?.status === 'SUCCESS' ? '采样 ' + sampling.sampledRows + ' 行 · 仅供观察' + (sampleDate ? ' · ' + sampleDate : '')
            : sampling?.status === 'FAILED' ? '采样失败，已保留表结构。' : sampling?.status === 'DISABLED' ? '采样已关闭。' : '尚无有效采样摘要。'}
        </span></div>}
        <label className="kd-field-toggle"><input type="checkbox" checked={showDescriptions} onChange={event => setShowDescriptions(event.target.checked)} />显示字段说明</label>
      </div>}
      {tab !== 'technical' && <label className="kw-search"><Icon name="search" size="sm" /><input
        aria-label={tab === 'preview' ? '在当前样本中查找' : '搜索字段'} placeholder={tab === 'preview' ? '在当前样本中查找' : '搜索字段名称、原字段名或说明'}
        value={search} onChange={event => setSearch(event.target.value)} /></label>}
    </div>
    <section id="kd-panel" role="tabpanel" aria-labelledby={'kd-tab-' + tab}>
      {tab === 'preview' && <div className="kd-table-card kbw-preview">
        {previewLoading ? <div className="kw-empty" role="status">加载数据预览…</div>
          : previewError ? <div className="kw-empty" role="alert">{previewError}</div>
            : <div className="kd-preview-frame" data-more-columns={viewportCounts.columnsRight > 0 || undefined}><div className="kw-table-scroll kd-preview-scroll" ref={previewScroll} onScroll={updatePreviewOverflow} tabIndex={0} role="region" aria-label="数据预览表">
              <DatasetPreviewTable columns={columns} rows={rows} showDescriptions={showDescriptions} />
              {!rows.length && <div className="kw-empty">{filter ? '当前样本没有匹配的数据' : '暂无数据行'}</div>}
            </div></div>}
        <footer className="kw-tablebottom">
          <span title={`当前预览共 ${rows.length} 行`}>显示 {previewLoading || previewError ? 0 : viewportCounts.rows} 行{sampledRows !== undefined && ` · 采样自 ${sampledRows} 行`} · {dataset.rowCount == null ? scale : `总行数 ${scale}`}</span>
          {viewportCounts.columnsRight > 0 && !previewLoading && !previewError && <span>向右滚动查看其余 {viewportCounts.columnsRight} 列</span>}
        </footer>
      </div>}
      {tab === 'fields' && <div className="kbw-field-layout">
        <div className="kd-table-card">
          <div className="kw-table-scroll" tabIndex={0} role="region" aria-label="数据集字段清单">
            <DatasetFieldsTable columns={fields} onField={selectField} selected={selected?.name}
              onEditSelected={canManage && !busy ? () => document.getElementById('kd-field-description')?.focus() : undefined} />
            {!fields.length && <div className="kw-empty">没有匹配的字段</div>}
          </div>
          <footer className="kw-tablebottom">共 {fields.length} 个字段</footer>
        </div>
        <aside className="kbw-card kbw-card-pad kbw-field-inspector" aria-label="字段详情">
          <h2><Icon name="terms" size="sm" />{selected ? datasetColumnLabel(selected) : '字段说明'}</h2>
          {selected ? <dl className="kbw-meta-list"><div><dt>字段名称</dt><dd><code>{selected.name}</code></dd></div>
            <div><dt>原字段名称</dt><dd>{selected.originalName || '—'}</dd></div><div><dt>数据类型</dt><dd><code>{selected.sqlType}</code></dd></div></dl>
            : <p>选择字段，查看类型和业务说明。</p>}
          {canManage ? <form onSubmit={event => { event.preventDefault(); void saveField(); }}
                onKeyDown={event => {
                  if (event.nativeEvent.isComposing || busy) return;
                  if (event.key === 'Escape' && abandon()) setFieldDraft(null);
                  if (event.key === 'Enter' && (event.ctrlKey || event.metaKey)) { event.preventDefault(); void saveField(); }
                }}>
              {selected && <label htmlFor="kd-field-description">业务说明<textarea id="kd-field-description" className="da-input" rows={5}
                maxLength={10000} value={fieldDraft ?? selected.description ?? ''} disabled={busy || descriptionDraft !== null}
                onChange={event => setFieldDraft(event.target.value)} /></label>}
              <div className="kw-actions"><button className="da-btn" type="button" disabled={busy || fieldDraft === null}
                onClick={() => { if (abandon()) setFieldDraft(null); }}>取消</button>
                <button className="da-btn da-btn-primary" title={!selected ? '请先选择一个字段' : undefined}
                  disabled={!selected || busy || (fieldDraft ?? '') === (selected.description ?? '') || fieldDraft === null}>{busy ? '保存中…' : '保存修改'}</button></div>
            </form> : selected && <><h3>业务说明</h3><p>{selected.description || '待补充说明'}</p></>}
        </aside>
      </div>}
      {tab === 'technical' && <div className="kd-technical">
        <div className="kd-technical-main">
          <section className="kd-table-card kd-technical-metadata"><div className="kbw-card-head"><h2>数据集信息</h2></div>
            <dl>{metadata.map(([key, value]) => <div key={key}><dt>{key}</dt><dd title={value}>{key === '物理库表' ? <code>{value}</code> : value}</dd></div>)}</dl></section>
          <section className="kd-table-card kd-physical-structure">
            <div className="kbw-card-head"><h2>物理表结构</h2><small>{dataset.columns.length} 个字段</small></div>
            <div className="kw-table-scroll" data-limit={dataset.columns.length > 24 || undefined} tabIndex={0} role="region" aria-label="物理表结构">
              <table className="kw-table"><colgroup><col /><col className="kd-physical-type" /></colgroup><thead><tr><th>字段</th><th>类型</th></tr></thead><tbody>{dataset.columns.map(column =>
                <tr key={column.name}><td title={column.name}><code>{column.name}</code></td><td title={column.sqlType}>{column.sqlType}</td></tr>)}</tbody></table>
            </div>
            <footer className="kw-tablebottom">共 {dataset.columns.length} 个字段</footer>
          </section>
        </div>
        <aside><section className="kd-table-card"><h2><Icon name="info" size="sm" />样本与数据</h2>
          <p>{external ? (sampling?.status === 'SUCCESS' ? '已采样 ' + sampling.sampledRows + ' 行' + (sampleDate ? ' · ' + sampleDate : '') + '。' : '当前仅展示采样记录。') + '刷新样本不会修改外部数据库的原表。' : '文件导入后保留原始字段和数据值。更新数据请重新上传文件。'}</p>
          {sampleAction}{canManage && !external && <button type="button" className="da-btn" onClick={() => navigate(root + '?section=datasets&upload=1')}><Icon name="upload" size="sm" />上传更新文件</button>}
        </section>
        <section className="kd-table-card"><h2><Icon name="graph" size="sm" />语义模型</h2><p>查看当前知识库的模型与关系。</p>
          <button type="button" className="da-btn" onClick={openModel}>查看关系图<Icon name="chevron" size="sm" /></button></section>
        {canManage && <section className="kd-table-card kd-danger"><h2><Icon name="info" size="sm" />{external ? '移除关联' : '删除数据集'}</h2>
          <p>{external ? '从知识库移除关联，外部数据库中的原始表保留。' : '删除后将失去该数据集及其字段说明，请先确认相关模型和问答的依赖。'}</p>
          <button type="button" className="da-btn" disabled={busy} onClick={() => void remove()}>{external ? '移除关联' : '删除数据集'}</button></section>}</aside>
      </div>}
    </section>
  </div>;
}
