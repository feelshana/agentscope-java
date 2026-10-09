import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import type { CSSProperties } from 'react';
import { useNavigate, useParams, useSearchParams } from 'react-router-dom';
import Icon from '../../components/Icon';
import MdlGraphView from '../../components/MdlGraphView';
import ModelingChatPanel from '../../components/ModelingChatPanel';
import ModelingQuestionsPanel from '../../components/modeling/ModelingQuestionsPanel';
import QuestionIntakeForm from '../../components/modeling/QuestionIntakeForm';
import MdlPublishPanel from '../../components/MdlPublishPanel';
import ModelingWorkflowGuide from '../../components/modeling/ModelingWorkflowGuide';
import { getModelingWorkflow } from '../../api/modelingWorkflow';
import type { ModelingWorkflow } from '../../api/modelingWorkflow';
import BusinessDocumentGuide from '../../components/modeling/BusinessDocumentGuide';
import type { DocumentUploadState } from '../../components/modeling/BusinessDocumentGuide';
import ModelSnapshotPanel from '../../components/modeling/ModelSnapshotPanel';
import './SemanticModelingPage.css';
import AssetYamlBrowser from '../../components/modeling/AssetYamlBrowser';
import type { AssetEntry } from '../../components/modeling/AssetYamlBrowser';
import {
  addManualRelation,
  adoptEnhanceProposal,
  createCube,
  createView,
  deleteCube,
  deleteView,
  getEnhanceOverview,
  getMdlPreview,
  getMdlView,
  getModelingOverview,
  ignoreEnhanceProposal,
  initializeMdl,
  suggestCubes,
  suggestRelations,
  triggerEnhance,
  updateCube,
  updateRelation,
  updateView,
} from '../../api/semanticModeling';
import type {
  CubeItem,
  CubeRequest,
  CubeSuggestion,
  EnhanceOverview,
  EnhanceProposal,
  MdlView,
  ModelingCube,
  ModelingGroupState,
  ModelingOverview,
  ModelingRelation,
  ModelingView,
} from '../../api/semanticModeling';
import { getGroupDetail, uploadKnowledge } from '../../api/datasets';
import type { Dataset, DatasetColumn, GroupDetail } from '../../api/datasets';

const JOIN_LABEL: Record<string, string> = {
  MANY_TO_ONE: '多对一',
  ONE_TO_MANY: '一对多',
  ONE_TO_ONE: '一对一',
};

const JOIN_OPTIONS = ['MANY_TO_ONE', 'ONE_TO_MANY', 'ONE_TO_ONE'];

const ORIGIN_LABEL: Record<string, string> = {
  inferred: '规则推断',
  doc: '文档',
  llm: 'AI 建议',
  manual: '人工',
};

const ENHANCE_TYPE_LABEL: Record<string, string> = {
  TERM: '业务术语',
  BUSINESS_RULE: '业务规则',
  RELATIONSHIP: '表关系',
  CUBE: 'Cube',
  VIEW: '语义视图',
  MANUAL_FIX: '人工处理',
};

const ENHANCE_CLASS_LABEL: Record<string, string> = {
  NEW: '新增',
  PARTIAL: '部分覆盖',
  CONFLICT: '冲突',
};

const CONFIDENCE_LABEL: Record<string, string> = { HIGH: '高', MEDIUM: '中', LOW: '低' };

/** specs/027: fixed three-level semantic maturity (pure computed signal, no LLM). */
type Maturity = 'baseline' | 'partial' | 'refined';

type TabKey = 'overview' | 'modeling' | 'questions' | 'publish' | 'schema' | 'derived' | 'cubes' | 'views' | 'glossary' | 'mdl';

const TAB_KEYS: TabKey[] = ['overview', 'modeling', 'questions', 'publish', 'schema', 'derived', 'cubes', 'views', 'glossary', 'mdl'];

const AGG_OPTIONS = ['SUM', 'AVG', 'COUNT', 'MAX', 'MIN', 'DISTINCT_COUNT'];
const GRANULARITY_OPTIONS = ['DAY', 'MONTH', 'YEAR'];
const CASE_OPS = ['=', '!=', '>', '>=', '<', '<=', 'IN'];

function msg(e: unknown): string {
  return e instanceof Error ? e.message : String(e);
}

function str(v: unknown): string {
  return typeof v === 'string' ? v : '';
}

function datasetMeaning(dataset: Dataset): string {
  const description = dataset.description?.trim();
  return description && description !== dataset.name.trim() ? description : '业务含义待补充';
}

function datasetLabel(dataset: Dataset): string {
  return `${dataset.name} — ${datasetMeaning(dataset)}`;
}

function columnLabel(column: DatasetColumn): string {
  const name =
    column.originalName && column.originalName !== column.name
      ? `${column.originalName}（${column.name}）`
      : column.name;
  const description = column.description?.trim();
  return description ? `${name} — ${description}` : name;
}

/** Renders a CASE literal: bare for numbers, single-quoted otherwise. */
function caseLiteral(raw: string): string {
  return /^-?\d+(\.\d+)?$/.test(raw) ? raw : `'${raw.replace(/'/g, "''")}'`;
}

function MdlStateBadge({ state }: { state: ModelingGroupState }) {
  if (state.mdlState === 'PUBLISHED') {
    return (
      <span
        className="da-badge"
        style={{ color: 'var(--da-success)', borderColor: 'var(--da-success)' }}
      >
        已发布 v{state.mdlVersion}
      </span>
    );
  }
  if (state.mdlState === 'DIRTY') {
    return (
      <span className="da-badge" style={{ color: 'var(--da-warn)', borderColor: 'var(--da-warn)' }}>
        有未生效变更{state.mdlVersion > 0 ? ` · 当前 v${state.mdlVersion}` : ''}
      </span>
    );
  }
  if (state.mdlState === 'INITIALIZING') {
    return <span className="da-badge">基础模型初始化中</span>;
  }
  if (state.mdlState === 'FAILED') {
    return (
      <span
        className="da-badge"
        style={{ color: 'var(--da-danger)', borderColor: 'var(--da-danger)' }}
      >
        基础模型初始化失败
      </span>
    );
  }
  return <span className="da-badge">暂无基础模型</span>;
}

/** specs/027: maturity badge — quality signal only; never implies modeling blocks querying. */
function MaturityBadge({ level }: { level: Maturity }) {
  const conf = {
    baseline: { text: '基线语义', color: 'var(--da-text-3)' },
    partial: { text: '语义完善中', color: 'var(--da-warn)' },
    refined: { text: '语义已完善', color: 'var(--da-success)' },
  }[level];
  return (
    <span
      className="da-badge"
      style={{ color: conf.color, borderColor: conf.color }}
      title="完善度影响问数的口径准确性，不影响能否问数"
    >
      {conf.text}
    </span>
  );
}

function JoinTypeSelect({
  value,
  onChange,
  allowAuto = true,
}: {
  value: string;
  onChange: (v: string) => void;
  allowAuto?: boolean;
}) {
  return (
    <select
      className="da-input"
      value={value}
      onChange={e => onChange(e.target.value)}
      style={{ width: 108, fontSize: '0.78rem', padding: '2px 6px' }}
    >
      {allowAuto && <option value="">自动</option>}
      {JOIN_OPTIONS.map(j => (
        <option key={j} value={j}>
          {JOIN_LABEL[j]}
        </option>
      ))}
    </select>
  );
}

/** Column dropdown: value is the physical column name, label prefers the original header. */
function ColumnSelect({
  columns,
  value,
  onChange,
  style,
}: {
  columns: DatasetColumn[];
  value: string;
  onChange: (v: string) => void;
  style?: CSSProperties;
}) {
  return (
    <select
      className="da-input"
      value={value}
      onChange={e => onChange(e.target.value)}
      style={{ flex: 1, minWidth: 0, fontSize: '0.82rem', ...style }}
    >
      <option value="">选择列…</option>
      {columns.map(c => (
        <option key={c.name} value={c.name}>
          {columnLabel(c)}
        </option>
      ))}
    </select>
  );
}

interface MeasureDraft {
  name: string;
  column: string;
  agg: string;
  caseOn: boolean;
  caseColumn: string;
  caseOp: string;
  caseValue: string;
}

interface DimensionDraft {
  name: string;
  column: string;
}

interface TimeDimDraft {
  name: string;
  column: string;
  granularity: string;
}

interface CubeDraft {
  /** null = new cube; otherwise the cube being edited. */
  id: string | null;
  name: string;
  baseDatasetId: string;
  description: string;
  measures: MeasureDraft[];
  dimensions: DimensionDraft[];
  timeDimensions: TimeDimDraft[];
}

interface ManualDraft {
  sourceDatasetId: string;
  sourceColumn: string;
  targetDatasetId: string;
  targetColumn: string;
  joinType: string;
}

function parseMeasure(m: CubeItem): MeasureDraft {
  const cf = str(m.caseFilter);
  const parts = cf.split(/\s+/).filter(Boolean);
  return {
    name: str(m.name),
    column: str(m.column),
    agg: str(m.agg) || 'SUM',
    caseOn: cf !== '',
    caseColumn: parts[0] ?? '',
    caseOp: parts[1] ?? '=',
    caseValue: parts
      .slice(2)
      .join(' ')
      .replace(/^'(.*)'$/, '$1'),
  };
}

function cubeToDraft(c: ModelingCube): CubeDraft {
  return {
    id: c.id,
    name: c.name,
    baseDatasetId: c.baseDatasetId,
    description: c.description ?? '',
    measures: c.measures.map(parseMeasure),
    dimensions: c.dimensions.map(d => ({ name: str(d.name), column: str(d.column) })),
    timeDimensions: c.timeDimensions.map(t => ({
      name: str(t.name),
      column: str(t.column),
      granularity: str(t.granularity) || 'MONTH',
    })),
  };
}

function suggestionToDraft(s: CubeSuggestion): CubeDraft {
  return {
    id: null,
    name: s.name,
    baseDatasetId: s.datasetId,
    description: s.reason,
    measures: s.measures.map(m => ({
      ...parseMeasure(m),
      caseOn: str(m.caseFilter) !== '',
    })),
    dimensions: s.dimensions.map(d => ({ name: str(d.name), column: str(d.column) })),
    timeDimensions: s.timeDimensions.map(t => ({
      name: str(t.name),
      column: str(t.column),
      granularity: str(t.granularity) || 'MONTH',
    })),
  };
}

