import React, { useEffect, useState } from 'react';
import { apiFetch, clearToken, getEntryOptions, saveToken, tokenExpiresAt } from '../api/auth';
import { readBiAccount } from '../utils/biAccount';
import { appPath } from '../api/paths';

/** The explicit BI entry alone accepts credentials; expired pages never auto-renew. */
export default function BiEntryPage({ receive = true }: { receive?: boolean }) {
  const [message, setMessage] = useState(receive ? '正在等待分析云登录…' : '请从分析云重新点击“数据分析智能体”进入。');
  useEffect(() => {
    if (receive && getEntryOptions().biAccountLoginEnabled) {
      clearToken();
      let active = true;
      const controller = new AbortController();
      try {
        const username = readBiAccount(localStorage);
        setMessage('正在登录…');
        void fetch(appPath('/api/auth/bi-account-login'), { method: 'POST', signal: controller.signal,
          headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ username }), cache: 'no-store' })
          .then(async response => { if (!response.ok) throw new Error('免登失败，请重新从分析云进入。'); return response.json(); })
          .then(result => { if (active) { saveToken(result.token); window.parent.postMessage({ type: 'dataagent:accepted' }, window.location.origin); window.location.replace(appPath('/chat')); } })
          .catch(error => { if (active) setMessage(error instanceof Error ? error.message : String(error)); });
      } catch (error) { setMessage(error instanceof Error ? error.message : String(error)); }
      return () => { active = false; controller.abort(); };
    }
    if (!receive || !getEntryOptions().biLoginEnabled || window.parent === window) return;
    clearToken();
    const handshakeId = crypto.randomUUID();
    let active = true;
    let accepted = false;
    const parent = window.parent;
    const timer = window.setTimeout(() => {
      if (!accepted) setMessage('未收到分析云登录信息，请关闭此页面后重新进入。');
    }, 15000);
    const listener = (event: MessageEvent) => {
      if (event.origin !== window.location.origin || event.source !== parent
          || event.data?.type !== 'dataagent:login' || event.data.handshakeId !== handshakeId || accepted) return;
      const token: unknown = event.data.token;
      if (typeof token !== 'string' || token.length > 16384 || tokenExpiresAt(token) <= Date.now()) {
        setMessage('登录信息已失效，请重新从分析云进入。'); return;
      }
      accepted = true;
      window.clearTimeout(timer);
      setMessage('正在验证登录信息…');
      void apiFetch('/api/auth/me', { headers: { Authorization: `Bearer ${token}` } }).then(response => {
        if (!response.ok) throw new Error();
        if (!active) return;
        clearToken(); saveToken(token);
        parent.postMessage({ type: 'dataagent:accepted', handshakeId }, window.location.origin);
        // A full reload discards all previous user caches, subscriptions and component state.
        window.location.replace(appPath('/chat'));
      }).catch(() => {
        if (active) { accepted = false; setMessage('免登验证失败，请重新从分析云进入。'); }
      });
    };
    window.addEventListener('message', listener);
    parent.postMessage({ type: 'dataagent:ready', handshakeId }, window.location.origin);
    return () => { active = false; window.clearTimeout(timer); window.removeEventListener('message', listener); };
  }, [receive]);
  return <main style={{ minHeight: '100dvh', display: 'grid', placeItems: 'center', background: 'var(--da-app-bg)' }}>
    <section role="status" style={{ padding: 32, textAlign: 'center' }}>
      <h1 style={{ fontSize: 24 }}>红海DataAgent</h1><p>{message}</p>
    </section>
  </main>;
}
