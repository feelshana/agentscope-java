/**
 * 本体模型 API 客户端。对应后端 /api/ontology/* 端点。
 */

import { getToken } from './auth';

export interface OntologyVO {
  id: string;
  name: string;
  version: string;
  groupId: string;
  origin: 'uploaded' | 'auto-generated';
  createdAt: string | null;
  updatedAt: string | null;
}

export interface OntologyProperty {
  name: string;
  label: string;
  type: string;
}

export interface OntologyNode {
  id: string;
  label: string;
  kind: 'dimension' | 'fact' | 'metric';
  table?: string;
  unit?: string;
  highlight: boolean;
  properties?: OntologyProperty[];
}

export interface OntologyEdge {
  id: string;
  source: string;
  target: string;
  label: string;
  cardinality: string;
  joinColumns?: string;
}

export interface OntologyGraphData {
  nodes: OntologyNode[];
  edges: OntologyEdge[];
  objectCount: number;
  relationshipCount: number;
  metricCount: number;
}

export interface AutoGenerateRequest {
  tableNames: string[];
  businessDoc?: string;
}

function authHeaders(): Record<string, string> {
  const token = getToken();
  return {
    ...(token ? { Authorization: `Bearer ${token}` } : {}),
    'Content-Type': 'application/json',
  };
}

async function apiFetch<T>(path: string, init?: RequestInit): Promise<T> {
  const res = await fetch(path, { headers: authHeaders(), ...init });
  if (!res.ok) {
    const txt = await res.text().catch(() => res.statusText);
    throw new Error(txt || `HTTP ${res.status}`);
  }
  return res.json() as Promise<T>;
}

/** 上传 model.yaml */
export async function uploadOntology(file: File, groupId: string): Promise<OntologyVO> {
  const form = new FormData();
  form.append('file', file);
  const token = getToken();
  const res = await fetch(`/api/ontology/upload?groupId=${encodeURIComponent(groupId)}`, {
    method: 'POST',
    headers: token ? { Authorization: `Bearer ${token}` } : {},
    body: form,
  });
  if (!res.ok) {
    const txt = await res.text().catch(() => '');
    let msg = txt || `Upload failed: ${res.status}`;
    try {
      const j = JSON.parse(txt);
      msg = j?.error?.message ?? j?.message ?? msg;
    } catch {
      /* keep raw text */
    }
    throw new Error(msg);
  }
  return res.json();
}

/** 自动生成本体 */
export async function autoGenerateOntology(
  groupId: string,
  req: AutoGenerateRequest,
): Promise<OntologyVO> {
  return apiFetch<OntologyVO>(`/api/ontology/auto-generate?groupId=${encodeURIComponent(groupId)}`, {
    method: 'POST',
    body: JSON.stringify(req),
  });
}

/** 获取图谱数据 */
export async function getOntologyGraph(groupId: string): Promise<OntologyGraphData> {
  return apiFetch<OntologyGraphData>(`/api/ontology/${encodeURIComponent(groupId)}/graph`);
}

/** 获取子图 */
export async function getOntologySubGraph(
  groupId: string,
  objects: string[],
): Promise<OntologyGraphData> {
  const params = objects.map(o => `objects=${encodeURIComponent(o)}`).join('&');
  return apiFetch<OntologyGraphData>(
    `/api/ontology/${encodeURIComponent(groupId)}/sub-graph?${params}`,
  );
}

/** 获取格式化目录文本 */
export async function getOntologyCatalog(groupId: string): Promise<string> {
  const token = getToken();
  const res = await fetch(`/api/ontology/${encodeURIComponent(groupId)}/catalog`, {
    headers: token ? { Authorization: `Bearer ${token}` } : {},
  });
  if (!res.ok) throw new Error(`Catalog fetch failed: ${res.status}`);
  return res.text();
}

/** 删除本体 */
export async function deleteOntology(id: string): Promise<void> {
  const token = getToken();
  const res = await fetch(`/api/ontology/${id}`, {
    method: 'DELETE',
    headers: token ? { Authorization: `Bearer ${token}` } : {},
  });
  if (!res.ok) throw new Error(`Delete failed: ${res.status}`);
}

