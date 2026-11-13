import { useEffect, useId, useRef, useState } from 'react';
import KnowledgeScopeLabel from '../../components/KnowledgeScopeLabel';
import ReadOnlyAnswer from './ReadOnlyAnswer';
import Brand from '../../components/Brand';
import {
  AdminConversationTurn, AdminDirectoryUser, AdminHistorySession,
  getAdminConversation, getHistoryOverview, historyUsers, exportHistory, HistoryQuery, HistoryOverview,
} from '../../api/adminHistory';
import { KnowledgeBreadcrumb } from '../../components/KnowledgeWorkspace';
import Icon from '../../components/Icon';
import { adminUserLabel } from './AdminUserSearch';
import HistoryUserPicker, { HistoryUserButton } from './HistoryUserPicker';
import '../../detail-surfaces.css';
import './AdminHistory.css';


const dateFormat = new Intl.DateTimeFormat('sv-SE', {
  dateStyle: 'short', timeStyle: 'short', timeZone: 'Asia/Shanghai',
});
const formatTime = (ms: number) => ms > 0 ? dateFormat.format(ms).replace(/-/g, '/') : '时间未知';

function ActivityTime({ value }: { value: number }) {
  if (!(value > 0)) return <>时间未知</>;
  const [date, time] = formatTime(value).split(' ');
  return <time dateTime={new Date(value).toISOString()}><span>{date}</span><span>{time}</span></time>;
}

function ConversationDetails({ session, close }: {
  session: AdminHistorySession;
  close: () => void;
}) {
  const { userId } = session.owner;
  const [turns, setTurns] = useState<AdminConversationTurn[]>([]);
  const [before, setBefore] = useState<number | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const dialogRef = useRef<HTMLDialogElement>(null);
  const titleId = useId();
  const [find, setFind] = useState('');
  const [expanded, setExpanded] = useState(false);
  const [retry, setRetry] = useState(0);
  const bodyRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    const dialog = dialogRef.current;
    dialog?.showModal();
    return () => dialog?.close();
  }, []);

  useEffect(() => {
    const controller = new AbortController();
    setError(''); setLoading(true);
    getAdminConversation(userId, session.sessionKey, undefined, controller.signal).then(page => {
      if (controller.signal.aborted) return;
      setTurns(page.items);
      setBefore(page.nextBefore);
    }).catch(e => {
      if (!controller.signal.aborted) setError((e as Error).message);
    }).finally(() => { if (!controller.signal.aborted) setLoading(false); });
    return () => controller.abort();
  }, [userId, session.sessionKey, retry]);

  async function loadEarlier() {
    if (before === null) return;
    setLoading(true);
    setError('');
    try {
      const page = await getAdminConversation(userId, session.sessionKey, before);
      const body = bodyRef.current;
      const height = body?.scrollHeight ?? 0;
      const top = body?.scrollTop ?? 0;
      setTurns(previous => [...page.items, ...previous]);
      requestAnimationFrame(() => { if (body) body.scrollTop = top + body.scrollHeight - height; });
      setBefore(page.nextBefore);
    } catch (e) { setError((e as Error).message); }
    finally { setLoading(false); }
  }

  function closeDrawer() {
    dialogRef.current?.close();
    close();
  }

  return <dialog className={`admin-history-drawer ah-replay${expanded ? " is-expanded" : ""}`} ref={dialogRef} aria-labelledby={titleId}
    onCancel={event => { event.preventDefault(); closeDrawer(); }}>
    <header className="admin-drawer-header">
      <div>
        <h2 id={titleId}>{session.title || '未命名会话'}</h2>
        <p>{adminUserLabel(session.owner)}<span className="ah-readonly-label">只读回看</span></p>
      </div>
      <button className="da-btn da-btn-sm ah-expand" type="button" onClick={() => setExpanded(v => !v)} aria-pressed={expanded}><Icon name={expanded ? 'zoomOut' : 'fit'} size="sm" />{expanded ? '收起阅读' : '展开阅读'}</button>
      <button className="da-btn admin-icon-button" type="button" aria-label="关闭详情" onClick={closeDrawer}>
        <Icon name="close" size="sm" />
      </button>
    </header>
    <div className="ah-detail-search"><Icon name="search" size="sm" /><input className="da-input" aria-label="在已加载对话中查找" placeholder="在已加载对话中查找" value={find} onChange={e => setFind(e.target.value)} /></div>
    <div className="admin-drawer-body" ref={bodyRef}>
      {loading && <p className="admin-list-feedback" role="status">正在加载正文…</p>}
      {error && <div className="admin-list-feedback admin-error" role="alert">{error}<button className="da-btn da-btn-sm" onClick={() => before !== null ? void loadEarlier() : setRetry(n => n + 1)}>重新加载</button></div>}
      {!loading && !error && !turns.length && <p className="admin-list-feedback">正文暂不可用。</p>}
      {before !== null && <button className="da-btn da-btn-sm" type="button" disabled={loading}
        onClick={() => void loadEarlier()}>加载更早对话</button>}
      <ol className="admin-history-list">{turns.filter(turn => [turn.question, turn.answer].join(' ').toLowerCase().includes(find.trim().toLowerCase())).map(turn => <li key={turn.position} className="admin-history-turn">
        <div className="ah-replay-question"><time>{formatTime(turn.askedAtMs)}</time><p>{turn.question || '（无文字）'}</p><KnowledgeScopeLabel scope={turn.knowledgeScope} showUnknown /></div>
        <div className="ah-replay-response"><div className="ah-replay-brand"><Brand /> {turn.answeredAtMs ? <time>{formatTime(turn.answeredAtMs)}</time> : null}</div><ReadOnlyAnswer turn={turn} portalRoot={dialogRef.current} /></div>
      </li>)}</ol>
    </div>
  </dialog>;
}


