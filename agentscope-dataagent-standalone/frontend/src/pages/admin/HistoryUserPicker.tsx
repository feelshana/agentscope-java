import { useEffect, useId, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import { AdminDirectoryUser, historyDirectory, historyUsers } from '../../api/adminHistory';
import Icon from '../../components/Icon';

const text = (u: AdminDirectoryUser | null) => u ? (u.displayName ? `${u.displayName} · ${u.username}` : u.username) : '';
export function HistoryUserButton({ user, onClick }: { user: AdminDirectoryUser; onClick: () => void }) {
  return <button type="button" className="ah-user" onClick={onClick} title={`筛选 ${text(user)} 的会话`}><span className="ah-avatar">{(user.displayName || user.username).slice(0, 1)}</span><span><strong>{user.displayName || user.username}</strong><small>{user.username}</small></span></button>;
}
export default function HistoryUserPicker({ user, select, from, to }: { user: AdminDirectoryUser | null; select: (u: AdminDirectoryUser | null) => void; from: string; to: string }) {
  const [draft, setDraft] = useState(text(user));
  const [term, setTerm] = useState('');
  const [open, setOpen] = useState(false);
  const [options, setOptions] = useState<AdminDirectoryUser[]>([]);
  const [loading, setLoading] = useState(false);
  const [hasMore, setHasMore] = useState(false);
  const [error, setError] = useState('');
  const [active, setActive] = useState(0);
  const [position, setPosition] = useState({ top: 0, left: 0, width: 305, maxHeight: 280 });
  const root = useRef<HTMLDivElement>(null);
  const popup = useRef<HTMLDivElement>(null);
  const input = useRef<HTMLInputElement>(null);
  const composing = useRef(false);
  const id = useId();
  useEffect(() => { setDraft(text(user)); setOpen(false); }, [user]);
  useEffect(() => {
    if (!open) return;
    const controller = new AbortController();
    setLoading(true); setError(''); setOptions([]); setActive(0); setHasMore(false);
    const timer = window.setTimeout(() => (term.trim()
      ? historyDirectory(term.trim(), controller.signal).then(items => ({ items, hasMore: items.length >= 20 }))
      : historyUsers({ userId: '', query: '', from, to, sort: 'activity', direction: 'desc', page: 0, pageSize: 20 }, 0, controller.signal)
        .then(page => ({ items: page.items.map(row => row.owner), hasMore: page.hasMore }))).then(page => {
      if (!controller.signal.aborted) { setOptions(page.items); setHasMore(page.hasMore); setActive(0); }
    }).catch(e => { if (!controller.signal.aborted) setError((e as Error).message); })
      .finally(() => { if (!controller.signal.aborted) setLoading(false); }), 200);
    return () => { window.clearTimeout(timer); controller.abort(); };
  }, [open, term, from, to]);
  useEffect(() => {
    if (!open) return;
    const place = () => {
      const r = root.current!.getBoundingClientRect();
      const width = Math.min(Math.max(305, r.width), window.innerWidth - 24);
      const below = window.innerHeight - r.bottom - 16;
      const height = Math.min(280, Math.max(below, r.top - 16));
      setPosition({ left: Math.max(12, Math.min(r.left, window.innerWidth - width - 12)), top: below >= 160 ? r.bottom + 6 : Math.max(12, r.top - height - 6), width, maxHeight: height });
    };
    place();
    const close = (e: PointerEvent) => { if (!root.current?.contains(e.target as Node) && !popup.current?.contains(e.target as Node)) { setOpen(false); setDraft(text(user)); } };
    document.addEventListener('pointerdown', close); window.addEventListener('resize', place); window.addEventListener('scroll', place, true);
    return () => { document.removeEventListener('pointerdown', close); window.removeEventListener('resize', place); window.removeEventListener('scroll', place, true); };
  }, [open, user]);
  useEffect(() => { popup.current?.querySelector(`#${CSS.escape(id + '-' + active)}`)?.scrollIntoView({ block: 'nearest' }); }, [active, id]);
  function pick(u: AdminDirectoryUser | null) { select(u); setDraft(text(u)); setOpen(false); input.current?.focus(); }
  function show() { setTerm(''); setOpen(true); setActive(0); }
  return <div className="ah-user-picker" ref={root}>
    <input ref={input} role="combobox" aria-label="筛选用户" aria-autocomplete="list" aria-expanded={open} aria-controls={id} aria-activedescendant={open ? `${id}-${active}` : undefined} value={draft} placeholder="全部用户" maxLength={191}
      onFocus={() => { if (!open) show(); }}
      onClick={() => { if (!open) show(); }}
      onChange={e => { setDraft(e.target.value); setOpen(true); if (!composing.current) setTerm(e.target.value); }}
      onCompositionStart={() => { composing.current = true; }} onCompositionEnd={e => { composing.current = false; setTerm(e.currentTarget.value); }}
      onKeyDown={e => { if (e.nativeEvent.isComposing) return; if (e.key === 'Escape' || e.key === 'Tab') { setOpen(false); setDraft(text(user)); return; } if (e.key === 'ArrowDown' || e.key === 'ArrowUp') { e.preventDefault(); if (!open) show(); else setActive(i => Math.max(0, Math.min(options.length, i + (e.key === 'ArrowDown' ? 1 : -1)))); } if (e.key === 'Enter' && open && !loading) { e.preventDefault(); pick(active === 0 ? null : options[active - 1] ?? null); } }} />
    {user && <button type="button" className="ah-icon" aria-label="清除用户筛选" onClick={() => pick(null)}><Icon name="close" size="sm" /></button>}
    <button type="button" className="ah-icon ah-drop" aria-label="展开用户列表" onClick={() => { if (open) { setOpen(false); setDraft(text(user)); } else show(); }}><Icon name="chevron" size="sm" /></button>
    {open && createPortal(<div ref={popup} role="listbox" id={id} className="ah-user-options" aria-label="用户搜索结果" style={position}>
      <button id={`${id}-0`} className={active === 0 ? 'active' : ''} role="option" aria-selected={!user} onClick={() => pick(null)}>全部用户</button>
      {loading ? <p role="status">{term.trim() ? '搜索用户中…' : '加载本时段用户…'}</p> : error ? <p role="alert">{error}</p> : options.length ? options.map((u, i) => <button id={`${id}-${i + 1}`} key={u.userId} role="option" aria-selected={user?.userId === u.userId} className={active === i + 1 ? 'active' : ''} onMouseEnter={() => setActive(i + 1)} onClick={() => pick(u)}><span className="ah-avatar">{(u.displayName || u.username).slice(0, 1)}</span><span><strong>{u.displayName || u.username}</strong><small>{u.username}{u.department ? ' · ' + u.department : ''}</small></span></button>) : <p>{term.trim() ? '没有匹配用户，请尝试姓名或账号' : '该时段暂无会话用户，可输入姓名或账号搜索全部用户'}</p>}
      {!loading && !error && <small className="ah-picker-note">{term.trim() ? (hasMore ? '先显示 20 位用户，请输入更完整的姓名或账号缩小范围' : '按姓名或账号搜索全部用户') : (hasMore ? '先显示本时段的 20 位用户；输入姓名或账号可搜索全部用户' : '展示所选时间内有会话的用户；输入姓名或账号可搜索全部用户')}</small>}
    </div>, document.body)}
  </div>;
}
