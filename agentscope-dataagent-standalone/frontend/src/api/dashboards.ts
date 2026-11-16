import { apiFetch, getToken } from './auth';

export interface Dashboard {
  id: string; groupId: string; title: string; revision: string;
  publishedRevision: string | null; canEdit: boolean; canPublish: boolean;
  verified: boolean; html: string | null; updatedAt: string;
  owned: boolean;
  deletionPending: boolean;
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await apiFetch(`/api/dashboards${path}`, {
    ...init, headers: { Authorization: `Bearer ${getToken()}`, 'Content-Type': 'application/json' },
  });
  if (!response.ok) {
    const error = await response.json().catch(() => ({})) as { message?: string; detail?: string; error?: string };
    throw new Error(error.message || error.detail || error.error || `操作失败（${response.status}）`);
  }
  return response.status === 204 ? undefined as T : response.json() as Promise<T>;
}

export const listDashboards = () => request<Dashboard[]>('');
export const deleteDashboard = (id: string) => request<void>(`/${encodeURIComponent(id)}`, { method: 'DELETE' });
export const createDashboard = (groupId: string, title: string) => request<Dashboard>('', {
  method: 'POST', body: JSON.stringify({ groupId, title }),
});
export const getDashboard = (id: string, draft: boolean) => request<Dashboard>(`/${encodeURIComponent(id)}?draft=${draft}`);
export const publishDashboard = (id: string, revision: string) => request<Dashboard>(`/${encodeURIComponent(id)}/publish`, {
  method: 'POST', body: JSON.stringify({ revision }),
});
export const queryDashboard = (id: string, draft: boolean, revision: string, sql: string, signal: AbortSignal) => request<unknown>(`/${encodeURIComponent(id)}/query`, {
  method: 'POST', body: JSON.stringify({ draft, revision, sql }), signal,
});