function UserStatistics({ query, close, select }: { query: HistoryQuery; close: () => void; select: (u: AdminDirectoryUser) => void }) {
  const dialog = useRef<HTMLDialogElement>(null);
  const [page, setPage] = useState(0);
  const [data, setData] = useState<Awaited<ReturnType<typeof historyUsers>> | null>(null);
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(true);
  useEffect(() => { dialog.current?.showModal(); return () => dialog.current?.close(); }, []);
  useEffect(() => {
    const c = new AbortController(); setLoading(true); setError('');
    historyUsers(query, page, c.signal).then(result => { if (!c.signal.aborted) setData(result); })
      .catch(e => { if (!c.signal.aborted) setError((e as Error).message); })
      .finally(() => { if (!c.signal.aborted) setLoading(false); });
    return () => c.abort();
  }, [query, page]);
  return <dialog ref={dialog} className="admin-history-drawer ah-stats-drawer" aria-labelledby="ah-stats-title" onCancel={e => { e.preventDefault(); close(); }}>
    <header className="admin-drawer-header"><div><h2 id="ah-stats-title">用户统计</h2><p>当前筛选内，每位用户的提问与会话数</p></div><button className="da-btn admin-icon-button" aria-label="关闭用户统计" onClick={close}><Icon name="close" size="sm" /></button></header>
    <div className="admin-drawer-body">{loading ? <p role="status">加载用户统计…</p> : error ? <p role="alert">{error}</p> : data?.items.length ? <table className="admin-table"><thead><tr><th>用户</th><th>提问</th><th>会话</th></tr></thead><tbody>{data.items.map(row => <tr key={row.owner.userId}><td><HistoryUserButton user={row.owner} onClick={() => { select(row.owner); close(); }} /></td><td>{row.questions}</td><td>{row.sessions}</td></tr>)}</tbody></table> : <p>当前筛选没有用户</p>}</div>
    <footer className="ah-pagination"><span>第 {page + 1} 页</span><button className="da-btn da-btn-sm" disabled={loading || !page} onClick={() => setPage(n => n - 1)}>上一页</button><button className="da-btn da-btn-sm" disabled={loading || !data?.hasMore} onClick={() => setPage(n => n + 1)}>下一页</button></footer>
  </dialog>;
}

function dateWindow(range: string) {
  const today = new Date(Date.now() + 8 * 3600000).toISOString().slice(0, 10);
  if (range === 'all') return { from: '', to: '' };
  const from = new Date(Date.parse(today + 'T00:00:00Z') - (Number(range) - 1) * 86400000).toISOString().slice(0, 10);
  return { from, to: today };
}
const initialQuery = (): HistoryQuery => ({ userId: '', query: '', ...dateWindow('7'), sort: 'activity', direction: 'desc', page: 0, pageSize: 10 });

