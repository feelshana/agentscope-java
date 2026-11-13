import { useCallback, useEffect, useId, useMemo, useRef, useState } from 'react';
import { getGroupDetail, getPreview, type Dataset, type DatasetColumn, type GroupDetail, type Preview } from '../api/datasets';
import { DatasetFieldsTable, DatasetPreviewTable } from './DatasetReadOnlyTables';
import DatasetFieldInfo from '../pages/configure/DatasetFieldInfo';
import Icon from './Icon';
import '../detail-surfaces.css';
import '../pages/configure/KnowledgeDetails.css';
import './KnowledgeDataPanel.css';

type Tab = 'preview' | 'fields';
const matches = (term: string, ...values: (string | null | undefined)[]) => values.some(v => v?.toLowerCase().includes(term.trim().toLowerCase()));

function DatasetContent({ dataset, onBack }: { dataset: Dataset; onBack: () => void }) {
  const [tab, setTab] = useState<Tab>('preview');
  const [descriptionOpen, setDescriptionOpen] = useState(false);
  const [search, setSearch] = useState('');
  const [preview, setPreview] = useState<Preview | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [retry, setRetry] = useState(0);
  const [info, setInfo] = useState<{ column: DatasetColumn; anchor: HTMLButtonElement } | null>(null);
  const closeInfo = useCallback(() => setInfo(null), []);
  const tabsId = useId();
  // Fetch only when the preview tab is opened; fields already arrived with the group detail.
  useEffect(() => {
    if (tab !== 'preview' || preview) return;
    const controller = new AbortController();
    const timeout = window.setTimeout(() => {
      controller.abort(); setLoading(false); setError('样例读取时间较长，请稍后重试；你仍可查看字段信息。');
    }, 20000);
    setLoading(true); setError('');
    getPreview(dataset.id, 20, controller.signal).then(value => { if (!controller.signal.aborted) setPreview(value); })
      .catch(e => { if (!controller.signal.aborted) setError((e as Error).message); })
      .finally(() => { window.clearTimeout(timeout); if (!controller.signal.aborted) setLoading(false); });
    return () => { window.clearTimeout(timeout); controller.abort(); };
  }, [dataset.id, tab, preview, retry]);
  const rows = useMemo(() => preview?.rows.map((values, index) => ({ values, index })).filter(row =>
    row.values.some(value => matches(search, String(value ?? 'NULL')))) ?? [], [preview, search]);
  const fields = useMemo(() => dataset.columns.filter(c => matches(search, c.name, c.originalName, c.displayName, c.sqlType, c.description)), [dataset.columns, search]);
  const columns = preview?.columns.map(c => dataset.columns.find(d => d.name === c.name) ?? c) ?? [];
  return <div className="kdp-dataset-view">
    <div className="kdp-dataset-heading"><button type="button" className="kdp-icon-button kdp-return" aria-label="返回数据集列表" title="返回数据集列表" onClick={onBack}><Icon name="back" size="sm" /></button><div><h3 title={dataset.name}>{dataset.name}</h3><p>{dataset.origin === 'datasource' ? '数据源关联' : dataset.origin === 'derived' ? '派生数据' : '文件上传'} · {dataset.columns.length} 个字段</p></div></div>
    {dataset.description && <div className={`kdp-summary${descriptionOpen ? ' is-expanded' : ''}`}><p id={`${tabsId}-description`}>{dataset.description}</p><button type="button" aria-expanded={descriptionOpen} aria-controls={`${tabsId}-description`} onClick={() => setDescriptionOpen(open => !open)}>{descriptionOpen ? '收起' : '展开'}<Icon name="chevron" size="sm" /></button></div>}
    <div className="kdp-toolbar">
    <div className="kdp-tabs" role="tablist" aria-label="数据集浏览">{(['preview', 'fields'] as const).map(t => <button type="button" key={t} role="tab" id={`${tabsId}-${t}`} aria-controls={`${tabsId}-content`} aria-selected={tab === t} onClick={() => { setTab(t); setSearch(''); setInfo(null); }}>{t === 'preview' ? '示例数据' : '字段信息'}{t === 'fields' && <small>{dataset.columns.length}</small>}</button>)}</div>
    <label className="kdp-search"><Icon name="search" size="sm" /><input type="search" aria-label={tab === 'preview' ? '在示例数据中查找' : '搜索字段信息'} placeholder={tab === 'preview' ? '在当前样例中查找' : '搜索字段名、原字段名或说明'} value={search} onChange={e => setSearch(e.target.value)} /></label>
    </div>
    <section className="kdp-data-area" role="tabpanel" id={`${tabsId}-content`} aria-labelledby={`${tabsId}-${tab}`}>
      {tab === 'preview' ? loading && !preview ? <div className="kdp-state" role="status"><span className="da-spinner da-spinner-sm" />加载示例数据…</div> : error ? <div className="kdp-state" role="alert"><p>{error}</p><button className="da-btn" onClick={() => setRetry(n => n + 1)}>重新加载样例</button></div> :
        <><div className="kdp-table-scroll" tabIndex={0} role="region" aria-label="示例数据表格，可横向滚动"><DatasetPreviewTable columns={columns} rows={rows} activeFieldName={info?.column.name} onFieldInfo={(column, anchor) => setInfo({ column, anchor })} />{!rows.length && <div className="kdp-state">{search ? '当前样例没有匹配的数据' : '暂无示例数据'}</div>}</div><p className="kdp-caption">当前显示 {rows.length} 行样例，用于了解数据，不代表完整数据范围。</p></>
        : <div className="kdp-table-scroll" tabIndex={0} role="region" aria-label="数据集字段表格，可横向滚动"><DatasetFieldsTable columns={fields} compact />{!fields.length && <div className="kdp-state">没有匹配的字段</div>}</div>}
    </section>
    {info && <DatasetFieldInfo column={info.column} anchor={info.anchor} close={closeInfo} />}
  </div>;
}

