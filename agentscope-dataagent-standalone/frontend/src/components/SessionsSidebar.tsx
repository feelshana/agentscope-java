import React, { useEffect, useMemo, useRef, useState } from 'react';
import { useLocation, useNavigate, useSearchParams } from 'react-router-dom';
import { ACTIVE_AGENT_ID } from '../api/activeAgent';
import { getToken, isAdmin } from '../api/auth';
import { InboxEntry, deleteSession, inboxPage } from '../api/sessions';
import Icon, { IconName } from './Icon';

interface UtilityItem {
  label: string;
  path: string;
  icon: IconName;
}

/** Primary nav (新建对话 and 更多 are rendered separately). */
const NAV_ITEMS: UtilityItem[] = [
  { label: '知识库', path: '/configure/datasets', icon: 'book' },
  { label: '业务术语', path: '/configure/semantic', icon: 'type' },
  { label: '例行任务', path: '/configure/scheduled-tasks', icon: 'clock' },
];

function decodeJwt(token: string): Record<string, unknown> {
  try {
    const payload = token.split('.')[1].replace(/-/g, '+').replace(/_/g, '/');
    return JSON.parse(new TextDecoder().decode(Uint8Array.from(atob(payload), c => c.charCodeAt(0))));
  } catch { return {}; }
}

function getUsername(): string {
  const token = getToken();
  if (!token) return '';
  const p = decodeJwt(token);
  return (p.username as string) || (p.sub as string) || '';
}

function startOfDay(d: Date): number {
  const x = new Date(d);
  x.setHours(0, 0, 0, 0);
  return x.getTime();
}

type Bucket = 'today' | 'yesterday' | 'earlier';

function bucketOf(ms: number): Bucket {
  const today = startOfDay(new Date());
  const yesterday = today - 86_400_000;
  if (ms >= today) return 'today';
  if (ms >= yesterday) return 'yesterday';
  return 'earlier';
}

const BUCKET_LABEL: Record<Bucket, string> = {
  today: '今天',
  yesterday: '昨天',
  earlier: '更早',
};

function relTime(ms: number): string {
  const diff = Date.now() - ms;
  if (diff < 60_000) return '刚刚';
  if (diff < 3_600_000) return `${Math.floor(diff / 60_000)} 分钟前`;
  if (diff < 86_400_000) return `${Math.floor(diff / 3_600_000)} 小时前`;
  return `${Math.floor(diff / 86_400_000)} 天前`;
}

export interface SessionsSidebarProps {
  refreshKey: number;
}