export default function AdminConversationHistoryPage() {
  const [user, setUser] = useState<AdminDirectoryUser | null>(null);
  const [draft, setDraft] = useState('');
  const [composing, setComposing] = useState(false);
  const [query, setQuery] = useState(initialQuery);
  const [range, setRange] = useState('7');
  const [data, setData] = useState<HistoryOverview | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [refresh, setRefresh] = useState(0);
  const [selected, setSelected] = useState<AdminHistorySession | null>(null);
  const [statistics, setStatistics] = useState(false);
  const [exporting, setExporting] = useState(false);
  const [notice, setNotice] = useState('');
  const [info, setInfo] = useState(false);
  const infoDialog = useRef<HTMLDialogElement>(null);
  const searchRef = useRef<HTMLInputElement>(null);
  const modified = !!(query.query || user || range !== '7');
  useEffect(() => { if (info) infoDialog.current?.showModal(); }, [info]);
  useEffect(() => {
    if (composing) return;
    const timer = window.setTimeout(() => setQuery(q => q.query === draft.trim() ? q : { ...q, query: draft.trim(), page: 0 }), 200);
    return () => window.clearTimeout(timer);
  }, [draft, composing]);
  useEffect(() => {
    const controller = new AbortController(); setLoading(true); setError('');
    let poll: number | undefined;
    getHistoryOverview(query, controller.signal).then(result => {
      if (controller.signal.aborted) return;
      setData(result);
      if (result.indexing) poll = window.setTimeout(() => setRefresh(n => n + 1), 3000);
    }).catch(e => { if (!controller.signal.aborted) setError((e as Error).message); })
      .finally(() => { if (!controller.signal.aborted) setLoading(false); });
    return () => { controller.abort(); window.clearTimeout(poll); };
  }, [query, refresh]);
  function select(u: AdminDirectoryUser | null) { setUser(u); setQuery(q => ({ ...q, userId: u?.userId ?? '', page: 0 })); setNotice(''); }
  function reset() { setUser(null); setDraft(''); setRange('7'); setQuery(initialQuery()); setNotice(''); }
  function sort(key: HistoryQuery['sort']) { setQuery(q => ({ ...q, sort: key, direction: q.sort === key && q.direction === 'desc' ? 'asc' : 'desc', page: 0 })); }
  async function download() {
    setExporting(true); setNotice('');
    try {
      const blob = await exportHistory(query); const url = URL.createObjectURL(blob);
      const a = document.createElement('a'); a.href = url; a.download = '用户对话历史.csv'; document.body.appendChild(a); a.click(); a.remove();
      window.setTimeout(() => URL.revokeObjectURL(url), 1000); setNotice('已导出当前筛选的会话列表');
    } catch (e) { setNotice((e as Error).message); }
    finally { setExporting(false); }
  }
  const sessions = !loading && !error ? data?.items ?? [] : [];
  const total = data?.totals.sessions ?? 0;
  const page = data?.page ?? query.page;
  const pages = Math.max(1, Math.ceil(total / query.pageSize));
  return <div className="kw-page ah-page">
    <div className="ah-top"><KnowledgeBreadcrumb items={[{ label: '更多', to: '/more' }, { label: '用户对话历史' }]} /><div className="kw-actions"><button className="da-btn ah-icon" aria-label="刷新会话列表" title="刷新会话列表" disabled={loading} onClick={() => setRefresh(n => n + 1)}><Icon name="refresh" size="sm" /></button><button className="da-btn" disabled={loading || exporting || !total || data?.indexing} onClick={() => void download()}><Icon name="file" size="sm" />{exporting ? '导出中…' : '导出列表'}</button></div></div>
    <div className="ah-content">
      <div className="ah-filters"><form className="ah-search" role="search" onSubmit={e => { e.preventDefault(); if (!composing) { setQuery(q => ({ ...q, query: draft.trim(), page: 0 })); searchRef.current?.focus(); } }}>
        <button className="ah-icon" type="submit" aria-label="搜索问题"><Icon name="search" size="sm" /></button><input ref={searchRef} type="search" aria-label="搜索会话标题或提问内容" placeholder="搜索会话标题或提问内容" maxLength={160} value={draft} onCompositionStart={() => setComposing(true)} onCompositionEnd={() => setComposing(false)} onChange={e => setDraft(e.target.value)} />
      </form><HistoryUserPicker user={user} select={select} from={query.from} to={query.to} />
        <label className="ah-date"><Icon name="calendar" size="sm" /><select aria-label="活动时间范围" value={range} onChange={e => { setRange(e.target.value); if (e.target.value !== 'custom') setQuery(q => ({ ...q, ...dateWindow(e.target.value), page: 0 })); }}><option value="1">今天</option><option value="7">近 7 天</option><option value="30">近 30 天</option><option value="all">全部时间</option><option value="custom">自定日期</option></select></label>
        {range === 'custom' && <div className="ah-custom-date"><label>从<input type="date" value={query.from} max={query.to || undefined} onChange={e => setQuery(q => ({ ...q, from: e.target.value, page: 0 }))} /></label><span>—</span><label>至<input type="date" value={query.to} min={query.from || undefined} onChange={e => setQuery(q => ({ ...q, to: e.target.value, page: 0 }))} /></label></div>}
        {modified && <button className="admin-table-link ah-reset" onClick={reset}>重置筛选</button>}
      </div>
      {notice && <div className="ah-notice" role="status">{notice}</div>}
      {data?.indexing && <div className="ah-notice" role="status">正在整理旧会话的提问索引，当前计数与正文检索尚未完整，将自动更新。</div>}
      <section className="ah-list-card" aria-label="会话列表" aria-busy={loading}>
        <div className="ah-summary"><div aria-live="polite">{loading ? <span>正在加载历史…</span> : error ? <span>列表加载失败</span> : <><span><strong>{total.toLocaleString()}</strong> 条会话</span><span><strong>{data?.totals.questions.toLocaleString() ?? '0'}</strong> 次提问</span><span><strong>{data?.totals.users.toLocaleString() ?? '0'}</strong> 位用户</span></>}</div><button className="admin-table-link" disabled={loading || !!error} onClick={() => setStatistics(true)}>用户统计</button><button className="ah-icon" aria-label="查看统计口径" title="查看统计口径" onClick={() => setInfo(true)}><Icon name="info" size="sm" /></button></div>
        <div className="ah-table-scroll"><table className="admin-table ah-table"><thead><tr><th>会话标题</th><th>用户</th><th aria-sort={query.sort === 'questions' ? query.direction === 'desc' ? 'descending' : 'ascending' : 'none'}><button onClick={() => sort('questions')}>本会话提问<Icon name={query.sort === 'questions' && query.direction === 'asc' ? 'arrowUp' : 'arrowDown'} size="sm" /></button></th><th aria-sort={query.sort === 'activity' ? query.direction === 'desc' ? 'descending' : 'ascending' : 'none'}><button onClick={() => sort('activity')}>最近活动时间<Icon name={query.sort === 'activity' && query.direction === 'asc' ? 'arrowUp' : 'arrowDown'} size="sm" /></button></th></tr></thead><tbody>{sessions.map(session => <tr key={session.sessionKey} onClick={() => setSelected(session)}><td><button className="admin-table-link ah-session-title" onClick={e => { e.stopPropagation(); setSelected(session); }} aria-haspopup="dialog" title={session.title || '未命名会话'}><Icon name="chat" size="sm" /><span className="ah-title-label">{session.title || '未命名会话'}</span></button></td><td onClick={e => e.stopPropagation()}><HistoryUserButton user={session.owner} onClick={() => select(session.owner)} /></td><td className="ah-number">{session.questionCount ?? 0} 问</td><td><div className="ah-time"><ActivityTime value={session.lastActivityMs} /><Icon name="chevron" size="sm" /></div></td></tr>)}</tbody></table>
          {loading && <div className="ah-feedback" role="status"><span className="da-spinner da-spinner-sm" />正在加载会话…</div>}
          {error && <div className="ah-feedback" role="alert"><p>{error}</p><button className="da-btn" onClick={() => setRefresh(n => n + 1)}>重新加载</button></div>}
          {!loading && !error && !sessions.length && <div className="ah-feedback"><Icon name="search" /><p>没有符合条件的会话</p><button className="admin-table-link" onClick={reset}>清除筛选条件</button></div>}
        </div>
        <footer className="ah-pagination"><span>{loading ? '加载中…' : total ? `显示 ${page * query.pageSize + 1}–${Math.min((page + 1) * query.pageSize, total)} 条，共 ${total} 条会话` : '共 0 条会话'}</span><div><label>每页 <select aria-label="每页会话数" value={query.pageSize} onChange={e => setQuery(q => ({ ...q, pageSize: Number(e.target.value), page: 0 }))}><option>10</option><option>20</option><option>50</option></select> 条</label><button className="da-btn da-btn-sm" aria-label="上一页" disabled={loading || !page} onClick={() => setQuery(q => ({ ...q, page: page - 1 }))}><Icon name="back" size="sm" /></button><span>{page + 1} / {pages}</span><button className="da-btn da-btn-sm" aria-label="下一页" disabled={loading || page + 1 >= pages} onClick={() => setQuery(q => ({ ...q, page: page + 1 }))}><Icon name="chevron" size="sm" /></button></div></footer>
      </section>
      <small className="ah-footnote">时间为北京时间 · 按会话最近活动筛选</small>
    </div>
    {selected && <ConversationDetails key={selected.sessionKey} session={selected} close={() => setSelected(null)} />}
    {statistics && <UserStatistics query={query} close={() => setStatistics(false)} select={select} />}
    {info && <dialog ref={infoDialog} className="ah-info-dialog" aria-labelledby="ah-info-title" onCancel={() => setInfo(false)}><header><h2 id="ah-info-title">统计口径</h2><button className="ah-icon" aria-label="关闭统计说明" onClick={() => { infoDialog.current?.close(); setInfo(false); }}><Icon name="close" size="sm" /></button></header><p>时间范围按会话最后活动时间筛选。提问数统计这些会话中的全部用户提问，不计 AI 回复或工具消息，并非仅统计日期范围内新增的提问。</p><p>问题搜索匹配会话标题或用户提问正文；用户筛选独立生效。列表、用户统计和导出使用相同筛选条件。</p><p>对话与用户反馈只读，可查看当时保存的图表和图片；不重新执行查询，不展示工具原始结果。</p></dialog>}
  </div>;
}
