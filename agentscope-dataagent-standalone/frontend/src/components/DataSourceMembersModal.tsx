import React, { useEffect, useRef, useState } from 'react';
import { dataSourceMembers, dataSourceMemberUsers, dataSourceRoleLabel, grantDataSourceMember, type DataSourceMember, type DataSourceMembers, type DataSourceRole, type ExternalDataSource } from '../api/datasources';
import DataSourceDialog from './DataSourceDialog';
import Icon from './Icon';
import ErrorNotice from './ErrorNotice';

export default function DataSourceMembersModal({ source, onClose, onChanged }: { source: ExternalDataSource; onClose: () => void; onChanged: () => void }) {
  const [tab, setTab] = useState<'members' | 'add'>('members');
  const [data, setData] = useState<DataSourceMembers | null>(null);
  const [search, setSearch] = useState(''); const [page, setPage] = useState(0);
  const [role, setRole] = useState<DataSourceRole>('USE');
  const [loading, setLoading] = useState(true); const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null); const [revision, setRevision] = useState(0);
  const alive = useRef(true);
  useEffect(() => { alive.current = true; return () => { alive.current = false; }; }, []);
  useEffect(() => {
    const abort = new AbortController(); setLoading(true); setError(null);
    const timer = window.setTimeout(() => {
      const request = tab === 'members' ? dataSourceMembers(source.id, page, abort.signal) : dataSourceMemberUsers(source.id, search, page, abort.signal);
      void request.then(value => { if (!abort.signal.aborted) setData(value); })
        .catch(e => { if (!abort.signal.aborted) setError(e instanceof Error ? e.message : String(e)); })
        .finally(() => { if (!abort.signal.aborted) setLoading(false); });
    }, tab === 'add' ? 250 : 0);
    return () => { window.clearTimeout(timer); abort.abort(); };
  }, [source.id, tab, search, page, revision]);
  async function change(member: DataSourceMember, next: DataSourceRole | null) {
    if (busy) return;
    if (next === null && !window.confirm(`移除 ${member.displayName || member.username} 的数据源权限？移除后不能继续浏览或新增关联；已关联知识库仍可使用。如需停止供数，请停用数据源。`)) return;
    setBusy(true); setError(null);
    try { await grantDataSourceMember(source.id, member.userId, next); if (!alive.current) return; setRevision(v => v + 1); onChanged(); }
    catch (e) { if (alive.current) setError(e instanceof Error ? e.message : String(e)); }
    finally { if (alive.current) setBusy(false); }
  }
  const pages = Math.max(1, Math.ceil((data?.total ?? 0) / 20));
  return <DataSourceDialog title="成员与权限" description={source.name} onClose={onClose} busy={busy} wide className="ds-members-dialog">
    <div className="ds-dialog-body">
      <div className="ds-permission-guide"><div><strong><Icon name="database" size="sm" />可使用</strong><p>浏览库表，关联到你可管理的知识库，无需知道连接密码。</p></div><div><strong><Icon name="settings" size="sm" />可管理</strong><p>同时可配置连接。成员授权、停用与删除仅创建人和全局管理员可操作。</p></div></div>
      <div className="kw-tabs ds-member-tabs"><button disabled={busy} className={tab === 'members' ? 'active' : ''} onClick={() => { setTab('members'); setPage(0); }}>已授权成员</button><button disabled={busy} className={tab === 'add' ? 'active' : ''} onClick={() => { setTab('add'); setPage(0); }}>添加成员</button></div>
      {tab === 'add' && <div className="ds-member-search"><label className="kw-search"><Icon name="search" size="sm" /><input aria-label="搜索成员姓名或账号" placeholder="搜索中文姓名或账号" value={search} disabled={busy} onChange={e => { setSearch(e.target.value); setPage(0); }} /></label><label className="ds-field">授予权限<select value={role} disabled={busy} onChange={e => setRole(e.target.value as DataSourceRole)}><option value="USE">可使用</option><option value="MANAGE">可管理</option></select></label></div>}
      {error && <ErrorNotice error={error}><button type="button" className="da-btn da-btn-sm" disabled={busy} onClick={() => setRevision(v => v + 1)}>重试</button></ErrorNotice>}
      {tab === 'members' && data?.creator && <div className="ds-member-creator"><Icon name="user" size="sm" /><strong>{data.creator.displayName || data.creator.username}</strong><span>{data.creator.username} · 创建人</span></div>}
      <div className="ds-members-table" aria-busy={loading}><table className="kw-table"><thead><tr><th>用户</th><th>权限</th><th>操作</th></tr></thead><tbody>
        {loading ? <tr><td colSpan={3} className="ds-empty" role="status">正在读取成员…</td></tr> : !data?.items.length ? <tr><td colSpan={3} className="ds-empty">{tab === 'add' ? '没有匹配的用户' : '暂未共享给其他用户，可以添加成员。'}</td></tr> : data.items.map(member => <tr key={member.userId}>
          <td><div className="ds-member-person"><span className="ds-member-avatar" aria-hidden="true">{(member.displayName || member.username).slice(0, 1)}</span><span><strong>{member.displayName || member.username}</strong><small>{member.username}{member.department ? ` · ${member.department}` : ''}</small></span></div></td>
          <td>{member.fixed ? <span className="ds-role-badge">{member.role === 'OWNER' ? '创建人' : '全局管理员'}</span> : tab === 'members' ? <select className="kw-select" aria-label={`${member.displayName || member.username}的权限`} disabled={busy} value={member.role || 'USE'} onChange={e => void change(member, e.target.value as DataSourceRole)}><option value="USE">可使用</option><option value="MANAGE">可管理</option></select> : dataSourceRoleLabel(member.role)}</td>
          <td>{member.fixed ? <span className="da-small">固定权限</span> : <button type="button" className={`da-btn da-btn-sm ${tab === 'add' ? 'da-btn-primary' : 'da-btn-ghost'}`} disabled={busy} onClick={() => void change(member, tab === 'add' ? role : null)}>{tab === 'add' ? member.role ? '更新授权' : '授权' : '移除'}</button>}</td>
        </tr>)}
      </tbody></table></div>
    </div>
    <footer className="ds-dialog-foot ds-members-foot"><span className="da-small">{data ? `共 ${data.total} 位成员` : ''} · 全局管理员始终可管理所有数据源</span><button type="button" className="da-btn" disabled={busy || loading || page === 0} onClick={() => setPage(p => p - 1)} aria-label="上一页成员"><Icon name="back" size="sm" /></button><span className="da-small">{page + 1} / {pages}</span><button type="button" className="da-btn" disabled={busy || loading || page + 1 >= pages} onClick={() => setPage(p => p + 1)} aria-label="下一页成员"><Icon name="chevron" size="sm" /></button><button type="button" className="da-btn" disabled={busy} onClick={onClose}>完成</button></footer>
  </DataSourceDialog>;
}
