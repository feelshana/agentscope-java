import { useEffect, useState } from 'react';

const SCRIPT = /assets\/index-[\w-]+\.js/;

/** The entry script this page was loaded with; null in the dev server, which has no hashed bundle. */
function loadedScript(): string | null {
  for (const script of Array.from(document.scripts)) {
    const match = script.src.match(SCRIPT);
    if (match) return match[0];
  }
  return null;
}

/**
 * After a redeploy an open tab keeps running the old bundle (it once auto-ran trial SQL the new
 * version no longer runs). Checks the served entry script on focus and every few minutes, and
 * offers a reload when it changed.
 */
export default function NewVersionBanner() {
  const [stale, setStale] = useState(false);
  useEffect(() => {
    const current = loadedScript();
    if (!current) return;
    let stopped = false;
    const check = async () => {
      try {
        const html = await (await fetch('/', { cache: 'no-store' })).text();
        const served = html.match(SCRIPT)?.[0];
        if (!stopped && served && served !== current) setStale(true);
      } catch { /* Offline or restarting: try again later. */ }
    };
    const timer = window.setInterval(() => void check(), 3 * 60_000);
    window.addEventListener('focus', check);
    return () => { stopped = true; window.clearInterval(timer); window.removeEventListener('focus', check); };
  }, []);
  if (!stale) return null;
  return <div role="alert" style={{ position: 'fixed', top: 12, left: '50%', transform: 'translateX(-50%)', zIndex: 2000,
    display: 'flex', alignItems: 'center', gap: 10, padding: '8px 14px', borderRadius: 10, fontSize: 13,
    background: 'var(--da-surface, #fff)', border: '1px solid var(--da-primary)', boxShadow: 'var(--da-shadow-pop)' }}>
    页面已更新到新版本，刷新后生效
    <button className="da-btn da-btn-primary da-btn-sm" onClick={() => window.location.reload()}>刷新</button>
  </div>;
}