export default function SessionsSidebar({ refreshKey }: SessionsSidebarProps) {
  const navigate = useNavigate();
  const location = useLocation();
  const [searchParams] = useSearchParams();
  const activeKey = searchParams.get('session');

  const [entries, setEntries] = useState<InboxEntry[]>([]);
  const [err, setErr] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [retryNonce, setRetryNonce] = useState(0);
  const [batchMode, setBatchMode] = useState(false);
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [batchDeleting, setBatchDeleting] = useState(false);
  const attempts = useRef(0);
  const [nextCursor, setNextCursor] = useState<string | null>(null);
  const [loadingMore, setLoadingMore] = useState(false);
  const generation = useRef(0);
  const moreRequest = useRef<AbortController | null>(null);
  const [pageError, setPageError] = useState<string | null>(null);

  async function loadMore() {
    if (!nextCursor || loading || moreRequest.current) return;
    const version = generation.current;
    const controller = new AbortController();
    moreRequest.current = controller;
    setLoadingMore(true); setPageError(null);
    try {
      const page = await inboxPage(ACTIVE_AGENT_ID, { limit: 20, cursor: nextCursor, signal: controller.signal });
      if (generation.current !== version) return;
      setEntries(old => {
        const seen = new Set(old.map(e => e.sessionKey));
        return [...old, ...page.items.filter(e => !seen.has(e.sessionKey))];
      });
      setNextCursor(page.nextCursor);
    } catch (e) {
      if (!controller.signal.aborted) setPageError('加载更多失败，请重试');
    } finally {
      if (moreRequest.current === controller) { moreRequest.current = null; setLoadingMore(false); }
    }
  }

  useEffect(() => {
    let cancelled = false;
    generation.current += 1;
    moreRequest.current?.abort(); moreRequest.current = null;
    setLoadingMore(false); setNextCursor(null); setPageError(null);
    const controller = new AbortController();
    setErr(null);
    setLoading(true);
    inboxPage(ACTIVE_AGENT_ID, { limit: 20, signal: controller.signal })
      .then(page => { if (!cancelled) { setEntries(page.items); setNextCursor(page.nextCursor); } })
      .catch(e => {
        if (cancelled) return;
        setErr(e instanceof Error ? e.message : '加载失败');
        // The backend may still be booting right after a restart: retry once.
        if (attempts.current < 1) {
          attempts.current += 1;
          window.setTimeout(() => { if (!cancelled) setRetryNonce(n => n + 1); }, 1500);
        }
      })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; controller.abort(); moreRequest.current?.abort(); };
  }, [refreshKey, retryNonce]);

  // Missing entries may be on a later page; do not fabricate a draft for an old URL.
  const grouped = useMemo(() => {
    const map: Record<Bucket, InboxEntry[]> = { today: [], yesterday: [], earlier: [] };

    for (const e of entries) map[bucketOf(e.lastActivityMs)].push(e);
    return map;
  }, [entries]);

  function handleNewChat() {
    navigate('/chat');
  }

  function entryNavKey(entry: InboxEntry): string {
    // Prefer the conversationId so the URL stays stable across turns; fall back to the storage
    // key for legacy entries that pre-date multi-session routing.
    return entry.conversationId ?? entry.sessionKey;
  }

  function openSession(entry: InboxEntry) {
    navigate(`/chat?session=${encodeURIComponent(entryNavKey(entry))}`);
  }

  async function handleDelete(entry: InboxEntry, ev: React.MouseEvent) {
    ev.stopPropagation();
    if (!confirm(`确定删除该对话？「${entry.title ?? entry.label ?? '新对话'}」`)) return;
    try {
      await deleteSession(ACTIVE_AGENT_ID, entryNavKey(entry));
      setEntries(prev => prev.filter(e => e.sessionKey !== entry.sessionKey));
      if (activeKey === entryNavKey(entry)) navigate('/chat');
    } catch (e: unknown) {
      alert(e instanceof Error ? e.message : '删除失败');
    }
  }

  function toggleBatchMode() {
    setBatchMode(m => {
      if (m) setSelected(new Set());
      return !m;
    });
  }

  function toggleSelect(key: string) {
    setSelected(prev => {
      const next = new Set(prev);
      if (next.has(key)) next.delete(key); else next.add(key);
      return next;
    });
  }

  function toggleSelectAll() {
    const allKeys = entries.map(e => entryNavKey(e));
    const allSelected = allKeys.every(k => selected.has(k));
    setSelected(allSelected ? new Set() : new Set(allKeys));
  }

  async function handleBatchDelete() {
    if (selected.size === 0) return;
    if (!confirm(`确定删除选中的 ${selected.size} 个对话？`)) return;
    setBatchDeleting(true);
    try {
      const keys = Array.from(selected);
      const results = await Promise.allSettled(keys.map(k => deleteSession(ACTIVE_AGENT_ID, k)));
      const deleted = new Set(keys.filter((_, i) => results[i].status === 'fulfilled'));
      const failed = new Set(keys.filter(k => !deleted.has(k)));
      setEntries(prev => prev.filter(e => !deleted.has(entryNavKey(e))));
      if (activeKey && deleted.has(activeKey)) navigate('/chat');
      setSelected(failed);
      setBatchMode(failed.size > 0);
      if (failed.size) alert(`${failed.size} 个会话未删除，请先停止正在进行的回答后重试。`);
    } catch (e: unknown) {
      alert(e instanceof Error ? e.message : '批量删除失败');
    } finally {
      setBatchDeleting(false);
    }
  }

  return (
    <div className="app-rail">
      <div className="rail-brand">
        <span className="rail-mark">DA</span>
        <span style={{ display: 'flex', flexDirection: 'column', minWidth: 0 }}>
          <span className="rail-brand-name">红海DataAgent</span>
          <span className="rail-brand-tag">企业级数据智能体</span>
        </span>
      </div>

      <nav className="rail-nav" aria-label="主导航">
        <button onClick={handleNewChat} className="rail-nav-item rail-new-chat"><Icon name="plus" />新建对话</button>
        {NAV_ITEMS.map(item => {
          const active = location.pathname.startsWith(item.path)
            || (item.path === '/configure/datasets' && location.pathname === '/configure/datasources');
          return <button key={item.path} onClick={() => navigate(item.path)} aria-current={active ? 'page' : undefined}
            className={`rail-nav-item${active ? ' is-active' : ''}`}><Icon name={item.icon} />{item.label}</button>;
        })}
        <button className={`rail-nav-item${location.pathname === '/more' || location.pathname === '/workspace' ? ' is-active' : ''}`}
          aria-current={location.pathname === '/more' || location.pathname === '/workspace' ? 'page' : undefined} onClick={() => navigate('/more')}><Icon name="moreHorizontal" />更多</button>
      </nav>

      <div className="rail-history" onScroll={e => {
        const el = e.currentTarget;
        if (!pageError && el.scrollHeight - el.scrollTop - el.clientHeight < 120) void loadMore();
      }}>
        {entries.length === 0 && <div className="rail-history-label">历史会话</div>}
        {entries.length > 0 && (
          <div className="rail-history-head">
            <span className="rail-history-label">历史会话</span>
            {batchMode ? (
              <>
                <button
                  type="button"
                  onClick={toggleSelectAll}
                  style={S.batchBtn}
                >
                  {entries.every(e => selected.has(entryNavKey(e))) ? '取消全选' : '全选'}
                </button>
                <button
                  type="button"
                  onClick={handleBatchDelete}
                  disabled={selected.size === 0 || batchDeleting}
                  style={{
                    ...S.batchBtn,
                    ...S.batchDeleteBtn,
                    ...(selected.size === 0 || batchDeleting ? S.batchBtnDisabled : {}),
                  }}
                >
                  {batchDeleting ? '删除中…' : `删除已选（${selected.size}）`}
                </button>
                <button
                  type="button"
                  onClick={toggleBatchMode}
                  style={S.batchCancelBtn}
                  title="取消"
                >
                  <Icon name="close" size="sm" />
                </button>
              </>
            ) : (
              <button
                type="button"
                onClick={toggleBatchMode}
                style={S.dotsBtn}
                title="批量管理"
              >
                <Icon name="moreHorizontal" size="sm" />
              </button>
            )}
          </div>
        )}
        {loading && <div style={S.muted}>加载中…</div>}
        {err && (
          <div style={S.error}>
            加载历史会话失败
            <button
              type="button"
              className="da-btn da-btn-ghost da-btn-sm"
              style={{ marginLeft: 8 }}
              onClick={() => setRetryNonce(n => n + 1)}
            >
              重试
            </button>
          </div>
        )}
        {!loading && !err && entries.length === 0 && (
          <div style={S.muted}>暂无会话。发送消息即可开始第一段对话。</div>
        )}

        {(['today', 'yesterday', 'earlier'] as Bucket[]).map(b => {
          const list = grouped[b];
          if (list.length === 0) return null;
          return (
            <div key={b} className="rail-history-group">
              <div className="rail-history-day">{BUCKET_LABEL[b]}</div>
              {list.map(e => {
                const isActive = entryNavKey(e) === activeKey && location.pathname === '/chat';
                const isSelected = selected.has(entryNavKey(e));
                return (
                  <SessionRow
                    key={e.sessionKey}
                    entry={e}
                    active={isActive}
                    batchMode={batchMode}
                    selected={isSelected}
                    onOpen={() => openSession(e)}
                    onDelete={ev => handleDelete(e, ev)}
                    onToggleSelect={() => toggleSelect(entryNavKey(e))}
                  />
                );
              })}
            </div>
          );
        })}
        {nextCursor && <button className="da-btn da-btn-ghost da-btn-sm" disabled={loadingMore}
          onClick={() => void loadMore()}>{loadingMore ? '加载中…' : pageError ?? '加载更多'}</button>}
      </div>

      <div className="rail-footer">
        <UserMenu username={getUsername()} />
      </div>
    </div>
  );
}

