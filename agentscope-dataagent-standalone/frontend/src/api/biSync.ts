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

export interface BiExtraSyncStatus {
  state: string; message: string; running: boolean; paused: boolean; hasGaps: boolean;
  action: string; lastRun: BiSyncStatus['lastRun'];
  tables: { name: string; label: string; snapshot: boolean; state: string;
    requestedFrom: string | null; availableFrom: string | null; coverageFrom: string | null;
    processedThrough: string | null; refreshedAt: string | null; rowCount: number | null;
    gapCount: number; gaps: { from: string | null; through: string | null; reason: string }[] }[];
}

export function getBiExtraSyncStatus(signal?: AbortSignal): Promise<BiExtraSyncStatus> {
  return knowledgeRequest<BiExtraSyncStatus>('/api/admin/bi-sync/extra', { signal });
}
