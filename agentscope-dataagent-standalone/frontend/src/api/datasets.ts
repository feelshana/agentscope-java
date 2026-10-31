import { apiFetch, getToken } from './auth';

export interface DatasetColumn {
  name: string;
  originalName: string;
  sqlType: string;
  description: string | null;
  sourceFormat?: string | null;
}

export interface Dataset {
  id: string;
  name: string;
  groupId: string | null;
  tableName: string;
  schemaName: string;
  rowCount: number | null;
  sampling?: { status: string; sampledAt: string; sampledRows: number; rowLimit: number } | null;
  description: string | null;
  sourceFileName: string | null;
  fileArtifactId: string | null;
  columns: DatasetColumn[];
  createdAt: string | null;
  origin?: string;
  externalDataSourceId?: string | null;
  currentVersion: number;
  commentSyncStatus?: string | null;
  commentSyncError?: string | null;
  commentSyncedAt?: string | null;
}

export interface DatasetGroup {
  role: 'QUERY' | 'MANAGE';
  canManage: boolean;
  canQuery: boolean;
  deletionPending?: boolean;
  ownerUsername?: string | null;
  id: string;
  artifactId: string | null;
  name: string;
  description: string | null;
  datasetCount: number;
  hasDescription: boolean;
  createdAt: string | null;
}

