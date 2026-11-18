import { knowledgeRequest } from './knowledgeWorkspace';

export type SyncGrain = 'FULL' | 'DAY' | 'MONTH';
export type SyncTlsMode = 'VERIFY_IDENTITY' | 'VERIFY_CA' | 'REQUIRED' | 'DISABLED';
export type SyncTarget = { host: string; port: number; username: string; sslMode: SyncTlsMode };
export type SyncConnection = SyncTarget & { database: string; password: string };
export type SyncColumn = { name: string; type: string; kind: string; nullable: boolean; indexed: boolean; comment: string };
export type SyncSelection = SyncConnection & {
  table: string; columns: string[]; targetTable: string; grain: SyncGrain;
  dateField: string; dateFormat: string; storageZone: string;
};
export type SyncRun = {
  id: string; kind: string; actor: string; state: 'QUEUED' | 'RUNNING' | 'SUCCESS' | 'FAILED';
  phase: string | null; date_from: string | null; date_through: string | null; progress_date: string | null;
  rows_read: number; rows_committed: number; error_code: string | null;
  queued_at: string; started_at: string | null; finished_at: string | null;
};
export type SyncTask = {
  id: string; name: string; sourceTable: string; targetTable: string; grain: SyncGrain;
  dateField: string | null; dateFormat: string | null; initialized: boolean; from: string | null;
  through: string | null; scheduleTime: string | null; nextRunAt?: string; nextPeriod?: string;
  lastRun: SyncRun | null;
};
export type SyncDetail = SyncTask & {
  runs: SyncRun[]; gaps?: { from: string; through: string }[];
  continuousThrough?: string | null; emptyPeriods?: number;
};
export type SyncList = { tasks: SyncTask[]; today: string; target?: SyncTarget; targetError?: boolean };
export type SyncPreview = { fingerprint: string; ddl: string; targetReady: boolean };

const base = '/api/admin/table-sync';
export const listSyncTasks = (signal: AbortSignal) => knowledgeRequest<SyncList>(base, { signal });
export const getSyncTask = (id: string, signal: AbortSignal) => knowledgeRequest<SyncDetail>(`${base}/${id}`, { signal });
export function syncAction<T>(action: string, data: object): Promise<T> {
  return knowledgeRequest<T>(`${base}/${action}`, { method: 'POST', body: JSON.stringify(data) });
}
