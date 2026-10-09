import React, { useEffect, useMemo, useRef, useState } from 'react';
import { useLocation, useNavigate, useSearchParams } from 'react-router-dom';
import { ACTIVE_AGENT_ID } from '../api/activeAgent';
import { getToken } from '../api/auth';
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
  { label: '业务术语', path: '/configure/semantic', icon: 'settings' },
  { label: '例行任务', path: '/configure/scheduled-tasks', icon: 'clock' },
];

/** Overflow menu items (TC-style 更多); currently only Workspace. */
const MORE_ITEMS: UtilityItem[] = [
  { label: '工作区', path: '/workspace', icon: 'folder' },
];

function decodeJwt(token: string): Record<string, unknown> {
  try { return JSON.parse(atob(token.split('.')[1])); } catch { return {}; }
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
  const [moreOpen, setMoreOpen] = useState(false);
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
    <div style={S.root}>
      <div style={S.brand}>
        <span className="da-logo">DA</span>
        <span style={{ display: 'flex', flexDirection: 'column', minWidth: 0 }}>
          <span style={S.brandName}>红海DataAgent</span>
          <span style={S.brandTag}>企业级数据智能体</span>
        </span>
      </div>

      <div
        style={{
          padding: '8px 10px',
          borderBottom: '1px solid var(--da-border)',
          display: 'flex',
          flexDirection: 'column',
          gap: 2,
          flexShrink: 0,
          position: 'relative',
        }}
      >
        <button
          onClick={handleNewChat}
          className={
            location.pathname === '/chat' && !activeKey
              ? 'da-navitem da-navitem-active'
              : 'da-navitem'
          }
        >
          <Icon name="plus" /> 新建对话
        </button>
        {NAV_ITEMS.map(item => {
          const active = location.pathname.startsWith(item.path);
          return (
            <button
              key={item.path}
              onClick={() => navigate(item.path)}
              className={active ? 'da-navitem da-navitem-active' : 'da-navitem'}
            >
              <Icon name={item.icon} />
              {item.label}
            </button>
          );
        })}
        <button
          onClick={() => setMoreOpen(o => !o)}
          className={moreOpen ? 'da-navitem da-navitem-active' : 'da-navitem'}
        >
          <Icon name="list" /> 更多
        </button>
        {moreOpen && (
          <div style={S.moreMenu}>
            {MORE_ITEMS.map(m => (
              <button
                key={m.path}
                className="da-navitem"
                onClick={() => {
                  setMoreOpen(false);
                  navigate(m.path);
                }}
              >
                <Icon name={m.icon} /> {m.label}
              </button>
            ))}
          </div>
        )}
      </div>

      <div style={S.scroll} onScroll={e => {
        const el = e.currentTarget;
        if (!pageError && el.scrollHeight - el.scrollTop - el.clientHeight < 120) void loadMore();
      }}>
        {entries.length > 0 && (
          <div style={S.listHeader}>
            <span style={S.listHeaderTitle}>历史会话</span>
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
            <div key={b} style={S.group}>
              <div style={S.groupLabel}>{BUCKET_LABEL[b]}</div>
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

      <div style={S.footer}>
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
  return (
    <div
      onClick={batchMode ? onToggleSelect : onOpen}
      className={
        'da-row' +
        (active ? ' da-row-active' : '') +
        (entry.unread && !active ? ' da-row-unread' : '') +
        (batchMode && selected ? ' da-row-selected' : '')
      }
      title={`${entry.title ?? entry.label ?? '新对话'} · ${relTime(entry.lastActivityMs)}`}
    >
      {batchMode && (
        <span style={S.checkbox}>
          <input
            type="checkbox"
            checked={selected}
            onChange={onToggleSelect}
            onClick={e => e.stopPropagation()}
          />
        </span>
      )}
      <div style={S.rowMain}>
        <div style={S.rowTitle}>{entry.title ?? entry.label ?? '新对话'}</div>
      </div>
      <div style={S.rowMeta}>
        {!batchMode && (
          <button
            onClick={onDelete}
            title="删除对话"
            className="da-btn da-btn-ghost da-btn-sm da-session-delete"
          >×</button>
        )}
      </div>
    </div>
  );
}

function UserMenu({ username }: { username: string }) {
  const location = useLocation();
  const [open, setOpen] = useState(false);
  const ref = useRef<HTMLDivElement>(null);

  useEffect(() => {
    function handleClick(e: MouseEvent) {
      if (ref.current && !ref.current.contains(e.target as Node)) setOpen(false);
    }
    if (open) document.addEventListener('mousedown', handleClick);
    return () => document.removeEventListener('mousedown', handleClick);
  }, [open]);

  useEffect(() => { setOpen(false); }, [location.pathname]);

  const initial = username.charAt(0).toUpperCase() || '用';

  return (
    <div ref={ref} style={{ position: 'relative' as const }}>
      <button
        onClick={() => setOpen(o => !o)}
        type="button"
        aria-label="查看当前用户"
        aria-expanded={open}
        style={{
          display: 'inline-flex', alignItems: 'center', gap: 9,
          maxWidth: '100%', minHeight: 36, padding: '0 6px 0 0',
          background: 'var(--da-sidebar-bg)', border: 'none', borderRadius: 8,
          cursor: 'pointer', color: 'var(--da-text)', fontSize: '0.88rem', fontWeight: 500,
        }}
      >
        <div style={{
          width: 28, height: 28, borderRadius: '50%',
          background: 'var(--da-primary)',
          color: '#ffffff', display: 'flex', alignItems: 'center', justifyContent: 'center',
          fontSize: '0.78rem', fontWeight: 700, userSelect: 'none' as const,
        }}>{initial}</div>
        <span style={{ overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
          {username || '用户'}
        </span>
      </button>

      {open && (
        <div style={{
          position: 'absolute' as const, bottom: 'calc(100% + 6px)', left: 0,
          minWidth: 120, maxWidth: 240,
          background: 'var(--da-surface)',
          border: '1px solid var(--da-border)', borderRadius: 10,
          boxShadow: 'var(--da-shadow-pop)', padding: '10px 14px',
          overflow: 'hidden', zIndex: 100,
        }}>
          <div style={{ fontSize: 13, fontWeight: 600, color: 'var(--da-text)', whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>
            {username || '用户'}
          </div>
        </div>
      )}
    </div>
  );
}

const S: Record<string, React.CSSProperties> = {
  root: {
    width: 280, flexShrink: 0,
    background: 'var(--da-sidebar-bg)', borderRight: '1px solid var(--da-border)',
    display: 'flex', flexDirection: 'column', minHeight: 0,
  },
  brand: {
    display: 'flex', alignItems: 'center', gap: 12,
    height: 'var(--da-shell-header-height)', boxSizing: 'border-box',
    padding: '0 16px', flexShrink: 0,
  },
  brandName: { fontSize: 16, fontWeight: 700, color: 'var(--da-text)', letterSpacing: '-0.01em' },
  brandTag: { fontSize: 12, color: 'var(--da-text-muted)', marginTop: 1 },
  moreMenu: {
    position: 'absolute', left: 10, right: 10, zIndex: 30,
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
  checkbox: {
    display: 'inline-flex', alignItems: 'center', flexShrink: 0, marginRight: 8,
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
    padding: '12px 14px', flexShrink: 0,
  },
};