export interface GroupDetail {
  publishedVersion?: number;
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

export async function downloadQueryCsv(artifactId: string): Promise<void> {
  const res = await apiFetch(`/api/datasets/query-results/${encodeURIComponent(artifactId)}/csv`, {
    headers: authHeaders(),
  });
  if (!res.ok) throw new Error(await errorMessage(res, '查询结果下载失败'));
  const url = URL.createObjectURL(await res.blob());
  const link = document.createElement('a');
  link.href = url;
  link.download = `${artifactId}.csv`;
  document.body.appendChild(link);
  link.click();
  link.remove();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}

async function errorMessage(res: Response, fallback: string): Promise<string> {
  try {
    const body = (await res.json()) as { message?: unknown; detail?: unknown };
    if (typeof body.message === 'string' && body.message.trim()) return body.message;
    if (typeof body.detail === 'string' && body.detail.trim()) return body.detail;
    return fallback;
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
  return apiFetch(url, { ...init, signal: controller.signal }).finally(() => clearTimeout(timer));
}

export interface UploadPolicy {
  maxFileBytes: number; maxSelectionBytes: number; maxFiles: number;
  maxRows: number; maxColumns: number; maxCells: number; concurrentFiles: number;
  statusAvailable?: boolean;
}
export class DatasetUploadError extends Error {
  constructor(message: string, public code: string, public datasetId?: string) { super(message); }
}
let uploadPolicyPromise: Promise<UploadPolicy> | undefined;
export function getUploadPolicy(): Promise<UploadPolicy> {
  return uploadPolicyPromise ??= (async () => {
    const res = await apiFetch('/api/datasets/upload-policy', { headers: authHeaders() });
    // Allows the development UI to remain usable until the local backend is restarted.
    if (res.status === 404 || res.status === 405) return { maxFileBytes: 50 * 1024 ** 2, maxSelectionBytes: 100 * 1024 ** 2,
      maxFiles: 10, maxRows: 500000, maxColumns: 100, maxCells: 10000000, concurrentFiles: 8, statusAvailable: false };
    if (!res.ok) throw new Error(await errorMessage(res, '读取上传限制失败'));
    return { ...await res.json(), statusAvailable: true };
  })().catch(error => { uploadPolicyPromise = undefined; throw error; });
}
export interface ServerUploadStatus {
  uploadId: string; name: string; fileName: string; phase: string; datasetId?: string; error?: string; active: boolean;
}
export async function getServerUploadStatuses(groupId: string): Promise<ServerUploadStatus[]> {
  const res = await apiFetch(`/api/datasets/upload-status?groupId=${encodeURIComponent(groupId)}`, { headers: authHeaders() });
  if (!res.ok) throw new Error(await errorMessage(res, '读取上传状态失败'));
  return res.json();
}
export function validateUploadSelection(files: File[], policy: UploadPolicy): void {
  if (!files.length || files.length > policy.maxFiles) throw new Error(`一次最多选择 ${policy.maxFiles} 个文件`);
  if (files.reduce((sum, f) => sum + f.size, 0) > policy.maxSelectionBytes) throw new Error(`本次文件总大小不能超过 ${policy.maxSelectionBytes / 1024 ** 2} MB`);
  const names = new Set<string>();
  for (const file of files) {
    if (!/\.(xlsx|xls|csv)$/i.test(file.name)) throw new Error(`不支持的文件格式：${file.name}`);
    if (file.size > policy.maxFileBytes) throw new Error(`${file.name} 超过单文件 ${policy.maxFileBytes / 1024 ** 2} MB 限制`);
    const name = file.name.replace(/\.[^.]+$/, '').trim().toLowerCase();
    if (names.has(name)) throw new Error(`本次选择包含同名数据集：${file.name}`);
    names.add(name);
  }
}

// ---------------------------------------------------------------- sheets

export async function listSheets(file: File): Promise<string[]> {
  const form = new FormData();
  form.append('file', file, file.name);
  const res = await apiFetch('/api/datasets/list-sheets', {
    method: 'POST',
    headers: authHeaders(),
    body: form,
  });
  if (!res.ok) throw new Error(await errorMessage(res, `Failed to list sheets: ${res.status}`));
  return res.json();
}

// ---------------------------------------------------------------- groups (KB)

export interface GroupPage { items: DatasetGroup[]; total: number; page: number; size: number }
export interface GroupListOptions { page?: number; size?: number; search?: string; sort?: string; manageOnly?: boolean }

export async function listGroupCards(ids: string[], signal?: AbortSignal): Promise<DatasetGroup[]> {
  if (!ids.length) return [];
  const response = await apiFetch(`/api/dataset-groups/cards?${new URLSearchParams({ ids: ids.join(',') })}`, { headers: authHeaders(), signal });
  if (!response.ok) throw new Error(await errorMessage(response, '所选知识库不存在或未授权'));
  return response.json();
}

export async function listGroupPage(options: GroupListOptions = {}, signal?: AbortSignal): Promise<GroupPage> {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), 30_000);
  const abort = () => controller.abort();
  if (signal?.aborted) controller.abort();
  signal?.addEventListener('abort', abort, { once: true });
  try {
    const params = new URLSearchParams({ page: String(options.page ?? 0), size: String(options.size ?? 20),
      search: options.search ?? '', sort: options.sort ?? 'date', manageOnly: String(options.manageOnly ?? false) });
    const res = await apiFetch(`/api/dataset-groups/page?${params}`, {
      headers: authHeaders(),
      signal: controller.signal,
    });
    if (!res.ok) throw new Error(await errorMessage(res, `Failed to list KBs: ${res.status}`));
    return res.json();
  } catch (e) {
    if (signal?.aborted) throw e;
    if (e instanceof DOMException && e.name === 'AbortError') {
      throw new Error('加载知识库列表超时，请刷新重试');
    }
    throw e;
  } finally {
    clearTimeout(timer);
    signal?.removeEventListener('abort', abort);
  }
}

/** Compatibility for the business-term selector; the card page and chat picker load one page. */
export async function listGroups(signal?: AbortSignal, manageOnly = false): Promise<DatasetGroup[]> {
  const all: DatasetGroup[] = [];
  for (let page = 0; ; page++) {
    const result = await listGroupPage({ page, size: 100, manageOnly }, signal);
    all.push(...result.items);
    if (!result.items.length || (page + 1) * result.size >= result.total) return all;
  }
}

export async function createGroup(name: string, description: string): Promise<DatasetGroup> {
  const res = await apiFetch('/api/dataset-groups', {
    method: 'POST',
    headers: jsonHeaders(),
    body: JSON.stringify({ name, description }),
  });
  if (!res.ok) {
    const fallback = res.status === 409
      ? `知识库「${name}」已存在，请更换名称，或取消后打开已有知识库。`
      : `创建知识库失败（${res.status}），请稍后重试。`;
    throw new Error(await errorMessage(res, fallback));
  }
  return res.json();
}