interface RowProps {
  entry: InboxEntry;
  active: boolean;
  batchMode: boolean;
  selected: boolean;
  onOpen: () => void;
  onDelete: (e: React.MouseEvent) => void;
  onToggleSelect: () => void;
}

function SessionRow({ entry, active, batchMode, selected, onOpen, onDelete, onToggleSelect }: RowProps) {
  const title = entry.title ?? entry.label ?? '新对话';
  return <div className={`rail-session${active ? ' is-active' : ''}${selected && batchMode ? ' is-selected' : ''}${entry.unread && !active ? ' is-unread' : ''}`}>
    {batchMode && <input type="checkbox" aria-label={`选择对话：${title}`} checked={selected} onChange={onToggleSelect} />}
    <button className="rail-session-open" title={`${title} · ${relTime(entry.lastActivityMs)}`} aria-current={active ? 'page' : undefined}
      onClick={batchMode ? onToggleSelect : onOpen}>{title}</button>
    {!batchMode && <button className="rail-session-delete" onClick={onDelete} aria-label={`删除对话：${title}`}><Icon name="trash" size="sm" /></button>}
  </div>;
}

function UserMenu({ username }: { username: string }) {
  const location = useLocation();
  const [open, setOpen] = useState(false);
  const ref = useRef<HTMLDivElement>(null);
  const trigger = useRef<HTMLButtonElement>(null);
  useEffect(() => {
    if (!open) return;
    const outside = (event: PointerEvent) => { if (!ref.current?.contains(event.target as Node)) setOpen(false); };
    const escape = (event: KeyboardEvent) => { if (event.key === 'Escape') { setOpen(false); trigger.current?.focus(); } };
    document.addEventListener('pointerdown', outside); document.addEventListener('keydown', escape);
    return () => { document.removeEventListener('pointerdown', outside); document.removeEventListener('keydown', escape); };
  }, [open]);
  useEffect(() => { setOpen(false); }, [location.pathname]);
  return <div ref={ref} className="rail-account">
    <button ref={trigger} type="button" className="rail-account-button" onClick={() => setOpen(value => !value)} aria-label="查看当前用户" aria-expanded={open}>
      <span className="rail-avatar">{username.charAt(0).toUpperCase() || '用'}</span>
      <span className="rail-account-copy"><span>{username || '用户'}</span><small>{isAdmin() ? '全局管理员' : '用户'}</small></span>
    </button>
    {open && <div className="rail-account-popover" role="status">{username || '用户'}</div>}
  </div>;
}

