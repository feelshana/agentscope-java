import React, { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { listGroupPage, type GroupPage } from '../api/datasets';
import type { ExternalDataSource } from '../api/datasources';
import DataSourceDialog from './DataSourceDialog';
import AssociateTablesModal from './AssociateTablesModal';
import Icon from './Icon';
import ErrorNotice from './ErrorNotice';
import { toast } from './Toast';

export default function DataSourceAssociateChooser({ source, onClose, onAssociated }: { source: ExternalDataSource; onClose: () => void; onAssociated: () => void }) {
  const navigate = useNavigate(); const [query, setQuery] = useState(''); const [page, setPage] = useState(0);
  const [data, setData] = useState<GroupPage | null>(null); const [selected, setSelected] = useState<string | null>(null);
  const [loading, setLoading] = useState(true); const [error, setError] = useState<string | null>(null); const [revision, setRevision] = useState(0);
  useEffect(() => {
    const abort = new AbortController(); setLoading(true); setError(null);
    const timer = window.setTimeout(() => { void listGroupPage({ page, size: 10, search: query, manageOnly: true, activeOnly: true }, abort.signal)
      .then(v => { if (!abort.signal.aborted) setData(v); }).catch(e => { if (!abort.signal.aborted) setError(e instanceof Error ? e.message : String(e)); })
      .finally(() => { if (!abort.signal.aborted) setLoading(false); }); }, 250);
    return () => { window.clearTimeout(timer); abort.abort(); };
  }, [query, page, revision]);
  if (selected) return <AssociateTablesModal groupId={selected} initialSourceId={source.id} onClose={onClose} onAssociated={() => { toast('库表已关联', 'success'); onAssociated(); navigate(`/configure/datasets/${selected}`); }} />;
  return <DataSourceDialog title="关联到知识库" description={`选择你可管理的知识库，再从「${source.name}」选择库表。`} onClose={onClose}>
    <div className="ds-dialog-body"><label className="kw-search ds-chooser-search"><Icon name="search" size="sm" /><input aria-label="搜索目标知识库" placeholder="搜索知识库" value={query} onChange={e => { setQuery(e.target.value); setPage(0); }} /></label>
      {error && <ErrorNotice error={error}><button className="da-btn" onClick={() => setRevision(v => v + 1)}>重试</button></ErrorNotice>}
      <div className="ds-kb-choices" aria-busy={loading}>{loading ? <p role="status">正在读取知识库…</p> : data?.items.length ? data.items.map(g => <button type="button" key={g.id} onClick={() => setSelected(g.id)}><Icon name="book" /><span><strong>{g.name}</strong><small>{g.description || '暂无业务说明'}</small></span><Icon name="chevron" size="sm" /></button>) : <p>暂无可管理的知识库，可以先创建知识库后再关联。</p>}</div>
    </div><footer className="ds-dialog-foot"><button className="da-btn" disabled={loading || page === 0} onClick={() => setPage(p => p - 1)}>上一页</button><span className="da-small">{page + 1} / {Math.max(1, Math.ceil((data?.total || 0) / 10))}</span><button className="da-btn" disabled={loading || (page + 1) * 10 >= (data?.total || 0)} onClick={() => setPage(p => p + 1)}>下一页</button><button className="da-btn" onClick={onClose}>取消</button></footer>
  </DataSourceDialog>;
}
