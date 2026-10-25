import { apiFetch, getToken } from './auth';

/** spec093: one column on the field screening page. */
export interface ScreeningField {
  name: string;
  originalName: string | null;
  sqlType: string | null;
  samples: string[];
  nullCount: number | null;
  role: 'BUSINESS' | 'TECHNICAL' | 'UNKNOWN';
  reason: string | null;
  included: boolean;
  description: string | null;
  userDescribed: boolean;
  /** The fact table's time column: must stay included. */
  locked: boolean;
  /** The same name exists in another table: probably a join key. */
  joinKey: boolean;
}

export interface ScreeningTable {
  datasetId: string;
  name: string;
  origin: string;
  tableRole: string | null;
  sampledRows: number;
  confirmed: boolean;
  fields: ScreeningField[];
}

export interface ScreeningStatus { datasetId: string; name: string; confirmed: boolean; included: number; total: number }

export interface FieldUpdate { name: string; included?: boolean; description?: string }

export interface ScreeningReference { path: string; line: string }

export interface ScreeningSaveResult { table: ScreeningTable; remodel: boolean; references: ScreeningReference[] }

const base = (groupId: string) => `/api/dataset-groups/${encodeURIComponent(groupId)}/fields`;

function headers(json = false): Record<string, string> {
  const token = getToken();
  return { ...(json ? { 'Content-Type': 'application/json' } : {}), ...(token ? { Authorization: `Bearer ${token}` } : {}) };
}

async function read<T>(response: Response, fallback: string): Promise<T> {
  if (!response.ok) {
    const detail = await response.json().catch(() => ({})) as { message?: string; detail?: string };
    throw new Error(detail.message || detail.detail || `${fallback}（${response.status}）`);
  }
  return response.json() as Promise<T>;
}

export async function getScreeningStatus(groupId: string): Promise<ScreeningStatus[]> {
  return read(await apiFetch(base(groupId), { headers: headers() }), '读取字段整理状态失败');
}

/** The first visit of an unscreened table asks the model for field roles (may take a few seconds). */
export async function getScreeningTable(groupId: string, datasetId: string): Promise<ScreeningTable> {
  return read(await apiFetch(`${base(groupId)}/${encodeURIComponent(datasetId)}`, { headers: headers() }), '读取字段失败');
}

/** {@code force} acknowledges the references listed by a previous save. */
export async function saveScreening(groupId: string, datasetId: string, updates: FieldUpdate[], confirm: boolean, force = false): Promise<ScreeningSaveResult> {
  return read(await apiFetch(`${base(groupId)}/${encodeURIComponent(datasetId)}`, {
    method: 'PUT', headers: headers(true), body: JSON.stringify({ updates, confirm, force }),
  }), '保存字段整理失败');
}
