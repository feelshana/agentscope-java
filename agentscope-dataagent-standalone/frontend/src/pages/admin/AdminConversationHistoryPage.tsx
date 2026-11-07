import { useEffect, useId, useRef, useState } from 'react';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import {
  AdminConversationTurn, AdminDirectoryUser, AdminHistoryFilters, AdminHistorySession,
  getAdminConversation, listAdminHistory,
} from '../../api/adminHistory';
import { KnowledgeBreadcrumb } from '../../components/KnowledgeWorkspace';
import Icon from '../../components/Icon';
import AdminUserSearch, { adminUserLabel } from './AdminUserSearch';
import './AdminHistory.css';

const emptyFilters: AdminHistoryFilters = { title: '', from: '', to: '' };
const dateFormat = new Intl.DateTimeFormat('sv-SE', {
  dateStyle: 'short', timeStyle: 'medium', timeZone: 'Asia/Shanghai',
});
const formatTime = (ms: number) => ms > 0 ? dateFormat.format(ms) : '时间未知';

function ActivityTime({ value }: { value: number }) {
  if (!(value > 0)) return <>时间未知</>;
  const [date, time] = formatTime(value).split(' ');
  return <time dateTime={new Date(value).toISOString()}><span>{date}</span><span>{time}</span></time>;
}

function MessageText({ value }: { value: string }) {
  return <div className="admin-history-message"><ReactMarkdown skipHtml remarkPlugins={[remarkGfm]}
    urlTransform={() => ''}
    components={{ a: ({ children }) => <span>{children}</span>, img: () => <span>［附件或图片不可查看］</span> }}>
    {value}
  </ReactMarkdown></div>;
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

  useEffect(() => {
    const dialog = dialogRef.current;
    dialog?.showModal();
    return () => dialog?.close();
  }, []);

  useEffect(() => {
    const controller = new AbortController();
    getAdminConversation(userId, session.sessionKey, undefined, controller.signal).then(page => {
      if (controller.signal.aborted) return;
      setTurns(page.items);
      setBefore(page.nextBefore);
    }).catch(e => {
      if (!controller.signal.aborted) setError((e as Error).message);
    }).finally(() => { if (!controller.signal.aborted) setLoading(false); });
    return () => controller.abort();
  }, [userId, session.sessionKey]);

  async function loadEarlier() {
    if (before === null) return;
    setLoading(true);
    setError('');
    try {
      const page = await getAdminConversation(userId, session.sessionKey, before);
      setTurns(previous => [...page.items, ...previous]);
      setBefore(page.nextBefore);
    } catch (e) { setError((e as Error).message); }
    finally { setLoading(false); }
  }

  function closeDrawer() {
    dialogRef.current?.close();
    close();
  }

  return <dialog className="admin-history-drawer" ref={dialogRef} aria-labelledby={titleId}
    onCancel={event => { event.preventDefault(); closeDrawer(); }}>
    <header className="admin-drawer-header">
      <div>
        <h2 id={titleId}>{session.title || '未命名会话'}</h2>
        <p>{adminUserLabel(session.owner)}</p>
      </div>
      <button className="da-btn admin-icon-button" type="button" aria-label="关闭详情" onClick={closeDrawer}>
        <Icon name="close" size="sm" />
      </button>
    </header>
    <div className="admin-drawer-body">
      {loading && <p className="admin-list-feedback" role="status">正在加载正文…</p>}
      {error && <p className="admin-list-feedback admin-error" role="alert">{error}</p>}
      {!loading && !error && !turns.length && <p className="admin-list-feedback">正文暂不可用。</p>}
      {before !== null && <button className="da-btn da-btn-sm" type="button" disabled={loading}
        onClick={() => void loadEarlier()}>加载更早对话</button>}
      <ol className="admin-history-list">{turns.map(turn => <li key={turn.position} className="admin-history-turn">
        <h3>提问 <span>{formatTime(turn.askedAtMs)}</span></h3>
        <MessageText value={turn.question || '（无文字）'} />
        <h3>回复{turn.answeredAtMs ? <span>{formatTime(turn.answeredAtMs)}</span> : null}</h3>
        {turn.answer ? <MessageText value={turn.answer} /> : <p className="da-small">暂无已保存的回复正文。</p>}
      </li>)}</ol>
    </div>
  </dialog>;
}