// ---------- P3 扩展：对象目录 / YAML 导入导出 / 清单导入 ----------

export interface OntoProperty {
  column: string;
  type: string;
  label?: string;
  note?: string;
}

export interface OntoObject {
  label?: string;
  table?: string;
  kind?: string;
  identity?: string;
  description?: string;
  time_field?: string;
  source_batches?: string[];
  grain?: string;
  properties: Record<string, OntoProperty>;
}

export interface OntoRelationship {
  from: string;
  to: string;
  join?: Array<Record<string, string>>;
  cardinality?: string;
  label?: string;
  note?: string;
}

export interface OntoMetric {
  label?: string;
  aggregate?: string;
  column?: string;
  unit?: string;
  object?: string;
  description?: string;
}

export interface OntoRule {
  statement: string;
}

export interface OntoLimitation {
  id: string;
  text: string;
}

export interface OntologyModelData {
  groupId: string;
  name?: string;
  version?: string;
  /** "uploaded"（用户上传 model.yaml）或 "auto-generated"（自动生成） */
  origin?: string;
  objects: Record<string, OntoObject>;
  relationships: Record<string, OntoRelationship>;
  metrics: Record<string, OntoMetric>;
  rules: Record<string, OntoRule>;
  limitations: OntoLimitation[];
}

export interface ImportSummary {
  batchesImported: number;
  batchesSkipped: number;
  derivedCreated: number;
  relationshipsWritten: number;
  warnings: string[];
}

/** 获取本体模型结构化数据（对象目录视图数据源） */
export async function getOntologyModel(groupId: string): Promise<OntologyModelData | null> {
  const token = getToken();
  const res = await fetch(`/api/ontology/${encodeURIComponent(groupId)}/model`, {
    headers: token ? { Authorization: `Bearer ${token}` } : {},
  });
  if (res.status === 404) return null;
  if (!res.ok) throw new Error(`Model fetch failed: ${res.status}`);
  return res.json() as Promise<OntologyModelData>;
}

/** 下载本体 YAML 文件 */
export async function downloadOntologyYaml(groupId: string): Promise<void> {
  const token = getToken();
  const res = await fetch(`/api/ontology/${encodeURIComponent(groupId)}/yaml`, {
    headers: token ? { Authorization: `Bearer ${token}` } : {},
  });
  if (!res.ok) throw new Error(`YAML download failed: ${res.status}`);
  const blob = await res.blob();
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = `${groupId}-ontology.yaml`;
  a.click();
  URL.revokeObjectURL(url);
}

/** 上传编辑后的本体 YAML */
export async function uploadOntologyYaml(groupId: string, file: File): Promise<OntologyVO> {
  const form = new FormData();
  form.append('file', file);
  const token = getToken();
  const res = await fetch(`/api/ontology/${encodeURIComponent(groupId)}/ontology`, {
    method: 'PUT',
    headers: token ? { Authorization: `Bearer ${token}` } : {},
    body: form,
  });
  if (!res.ok) throw new Error(`YAML upload failed: ${res.status}`);
  return res.json();
}

/** 导入本体清单（manifest + 数据文件） */
export async function importManifest(
  groupId: string,
  manifestFile: File,
  dataFiles: File[],
): Promise<ImportSummary> {
  const form = new FormData();
  form.append('manifest', manifestFile);
  for (const f of dataFiles) {
    form.append('files', f);
  }
  const token = getToken();
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), 300_000); // 5 min
  try {
    const res = await fetch(
      `/api/dataset-groups/${encodeURIComponent(groupId)}/import-manifest`,
      {
        method: 'POST',
        headers: token ? { Authorization: `Bearer ${token}` } : {},
        body: form,
        signal: controller.signal,
      },
    );
    if (!res.ok) throw new Error(`Manifest import failed: ${res.status}`);
    return res.json() as Promise<ImportSummary>;
  } catch (e) {
    if (e instanceof DOMException && e.name === 'AbortError') {
      throw new Error('导入超时（超过 5 分钟），请检查文件是否过大或服务端日志');
    }
    throw e;
  } finally {
    clearTimeout(timer);
  }
}
