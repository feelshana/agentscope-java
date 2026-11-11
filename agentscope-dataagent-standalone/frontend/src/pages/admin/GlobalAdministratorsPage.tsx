import { Fragment, useEffect, useRef, useState } from 'react';
import { AdminDirectoryUser, listGlobalAdministrators, setGlobalAdministrator } from '../../api/adminHistory';
import { KnowledgeBreadcrumb, WorkspaceMenu } from '../../components/KnowledgeWorkspace';
import Icon, { IconName } from '../../components/Icon';
import AdministratorPicker from './AdministratorPicker';
import '../../admin-help.css';

const capabilities: { icon: IconName; title: string; body: string }[] = [
  { icon: 'shield', title: '管理全部知识库', body: '管理数据、建模与发布，维护知识库的业务口径。' },
  { icon: 'user', title: '授予与回收权限', body: '维护全局管理员与知识库成员的访问权限。' },
  { icon: 'list', title: '查看用户对话历史', body: '按用户检索提问与回复，了解使用情况、排查问题。' },
];
export default function GlobalAdministratorsPage() {
  const [admins, setAdmins] = useState<AdminDirectoryUser[] | null>(null);
  const [loading, setLoading] = useState(true);
  const [adding, setAdding] = useState(false);
  const [removing, setRemoving] = useState<AdminDirectoryUser | null>(null);
  const [busy, setBusy] = useState(false);
  const [query, setQuery] = useState('');
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [revision, setRevision] = useState(0);
  const search = useRef<HTMLInputElement>(null);
  useEffect(() => {
    const controller = new AbortController();
    setLoading(true); setError('');
    listGlobalAdministrators(controller.signal).then(users => { if (!controller.signal.aborted) setAdmins(users); })
      .catch(e => { if (!controller.signal.aborted) setError((e as Error).message); })
      .finally(() => { if (!controller.signal.aborted) setLoading(false); });
    return () => controller.abort();
  }, [revision]);
  async function grant(user: AdminDirectoryUser) {
    await setGlobalAdministrator(user.userId, true);
    setAdmins(old => [...(old ?? []).filter(item => item.userId !== user.userId), user]);
    setNotice('已添加全局管理员。对方重新登录后可使用管理功能。');
    setRevision(n => n + 1);
  }
  async function revoke() {
    if (!removing || busy || admins?.length === 1) return;
    setBusy(true); setError(''); setNotice('');
    try {
      await setGlobalAdministrator(removing.userId, false);
      setAdmins(old => old?.filter(user => user.userId !== removing.userId) ?? []);
      setRemoving(null); setNotice('已移除全局管理员权限。'); setRevision(n => n + 1);
    } catch (e) { setError((e as Error).message); }
    finally { setBusy(false); }
  }
  const matches = (admins ?? []).filter(user => [user.displayName, user.username, user.department].join(' ').toLowerCase().includes(query.trim().toLowerCase()));
  return <div className="kw-page am-page" onKeyDown={e => { if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'k' && !adding) { e.preventDefault(); search.current?.focus(); } }}>
    <KnowledgeBreadcrumb items={[{ label: '更多', to: '/more' }, { label: '全局管理员' }]} />
    <div className="kw-scroll"><div className="am-content">
      <header className="am-page-head"><div><h1>全局管理员</h1><p>查看和维护拥有全部知识库管理与授权能力的账号。</p></div><button className="am-btn primary" disabled={busy || admins === null} onClick={() => { setRemoving(null); setNotice(''); setAdding(true); }}><Icon name="plus" />添加管理员</button></header>
      {error && <p className="am-error" role="alert">{error}</p>}{notice && <p className="am-notice" role="status">{notice}</p>}
      <section className="am-card am-list-card" aria-label="管理员列表" aria-busy={loading}>
        <div className="am-list-toolbar"><label className="am-search"><Icon name="search" /><input ref={search} type="search" aria-label="筛选管理员" disabled={busy} placeholder="搜索姓名、账号或部门" value={query} onChange={e => { setQuery(e.target.value); if (!busy) setRemoving(null); }} maxLength={191} /><kbd>Ctrl K</kbd></label><span className="am-count">{admins ? matches.length + ' 位' + (query ? ' / 共 ' + admins.length + ' 位' : '') : '—'}</span><button className="am-close" disabled={loading || busy} aria-label="刷新管理员列表" title="刷新管理员列表" onClick={() => setRevision(n => n + 1)}><Icon name="refresh" /></button></div>
        <div className="am-table-scroll"><table className="am-table"><thead><tr><th>成员</th><th>部门</th><th><span className="am-sr-only">操作</span></th></tr></thead><tbody>{matches.map((user, index) => <Fragment key={user.userId}>
          <tr className={removing?.userId === user.userId ? 'open' : ''}><td><div className="am-who"><span className={'am-avatar tone-' + index % 4}>{(user.displayName || user.username).slice(0, 1)}</span><span className="am-identity"><strong>{user.displayName || user.username}</strong><small>{user.username}</small></span></div></td><td>{user.department || '—'}</td><td className="am-row-action"><WorkspaceMenu label={(user.displayName || user.username) + '的权限操作'} items={[{ label: '移除权限', icon: 'shield', danger: true, disabled: busy || loading || admins?.length === 1, onClick: () => { setError(''); setNotice(''); setRemoving(user); } }]} /></td></tr>
          {removing?.userId === user.userId && <tr className="am-confirm-row"><td colSpan={3}><div className="am-inline-confirm"><span className="am-danger-icon"><Icon name="shield" /></span><div><strong>移除 {user.displayName || user.username} 的全局管理员权限？</strong><p>此账号将失去全局管理能力。已有知识库、已发布模型和历史对话都会保留。</p></div><div className="am-confirm-actions"><button className="am-btn small" disabled={busy} onClick={() => setRemoving(null)}>取消</button><button className="am-btn small danger" disabled={busy} onClick={() => void revoke()}>{busy ? '正在移除…' : '确认移除'}</button></div></div></td></tr>}
        </Fragment>)}</tbody></table></div>
        {!matches.length && <div className="am-feedback" role="status">{loading ? '正在加载管理员…' : error ? <button className="am-text" onClick={() => setRevision(n => n + 1)}>重新加载</button> : query ? '没有匹配的管理员，试试其他姓名、账号或部门。' : '没有可显示的管理员。'}</div>}
        <footer className="am-list-foot"><Icon name="info" /><span>系统始终保留至少一位全局管理员，最后一位无法移除。</span></footer>
      </section>
      <section className="am-card am-capabilities"><header><h2>全局管理员可以做什么</h2><p>授予前请确认对方职责范围</p></header><div>{capabilities.map(item => <article key={item.title}><span className="am-cap-icon"><Icon name={item.icon} /></span><div><strong>{item.title}</strong><p>{item.body}</p></div></article>)}</div></section>
    </div></div>
    {adding && admins && <AdministratorPicker admins={admins} grant={grant} close={() => setAdding(false)} />}
  </div>;
}
