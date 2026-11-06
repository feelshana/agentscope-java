import { useEffect, useState } from 'react';
import { AdminDirectoryUser, listGlobalAdministrators, setGlobalAdministrator } from '../../api/adminHistory';
import { KnowledgeBreadcrumb } from '../../components/KnowledgeWorkspace';
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
      <h1>全局管理员</h1>
      <button className="da-btn da-btn-primary" type="button" disabled={busy || admins === null}
        onClick={() => { setAdding(value => !value); setPending(null); }}>
        {adding ? '关闭用户搜索' : '添加管理员'}
      </button>
      {adding && <fieldset disabled={busy} className="admin-history-search" aria-label="添加管理员">
        <AdminUserSearch excludedIds={admins?.map(user => user.userId)}
          onSelect={user => setPending({ user, enabled: true })} />
      </fieldset>}
      {error && <p role="alert">{error}</p>}
      {notice && <p role="status">{notice}</p>}
      {pending && <section className="admin-history-box" aria-label="确认权限变更">
        <p>{pending.enabled ? '授予' : '移除'} {adminUserLabel(pending.user)} 的全局管理员权限？</p>
        <button className="da-btn da-btn-primary" type="button" onClick={() => void confirm()} disabled={busy}>确认</button>{' '}
        <button className="da-btn" type="button" onClick={() => setPending(null)} disabled={busy}>取消</button>
      </section>}
      {admins === null && !error && <p role="status">正在加载管理员…</p>}
      {admins?.length === 0 && <p>没有可显示的管理员。</p>}
      <ul className="admin-history-list">{admins?.map(user => (
        <li className="admin-history-row" key={user.userId}>
          <span>{adminUserLabel(user)}</span>
          <button className="da-btn" type="button" disabled={busy || admins.length === 1}
            title={admins.length === 1 ? '不能移除最后一位全局管理员' : undefined}
            onClick={() => { setAdding(false); setPending({ user, enabled: false }); }}>移除权限</button>
        </li>
      ))}</ul>
    </div></div>
  </div>;
}