export async function updateGroup(groupId: string, name: string, description: string): Promise<DatasetGroup> {
  const res = await apiFetch(`/api/dataset-groups/${encodeURIComponent(groupId)}`, {
    method: 'PUT',
    headers: jsonHeaders(),
    body: JSON.stringify({ name, description }),
  });
  if (!res.ok) throw new Error(await errorMessage(res, `知识库更新失败: ${res.status}`));
  return res.json();
}

export async function getGroupDetail(groupId: string): Promise<GroupDetail> {
  const res = await apiFetch(`/api/dataset-groups/${encodeURIComponent(groupId)}`, {
    headers: authHeaders(),
  });
  if (!res.ok) throw new Error(await errorMessage(res, `Failed to load KB: ${res.status}`));
  return res.json();
}

export async function deleteGroup(groupId: string): Promise<void> {
  const res = await apiFetch(`/api/dataset-groups/${encodeURIComponent(groupId)}`, {
    method: 'DELETE',
    headers: authHeaders(),
  });
  if (!res.ok) throw new Error(await errorMessage(res, `Failed to delete KB: ${res.status}`));
}

export async function uploadKnowledge(groupId: string, file: File): Promise<string> {
  const form = new FormData();
  form.append('file', file, file.name);
  const res = await apiFetch(`/api/dataset-groups/${encodeURIComponent(groupId)}/knowledge`, {
    method: 'PUT',
    headers: authHeaders(),
    body: form,
  });
  if (!res.ok) throw new Error(await errorMessage(res, `业务文档上传失败：${res.status}`));
  const body = (await res.json()) as { content?: string };
  return body.content ?? '';
}

export async function getInstructions(groupId: string): Promise<{ hasInstructions: boolean; instructionsText: string }> {
  const res = await apiFetch(`/api/dataset-groups/${encodeURIComponent(groupId)}/instructions`, { headers: authHeaders() });
  if (!res.ok) throw new Error(await errorMessage(res, `规则读取失败: ${res.status}`));
  return res.json();
}

export async function uploadInstructions(file: File, groupId: string): Promise<{ status: string; message: string }> {
  const form = new FormData();
  form.append('file', file, file.name);
  const res = await apiFetch(`/api/dataset-groups/${encodeURIComponent(groupId)}/instructions`, {
    method: 'POST', headers: authHeaders(), body: form,
  });
  if (!res.ok) throw new Error(await errorMessage(res, `规则上传失败: ${res.status}`));
  return res.json();
}

// ---------------------------------------------------------------- datasets

export async function listDatasets(groupId?: string): Promise<Dataset[]> {
  const qs = groupId ? `?groupId=${encodeURIComponent(groupId)}` : '';
  const res = await apiFetch(`/api/datasets${qs}`, { headers: authHeaders() });
  if (!res.ok) throw new Error(await errorMessage(res, `Failed to list datasets: ${res.status}`));
  return res.json();
}

