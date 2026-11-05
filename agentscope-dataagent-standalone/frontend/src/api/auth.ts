import { appPath } from './paths';
import { clearImageCache } from './imageCache';
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

function expireToken(expected: string) {
  if (getToken() !== expected) return;
  clearToken();
  window.dispatchEvent(new Event(AUTH_EXPIRED_EVENT));
}

/** Clear the browser session if a protected request rejects its current credential. */
export async function apiFetch(input: RequestInfo | URL, init?: RequestInit): Promise<Response> {
  const request = input instanceof Request ? input : null;
  const url = new URL(request ? request.url : String(input), window.location.href);
  const headers = new Headers(init?.headers ?? request?.headers);
  const credential = headers.get('Authorization');
  let target = input;
  if (url.origin === window.location.origin && url.pathname.startsWith('/api/')) {
    const prefixed = new URL(appPath(url.pathname) + url.search, url.origin);
    target = request ? new Request(prefixed, request) : prefixed;
  }
  const ownApi = url.origin === window.location.origin
    && (url.pathname.startsWith('/api/') || url.pathname.startsWith(appPath('/api/')));
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
  clearImageCache();
  localStorage.removeItem('claw_token');
  sessionStorage.setItem('dataagent_token', token);
}

export function clearToken() {
  clearImageCache();
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

/** Show the sign-in entry when the current credential reaches its signed deadline. */
export function watchSessionExpiry(): () => void {
  const token = getToken();
  if (!token) return () => {};
  const timer = setTimeout(() => expireToken(token), Math.max(0, tokenExpiresAt(token) - Date.now()));
  return () => clearTimeout(timer);
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
