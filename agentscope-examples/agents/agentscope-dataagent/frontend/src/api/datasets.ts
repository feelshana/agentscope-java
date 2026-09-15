import { getToken } from './auth';

export interface DatasetColumn {
  name: string;
  originalName: string;
  sqlType: string;
  description: string | null;
}

export interface Dataset {
  id: string;
  name: string;
  groupId: string | null;
  tableName: string;
  schemaName: string;
  rowCount: number;
  description: string | null;
  sourceFileName: string | null;
  columns: DatasetColumn[];
  createdAt: string | null;
  origin?: string;
  externalDataSourceId?: string | null;
}

export interface DatasetGroup {
  id: string;
  name: string;
  description: string | null;
  datasetCount: number;
  hasDescription: boolean;
  createdAt: string | null;
}

export interface GroupDetail {
  group: DatasetGroup;
  datasets: Dataset[];
  knowledge: string;
  manifestJson: string | null;
}

export interface Preview {
  columns: DatasetColumn[];
  rows: string[][];
}

function authHeaders(): Record<string, string> {
  const token = getToken();
  return token ? { Authorization: `Bearer ${token}` } : {};
}

function jsonHeaders(): Record<string, string> {
  return { 'Content-Type': 'application/json', ...authHeaders() };
}

async function errorMessage(res: Response, fallback: string): Promise<string> {
  try {
    const body = (await res.json()) as { message?: string };
    return body.message || fallback;
  } catch {
    return fallback;
  }
}

/** Wrap fetch with an AbortController timeout. Default: 5 minutes. */
function fetchWithTimeout(
  url: string,
  init: RequestInit,
  timeoutMs = 300_000,
): Promise<Response> {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  return fetch(url, { ...init, signal: controller.signal }).finally(() => clearTimeout(timer));
}

// ---------------------------------------------------------------- groups (KB)

export async function listGroups(): Promise<DatasetGroup[]> {
  const res = await fetch('/api/dataset-groups', { headers: authHeaders() });
  if (!res.ok) throw new Error(await errorMessage(res, `Failed to list KBs: ${res.status}`));
  return res.json();
}

export async function createGroup(name: string, description: string): Promise<DatasetGroup> {
  const res = await fetch('/api/dataset-groups', {
    method: 'POST',
    headers: jsonHeaders(),
    body: JSON.stringify({ name, description }),
  });
  if (!res.ok) throw new Error(await errorMessage(res, `Failed to create KB: ${res.status}`));
  return res.json();
}

export async function getGroupDetail(groupId: string): Promise<GroupDetail> {
  const res = await fetch(`/api/dataset-groups/${encodeURIComponent(groupId)}`, {
    headers: authHeaders(),
  });
  if (!res.ok) throw new Error(await errorMessage(res, `Failed to load KB: ${res.status}`));
  return res.json();
}

export async function deleteGroup(groupId: string): Promise<void> {
  const res = await fetch(`/api/dataset-groups/${encodeURIComponent(groupId)}`, {
    method: 'DELETE',
    headers: authHeaders(),
  });
  if (!res.ok) throw new Error(await errorMessage(res, `Failed to delete KB: ${res.status}`));
}

export async function uploadKnowledge(groupId: string, file: File): Promise<string> {
  const form = new FormData();
  form.append('file', file, file.name);
  const res = await fetch(`/api/dataset-groups/${encodeURIComponent(groupId)}/knowledge`, {
    method: 'PUT',
    headers: authHeaders(),
    body: form,
  });
  if (!res.ok) throw new Error(await errorMessage(res, `Upload failed: ${res.status}`));
  const body = (await res.json()) as { content?: string };
  return body.content ?? '';
}

export interface KnowledgeDoc {
  content: string;
  updatedAt: string | null;
}

export async function getKnowledge(groupId: string): Promise<KnowledgeDoc> {
  const res = await fetch(`/api/dataset-groups/${encodeURIComponent(groupId)}/knowledge`, {
    headers: authHeaders(),
  });
  if (!res.ok) throw new Error(await errorMessage(res, `Failed to load knowledge: ${res.status}`));
  const body = (await res.json()) as { content?: string; updatedAt?: string | null };
  return { content: body.content ?? '', updatedAt: body.updatedAt ?? null };
}

// ---------------------------------------------------------------- datasets

export async function listDatasets(groupId?: string): Promise<Dataset[]> {
  const qs = groupId ? `?groupId=${encodeURIComponent(groupId)}` : '';
  const res = await fetch(`/api/datasets${qs}`, { headers: authHeaders() });
  if (!res.ok) throw new Error(await errorMessage(res, `Failed to list datasets: ${res.status}`));
  return res.json();
}

