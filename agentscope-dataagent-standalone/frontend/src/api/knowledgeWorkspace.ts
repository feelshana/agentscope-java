import { apiFetch, getToken } from './auth';
export async function knowledgeRequest<T>(url: string, init: RequestInit = {}): Promise<T> {
  const response = await apiFetch(url, { ...init, headers: { Authorization: `Bearer ${getToken()}`, 'Content-Type': 'application/json' } });
  if (!response.ok) { const body = await response.json().catch(() => ({})); throw new Error(body.message || body.detail || '操作失败，请重试'); }
  const text = await response.text(); return text ? JSON.parse(text) as T : undefined as T;
}
