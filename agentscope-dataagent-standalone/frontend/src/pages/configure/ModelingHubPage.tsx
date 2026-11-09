import { useEffect, useState } from 'react';
import { DataSourceChoices } from '../../components/Onboarding';
import { useNavigate } from 'react-router-dom';
import { KnowledgeBreadcrumb } from '../../components/KnowledgeWorkspace';
import Icon from '../../components/Icon';
import { listGroupPage } from '../../api/datasets';
import type { DatasetGroup, GroupPage } from '../../api/datasets';
import { listModelingHubSummaries } from '../../api/semanticModeling';
import type { ModelingHubSummary } from '../../api/semanticModeling';
import './ModelingHubPage.css';

const PAGE_SIZE = 10;
const HEADERS = ['知识库', '当前状态', '模型', '关系', '需要关注', '创建时间', '操作'];
const SKELETON_WIDTHS = ['70%', '68%', '48%', '48%', '65%', '72%', '75%'];

/** The one thing each knowledge base needs next, in the modeler's words. */
function status(group: DatasetGroup, summary: ModelingHubSummary | undefined, loading: boolean): {
  label: string; tone: 'warn' | 'running' | 'published' | 'neutral'
} {
  if (group.canManage && loading) return { label: '正在读取状态', tone: 'neutral' };
  if (group.canManage && (!summary?.draft || summary.confirmations === null)) return { label: '状态读取失败', tone: 'neutral' };
  if (summary?.draft?.assistantRunning) return { label: '助手运行中', tone: 'running' };
  if (summary?.confirmations) return { label: `待你确定 ${summary.confirmations} 项`, tone: 'warn' };
  if (summary?.draft?.changes) return { label: group.publishedVersion ? `有 ${summary.draft.changes} 处修改待发布` : '待首次发布', tone: 'warn' };
  if (group.publishedVersion) return { label: `已发布 v${group.publishedVersion}`, tone: 'published' };
  return { label: '尚未建模', tone: 'neutral' };
}

/**
 * spec092: standalone entry to semantic modeling, newest first, each with the next thing it needs.
 * spec106: QUERY users come here too, for the published models; the counts are the published ones.
 */
