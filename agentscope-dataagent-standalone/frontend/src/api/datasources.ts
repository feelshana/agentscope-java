import { apiFetch, getToken } from './auth';

export interface ExternalDataSource {
  id: string;
  name: string;
  kind: string;
  jdbcUrl: string | null;
  username: string | null;
  sampling: boolean;
  /** spec105: the shared read-only demo source; no connection details are returned for it. */
  builtin?: boolean;
  createdAt: string | null;
  ownerId?: string;
  creatorName?: string;
  creatorAccount?: string;
  role?: 'OWNER' | 'USE' | 'MANAGE';
  canManage?: boolean;
  canGovern?: boolean;
  enabled?: boolean;
  shared?: boolean;
  knowledgeBaseCount?: number;
  targetLocked?: boolean;
}

export type DataSourceRole = 'USE' | 'MANAGE';
export interface DataSourceMember { userId: string; username: string; displayName: string | null; department: string | null; role: DataSourceRole | 'OWNER' | null; fixed: boolean }
export interface DataSourceMembers { items: DataSourceMember[]; total: number; page: number; size: number; creator: DataSourceMember | null }
export const dataSourceRoleLabel = (role?: string | null) => role === 'OWNER' ? '创建人' : role === 'MANAGE' ? '可管理' : role === 'USE' ? '可使用' : '未授权';

export async function dataSourceMembers(id: string, page = 0, signal?: AbortSignal): Promise<DataSourceMembers> {
  const res = await apiFetch(`/api/datasources/${encodeURIComponent(id)}/members?page=${page}`, { headers: jsonHeaders(), signal });
  if (!res.ok) throw new Error(await errorMessage(res, '无法读取数据源成员'));
  return res.json();
}
export async function dataSourceMemberUsers(id: string, search: string, page = 0, signal?: AbortSignal): Promise<DataSourceMembers> {
  const res = await apiFetch(`/api/datasources/${encodeURIComponent(id)}/member-users?${new URLSearchParams({ search, page: String(page) })}`, { headers: jsonHeaders(), signal });
  if (!res.ok) throw new Error(await errorMessage(res, '无法搜索用户'));
  return res.json();
}
export async function grantDataSourceMember(id: string, userId: string, role: DataSourceRole | null): Promise<void> {
  const res = await apiFetch(`/api/datasources/${encodeURIComponent(id)}/members/${encodeURIComponent(userId)}`, {
    method: role ? 'PUT' : 'DELETE', headers: jsonHeaders(), ...(role ? { body: JSON.stringify({ role }) } : {}),
  });
  if (!res.ok) throw new Error(await errorMessage(res, '数据源权限修改失败'));
}
export async function setDataSourceEnabled(id: string, enabled: boolean): Promise<ExternalDataSource> {
  const res = await apiFetch(`/api/datasources/${encodeURIComponent(id)}/enabled`, { method: 'PUT', headers: jsonHeaders(), body: JSON.stringify({ enabled }) });
  if (!res.ok) throw new Error(await errorMessage(res, '数据源状态修改失败'));
  return res.json();
}

export interface DataSourceRequest {
  name: string;
  kind?: string;
  jdbcUrl?: string;
  host?: string;
  port?: number;
  database?: string;
  sslMode?: string;
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
    const body = (await res.json()) as { message?: string; detail?: string };
    return body.message || body.detail || fallback;
  } catch {
    return fallback;
  }
}

function saveErrorFallback(res: Response, action: string): string {
  return res.status === 403
    ? '操作被拒绝：请确认数据源权限，或联系管理员检查数据源允许列表'
    : `${action}数据源失败（${res.status}）`;
}

export async function listDataSources(): Promise<ExternalDataSource[]> {
  const res = await apiFetch('/api/datasources', { headers: jsonHeaders() });
  if (!res.ok) throw new Error(await errorMessage(res, `Failed to list data sources: ${res.status}`));
  return res.json();
}

export async function createDataSource(req: DataSourceRequest): Promise<ExternalDataSource> {
  const res = await apiFetch('/api/datasources', {
    method: 'POST',
    headers: jsonHeaders(),
    body: JSON.stringify(req),
  });
  if (!res.ok) throw new Error(await errorMessage(res, saveErrorFallback(res, '添加')));
  return res.json();
}

export async function updateDataSource(
  id: string,
  req: DataSourceRequest,
): Promise<ExternalDataSource> {
  const res = await apiFetch(`/api/datasources/${encodeURIComponent(id)}`, {
    method: 'PUT',
    headers: jsonHeaders(),
    body: JSON.stringify(req),
  });
  if (!res.ok) throw new Error(await errorMessage(res, saveErrorFallback(res, '更新')));
  return res.json();
}

export async function deleteDataSource(id: string): Promise<void> {
  const res = await apiFetch(`/api/datasources/${encodeURIComponent(id)}`, {
    method: 'DELETE',
    headers: jsonHeaders(),
  });
  if (!res.ok) throw new Error(await errorMessage(res, `Failed to delete data source: ${res.status}`));
}

export async function getDataSourceStatus(id: string): Promise<DataSourceStatus> {
  const res = await apiFetch(`/api/datasources/${encodeURIComponent(id)}/status`, {
    headers: jsonHeaders(),
  });
  if (!res.ok) throw new Error(await errorMessage(res, `Failed to check status: ${res.status}`));
  return res.json();
}

export async function testDataSourceConnection(
  req: DataSourceRequest,
  sourceId?: string | null,
): Promise<DataSourceStatus> {
  const query = sourceId ? `?sourceId=${encodeURIComponent(sourceId)}` : '';
  const res = await apiFetch(`/api/datasources/test-connection${query}`, {
    method: 'POST',
    headers: jsonHeaders(),
    body: JSON.stringify(req),
  });
  if (!res.ok) throw new Error(await errorMessage(res, `测试连接失败（${res.status}）`));
  return res.json();
}

export async function listSchemas(id: string): Promise<string[]> {
  const res = await apiFetch(`/api/datasources/${encodeURIComponent(id)}/schemas`, {
    headers: jsonHeaders(),
  });
  if (!res.ok) throw new Error(await errorMessage(res, `Failed to list schemas: ${res.status}`));
  return res.json();
}

export async function listTables(id: string, schema: string): Promise<SchemaTable[]> {
  const res = await apiFetch(
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
  const res = await apiFetch(
    `/api/datasources/${encodeURIComponent(id)}/schemas/${encodeURIComponent(schema)}/tables/${encodeURIComponent(table)}/columns`,
    { headers: jsonHeaders() },
  );
  if (!res.ok) throw new Error(await errorMessage(res, `Failed to list columns: ${res.status}`));
  return res.json();
}

export async function associateTables(
  groupId: string,
  payload: { dataSourceId: string; schema: string; tables: string[]; sampling?: boolean; publish?: boolean },
): Promise<unknown[]> {
  const res = await apiFetch(`/api/dataset-groups/${encodeURIComponent(groupId)}/associate`, {
    method: 'POST',
    headers: jsonHeaders(),
    body: JSON.stringify(payload),
  });
  if (!res.ok) throw new Error(await errorMessage(res, `Failed to associate tables: ${res.status}`));
  return res.json();
}
