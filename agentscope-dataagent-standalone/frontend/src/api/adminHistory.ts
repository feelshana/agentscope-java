import { apiFetch, getToken } from './auth';

export interface AdminDirectoryUser {
  userId: string;
  username: string;
  displayName: string | null;
  department: string | null;
}

export interface AdminHistorySession {
  sessionKey: string;
  agentId: string;
  title: string | null;
  lastActivityMs: number;
  hasMessages: boolean;
  owner: AdminDirectoryUser;
}

export interface AdminConversationTurn {
  position: number;
  askedAtMs: number;
  question: string | null;
  answer: string | null;
  answeredAtMs: number | null;
}

export interface AdminHistoryFilters {
  title: string;
  from: string;
  to: string;
}

const administrators = '/api/admin/administrators';
const history = '/api/admin/conversation-history';

async function request(path: string, signal?: AbortSignal, method = 'GET') {
  const response = await apiFetch(path, {
    headers: { Authorization: `Bearer ${getToken()}` }, signal, method, cache: 'no-store',
  });
  if (response.ok) return response;
  if (response.status === 403) throw new Error('需要全局管理员权限，请重新登录后确认权限。');
  if (response.status === 404) throw new Error('该记录不存在，可能已被删除。');
  if (response.status === 409) throw new Error('不能移除最后一位全局管理员。');
  if (response.status === 400) throw new Error('请检查搜索词或日期范围。');
  throw new Error('操作失败，请稍后重试。');
}

export async function searchAdminUsers(term: string, signal?: AbortSignal): Promise<AdminDirectoryUser[]> {
  return (await request(`${administrators}/search-users?q=${encodeURIComponent(term)}`, signal)).json();
}

export async function listGlobalAdministrators(signal?: AbortSignal): Promise<AdminDirectoryUser[]> {
  return (await request(administrators, signal)).json();
}

export async function setGlobalAdministrator(userId: string, enabled: boolean) {
  await request(`${administrators}/${encodeURIComponent(userId)}`, undefined, enabled ? 'PUT' : 'DELETE');
}

export async function listAdminHistory(
  userId: string | undefined, filters: AdminHistoryFilters, cursor?: AdminHistorySession, signal?: AbortSignal,
): Promise<{ items: AdminHistorySession[]; hasMore: boolean }> {
  const params = new URLSearchParams();
  if (userId) params.set('userId', userId);
  for (const [key, value] of Object.entries(filters)) if (value) params.set(key, value);
  if (cursor) {
    params.set('before', String(cursor.lastActivityMs));
    params.set('beforeKey', cursor.sessionKey);
  }
  return (await request(`${history}/sessions?${params}`, signal)).json();
}

export async function getAdminConversation(
  userId: string, key: string, before?: number, signal?: AbortSignal,
): Promise<{ items: AdminConversationTurn[]; nextBefore: number | null }> {
  const params = new URLSearchParams({ sessionKey: key });
  if (before != null) params.set('before', String(before));
  return (await request(`${history}/${encodeURIComponent(userId)}/messages?${params}`, signal)).json();
}
