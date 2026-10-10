import { getToken } from './auth';
import type { Dataset } from './datasets';

/** Lifecycle of the knowledge base's published Wren snapshot. */
export interface ModelingGroupState {
  mdlState: 'NONE' | 'INITIALIZING' | 'FAILED' | 'PUBLISHED' | 'DIRTY';
  mdlVersion: number;
  mdlPublishedAt: string | null;
  mdlLastError: string | null;
}

/** A candidate or reviewed edge between two datasets (physical column names). */
export interface ModelingRelation {
  id: string;
  sourceDatasetId: string;
  sourceColumn: string;
  targetDatasetId: string;
  targetColumn: string;
  relationType: string;
  description: string | null;
  confidence: number;
  origin: string;
  /** MANY_TO_ONE / ONE_TO_MANY / ONE_TO_ONE, or null when probing was inconclusive. */
  joinType: string | null;
  /** PENDING / CONFIRMED / REJECTED. */
  status: string;
}

/**
 * One measure / dimension entry as stored in a cube's JSON body. Fields map 1:1 to the cube
 * YAML: name + column + agg for measures, name + column for dimensions, plus granularity for
 * time dimensions. `caseFilter` carries an optional CASE condition (e.g. "status = 'paid'").
 */
export interface CubeItem {
  name?: string;
  column?: string;
  agg?: string;
  granularity?: string;
  caseFilter?: string;
  description?: string;
  [key: string]: unknown;
}

export interface ModelingCube {
  id: string;
  name: string;
  baseDatasetId: string;
  description: string | null;
  status: string;
  measures: CubeItem[];
  dimensions: CubeItem[];
  timeDimensions: CubeItem[];
  createdAt: string | null;
  updatedAt: string | null;
}

/** One named SQL view (specs/011 M2): a single SELECT/WITH statement compiled into the MDL. */
export interface ModelingView {
  id: string;
  name: string;
  baseDatasetId: string | null;
  sqlText: string;
  description: string | null;
  status: string;
  createdAt: string | null;
  updatedAt: string | null;
}

/** One business term bound to this knowledge base (specs/026; managed via the semantic-config page). */
export interface ModelingTerm {
  id: string;
  term: string;
  explanation: string;
  synonyms: string | null;
}

export interface ModelingOverview {
  group: ModelingGroupState;
  datasets: Dataset[];
  relations: ModelingRelation[];
  cubes: ModelingCube[];
  views: ModelingView[];
  terms: ModelingTerm[];
}

export interface RelationUpdateRequest {
  status: 'CONFIRMED' | 'REJECTED';
  joinType?: string | null;
  swap?: boolean;
}

export interface ManualRelationRequest {
  sourceDatasetId: string;
  sourceColumn: string;
  targetDatasetId: string;
  targetColumn: string;
  joinType?: string | null;
}

export interface CubeRequest {
  name: string;
  baseDatasetId: string;
  description?: string | null;
  measures: CubeItem[];
  dimensions: CubeItem[];
  timeDimensions: CubeItem[];
}

export interface ViewRequest {
  name: string;
  baseDatasetId?: string | null;
  sqlText: string;
  description?: string | null;
}

/** One asynchronous document semantic-difference analysis task. */
export interface EnhanceTask {
  id: string;
  sourceType: string;
  sourceLabel: string | null;
  status: 'RUNNING' | 'READY' | 'FAILED';
  errorMessage: string | null;
  createdAt: string | null;
  finishedAt: string | null;
}

export interface EnhanceProposal {
  id: string;
  type: 'TERM' | 'BUSINESS_RULE' | 'RELATIONSHIP' | 'CUBE' | 'VIEW' | 'MANUAL_FIX';
  classification: 'NEW' | 'PARTIAL' | 'CONFLICT';
  title: string;
  summary: string | null;
  payload: Record<string, unknown>;
  sourceQuote: string | null;
  confidence: 'HIGH' | 'MEDIUM' | 'LOW';
  status: 'PENDING' | 'ADOPTED' | 'IGNORED';
  decisionError: string | null;
  createdAt: string | null;
  decidedAt: string | null;
}

