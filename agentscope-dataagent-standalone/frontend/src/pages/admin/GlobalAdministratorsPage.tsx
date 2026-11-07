import { useEffect, useState } from 'react';
import { AdminDirectoryUser, listGlobalAdministrators, setGlobalAdministrator } from '../../api/adminHistory';
import { KnowledgeBreadcrumb } from '../../components/KnowledgeWorkspace';
import Icon from '../../components/Icon';
import AdminUserSearch, { adminUserLabel } from './AdminUserSearch';
import './AdminHistory.css';

export default function GlobalAdministratorsPage() {
  const [admins, setAdmins] = useState<AdminDirectoryUser[] | null>(null);
  const [adding, setAdding] = useState(false);
  const [pending, setPending] = useState<{ user: AdminDirectoryUser; enabled: boolean } | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [revision, setRevision] = useState(0);

  useEffect(() => {
    const controller = new AbortController();
    listGlobalAdministrators(controller.signal).then(setAdmins).catch(e => {
      if (!controller.signal.aborted) setError((e as Error).message);
    });
    return () => controller.abort();
  }, [revision]);

  async function confirm() {
    if (!pending) return;
    setBusy(true);
    setError('');
    setNotice('');
    try {
      await setGlobalAdministrator(pending.user.userId, pending.enabled);
      setAdmins(previous => {
        const rest = previous?.filter(user => user.userId !== pending.user.userId) ?? [];
        return pending.enabled ? [...rest, pending.user] : rest;
      });
      setNotice(pending.enabled ? '已添加全局管理员。该用户重新登录后可使用管理功能。' : '已移除全局管理员权限。');
      setAdding(false);
      setPending(null);
      setRevision(value => value + 1);
    } catch (e) { setError((e as Error).message); }
    finally { setBusy(false); }
  }

  return <div className="kw-page">
    <KnowledgeBreadcrumb items={[{ label: '更多', to: '/more' }, { label: '全局管理员' }]} />
    <div className="kw-scroll"><div className="kw-content admin-history">
      <header className="admin-page-header">
        <div><h1>全局管理员</h1><p>查看和维护拥有全局管理权限的账号。</p></div>
        <button className={`da-btn ${adding ? '' : 'da-btn-primary'}`} type="button"
          disabled={busy || admins === null} aria-expanded={adding}
          onClick={() => { setAdding(value => !value); setPending(null); }}>
          <Icon name={adding ? 'close' : 'plus'} size="sm" />
          {adding ? '关闭用户搜索' : '添加管理员'}
        </button>
      </header>
      {adding && <section className="admin-action-panel" aria-label="添加管理员">
        <h2>查找用户</h2>
        <fieldset disabled={busy} className="admin-history-search" aria-label="搜索待添加账号">
          <AdminUserSearch excludedIds={admins?.map(user => user.userId)}
            onSelect={user => setPending({ user, enabled: true })} />
        </fieldset>
      </section>}
      {pending && <section className="admin-action-panel admin-confirm" aria-label="确认权限变更">
        <div>
          <h2>{pending.enabled ? '确认添加管理员' : '确认移除权限'}</h2>
          <p>{pending.enabled ? '授予' : '移除'} {adminUserLabel(pending.user)} 的全局管理员权限？</p>
        </div>
        <div className="admin-form-actions">
          <button className="da-btn" type="button" onClick={() => setPending(null)} disabled={busy}>取消</button>
          <button className={`da-btn ${pending.enabled ? 'da-btn-primary' : 'da-btn-danger'}`} type="button"
            onClick={() => void confirm()} disabled={busy}>确认</button>
        </div>
      </section>}
      {error && <p className="admin-action-feedback admin-error" role="alert">{error}</p>}
      {notice && <p className="admin-action-feedback admin-notice" role="status">{notice}</p>}
      <div className="admin-list-meta">
        <h2>管理员列表</h2>
        {admins && <span>共 {admins.length} 位</span>}
      </div>
      <table className="admin-table admin-administrators-table" aria-label="管理员列表" aria-busy={admins === null && !error}>
        <thead><tr><th scope="col">用户</th><th scope="col">部门</th><th scope="col">操作</th></tr></thead>
        <tbody>{admins?.map(user => (
          <tr key={user.userId}>
            <td>
              <strong>{user.displayName || user.username}</strong>
              {user.displayName && user.displayName !== user.username && <span className="admin-account">{user.username}</span>}
            </td>
            <td className="admin-department">{user.department || '—'}</td>
            <td>
              <button className="da-btn da-btn-sm" type="button" disabled={busy || admins.length === 1}
                title={admins.length === 1 ? '不能移除最后一位全局管理员' : undefined}
                onClick={() => { setAdding(false); setPending({ user, enabled: false }); }}>移除权限</button>
            </td>
          </tr>
        ))}</tbody>
      </table>
      {admins === null && !error && <p className="admin-list-feedback" role="status">正在加载管理员…</p>}
      {admins?.length === 0 && <p className="admin-list-feedback">没有可显示的管理员。</p>}
      {admins?.length === 1 && <p className="admin-list-footer">至少保留一位全局管理员。</p>}
    </div></div>
  </div>;
}
