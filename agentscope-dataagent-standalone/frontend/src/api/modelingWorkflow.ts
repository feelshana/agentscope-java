import { apiFetch, getToken } from './auth';

export interface ModelingWorkflow {
  groupId: string;
  stage: 'DATA_PREPARATION' | 'MODELING' | 'VALIDATION' | 'CONFIRMATION' | 'PUBLICATION' | 'COMPLETE';
  mdlState: string;
  publishedVersion: number;
  queryAvailable: boolean;
  draftChanged: boolean;
  engineeringStatus: 'PASSED' | 'FAILED' | 'NOT_CHECKED';
  engineeringCheckedAt: string | null;
  questionSummary: {
    total: number; confirmed: number; incomplete: number;
    needsValidation: number; awaitingConfirmation: number;
  };
  changedAssets: { path: string; label: string; kind: 'ADDED' | 'MODIFIED' | 'REMOVED' }[];
  blockers: { code: string; questionId: string | null; message: string }[];
  nextAction: { type: string; label: string; message: string };
  canPublish: boolean;
  /** spec092: confirmed questions whose model changed since confirmation; a re-check is suggested. */
  affectedQuestionIds: string[];
}

export interface FactQueryWindowCandidate {
  datasetId: string;
  tableName: string;
  temporalColumns: string[];
  timeColumn: string | null;
  tableRole: 'FACT' | 'DIMENSION' | 'UNKNOWN';
  mode: 'LIMITED' | 'UNLIMITED' | null;
  windowDays: number | null;
}

export async function getFactQueryWindowCandidates(groupId: string): Promise<FactQueryWindowCandidate[]> {
  const token = getToken();
  const response = await apiFetch(`/api/dataset-groups/${encodeURIComponent(groupId)}/fact-query-windows`, { headers: token ? { Authorization: `Bearer ${token}` } : {} });
  if (!response.ok) throw new Error(`加载事实表时间范围失败（${response.status}）`);
  return response.json() as Promise<FactQueryWindowCandidate[]>;
}

export async function saveFactQueryWindow(groupId: string, value: { datasetId: string; mode: 'LIMITED' | 'UNLIMITED'; days?: number; timeColumn?: string }): Promise<FactQueryWindowCandidate> {
  const token = getToken();
  const response = await apiFetch(`/api/dataset-groups/${encodeURIComponent(groupId)}/fact-query-windows`, { method: 'PUT', headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: `Bearer ${token}` } : {}) }, body: JSON.stringify(value) });
  if (!response.ok) throw new Error(`保存事实表时间范围失败（${response.status}）`);
  return response.json() as Promise<FactQueryWindowCandidate>;
}


export async function getModelingWorkflow(groupId: string): Promise<ModelingWorkflow> {
  const token = getToken();
  const response = await apiFetch(`/api/dataset-groups/${encodeURIComponent(groupId)}/modeling/workflow`, { headers: token ? { Authorization: `Bearer ${token}` } : {} });
  if (!response.ok) throw new Error(`加载建模状态失败（${response.status}）`);
  return response.json() as Promise<ModelingWorkflow>;
}

export async function confirmTableRole(groupId: string, datasetId: string, role: 'FACT' | 'DIMENSION'): Promise<void> {
  const token = getToken();
  const response = await apiFetch(`/api/dataset-groups/${encodeURIComponent(groupId)}/fact-query-windows/table-role`, {
    method: 'PUT', headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: `Bearer ${token}` } : {}) },
    body: JSON.stringify({ datasetId, role }),
  });
  if (!response.ok) throw new Error(`保存表角色失败（${response.status}）`);
}


export interface QueryPreparationStatus {
  state: 'NOT_READY' | 'QUEUED' | 'INDEXING' | 'WARMING' | 'READY' | 'FAILED';
  version: number;
  updatedAt: string | null;
  message: string;
}

/** User-facing text for a preparation that is still running; null once it is ready or failed. */
export function preparationPhase(state: QueryPreparationStatus['state']): string | null {
  if (state === 'QUEUED') return '排队准备';
  if (state === 'INDEXING') return '正在建立检索索引';
  if (state === 'WARMING') return '正在预热检索';
  return null;
}

export async function getQueryPreparation(groupId: string): Promise<QueryPreparationStatus> {
  const token = getToken();
  const response = await apiFetch(`/api/dataset-groups/${encodeURIComponent(groupId)}/query-preparation`, { headers: token ? { Authorization: `Bearer ${token}` } : {} });
  if (!response.ok) throw new Error(`加载检索准备状态失败（${response.status}）`);
  return response.json() as Promise<QueryPreparationStatus>;
}