function ConversationList({ userId, filters, selectUser }: {
  userId?: string;
  filters: AdminHistoryFilters;
  selectUser: (user: AdminDirectoryUser) => void;
}) {
  const [sessions, setSessions] = useState<AdminHistorySession[]>([]);
  const [selected, setSelected] = useState<AdminHistorySession | null>(null);
  const [hasMore, setHasMore] = useState(false);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');

  useEffect(() => {
    const controller = new AbortController();
    listAdminHistory(userId, filters, undefined, controller.signal).then(page => {
      if (controller.signal.aborted) return;
      setSessions(page.items);
      setHasMore(page.hasMore);
    }).catch(e => {
      if (!controller.signal.aborted) setError((e as Error).message);
    }).finally(() => { if (!controller.signal.aborted) setLoading(false); });
    return () => controller.abort();
  }, [userId, filters]);

  async function loadMore() {
    setLoading(true);
    setError('');
    try {
      const page = await listAdminHistory(userId, filters, sessions[sessions.length - 1]);
      setSessions(previous => [...previous, ...page.items]);
      setHasMore(page.hasMore);
    } catch (e) { setError((e as Error).message); }
    finally { setLoading(false); }
  }

  return <section aria-label="会话列表">
    <table className="admin-table admin-session-table" aria-label="会话列表" aria-busy={loading}>
      <thead><tr>
        <th scope="col">会话标题</th><th scope="col">用户名</th><th scope="col">最近活动时间</th>
      </tr></thead>
      <tbody>{sessions.map(session => {
        const title = session.title || '未命名会话';
        return <tr key={JSON.stringify([session.owner.userId, session.sessionKey])}>
          <td><button className="admin-table-link admin-session-title" type="button" title={title}
            aria-haspopup="dialog" onClick={() => setSelected(session)}>{title}</button></td>
          <td>{userId ? <span className="admin-username">{session.owner.username}</span>
            : <button className="admin-table-link admin-username" type="button"
              onClick={() => selectUser(session.owner)} title="查看该用户的所有会话">{session.owner.username}</button>}</td>
          <td><ActivityTime value={session.lastActivityMs} /></td>
        </tr>;
      })}</tbody>
    </table>
    {loading && <p className="admin-list-feedback" role="status">正在加载历史…</p>}
    {error && <p className="admin-list-feedback admin-error" role="alert">{error}</p>}
    {!loading && !error && !sessions.length && <p className="admin-list-feedback">没有符合条件的会话。</p>}
    {sessions.length > 0 && <footer className="admin-list-footer">
      <span>已显示 {sessions.length} 条会话</span>
      {hasMore && <button className="da-btn da-btn-sm" type="button" disabled={loading}
        onClick={() => void loadMore()}>加载更多会话</button>}
    </footer>}
    {selected && <ConversationDetails key={selected.sessionKey} session={selected}
      close={() => setSelected(null)} />}
  </section>;
}

export default function AdminConversationHistoryPage() {
  const [user, setUser] = useState<AdminDirectoryUser | null>(null);
  const [draft, setDraft] = useState(emptyFilters);
  const [filters, setFilters] = useState(emptyFilters);
  return <div className="kw-page">
    <KnowledgeBreadcrumb items={[{ label: '更多', to: '/more' }, { label: '用户对话历史' }]} />
    <div className="kw-scroll"><div className="kw-content admin-history">
      <header className="admin-page-header">
        <div><h1>用户对话历史</h1><p>按用户、会话标题和日期查看提问与回复。</p></div>
      </header>
      <div className="admin-history-toolbar">
        <form className="admin-history-form" onSubmit={event => { event.preventDefault(); setFilters(draft); }}>
          <label className="admin-history-title-filter">会话标题 <input className="da-input" type="search" maxLength={160}
            placeholder="搜索会话标题" value={draft.title}
            onChange={event => setDraft({ ...draft, title: event.target.value })} /></label>
          <label>活动起始日期 <input className="da-input" type="date" value={draft.from} max={draft.to || undefined}
            onChange={event => setDraft({ ...draft, from: event.target.value })} /></label>
          <label>活动结束日期 <input className="da-input" type="date" value={draft.to} min={draft.from || undefined}
            onChange={event => setDraft({ ...draft, to: event.target.value })} /></label>
          <div className="admin-form-actions">
            <button className="da-btn" type="submit">筛选</button>
            <button className="da-btn da-btn-ghost" type="button" onClick={() => { setDraft(emptyFilters); setFilters(emptyFilters); }}>清空筛选</button>
          </div>
        </form>
        <AdminUserSearch onSelect={setUser} selectSingleMatch />
      </div>
      <div className="admin-list-meta">
        <div className="admin-user-scope">
          <h2>{user ? user.username : '所有用户的会话'}</h2>
          {user && <button className="admin-table-link" type="button" onClick={() => setUser(null)}>查看所有用户</button>}
        </div>
        <span><Icon name="clock" size="sm" /> 最近活动优先 · 北京时间</span>
      </div>
      <ConversationList key={JSON.stringify([user?.userId, filters])} userId={user?.userId}
        filters={filters} selectUser={setUser} />
    </div></div>
  </div>;
}
