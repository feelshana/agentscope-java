import React, { useEffect, useMemo, useRef, useState } from 'react';
import { useLocation, useNavigate, useSearchParams } from 'react-router-dom';
import { ACTIVE_AGENT_ID } from '../api/activeAgent';
import { apiFetch, getToken, isAdmin } from '../api/auth';
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
  // spec092: modeling is reviewed and published per knowledge base; give it its own entry.
  { label: '语义建模', path: '/configure/modeling', icon: 'model' },
  { label: '业务术语', path: '/configure/semantic', icon: 'settings' },
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
  /** Icon-only rail; the modeling workbench starts collapsed to give the work area the width. */
  collapsed?: boolean;
  onToggle?: () => void;
}

export default function SessionsSidebar({ refreshKey, collapsed = false, onToggle }: SessionsSidebarProps) {
  const navigate = useNavigate();
  const location = useLocation();
  const navigationItems = location.pathname.startsWith('/knowledge-bases/')
    ? NAV_ITEMS.filter(item => item.path !== '/configure/modeling') : NAV_ITEMS;
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

  if (collapsed) {
    const rail = (label: string, icon: IconName, active: boolean, onClick: () => void) =>
      <button key={label} title={label} aria-label={label} onClick={onClick}
        className={active ? 'da-navitem da-navitem-active' : 'da-navitem'} style={S.railItem}><Icon name={icon} /></button>;
    return (
      <div style={S.rail}>
        <div style={{ ...S.brand, padding: 0, justifyContent: 'center' }}><span className="da-logo">DA</span></div>
        <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 4, padding: '8px 0' }}>
          {rail('新建对话', 'plus', location.pathname === '/chat' && !activeKey, handleNewChat)}
          {navigationItems.map(item => rail(item.label, item.icon, location.pathname.startsWith(item.path), () => navigate(item.path)))}
          {rail('更多', 'moreHorizontal', ['/more', '/workspace', '/admin/models', '/admin/public-knowledge-bases'].includes(location.pathname), () => navigate('/more'))}
        </div>
        <span style={{ flex: 1 }} />
        {onToggle && <button type="button" title="展开侧边栏" aria-label="展开侧边栏" aria-expanded={false} className="rail-collapse-toggle" style={{ margin: '0 auto 8px' }} onClick={onToggle}><Icon name="chevron" size="sm" /></button>}
      </div>
    );
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
        {navigationItems.map(item => {
          const active = location.pathname.startsWith(item.path)
            || (item.path === '/configure/datasets' && location.pathname === '/configure/datasources');
          return <button key={item.path} onClick={() => navigate(item.path)} aria-current={active ? 'page' : undefined}
            className={`rail-nav-item${active ? ' is-active' : ''}`}><Icon name={item.icon} />{item.label}</button>;
        })}
        <button className={`rail-nav-item${['/more', '/workspace', '/admin/models', '/admin/public-knowledge-bases'].includes(location.pathname) ? ' is-active' : ''}`}
          aria-current={['/more', '/workspace', '/admin/models', '/admin/public-knowledge-bases'].includes(location.pathname) ? 'page' : undefined} onClick={() => navigate('/more')}><Icon name="moreHorizontal" />更多</button>
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
        {onToggle && <button type="button" title="收起侧边栏" aria-label="收起侧边栏" aria-expanded={true} className="rail-collapse-toggle is-expanded" onClick={onToggle}><Icon name="chevron" size="sm" /></button>}
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
  const [profile, setProfile] = useState<{ displayName?: string; company?: string; department?: string } | null>(null);
  useEffect(() => { const controller = new AbortController(); void apiFetch('/api/auth/profile', { signal: controller.signal, headers: { Authorization: `Bearer ${getToken()}` } })
    .then(r => r.ok ? r.json() : null).then(p => { if (!controller.signal.aborted) setProfile(p); }).catch(() => {});
    return () => controller.abort(); }, [username]);
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
      <span className="rail-account-copy"><span>{profile?.displayName || username || '用户'}</span><small>{profile?.company || (isAdmin() ? '全局管理员' : '用户')}</small></span>
    </button>
    {open && <div className="rail-account-popover" role="status">{profile?.displayName || username} · {username}<br />{profile?.company} {profile?.department}<br />{isAdmin() ? '全局管理员' : '普通用户'}</div>}
  </div>;
}

const S: Record<string, React.CSSProperties> = {
  root: {
    width: 280, flexShrink: 0,
    background: 'var(--da-sidebar-bg)', borderRight: '1px solid var(--da-border)',
    display: 'flex', flexDirection: 'column', minHeight: 0,
  },
  rail: {
    width: 60, flexShrink: 0,
    background: 'var(--da-sidebar-bg)', borderRight: '1px solid var(--da-border)',
    display: 'flex', flexDirection: 'column', minHeight: 0,
  },
  railItem: { width: 40, height: 40, padding: 0, justifyContent: 'center', fontSize: 16 },
  brand: {
    display: 'flex', alignItems: 'center', gap: 12,
    height: 'var(--da-shell-header-height)', boxSizing: 'border-box',
    padding: '0 16px', flexShrink: 0,
  },
  brandName: { fontSize: 16, fontWeight: 700, color: 'var(--da-text)', letterSpacing: '-0.01em' },
  brandTag: { fontSize: 12, color: 'var(--da-text-muted)', marginTop: 1 },
  moreMenu: {
    position: 'fixed', width: 180, zIndex: 1100,
    background: 'var(--da-surface)', border: '1px solid var(--da-border)',
    borderRadius: 'var(--da-radius-lg)', boxShadow: 'var(--da-shadow-pop)',
    padding: 6, display: 'flex', flexDirection: 'column', gap: 2,
  },
  scroll: { flex: 1, overflowY: 'auto', padding: '12px 10px 18px' },
  listHeader: {
    display: 'flex', alignItems: 'center', gap: 6,
    padding: '4px 6px 8px',
  },
  listHeaderTitle: {
    flex: 1, fontSize: '0.78rem', fontWeight: 600, color: 'var(--da-text-muted)',
  },
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
  group: { marginBottom: 18 },
  groupLabel: {
    fontSize: '0.72rem', fontWeight: 500,
    color: 'var(--da-text-muted)', textTransform: 'uppercase', padding: '8px 12px 6px',
  },
  rowMain: { flex: 1, minWidth: 0 },
  rowTitle: {
    fontSize: '0.88rem', fontWeight: 400, color: 'var(--da-text-2)',
    overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap',
  },
  rowMeta: {
    display: 'flex', alignItems: 'center', gap: 4,
    fontSize: '0.75rem', color: 'var(--da-text-muted)', flexShrink: 0,
  },
  footer: {
    padding: '12px 14px', flexShrink: 0, display: 'flex', alignItems: 'center', gap: 8,
  },
};
