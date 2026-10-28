import React, { useEffect, useState } from 'react';
import { KnowledgeBreadcrumb } from '../components/KnowledgeWorkspace';
import { knowledgeRequest } from '../api/knowledgeWorkspace';
interface Item { id: string; name: string; publishedVersion: number; publicQuery: boolean }
interface Page { items: Item[]; total: number; page: number }
export default function PublicKnowledgeBasesPage() {
  const [data, setData] = useState<Page | null>(null); const [search, setSearch] = useState(''); const [page, setPage] = useState(0);
  const [error, setError] = useState(''); const [busy, setBusy] = useState(false); const [revision, setRevision] = useState(0);
  useEffect(() => { const abort = new AbortController(); setData(null); const timer = setTimeout(() => {
    void knowledgeRequest<Page>(`/api/admin/public-knowledge-bases?${new URLSearchParams({ search, page: String(page) })}`, { signal: abort.signal })
      .then(value => { if (!abort.signal.aborted) setData(value); }).catch(e => { if (!abort.signal.aborted) setError(e.message); }); }, 200);
    return () => { clearTimeout(timer); abort.abort(); }; }, [search, page, revision]);
  async function change(item: Item) {
    if (!window.confirm(`${item.publicQuery ? '取消' : '启用'}“${item.name}”的全员使用？取消后仍保留单独授予的成员权限。`)) return;
    setBusy(true); setError(''); try { await knowledgeRequest<void>(`/api/admin/public-knowledge-bases/${encodeURIComponent(item.id)}`, { method: 'PUT', body: JSON.stringify({ publicQuery: !item.publicQuery }) }); setRevision(n => n + 1); }
    catch (e) { setError(e instanceof Error ? e.message : String(e)); } finally { setBusy(false); }
  }
  return <div className="kw-page"><KnowledgeBreadcrumb items={[{ label: '系统管理', to: '/more' }, { label: '公共知识库' }]} /><div className="kw-scroll"><div className="kw-content">
    <div className="kw-pagehead"><div className="kw-heading"><h1>公共知识库</h1><p>全局管理员配置 · 全体已登录用户可使用，包括未来新增用户。</p></div></div>
    <p>仅开放使用与只读浏览，不开放管理。请使用脱敏或合成样例；私人会话与分析文件仍归本人。</p>
    <input className="da-input" style={{ marginBottom: 16 }} aria-label="搜索知识库" placeholder="搜索知识库" value={search} onChange={e => { setSearch(e.target.value); setPage(0); setError(''); }} />
    {error && <div className="kw-alert" role="alert">{error}<button onClick={() => setRevision(n => n + 1)}>重试</button></div>}
    <div className="kw-tablewrap" style={{ overflowX: 'auto' }}><table className="kw-table"><thead><tr><th>知识库</th><th>已发布版本</th><th>开放范围</th><th>操作</th></tr></thead><tbody>{data?.items.map(item => <tr key={item.id}><td>{item.name}</td><td>{item.publishedVersion || '未发布'}</td><td>{item.publicQuery ? '全员可使用' : '按成员授权'}</td><td><button className="da-btn" disabled={busy || (!item.publicQuery && !item.publishedVersion)} onClick={() => void change(item)}>{item.publicQuery ? '取消全员使用' : '设为全员使用'}</button></td></tr>)}</tbody></table>{!data && !error && <p role="status">加载中…</p>}{data && !data.items.length && <p>没有匹配的知识库。</p>}</div>
    <div className="kw-pagination"><span>共 {data?.total ?? 0} 个知识库</span><button className="da-btn da-btn-sm" disabled={busy || page === 0} onClick={() => setPage(p => p - 1)}>上一页</button><span>{page + 1}</span><button className="da-btn da-btn-sm" disabled={busy || !data || (page + 1) * 20 >= data.total} onClick={() => setPage(p => p + 1)}>下一页</button></div>
  </div></div></div>;
}
