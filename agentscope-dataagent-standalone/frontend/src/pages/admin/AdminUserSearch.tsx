import { useState } from 'react';
import { AdminDirectoryUser, searchAdminUsers } from '../../api/adminHistory';
import Icon from '../../components/Icon';

export function adminUserLabel(user: AdminDirectoryUser) {
  return [user.displayName ? `${user.displayName}（${user.username}）` : user.username, user.department]
    .filter(Boolean).join(' · ');
}

export default function AdminUserSearch({ onSelect, excludedIds = [], selectSingleMatch = false }: {
  onSelect: (user: AdminDirectoryUser) => void;
  excludedIds?: string[];
  selectSingleMatch?: boolean;
}) {
  const [term, setTerm] = useState('');
  const [items, setItems] = useState<AdminDirectoryUser[] | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const candidates = items?.filter(user => !excludedIds.includes(user.userId));

  async function search() {
    const query = term.trim();
    if (loading || !query) return;
    setItems(null);
    setError('');
    setLoading(true);
    try {
      const results = await searchAdminUsers(query);
      if (selectSingleMatch && results.length === 1 && !excludedIds.includes(results[0].userId)) {
        onSelect(results[0]);
      } else {
        setItems(results);
      }
    }
    catch (e) { setError((e as Error).message); }
    finally { setLoading(false); }
  }

  return <div className="admin-directory-search">
    <form className="admin-history-form" onSubmit={event => { event.preventDefault(); void search(); }}>
      <label>姓名或登录账号 <input className="da-input" type="search" value={term} maxLength={191}
        placeholder="输入姓名或账号"
        onChange={event => { setTerm(event.target.value); setItems(null); setError(''); }} disabled={loading} /></label>
      <button className="da-btn" type="submit" disabled={loading || !term.trim()}>
        <Icon name="search" size="sm" /> {loading ? '搜索中…' : '搜索'}
      </button>
    </form>
    {error && <p className="admin-search-feedback admin-error" role="alert">{error}</p>}
    {candidates?.length === 0 && <p className="admin-search-feedback">没有找到符合条件的用户。可以用完整账号再搜一次。</p>}
    {selectSingleMatch && (candidates?.length ?? 0) > 1 && <p className="admin-search-feedback">找到多个用户，请选择要查看的账号。</p>}
    {!!candidates?.length && <ul className="admin-search-results">{candidates.map(user => (
      <li key={user.userId}><button className="da-btn" type="button" onClick={() => {
        setItems(null);
        onSelect(user);
      }}>{adminUserLabel(user)}</button></li>
    ))}</ul>}
  </div>;
}
