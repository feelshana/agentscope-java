import { useEffect, useRef, useState } from 'react';
import { grantMembers, memberFilters, grantMember, type KnowledgeBaseMember, type KnowledgeBaseRole, listMembers, type MemberPage, revokeMember, searchMemberUsers } from '../api/knowledgeBaseMembers';
import { isAdmin } from '../api/auth';
import { knowledgeRequest } from '../api/knowledgeWorkspace';
import { WorkspaceMenu } from './KnowledgeWorkspace';
import Icon from './Icon';

const roleLabel = (role: KnowledgeBaseRole | null) => role === 'MANAGE' ? '管理' : role === 'QUERY' ? '使用' : '无单独授权';

export default function KnowledgeBaseMembersModal({ groupId, name, onClose, onChanged, inline = false }: {
  groupId: string; name: string; onClose?: () => void; onChanged?: () => void; inline?: boolean;
}) {
  const [members, setMembers] = useState<MemberPage | null>(null);
  const [users, setUsers] = useState<MemberPage | null>(null);
  const [tab, setTab] = useState<'members' | 'add'>('members');
  const [search, setSearch] = useState('');
  const [company, setCompany] = useState('');
  const [department, setDepartment] = useState('');
  const [filters, setFilters] = useState<{ companies: string[]; departments: string[] }>({ companies: [], departments: [] });
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [page, setPage] = useState(0);
  const [revision, setRevision] = useState(0);
  const [role, setRole] = useState<KnowledgeBaseRole>('QUERY');
  const [roleEditor, setRoleEditor] = useState<KnowledgeBaseMember>();
  const [allGrants, setAllGrants] = useState<KnowledgeBaseMember[]>();
  const [publicQuery, setPublicQuery] = useState<boolean>();
  const [busy, setBusy] = useState(false);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const shell = useRef<HTMLDivElement>(null);
  const roleDialog = useRef<HTMLDialogElement>(null);
  const closeRef = useRef(onClose);
  const busyRef = useRef(busy);
  closeRef.current = onClose;
  busyRef.current = busy;

  useEffect(() => {
    if (tab !== 'add') return;
    const abort = new AbortController();
    void memberFilters(groupId, company, abort.signal).then(value => { if (!abort.signal.aborted) setFilters(value); }).catch(() => {});
    return () => abort.abort();
  }, [tab, groupId, company]);
  useEffect(() => setSelected(new Set()), [search, company, department]);
  useEffect(() => {
    if (inline) return;
    const previous = document.activeElement as HTMLElement | null;
    shell.current?.focus();
    const key = (event: KeyboardEvent) => {
      if (event.key === 'Escape' && !busyRef.current) closeRef.current?.();
      if (event.key !== 'Tab') return;
      const elements = Array.from(shell.current?.querySelectorAll<HTMLElement>('button:not(:disabled),input:not(:disabled),select:not(:disabled),[tabindex="0"]') ?? []);
      if (!elements.length) return;
      const first = elements[0], last = elements[elements.length - 1];
      if (event.shiftKey && (document.activeElement === first || document.activeElement === shell.current)) { event.preventDefault(); last.focus(); }
      else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first.focus(); }
    };
    document.addEventListener('keydown', key);
    return () => { document.removeEventListener('keydown', key); previous?.focus(); };
  }, [inline]);
  useEffect(() => {
    const controller = new AbortController();
    setLoading(true);
    setError(null);
    const timer = window.setTimeout(() => {
      const action = tab === 'members' ? listMembers(groupId, page, controller.signal)
        : searchMemberUsers(groupId, search, page, controller.signal, company, department);
      void action.then(result => {
        if (controller.signal.aborted) return;
        if (tab === 'members') setMembers(result); else setUsers(result);
      }).catch(e => { if (!controller.signal.aborted) setError(e instanceof Error ? e.message : String(e)); })
        .finally(() => { if (!controller.signal.aborted) setLoading(false); });
    }, tab === 'add' ? 250 : 0);
    return () => { window.clearTimeout(timer); controller.abort(); };
  }, [groupId, tab, search, page, revision, company, department]);
  useEffect(() => {
    if (roleEditor && !roleDialog.current?.open) roleDialog.current?.showModal();
  }, [roleEditor]);
  const memberTotal = members?.total ?? 0;
  useEffect(() => {
    if (!inline || memberTotal <= 20) return;
    const abort = new AbortController();
    const readGrants = async () => {
      const first = await listMembers(groupId, 0, abort.signal, 100);
      const rest = await Promise.all(Array.from({ length: Math.max(0, Math.ceil(first.total / first.size) - 1) },
        (_, index) => listMembers(groupId, index + 1, abort.signal, first.size)));
      if (!abort.signal.aborted) setAllGrants([first, ...rest].flatMap(result => result.items));
    };
    void readGrants().catch(() => {});
    return () => abort.abort();
  }, [inline, groupId, memberTotal, revision]);
  useEffect(() => {
    if (!inline || !isAdmin()) return;
    const abort = new AbortController();
    void knowledgeRequest<{ items: { id: string; publicQuery: boolean }[] }>(
      '/api/admin/public-knowledge-bases?' + new URLSearchParams({ search: name }), { signal: abort.signal })
      .then(result => { if (!abort.signal.aborted) setPublicQuery(result.items.find(item => item.id === groupId)?.publicQuery); })
      .catch(() => {});
    return () => abort.abort();
  }, [inline, groupId, name]);

  function reloadMembers() {
    setAllGrants(undefined);
    setRevision(value => value + 1);
    onChanged?.();
  }
  async function change(member: KnowledgeBaseMember, next: KnowledgeBaseRole | null) {
    if (next === null && !window.confirm(`移除 ${member.username} 的单独授权？本人已生成的历史回答和附件会保留。`)) return;
    setBusy(true); setError(null);
    try {
      if (next === null) await revokeMember(groupId, member.userId);
      else await grantMember(groupId, member.userId, next);
      reloadMembers();
    } catch (e) { setError(e instanceof Error ? e.message : String(e)); }
    finally { setBusy(false); }
  }
  async function selectFiltered() {
    setBusy(true); setError(null);
    try {
      const ids = new Set<string>();
      for (let p = 0; ; p++) {
        const result = await searchMemberUsers(groupId, search, p, undefined, company, department, 100);
        if (result.total > 2000) throw new Error('筛选结果超过2000人，请缩小公司或部门范围。');
        result.items.filter(u => !u.owner && !u.admin).forEach(u => ids.add(u.userId));
        if ((p + 1) * 100 >= result.total) break;
      }
      setSelected(ids);
    } catch (e) { setError(e instanceof Error ? e.message : String(e)); } finally { setBusy(false); }
  }
  async function batch() {
    if (!selected.size || !window.confirm(`将为选中的 ${selected.size} 人授予${roleLabel(role)}权限。此授权不会自动跟随以后人员调岗变化。`)) return;
    setBusy(true); setError(null);
    try { await grantMembers(groupId, [...selected], role); setSelected(new Set()); reloadMembers(); }
    catch (e) { setError(e instanceof Error ? e.message : String(e)); } finally { setBusy(false); }
  }

  const data = tab === 'members' ? members : users;
  const pages = Math.max(1, Math.ceil((data?.total ?? 0) / 20));
  const creator = members?.creator;
  const total = memberTotal + Number(!!creator);
  const displayRows = inline && tab === 'members' && page === 0 && creator && !members?.items.some(item => item.userId === creator.userId)
    ? [creator, ...(members?.items ?? [])] : data?.items ?? [];
  const selectTab = (next: 'members' | 'add') => { setTab(next); setPage(0); };
  const grants = members && memberTotal <= 20 ? members.items : allGrants;
  const queryCount = grants?.filter(member => member.role === 'QUERY' && !member.admin).length;
  const manageCount = grants ? grants.filter(member => member.role === 'MANAGE' || member.admin).length + Number(!!creator) : undefined;
  const body = <div className="da-modal-body" style={{ minHeight: 0 }}>
    {!inline && <>
      <p className="da-small" style={{ marginTop: 0, lineHeight: 1.7 }}>使用：选库问答、下载本人生成的结果。管理：同时支持数据维护、建模、发布、成员授权和删除。创建者与全局管理员固定拥有管理权限。</p>
      <div className="kw-tabs" style={{ marginBottom: 20 }}>
        <button type="button" className={tab === 'members' ? 'active' : ''} disabled={busy} onClick={() => selectTab('members')}>已授权成员</button>
        <button type="button" className={tab === 'add' ? 'active' : ''} disabled={busy} onClick={() => selectTab('add')}>添加成员</button>
      </div>
      {tab === 'members' && creator && <div className="kw-member-owner"><Icon name="user" size="sm" />{creator.username}<span>创建者 · 管理</span></div>}
    </>}
    {error && <div className="kw-alert" role="alert">{error}<button className="da-btn da-btn-sm" onClick={() => setRevision(value => value + 1)} disabled={busy}>刷新</button></div>}
    {tab === 'add' && <>
      <div className="kw-toolbar">
        <label className="kw-search"><Icon name="search" size="sm" /><input autoFocus aria-label="搜索中文名或账号" placeholder="搜索中文名或账号" value={search} onChange={e => { setSearch(e.target.value); setPage(0); }} /></label>
        <select className="kw-select" aria-label="新成员权限" value={role} onChange={e => setRole(e.target.value as KnowledgeBaseRole)}><option value="QUERY">使用权限</option><option value="MANAGE">管理权限</option></select>
      </div>
      <div className="kw-toolbar" style={{ flexWrap: 'wrap' }}>
        <select className="kw-select" aria-label="筛选公司" value={company} onChange={e => { setCompany(e.target.value); setDepartment(''); setPage(0); }}><option value="">全部公司</option>{filters.companies.map(value => <option key={value}>{value}</option>)}</select>
        <select className="kw-select" aria-label="筛选部门" value={department} onChange={e => { setDepartment(e.target.value); setPage(0); }}><option value="">全部部门</option>{filters.departments.map(value => <option key={value}>{value}</option>)}</select>
        <button className="da-btn" disabled={busy || loading} onClick={() => setSelected(previous => new Set([...previous, ...(users?.items.filter(user => !user.owner && !user.admin).map(user => user.userId) ?? [])]))}>选择本页</button>
        <button className="da-btn" disabled={busy || loading} onClick={() => void selectFiltered()}>选择全部筛选结果</button>
        <button className="da-btn" disabled={busy || !selected.size} onClick={() => setSelected(new Set())}>清空选择</button>
        <button className="da-btn da-btn-primary" disabled={busy || !selected.size} onClick={() => void batch()}>批量授权（{selected.size}人）</button>
        <span className="da-small">按当前选中的人员授权，不自动跟随组织变化。</span>
      </div>
    </>}
    <div className="kw-tablewrap" style={{ minHeight: inline ? 0 : 140, maxHeight: inline ? undefined : '36vh', overflow: inline ? 'visible' : 'auto' }} aria-busy={loading}>
      <table className="kw-table"><thead><tr><th>{inline ? '成员' : '用户名'}</th><th>权限</th>{inline && <th>来源</th>}<th style={inline ? undefined : { width: 180 }}>操作</th></tr></thead><tbody>
        {loading ? <tr><td colSpan={inline ? 4 : 3}>加载中…</td></tr> : displayRows.map(member => <tr key={member.userId}>
          <td><div className={inline ? 'kbw-member-identity' : undefined}>
            {tab === 'add' && !member.owner && !member.admin && <input type="checkbox" aria-label={`选择${member.username}`} disabled={busy} checked={selected.has(member.userId)} onChange={e => setSelected(previous => { const next = new Set(previous); if (e.target.checked) next.add(member.userId); else next.delete(member.userId); return next; })} />}
            {inline && <span className="kbw-member-avatar" aria-hidden="true">{(member.displayName || member.username).slice(0, 1).toUpperCase()}</span>}
            <span><strong>{member.displayName || member.username}</strong><small className="da-small">{[member.username, member.company, member.department].filter(Boolean).join(' · ')}</small></span>
          </div></td>
          <td>{inline ? <span className="kbw-role-pill" data-manage={member.owner || member.admin || member.role === 'MANAGE' || undefined}>{member.owner || member.admin ? '管理' : roleLabel(member.role)}</span> : member.owner ? '创建者 · 管理' : member.admin ? '全局管理员 · 管理' : roleLabel(member.role)}</td>
          {inline && <td>{member.owner ? '创建者' : member.admin ? '全局管理' : member.role ? '单独授权' : '—'}</td>}
          <td>{member.owner || member.admin ? <span className="da-small">固定权限</span> : tab === 'add'
            ? <button className="da-btn da-btn-sm" disabled={busy || member.role === role} onClick={() => void change(member, role)}>{member.role === role ? '已授权' : `授予${roleLabel(role)}`}</button>
            : inline ? <WorkspaceMenu label={member.username + '的成员操作'} items={[
              { label: '修改权限', icon: 'shield', disabled: busy, onClick: () => setRoleEditor(member) },
              { label: '移除', icon: 'trash', danger: true, disabled: busy, onClick: () => void change(member, null) },
            ]} /> : <div style={{ display: 'flex', gap: 8 }}>
              <select className="kw-select" aria-label={`${member.username}的权限`} disabled={busy} value={member.role ?? 'QUERY'} onChange={event => void change(member, event.target.value as KnowledgeBaseRole)}><option value="QUERY">使用</option><option value="MANAGE">管理</option></select>
              <button className="da-btn da-btn-sm" disabled={busy} onClick={() => void change(member, null)}>撤销</button>
            </div>}</td>
        </tr>)}
        {!loading && (tab === 'members' ? memberTotal === 0 : !displayRows.length) && <tr className={inline ? 'kbw-members-empty' : undefined}>
          <td colSpan={inline ? 4 : 3}>{tab === 'add' ? '没有匹配的用户' : '还没有其他成员。邀请同事一起使用或管理此库。'}</td>
        </tr>}
      </tbody></table>
    </div>
    {(!inline || pages > 1 || page > 0) && <div className="kw-pagination"><span>共 {data?.total ?? 0} 位{tab === 'members' ? '授权成员' : '用户'}</span><span className="kw-spacer" />
      <button className="da-btn da-btn-sm" disabled={loading || busy || page === 0} onClick={() => setPage(value => value - 1)}>上一页</button><span>{page + 1} / {pages}</span>
      <button className="da-btn da-btn-sm" disabled={loading || busy || page + 1 >= pages} onClick={() => setPage(value => value + 1)}>下一页</button></div>}
  </div>;

  if (inline) return <div className="kbw-page kbw-members"><div className="kbw-members-frame">
    <header className="kbw-page-heading"><div><h1>成员与权限</h1><p>查看谁可以使用这个知识库，以及谁能管理数据和模型。</p></div>
      <div className="kbw-page-actions">{tab === 'members'
        ? <button type="button" className="da-btn da-btn-primary" disabled={busy} onClick={() => selectTab('add')}><Icon name="plus" size="sm" />邀请成员</button>
        : <button type="button" className="da-btn" disabled={busy} onClick={() => selectTab('members')}>返回成员</button>}</div>
    </header>
    <div className="kbw-members-layout">
      <section className="kbw-card kbw-members-column">
        <div className="kbw-card-head"><h2>{tab === 'members' ? '知识库成员' : '邀请成员'}</h2></div>
        {body}
        {tab === 'members' && members && <footer className="kbw-members-total">共 {total} 位成员</footer>}
      </section>
      <aside className="kbw-side-cards" aria-label="成员与权限摘要">
        <section className="kbw-card kbw-card-pad kbw-members-model"><h2><Icon name="shield" size="sm" />权限模型</h2>
          <dl><div><dt><span className="kbw-role-pill">使用</span> <small>{queryCount === undefined ? '—' : queryCount + ' 人'}</small></dt><dd>基于此库问数、下载本人生成的结果。</dd></div>
            <div><dt><span className="kbw-role-pill" data-manage>管理</span> <small>{manageCount === undefined ? '—' : manageCount + ' 人'}</small></dt><dd>维护数据、建模、发布和管理成员。</dd></div></dl>
          <p>创建者和全局管理员拥有固定管理权限。</p>
        </section>
        <section className="kbw-card kbw-card-pad kbw-members-facts"><h2><Icon name="book" size="sm" />这个库</h2>
          <dl className="kbw-meta-list"><div><dt>创建者</dt><dd>{creator?.username ?? '—'}</dd></div>
            <div><dt>成员</dt><dd>{members ? total + ' 人' : '—'}</dd></div>
            <div><dt>可见范围</dt><dd>{publicQuery === undefined ? '由管理员配置' : publicQuery ? '全员可使用' : '按成员授权'}</dd></div></dl>
        </section>
      </aside>
    </div>
    {roleEditor && <dialog ref={roleDialog} className="mo-form-dialog" aria-label="修改权限" onClose={() => setRoleEditor(undefined)}>
      <header className="mo-dialog-head"><strong>修改 {roleEditor.username} 的权限</strong></header>
      <form onSubmit={event => { event.preventDefault(); const next = new FormData(event.currentTarget).get('role') as KnowledgeBaseRole; roleDialog.current?.close(); if (next !== roleEditor.role) void change(roleEditor, next); }}>
        <div className="mo-dialog-body"><select className="da-input" name="role" aria-label="成员权限" defaultValue={roleEditor.role ?? 'QUERY'}><option value="QUERY">使用</option><option value="MANAGE">管理</option></select></div>
        <footer className="mo-dialog-footer"><button type="button" className="da-btn" onClick={() => roleDialog.current?.close()}>取消</button><button className="da-btn da-btn-primary">保存权限</button></footer>
      </form>
    </dialog>}
  </div></div>;

  return <div className="da-modal-overlay" onClick={() => { if (!busy) onClose?.(); }}>
    <div ref={shell} className="da-modal-shell" role="dialog" aria-modal="true" aria-labelledby="kb-members-title" tabIndex={-1}
      style={{ width: 'min(760px, 94vw)' }} onClick={event => event.stopPropagation()}>
      <div className="da-modal-head" style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
        <div><div id="kb-members-title" className="da-modal-title">成员与权限</div><div className="da-small" style={{ marginTop: 6 }}>{name}</div></div>
        <button className="da-btn da-btn-ghost" aria-label="关闭成员管理" disabled={busy} onClick={onClose}><Icon name="close" size="sm" /></button>
      </div>
      {body}<div className="da-modal-foot" style={{ flexShrink: 0 }}><button className="da-btn" disabled={busy} onClick={onClose}>完成</button></div>
    </div>
  </div>;
}
