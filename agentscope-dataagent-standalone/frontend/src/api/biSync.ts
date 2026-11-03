import { knowledgeRequest } from './knowledgeWorkspace';

export interface BiSyncStatus {
  state: string;
  message: string;
  running: boolean;
  paused: boolean;
  freshness: string;
  completedThrough: string | null;
  reportThrough: string | null;
  dashboardThrough: string | null;
  lastSuccessAt: string | null;
  lastRun: {
    outcome: string;
    startedAt: string | null;
    finishedAt: string | null;
    durationMs: number | null;
    message: string | null;
  } | null;
}

export function getBiSyncStatus(signal?: AbortSignal): Promise<BiSyncStatus> {
  return knowledgeRequest<BiSyncStatus>('/api/admin/bi-sync', { signal });
}