const S: Record<string, React.CSSProperties> = {
  dotsBtn: {
    marginLeft: 'auto',
    background: 'transparent', border: 'none', cursor: 'pointer',
    color: 'var(--da-text-muted)', padding: '4px 6px', borderRadius: 6,
    display: 'inline-flex', alignItems: 'center',
    transition: 'var(--da-transition)',
  },
  batchBtn: {
    fontSize: '0.78rem', padding: '4px 10px', borderRadius: 6,
    border: '1px solid var(--da-border)', background: 'var(--da-surface)',
    color: 'var(--da-text)', cursor: 'pointer', fontWeight: 500,
  },
  batchDeleteBtn: {
    background: 'var(--da-danger)', color: '#fff', border: 'none',
  },
  batchBtnDisabled: {
    opacity: 0.5, cursor: 'not-allowed',
  },
  batchCancelBtn: {
    marginLeft: 'auto',
    background: 'transparent', border: 'none', cursor: 'pointer',
    color: 'var(--da-text-muted)', padding: '4px 6px', borderRadius: 6,
    display: 'inline-flex', alignItems: 'center',
  },
  muted: { padding: '8px 12px', fontSize: '0.9rem', color: 'var(--da-text-muted)' },
  error: { padding: '8px 12px', fontSize: '0.9rem', color: 'var(--da-danger)' },
};