/** Ephemeral read-only browser: no mutation callbacks, persisted cache or background prefetch. */
export default function KnowledgeDataPanel({ groupId, onClose }: { groupId: string; onClose: () => void }) {
  const [detail, setDetail] = useState<GroupDetail | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [retry, setRetry] = useState(0);
  const [search, setSearch] = useState('');
  const [datasetId, setDatasetId] = useState<string | null>(null);
  const closeRef = useRef<HTMLButtonElement>(null);
  const scrollRef = useRef<HTMLDivElement>(null);
  const listScroll = useRef(0);
  const headingId = useId();
  useEffect(() => { closeRef.current?.focus({ preventScroll: true }); }, []);
  useEffect(() => {
    const controller = new AbortController();
    const timeout = window.setTimeout(() => {
      controller.abort(); setLoading(false); setError('知识库资料加载超时，请稍后重试。');
    }, 15000);
    setLoading(true); setError(''); setDetail(null);
    getGroupDetail(groupId, controller.signal).then(value => { if (!controller.signal.aborted) setDetail(value); })
      .catch(e => { if (!controller.signal.aborted) setError((e as Error).message); })
      .finally(() => { window.clearTimeout(timeout); if (!controller.signal.aborted) setLoading(false); });
    return () => { window.clearTimeout(timeout); controller.abort(); };
  }, [groupId, retry]);
  const dataset = detail?.datasets.find(d => d.id === datasetId);
  const visible = detail?.datasets.filter(d => matches(search, d.name, d.description, d.sourceFileName)) ?? [];
  function back() { setDatasetId(null); requestAnimationFrame(() => { if (scrollRef.current) scrollRef.current.scrollTop = listScroll.current; }); }
  return <aside className="kb-data-panel" id="chat-knowledge-data" aria-labelledby={headingId} onKeyDown={e => {
    if (document.getElementById('kd-field-help')) return;
    if (e.key === 'Escape' && !e.defaultPrevented && !e.nativeEvent.isComposing) { e.preventDefault(); e.stopPropagation(); onClose(); }
  }}>
    <header className="kdp-header"><div className="kdp-library"><Icon name="book" size="sm" /><h2 id={headingId} title={detail?.group.name}>{detail?.group.name || '查看知识库数据'}</h2><span className="kdp-readonly">只读</span></div><button ref={closeRef} type="button" className="kdp-icon-button" aria-label="关闭知识库数据" title="关闭（Esc）" onClick={onClose}><Icon name="close" size="sm" /></button></header>
    <div className={`kdp-body${dataset ? ' has-dataset' : ''}`} ref={scrollRef}>
      {loading ? <div className="kdp-state" role="status"><span className="da-spinner da-spinner-sm" />加载知识库资料…</div> : error ? <div className="kdp-state" role="alert"><Icon name="info" /><h3>暂时无法查看知识库</h3><p>{error}</p><button className="da-btn" onClick={() => setRetry(n => n + 1)}>重新加载资料</button></div> : detail && (dataset ?
        <DatasetContent key={dataset.id} dataset={dataset} onBack={back} />
        : <><p className="kdp-description">{detail.group.description || '这个知识库还没有业务说明，可从下方数据集了解包含的数据。'}</p>
          <div className="kdp-section-heading"><h3>数据集 <small>{detail.datasets.length}</small></h3><span>查看样例与字段</span></div>
          {detail.datasets.length > 0 && <label className="kdp-search"><Icon name="search" size="sm" /><input type="search" aria-label="搜索知识库数据集" placeholder="搜索数据集名称或说明" value={search} onChange={e => setSearch(e.target.value)} /></label>}
          <div className="kdp-datasets">{visible.map(d => <button type="button" className="kdp-dataset" key={d.id} onClick={() => { listScroll.current = scrollRef.current?.scrollTop ?? 0; setDatasetId(d.id); if (scrollRef.current) scrollRef.current.scrollTop = 0; }}><span className="kdp-tile"><Icon name="table" size="sm" /></span><span className="kdp-dataset-text"><strong>{d.name}</strong><span>{d.description || d.sourceFileName || '查看数据集内容'}</span><small>{d.columns.length} 个字段 · {d.rowCount == null ? '总行数未统计' : `${d.rowCount.toLocaleString()} 行`}</small></span><Icon name="chevron" size="sm" /></button>)}</div>
          {!visible.length && <div className="kdp-state"><Icon name={search ? 'search' : 'table'} /><h3>{search ? '没有匹配的数据集' : '这个知识库还没有数据集'}</h3><p>{search ? '试试其他名称或关键词。' : '添加数据后，可以在这里查看样例和字段。'}</p>{search && <button className="kdp-back" onClick={() => setSearch('')}>清除搜索</button>}</div>}</>)}
    </div>
    <footer className="kdp-footer"><Icon name="info" size="sm" /><span>当前数据集资料仅供浏览，问数使用已发布的语义模型。</span></footer>
  </aside>;
}
