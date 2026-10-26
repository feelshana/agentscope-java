/** Browser-only BI integration. Supply the shared key from the agreed private handoff. */
function base64url(bytes) {
  return btoa(String.fromCharCode(...bytes)).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

export async function createDataAgentTicket(username, sharedKey, options = {}) {
  const keyBytes = Uint8Array.from(atob(sharedKey), character => character.charCodeAt(0));
  if (keyBytes.length !== 32) throw new Error('免登密钥必须是32字节Base64');
  const key = await crypto.subtle.importKey('raw', keyBytes, 'AES-GCM', false, ['encrypt']);
  const iv = crypto.getRandomValues(new Uint8Array(12));
  const payload = new TextEncoder().encode(JSON.stringify({
    username,
    issuedAt: options.issuedAt ?? Date.now(),
    requestId: options.requestId ?? crypto.randomUUID(),
  }));
  const result = await crypto.subtle.encrypt({ name: 'AES-GCM', iv, tagLength: 128 }, key, payload);
  return `v1.${base64url(iv)}.${base64url(new Uint8Array(result))}`;
}

/** Mount once per BI wrapper page. A reload reuses its fixed-expiry JWT without renewal. */
export function mountDataAgent({ iframe, username, sharedKey, basePath = '/dataagent',
  newEntry = true, onError = message => window.alert(message) }) {
  const origin = window.location.origin;
  const base = basePath.replace(/\/$/, '');
  const storageKey = `dataagent:bi-entry:${base}`;
  let closed = false;
  let loginPromise;
  const timer = window.setTimeout(() => onError('智能体页面未就绪，请重新进入'), 20000);
  const login = async () => {
    if (!newEntry) {
      let cached;
      try { cached = JSON.parse(sessionStorage.getItem(storageKey) || 'null'); } catch { /* invalid cache */ }
      if (cached?.username === username && cached.expiresAt > Date.now()) return cached;
      throw new Error('会话已过期，请从分析云重新点击进入');
    }
    sessionStorage.removeItem(storageKey);
    const ticket = await createDataAgentTicket(username, sharedKey);
    const response = await fetch(`${base}/api/auth/bi-login`, {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ ticket }), cache: 'no-store',
    });
    if (!response.ok) {
      const body = await response.json().catch(() => ({}));
      throw new Error(body.message || body.detail || '免登失败，请重新进入或联系管理员');
    }
    const result = await response.json();
    return { ...result, username };
  };
  const listener = async event => {
    if (closed || event.origin !== origin || event.source !== iframe.contentWindow) return;
    if (event.data?.type === 'dataagent:accepted') { window.clearTimeout(timer); return; }
    if (event.data?.type !== 'dataagent:ready' || typeof event.data.handshakeId !== 'string') return;
    window.clearTimeout(timer);
    try {
      loginPromise ??= login();
      const result = await loginPromise;
      if (closed) return;
      sessionStorage.setItem(storageKey, JSON.stringify(result));
      iframe.contentWindow.postMessage({ type: 'dataagent:login',
        handshakeId: event.data.handshakeId, token: result.token }, origin);
    } catch (error) {
      if (!closed) onError(error instanceof Error ? error.message : '免登失败，请重新进入');
    }
  };
  window.addEventListener('message', listener);
  iframe.src = `${base}/bi-entry`;
  return () => { closed = true; window.clearTimeout(timer); window.removeEventListener('message', listener); };
}