export async function uploadDataset(
  groupId: string,
  name: string,
  file: File,
  overwrite = false,
  selectedSheet?: string,
  uploadId?: string,
): Promise<Dataset> {
  const form = new FormData();
  form.append('file', file, file.name);
  // `name`/`groupId`/`overwrite`/`selectedSheet` travel as query params: WebFlux @RequestParam does not bind
  // multipart fields. When overwrite is true, the new version replaces the current dataset after validation.
  const params: Record<string, string> = { name, groupId, overwrite: String(overwrite) };
  if (selectedSheet) params.selectedSheet = selectedSheet;
  if (uploadId) params.uploadId = uploadId;
  const qs = new URLSearchParams(params);
  let res: Response;
  try {
    res = await fetchWithTimeout(`/api/datasets?${qs.toString()}`, {
      method: 'POST',
      headers: authHeaders(),
      body: form,
    });
  } catch (e) {
    if (e instanceof DOMException && e.name === 'AbortError') {
      throw new DatasetUploadError('请求超过 5 分钟，导入状态待确认，请先刷新查看结果，勿立即重复上传', 'UNCERTAIN');
    }
    throw new DatasetUploadError(e instanceof Error ? `网络中断，导入状态待确认：${e.message}` : '网络中断，导入状态待确认', 'UNCERTAIN');
  }
  if (!res.ok) {
    const body = await res.json().catch(() => ({})) as { code?: string; message?: string; detail?: string; datasetId?: string };
    const uncertain = res.status >= 500 && !body.code;
    throw new DatasetUploadError(body.message || body.detail || (uncertain ? `服务器异常（${res.status}），请先刷新确认导入结果` : `导入失败：${res.status}`),
      body.code || (res.status === 429 ? 'BUSY' : uncertain ? 'UNCERTAIN' : 'FAILED'), body.datasetId);
  }
  return res.json();
}

export async function deleteDataset(id: string): Promise<void> {
  const res = await apiFetch(`/api/datasets/${encodeURIComponent(id)}`, {
    method: 'DELETE',
    headers: authHeaders(),
  });
  if (!res.ok) throw new Error(await errorMessage(res, `Failed to delete dataset: ${res.status}`));
}

export async function getPreview(id: string, limit = 20): Promise<Preview> {
  const res = await apiFetch(`/api/datasets/${encodeURIComponent(id)}/preview?limit=${limit}`, {
    headers: authHeaders(),
  });
  if (!res.ok) throw new Error(await errorMessage(res, `Failed to preview: ${res.status}`));
  return res.json();
}

export async function updateColumns(
  id: string,
  updates: { name: string; description: string }[],
): Promise<Dataset> {
  const res = await apiFetch(`/api/datasets/${encodeURIComponent(id)}/columns`, {
    method: 'PUT',
    headers: jsonHeaders(),
    body: JSON.stringify(updates),
  });
  if (!res.ok) throw new Error(await errorMessage(res, `Failed to update columns: ${res.status}`));
  return res.json();
}

export async function updateDatasetDescription(id: string, description: string): Promise<Dataset> {
  const res = await apiFetch(`/api/datasets/${encodeURIComponent(id)}/description`, {
    method: 'PUT',
    headers: jsonHeaders(),
    body: JSON.stringify({ description }),
  });
  if (!res.ok) throw new Error(await errorMessage(res, `Failed to update description: ${res.status}`));
  return res.json();
}

export async function refreshDatasetSample(id: string): Promise<Dataset> {
  const res = await apiFetch(`/api/datasets/${encodeURIComponent(id)}/sample-refresh`, {
    method: 'POST', headers: authHeaders(),
  });
  if (!res.ok) throw new Error(await errorMessage(res, '样本刷新失败'));
  return res.json();
}

export interface DatasetColumnSuggestion {
  physicalName: string;
  englishName: string;
  description: string;
}

export interface DatasetMetadataSuggestion {
  tableDescription: string;
  columns: DatasetColumnSuggestion[];
}

export async function generateMetadataSuggestions(id: string): Promise<DatasetMetadataSuggestion> {
  try {
    const res = await fetchWithTimeout(`/api/datasets/${encodeURIComponent(id)}/metadata-suggestions`, {
      method: 'POST',
      headers: authHeaders(),
    }, 130_000);
    if (!res.ok) throw new Error(await errorMessage(res, `生成建议失败（${res.status}）`));
    return res.json();
  } catch (e) {
    if (e instanceof DOMException && e.name === 'AbortError') {
      throw new Error('生成建议超时，现有描述未修改，请稍后重试');
    }
    throw e;
  }
}

// ---------------------------------------------------------------- import tasks

export interface ImportTask {
  id: string;
  groupId: string;
  datasetName: string;
  sourceFileName: string;
  selectedSheet: string | null;
  datasetId: string | null;
  status: string;
  operation: string;
  sourceRows: number | null;
  writtenRows: number | null;
  errorSummary: string | null;
  warningSummary: string | null;
  createdAt: string | null;
  finishedAt: string | null;
}

