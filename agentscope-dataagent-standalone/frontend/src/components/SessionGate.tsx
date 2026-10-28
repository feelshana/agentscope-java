import React, { useEffect, useState } from 'react';
import { Navigate } from 'react-router-dom';
import { AUTH_EXPIRED_EVENT, getEntryOptions, getToken, me, clearToken } from '../api/auth';
import { appPath } from '../api/paths';
import { readBiAccount } from '../utils/biAccount';
import BiEntryPage from '../pages/BiEntryPage';

/** Validate cached credentials before mounting protected pages or fetching their data. */
export default function SessionGate({ children }: { children: React.ReactElement }) {
  const [hadToken] = useState(() => Boolean(getToken()));
  const [state, setState] = useState<'checking' | 'ready' | 'expired' | 'error'>(
    () => getToken() ? 'checking' : 'expired',
  );
  const [attempt, setAttempt] = useState(0);
  useEffect(() => {
    let active = true;
    const expire = () => { if (active) setState('expired'); };
    window.addEventListener(AUTH_EXPIRED_EVENT, expire);
    if (getToken()) {
      setState('checking');
      void me().then(profile => {
        if (active && getEntryOptions().biAccountLoginEnabled) {
          let account: string;
          try { account = readBiAccount(localStorage); } catch { clearToken(); setState('expired'); return; }
          if (account.toLowerCase() !== profile.username.toLowerCase()) {
            clearToken(); window.location.replace(appPath('/bi-entry')); return;
          }
        }
        if (active) setState(getToken() ? 'ready' : 'expired');
      }).catch(() => {
        if (active) setState(getToken() ? 'error' : 'expired');
      });
    } else expire();
    return () => { active = false; window.removeEventListener(AUTH_EXPIRED_EVENT, expire); };
  }, [attempt]);
  if (state === 'expired') return getEntryOptions().passwordLoginEnabled
    ? <Navigate to="/login" replace state={{ sessionExpired: hadToken }} />
    : <BiEntryPage receive={!hadToken && Boolean(getEntryOptions().biAccountLoginEnabled)} />;
  if (state === 'ready') return children;
  return <div role="status" style={{ padding: 32 }}>
    {state === 'checking' ? '正在验证登录状态…' : '暂时无法验证登录状态，请检查服务连接后重试。'}
    {state === 'error' && <button className="da-btn" onClick={() => setAttempt(value => value + 1)}>重试</button>}
  </div>;
}
