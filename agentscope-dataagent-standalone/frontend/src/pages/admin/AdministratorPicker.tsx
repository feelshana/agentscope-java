import { useEffect, useRef, useState } from 'react';
import { AdminDirectoryUser, searchAdminUsers } from '../../api/adminHistory';
import Icon from '../../components/Icon';

export default function AdministratorPicker({ admins, close, grant }: {
  admins: AdminDirectoryUser[]; close: () => void; grant: (user: AdminDirectoryUser) => Promise<void>;
}) {
  const dialog = useRef<HTMLDialogElement>(null);
  const [term, setTerm] = useState('');
  const [composing, setComposing] = useState(false);
  const [items, setItems] = useState<AdminDirectoryUser[]>([]);
  const [selected, setSelected] = useState<AdminDirectoryUser | null>(null);
  const [loading, setLoading] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  useEffect(() => { const element = dialog.current; element?.showModal(); return () => element?.close(); }, []);
  useEffect(() => {
    if (composing || !term.trim()) { setLoading(false); setItems([]); return; }
    const controller = new AbortController();
    setLoading(true); setError(''); setItems([]);
    const timer = window.setTimeout(() => {
      searchAdminUsers(term.trim(), controller.signal).then(results => {
        if (!controller.signal.aborted) setItems(results);
      }).catch(e => { if (!controller.signal.aborted) setError((e as Error).message); })
        .finally(() => { if (!controller.signal.aborted) setLoading(false); });
    }, 250);
    return () => { window.clearTimeout(timer); controller.abort(); };
  }, [term, composing]);
  const candidates = items.filter(user => !admins.some(admin => admin.userId === user.userId));
  async function submit() {
    if (!selected || busy) return;
    setBusy(true); setError('');
    try { await grant(selected); close(); }
    catch (e) { setError((e as Error).message); }
    finally { setBusy(false); }
  }
  return <dialog ref={dialog} className="am-dialog" aria-labelledby="am-grant-title"
    onCancel={e => { e.preventDefault(); if (!busy) close(); }}
    onClick={e => { const r = e.currentTarget.getBoundingClientRect(); if (!busy && e.target === e.currentTarget && (e.clientX < r.left || e.clientX > r.right || e.clientY < r.top || e.clientY > r.bottom)) close(); }}>
    <header className="am-dialog-head"><div><h2 id="am-grant-title">添加全局管理员</h2><p>选择用户并确认添加。对方重新登录后可使用管理功能。</p></div><button className="am-close" aria-label="关闭添加管理员" disabled={busy} onClick={close}><Icon name="close" /></button></header>
    <label className="am-search am-picker-search"><Icon name="search" /><input autoFocus aria-label="搜索待添加的管理员" type="search" maxLength={191} placeholder="输入姓名或账号查找用户" value={term} disabled={busy}
      onCompositionStart={() => setComposing(true)} onCompositionEnd={() => setComposing(false)}
      onChange={e => { setTerm(e.target.value); setSelected(null); setError(''); }} /></label>
    <div className="am-picker-results" aria-label="搜索结果">
      {loading ? <p className="am-feedback" role="status">正在搜索用户…</p> : candidates.length ? <fieldset disabled={busy}><legend className="am-sr-only">选择一位管理员</legend>{candidates.map(user => <label key={user.userId} className={`am-candidate${selected?.userId === user.userId ? ' selected' : ''}`}>
        <span className="am-avatar">{(user.displayName || user.username).slice(0, 1)}</span><span className="am-identity"><strong>{user.displayName || user.username}</strong><small>{user.username}{user.department ? ` · ${user.department}` : ''}</small></span>
        <input type="radio" name="administrator" value={user.userId} checked={selected?.userId === user.userId} onChange={() => setSelected(user)} aria-label={`选择 ${user.displayName || user.username}（${user.username}）`} /></label>)}</fieldset>
        : <p className="am-feedback">{!term.trim() ? '输入姓名或账号，搜索结果会自动显示。' : items.length ? '匹配用户已拥有全局管理员权限。' : error ? '搜索未完成，请重试。' : '没有找到匹配用户，请尝试完整账号。'}</p>}
    </div>
    <div className="am-grant-note"><Icon name="shield" /><span>{selected ? `将授予 ${selected.displayName || selected.username}（${selected.username}）全局管理权限。` : '添加前请确认对方职责范围。'} 管理员可管理全部知识库、维护权限并查看用户对话历史。</span></div>
    {error && <p className="am-error" role="alert">{error}</p>}
    <footer className="am-dialog-foot"><button className="am-btn" disabled={busy} onClick={close}>取消</button><button className="am-btn primary" disabled={!selected || busy} onClick={() => void submit()}>{busy ? '正在添加…' : '确认添加管理员'}</button></footer>
  </dialog>;
}
