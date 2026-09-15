import { getToken } from './auth';

// ---- 语义术语（Semantic Terms） ----

export interface SemanticTerm {
  id: string;
  term: string;
  explanation: string | null;
  synonyms: string | null;
  scope: string;
}

export interface SemanticTermRequest {
  term: string;
  explanation?: string;
  synonyms?: string;
  scope?: string;
}

// ---- 语义模型（Semantic Model） ----

export interface SemanticModelVO {
  id: string;
  name: string;
  groupId: string;
  origin: string;
  mdlHash: string;
  modelCount: number;
  relationshipCount: number;
  cubeCount: number;
  createdAt: string | null;
  updatedAt: string | null;
}

export interface SemanticGraphNode {
  id: string;
  type: 'model' | 'cube';
  label: string;
  kind?: string;
  baseObject?: string;
  description?: string;
  columns?: any[];
  measures?: any[];
  dimensions?: any[];
  timeDimensions?: any[];
}

export interface SemanticGraphEdge {
  id: string;
  type: 'relationship' | 'cube-base';
  source: string;
  target: string;
  label: string;
  joinType?: string;
  condition?: string;
}

export interface SemanticGraphData {
  nodes: SemanticGraphNode[];
  edges: SemanticGraphEdge[];
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

export async function listSemanticTerms(): Promise<SemanticTerm[]> {
  const res = await fetch('/api/semantic-terms', { headers: jsonHeaders() });
  if (!res.ok) throw new Error(await errorMessage(res, `Failed to list terms: ${res.status}`));
  return res.json();
}

export async function createSemanticTerm(req: SemanticTermRequest): Promise<SemanticTerm> {
  const res = await fetch('/api/semantic-terms', {
    method: 'POST',
    headers: jsonHeaders(),
    body: JSON.stringify(req),
  });
  if (!res.ok) throw new Error(await errorMessage(res, `Failed to create term: ${res.status}`));
  return res.json();
}

export async function updateSemanticTerm(
  id: string,
  req: SemanticTermRequest,
): Promise<SemanticTerm> {
  const res = await fetch(`/api/semantic-terms/${encodeURIComponent(id)}`, {
    method: 'PUT',
    headers: jsonHeaders(),
    body: JSON.stringify(req),
  });
  if (!res.ok) throw new Error(await errorMessage(res, `Failed to update term: ${res.status}`));
  return res.json();
}

export async function deleteSemanticTerm(id: string): Promise<void> {
  const res = await fetch(`/api/semantic-terms/${encodeURIComponent(id)}`, {
    method: 'DELETE',
    headers: jsonHeaders(),
  });
  if (!res.ok) throw new Error(await errorMessage(res, `Failed to delete term: ${res.status}`));
}

// ---- 语义模型 API ----

function semHeaders(): Record<string, string> {
  const token = getToken();
  return {
    'Content-Type': 'application/json',
    ...(token ? { Authorization: `Bearer ${token}` } : {}),
  };
}

/** 上传 ontology.json */
export async function uploadSemanticModel(file: File, groupId: string): Promise<SemanticModelVO> {
  const form = new FormData();
  form.append('file', file);
  const token = getToken();
  const res = await fetch(`/api/semantic-model/upload?groupId=${encodeURIComponent(groupId)}`, {
    method: 'POST',
    headers: token ? { Authorization: `Bearer ${token}` } : {},
    body: form,
  });
  if (!res.ok) {
    const txt = await res.text().catch(() => '');
    let msg = txt || `Upload failed: ${res.status}`;
    try { msg = JSON.parse(txt)?.message ?? msg; } catch { /* keep raw */ }
    throw new Error(msg);
  }
  return res.json();
}

/** 从已选数据表自动建模 */
export async function autoGenerateSemanticModel(
  groupId: string,
  tableNames?: string[],
): Promise<SemanticModelVO> {
  const res = await fetch(`/api/semantic-model/auto-generate?groupId=${encodeURIComponent(groupId)}`, {
    method: 'POST',
    headers: semHeaders(),
    body: JSON.stringify({ tableNames: tableNames ?? [] }),
  });
  if (!res.ok) throw new Error(`Auto-generate failed: ${res.status}`);
  return res.json();
}

/** 获取语义模型图谱数据（nodes + edges） */
export async function getSemanticGraph(groupId: string): Promise<SemanticGraphData> {
  const res = await fetch(`/api/semantic-model/${encodeURIComponent(groupId)}/graph`, {
    headers: semHeaders(),
  });
  if (!res.ok) throw new Error(`Graph fetch failed: ${res.status}`);
  return res.json();
}

/** 获取语义模型 schema 描述（Markdown） */
export async function getSemanticSchemaDescription(groupId: string): Promise<string> {
  const token = getToken();
  const res = await fetch(`/api/semantic-model/${encodeURIComponent(groupId)}/schema-description`, {
    headers: token ? { Authorization: `Bearer ${token}` } : {},
  });
  if (!res.ok) throw new Error(`Schema description fetch failed: ${res.status}`);
  return res.text();
}

/** 导出语义模型 JSON */
export async function exportSemanticModel(groupId: string): Promise<any> {
  const res = await fetch(`/api/semantic-model/${encodeURIComponent(groupId)}/export`, {
    headers: semHeaders(),
  });
  if (!res.ok) throw new Error(`Export failed: ${res.status}`);
  return res.json();
}

/** 删除语义模型 */
export async function deleteSemanticModel(modelId: string): Promise<void> {
  const token = getToken();
  const res = await fetch(`/api/semantic-model/${encodeURIComponent(modelId)}`, {
    method: 'DELETE',
    headers: token ? { Authorization: `Bearer ${token}` } : {},
  });
  if (!res.ok) throw new Error(`Delete failed: ${res.status}`);
}

// ---- Instructions（业务规则 + 数据限制） ----

/** 上传 instructions.md（业务规则 + 数据限制） */
export async function uploadInstructions(file: File, groupId: string): Promise<{ status: string; message: string }> {
  const form = new FormData();
  form.append('file', file);
  const token = getToken();
  const res = await fetch(`/api/semantic-model/instructions?groupId=${encodeURIComponent(groupId)}`, {
    method: 'POST',
    headers: token ? { Authorization: `Bearer ${token}` } : {},
    body: form,
  });
  if (!res.ok) {
    const txt = await res.text().catch(() => '');
    let msg = txt || `Upload failed: ${res.status}`;
    try { msg = JSON.parse(txt)?.message ?? msg; } catch { /* keep raw */ }
    throw new Error(msg);
  }
  return res.json();
}

/** 获取 instructions 文本 */
export async function getInstructions(groupId: string): Promise<{ hasInstructions: boolean; instructionsText: string }> {
  const res = await fetch(`/api/semantic-model/${encodeURIComponent(groupId)}/instructions`, {
    headers: semHeaders(),
  });
  if (!res.ok) throw new Error(`Instructions fetch failed: ${res.status}`);
  return res.json();
}
