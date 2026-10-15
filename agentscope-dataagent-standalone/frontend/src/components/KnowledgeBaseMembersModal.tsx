import React, { useEffect, useRef, useState } from 'react';
import { grantMember, KnowledgeBaseMember, KnowledgeBaseRole, listMembers, MemberPage, revokeMember, searchMemberUsers } from '../api/knowledgeBaseMembers';
import Icon from './Icon';

const roleLabel = (role: KnowledgeBaseRole | null) => role === 'MANAGE' ? '管理' : role === 'QUERY' ? '使用' : '未授权';

export default function KnowledgeBaseMembersModal({ groupId, name, onClose, onChanged }: {
  groupId: string; name: string; onClose: () => void; onChanged?: () => void;
}) {
  const [members, setMembers] = useState<MemberPage | null>(null);
  const [users, setUsers] = useState<MemberPage | null>(null);
  const [tab, setTab] = useState<'members' | 'add'>('members');
  const [search, setSearch] = useState('');
  const [page, setPage] = useState(0);
  const [revision, setRevision] = useState(0);
  const [role, setRole] = useState<KnowledgeBaseRole>('QUERY');
  const [busy, setBusy] = useState(false);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const shell = useRef<HTMLDivElement>(null);
  const closeRef = useRef(onClose);
  const busyRef = useRef(busy);
  closeRef.current = onClose; busyRef.current = busy;
  useEffect(() => {
    const previous = document.activeElement as HTMLElement | null;
    shell.current?.focus();
    const key = (event: KeyboardEvent) => {
      if (event.key === 'Escape' && !busyRef.current) closeRef.current();
      if (event.key === 'Tab') {
        const elements = Array.from(shell.current?.querySelectorAll<HTMLElement>('button:not(:disabled),input:not(:disabled),select:not(:disabled),[tabindex="0"]') ?? []);
        if (!elements.length) return;
        const first = elements[0], last = elements[elements.length - 1];
        if (event.shiftKey && (document.activeElement === first || document.activeElement === shell.current)) { event.preventDefault(); last.focus(); }
        else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first.focus(); }
      }
    };
    document.addEventListener('keydown', key);
    return () => { document.removeEventListener('keydown', key); previous?.focus(); };
  }, []);

  useEffect(() => {
    const controller = new AbortController();
    setLoading(true); setError(null);
    const timer = window.setTimeout(() => {
      const action = tab === 'members' ? listMembers(groupId, page, controller.signal)
        : searchMemberUsers(groupId, search, page, controller.signal);
      action.then(result => {
        if (controller.signal.aborted) return;
        if (tab === 'members') setMembers(result); else setUsers(result);
      }).catch(e => { if (!controller.signal.aborted) setError(e instanceof Error ? e.message : String(e)); })
        .finally(() => { if (!controller.signal.aborted) setLoading(false); });
    }, tab === 'add' ? 250 : 0);
    return () => { window.clearTimeout(timer); controller.abort(); };
  }, [groupId, tab, search, page, revision]);

  async function change(member: KnowledgeBaseMember, next: KnowledgeBaseRole | null) {
    if (next === null && !window.confirm(`撤销 ${member.username} 的权限？已生成的本人历史回答和附件仍保留。`)) return;
    setBusy(true); setError(null);
    try {
      if (next === null) await revokeMember(groupId, member.userId); else await grantMember(groupId, member.userId, next);
      setRevision(r => r + 1); onChanged?.();
    } catch (e) { setError(e instanceof Error ? e.message : String(e)); }
    finally { setBusy(false); }
  }

  const data = tab === 'members' ? members : users;
  const pages = Math.max(1, Math.ceil((data?.total ?? 0) / 20));
  return <div className="da-modal-overlay" onClick={() => { if (!busy) onClose(); }}>
    <div ref={shell} className="da-modal-shell" role="dialog" aria-modal="true" aria-labelledby="kb-members-title" tabIndex={-1}
      style={{ width: 'min(760px, 94vw)' }} onClick={e => e.stopPropagation()}>
      <div className="da-modal-head" style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
        <div><div id="kb-members-title" className="da-modal-title">成员与权限</div><div className="da-small" style={{ marginTop: 6 }}>{name}</div></div>
        <button className="da-btn da-btn-ghost" aria-label="关闭成员管理" disabled={busy} onClick={onClose}><Icon name="close" size="sm" /></button>
      </div>
      <div className="da-modal-body">
        <p className="da-small" style={{ marginTop: 0, lineHeight: 1.7 }}>使用：选库问答、下载本人生成的结果。管理：同时支持数据维护、建模、发布、成员授权和删除。创建者与全局管理员固定拥有管理权限。</p>
        <div className="kw-tabs" style={{ marginBottom: 20 }}>
          <button className={tab === 'members' ? 'active' : ''} disabled={busy} onClick={() => { setTab('members'); setPage(0); }}>已授权成员</button>
          <button className={tab === 'add' ? 'active' : ''} disabled={busy} onClick={() => { setTab('add'); setPage(0); }}>添加成员</button>
        </div>
        {error && <div className="kw-alert" role="alert">{error}<button className="da-btn da-btn-sm" onClick={() => setRevision(r => r + 1)} disabled={busy}>刷新</button></div>}
        {tab === 'members' && members?.creator && <div className="kw-member-owner"><Icon name="user" size="sm" />{members.creator.username}<span>创建者 · 管理</span></div>}
        {tab === 'add' && <div className="kw-toolbar">
          <label className="kw-search"><Icon name="search" size="sm" /><input autoFocus aria-label="搜索用户名" placeholder="搜索用户名" value={search} onChange={e => { setSearch(e.target.value); setPage(0); }} /></label>
          <select className="kw-select" aria-label="新成员权限" value={role} onChange={e => setRole(e.target.value as KnowledgeBaseRole)}><option value="QUERY">使用权限</option><option value="MANAGE">管理权限</option></select>
        </div>}
        <div className="kw-tablewrap" style={{ minHeight: 200, maxHeight: '42vh', overflow: 'auto' }} aria-busy={loading}>
          <table className="kw-table"><thead><tr><th>用户名</th><th>权限</th><th style={{ width: 180 }}>操作</th></tr></thead><tbody>
            {loading ? <tr><td colSpan={3}>加载中…</td></tr> : data?.items.map(member => <tr key={member.userId}>
              <td>{member.username}</td><td>{member.owner ? '创建者 · 管理' : member.admin ? '全局管理员 · 管理' : roleLabel(member.role)}</td>
              <td>{member.owner || member.admin ? <span className="da-small">固定权限</span> : tab === 'add'
                ? <button className="da-btn da-btn-sm" disabled={busy || member.role === role} onClick={() => void change(member, role)}>{member.role === role ? '已授权' : `授予${roleLabel(role)}`}</button>
                : <div style={{ display: 'flex', gap: 8 }}><select className="kw-select" aria-label={`${member.username}的权限`} disabled={busy} value={member.role ?? 'QUERY'} onChange={e => void change(member, e.target.value as KnowledgeBaseRole)}><option value="QUERY">使用</option><option value="MANAGE">管理</option></select><button className="da-btn da-btn-sm" disabled={busy} onClick={() => void change(member, null)}>撤销</button></div>}
              </td>
            </tr>)}
            {!loading && !data?.items.length && <tr><td colSpan={3} style={{ textAlign: 'center', padding: 40, color: 'var(--kw-muted)' }}>{tab === 'members' ? '尚未授予其他用户权限' : '没有匹配的用户'}</td></tr>}
          </tbody></table>
        </div>
        <div className="kw-pagination"><span>共 {data?.total ?? 0} 位{tab === 'members' ? '授权成员' : '用户'}</span><span className="kw-spacer" /><button className="da-btn da-btn-sm" disabled={loading || busy || page === 0} onClick={() => setPage(p => p - 1)}>上一页</button><span>{page + 1} / {pages}</span><button className="da-btn da-btn-sm" disabled={loading || busy || page + 1 >= pages} onClick={() => setPage(p => p + 1)}>下一页</button></div>
      </div>
      <div className="da-modal-foot"><button className="da-btn" disabled={busy} onClick={onClose}>完成</button></div>
    </div>
  </div>;
}
