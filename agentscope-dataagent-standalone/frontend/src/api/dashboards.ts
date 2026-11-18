import { apiFetch, getToken } from './auth';

export interface Dashboard {
  id: string; groupId: string; title: string; revision: string;
  publishedRevision: string | null; canEdit: boolean; canPublish: boolean;
  verified: boolean; html: string | null; updatedAt: string;
  fileCheck?: { status: 'UNCHECKED' | 'CHECKING' | 'PASSED' | 'FAILED' | 'STALE' | 'MODEL_CHANGED'; error: string | null } | null;
  owned: boolean;
  deletionPending: boolean;
  description?: string | null;
  createdAt?: string | null;
  creatorName?: string | null;
}

export interface DashboardGeneration {
  id: string; status: 'QUEUED' | 'RUNNING' | 'CHECKING' | 'SUCCEEDED' | 'FAILED' | 'CHECK_FAILED' | 'CANCELLED' | 'INTERRUPTED';
  phase: string; startedAt: string; finishedAt: string | null; baseRevision: string; checkpointRevision: string;
  resultRevision: string | null; autoAttempt: number; message: string; answer: string; error: string | null;
}
export const getDashboardGeneration = (id: string, signal?: AbortSignal) => request<{ task: DashboardGeneration | null; busyElsewhere: boolean; checkingFile: boolean }>(`/${encodeURIComponent(id)}/generation`, { signal });
export const startDashboardGeneration = (id: string, requestId: string, revision: string, message: string, parentRunId?: string) => request<DashboardGeneration>(`/${encodeURIComponent(id)}/generation`, { method: 'POST', body: JSON.stringify({ requestId, revision, message, parentRunId }) });
export const stopDashboardGeneration = (id: string, requestId: string) => request<DashboardGeneration>(`/${encodeURIComponent(id)}/generation/stop`, { method: 'POST', body: JSON.stringify({ requestId }) });

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
export const updateDashboardMetadata = (dashboard: Dashboard, title: string, description: string) => request<Dashboard>(`/${encodeURIComponent(dashboard.id)}`, {
  method: 'PATCH', body: JSON.stringify({ title, description, expectedUpdatedAt: dashboard.updatedAt }),
});
export const deleteDashboard = (id: string) => request<void>(`/${encodeURIComponent(id)}`, { method: 'DELETE' });
export const createDashboard = (groupId: string, title: string, description = '') => request<Dashboard>('', {
  method: 'POST', body: JSON.stringify({ groupId, title, description }),
});
export const getDashboard = (id: string, draft: boolean) => request<Dashboard>(`/${encodeURIComponent(id)}?draft=${draft}`);
export const checkDashboard = (dashboard: Dashboard) => request<Dashboard>(`/${encodeURIComponent(dashboard.id)}/check`, { method: 'POST', body: JSON.stringify({ revision: dashboard.revision }) });
export const checkpointDashboard = (dashboard: Dashboard) => request<{ revision: string }>(`/${encodeURIComponent(dashboard.id)}/checkpoint`, { method: 'POST', body: JSON.stringify({ revision: dashboard.revision }) });
export const restoreDashboard = (dashboard: Dashboard, targetRevision: string) => request<Dashboard>(`/${encodeURIComponent(dashboard.id)}/restore`, { method: 'POST', body: JSON.stringify({ revision: dashboard.revision, targetRevision }) });
export const publishDashboard = (id: string, revision: string) => request<Dashboard>(`/${encodeURIComponent(id)}/publish`, {
  method: 'POST', body: JSON.stringify({ revision }),
});
export const queryDashboard = (id: string, draft: boolean, revision: string, sql: string, signal: AbortSignal) => request<unknown>(`/${encodeURIComponent(id)}/query`, {
  method: 'POST', body: JSON.stringify({ draft, revision, sql }), signal,
});
