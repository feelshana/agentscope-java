import { appPath } from './paths';
const BASE = '';
export interface EntryOptions { passwordLoginEnabled: boolean; biLoginEnabled: boolean; biAccountLoginEnabled?: boolean }
let entryOptions: EntryOptions = { passwordLoginEnabled: false, biLoginEnabled: false };
export function getEntryOptions(): EntryOptions { return entryOptions; }
export async function loadEntryOptions(): Promise<void> {
  const response = await fetch(appPath('/api/auth/options'), { cache: 'no-store' });
  if (!response.ok) throw new Error('暂时无法读取登录配置');
  const value = await response.json();
  if (typeof value.passwordLoginEnabled !== 'boolean' || typeof value.biLoginEnabled !== 'boolean')
    throw new Error('登录配置格式不正确');
  entryOptions = value;
}

export const AUTH_EXPIRED_EVENT = 'dataagent:auth-expired';
export const SESSION_EXPIRED_MESSAGE = '登录状态已失效，请重新进入';

const RENEW_PATH = '/api/auth/renew';
const RENEW_INTERVAL_MS = 5_000;
const RENEW_TIMEOUT_MS = 10_000;
let pendingRenewal: { token: string; result: Promise<string | null> } | undefined;

function expireToken(expected: string) {
  if (getToken() !== expected) return;
  clearToken();
  window.dispatchEvent(new Event(AUTH_EXPIRED_EVENT));
}

/** Only the server's rejection of the current credential can end this browser session. */
export async function apiFetch(input: RequestInfo | URL, init?: RequestInit): Promise<Response> {
  const request = input instanceof Request ? input : null;
  const url = new URL(request ? request.url : String(input), window.location.href);
  const headers = new Headers(init?.headers ?? request?.headers);
  let credential = headers.get('Authorization');
  let target = input;
  if (url.origin === window.location.origin && url.pathname.startsWith('/api/')) {
    const prefixed = new URL(appPath(url.pathname) + url.search, url.origin);
    target = request ? new Request(prefixed, request) : prefixed;
  }
  const ownApi = url.origin === window.location.origin
    && (url.pathname.startsWith('/api/') || url.pathname.startsWith(appPath('/api/')));
  const renewal = pendingRenewal;
  if (ownApi && !url.pathname.endsWith(RENEW_PATH) && renewal
      && credential === `Bearer ${renewal.token}`
      && tokenExpiresAt(renewal.token) - Date.now() <= RENEW_TIMEOUT_MS) {
    const replacement = await renewal.result.catch(() => null);
    if (replacement && getToken() === replacement) {
      credential = `Bearer ${replacement}`;
      headers.set('Authorization', credential);
      init = { ...init, headers };
    }
  }
  const response = await fetch(target, init);
  if (response.status === 401 && ownApi
      && !url.pathname.endsWith('/api/auth/login') && !url.pathname.endsWith('/api/auth/bi-login') && !url.pathname.endsWith('/api/auth/bi-account-login')) {
    const current = getToken();
    if (current && credential === `Bearer ${current}`) expireToken(current);
    throw new Error(SESSION_EXPIRED_MESSAGE);
  }
  return response;
}

export interface LoginResponse {
  token: string;
  userId: string;
  username: string;
  roles: string[];
  expiresAt: number;
}

export interface MeResponse {
  userId: string;
  username: string;
  roles: string[];
}

export interface CurrentUserProfile {
  displayName?: string;
  company?: string;
  department?: string;
}

export async function currentUserProfile(signal?: AbortSignal): Promise<CurrentUserProfile> {
  const res = await apiFetch('/api/auth/profile', {
    signal, headers: { Authorization: `Bearer ${getToken()}` },
  });
  if (!res.ok) throw new Error('暂时无法读取用户资料');
  return res.json();
}

export async function login(username: string, password: string): Promise<LoginResponse> {
  const res = await fetch(appPath(`${BASE}/api/auth/login`), {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username, password }),
  });
  if (!res.ok) throw new Error('Invalid credentials');
  return res.json();
}

export async function me(): Promise<MeResponse> {
  const res = await apiFetch(`${BASE}/api/auth/me`, {
    headers: { Authorization: `Bearer ${getToken()}` },
  });
  if (!res.ok) throw new Error('暂时无法验证登录状态，请稍后重试');
  return res.json();
}

export function getToken(): string | null {
  return sessionStorage.getItem('dataagent_token');
}

export function saveToken(token: string) {
  localStorage.removeItem('claw_token');
  sessionStorage.setItem('dataagent_token', token);
}

export function clearToken() {
  localStorage.removeItem('claw_token');
  sessionStorage.removeItem('dataagent_token');
}

