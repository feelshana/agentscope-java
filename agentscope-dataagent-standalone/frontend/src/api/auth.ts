const BASE = '';

export const AUTH_EXPIRED_EVENT = 'dataagent:auth-expired';
export const SESSION_EXPIRED_MESSAGE = '登录状态已失效，请重新登录';

/** Only the server's rejection of the current credential can end this browser session. */
export async function apiFetch(input: RequestInfo | URL, init?: RequestInit): Promise<Response> {
  const request = input instanceof Request ? input : null;
  const url = new URL(request ? request.url : String(input), window.location.href);
  const headers = new Headers(init?.headers ?? request?.headers);
  const credential = headers.get('Authorization');
  const response = await fetch(input, init);
  if (response.status === 401 && url.origin === window.location.origin
      && url.pathname.startsWith('/api/') && url.pathname !== '/api/auth/login') {
    const current = getToken();
    if (current && credential === `Bearer ${current}`) {
      clearToken();
      window.dispatchEvent(new Event(AUTH_EXPIRED_EVENT));
    }
    throw new Error(SESSION_EXPIRED_MESSAGE);
  }
  return response;
}

export interface LoginResponse {
  token: string;
  userId: string;
  username: string;
  roles: string[];
}

export interface MeResponse {
  userId: string;
  username: string;
  roles: string[];
}

export async function login(username: string, password: string): Promise<LoginResponse> {
  const res = await fetch(`${BASE}/api/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username, password }),
  });
  if (!res.ok) throw new Error('Invalid credentials');
  return res.json();
}

export async function me(): Promise<MeResponse> {
  const res = await apiFetch(`${BASE}/api/auth/me`, {
    headers: { Authorization: `Bearer ${localStorage.getItem('claw_token')}` },
  });
  if (!res.ok) throw new Error('暂时无法验证登录状态，请稍后重试');
  return res.json();
}

export function getToken(): string | null {
  return localStorage.getItem('claw_token');
}

export function saveToken(token: string) {
  localStorage.setItem('claw_token', token);
}

export function clearToken() {
  localStorage.removeItem('claw_token');
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
