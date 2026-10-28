import { apiFetch, getToken } from './auth';

export type KnowledgeBaseRole = 'QUERY' | 'MANAGE';
export interface KnowledgeBaseMember {
  userId: string; username: string; role: KnowledgeBaseRole | null;
  owner: boolean; admin: boolean; updatedAt: string | null; displayName?: string; company?: string; department?: string;
}
export interface MemberPage { creator?: KnowledgeBaseMember; items: KnowledgeBaseMember[]; total: number; page: number; size: number }

async function request<T>(url: string, init: RequestInit = {}): Promise<T> {
  const response = await apiFetch(url, { ...init, headers: { Authorization: `Bearer ${getToken()}`, 'Content-Type': 'application/json' } });
  if (!response.ok) {
    const detail = await response.json().catch(() => ({}));
    throw new Error(detail.message || detail.detail || '成员操作失败，请刷新后重试');
  }
  return response.status === 204 || response.headers.get('content-length') === '0' ? undefined as T
    : await response.text().then(text => text ? JSON.parse(text) as T : undefined as T);
}

const base = (groupId: string) => `/api/dataset-groups/${encodeURIComponent(groupId)}/members`;
export const listMembers = (groupId: string, page: number, signal?: AbortSignal) =>
  request<MemberPage>(`${base(groupId)}?page=${page}&size=20`, { signal });
export const searchMemberUsers = (groupId: string, search: string, page: number, signal?: AbortSignal, company = '', department = '', size = 20) =>
  request<MemberPage>(`${base(groupId)}/users?${new URLSearchParams({ search, company, department, page: String(page), size: String(size) })}`, { signal });
export const grantMember = (groupId: string, userId: string, role: KnowledgeBaseRole) =>
  request<void>(`${base(groupId)}/${encodeURIComponent(userId)}`, { method: 'PUT', body: JSON.stringify({ role }) });
export const revokeMember = (groupId: string, userId: string) =>
  request<void>(`${base(groupId)}/${encodeURIComponent(userId)}`, { method: 'DELETE' });

export const memberFilters = (groupId: string, company: string, signal?: AbortSignal) => request<{ companies: string[]; departments: string[] }>(`${base(groupId)}/filters?${new URLSearchParams({ company })}`, { signal });
export const grantMembers = (groupId: string, userIds: string[], role: KnowledgeBaseRole) => request<void>(`${base(groupId)}/batch`, { method: 'PUT', body: JSON.stringify({ userIds, role }) });
