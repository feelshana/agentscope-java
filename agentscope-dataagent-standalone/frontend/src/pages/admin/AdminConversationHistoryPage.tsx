import { useEffect, useState } from 'react';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import {
  AdminConversationTurn, AdminDirectoryUser, AdminHistoryFilters, AdminHistorySession,
  getAdminConversation, listAdminHistory,
} from '../../api/adminHistory';
import { KnowledgeBreadcrumb } from '../../components/KnowledgeWorkspace';
import AdminUserSearch, { adminUserLabel } from './AdminUserSearch';
import './AdminHistory.css';

const emptyFilters: AdminHistoryFilters = { title: '', from: '', to: '' };
const dateFormat = new Intl.DateTimeFormat('zh-CN', {
  dateStyle: 'medium', timeStyle: 'medium', timeZone: 'Asia/Shanghai',
});
const formatTime = (ms: number) => ms > 0 ? dateFormat.format(ms) : '时间未知';

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

  return <section className="admin-history-box" aria-label="对话详情">
    <div className="admin-history-row">
      <h2>{session.title || '未命名会话'}</h2>
      <button className="da-btn" type="button" onClick={close}>关闭详情</button>
    </div>
    <p className="da-small">{adminUserLabel(session.owner)}</p>
    {loading && <p role="status">正在加载正文…</p>}
    {error && <p role="alert">{error}</p>}
    {!loading && !error && !turns.length && <p>正文暂不可用。</p>}
    {before !== null && <button className="da-btn" type="button" disabled={loading}
      onClick={() => void loadEarlier()}>加载更早对话</button>}
    <ol className="admin-history-list">{turns.map(turn => <li key={turn.position} className="admin-history-turn">
      <h3>提问 · {formatTime(turn.askedAtMs)}</h3>
      <MessageText value={turn.question || '（无文字）'} />
      <h3>回复{turn.answeredAtMs ? ` · ${formatTime(turn.answeredAtMs)}` : ''}</h3>
      {turn.answer ? <MessageText value={turn.answer} /> : <p>暂无已保存的回复正文。</p>}
    </li>)}</ol>
  </section>;
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

  return <div className="admin-history-columns">
    <section aria-label="会话列表">
      {loading && <p role="status">正在加载历史…</p>}
      {error && <p role="alert">{error}</p>}
      {!loading && !error && !sessions.length && <p>没有符合条件的会话。</p>}
      <ul className="admin-history-list">{sessions.map(session => (
        <li key={session.sessionKey} className="admin-history-session-row">
          <button className="da-btn admin-history-session" type="button"
            aria-pressed={selected?.sessionKey === session.sessionKey} onClick={() => setSelected(session)}>
            <strong>{session.title || '未命名会话'}</strong>
            <span>{session.agentId} · {formatTime(session.lastActivityMs)}</span>
            {!session.hasMessages && <span>正文暂不可用</span>}
          </button>
          {!userId && <button className="da-btn admin-history-owner" type="button"
            onClick={() => selectUser(session.owner)} title="查看该用户的所有会话">
            {adminUserLabel(session.owner)}
          </button>}
        </li>
      ))}</ul>
      {hasMore && <button className="da-btn" type="button" disabled={loading}
        onClick={() => void loadMore()}>加载更多会话</button>}
    </section>
    {selected && <ConversationDetails key={selected.sessionKey} session={selected}
      close={() => setSelected(null)} />}
  </div>;
}

export default function AdminConversationHistoryPage() {
  const [user, setUser] = useState<AdminDirectoryUser | null>(null);
  const [draft, setDraft] = useState(emptyFilters);
  const [filters, setFilters] = useState(emptyFilters);
  return <div className="kw-page">
    <KnowledgeBreadcrumb items={[{ label: '更多', to: '/more' }, { label: '用户对话历史' }]} />
    <div className="kw-scroll"><div className="kw-content admin-history">
      <h1>用户对话历史</h1>
      <AdminUserSearch onSelect={setUser} selectSingleMatch />
      <div className="admin-history-row">
        <h2>{user ? adminUserLabel(user) : '所有用户的会话'}</h2>
        {user && <button className="da-btn" type="button" onClick={() => setUser(null)}>查看所有用户</button>}
      </div>
      <form className="admin-history-form" onSubmit={event => { event.preventDefault(); setFilters(draft); }}>
        <label>会话标题 <input className="da-input" maxLength={160} value={draft.title}
          onChange={event => setDraft({ ...draft, title: event.target.value })} /></label>
        <label>活动起始日期 <input className="da-input" type="date" value={draft.from} max={draft.to || undefined}
          onChange={event => setDraft({ ...draft, from: event.target.value })} /></label>
        <label>活动结束日期 <input className="da-input" type="date" value={draft.to} min={draft.from || undefined}
          onChange={event => setDraft({ ...draft, to: event.target.value })} /></label>
        <button className="da-btn" type="submit">筛选</button>
        <button className="da-btn" type="button" onClick={() => { setDraft(emptyFilters); setFilters(emptyFilters); }}>清空筛选</button>
      </form>
      <p className="da-small">按最近活动时间倒序显示，时间为北京时间。</p>
      <ConversationList key={JSON.stringify([user?.userId, filters])} userId={user?.userId}
        filters={filters} selectUser={setUser} />
    </div></div>
  </div>;
}