function emptyDraft(datasets: Dataset[]): CubeDraft {
  const base = datasets[0]?.id ?? '';
  return {
    id: null,
    name: '',
    baseDatasetId: base,
    description: '',
    measures: [{ name: '', column: '', agg: 'SUM', caseOn: false, caseColumn: '', caseOp: '=', caseValue: '' }],
    dimensions: [],
    timeDimensions: [],
  };
}

function draftToRequest(d: CubeDraft): CubeRequest {
  return {
    name: d.name.trim(),
    baseDatasetId: d.baseDatasetId,
    description: d.description.trim() || null,
    measures: d.measures
      .filter(m => m.column && m.name.trim())
      .map(m => {
        const item: CubeItem = { name: m.name.trim(), column: m.column, agg: m.agg };
        if (m.caseOn && m.caseColumn && m.caseValue.trim()) {
          item.caseFilter = `${m.caseColumn} ${m.caseOp} ${caseLiteral(m.caseValue.trim())}`;
        }
        return item;
      }),
    dimensions: d.dimensions
      .filter(x => x.column && x.name.trim())
      .map(x => ({ name: x.name.trim(), column: x.column })),
    timeDimensions: d.timeDimensions
      .filter(x => x.column && x.name.trim())
      .map(x => ({ name: x.name.trim(), column: x.column, granularity: x.granularity })),
  };
}

function validateDraft(d: CubeDraft): string | null {
  if (!d.name.trim()) return '请填写 Cube 名称';
  if (!d.baseDatasetId) return '请选择基准数据集';
  const req = draftToRequest(d);
  if (req.measures.length + req.dimensions.length + req.timeDimensions.length === 0) {
    return '至少需要一个有效指标、维度或时间维度（名称与列都要填写）';
  }
  return null;
}

interface ViewDraft {
  /** null = new view; otherwise the view being edited. */
  id: string | null;
  name: string;
  baseDatasetId: string;
  sqlText: string;
  description: string;
}

function viewToDraft(v: ModelingView): ViewDraft {
  return {
    id: v.id,
    name: v.name,
    baseDatasetId: v.baseDatasetId ?? '',
    sqlText: v.sqlText,
    description: v.description ?? '',
  };
}

function emptyViewDraft(): ViewDraft {
  return { id: null, name: '', baseDatasetId: '', sqlText: '', description: '' };
}

/** Client-side mirror of the server's single-SELECT/WITH shape check (specs/011 M2). */
function validateViewDraft(d: ViewDraft): string | null {
  if (!d.name.trim()) return '请填写视图名称';
  if (!d.sqlText.trim()) return '请填写 SQL';
  const head = d.sqlText.trim().toUpperCase();
  if (!head.startsWith('SELECT') && !head.startsWith('WITH')) {
    return 'SQL 必须以 SELECT 或 WITH 开头（单条语句）';
  }
  return null;
}

/**
 * Semantic-modeling Tab (specs/010 M1 + M2): group MDL state header, dataset model card, relation
 * candidates with human review (confirm / redirect / reject / manual add), cube candidates with
 * an editable configuration form whose fields map 1:1 to the cube YAML, and the MDL publish
 * panel (assemble / validate / publish with a diff against the published snapshot).
 *
 * specs/030 (ADR 0041): mounted as the standalone fullscreen route `/configure/modeling/:groupId`
 * — AppShell hides the global chat sidebar here and this page owns the whole viewport, so the
 * asset view gets the full width (the workbench is the primary surface, not a nested tab).
 */
