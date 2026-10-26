import React, { useEffect, useState } from 'react';
import { loadEntryOptions } from '../api/auth';

/** Do not expose a password form before the server entry policy is known. */
export default function AuthBootstrap({ children }: { children: React.ReactElement }) {
  const [state, setState] = useState('loading');
  const [attempt, setAttempt] = useState(0);
  useEffect(() => {
    let active = true;
    setState('loading');
    void loadEntryOptions().then(() => { if (active) setState('ready'); })
      .catch(() => { if (active) setState('error'); });
    return () => { active = false; };
  }, [attempt]);
  if (state === 'ready') return children;
  return <div role="status" style={{ padding: 32 }}>
    {state === 'loading' ? '正在加载…' : '暂时无法读取登录配置，请检查服务连接后重试。'}
    {state === 'error' && <button className="da-btn" onClick={() => setAttempt(n => n + 1)}>重试</button>}
  </div>;
}