export async function uploadDataset(
  groupId: string,
  name: string,
  file: File,
  descriptionFile?: File,
  overwrite = false,
): Promise<Dataset> {
  const form = new FormData();
  form.append('file', file, file.name);
  if (descriptionFile) form.append('descriptionFile', descriptionFile, descriptionFile.name);
  // `name`/`groupId`/`overwrite` travel as query params: WebFlux @RequestParam does not bind
  // multipart fields. When overwrite is true, a same-name dataset is deleted and re-created.
  const qs = new URLSearchParams({ name, groupId, overwrite: String(overwrite) });
  let res: Response;
  try {
    res = await fetchWithTimeout(`/api/datasets?${qs.toString()}`, {
      method: 'POST',
      headers: authHeaders(),
      body: form,
    });
  } catch (e) {
    if (e instanceof DOMException && e.name === 'AbortError') {
      throw new Error('上传超时（超过 5 分钟），请检查文件是否过大或服务端日志');
    }
    throw e;
  }
  if (!res.ok) throw new Error(await errorMessage(res, `Upload failed: ${res.status}`));
  return res.json();
}

export async function deleteDataset(id: string): Promise<void> {
  const res = await fetch(`/api/datasets/${encodeURIComponent(id)}`, {
    method: 'DELETE',
    headers: authHeaders(),
  });
  if (!res.ok) throw new Error(await errorMessage(res, `Failed to delete dataset: ${res.status}`));
}

export async function getPreview(id: string, limit = 20): Promise<Preview> {
  const res = await fetch(`/api/datasets/${encodeURIComponent(id)}/preview?limit=${limit}`, {
    headers: authHeaders(),
  });
  if (!res.ok) throw new Error(await errorMessage(res, `Failed to preview: ${res.status}`));
  return res.json();
}

export async function updateColumns(
  id: string,
  updates: { name: string; description: string }[],
): Promise<Dataset> {
  const res = await fetch(`/api/datasets/${encodeURIComponent(id)}/columns`, {
    method: 'PUT',
    headers: jsonHeaders(),
    body: JSON.stringify(updates),
  });
  if (!res.ok) throw new Error(await errorMessage(res, `Failed to update columns: ${res.status}`));
  return res.json();
}

// ---------------------------------------------------------------- relationship graph

export interface GraphField {
  name: string;
  sqlType: string;
  description: string | null;
}

export interface GraphNode {
  id: string;
  label: string;
  type: string;
  fields: GraphField[];
  origin?: string | null;
}

export interface GraphEdge {
  id: string;
  source: string;
  target: string;
  relationType: string;
  confidence: number;
  label: string | null;
  origin?: string | null;
}

export interface GraphDto {
  nodes: GraphNode[];
  edges: GraphEdge[];
}

export async function getGroupGraph(groupId: string): Promise<GraphDto> {
  const res = await fetch(`/api/dataset-groups/${encodeURIComponent(groupId)}/graph`, {
    headers: authHeaders(),
  });
  if (!res.ok) throw new Error(await errorMessage(res, `Failed to load graph: ${res.status}`));
  return res.json();
}

// ---------------------------------------------------------------- description (sources.json)

export interface DescriptionInfo {
  content: string | null;
  hasDescription: boolean;
}

/** 保存数据描述文件（sources.json）到知识库。 */
export async function saveDescription(groupId: string, file: File): Promise<{ name: string; saved: boolean }> {
  const form = new FormData();
  form.append('file', file, file.name);
  const res = await fetch(`/api/dataset-groups/${encodeURIComponent(groupId)}/description`, {
    method: 'PUT',
    headers: authHeaders(),
    body: form,
  });
  if (!res.ok) throw new Error(await errorMessage(res, `Failed to save description: ${res.status}`));
  return res.json();
}

/** 获取已保存的数据描述文件内容。 */
export async function getDescription(groupId: string): Promise<DescriptionInfo> {
  const res = await fetch(`/api/dataset-groups/${encodeURIComponent(groupId)}/description`, {
    headers: authHeaders(),
  });
  if (!res.ok) throw new Error(await errorMessage(res, `Failed to get description: ${res.status}`));
  return res.json();
}

export interface ImportSummary {
  batchesImported: number;
  batchesSkipped: number;
  derivedCreated: number;
  relationshipsWritten: number;
  warnings: string[];
}

/** 上传单个数据文件（Excel），使用已保存的描述进行建表。逐文件调用以实现独立状态更新。 */
export async function uploadDataWithDescription(
  groupId: string,
  file: File,
): Promise<ImportSummary> {
  const form = new FormData();
  form.append('files', file);
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), 300_000); // 5 min
  try {
    const res = await fetch(`/api/dataset-groups/${encodeURIComponent(groupId)}/upload-data`, {
      method: 'POST',
      headers: authHeaders(),
      body: form,
      signal: controller.signal,
    });
    if (!res.ok) throw new Error(await errorMessage(res, `Upload failed: ${res.status}`));
    return res.json() as Promise<ImportSummary>;
  } catch (e) {
    if (e instanceof DOMException && e.name === 'AbortError') {
      throw new Error('上传超时（超过 5 分钟），请检查文件是否过大或服务端日志');
    }
    throw e;
  } finally {
    clearTimeout(timer);
  }
}