export default function SemanticModelingPage() {
  const { groupId = '' } = useParams();
  const navigate = useNavigate();
  const [groupDetail, setGroupDetail] = useState<GroupDetail | null>(null);
  const [businessDocument, setBusinessDocument] = useState('');
  const documentRevision = useRef(0);
  const [documentUpload, setDocumentUpload] = useState<DocumentUploadState>({ status: 'idle', filename: '' });
  const documentGroup = useRef(groupId);
  documentGroup.current = groupId;
  const [searchParams, setSearchParams] = useSearchParams();
  const [overview, setOverview] = useState<ModelingOverview | null>(null);
  const [workflow, setWorkflow] = useState<ModelingWorkflow | null>(null);
  const [workflowError, setWorkflowError] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState<string | null>(null);
  const [candidates, setCandidates] = useState<CubeSuggestion[] | null>(null);
  const [draft, setDraft] = useState<CubeDraft | null>(null);
  const [draftError, setDraftError] = useState<string | null>(null);
  const [manualOpen, setManualOpen] = useState(false);
  const [chatDraft, setChatDraft] = useState<{ groupId: string; message: string; requestId: number; submit: boolean } | null>(null);
  const [nativeSession, setNativeSession] = useState<{ groupId: string; pending: boolean; error?: string } | null>(null);
  const onSessionReady = useCallback((pending: boolean, error?: string) => {
    if (documentGroup.current === groupId) setNativeSession({ groupId, pending, error });
  }, [groupId]);
  const intakeReady = workflow?.groupId === groupId && nativeSession?.groupId === groupId;
  const showConversation = (workflow?.groupId === groupId && workflow.questionSummary.total > 0)
    || chatDraft?.groupId === groupId || (nativeSession?.groupId === groupId && nativeSession.pending);
  /** Hidden input for the unified "上传语义文档" entries (specs/028). */
  const semanticDocRef = useRef<HTMLInputElement | null>(null);
  /** specs/030: structured workspace view (models/cubes/views with backing YAML paths). */
  const [mdlView, setMdlView] = useState<MdlView | null>(null);
  /** specs/030: workspace files differing from the published snapshot — row-level 未发布 marks. */
  const [dirtyPaths, setDirtyPaths] = useState<Set<string>>(new Set());
  const [viewDraft, setViewDraft] = useState<ViewDraft | null>(null);
  const [viewDraftError, setViewDraftError] = useState<string | null>(null);
  const [enhance, setEnhance] = useState<EnhanceOverview | null>(null);
  const [manual, setManual] = useState<ManualDraft>({
    sourceDatasetId: '',
    sourceColumn: '',
    targetDatasetId: '',
    targetColumn: '',
    joinType: '',
  });

  // specs/030 (ADR 0041): tab + selected asset live in the URL (?asset=&selected=) so refresh,
  // bookmarking and sharing keep the position; the modeling route itself carries the group.
  const urlTab = searchParams.get('asset');
  const tab: TabKey = urlTab && (TAB_KEYS as string[]).includes(urlTab) ? (urlTab as TabKey) : 'overview';
  const selectedParam = searchParams.get('selected');
  const detailOpen = TAB_KEYS.slice(4).includes(tab);

  const setTab = (key: TabKey) => {
    const next = new URLSearchParams(searchParams);
    next.set('asset', key);
    next.delete('selected');
    setSearchParams(next, { replace: true });
  };

  const submitQuestions = (message: string) => {
    setChatDraft(previous => ({ groupId, message, requestId: (previous?.requestId ?? 0) + 1, submit: true }));
    setTab('modeling');
  };

  const setSelected = (key: string | null) => {
    const next = new URLSearchParams(searchParams);
    if (key) next.set('selected', key);
    else next.delete('selected');
    setSearchParams(next, { replace: true });
  };

  useEffect(() => {
    if (urlTab || !workflow || workflow.groupId !== groupId) return;
    const stageTab: TabKey = workflow.queryAvailable && workflow.questionSummary.total === 0 ? 'modeling' : workflow.stage === 'MODELING' ? 'modeling'
      : ['VALIDATION', 'CONFIRMATION'].includes(workflow.stage) ? 'questions'
        : workflow.stage === 'PUBLICATION' ? 'publish' : workflow.queryAvailable ? 'modeling' : 'overview';
    const next = new URLSearchParams(searchParams);
    next.set('asset', stageTab);
    setSearchParams(next, { replace: true });
  }, [workflow, urlTab, groupId, searchParams, setSearchParams]);

  const refreshGeneration = useRef(0);
  const refresh = useCallback(async () => {
    const request = ++refreshGeneration.current;
    // specs/037: settle both sources independently — one failing source must not blank the page.
    const results = await Promise.allSettled([getModelingOverview(groupId), getMdlView(groupId), getModelingWorkflow(groupId)]);
    if (request !== refreshGeneration.current) return;
    if (results[2].status === 'fulfilled') { setWorkflow(results[2].value); setWorkflowError(null); }
    else { setWorkflow(null); setWorkflowError(msg(results[2].reason)); }
    let failed: unknown = null;
    if (results[0].status === 'fulfilled') setOverview(results[0].value);
    else failed ??= results[0].reason;
    if (results[1].status === 'fulfilled') setMdlView(results[1].value);
    else failed ??= results[1].reason;
    try {
      const preview = await getMdlPreview(groupId);
      if (request !== refreshGeneration.current) return;
      const published = new Map(preview.publishedFiles.map(f => [f.path, f.content]));
      setDirtyPaths(
        new Set(
          preview.files.filter(f => published.get(f.path) !== f.content).map(f => f.path),
        ),
      );
    } catch {
      setDirtyPaths(new Set());
    }
    setError(failed ? msg(failed) : null);
  }, [groupId]);

  const refreshEnhance = useCallback(async () => {
    try {
      const value = await getEnhanceOverview(groupId);
      if (documentGroup.current === groupId) setEnhance(value);
    } catch (e) {
      setError(msg(e));
    }
  }, [groupId]);

  useEffect(() => {
    setWorkflow(null); setWorkflowError(null); setEnhance(null);
    refresh();
    refreshEnhance();
    return () => { refreshGeneration.current++; };
  }, [refresh, refreshEnhance]);

  // Group name for the fullscreen header back-link (loaded once per group).
  useEffect(() => {
    let cancelled = false;
    setGroupDetail(null);
    setDocumentUpload({ status: 'idle', filename: '' });
    setBusinessDocument('');
    const revision = ++documentRevision.current;
    getGroupDetail(groupId)
      .then(d => { if (!cancelled) { setGroupDetail(d); if (revision === documentRevision.current) setBusinessDocument(d.knowledge ?? ''); } })
      .catch(() => { /* header falls back to a generic label */ });
    return () => { cancelled = true; };
  }, [groupId]);

  // specs/027: the chat panel (and any sibling surface) broadcasts changes; refetch in place —
  // the active tab stays untouched so the user never loses their position.
  useEffect(() => {
    const onUpdate = (e: Event) => {
      const detail = (e as CustomEvent<{ groupId?: string }>).detail;
      if (!detail || detail.groupId === groupId) refresh();
    };
    window.addEventListener('modeling:updated', onUpdate);
    return () => window.removeEventListener('modeling:updated', onUpdate);
  }, [groupId, refresh]);

  useEffect(() => {
    if (enhance?.task?.status !== 'RUNNING') return;
    const timer = window.setInterval(refreshEnhance, 3000);
    return () => window.clearInterval(timer);
  }, [enhance?.task?.status, refreshEnhance]);

  const datasets = overview?.datasets ?? [];
  const datasetsById = useMemo(() => {
    const m = new Map<string, Dataset>();
    for (const d of datasets) m.set(d.id, d);
    return m;
  }, [datasets]);

  // specs/030: asset-browser entries — the workspace YAML backing each model/cube/view.
  const modelEntries: AssetEntry[] = useMemo(
    () =>
      (mdlView?.models ?? []).map(m => ({
        key: m.modelName,
        title: m.datasetName || m.modelName,
        subtitle: m.tableName ? `${m.tableName} · ${m.columns.length} 列` : `${m.columns.length} 列`,
        file: m.path,
        dirty: dirtyPaths.has(m.path),
      })),
    [mdlView, dirtyPaths],
  );

  // specs/034: derived (ref_sql) models — the right pane streams the ref_sql.sql definition.
  const derivedEntries: AssetEntry[] = useMemo(
    () =>
      (mdlView?.derivedModels ?? []).map(m => ({
        key: m.modelName,
        title: m.modelName,
        subtitle: m.description
          ? `${m.description} · ${m.columns.length} 列`
          : `${m.columns.length} 列`,
        file: m.refSqlPath ?? m.path,
        dirty:
          dirtyPaths.has(m.path) || (m.refSqlPath !== null && dirtyPaths.has(m.refSqlPath)),
      })),
    [mdlView, dirtyPaths],
  );

  const cubeEntries: AssetEntry[] = useMemo(
    () =>
      (mdlView?.cubes ?? []).map(c => ({
        key: c.name,
        title: c.name,
        subtitle: `${c.baseModel || '—'} · 指标 ${c.measures.length} · 维度 ${c.dimensions.length}`,
        file: c.path,
        dirty: dirtyPaths.has(c.path),
      })),
    [mdlView, dirtyPaths],
  );

  const viewEntries: AssetEntry[] = useMemo(
    () =>
      (mdlView?.views ?? []).map(v => ({
        key: v.name,
        title: v.name,
        subtitle: v.description ?? undefined,
        file: v.path,
        dirty: dirtyPaths.has(v.path),
      })),
    [mdlView, dirtyPaths],
  );

  const datasetName = useCallback(
    (id: string) => {
      const dataset = datasetsById.get(id);
      return dataset ? datasetLabel(dataset) : '未知表';
    },
    [datasetsById],
  );

  // specs/036: relation rows show the three-element form — short dataset name (no description
  // tail) and the original header without the description, so each row stays one glance-readable.
  const datasetShortName = useCallback(
    (id: string) => datasetsById.get(id)?.name.trim() || '未知表',
    [datasetsById],
  );

  const datasetColumnShortName = useCallback(
    (datasetId: string, columnName: string) => {
      const column = datasetsById
        .get(datasetId)
        ?.columns.find(item => item.name === columnName);
      if (!column) return columnName;
      return column.originalName && column.originalName !== column.name
        ? `${column.originalName}（${column.name}）`
        : column.name;
    },
    [datasetsById],
  );

  const columnsOf = useCallback(
    (datasetId: string) => datasetsById.get(datasetId)?.columns ?? [],
    [datasetsById],
  );

  const datasetColumnName = useCallback(
    (datasetId: string, columnName: string) => {
      const column = datasetsById
        .get(datasetId)
        ?.columns.find(item => item.name === columnName);
      return column ? columnLabel(column) : columnName;
    },
    [datasetsById],
  );

  const pending = (overview?.relations ?? []).filter(r => r.status === 'PENDING');
  const confirmed = (overview?.relations ?? []).filter(r => r.status === 'CONFIRMED');
  const rejected = (overview?.relations ?? []).filter(r => r.status === 'REJECTED');

  // specs/030: the URL-selected asset resolved against platform entities for edit/delete actions.
  const selectedCube = (overview?.cubes ?? []).find(c => c.name === selectedParam) ?? null;
  const selectedView = (overview?.views ?? []).find(v => v.name === selectedParam) ?? null;

  // specs/027: three fixed levels from one overview fetch (+ adopted doc-enhance rules).
  const maturity: Maturity = useMemo(() => {
    if (!overview) return 'baseline';
    const confirmedRelations = overview.relations.filter(r => r.status === 'CONFIRMED').length;
    const cubes = overview.cubes.length;
    const publishedViews = overview.views.filter(v => v.status === 'PUBLISHED').length;
    const terms = overview.terms.length;
    const rules = (enhance?.proposals ?? []).filter(
      p => p.type === 'BUSINESS_RULE' && p.status === 'ADOPTED',
    ).length;
    if (confirmedRelations === 0 && cubes === 0 && publishedViews === 0 && terms === 0) {
      return 'baseline';
    }
    if ((cubes > 0 || publishedViews > 0) && (rules > 0 || terms > 0)) return 'refined';
    return 'partial';
  }, [overview, enhance]);

  const tabItems: { key: TabKey; label: string; count?: number }[] = [
    { key: 'overview', label: '1 · 数据准备' },
    { key: 'modeling', label: '2 · 对话建模' },
    { key: 'questions', label: '3 · 验证与确认' },
    { key: 'publish', label: '4 · 发布' },
    { key: 'schema', label: '表与关系', count: datasets.length },
    { key: 'derived', label: '派生模型', count: mdlView?.derivedModels.length ?? 0 },
    // specs/037: cube/view counts read the workspace (mdlView) — the same source as the tab
    // content, so counts and lists can no longer diverge after REST or chat writes.
    { key: 'cubes', label: 'Cube', count: mdlView?.cubes.length ?? 0 },
    { key: 'views', label: '视图', count: mdlView?.views.length ?? 0 },
    { key: 'glossary', label: '术语与规则', count: overview?.terms.length ?? 0 },
    { key: 'mdl', label: 'MDL' },
  ];

  async function run(label: string, fn: () => Promise<void>) {
    setBusy(label);
    setError(null);
    try {
      await fn();
    } catch (e) {
      setError(msg(e));
    } finally {
      setBusy(null);
    }
  }

  function applyRelations(relations: ModelingRelation[]) {
    setOverview(prev => (prev ? { ...prev, relations } : prev));
  }

  const onUploadSemanticDoc = async (file: File) => {
    if (documentUpload.status === 'uploading') return;
    documentRevision.current++;
    setDocumentUpload({ status: 'uploading', filename: file.name });
    try {
      const content = await uploadKnowledge(groupId, file);
      if (documentGroup.current !== groupId) return;
      setBusinessDocument(content);
      setGroupDetail(previous => previous ? { ...previous, knowledge: content } : previous);
      setDocumentUpload({ status: 'success', filename: file.name });
      await Promise.all([refreshEnhance(), refresh()]);
    } catch (e) {
      if (documentGroup.current === groupId) setDocumentUpload({ status: 'error', filename: file.name, error: msg(e) });
    }
  };

  const onTriggerEnhance = () =>
    run('enhance:run', async () => {
      await triggerEnhance(groupId);
      await refreshEnhance();
    });

  const onAdoptEnhance = (proposal: EnhanceProposal) =>
    run(`enhance:adopt:${proposal.id}`, async () => {
      await adoptEnhanceProposal(groupId, proposal.id);
      await Promise.all([refreshEnhance(), refresh()]);
    });

  const onIgnoreEnhance = (proposal: EnhanceProposal) =>
    run(`enhance:ignore:${proposal.id}`, async () => {
      await ignoreEnhanceProposal(groupId, proposal.id);
      await refreshEnhance();
    });

  const onInitializeMdl = () =>
    run('mdl:initialize', async () => {
      await initializeMdl(groupId);
      await refresh();
    });

  const onSuggestRelations = () =>
    run('relations', async () => {
      applyRelations(await suggestRelations(groupId));
    });

  const onSuggestCubes = () =>
    run('cubes', async () => {
      setCandidates(await suggestCubes(groupId));
    });

  const onConfirm = (r: ModelingRelation, joinType: string) =>
    run(`rel:${r.id}`, async () => {
      const updated = await updateRelation(groupId, r.id, {
        status: 'CONFIRMED',
        joinType: joinType || null,
      });
      applyRelations(
        (overview?.relations ?? []).map(x => (x.id === updated.id ? updated : x)),
      );
    });

  const onSwap = (r: ModelingRelation) =>
    run(`rel:${r.id}`, async () => {
      const updated = await updateRelation(groupId, r.id, { status: 'CONFIRMED', swap: true });
      applyRelations(
        (overview?.relations ?? []).map(x => (x.id === updated.id ? updated : x)),
      );
    });

  const onReject = (r: ModelingRelation) =>
    run(`rel:${r.id}`, async () => {
      const updated = await updateRelation(groupId, r.id, { status: 'REJECTED' });
      applyRelations(
        (overview?.relations ?? []).map(x => (x.id === updated.id ? updated : x)),
      );
    });

  const onManualSubmit = () =>
    run('manual', async () => {
      if (
        !manual.sourceDatasetId ||
        !manual.sourceColumn ||
        !manual.targetDatasetId ||
        !manual.targetColumn
      ) {
        throw new Error('请完整选择源表/列与目标表/列');
      }
      const created = await addManualRelation(groupId, {
        sourceDatasetId: manual.sourceDatasetId,
        sourceColumn: manual.sourceColumn,
        targetDatasetId: manual.targetDatasetId,
        targetColumn: manual.targetColumn,
        joinType: manual.joinType || null,
      });
      applyRelations([...(overview?.relations ?? []), created]);
      setManualOpen(false);
      setManual(m => ({ ...m, sourceColumn: '', targetColumn: '', joinType: '' }));
    });

  const onSaveCube = () =>
    run('cube:save', async () => {
      if (!draft) return;
      const problem = validateDraft(draft);
      if (problem) {
        setDraftError(problem);
        return;
      }
      const req = draftToRequest(draft);
      if (draft.id) {
        const saved = await updateCube(groupId, draft.id, req);
        setOverview(prev =>
          prev ? { ...prev, cubes: prev.cubes.map(c => (c.id === saved.id ? saved : c)) } : prev,
        );
      } else {
        const saved = await createCube(groupId, req);
        setOverview(prev => (prev ? { ...prev, cubes: [...prev.cubes, saved] } : prev));
        setCandidates(prev => {
          if (prev === null) return prev;
          const rest = prev.filter(c => c.name !== saved.name);
          return rest.length > 0 ? rest : null;
        });
      }
      setDraft(null);
      setDraftError(null);
      await refresh();
    });

  const onDeleteCube = (c: ModelingCube) => {
    if (!window.confirm(`删除 Cube「${c.name}」？`)) return;
    run(`cube:del:${c.id}`, async () => {
      await deleteCube(groupId, c.id);
      setOverview(prev =>
        prev ? { ...prev, cubes: prev.cubes.filter(x => x.id !== c.id) } : prev,
      );
      await refresh();
    });
  };

  const onSaveView = () =>
    run('view:save', async () => {
      if (!viewDraft) return;
      const problem = validateViewDraft(viewDraft);
      if (problem) {
        setViewDraftError(problem);
        return;
      }
      const req = {
        name: viewDraft.name.trim(),
        baseDatasetId: viewDraft.baseDatasetId || null,
        sqlText: viewDraft.sqlText,
        description: viewDraft.description.trim() || null,
      };
      if (viewDraft.id) {
        const saved = await updateView(groupId, viewDraft.id, req);
        setOverview(prev =>
          prev ? { ...prev, views: prev.views.map(v => (v.id === saved.id ? saved : v)) } : prev,
        );
      } else {
        const saved = await createView(groupId, req);
        setOverview(prev => (prev ? { ...prev, views: [...prev.views, saved] } : prev));
      }
      setViewDraft(null);
      setViewDraftError(null);
      await refresh();
    });

  const onDeleteView = (v: ModelingView) => {
    if (!window.confirm(`删除视图「${v.name}」？`)) return;
    run(`view:del:${v.id}`, async () => {
      await deleteView(groupId, v.id);
      setOverview(prev =>
        prev ? { ...prev, views: prev.views.filter(x => x.id !== v.id) } : prev,
      );
      await refresh();
    });
  };

  function renderRelationRow(r: ModelingRelation, kind: 'pending' | 'confirmed' | 'rejected') {
    const rowBusy = busy === `rel:${r.id}`;
    return (
      <RelationRow
        key={r.id}
        relation={r}
        kind={kind}
        rowBusy={rowBusy}
        datasetName={datasetShortName}
        columnName={datasetColumnShortName}
        onConfirm={jt => onConfirm(r, jt)}
        onSwap={() => onSwap(r)}
        onReject={() => onReject(r)}
      />
    );
  }

  return (
    <div className="modeling-workbench" style={{ flex: 1, display: 'flex', flexDirection: 'column', minHeight: 0, overflow: 'hidden', padding: 16, gap: 12 }}>
      <div style={{ display: 'flex', flexDirection: 'column', gap: 12, flexShrink: 0 }}>
      {error && (
        <div className="da-card" style={{ borderColor: 'var(--da-danger)' }}>
          <div className="da-small" style={{ color: 'var(--da-danger)' }}>
            {error}
          </div>
        </div>
      )}

      {/* ---------- state header (specs/030 fullscreen: back-link + group name on the workbench top bar) ---------- */}
      <div className="da-card" style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
        <button
          className="da-btn da-btn-sm"
          onClick={() => navigate(`/configure/datasets/${groupId}`)}
          title="返回知识库"
        >
          ← 返回
        </button>
        <div className="da-h2" style={{ margin: 0 }}>
          {groupDetail?.group.name ?? '语义模型'}
        </div>
        <Icon name="model" />
        <span className="da-small" style={{ color: 'var(--da-text-3)' }}>
          语义建模
        </span>
        {workflow ? <span className="da-badge">{workflow.queryAvailable ? `已发布 v${workflow.publishedVersion}` : '基础模型待准备'}{workflow.draftChanged ? ' · 有未发布草稿' : ''}</span> : overview && <MdlStateBadge state={overview.group} />}
        {overview && workflow?.queryAvailable && <MaturityBadge level={maturity} />}
        {overview?.group.mdlPublishedAt && (
          <span className="da-small" style={{ color: 'var(--da-text-3)' }}>
            发布于 {overview.group.mdlPublishedAt.slice(0, 19).replace('T', ' ')}
          </span>
        )}
        <div style={{ flex: 1 }} />
        <button className="da-btn" style={{ borderColor: 'var(--da-primary)', color: 'var(--da-primary)', padding: '9px 18px', fontWeight: 600 }}
          onClick={() => setTab(detailOpen ? 'modeling' : 'schema')}>
          <Icon name="model" size="sm" /> {detailOpen ? '← 返回对话建模' : '查看语义模型'}
          {!detailOpen && <span style={{ display: 'block', fontSize: 11, fontWeight: 400 }}>模型 · Cube · 视图 · MDL</span>}
        </button>
      </div>

      {/* ---------- asset tab nav (specs/030 fullscreen: primary navigation of the workbench —
          segmented control, active tab is a solid pill so the tabs are unmistakable) ---------- */}
      <div
        style={{
          display: 'flex',
          gap: 4,
          padding: 6,
          background: 'var(--da-surface)',
          border: '1px solid var(--da-border)',
          borderRadius: 'var(--da-radius-lg, 10px)',
          overflowX: 'auto',
          /* never let the column flex squeeze the nav bar — the guide card below used to
             overlap the tabs when the main column overflowed (specs/030 fullscreen fix) */
          flexShrink: 0,
          position: 'relative',
          zIndex: 1,
        }}
      >
        {tabItems.slice(0, 4).map(item => {
          const active = tab === item.key;
          return (
            <button
              key={item.key}
              onClick={() => setTab(item.key)}
              className="da-btn"
              style={{
                border: active ? '1px solid rgba(79, 70, 229, 0.45)' : '1px solid transparent',
                background: active ? 'var(--da-primary-subtle)' : 'transparent',
                color: active ? 'var(--da-primary)' : 'var(--da-text-2)',
                fontWeight: active ? 600 : 500,
                fontSize: '0.92rem',
                padding: '8px 16px',
                borderRadius: 8,
                flexShrink: 0,
              }}
            >
              {item.label}
              {item.count !== undefined && (
                <span
                  style={{
                    fontSize: '0.72rem',
                    fontWeight: 400,
                    color: active ? 'var(--da-primary)' : 'var(--da-text-3)',
                    background: active ? 'rgba(79, 70, 229, 0.12)' : 'var(--da-surface-sunken, #eee)',
                    borderRadius: 8,
                    padding: '0 6px',
                  }}
                >
                  {item.count}
                </span>
              )}
            </button>
          );
        })}
        {detailOpen && <select className="da-input" aria-label="模型详情" value={tab}
          onChange={event => setTab(event.target.value as TabKey)} style={{ width: 180, marginLeft: 'auto' }}>
          {tabItems.slice(4).map(item => <option key={item.key} value={item.key}>{item.label}{item.count !== undefined ? ` (${item.count})` : ''}</option>)}
        </select>}
      </div>
      </div>
      <div className="modeling-workbench-body" style={{ display: 'flex', flex: 1, minHeight: 0, gap: 16 }}>
      <div className="modeling-progress-column" style={{ flex: tab === 'modeling' ? '0 0 300px' : 1, minWidth: 0, overflowY: 'auto', display: tab === 'modeling' && !showConversation && workflow?.queryAvailable ? 'none' : 'flex', flexDirection: 'column', gap: 12 }}>
      {overview?.group.mdlLastError && !workflow?.queryAvailable && (
        <div className="da-alert da-alert-error">
          <div>{overview.group.mdlLastError}</div>
          <button
            className="da-btn da-btn-sm"
            style={{ marginTop: 8 }}
            disabled={busy !== null || datasets.length === 0}
            onClick={onInitializeMdl}
          >
            {busy === 'mdl:initialize' ? '生成中…' : '重新生成基础 MDL'}
          </button>
        </div>
      )}

      {!detailOpen && <ModelingWorkflowGuide workflow={workflow} error={workflowError} busy={busy !== null}
        onNext={type => {
          if (type === 'PREPARE_DATA') navigate(`/configure/datasets/${groupId}`);
          else if (type === 'INITIALIZE') void onInitializeMdl();
          else if (type === 'MODEL' || type === 'ADD_QUESTIONS') { setTab('modeling'); }
          else if (type === 'VALIDATE' || type === 'CONFIRM') setTab('questions');
          else if (type === 'PUBLISH') setTab('publish');
          else if (type === 'QUERY') navigate(`/chat?groups=${encodeURIComponent(groupId)}`);
          else setTab('overview');
        }} hideModelAction={tab === 'modeling'} hideNextAction={tab === 'questions' || tab === 'publish'} />}
      {detailOpen && <div className="da-card da-small">当前工作区草稿 · 问数使用已发布 v{workflow?.publishedVersion ?? 0}。在 MDL 页查看已发布工程快照与差异。</div>}

      {tab === 'overview' && <div className="da-card" style={{ padding: 16 }}>
        <div className="da-h2">准备数据与业务资料</div>
        <p className="da-small">选择表或上传 CSV / Excel 后，系统生成并发布基础模型。只有发布成功才可问数；业务口径可随后逐步完善。</p>
        <p className="da-small">业务文档可选。没有文档也可以直接通过对话建模，不必先填写全部问题。</p>
        <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
          <button className="da-btn" onClick={() => navigate(`/configure/datasets/${groupId}`)}>选择表或上传数据</button>
          <button className="da-btn da-btn-primary" onClick={() => { setTab('modeling'); }}>进入对话建模</button>
        </div>
      </div>}

      {tab === 'modeling' && <ModelingQuestionsPanel key={`${groupId}-requirements`} groupId={groupId} mode="requirements"
        hideAdd={!showConversation} onAddQuestions={submitQuestions} onDiscuss={message => {
        setChatDraft(previous => ({ groupId, message, requestId: (previous?.requestId ?? 0) + 1, submit: false }));
      }} />}
      {tab === 'questions' && <ModelingQuestionsPanel key={`${groupId}-acceptance`} groupId={groupId} mode="acceptance" workflow={workflow}
        onAddQuestions={submitQuestions} onPublish={() => setTab('publish')} onQuery={() => navigate(`/chat?groups=${encodeURIComponent(groupId)}`)} onDiscuss={message => {
        setTab('modeling'); setChatDraft(previous => ({ groupId, message, requestId: (previous?.requestId ?? 0) + 1, submit: false }));
      }} />}
      {tab === 'publish' && <MdlPublishPanel groupId={groupId} workflow={workflow} onPublished={refresh}
        onReview={() => setTab('questions')} />}

      {tab === 'schema' && (
        <>
      <div className="da-small" style={{ color: 'var(--da-text-3)', marginBottom: 4 }}>
        关系建议 = 规则推断（同名/共享列）+ AI 建议，join 类型由数据唯一性探测（不靠猜测）。人工确认或拒绝的记录在重新分析后仍保留。需要补充业务说明时，返回「对话建模」。
      </div>

      {/* ---------- models: asset list + workspace YAML projection (specs/030) ---------- */}
      <div className="da-card">
        <div style={{ display: 'flex', alignItems: 'center', gap: 10, marginBottom: 10 }}>
          <div className="da-h2" style={{ margin: 0 }}>
            模型 ({modelEntries.length})
          </div>
          <span className="da-small" style={{ color: 'var(--da-text-3)' }}>
            点击模型查看 wren 工程的 metadata.yml（工作区即真相，UI 是 YAML 的投影）
          </span>
        </div>
        <AssetYamlBrowser
          groupId={groupId}
          entries={modelEntries}
          selectedKey={tab === 'schema' ? selectedParam : null}
          onSelect={setSelected}
          emptyText="暂无模型。关联数据源或上传数据后，平台自动播种基线模型。"
        />
      </div>

      {/* ---------- relations ---------- */}
      <div className="da-card">
        <div style={{ display: 'flex', alignItems: 'center', gap: 10, marginBottom: 10 }}>
          <div className="da-h2" style={{ margin: 0 }}>
            关系候选 ({pending.length + confirmed.length})
          </div>
          <span className="da-small" style={{ color: 'var(--da-text-3)' }}>
            待审 {pending.length} · 已确认 {confirmed.length} · 已拒绝 {rejected.length}
          </span>
          <div style={{ flex: 1 }} />
          <button
            className="da-btn da-btn-sm"
            disabled={busy !== null || datasets.length < 2}
            onClick={onSuggestRelations}
            title="由规则与 AI 重新推断关系候选（已确认/拒绝的记录保留）"
          >
            {busy === 'relations' ? '分析中…' : '重新分析关系'}
          </button>
          <button
            className="da-btn da-btn-sm"
            disabled={busy !== null || datasets.length < 2}
            onClick={() => setManualOpen(o => !o)}
          >
            {manualOpen ? '收起' : '+ 手工添加'}
          </button>
        </div>

        {manualOpen && (
          <div
            className="da-card"
            style={{ background: 'var(--da-surface-sunken)', marginBottom: 10, padding: 12 }}
          >
            <div className="da-small" style={{ marginBottom: 8 }}>
              手工添加关系（保存后立即确认，重新分析关系时不会被清除）：
            </div>
            <div style={{ display: 'flex', alignItems: 'center', gap: 6, flexWrap: 'wrap' }}>
              <select
                className="da-input"
                value={manual.sourceDatasetId}
                onChange={e =>
                  setManual(m => ({ ...m, sourceDatasetId: e.target.value, sourceColumn: '' }))
                }
                style={{ width: 170 }}
              >
                <option value="">源表…</option>
                {datasets.map(d => (
                  <option key={d.id} value={d.id}>
                    {datasetLabel(d)}
                  </option>
                ))}
              </select>
              <ColumnSelect
                columns={columnsOf(manual.sourceDatasetId)}
                value={manual.sourceColumn}
                onChange={v => setManual(m => ({ ...m, sourceColumn: v }))}
                style={{ width: 170, flex: 'none' }}
              />
              <Icon name="chevron" />
              <select
                className="da-input"
                value={manual.targetDatasetId}
                onChange={e =>
                  setManual(m => ({ ...m, targetDatasetId: e.target.value, targetColumn: '' }))
                }
                style={{ width: 170 }}
              >
                <option value="">目标表…</option>
                {datasets.map(d => (
                  <option key={d.id} value={d.id}>
                    {datasetLabel(d)}
                  </option>
                ))}
              </select>
              <ColumnSelect
                columns={columnsOf(manual.targetDatasetId)}
                value={manual.targetColumn}
                onChange={v => setManual(m => ({ ...m, targetColumn: v }))}
                style={{ width: 170, flex: 'none' }}
              />
              <JoinTypeSelect
                value={manual.joinType}
                onChange={v => setManual(m => ({ ...m, joinType: v }))}
              />
              <button
                className="da-btn da-btn-primary da-btn-sm"
                disabled={busy !== null}
                onClick={onManualSubmit}
              >
                {busy === 'manual' ? '保存中…' : '保存'}
              </button>
            </div>
          </div>
        )}

        {pending.length === 0 && confirmed.length === 0 && rejected.length === 0 ? (
          <div className="da-small">
            暂无关系。点击「重新分析关系」由规则与 AI 生成候选，或手工添加。
          </div>
        ) : (
          <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
            {pending.map(r => renderRelationRow(r, 'pending'))}
            {confirmed.map(r => renderRelationRow(r, 'confirmed'))}
            {confirmed.length > 0 && rejected.length > 0 && (
              <div
                style={{
                  borderTop: '1px solid var(--da-border)',
                  marginTop: 4,
                  paddingTop: 6,
                }}
              />
            )}
            {rejected.map(r => renderRelationRow(r, 'rejected'))}
          </div>
        )}
      </div>
        </>
      )}

      {/* ---------- derived models: ref_sql-defined models (specs/034, ADR 0042; the assistant never creates them on its own — manual-edit carrier per specs/035) ---------- */}
      {tab === 'derived' && (
        <div className="da-card">
          <div style={{ display: 'flex', alignItems: 'center', gap: 10, marginBottom: 10 }}>
            <div className="da-h2" style={{ margin: 0 }}>
              派生模型 ({derivedEntries.length})
            </div>
            <span className="da-small" style={{ color: 'var(--da-text-3)' }}>
              人工编辑的 ref_sql 命名口径（models/&lt;名称&gt;/ref_sql.sql），发布后可按模型名直接查询；
              跨表 JOIN/窗口/CTE 类复杂口径建议用「对话建模」创建命名视图。点击右侧查看 ref_sql.sql 与列清单
            </span>
          </div>
          <AssetYamlBrowser
            groupId={groupId}
            entries={derivedEntries}
            selectedKey={tab === 'derived' ? selectedParam : null}
            onSelect={setSelected}
            emptyText="暂无派生模型。派生模型为人工编辑资产：在工程文件 models/&lt;名称&gt;/ref_sql.sql 中编写口径 SQL 后发布生效；复杂口径也可用右上角「对话建模」创建命名视图承载。"
          />
        </div>
      )}

      {tab === 'cubes' && (
      <div className="da-card">
        <div style={{ display: 'flex', alignItems: 'center', gap: 10, marginBottom: 10 }}>
          <div className="da-h2" style={{ margin: 0 }}>
            语义 Cube ({overview?.cubes.length ?? 0})
          </div>
          <div style={{ flex: 1 }} />
          <button
            className="da-btn da-btn-sm"
            disabled={busy !== null || datasets.length === 0}
            onClick={onSuggestCubes}
          >
            {busy === 'cubes' ? '生成中…' : 'AI 提议'}
          </button>
          <button
            className="da-btn da-btn-primary da-btn-sm"
            disabled={busy !== null || datasets.length === 0}
            onClick={() => {
              setDraftError(null);
              setDraft(emptyDraft(datasets));
            }}
          >
            + 新建 Cube
          </button>
        </div>
        <div className="da-small" style={{ color: 'var(--da-text-3)', marginBottom: 10 }}>
          Cube = 指标的聚合视图（指标聚合函数 + 维度 + 时间维度），供已发布组的问数工具使用。
        </div>

        {candidates && (
          <div style={{ display: 'flex', flexDirection: 'column', gap: 8, marginBottom: 12 }}>
            <div className="da-small" style={{ fontWeight: 600 }}>
              AI 提议（{candidates.length}）
            </div>
            {candidates.length === 0 ? (
              <div className="da-small">
                没有新的提议（可能是模型不可用或没有合适的聚合表）。
              </div>
            ) : (
              candidates.map((s, i) => (
                <div
                  key={`cand-${i}`}
                  className="da-card"
                  style={{
                    background: 'var(--da-primary-subtle)',
                    padding: 10,
                    borderColor: 'var(--da-primary)',
                  }}
                >
                  <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                    <span style={{ fontWeight: 600 }}>{s.name}</span>
                    <span className="da-badge">{datasetName(s.datasetId)}</span>
                    <div style={{ flex: 1 }} />
                    <button
                      className="da-btn da-btn-primary da-btn-sm"
                      disabled={busy !== null}
                      onClick={() => {
                        setDraftError(null);
                        setDraft(suggestionToDraft(s));
                      }}
                    >
                      采纳
                    </button>
                    <button
                      className="da-btn da-btn-sm"
                      onClick={() =>
                        setCandidates(prev => {
                          const rest = (prev ?? []).filter((_, idx) => idx !== i);
                          return rest.length > 0 ? rest : null;
                        })
                      }
                    >
                      忽略
                    </button>
                  </div>
                  {s.reason && (
                    <div className="da-small" style={{ marginTop: 4 }}>
                      {s.reason}
                    </div>
                  )}
                  <div className="da-small" style={{ color: 'var(--da-text-3)', marginTop: 4 }}>
                    指标 {s.measures.length} · 维度 {s.dimensions.length} · 时间维度{' '}
                    {s.timeDimensions.length}
                  </div>
                </div>
              ))
            )}
          </div>
        )}

        <AssetYamlBrowser
          groupId={groupId}
          entries={cubeEntries}
          selectedKey={tab === 'cubes' ? selectedParam : null}
          onSelect={setSelected}
          emptyText="暂无 Cube。可用「AI 提议」或手工新建。"
        />
        {tab === 'cubes' && selectedCube && (
          <div style={{ display: 'flex', gap: 6, marginTop: 10, alignItems: 'center' }}>
            <span className="da-small" style={{ color: 'var(--da-text-3)', marginRight: 'auto' }}>
              已选中「{selectedCube.name}」
            </span>
            <button
              className="da-btn da-btn-sm"
              disabled={busy !== null}
              onClick={() => {
                setDraftError(null);
                setDraft(cubeToDraft(selectedCube));
              }}
            >
              编辑
            </button>
            <button
              className="da-btn da-btn-danger da-btn-sm"
              disabled={busy !== null}
              onClick={() => onDeleteCube(selectedCube)}
            >
              删除
            </button>
          </div>
        )}
      </div>
      )}

      {tab === 'views' && (
      <div className="da-card">
        <div style={{ display: 'flex', alignItems: 'center', gap: 10, marginBottom: 10 }}>
          <div className="da-h2" style={{ margin: 0 }}>
            语义视图 ({overview?.views.length ?? 0})
          </div>
          <div style={{ flex: 1 }} />
          <button
            className="da-btn da-btn-primary da-btn-sm"
            disabled={busy !== null}
            onClick={() => {
              setViewDraftError(null);
              setViewDraft(emptyViewDraft());
            }}
          >
            + 新建视图
          </button>
        </div>
        <div className="da-small" style={{ color: 'var(--da-text-3)', marginBottom: 10 }}>
          视图 = 一条 SELECT/WITH 固化的 SQL 口径（HAVING 阈值、窗口函数、多表 JOIN 等
          Cube 表达不了的口径），发布后问数可直接按视图名查询。
        </div>
        <AssetYamlBrowser
          groupId={groupId}
          entries={viewEntries}
          selectedKey={tab === 'views' ? selectedParam : null}
          onSelect={setSelected}
          emptyText="暂无视图。可用「对话建模」让助手沉淀，或手工新建。"
        />
        {tab === 'views' && selectedView && (
          <div style={{ display: 'flex', gap: 6, marginTop: 10, alignItems: 'center' }}>
            <span className="da-small" style={{ color: 'var(--da-text-3)', marginRight: 'auto' }}>
              已选中「{selectedView.name}」
            </span>
            <button
              className="da-btn da-btn-sm"
              disabled={busy !== null}
              onClick={() => {
                setViewDraftError(null);
                setViewDraft(viewToDraft(selectedView));
              }}
            >
              编辑
            </button>
            <button
              className="da-btn da-btn-danger da-btn-sm"
              disabled={busy !== null}
              onClick={() => onDeleteView(selectedView)}
            >
              删除
            </button>
          </div>
        )}
      </div>
      )}

      {tab === 'glossary' && (
      <div className="da-card">
        <div style={{ display: 'flex', alignItems: 'center', gap: 10, marginBottom: 10 }}>
          <div className="da-h2" style={{ margin: 0 }}>
            文档语义增强
          </div>
          {enhance?.task && (
            <span
              className="da-badge"
              style={
                enhance.task.status === 'FAILED'
                  ? { color: 'var(--da-danger)', borderColor: 'var(--da-danger)' }
                  : enhance.task.status === 'READY'
                    ? { color: 'var(--da-success)', borderColor: 'var(--da-success)' }
                    : undefined
              }
            >
              {enhance.task.status === 'RUNNING'
                ? '分析中'
                : enhance.task.status === 'READY'
                  ? '已完成'
                  : '分析失败'}
            </span>
          )}
          <div style={{ flex: 1 }} />
          <button
            className="da-btn da-btn-primary da-btn-sm"
            disabled={busy !== null || documentUpload.status === 'uploading'}
            onClick={() => semanticDocRef.current?.click()}
            title="上传 .docx / .md / .txt，保存后自动与当前模型做差异分析"
          >
            {documentUpload.status === 'uploading' ? '上传中…' : '上传语义文档'}
          </button>
          <button
            className="da-btn da-btn-sm"
            disabled={busy !== null || enhance?.task?.status === 'RUNNING'}
            onClick={onTriggerEnhance}
          >
            {enhance?.task ? '重新分析文档' : '分析已上传文档'}
          </button>
        </div>
        <div className="da-small" style={{ color: 'var(--da-text-3)', marginBottom: 10 }}>
          上传语义文档（.docx/.md/.txt）后自动与当前模型做差异分析；提案不会自动落库，需逐条审阅。也可把同一内容粘贴到「对话建模」。
        </div>
        {enhance?.task?.status === 'FAILED' && (
          <div className="da-alert da-alert-error" style={{ marginBottom: 10 }}>
            {enhance.task.errorMessage || '分析失败，请稍后重试'}
          </div>
        )}
        {enhance?.task?.status === 'RUNNING' ? (
          <div className="da-small">正在分析文档与当前语义状态，页面会自动刷新…</div>
        ) : (enhance?.proposals.filter(p => p.status === 'PENDING').length ?? 0) === 0 ? (
          <div className="da-small">暂无待审提案。上传业务说明文档后会自动分析，也可手动重跑。</div>
        ) : (
          <div style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
            {enhance?.proposals
              .filter(p => p.status === 'PENDING')
              .map(p => {
                const manual = p.classification === 'CONFLICT' || p.type === 'MANUAL_FIX';
                return (
                  <div
                    key={p.id}
                    style={{
                      border: '1px solid var(--da-border)',
                      borderRadius: 8,
                      padding: 12,
                    }}
                  >
                    <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                      <strong>{p.title}</strong>
                      <span className="da-badge">{ENHANCE_TYPE_LABEL[p.type] ?? p.type}</span>
                      <span
                        className="da-badge"
                        style={
                          p.classification === 'CONFLICT'
                            ? { color: 'var(--da-danger)', borderColor: 'var(--da-danger)' }
                            : p.classification === 'PARTIAL'
                              ? { color: 'var(--da-warn)', borderColor: 'var(--da-warn)' }
                              : undefined
                        }
                      >
                        {ENHANCE_CLASS_LABEL[p.classification] ?? p.classification}
                      </span>
                      <span className="da-small" style={{ color: 'var(--da-text-3)' }}>
                        置信度：{CONFIDENCE_LABEL[p.confidence] ?? p.confidence}
                      </span>
                      <div style={{ flex: 1 }} />
                      {!manual && (
                        <button
                          className="da-btn da-btn-primary da-btn-sm"
                          disabled={busy !== null}
                          onClick={() => onAdoptEnhance(p)}
                        >
                          采纳
                        </button>
                      )}
                      <button
                        className="da-btn da-btn-sm"
                        disabled={busy !== null}
                        onClick={() => onIgnoreEnhance(p)}
                      >
                        忽略
                      </button>
                    </div>
                    {p.summary && <div style={{ marginTop: 8 }}>{p.summary}</div>}
                    {p.sourceQuote && (
                      <div className="da-small" style={{ marginTop: 6, color: 'var(--da-text-3)' }}>
                        来源：“{p.sourceQuote}”
                      </div>
                    )}
                    <details style={{ marginTop: 6 }}>
                      <summary className="da-small" style={{ cursor: 'pointer' }}>
                        查看落库草案
                      </summary>
                      <pre
                        style={{
                          whiteSpace: 'pre-wrap',
                          overflowWrap: 'anywhere',
                          fontSize: '0.75rem',
                          marginBottom: 0,
                        }}
                      >
                        {JSON.stringify(
                          p.payload,
                          (key, value) =>
                            typeof value === 'string' &&
                            ['datasetId', 'sourceDatasetId', 'targetDatasetId', 'baseDatasetId'].includes(
                              key,
                            )
                              ? datasetName(value)
                              : value,
                          2,
                        )}
                      </pre>
                    </details>
                    {manual && (
                      <div className="da-small" style={{ marginTop: 6, color: 'var(--da-warn)' }}>
                        该提案存在冲突或暂无安全落点，请在现有编辑器中人工处理。
                      </div>
                    )}
                    {p.decisionError && (
                      <div className="da-small" style={{ marginTop: 6, color: 'var(--da-danger)' }}>
                        {p.decisionError}
                      </div>
                    )}
                  </div>
                );
              })}
          </div>
        )}
      </div>
      )}

      {tab === 'glossary' && (
      <div className="da-card">
        <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
          <div className="da-h2" style={{ margin: 0 }}>
            业务术语 ({overview?.terms.length ?? 0})
          </div>
          <div style={{ flex: 1 }} />
          <span className="da-small" style={{ color: 'var(--da-text-3)' }}>
            本知识库存用 · 保存即生效（每轮问数自动注入上下文），在「语义配置」页维护
          </span>
        </div>
        {(overview?.terms.length ?? 0) === 0 ? (
          <div className="da-small" style={{ marginTop: 8 }}>
            暂无术语。可让建模助手在对话中沉淀，或在「语义配置」页手工维护。
          </div>
        ) : (
          <table className="da-table" style={{ marginTop: 8 }}>
            <thead>
              <tr>
                <th>术语</th>
                <th>解释</th>
                <th>同义词</th>
              </tr>
            </thead>
            <tbody>
              {overview?.terms.map(t => (
                <tr key={t.id}>
                  <td style={{ fontWeight: 600 }}>{t.term}</td>
                  <td>{t.explanation}</td>
                  <td>{t.synonyms || '—'}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>
      )}

      {/* ---------- mdl tab: publish + graph ---------- */}
      {tab === 'mdl' && (
        <>


      <MdlGraphView key={workflow?.engineeringCheckedAt ?? workflow?.publishedVersion} groupId={groupId} />
      <ModelSnapshotPanel groupId={groupId} />
        </>
      )}

      {/* ---------- specs/030: chat lives in the right-side dock mounted at the root ---------- */}

      {/* ---------- hidden input for 上传语义文档（specs/028） ---------- */}
      <input
        ref={semanticDocRef}
        type="file"
        accept=".docx,.md,.txt"
        style={{ display: 'none' }}
        onChange={e => {
          const f = e.target.files?.[0];
          if (f) onUploadSemanticDoc(f);
          e.target.value = '';
        }}
      />

      {/* ---------- cube editor ---------- */}
      {draft && (
        <div
          style={{
            position: 'fixed',
            inset: 0,
            background: 'rgba(0,0,0,0.45)',
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            zIndex: 1000,
          }}
          onClick={() => setDraft(null)}
        >
          <div
            className="da-card"
            style={{ width: 760, maxHeight: '85vh', overflowY: 'auto', padding: 20 }}
            onClick={e => e.stopPropagation()}
          >
            <div className="da-h2" style={{ marginBottom: 12 }}>
              {draft.id ? `编辑 Cube：${draft.name}` : '新建 Cube'}
            </div>

            <div style={{ display: 'flex', gap: 8, marginBottom: 10 }}>
              <div style={{ flex: 1 }}>
                <div className="da-label">名称</div>
                <input
                  className="da-input"
                  value={draft.name}
                  onChange={e => setDraft(d => (d ? { ...d, name: e.target.value } : d))}
                  style={{ width: '100%' }}
                  placeholder="如：订单分析"
                />
              </div>
              <div style={{ flex: 1 }}>
                <div className="da-label">基准数据集</div>
                <select
                  className="da-input"
                  value={draft.baseDatasetId}
                  onChange={e =>
                    setDraft(d =>
                      d
                        ? {
                            ...d,
                            baseDatasetId: e.target.value,
                            measures: d.measures.map(m => ({ ...m, column: '' })),
                            dimensions: d.dimensions.map(x => ({ ...x, column: '' })),
                            timeDimensions: d.timeDimensions.map(x => ({ ...x, column: '' })),
                          }
                        : d,
                    )
                  }
                  style={{ width: '100%' }}
                >
                  <option value="">请选择…</option>
                  {datasets.map(ds => (
                    <option key={ds.id} value={ds.id}>
                      {datasetLabel(ds)}
                    </option>
                  ))}
                </select>
              </div>
            </div>
            <div style={{ marginBottom: 12 }}>
              <div className="da-label">描述（可选）</div>
              <input
                className="da-input"
                value={draft.description}
                onChange={e => setDraft(d => (d ? { ...d, description: e.target.value } : d))}
                style={{ width: '100%' }}
                placeholder="业务口径说明"
              />
            </div>

            {/* measures */}
            <div className="da-small" style={{ fontWeight: 600, marginBottom: 6 }}>
              指标
            </div>
            <div style={{ display: 'flex', flexDirection: 'column', gap: 6, marginBottom: 12 }}>
              {draft.measures.map((m, i) => (
                <div key={`m-${i}`}>
                  <div style={{ display: 'flex', gap: 6, alignItems: 'center' }}>
                    <input
                      className="da-input"
                      value={m.name}
                      placeholder="指标名"
                      onChange={e =>
                        setDraft(d =>
                          d
                            ? {
                                ...d,
                                measures: d.measures.map((x, idx) =>
                                  idx === i ? { ...x, name: e.target.value } : x,
                                ),
                              }
                            : d,
                        )
                      }
                      style={{ width: 130 }}
                    />
                    <ColumnSelect
                      columns={columnsOf(draft.baseDatasetId)}
                      value={m.column}
                      onChange={v =>
                        setDraft(d =>
                          d
                            ? {
                                ...d,
                                measures: d.measures.map((x, idx) =>
                                  idx === i ? { ...x, column: v } : x,
                                ),
                              }
                            : d,
                        )
                      }
                    />
                    <select
                      className="da-input"
                      value={m.agg}
                      onChange={e =>
                        setDraft(d =>
                          d
                            ? {
                                ...d,
                                measures: d.measures.map((x, idx) =>
                                  idx === i ? { ...x, agg: e.target.value } : x,
                                ),
                              }
                            : d,
                        )
                      }
                      style={{ width: 88 }}
                    >
                      {AGG_OPTIONS.map(a => (
                        <option key={a} value={a}>
                          {a}
                        </option>
                      ))}
                    </select>
                    <button
                      className="da-btn da-btn-sm"
                      style={{ fontSize: '0.72rem' }}
                      onClick={() =>
                        setDraft(d =>
                          d
                            ? {
                                ...d,
                                measures: d.measures.map((x, idx) =>
                                  idx === i
                                    ? { ...x, caseOn: !x.caseOn, caseColumn: x.caseOn ? '' : x.caseColumn }
                                    : x,
                                ),
                              }
                            : d,
                        )
                      }
                    >
                      {m.caseOn ? '移除 CASE' : '+ CASE 条件'}
                    </button>
                    <button
                      className="da-btn da-btn-sm"
                      disabled={draft.measures.length <= 1}
                      onClick={() =>
                        setDraft(d =>
                          d ? { ...d, measures: d.measures.filter((_, idx) => idx !== i) } : d,
                        )
                      }
                    >
                      ✕
                    </button>
                  </div>
                  {m.caseOn && (
                    <div
                      style={{
                        display: 'flex',
                        gap: 6,
                        alignItems: 'center',
                        marginTop: 4,
                        paddingLeft: 12,
                      }}
                    >
                      <span className="da-small" style={{ color: 'var(--da-text-3)' }}>
                        CASE 条件
                      </span>
                      <ColumnSelect
                        columns={columnsOf(draft.baseDatasetId)}
                        value={m.caseColumn}
                        onChange={v =>
                          setDraft(d =>
                            d
                              ? {
                                  ...d,
                                  measures: d.measures.map((x, idx) =>
                                    idx === i ? { ...x, caseColumn: v } : x,
                                  ),
                                }
                              : d,
                          )
                        }
                        style={{ maxWidth: 200 }}
                      />
                      <select
                        className="da-input"
                        value={m.caseOp}
                        onChange={e =>
                          setDraft(d =>
                            d
                              ? {
                                  ...d,
                                  measures: d.measures.map((x, idx) =>
                                    idx === i ? { ...x, caseOp: e.target.value } : x,
                                  ),
                                }
                              : d,
                          )
                        }
                        style={{ width: 70 }}
                      >
                        {CASE_OPS.map(op => (
                          <option key={op} value={op}>
                            {op}
                          </option>
                        ))}
                      </select>
                      <input
                        className="da-input"
                        value={m.caseValue}
                        placeholder="值"
                        onChange={e =>
                          setDraft(d =>
                            d
                              ? {
                                  ...d,
                                  measures: d.measures.map((x, idx) =>
                                    idx === i ? { ...x, caseValue: e.target.value } : x,
                                  ),
                                }
                              : d,
                          )
                        }
                        style={{ width: 120 }}
                      />
                      <span className="da-small" style={{ color: 'var(--da-text-3)' }}>
                        指标 ={' '}
                        {(m.agg === 'DISTINCT_COUNT' ? 'COUNT(DISTINCT ' : `${m.agg}(`) +
                          `CASE WHEN ${m.caseColumn || '列'} ${m.caseOp} ${
                            m.caseValue ? caseLiteral(m.caseValue) : '值'
                          } THEN ${m.column || '列'} END`}
                      </span>
                    </div>
                  )}
                </div>
              ))}
              <div>
                <button
                  className="da-btn da-btn-sm"
                  onClick={() =>
                    setDraft(d =>
                      d
                        ? {
                            ...d,
                            measures: [
                              ...d.measures,
                              {
                                name: '',
                                column: '',
                                agg: 'SUM',
                                caseOn: false,
                                caseColumn: '',
                                caseOp: '=',
                                caseValue: '',
                              },
                            ],
                          }
                        : d,
                    )
                  }
                >
                  + 添加指标
                </button>
              </div>
            </div>

            {/* dimensions */}
            <div className="da-small" style={{ fontWeight: 600, marginBottom: 6 }}>
              维度
            </div>
            <div style={{ display: 'flex', flexDirection: 'column', gap: 6, marginBottom: 12 }}>
              {draft.dimensions.map((x, i) => (
                <div key={`d-${i}`} style={{ display: 'flex', gap: 6, alignItems: 'center' }}>
                  <input
                    className="da-input"
                    value={x.name}
                    placeholder="维度名"
                    onChange={e =>
                      setDraft(d =>
                        d
                          ? {
                              ...d,
                              dimensions: d.dimensions.map((y, idx) =>
                                idx === i ? { ...y, name: e.target.value } : y,
                              ),
                            }
                          : d,
                      )
                    }
                    style={{ width: 130 }}
                  />
                  <ColumnSelect
                    columns={columnsOf(draft.baseDatasetId)}
                    value={x.column}
                    onChange={v =>
                      setDraft(d =>
                        d
                          ? {
                              ...d,
                              dimensions: d.dimensions.map((y, idx) =>
                                idx === i ? { ...y, column: v } : y,
                              ),
                            }
                          : d,
                      )
                    }
                  />
                  <button
                    className="da-btn da-btn-sm"
                    onClick={() =>
                      setDraft(d =>
                        d ? { ...d, dimensions: d.dimensions.filter((_, idx) => idx !== i) } : d,
                      )
                    }
                  >
                    ✕
                  </button>
                </div>
              ))}
              <div>
                <button
                  className="da-btn da-btn-sm"
                  onClick={() =>
                    setDraft(d =>
                      d ? { ...d, dimensions: [...d.dimensions, { name: '', column: '' }] } : d,
                    )
                  }
                >
                  + 添加维度
                </button>
              </div>
            </div>

            {/* time dimensions */}
            <div className="da-small" style={{ fontWeight: 600, marginBottom: 6 }}>
              时间维度
            </div>
            <div style={{ display: 'flex', flexDirection: 'column', gap: 6, marginBottom: 12 }}>
              {draft.timeDimensions.map((x, i) => (
                <div key={`t-${i}`} style={{ display: 'flex', gap: 6, alignItems: 'center' }}>
                  <input
                    className="da-input"
                    value={x.name}
                    placeholder="时间维度名"
                    onChange={e =>
                      setDraft(d =>
                        d
                          ? {
                              ...d,
                              timeDimensions: d.timeDimensions.map((y, idx) =>
                                idx === i ? { ...y, name: e.target.value } : y,
                              ),
                            }
                          : d,
                      )
                    }
                    style={{ width: 130 }}
                  />
                  <ColumnSelect
                    columns={columnsOf(draft.baseDatasetId)}
                    value={x.column}
                    onChange={v =>
                      setDraft(d =>
                        d
                          ? {
                              ...d,
                              timeDimensions: d.timeDimensions.map((y, idx) =>
                                idx === i ? { ...y, column: v } : y,
                              ),
                            }
                          : d,
                      )
                    }
                  />
                  <select
                    className="da-input"
                    value={x.granularity}
                    onChange={e =>
                      setDraft(d =>
                        d
                          ? {
                              ...d,
                              timeDimensions: d.timeDimensions.map((y, idx) =>
                                idx === i ? { ...y, granularity: e.target.value } : y,
                              ),
                            }
                          : d,
                      )
                    }
                    style={{ width: 100 }}
                  >
                    {GRANULARITY_OPTIONS.map(g => (
                      <option key={g} value={g}>
                        {g}
                      </option>
                    ))}
                  </select>
                  <button
                    className="da-btn da-btn-sm"
                    onClick={() =>
                      setDraft(d =>
                        d
                          ? {
                              ...d,
                              timeDimensions: d.timeDimensions.filter((_, idx) => idx !== i),
                            }
                          : d,
                      )
                    }
                  >
                    ✕
                  </button>
                </div>
              ))}
              <div>
                <button
                  className="da-btn da-btn-sm"
                  onClick={() =>
                    setDraft(d =>
                      d
                        ? {
                            ...d,
                            timeDimensions: [
                              ...d.timeDimensions,
                              { name: '', column: '', granularity: 'MONTH' },
                            ],
                          }
                        : d,
                    )
                  }
                >
                  + 添加时间维度
                </button>
              </div>
            </div>

            {draftError && (
              <div className="da-small" style={{ color: 'var(--da-danger)', marginBottom: 8 }}>
                {draftError}
              </div>
            )}

            <div style={{ display: 'flex', gap: 8, justifyContent: 'flex-end' }}>
              <button className="da-btn" onClick={() => setDraft(null)}>
                取消
              </button>
              <button
                className="da-btn da-btn-primary"
                disabled={busy !== null}
                onClick={onSaveCube}
              >
                {busy === 'cube:save' ? '保存中…' : '保存'}
              </button>
            </div>
          </div>
        </div>
      )}

      {/* ---------- view editor (specs/011 M2) ---------- */}
      {viewDraft && (
        <div
          style={{
            position: 'fixed',
            inset: 0,
            background: 'rgba(0,0,0,0.45)',
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            zIndex: 1000,
          }}
          onClick={() => setViewDraft(null)}
        >
          <div
            className="da-card"
            style={{ width: 640, maxHeight: '85vh', overflowY: 'auto', padding: 20 }}
            onClick={e => e.stopPropagation()}
          >
            <div className="da-h2" style={{ marginBottom: 12 }}>
              {viewDraft.id ? `编辑视图：${viewDraft.name}` : '新建视图'}
            </div>
            <div style={{ display: 'flex', gap: 8, marginBottom: 10 }}>
              <div style={{ flex: 1 }}>
                <div className="da-label">名称</div>
                <input
                  className="da-input"
                  value={viewDraft.name}
                  onChange={e =>
                    setViewDraft(d => (d ? { ...d, name: e.target.value } : d))
                  }
                  style={{ width: '100%' }}
                  placeholder="如：高频访问用户"
                />
              </div>
              <div style={{ flex: 1 }}>
                <div className="da-label">基准数据集（可选，仅展示用）</div>
                <select
                  className="da-input"
                  value={viewDraft.baseDatasetId}
                  onChange={e =>
                    setViewDraft(d => (d ? { ...d, baseDatasetId: e.target.value } : d))
                  }
                  style={{ width: '100%' }}
                >
                  <option value="">不指定</option>
                  {datasets.map(ds => (
                    <option key={ds.id} value={ds.id}>
                      {datasetLabel(ds)}
                    </option>
                  ))}
                </select>
              </div>
            </div>
            <div style={{ marginBottom: 10 }}>
              <div className="da-label">SQL（单条 SELECT/WITH，末尾分号会被自动去除）</div>
              <textarea
                className="da-input"
                value={viewDraft.sqlText}
                onChange={e =>
                  setViewDraft(d => (d ? { ...d, sqlText: e.target.value } : d))
                }
                style={{
                  width: '100%',
                  minHeight: 140,
                  fontFamily: 'var(--da-mono, monospace)',
                }}
                placeholder={
                  'SELECT user_id, COUNT(*) AS visits\nFROM orders\nGROUP BY user_id\nHAVING COUNT(*) > 5'
                }
              />
            </div>
            <div style={{ marginBottom: 12 }}>
              <div className="da-label">描述（可选）</div>
              <input
                className="da-input"
                value={viewDraft.description}
                onChange={e =>
                  setViewDraft(d => (d ? { ...d, description: e.target.value } : d))
                }
                style={{ width: '100%' }}
                placeholder="业务口径说明，发布后会进入问数上下文"
              />
            </div>
            {viewDraftError && (
              <div className="da-small" style={{ color: 'var(--da-danger)', marginBottom: 8 }}>
                {viewDraftError}
              </div>
            )}
            <div style={{ display: 'flex', gap: 8, justifyContent: 'flex-end' }}>
              <button className="da-btn" onClick={() => setViewDraft(null)}>
                取消
              </button>
              <button
                className="da-btn da-btn-primary"
                disabled={busy !== null}
                onClick={onSaveView}
              >
                {busy === 'view:save' ? '保存中…' : '保存'}
              </button>
            </div>
          </div>
        </div>
      )}
      </div>

      {/* Keep the chat mounted so browsing model details does not discard pending HITL. */}
      <div className="modeling-chat-main" style={{ display: tab === 'modeling' ? 'flex' : 'none', flexDirection: 'column', gap: 12, flex: 1, order: -1, minWidth: 0, minHeight: 0 }}>
        <BusinessDocumentGuide content={businessDocument} upload={documentUpload} analysisStatus={enhance?.task?.status}
          onUpload={() => semanticDocRef.current?.click()} />
        {!showConversation && <div className="da-card" style={{ padding: 24, overflowY: 'auto' }}>
          {!intakeReady ? <p role="status">{workflowError || '正在读取问题与建模会话…'}</p>
            : nativeSession?.error ? <p role="alert">{nativeSession.error}</p>
            : <QuestionIntakeForm key={groupId} initial onSubmit={submitQuestions} />}
        </div>}
        <div style={{ display: showConversation ? 'block' : 'none', flex: 1, minHeight: 0 }}>
          <ModelingChatPanel key={groupId} groupId={groupId}
            draftPrompt={chatDraft?.groupId === groupId ? chatDraft.message : undefined}
            draftPromptId={chatDraft?.groupId === groupId ? chatDraft.requestId : undefined}
            submitDraft={chatDraft?.groupId === groupId && chatDraft.submit}
            onSessionReady={onSessionReady}
            variant="dock" onClose={() => setTab('overview')} />
        </div>
      </div>
      </div>
    </div>
  );
}

/**
 * One relation row. Pending rows expose the full review kit (joinType + confirm / redirect /
 * reject); confirmed rows can still be redirected; rejected rows can be re-confirmed.
 */
function RelationRow({
  relation: r,
  kind,
  rowBusy,
  datasetName,
  columnName,
  onConfirm,
  onSwap,
  onReject,
}: {
  relation: ModelingRelation;
  kind: 'pending' | 'confirmed' | 'rejected';
  rowBusy: boolean;
  datasetName: (id: string) => string;
  columnName: (datasetId: string, columnName: string) => string;
  onConfirm: (joinType: string) => void;
  onSwap: () => void;
  onReject: () => void;
}) {
  const [jt, setJt] = useState(r.joinType ?? '');
  useEffect(() => {
    setJt(r.joinType ?? '');
  }, [r.joinType]);

  const dim = kind === 'rejected';
  return (
    <div
      className="da-card"
      style={{
        padding: '8px 10px',
        opacity: dim ? 0.55 : 1,
        borderColor: kind === 'confirmed' ? 'var(--da-success)' : undefined,
      }}
    >
      <div style={{ display: 'flex', alignItems: 'center', gap: 8, flexWrap: 'wrap' }}>
        <span className="da-badge">{ORIGIN_LABEL[r.origin] ?? r.origin}</span>
        <span style={{ fontFamily: 'var(--da-mono, monospace)', fontSize: '0.85rem' }}>
          {datasetName(r.sourceDatasetId)} · {columnName(r.sourceDatasetId, r.sourceColumn)}
        </span>
        <Icon name="chevron" />
        <span style={{ fontFamily: 'var(--da-mono, monospace)', fontSize: '0.85rem' }}>
          {datasetName(r.targetDatasetId)} · {columnName(r.targetDatasetId, r.targetColumn)}
        </span>
        {kind === 'pending' ? (
          <JoinTypeSelect value={jt} onChange={setJt} />
        ) : (
          <span
            className="da-small"
            style={{ color: kind === 'confirmed' ? 'var(--da-success)' : 'var(--da-text-3)' }}
          >
            {r.joinType ? JOIN_LABEL[r.joinType] ?? r.joinType : 'join 类型未知'}
          </span>
        )}
        <div style={{ flex: 1 }} />
        {rowBusy && <span className="da-spinner da-spinner-sm" />}
        {kind === 'pending' && (
          <>
            <button
              className="da-btn da-btn-primary da-btn-sm"
              disabled={rowBusy}
              onClick={() => onConfirm(jt)}
            >
              确认
            </button>
            <button className="da-btn da-btn-sm" disabled={rowBusy} onClick={onSwap}>
              改向确认
            </button>
            <button className="da-btn da-btn-sm" disabled={rowBusy} onClick={onReject}>
              拒绝
            </button>
          </>
        )}
        {kind === 'confirmed' && (
          <>
            <span className="da-small" style={{ color: 'var(--da-success)' }}>
              ✓ 已确认
            </span>
            <button className="da-btn da-btn-sm" disabled={rowBusy} onClick={onSwap}>
              改向
            </button>
            <button className="da-btn da-btn-sm" disabled={rowBusy} onClick={onReject}>
              拒绝
            </button>
          </>
        )}
        {kind === 'rejected' && (
          <>
            <span className="da-small" style={{ color: 'var(--da-text-3)' }}>
              已拒绝
            </span>
            <button
              className="da-btn da-btn-sm"
              disabled={rowBusy}
              onClick={() => onConfirm(jt)}
            >
              重新确认
            </button>
          </>
        )}
      </div>
      {(kind === 'pending' && !r.joinType) && (
        <div className="da-small" style={{ color: 'var(--da-text-3)', marginTop: 4 }}>
          join 类型待探测，可手动指定
        </div>
      )}
    </div>
  );
}