export async function listImportTasks(groupId: string): Promise<ImportTask[]> {
  const res = await apiFetch(`/api/datasets/import-tasks?groupId=${encodeURIComponent(groupId)}`, {
    headers: authHeaders(),
  });
  if (!res.ok) throw new Error(await errorMessage(res, `Failed to list import tasks: ${res.status}`));
  return res.json();
}

export async function retryImportTask(taskId: string): Promise<ImportTask> {
  const res = await apiFetch(`/api/datasets/import-tasks/${encodeURIComponent(taskId)}/retry`, {
    method: 'POST',
    headers: authHeaders(),
  });
  if (!res.ok) throw new Error(await errorMessage(res, `重试失败: ${res.status}`));
  return res.json();
}

export interface ColumnFormatUpdate {
  name: string;
  sqlType: string;
  sourceFormat?: string | null;
}

export async function startColumnFormatReimport(
  datasetId: string,
  columns: ColumnFormatUpdate[],
): Promise<ImportTask> {
  const res = await apiFetch(`/api/datasets/${encodeURIComponent(datasetId)}/column-format-reimport`, {
    method: 'POST',
    headers: jsonHeaders(),
    body: JSON.stringify({ columns }),
  });
  if (!res.ok) throw new Error(await errorMessage(res, `字段类型校正失败: ${res.status}`));
  return res.json();
}

export async function getImportTask(taskId: string): Promise<ImportTask> {
  const res = await apiFetch(`/api/datasets/import-tasks/${encodeURIComponent(taskId)}`, {
    headers: authHeaders(),
  });
  if (!res.ok) throw new Error(await errorMessage(res, `读取导入任务失败: ${res.status}`));
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
  const res = await apiFetch(`/api/dataset-groups/${encodeURIComponent(groupId)}/graph`, {
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

/** @deprecated Legacy description import API. */
export async function saveDescription(groupId: string, file: File): Promise<{ name: string; saved: boolean }> {
  const form = new FormData();
  form.append('file', file, file.name);
  const res = await apiFetch(`/api/dataset-groups/${encodeURIComponent(groupId)}/description`, {
    method: 'PUT',
    headers: authHeaders(),
    body: form,
  });
  if (!res.ok) throw new Error(await errorMessage(res, `Failed to save description: ${res.status}`));
  return res.json();
}

/** @deprecated Legacy description import API. */
export async function getDescription(groupId: string): Promise<DescriptionInfo> {
  const res = await apiFetch(`/api/dataset-groups/${encodeURIComponent(groupId)}/description`, {
    headers: authHeaders(),
  });
  if (!res.ok) throw new Error(await errorMessage(res, `Failed to get description: ${res.status}`));
  return res.json();
}

/** @deprecated Legacy description import result. */
export interface ImportSummary {
  batchesImported: number;
  batchesSkipped: number;
  derivedCreated: number;
  relationshipsWritten: number;
  warnings: string[];
}

/** @deprecated Legacy description-driven upload API. */
export async function uploadDataWithDescription(
  groupId: string,
  file: File,
): Promise<ImportSummary> {
  const form = new FormData();
  form.append('files', file);
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), 300_000); // 5 min
  try {
    const res = await apiFetch(`/api/dataset-groups/${encodeURIComponent(groupId)}/upload-data`, {
      method: 'POST',
      headers: authHeaders(),
      body: form,
      signal: controller.signal,
    });
    if (!res.ok) throw new Error(await errorMessage(res, `业务文档上传失败：${res.status}`));
    return res.json() as Promise<ImportSummary>;
  } catch (e) {
    if (e instanceof DOMException && e.name === 'AbortError') {
      throw new DatasetUploadError('请求超过 5 分钟，导入状态待确认，请先刷新查看结果，勿立即重复上传', 'UNCERTAIN');
    }
    throw e;
  } finally {
    clearTimeout(timer);
  }
}
