import { apiFetch, getToken } from './auth';

export type ModelProvider = 'OPENAI' | 'DEEPSEEK' | 'QWEN';
export interface ModelTest { success: boolean; revision: number; testedAt: string; durationMs: number; message: string }
export interface ModelServiceRequest {
  revision?: number; name: string; provider: ModelProvider; baseUrl: string; endpointPath: string;
  modelName: string; apiKey: string; stream: boolean; timeoutSeconds: number; temperature: number;
  disableThinking: boolean; external: boolean;
}
export interface ModelService extends Omit<ModelServiceRequest, 'apiKey' | 'revision'> {
  id: string; revision: number; keyConfigured: boolean; updatedAt: string;
  current: boolean; pendingChanges: boolean; lastTest: ModelTest | null;
}
export interface ModelServicesView {
  services: ModelService[];
  active: { id: string | null; revision: number; name: string; modelName: string; actorId: string | null; appliedAt: string | null; external: boolean };
  history: { actorId: string; appliedAt: string; fromId: string | null; toId: string; revision: number }[];
}

async function request<T>(path = '', method = 'GET', body?: unknown, signal?: AbortSignal): Promise<T> {
  const res = await apiFetch(`/api/admin/model-services${path}`, {
    method, signal, headers: { Authorization: `Bearer ${getToken()}`, 'Content-Type': 'application/json' },
    ...(body === undefined ? {} : { body: JSON.stringify(body) }),
  });
  if (!res.ok) {
    let message = res.status === 403 ? '仅全局管理员可管理大模型服务。' : `操作失败（${res.status}），请刷新后重试。`;
    try { const error = await res.json() as { detail?: string; message?: string }; message = error.detail || error.message || message; } catch { /* Use the safe fallback. */ }
    throw new Error(message);
  }
  return res.json() as Promise<T>;
}
export const listModelServices = (signal?: AbortSignal) => request<ModelServicesView>('', 'GET', undefined, signal);
export const saveModelService = (id: string | null, body: ModelServiceRequest) => request<ModelServicesView>(id ? `/${encodeURIComponent(id)}` : '', id ? 'PUT' : 'POST', body);
export const testModelService = (service: ModelService) => request<ModelTest>(`/${encodeURIComponent(service.id)}/test`, 'POST', { revision: service.revision });
export const activateModelService = (service: ModelService) => request<ModelServicesView>(`/${encodeURIComponent(service.id)}/activate`, 'POST', { revision: service.revision });
export const deleteModelService = (service: ModelService) => request<ModelServicesView>(`/${encodeURIComponent(service.id)}?revision=${service.revision}`, 'DELETE');
