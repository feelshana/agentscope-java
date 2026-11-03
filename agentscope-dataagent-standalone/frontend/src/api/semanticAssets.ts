import { apiFetch, getToken } from './auth';

export type AssetKind = 'view' | 'ref_sql';
export type AssetStatus = 'PUBLISHED' | 'REVIEW_PENDING' | 'VERIFY_PENDING' | 'VERIFIED';
export type Verdict = 'EXPECTED' | 'PROBLEM';

export interface AssetReview {
  hash: string;
  reviewedBy: string;
  reviewedAt: string;
  timeColumns: string[];
  ran: string[];
  verdicts: Record<string, Verdict>;
  verifiedBy: string | null;
  verifiedAt: string | null;
  passed: boolean;
}

export interface SemanticAsset {
  kind: AssetKind;
  name: string;
  directory: string;
  sql: string;
  description: string | null;
  hash: string;
  status: AssetStatus;
  review: AssetReview | null;
}

export interface AssetDetail {
  asset: SemanticAsset;
  files: { path: string; content: string }[];
  needsTimeColumn: boolean;
  fixedDates: string[];
  outputColumns: { name: string; type: string }[];
  /** spec110: risks the reviewer must see before passing the asset. */
  warnings: string[];
}

export interface VerifyCase {
  question: string;
  sql: string;
}

export interface AssetTable {
  columns: unknown[];
  rows: unknown[];
  rowCount: number;
  truncated: boolean;
}

export interface AssetCheck {
  key: string;
  label: string;
  sql: string;
  draft: AssetTable | null;
  published: AssetTable | null;
  error: string | null;
  /** spec110: the SQL as Wren would expand it on the draft; verification never executes. */
  expanded: string | null;
}

async function request<T>(groupId: string, suffix: string, method = 'GET', body?: unknown): Promise<T> {
  const token = getToken();
  const response = await apiFetch(`/api/dataset-groups/${encodeURIComponent(groupId)}/assets${suffix}`, {
    method,
    headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: `Bearer ${token}` } : {}) },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  if (!response.ok) {
    const detail = await response.json().catch(() => ({})) as { message?: string; detail?: string };
    throw new Error(detail.message || detail.detail || `语义资产操作失败（${response.status}）`);
  }
  return response.json() as Promise<T>;
}

const path = (asset: Pick<SemanticAsset, 'kind' | 'name'>) => `/${asset.kind}/${encodeURIComponent(asset.name)}`;

export const listAssets = (groupId: string) => request<SemanticAsset[]>(groupId, '');
/** Example requests written from this knowledge base's draft model; kind is the button label. */
export interface AssetExample { kind: string; text: string }
export const assetExamples = (groupId: string) => request<AssetExample[]>(groupId, '/examples');
export const getAsset = (groupId: string, asset: Pick<SemanticAsset, 'kind' | 'name'>) =>
  request<AssetDetail>(groupId, path(asset));
export const saveAsset = (groupId: string, asset: SemanticAsset, file: string, content: string) =>
  request<AssetDetail>(groupId, path(asset), 'PUT', { file, content, expectedHash: asset.hash });
export const reviewAsset = (groupId: string, asset: SemanticAsset, timeColumns: string[]) =>
  request<AssetDetail>(groupId, `${path(asset)}/review`, 'POST', { timeColumns });
export const generateAssetSql = (groupId: string, asset: SemanticAsset, question: string) =>
  request<{ sql: string }>(groupId, `${path(asset)}/generate`, 'POST', { question });
export const verifyAsset = (groupId: string, asset: SemanticAsset, cases: VerifyCase[]) =>
  request<AssetCheck[]>(groupId, `${path(asset)}/verify`, 'POST', { cases });
/** {@code save}: cases judged as expected to keep as confirmed questions (spec108). */
export const judgeAsset = (groupId: string, asset: SemanticAsset, verdicts: Record<string, Verdict>, save: VerifyCase[] = []) =>
  request<AssetDetail>(groupId, `${path(asset)}/verdict`, 'POST', { verdicts, save });

/** spec109: a query template the assistant proposed in the modeling conversation. */
export interface AssetProposal {
  id: string; kind: string; name: string; need: string; outline: string; reason: string;
  status: 'PENDING' | 'DRAFTED' | 'DISMISSED'; createdAt: string; decidedBy: string | null; decidedAt: string | null;
  /** spec110: coach questions the proposal answers; explicit marks a template the user asked for. */
  questionIds: string[] | null; explicit: boolean | null;
}
export const listAssetProposals = (groupId: string) => request<AssetProposal[]>(groupId, '/proposals');
export const decideAssetProposal = (groupId: string, id: string, status: 'DRAFTED' | 'DISMISSED') =>
  request<AssetProposal>(groupId, `/proposals/${encodeURIComponent(id)}`, 'POST', { status });

/** What deleting an asset touches: rule entries naming it and questions using it. */
export interface AssetImpact { rules: string[]; questions: string[] }
export const assetImpact = (groupId: string, asset: Pick<SemanticAsset, 'kind' | 'name'>) =>
  request<AssetImpact>(groupId, `${path(asset)}/impact`);
export const deleteAsset = (groupId: string, asset: Pick<SemanticAsset, 'kind' | 'name'>) =>
  request<{ deleted: boolean }>(groupId, path(asset), 'DELETE');
