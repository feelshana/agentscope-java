import { getToken } from './auth';

export interface ExternalDataSource {
  id: string;
  name: string;
  kind: string;
  jdbcUrl: string;
  username: string | null;
  sampling: boolean;
  createdAt: string | null;
}

export interface DataSourceRequest {
  name: string;
  kind?: string;
  jdbcUrl: string;
  username?: string;
  password?: string;
  sampling?: boolean;
}

export interface DataSourceStatus {
  connected: boolean;
  error: string | null;
}

export interface SchemaTable {
  name: string;
  type: string;
  comment?: string | null;
}

export interface SchemaColumn {
  name: string;
  type: string;
  description: string | null;
}

function jsonHeaders(): Record<string, string> {
  const token = getToken();
  return {
    'Content-Type': 'application/json',
    ...(token ? { Authorization: `Bearer ${token}` } : {}),
  };
}

async function errorMessage(res: Response, fallback: string): Promise<string> {
  try {
    const body = (await res.json()) as { message?: string };
    return body.message || fallback;
  } catch {
    return fallback;
  }
}

export async function listDataSources(): Promise<ExternalDataSource[]> {
  const res = await fetch('/api/datasources', { headers: jsonHeaders() });
  if (!res.ok) throw new Error(await errorMessage(res, `Failed to list data sources: ${res.status}`));
  return res.json();
}

export async function createDataSource(req: DataSourceRequest): Promise<ExternalDataSource> {
  const res = await fetch('/api/datasources', {
    method: 'POST',
    headers: jsonHeaders(),
    body: JSON.stringify(req),
  });
  if (!res.ok) throw new Error(await errorMessage(res, `Failed to create data source: ${res.status}`));
  return res.json();
}

export async function updateDataSource(
  id: string,
  req: DataSourceRequest,
): Promise<ExternalDataSource> {
  const res = await fetch(`/api/datasources/${encodeURIComponent(id)}`, {
    method: 'PUT',
    headers: jsonHeaders(),
    body: JSON.stringify(req),
  });
  if (!res.ok) throw new Error(await errorMessage(res, `Failed to update data source: ${res.status}`));
  return res.json();
}

export async function deleteDataSource(id: string): Promise<void> {
  const res = await fetch(`/api/datasources/${encodeURIComponent(id)}`, {
    method: 'DELETE',
    headers: jsonHeaders(),
  });
  if (!res.ok) throw new Error(await errorMessage(res, `Failed to delete data source: ${res.status}`));
}

export async function getDataSourceStatus(id: string): Promise<DataSourceStatus> {
  const res = await fetch(`/api/datasources/${encodeURIComponent(id)}/status`, {
    headers: jsonHeaders(),
  });
  if (!res.ok) throw new Error(await errorMessage(res, `Failed to check status: ${res.status}`));
  return res.json();
}

export async function listSchemas(id: string): Promise<string[]> {
  const res = await fetch(`/api/datasources/${encodeURIComponent(id)}/schemas`, {
    headers: jsonHeaders(),
  });
  if (!res.ok) throw new Error(await errorMessage(res, `Failed to list schemas: ${res.status}`));
  return res.json();
}

export async function listTables(id: string, schema: string): Promise<SchemaTable[]> {
  const res = await fetch(
    `/api/datasources/${encodeURIComponent(id)}/schemas/${encodeURIComponent(schema)}/tables`,
    { headers: jsonHeaders() },
  );
  if (!res.ok) throw new Error(await errorMessage(res, `Failed to list tables: ${res.status}`));
  return res.json();
}

export async function listColumns(
  id: string,
  schema: string,
  table: string,
): Promise<SchemaColumn[]> {
  const res = await fetch(
    `/api/datasources/${encodeURIComponent(id)}/schemas/${encodeURIComponent(schema)}/tables/${encodeURIComponent(table)}/columns`,
    { headers: jsonHeaders() },
  );
  if (!res.ok) throw new Error(await errorMessage(res, `Failed to list columns: ${res.status}`));
  return res.json();
}

export async function associateTables(
  groupId: string,
  payload: { dataSourceId: string; schema: string; tables: string[]; sampling?: boolean },
): Promise<unknown[]> {
  const res = await fetch(`/api/dataset-groups/${encodeURIComponent(groupId)}/associate`, {
    method: 'POST',
    headers: jsonHeaders(),
    body: JSON.stringify(payload),
  });
  if (!res.ok) throw new Error(await errorMessage(res, `Failed to associate tables: ${res.status}`));
  return res.json();
}