/** Used for UI expiry hints only; the server verifies every protected request. */
export function tokenExpiresAt(token: string): number {
  try {
    const text = token.split('.')[1].replace(/-/g, '+').replace(/_/g, '/');
    const exp: unknown = JSON.parse(atob(text)).exp;
    return typeof exp === 'number' ? exp * 1000 : 0;
  } catch { return 0; }
}

/** Only activity in the ready page calls this endpoint. */
function renewSession(token: string, lifetime: AbortSignal): Promise<string | null> {
  if (pendingRenewal?.token === token) return pendingRenewal.result;
  const signal = AbortSignal.any([lifetime, AbortSignal.timeout(RENEW_TIMEOUT_MS)]);
  const result = apiFetch(RENEW_PATH, {
    method: 'POST', signal, cache: 'no-store', headers: { Authorization: `Bearer ${token}` },
  }).then(async response => {
    if (!response.ok) return null;
    const { token: replacement } = await response.json() as LoginResponse;
    if (signal.aborted || getToken() !== token || typeof replacement !== 'string'
        || tokenExpiresAt(replacement) <= Date.now()) return null;
    saveToken(replacement);
    return replacement;
  }).finally(() => {
    if (pendingRenewal?.result === result) pendingRenewal = undefined;
  });
  pendingRenewal = { token, result };
  return result;
}

/** Attach once while the protected page is ready. A timer alone cannot renew a session. */
export function watchSessionActivity(): () => void {
  const lifetime = new AbortController();
  const events = ['pointerdown', 'keydown', 'wheel', 'touchmove'];
  let activityAt: number | undefined;
  let renewAfter = 0;
  let busy = false;
  let expiryTimer: ReturnType<typeof setTimeout> | undefined;
  let renewalTimer: ReturnType<typeof setTimeout> | undefined;

  function checkExpiry() {
    clearTimeout(expiryTimer);
    const token = getToken();
    if (!token) return;
    const remaining = tokenExpiresAt(token) - Date.now();
    if (remaining > 0) expiryTimer = setTimeout(checkExpiry, remaining);
    else if (pendingRenewal?.token !== token) expireToken(token);
  }

  function renewAfterActivity() {
    clearTimeout(renewalTimer);
    const now = Date.now();
    if (lifetime.signal.aborted || busy || activityAt === undefined
        || document.visibilityState !== 'visible') return;
    if (now - activityAt > RENEW_TIMEOUT_MS) {
      activityAt = undefined;
      return;
    }
    const token = getToken();
    if (!token) return;
    if (tokenExpiresAt(token) <= now) {
      expireToken(token);
      return;
    }
    if (now < renewAfter && tokenExpiresAt(token) - now > RENEW_INTERVAL_MS) {
      renewalTimer = setTimeout(renewAfterActivity, renewAfter - now);
      return;
    }
    activityAt = undefined;
    busy = true;
    renewAfter = now + RENEW_INTERVAL_MS;
    void renewSession(token, lifetime.signal).catch(() => null).finally(() => {
      busy = false;
      if (!lifetime.signal.aborted) {
        checkExpiry();
        renewAfterActivity();
      }
    });
  }

  function onActivity(event: Event) {
    if (!event.isTrusted || document.visibilityState !== 'visible') return;
    activityAt = Date.now();
    renewAfterActivity();
  }

  function onVisibilityChange() {
    if (document.visibilityState !== 'visible') activityAt = undefined;
    checkExpiry();
  }

  checkExpiry();
  document.addEventListener('visibilitychange', onVisibilityChange);
  for (const event of events) {
    window.addEventListener(event, onActivity, { capture: true, passive: true });
  }
  return () => {
    lifetime.abort();
    clearTimeout(expiryTimer);
    clearTimeout(renewalTimer);
    document.removeEventListener('visibilitychange', onVisibilityChange);
    for (const event of events) window.removeEventListener(event, onActivity, true);
  };
}

/**
 * Decodes the JWT payload (base64url → JSON) without signature verification.
 * Returns null if no token or parsing fails.
 */
function decodeTokenPayload(): Record<string, unknown> | null {
  const token = getToken();
  if (!token) return null;
  try {
    const parts = token.split('.');
    if (parts.length !== 3) return null;
    const payload = parts[1].replace(/-/g, '+').replace(/_/g, '/');
    return JSON.parse(atob(payload)) as Record<string, unknown>;
  } catch {
    return null;
  }
}

/** Returns true if the current user's JWT contains the 'admin' role. */
export function isAdmin(): boolean {
  const payload = decodeTokenPayload();
  if (!payload) return false;
  const roles = payload['roles'];
  if (Array.isArray(roles)) {
    return roles.some(r => r === 'admin' || r === 'ROLE_ADMIN');
  }
  return false;
}
