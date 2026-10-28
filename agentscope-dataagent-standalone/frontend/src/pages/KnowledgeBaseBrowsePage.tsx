import React, { useEffect, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { KnowledgeBreadcrumb } from '../components/KnowledgeWorkspace';
import { knowledgeRequest } from '../api/knowledgeWorkspace';
import type { DatasetColumn } from '../api/datasets';
interface Table { id: string; name: string; description: string | null; rowCount: number | null; columns: DatasetColumn[] }
interface Browse { id: string; name: string; description: string | null; publishedVersion: number; publicQuery: boolean; datasets: Table[]; models: unknown; relationships: unknown; knowledge: { path: string; content: string }[] }
export default function KnowledgeBaseBrowsePage() {
  const { groupId = '' } = useParams(); const navigate = useNavigate();
  const [data, setData] = useState<Browse | null>(null); const [table, setTable] = useState('');
  const [preview, setPreview] = useState<{ columns: DatasetColumn[]; rows: (string | null)[][] } | null>(null);
  const [error, setError] = useState(''); const [loading, setLoading] = useState(false); const [retry, setRetry] = useState(0);
  useEffect(() => { const abort = new AbortController(); setData(null); setError('');
    knowledgeRequest<Browse>(`/api/knowledge-bases/${encodeURIComponent(groupId)}/browse`, { signal: abort.signal })
      .then(value => { if (!abort.signal.aborted) { setData(value); setTable(value.datasets[0]?.id || ''); } })
      .catch(e => { if (!abort.signal.aborted) setError(String(e.message)); }); return () => abort.abort(); }, [groupId, retry]);
  useEffect(() => { setPreview(null); if (!table) return; const abort = new AbortController(); setLoading(true);
    knowledgeRequest<{ columns: DatasetColumn[]; rows: (string | null)[][] }>(`/api/knowledge-bases/${encodeURIComponent(groupId)}/browse/${encodeURIComponent(table)}/preview`, { signal: abort.signal })
      .then(value => { if (!abort.signal.aborted) setPreview(value); }).catch(e => { if (!abort.signal.aborted) setError(String(e.message)); })
      .finally(() => { if (!abort.signal.aborted) setLoading(false); }); return () => abort.abort(); }, [groupId, table, retry]);
  const selected = data?.datasets.find(d => d.id === table);
  return <div className="kw-page"><KnowledgeBreadcrumb items={[{ label: '知识库', to: '/configure/datasets' }, { label: data?.name || '只读详情' }]} />
    <div className="kw-scroll"><div className="kw-content"><div className="kw-pagehead"><div className="kw-heading"><h1>{data?.name || '知识库详情'}</h1><p>{data?.description} · 只读浏览{data?.publicQuery ? ' · 全员可使用' : ''}</p></div>
      <button className="da-btn da-btn-primary" disabled={!data?.publishedVersion} onClick={() => navigate(`/chat?groups=${encodeURIComponent(groupId)}`)}>基于此库提问</button></div>
      {error && <div role="alert" className="kw-alert">{error}<button className="da-btn" onClick={() => setRetry(n => n + 1)}>重试</button></div>}
      {!data && !error && <p role="status">正在加载知识库…</p>}
      {data && <><h2>数据集</h2><select className="kw-select" aria-label="查看数据集" value={table} onChange={e => setTable(e.target.value)}>{data.datasets.map(d => <option key={d.id} value={d.id}>{d.name}</option>)}</select>
        <p>{selected?.description} {selected?.rowCount != null ? `· ${selected.rowCount} 行` : ''}</p>
        <div className="kw-tablewrap" style={{ overflowX: 'auto' }}><table className="kw-table"><thead><tr><th>字段</th><th>类型</th><th>说明</th></tr></thead><tbody>{selected?.columns.map(c => <tr key={c.name}><td>{c.originalName || c.name}</td><td>{c.sqlType}</td><td>{c.description || '—'}</td></tr>)}</tbody></table></div>
        <h2>数据样例</h2><p className="da-small">最多展示20行，仅供了解数据，不代表完整数据分布。</p>{loading ? <p role="status">正在加载样例…</p> : <div className="kw-tablewrap" style={{ overflowX: 'auto' }}><table className="kw-table"><thead><tr>{preview?.columns.map(c => <th key={c.name}>{c.originalName || c.name}</th>)}</tr></thead><tbody>{preview?.rows.map((row, i) => <tr key={i}>{row.map((v, j) => <td key={j}>{v ?? 'NULL'}</td>)}</tr>)}</tbody></table>{preview && !preview.rows.length && <p>暂无数据。</p>}</div>}
        <h2>已发布业务口径</h2>{data.knowledge.map(k => <details key={k.path}><summary>{k.path}</summary><pre style={{ whiteSpace: 'pre-wrap', overflowWrap: 'anywhere' }}>{k.content}</pre></details>)}{!data.knowledge.length && <p>暂无已发布口径文档。</p>}
        <h2>已发布模型与关系</h2><p className="da-small">发布版本 {data.publishedVersion}；未发布草稿不在此展示。</p>{data.publishedVersion ? <details><summary>查看模型定义</summary><pre style={{ whiteSpace: 'pre-wrap', overflowWrap: 'anywhere' }}>{JSON.stringify({ models: data.models, relationships: data.relationships }, null, 2)}</pre></details> : <p>尚未发布模型，暂不能问数。</p>}</>}
    </div></div></div>;
}
