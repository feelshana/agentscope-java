import { getToken } from './auth';

export interface KgUnit {
  unitName: string;
  unitType: string;
  status: string;
  errorMsg: string | null;
}

export interface KgStats {
  entityCount: number;
  relationCount: number;
  docCount: number;
  processed: number;
  failed: number;
  pending: number;
  running: number;
}

export interface KgStatus {
  taskStatus: string | null;
  progress: number;
  stats: KgStats;
  units: KgUnit[];
}

export interface KgNode {
  id: string;
  text: string;
  label: string;
  attributes: string | null;
}

export interface KgEdge {
  id: string;
  source: string;
  target: string;
  label: string;
  text: string | null;
}

export interface KgGraph {
  nodes: KgNode[];
  edges: KgEdge[];
  typeDist: Record<string, number>;
}

export interface KgBuildTask {
  id: string;
  status: string;
  triggeredAt: string | null;
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

export interface KgBuildRequest {
  includeDoc?: boolean;
  datasetIds?: string[];
}

export async function triggerKgBuild(
  groupId: string,
  req?: KgBuildRequest,
): Promise<KgBuildTask> {
  const res = await fetch(`/api/dataset-groups/${encodeURIComponent(groupId)}/kg/build`, {
    method: 'POST',
    headers: jsonHeaders(),
    body: JSON.stringify(req ?? {}),
  });
  if (!res.ok) throw new Error(await errorMessage(res, `Failed to build graph: ${res.status}`));
  return res.json();
}

export async function getKgStatus(groupId: string): Promise<KgStatus> {
  const res = await fetch(`/api/dataset-groups/${encodeURIComponent(groupId)}/kg/status`, {
    headers: authHeaders(),
  });
  if (!res.ok) throw new Error(await errorMessage(res, `Failed to load graph status: ${res.status}`));
  return res.json();
}

export async function getKgGraph(groupId: string): Promise<KgGraph> {
  const res = await fetch(`/api/dataset-groups/${encodeURIComponent(groupId)}/kg/graph`, {
    headers: authHeaders(),
  });
  if (!res.ok) throw new Error(await errorMessage(res, `Failed to load graph: ${res.status}`));
  return res.json();
}