export default function ModelingHubPage() {
  const navigate = useNavigate();
  const [query, setQuery] = useState('');
  const [request, setRequest] = useState({ page: 0, search: '' });
  const [retry, setRetry] = useState(0);
  const [result, setResult] = useState<{ request: typeof request; retry: number; data: GroupPage } | null>(null);
  const [error, setError] = useState<{ request: typeof request; retry: number; text: string } | null>(null);
  const [summaries, setSummaries] = useState<{ data: GroupPage; items: Map<string, ModelingHubSummary>; failed?: boolean } | null>(null);

  useEffect(() => {
    const keyword = query.trim();
    const timer = window.setTimeout(() => {
      setRequest(current => current.search === keyword ? current : { page: 0, search: keyword });
    }, 240);
    return () => window.clearTimeout(timer);
  }, [query]);

  useEffect(() => {
    const controller = new AbortController();
    void listGroupPage({ ...request, size: PAGE_SIZE, activeOnly: true }, controller.signal).then(data => {
      if (controller.signal.aborted) return;
      const lastPage = Math.max(0, Math.ceil(data.total / PAGE_SIZE) - 1);
      if (request.page > lastPage) {
        setRequest(current => current === request ? { ...current, page: lastPage } : current);
        return;
      }
      setResult({ request, retry, data });
      const ids = data.items.filter(group => !group.deletionPending).map(group => group.id);
      if (!ids.length) return;
      void listModelingHubSummaries(ids, controller.signal).then(items => {
        if (!controller.signal.aborted) setSummaries({ data, items: new Map(items.map(item => [item.groupId, item])) });
      }).catch(() => {
        if (!controller.signal.aborted) setSummaries({ data, items: new Map(), failed: true });
      });
    }).catch(e => {
      if (!controller.signal.aborted) setError({ request, retry, text: e instanceof Error ? e.message : '加载失败' });
    });
    return () => controller.abort();
  }, [request, retry]);

  const data = result?.request === request && result.retry === retry ? result.data : null;
  const failure = error?.request === request && error.retry === retry ? error.text : null;
  const rows = data?.items.filter(group => !group.deletionPending);
  const currentSummaries = summaries?.data === data ? summaries : null;
  const pages = Math.ceil((data?.total ?? 0) / PAGE_SIZE);
  const open = (group: DatasetGroup) => navigate(`/configure/modeling/${group.id}`);

  return <div className="kw-page workspace-overview modeling-hub">
    <KnowledgeBreadcrumb items={[{ label: '返回对话', to: '/chat' }, { label: '语义建模' }]} />
    <div className="kw-scroll"><div className="kw-content">
      <div className="kw-pagehead">
        <div className="kw-heading"><h1>语义建模</h1><p>梳理字段含义、模型关系与业务口径。问数使用已发布的版本。</p></div>
      </div>
      {(failure || currentSummaries?.failed) && <div className="kw-alert" role="alert">
        {failure ?? '建模摘要暂时无法读取。'} <button type="button" className="da-btn da-btn-sm" onClick={() => setRetry(n => n + 1)}>重试</button>
      </div>}
      <div className="kw-toolbar mh-toolbar">
        <label className="kw-search">
          <Icon name="search" size="sm" />
          <input type="search" value={query} onChange={e => setQuery(e.target.value)}
            maxLength={100} placeholder="搜索知识库名称或说明…" aria-label="搜索知识库" />
        </label>
        {data && <span className="kw-count" role="status">
          {request.search ? `找到 ${data.total} 个 · 按创建时间从新到旧` : `共 ${data.total} 个 · 按创建时间从新到旧`}
        </span>}
      </div>
      {rows?.length === 0 && !request.search && <div className="kw-empty overview-surface mh-empty">
        <span>还没有知识库。先创建知识库并上传数据，或新建数据源关联外部表。</span>
        <div className="mh-empty-choices"><DataSourceChoices compact /></div>
      </div>}
      {(!failure && (!rows || rows.length > 0)) && <>
        <p className="mh-scroll-hint">左右滑动可查看其余列。</p>
        <div className="kw-table-scroll mh-table-scroll" role="region" aria-label="知识库建模列表" tabIndex={0}><table className="kw-table mh-table" aria-busy={!rows}>
        <thead><tr>{HEADERS.map((label, i) => <th key={label} scope="col" className={i === 2 || i === 3 ? 'numeric' : i === 6 ? 'end' : undefined}>{label}</th>)}</tr></thead>
        <tbody>{!rows ? Array.from({ length: 5 }, (_, i) => <tr className="mh-skeleton-row" key={i} aria-hidden="true">
          {SKELETON_WIDTHS.map((width, j) => <td key={j} className={j === 2 || j === 3 ? 'numeric' : undefined}><span className="mh-skeleton" style={{ width }} /></td>)}
        </tr>) : rows.map(group => {
          const summary = currentSummaries?.items.get(group.id);
          const s = status(group, summary, !currentSummaries);
          return <tr key={group.id} tabIndex={0} aria-label={`打开${group.name}的语义建模`} onClick={() => open(group)}
            onKeyDown={e => { if (e.key === 'Enter' && e.target === e.currentTarget) open(group); }}>
            <td><div className="mh-name"><span className="overview-icon mh-icon"><Icon name="model" size="sm" /></span>
              <span className="mh-name-copy"><strong title={group.name}>{group.name}</strong>
                <small>{group.description || '暂无说明'}</small></span></div></td>
            <td><span className={`overview-status is-${s.tone}`}>{s.label}</span>
              {!!group.publishedVersion && s.tone !== 'published' && <span className="mh-sub">线上 v{group.publishedVersion}</span>}</td>
            <td className="numeric">{summary?.published?.models ?? '—'}</td>
            <td className="numeric">{summary?.published?.relations ?? '—'}</td>
            <td>{summary?.published?.unrelated ? <span className="mh-attention">{summary.published.unrelated} 个模型未关联</span> : '—'}</td>
            <td>{group.createdAt ? <time dateTime={group.createdAt}>{new Date(group.createdAt).toLocaleDateString('zh-CN')}</time> : '—'}</td>
            <td className="end">
              <button type="button" className={`da-btn overview-action${group.canManage ? ' is-soft' : ''}`}
                onClick={e => { e.stopPropagation(); open(group); }}>
                {group.canManage ? '进入建模' : '查看模型'}<Icon name="chevron" size="sm" />
              </button>
            </td>
          </tr>;
        })}</tbody></table></div>
      </>}
      {rows?.length === 0 && request.search && <div className="kw-empty overview-surface mh-empty">
        <strong>没有匹配的知识库</strong>
        <button type="button" className="da-btn overview-action" onClick={() => setQuery('')}>清除搜索</button>
      </div>}
      {pages > 1 && <nav className="kw-pagination" aria-label="建模列表分页">
        <span>每页 {PAGE_SIZE} 个</span><span className="kw-spacer" />
        <button type="button" className="da-btn da-btn-sm" disabled={request.page === 0} onClick={() => setRequest({ ...request, page: request.page - 1 })}>上一页</button>
        <span>{request.page + 1} / {pages}</span>
        <button type="button" className="da-btn da-btn-sm" disabled={request.page + 1 >= pages} onClick={() => setRequest({ ...request, page: request.page + 1 })}>下一页</button>
      </nav>}
    </div></div>
  </div>;
}