export interface EnhanceOverview {
  task: EnhanceTask | null;
  proposals: EnhanceProposal[];
}

/** An LLM cube proposal; nothing is persisted until adopted via createCube. */
export interface CubeSuggestion {
  datasetId: string;
  datasetName: string;
  name: string;
  measures: CubeItem[];
  dimensions: CubeItem[];
  timeDimensions: CubeItem[];
  reason: string;
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

function base(groupId: string): string {
  return `/api/dataset-groups/${encodeURIComponent(groupId)}/modeling`;
}

/** Group MDL state + datasets + persisted relations + cubes. */
export async function getModelingOverview(groupId: string): Promise<ModelingOverview> {
  const res = await fetch(base(groupId), { headers: authHeaders() });
  if (!res.ok) throw new Error(await errorMessage(res, `加载语义模型失败: ${res.status}`));
  return res.json();
}

/** Recomputes rule edges + LLM candidates + joinType probing; returns the persisted edges. */
export async function suggestRelations(groupId: string): Promise<ModelingRelation[]> {
  const res = await fetch(`${base(groupId)}/suggest/relations`, {
    method: 'POST',
    headers: authHeaders(),
  });
  if (!res.ok) throw new Error(await errorMessage(res, `关系建议生成失败: ${res.status}`));
  return res.json();
}

/** LLM cube proposals (not persisted). */


/** Human review of an edge: confirm (optionally redirecting via swap / joinType) or reject. */
export async function updateRelation(
  groupId: string,
  relationId: string,
  req: RelationUpdateRequest,
): Promise<ModelingRelation> {
  const res = await fetch(`${base(groupId)}/relations/${encodeURIComponent(relationId)}`, {
    method: 'PUT',
    headers: jsonHeaders(),
    body: JSON.stringify(req),
  });
  if (!res.ok) throw new Error(await errorMessage(res, `关系更新失败: ${res.status}`));
  return res.json();
}

/** Hand-written relation, confirmed immediately. */
export async function addManualRelation(
  groupId: string,
  req: ManualRelationRequest,
): Promise<ModelingRelation> {
  const res = await fetch(`${base(groupId)}/relations`, {
    method: 'POST',
    headers: jsonHeaders(),
    body: JSON.stringify(req),
  });
  if (!res.ok) throw new Error(await errorMessage(res, `关系保存失败: ${res.status}`));
  return res.json();
}







// ------------------------------------------------------------------ views (specs/011 M2)

export async function createView(groupId: string, req: ViewRequest): Promise<ModelingView> {
  const res = await fetch(`${base(groupId)}/views`, {
    method: 'POST',
    headers: jsonHeaders(),
    body: JSON.stringify(req),
  });
  if (!res.ok) throw new Error(await errorMessage(res, `视图创建失败: ${res.status}`));
  return res.json();
}

export async function updateView(
  groupId: string,
  viewId: string,
  req: ViewRequest,
): Promise<ModelingView> {
  const res = await fetch(`${base(groupId)}/views/${encodeURIComponent(viewId)}`, {
    method: 'PUT',
    headers: jsonHeaders(),
    body: JSON.stringify(req),
  });
  if (!res.ok) throw new Error(await errorMessage(res, `视图更新失败: ${res.status}`));
  return res.json();
}

export async function deleteView(groupId: string, viewId: string): Promise<void> {
  const res = await fetch(`${base(groupId)}/views/${encodeURIComponent(viewId)}`, {
    method: 'DELETE',
    headers: authHeaders(),
  });
  if (!res.ok) throw new Error(await errorMessage(res, `视图删除失败: ${res.status}`));
}

// ------------------------------------------------------------------ document semantic enhancement (specs/014)

export async function getEnhanceOverview(groupId: string): Promise<EnhanceOverview> {
  const res = await fetch(`${base(groupId)}/enhance`, { headers: authHeaders() });
  if (!res.ok) throw new Error(await errorMessage(res, `语义增强状态加载失败: ${res.status}`));
  return res.json();
}

export async function triggerEnhance(groupId: string): Promise<EnhanceTask> {
  const res = await fetch(`${base(groupId)}/enhance`, {
    method: 'POST',
    headers: authHeaders(),
  });
  if (!res.ok) throw new Error(await errorMessage(res, `语义增强启动失败: ${res.status}`));
  return res.json();
}

export async function adoptEnhanceProposal(
  groupId: string,
  proposalId: string,
): Promise<EnhanceProposal> {
  const res = await fetch(
    `${base(groupId)}/enhance/proposals/${encodeURIComponent(proposalId)}/adopt`,
    { method: 'POST', headers: authHeaders() },
  );
  if (!res.ok) throw new Error(await errorMessage(res, `提案采纳失败: ${res.status}`));
  return res.json();
}

export async function ignoreEnhanceProposal(
  groupId: string,
  proposalId: string,
): Promise<EnhanceProposal> {
  const res = await fetch(
    `${base(groupId)}/enhance/proposals/${encodeURIComponent(proposalId)}/ignore`,
    { method: 'POST', headers: authHeaders() },
  );
  if (!res.ok) throw new Error(await errorMessage(res, `提案忽略失败: ${res.status}`));
  return res.json();
}

// ------------------------------------------------------------------ MDL publish (specs/010 M2)

/** One generated project file; `path` is project-relative with '/' separators. */
export interface MdlFile {
  path: string;
  content: string;
}

/** A validation finding; `source` is "local" (pre-flight) or "wren" (CLI diagnostics). */
export interface MdlIssue {
  severity: string;
  message: string;
  source: string;
}

/** Assembled YAML for the whole knowledge base plus the published snapshot it would replace. */
export interface MdlPreview {
  files: MdlFile[];
  publishedFiles: MdlFile[];
  issues: MdlIssue[];
  mdlState: string;
  mdlVersion: number;
  mdlPublishedAt: string | null;
  changed: boolean;
}

export interface MdlValidation {
  ok: boolean;
  issues: MdlIssue[];
  output: string;
  files: MdlFile[];
}

export interface MdlPublishResult {
  ok: boolean;
  issues: MdlIssue[];
  output: string;
  mdlState: string;
  mdlVersion: number;
  mdlPublishedAt: string | null;
}

/** Assembles the MDL YAML (confirmed relations + cubes, normalized types); read-only. */
export async function getMdlPreview(groupId: string): Promise<MdlPreview> {
  const res = await fetch(`${base(groupId)}/mdl`, { headers: authHeaders() });
  if (!res.ok) throw new Error(await errorMessage(res, `MDL 预览生成失败: ${res.status}`));
  return res.json();
}

// ------------------------------------------------------------------ MDL view (specs/013 M2)

/** One column in a logical model; relationship/calculated carry the visualization badges. */
export interface MdlColumnView {
  name: string;
  type: string | null;
  description: string | null;
  relationship: boolean;
  calculated: boolean;
  expression: string | null;
}

export interface MdlModelView {
  datasetId: string;
  datasetName: string;
  tableName: string;
  modelName: string;
  description: string | null;
  columns: MdlColumnView[];
  /** Workspace-relative YAML file backing this model (specs/030 asset browser). */
  path: string;
  /**
   * Workspace-relative ref_sql.sql for a derived (ref_sql) model; null for physical models
   * (specs/034 — the model is defined by SQL, not a physical table).
   */
  refSqlPath: string | null;
}

export interface MdlRelationView {
  name: string;
  leftModel: string;
  rightModel: string;
  joinType: string;
  condition: string;
  sourceColumns: string[];
  targetColumns: string[];
}

export interface MdlCubeMemberView {
  name: string;
  expression: string;
  type: string;
  description: string | null;
}

export interface MdlCubeView {
  name: string;
  baseModel: string;
  description: string | null;
  measures: MdlCubeMemberView[];
  dimensions: MdlCubeMemberView[];
  timeDimensions: MdlCubeMemberView[];
  /** Workspace-relative YAML file backing this cube (specs/030 asset browser). */
  path: string;
}

/** One named SQL view summary inside the structured MDL view (specs/030). */
export interface MdlViewSummary {
  name: string;
  sql: string;
  description: string | null;
  /** Workspace-relative YAML file backing this view. */
  path: string;
}

/** Structured read-only MDL view: freshly assembled draft + publication state. */
export interface MdlView {
  state: string | null;
  version: number;
  publishedAt: string | null;
  changed: boolean;
  models: MdlModelView[];
  relations: MdlRelationView[];
  cubes: MdlCubeView[];
  views: MdlViewSummary[];
  /** Derived (ref_sql) models, split out of `models` (specs/034; shown in the 派生模型 tab). */
  derivedModels: MdlModelView[];
  issues: MdlIssue[];
}

/** Fetches the structured MDL view for visualization; read-only, never touches staging. */
export async function getMdlView(groupId: string): Promise<MdlView> {
  const res = await fetch(`${base(groupId)}/mdl/view`, { headers: authHeaders() });
  if (!res.ok) throw new Error(await errorMessage(res, `MDL 视图加载失败: ${res.status}`));
  return res.json();
}

/** Local checks + `wren context validate --strict`; local errors short-circuit the CLI. */
export async function validateMdl(groupId: string): Promise<MdlValidation> {
  const res = await fetch(`${base(groupId)}/mdl/validate`, {
    method: 'POST',
    headers: authHeaders(),
  });
  if (!res.ok) throw new Error(await errorMessage(res, `MDL 验证失败: ${res.status}`));
  return res.json();
}

/** Rebuilds the table/column baseline without adopting semantic drafts. */
export async function initializeMdl(groupId: string): Promise<MdlPublishResult> {
  const res = await fetch(`${base(groupId)}/mdl/initialize`, {
    method: 'POST',
    headers: authHeaders(),
  });
  if (!res.ok) throw new Error(await errorMessage(res, `基础 MDL 生成失败: ${res.status}`));
  return res.json();
}

/** validate -> build -> snapshot -> version+1; any failure keeps the previous snapshot. */
export async function publishMdl(groupId: string): Promise<MdlPublishResult> {
  const res = await fetch(`${base(groupId)}/mdl/publish`, {
    method: 'POST',
    headers: authHeaders(),
  });
  if (!res.ok) throw new Error(await errorMessage(res, `MDL 发布失败: ${res.status}`));
  return res.json();
}

// ---------------------------------------- workspace file access (specs/019 §5 HITL card)

/** One workspace file read for the HITL file-change card editor. */
export interface WorkspaceFile {
  path: string;
  content: string;
}

/** Gates ①+② verdict plus the before/after content for one write_file/patch_file draft. */
export interface WorkspacePreview {
  ok: boolean;
  error: string | null;
  oldContent: string;
  newContent: string;
}

/** Reads one workspace file; the backend is tenant-checked and path-confined. */
export async function readWorkspaceFile(groupId: string, path: string): Promise<WorkspaceFile> {
  const res = await fetch(`${base(groupId)}/workspace/file?path=${encodeURIComponent(path)}`, {
    headers: authHeaders(),
  });
  if (!res.ok) throw new Error(await errorMessage(res, `工作区文件读取失败: ${res.status}`));
  return res.json();
}

/**
 * Previews a write_file/patch_file draft through the real write gates (YAML parse + scratch
 * `context validate --strict`) without touching the workspace; feeds the HITL card diff.
 */
export async function previewWorkspaceChange(
  groupId: string,
  toolName: 'write_file' | 'patch_file',
  input: Record<string, unknown>,
): Promise<WorkspacePreview> {
  const res = await fetch(`${base(groupId)}/workspace/preview`, {
    method: 'POST',
    headers: jsonHeaders(),
    body: JSON.stringify({ toolName, input }),
  });
  if (!res.ok) throw new Error(await errorMessage(res, `变更预检失败: ${res.status}`));
  return res.json();
}
