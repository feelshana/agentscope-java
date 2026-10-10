import { getToken } from './auth';

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
}

export async function getModelingWorkflow(groupId: string): Promise<ModelingWorkflow> {
  const token = getToken();
  const response = await fetch(
    `/api/dataset-groups/${encodeURIComponent(groupId)}/modeling/workflow`,
    { headers: token ? { Authorization: `Bearer ${token}` } : {} },
  );
  if (!response.ok) {
    const detail = await response.json().catch(() => ({})) as { message?: string; detail?: string };
    throw new Error(detail.message || detail.detail || `加载建模进度失败（${response.status}）`);
  }
  return response.json() as Promise<ModelingWorkflow>;
}
