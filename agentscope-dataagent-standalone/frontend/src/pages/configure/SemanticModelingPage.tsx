import { tourHoldsQuery, tourSignal, useTour } from '../../state/guidedTour';
import { FactQueryWindowSettings } from '../../components/FactQueryWindowSettings';
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useNavigate, useParams, useSearchParams } from 'react-router-dom';
import ModelingChatPanel from '../../components/ModelingChatPanel';
import type { AssistantState } from '../../components/ModelingChatPanel';
import { confirmationRequest } from '../../components/modeling/modelingHistory';
import { getModelingPrompts } from '../../components/modeling/modelingPrompts';
import SemanticWorkbench from '../../components/modeling/SemanticWorkbench';
import { WorkspaceMenu } from '../../components/KnowledgeWorkspace';
import { changeKey } from '../../components/modeling/SemanticWorkbench';
import type { ChangeMark, WorkbenchView } from '../../components/modeling/SemanticWorkbench';
import PendingPanel from '../../components/modeling/PendingPanel';
import { getDraftItems } from '../../api/draftReview';
import type { DraftItem } from '../../api/draftReview';
import SemanticReadOnlyWorkspace from '../../components/modeling/SemanticReadOnlyWorkspace';
import { draftSnapshot, publishedSnapshot } from '../../components/modeling/semanticSnapshot';
import ModelingMaterials from '../../components/modeling/ModelingMaterials';
import FieldScreeningPanel from '../../components/modeling/FieldScreeningPanel';
import FieldScreeningReview from '../../components/modeling/FieldScreeningReview';
import type { ScreeningSelection } from '../../components/modeling/FieldScreeningReview';
import ModelingPreparation from '../../components/modeling/ModelingPreparation';
import Icon from '../../components/Icon';
import type { FieldAdjustment } from '../../components/modeling/FieldScreeningPanel';
import { getScreeningStatus } from '../../api/fieldScreening';
import type { ScreeningStatus } from '../../api/fieldScreening';
import ConfirmationCards from '../../components/modeling/ConfirmationCards';
import { questionRequest } from '../../components/modeling/QuestionIntakeForm';
import { getModelingQuestions } from '../../api/modelingQuestions';
import DraftBar from '../../components/modeling/DraftBar';
import type { DraftSummary } from '../../components/modeling/DraftBar';
import NextStepCard from '../../components/modeling/NextStepCard';
import TrialPanel from '../../components/modeling/TrialPanel';
import TurnResultCard from '../../components/modeling/TurnResultCard';
import AdvancedModelingPanel from '../../components/modeling/AdvancedModelingPanel';
import ConfirmedExamplesSection from '../../components/modeling/ConfirmedExamplesSection';
import AssetProposalCards from '../../components/modeling/AssetProposalCards';
import QueryCoachPanel from '../../components/modeling/QueryCoachPanel';
import type { AssetEntry } from '../../components/modeling/AssetYamlBrowser';
import { listGroupCards } from '../../api/datasets';
import type { DatasetGroup } from '../../api/datasets';
import { getKnowledgeBaseBrowse, getMdlView, getMdlPreview } from '../../api/semanticModeling';
import type { KnowledgeBaseBrowse, MdlView, MdlPreview } from '../../api/semanticModeling';
import { getModelingWorkflow } from '../../api/modelingWorkflow';
import type { ModelingWorkflow } from '../../api/modelingWorkflow';
import { listConfirmations, resolveConfirmations } from '../../api/draftReview';
import type { Confirmation, ConfirmationAnswer } from '../../api/draftReview';
import type { TurnPurpose } from '../../api/chat';
import { useQueryReadiness } from '../../components/useQueryReadiness';
import SemanticAssetsPanel from '../../components/modeling/SemanticAssetsPanel';
import './SemanticModelingPage.css';
import '../../components/modeling/ModelingSetup.css';

type Tab = 'semantic' | 'coach' | 'assets' | 'overview';
// spec092 §7: the conversation is the workspace; review happens inline, publishing in the draft bar.
// spec117: models, relations and business knowledge are one 语义模型 tab; older addresses still work.
const LEGACY_TABS: Record<string, Tab> = {
  models: 'semantic', relations: 'semantic', knowledge: 'semantic', modeling: 'semantic', qa: 'coach',
  schema: 'semantic', derived: 'semantic', views: 'semantic', cubes: 'semantic', mdl: 'semantic', glossary: 'semantic', sql: 'coach',
};
const TAB_KEYS: Tab[] = ['semantic', 'coach', 'assets', 'overview'];
const ADVANCED = new Set(['views', 'cubes', 'mdl']);
const RULE_ADDRESSES = new Set(['knowledge', 'glossary']);
const tabOf = (asset: string | null, kind: string | null): Tab | null => {
  // spec111: a bare ?asset=assets is the new page; with a kind it is the older address.
  if (asset === 'assets') return kind ? LEGACY_TABS[kind] ?? 'semantic' : 'assets';
  if (asset && (TAB_KEYS as string[]).includes(asset)) return asset as Tab;
  return asset ? LEGACY_TABS[asset] ?? 'semantic' : null;
};
/** spec117: which view of 语义模型 an address opens. */
export const workbenchViewOf = (asset: string | null, view: string | null): WorkbenchView =>
  view === 'rules' || view === 'graph' ? view : RULE_ADDRESSES.has(asset ?? '') ? 'rules' : 'graph';
const message = (e: unknown) => e instanceof Error ? e.message : String(e);
const AUTO_MODELING_REQUEST = '请加载官方 Wren 技能，按官方 Auto-pilot 完善当前采样后的语义模型。事实表时间范围已独立保存到数据库，必要业务 SQL 遵守工具时间门禁；数据探查遵守运行上下文中的表来源与探查边界；运行上下文列出"本次新增的表"时只处理这些表。'
  + '业务文档位于 raw/docs/（只读，可能没有）。完成校验后按回复写法简短说明（不列文件与命令）；修改进入草稿，由我在对话中查看后统一发布。';
/** spec092: sent automatically after documents change; the assistant reconciles, never regenerates. */
const documentRequest = (changes: string[]) => `业务文档有变化：${changes.join('；')}。文档位于 raw/docs/（只读）。`
  + '请读取有变化的文档，与当前模型描述和 knowledge/rules 对比后更新：文档写清楚的指标定义、默认过滤、术语直接写入 knowledge/rules/（注明来源文档），'
  + '字段含义写入对应模型的列描述，不要向我确认；只有文档有歧义、且不同理解会改变统计结果时，才用 request_user_confirmation 提问。'
  + '文档被删除时，不要自行删除规则，在回复中列出可能来自该文档的规则，由我决定是否删除。不要重新生成模型，不要重新加载已加载的技能，不要写 Cube/View。'
  + '只修改与文档有差异的内容，完成后执行校验，按回复写法简短说明。';
/** spec092: hands the user's answers to the assistant to write where queries read them. */
/** spec093: after the modeler changed one table's fields, only that table is re-modeled. */
const remodelRequest = (c: FieldAdjustment) => `建模人员调整了表 ${c.table} 的字段：`
  + (c.added.length ? `新纳入 ${c.added.join('、')}；` : '') + (c.removed.length ? `取消纳入 ${c.removed.join('、')}；` : '')
  + (c.described.length ? `改写了 ${c.described.join('、')} 的说明；` : '')
  + `草稿模型已按此更新。请只处理这张表：为新纳入且没有说明的字段补充列描述；检查 knowledge/rules、relationships.yml 与 knowledge/questions 中引用被取消字段的内容并修正，无法修正的在回复中列出；`
  + '不要修改带 description_source: user 的说明，不要改动其他表。完成后执行校验，按回复写法简短说明。';

/** Short texts the conversation shows for the requests above. */
const documentLabel = (changes: string[]) => `业务文档有变化，请同步到模型：${changes.join('；')}`;
const remodelLabel = (c: FieldAdjustment) => {
  const parts = [c.added.length ? `新纳入 ${c.added.join('、')}` : '', c.removed.length ? `取消 ${c.removed.join('、')}` : ''].filter(Boolean);
  return `我调整了 ${c.table} 的字段，请按新字段重新建模这张表${parts.length ? `（${parts.join('；')}）` : ''}`;
};

/** A request waiting for the assistant; requests run one after another in the conversation. */
type Request = { id: number; text: string; purpose?: TurnPurpose; documents?: string[]; label?: string };

/** spec106: managers get the workbench; a QUERY user gets the published version read-only. */
/** The guided tour takes the user to the query page itself once querying is ready. */
const TOUR_QUERY_HINT = '引导会在问数准备好后带你去问数';

export default function SemanticModelingPage() {
  const { groupId = '' } = useParams();
  const [params] = useSearchParams();
  const [access, setAccess] = useState<DatasetGroup | null>(null);
  const [error, setError] = useState('');
  useEffect(() => {
    const controller = new AbortController();
    setAccess(null); setError('');
    void listGroupCards([groupId], controller.signal).then(cards => {
      if (controller.signal.aborted) return;
      const card = cards.find(item => item.id === groupId && !item.deletionPending);
      if (!card) throw new Error('知识库不存在或未授权');
      setAccess(card);
    }).catch(e => { if (!controller.signal.aborted) setError(message(e)); });
    return () => controller.abort();
  }, [groupId]);
  if (error) return <div className="mw-content"><div role="alert" className="da-alert da-alert-error">{error}</div></div>;
  if (access?.id !== groupId) return <div className="modeling-workbench mw"><div className="mw-content"><GraphLoading /></div></div>;
  return access.canManage ? <ModelingWorkbench key={groupId} group={access} /> : <SemanticReadOnlyWorkspace groupId={groupId} requested={tabOf(params.get('asset'), params.get('kind'))} />;
}

function GraphLoading() {
  return <div className="mw-graph-loading" role="status">
    <span>正在加载语义模型…</span>
    <div aria-hidden="true"><i /><b /><i /></div>
  </div>;
}

function ModelingWorkbench({ group }: { group: DatasetGroup }) {
  const groupId = group.id;
  const navigate = useNavigate();
  const [params, setParams] = useSearchParams();
  const asset = params.get('asset');
  const legacyAsset = asset === 'assets' ? params.get('kind') : asset;
  const explicitTab = tabOf(asset, params.get('kind'));
  const [view, setView] = useState<MdlView | null>(null);
  const [preview, setPreview] = useState<MdlPreview | null>(null);
  const [workflow, setWorkflow] = useState<ModelingWorkflow | null>(null);
  const [confirmations, setConfirmations] = useState<Confirmation[]>([]);
  const [confirmationsReady, setConfirmationsReady] = useState(false);
  const [error, setError] = useState('');
  const [queue, setQueue] = useState<{ groupId: string; items: Request[] }>({ groupId, items: [] });
  const pending = queue.groupId === groupId ? queue.items : [];
  const head = pending[0];
  const [assistantState, setAssistantState] = useState<AssistantState>('idle');
  const idle = assistantState === 'idle' && !head;
  const [readyGroup, setReadyGroup] = useState<string | null>(null);
  const windowReady = readyGroup === groupId;
  const windowReadyRef = useRef(false);
  const updateWindowReady = useCallback((ready: boolean) => { windowReadyRef.current = ready; setReadyGroup(ready ? groupId : null); }, [groupId]);
  // spec093: tables waiting for field screening; null until loaded.
  const [screening, setScreening] = useState<ScreeningStatus[] | null>(null);
  const screeningPending = !!screening?.some(t => !t.confirmed);
  const preparationKey = `modeling-prepare:${groupId}`;
  const [setupPhase, setSetupPhase] = useState<'fields' | 'prepare' | null>(() => {
    if (params.get('start') === '1') return 'prepare';
    try { return sessionStorage.getItem(preparationKey) === '1' ? 'prepare' : null; } catch { return null; }
  });
  const [fieldEditor, setFieldEditor] = useState<ScreeningSelection | null>(null);
  const [confirmingScreening, setConfirmingScreening] = useState(false);
  const [startSignal, setStartSignal] = useState(0);
  const setupFields = screeningPending || (screening === null && setupPhase !== null) || setupPhase === 'fields' || fieldEditor !== null;
  const setupActive = setupFields || setupPhase !== null;
  const setupLeaveGuard = useRef<(() => Promise<boolean>) | null>(null);
  const [adjusting, setAdjusting] = useState<string | null>(null);
  const [windowSignal, setWindowSignal] = useState(0);
  // spec092 §7: 试问 opens only when the user asks for it.
  const [trialOpen, setTrialOpen] = useState(false);
  const [examples, setExamples] = useState(0);
  const [draftSummary, setDraftSummary] = useState<DraftSummary>({ changes: 0, examples: 0, publishing: false, publishedVersion: null, error: null });
  const [publishSignal, setPublishSignal] = useState(0);
  const [prefill, setPrefill] = useState<{ id: number; text: string } | null>(null);
  const [focusRelation, setFocusRelation] = useState<string | null>(null);
  const [changeMark, setChangeMark] = useState<ChangeMark | null>(null);
  const [versionChoice, setVersionChoice] = useState<'draft' | 'published' | 'compare'>('draft');
  const fieldEditGuard = useRef<(() => boolean) | null>(null);
  const canLeaveField = () => fieldEditGuard.current?.() ?? true;
  const [browse, setBrowse] = useState<KnowledgeBaseBrowse | null>(null);
  const [advancedOpen, setAdvancedOpen] = useState(() => ADVANCED.has(legacyAsset ?? ''));
  useEffect(() => { if (ADVANCED.has(legacyAsset ?? '')) setAdvancedOpen(true); }, [legacyAsset]);
  const publishedVersion = workflow?.publishedVersion ?? group.publishedVersion ?? 0;
  const tab: Tab = explicitTab ?? 'semantic';
  const mode = params.get('mode');
  const inChat = setupActive || mode === 'chat' || (mode !== 'board' && (!explicitTab || asset === 'modeling'));
  const [comparisonOpen, setComparisonOpen] = useState(false);
  const [pendingOpen, setPendingOpen] = useState(false);
  const pendingDialog = useRef<HTMLDialogElement>(null);
  const conversationConfirmation = useRef<HTMLDivElement>(null);
  useEffect(() => {
    const dialog = pendingDialog.current;
    if (!dialog) return;
    if (pendingOpen && !dialog.open) dialog.showModal();
    else if (!pendingOpen && dialog.open) dialog.close();
  }, [pendingOpen]);
  const showBoard = () => {
    if (!canLeaveField()) return;
    setComparisonOpen(false);
    setParams(previous => { const next = new URLSearchParams(previous); next.set('mode', 'board'); return next; });
  };
  const openAssistant = () => {
    if (!canLeaveField()) return;
    setPendingOpen(false);
    setComparisonOpen(false);
    setParams(previous => { const next = new URLSearchParams(previous); next.set('mode', 'chat'); return next; });
  };
  const [marked, setMarked] = useState<DraftItem[]>([]);
  const [seen, setSeen] = useState<Set<string>>(new Set());
  const [reveal, setReveal] = useState<{ id: number; item: DraftItem } | null>(null);

  const activeGroup = useRef(groupId); activeGroup.current = groupId;
  const generation = useRef(0);
  const sequence = useRef(0);
  const selectTab = (key: Tab) => {
    if (canLeaveField()) {
      setComparisonOpen(false);
      setParams({ asset: key, mode: 'board' });
    }
  };
  /** {@code label} is what the conversation shows for a request the platform wrote. */
  const request = (text: string, purpose?: TurnPurpose, label?: string) => {
    if (!windowReadyRef.current) { setError('请先完成事实表时间配置，再开始建模。'); return false; }
    const item = { id: ++sequence.current, text, purpose, label };
    setQueue(previous => ({ groupId, items: [...previous.items, item] }));
    return true;
  };
  // Several quick document changes merge into one queued request instead of one turn each.
  const documentsChanged = (change: string) => {
    window.dispatchEvent(new CustomEvent('modeling:updated', { detail: { groupId } }));
    // Before modeling starts, the automatic modeling turn reads every document anyway.
    if (setupActive || !windowReady) return;
    setQueue(previous => {
      const items = previous.groupId === groupId ? previous.items : [];
      const waiting = items.findIndex(r => r.purpose === 'document');
      if (waiting < 0) {
        const documents = [change];
        return { groupId, items: [...items, { id: ++sequence.current, text: documentRequest(documents), purpose: 'document', documents, label: documentLabel(documents) }] };
      }
      const documents = [...(items[waiting].documents ?? []), change];
      return { groupId, items: items.map((r, i) => i === waiting ? { ...r, text: documentRequest(documents), documents, label: documentLabel(documents) } : r) };
    });
  };
  const answered = async (answers: ConfirmationAnswer[]) => {
    if (!answers.length) return;
    try {
      const decisions = await resolveConfirmations(groupId, answers);
      if (activeGroup.current !== groupId) return;
      if (decisions.length) request(confirmationRequest(decisions), 'confirm');
      // spec114: once the rule is written, the questions that waited for it get their SQL.
      const linked = [...new Set(decisions.flatMap(d => d.questionIds ?? []))];
      if (linked.length) {
        const waiting = (await getModelingQuestions(groupId)).filter(r => linked.includes(r.question.id));
        if (waiting.length) request(questionRequest(waiting.map(r => r.question.question), waiting.map(r => r.question.id)), 'question', `按确认的口径生成 ${waiting.length} 题 SQL`);
      }
      await refresh();
    } catch (e) { if (activeGroup.current === groupId) setError(message(e)); }
  };
  const refresh = useCallback(async () => {
    const current = ++generation.current;
    const isCurrent = () => current === generation.current && activeGroup.current === groupId;
    setError('');
    setConfirmationsReady(false);
    const load = async <T,>(request: Promise<T>, apply: (value: T) => void) => {
      try {
        const value = await request;
        if (isCurrent()) apply(value);
      } catch (e) {
        if (isCurrent()) setError(previous => previous || message(e));
      }
    };
    const graph = load(getMdlView(groupId), setView);
    const checks = [
      load(getModelingWorkflow(groupId), setWorkflow),
      load(listConfirmations(groupId), items => { setConfirmations(items); setConfirmationsReady(true); }),
      load(getScreeningStatus(groupId), setScreening),
    ];
    await graph;
    if (isCurrent()) await load(getMdlPreview(groupId), setPreview);
    await Promise.all(checks);
  }, [groupId]);
  useEffect(() => {
    setReadyGroup(null); windowReadyRef.current = false;
    setTrialOpen(false); setExamples(0);
    setView(null); setPreview(null); setWorkflow(null); setConfirmations([]); setScreening(null); setAdjusting(null);
    setPrefill(null); setFocusRelation(null); setChangeMark(null); setMarked([]); setReveal(null);
    try { setSeen(new Set(JSON.parse(localStorage.getItem(`sw-seen:${groupId}`) ?? '[]') as string[])); } catch { setSeen(new Set()); } setVersionChoice('draft'); setBrowse(null);
    void refresh();
    const update = (event: Event) => {
      if ((event as CustomEvent<{ groupId: string }>).detail?.groupId === groupId) void refresh();
    };
    window.addEventListener('modeling:updated', update);
    return () => { generation.current++; window.removeEventListener('modeling:updated', update); };
  }, [groupId, refresh]);
  useEffect(() => {
    if (params.get('start') === '1') setSetupPhase('prepare');
  }, [params]);
  // spec105: the guided tour follows what this page really reports.
  useEffect(() => { tourSignal('data-ready', { groupId }); }, [groupId]);
  useEffect(() => { if (screening !== null && !screeningPending) tourSignal('screening-done', { groupId }); }, [screening, screeningPending, groupId]);
  useEffect(() => { if (windowReady) tourSignal('window-ready', { groupId }); }, [windowReady, groupId]);
  // A queued request is not a run: idle flickers on between handing it over and the run starting.
  const assistantRan = useRef(false);
  useEffect(() => {
    if (assistantState !== 'idle') assistantRan.current = true;
    else if (assistantRan.current && !head) { assistantRan.current = false; tourSignal('model-idle', { groupId }); }
  }, [assistantState, head, groupId]);
  // Open questions are settled before trying the model. The wait outlasts the moment an answered
  // request has left the queue but its run has not started, which also reads as idle.
  const tour = useTour();
  const tourStep = tour?.step;
  const queryHeld = tourHoldsQuery(tour, groupId);
  useEffect(() => {
    if (tourStep !== 'confirm' || !idle || !workflow || !confirmationsReady || confirmations.length > 0) return;
    const timer = window.setTimeout(() => tourSignal('confirm-done', { groupId }), 2000);
    return () => window.clearTimeout(timer);
  }, [tourStep, idle, workflow, confirmationsReady, confirmations.length, groupId]);
  // No skip buttons in the tour, so after a reload it recovers from what the page shows.
  useEffect(() => {
    if (tourStep !== 'modeling' || !idle || !(draftSummary.changes > 0 || workflow?.queryAvailable)) return;
    const timer = window.setTimeout(() => tourSignal('model-idle', { groupId }), 4000);
    return () => window.clearTimeout(timer);
  }, [tourStep, idle, draftSummary.changes, workflow?.queryAvailable, groupId]);
  const startModeling = () => {
    if (!request(AUTO_MODELING_REQUEST, 'auto', '开始自动建模')) return;
    tourSignal('model-started', { groupId });
    setSetupPhase(null);
    try { sessionStorage.removeItem(preparationKey); } catch { /* Resume is optional. */ }
    setParams({ asset: 'semantic', mode: 'chat' }, { replace: true });
  };

  const files = preview?.files || [];
  const published = new Map(preview?.publishedFiles.map(f => [f.path, f.content]) || []);
  const dirty = (path: string | null) => !!path && files.some(f => f.path === path && published.get(path) !== f.content);
  const viewEntries: AssetEntry[] = (view?.views || []).map(v => ({ key: v.name, title: v.name, subtitle: v.description || undefined, file: v.path, dirty: dirty(v.path) }));
  const cubeEntries: AssetEntry[] = (view?.cubes || []).map(c => ({ key: c.name, title: c.name, subtitle: `${c.baseModel} · ${c.measures.length} 个指标`, file: c.path, dirty: dirty(c.path) }));
  // spec117: rules live in the workbench; the remaining knowledge files stay browsable under 高级.
  const otherKnowledge: AssetEntry[] = files.filter(f => /^knowledge\/(glossary|metrics|caveats)\//.test(f.path) && !f.path.endsWith('/.gitkeep'))
    .map(f => ({ key: f.path, title: f.path, file: f.path, dirty: dirty(f.path) }));
  const selected = params.get('selected');
  const hasPublished = publishedVersion > 0;
  const quickPrompts = useMemo(() => hasPublished && !setupActive ? getModelingPrompts(view) : undefined, [hasPublished, setupActive, view]);
  const version = hasPublished && versionChoice === 'published' ? 'published' : 'draft';
  const comparing = hasPublished && versionChoice === 'compare';
  const graphReady = view !== null;
  useEffect(() => {
    // The published version is shown, or compared against while viewing the draft (spec117).
    if (tab !== 'semantic' || !hasPublished || !graphReady) return undefined;
    let cancelled = false;
    void getKnowledgeBaseBrowse(groupId).then(value => { if (!cancelled) setBrowse(value); })
      .catch(e => { if (!cancelled) setError(message(e)); });
    return () => { cancelled = true; };
  }, [groupId, tab, hasPublished, publishedVersion, graphReady]);
  const snapshot = useMemo(() => version === 'published'
    ? (browse ? publishedSnapshot(browse) : null)
    : (view ? draftSnapshot(view) : null), [version, browse, view]);
  const snapshotFiles = useMemo(() => new Map((version === 'published' ? preview?.publishedFiles : preview?.files)?.map(f => [f.path, f.content]) ?? []), [version, preview]);
  const baseline = useMemo(() => version === 'draft' && browse ? publishedSnapshot(browse) : null, [version, browse]);
  const baselineFiles = useMemo(() => version === 'draft' && hasPublished && preview ? new Map(preview.publishedFiles.map(f => [f.path, f.content])) : null, [version, hasPublished, preview]);
  const workbenchView = workbenchViewOf(asset, params.get('view'));
  const tabs: { key: Tab; label: string }[] = [
    { key: 'semantic', label: '语义建模' }, { key: 'coach', label: '问数优化' }, { key: 'assets', label: '公共视图' }, { key: 'overview', label: '资料' },
  ];
  // A turn's changes are marked when it ends; marks accumulate from the earliest unmarked turn.
  const turnStart = useRef<string | null>(null);
  useEffect(() => {
    if (assistantState !== 'idle') { turnStart.current ??= new Date(Date.now() - 2000).toISOString(); return; }
    const since = turnStart.current;
    if (!since || head) return;
    turnStart.current = null;
    // Before the first publish everything is new; the workbench shows a summary instead.
    if (hasPublished) setChangeMark(previous => previous ?? { since });
  }, [assistantState, head, hasPublished]);
  useEffect(() => {
    if (!changeMark) { setMarked([]); return undefined; }
    let cancelled = false;
    void getDraftItems(groupId, changeMark.since ?? undefined).then(items => { if (!cancelled) setMarked(items); }).catch(() => undefined);
    return () => { cancelled = true; };
  }, [groupId, changeMark, preview]);
  const isSeen = useCallback((item: DraftItem) => seen.has(changeKey(item)), [seen]);
  const markSeen = (items: DraftItem[]) => setSeen(previous => {
    const next = new Set(previous);
    items.forEach(i => next.add(changeKey(i)));
    try { localStorage.setItem(`sw-seen:${groupId}`, JSON.stringify([...next].slice(-500))); } catch { /* per-browser only */ }
    return next;
  });
  const unseenCount = changeMark ? marked.filter(i => !isSeen(i)).length : 0;
  const pendingCount = confirmations.length + unseenCount;
  const revealChange = (item: DraftItem) => {
    if (!canLeaveField()) return;
    setPendingOpen(false);
    setComparisonOpen(false);
    setVersionChoice('draft');
    setParams({ mode: 'board', asset: 'semantic', view: item.category === 'MODEL' || item.category === 'RELATIONSHIP' ? 'graph' : 'rules' });
    setReveal({ id: ++sequence.current, item });
  };
  /** Fills the conversation's input; the user reads it and decides whether to send. */
  const askAssistant = (text: string) => {
    if (!canLeaveField()) return;
    setPrefill({ id: ++sequence.current, text });
    if (!comparisonOpen) openAssistant();
  };
  const setWorkbenchView = (next: WorkbenchView) => setParams({ asset: 'semantic', view: next, mode: comparisonOpen ? 'chat' : 'board' }, { replace: true });
  const showVersion = (next: typeof versionChoice) => {
    if (next !== versionChoice && canLeaveField()) setVersionChoice(next);
  };
  const versionBar = <div className="sm-version">
    {hasPublished && <WorkspaceMenu label="版本" triggerLabel="版本" items={[
      { label: `查看线上 v${publishedVersion}`, icon: 'globe', disabled: !browse || version === 'published', onClick: () => showVersion('published') },
      { label: '与线上对比', icon: 'graph', disabled: !browse || comparing, onClick: () => showVersion('compare') },
      ...(version === 'published' || comparing
        ? [{ label: '返回工作台', icon: 'back' as const, onClick: () => showVersion('draft') }] : []),
    ]} />}
    {version === 'published' && <>
      <span className="sm-version-status">正在查看线上 v{publishedVersion}（只读）</span>
      <button type="button" className="da-btn da-btn-sm" onClick={() => showVersion('draft')}>返回工作台</button>
    </>}
  </div>;
  const waiting = pending.length;
  const assistantLabel = assistantState === 'awaiting' ? '助手有待确认提案'
    : assistantState === 'running' ? (waiting ? `助手运行中 · ${waiting} 个请求排队` : '助手运行中')
    : waiting ? '请求排队中' : '助手空闲';
  const toQuery = workflow?.queryAvailable ? () => { if (canLeaveField()) navigate(`/chat?groups=${encodeURIComponent(groupId)}`); } : undefined;
  const preparationVersion = Math.max(workflow?.publishedVersion ?? 0, draftSummary.publishedVersion ?? 0);
  const preparing = useQueryReadiness(groupId, preparationVersion);
  const publishDraft = () => { if (canLeaveField()) setPublishSignal(n => n + 1); };
  // Indexing and warm-up run right after publishing; the first question should not wait for them.
  useEffect(() => {
    if (tourStep !== 'preparing' || preparing !== null) return;
    const timer = window.setTimeout(() => tourSignal('query-ready', { groupId }), 3000);
    return () => window.clearTimeout(timer);
  }, [tourStep, preparing, groupId]);
  // Any turn may rewrite drafts or trial questions; every panel listening for updates reloads.
  const turnDone = () => {
    window.dispatchEvent(new CustomEvent('modeling:updated', { detail: { groupId } }));
  };
  const locateConfirmations = () => { if (canLeaveField()) setPendingOpen(true); };

  const exitSetup = async (destination: string) => {
    if (confirmingScreening) return;
    if (setupLeaveGuard.current && !await setupLeaveGuard.current()) return;
    try { sessionStorage.setItem(preparationKey, '1'); } catch { /* Resume is optional. */ }
    navigate(destination);
  };
  const returnToModeling = () => {
    if (setupActive) void exitSetup('/configure/modeling');
    else if (canLeaveField()) navigate('/configure/modeling');
  };
  const finishScreening = (tables: ScreeningStatus[]) => {
    setFieldEditor(null);
    setScreening(tables);
    setConfirmingScreening(false);
    setSetupPhase('prepare');
    try { sessionStorage.setItem(preparationKey, '1'); } catch { /* Resume is optional. */ }
    void refresh();
  };

  const comparingConversation = inChat && comparisonOpen && !setupActive;
  const visibleBoard = !inChat || comparingConversation;
  const pairWithConversation = () => {
    if (!canLeaveField()) return;
    setComparisonOpen(true);
    setParams(previous => { const next = new URLSearchParams(previous); next.set('mode', 'chat'); return next; });
  };
  const inspectTurn = (turn: string, sideBySide = false) => {
    if (!canLeaveField()) return;
    setComparisonOpen(sideBySide);
    setChangeMark(previous => previous && (!previous.since || previous.since < turn) ? previous : { since: turn });
    setVersionChoice('draft');
    setParams({ asset: 'semantic', view: 'graph', mode: sideBySide ? 'chat' : 'board' });
  };
  const guidedScreening = setupFields && tourStep === 'screening' && !tour?.paused;
  const editingScreening = setupFields && (fieldEditor !== null || guidedScreening);
  const preparation = setupActive ? <div className="mw-setup-intro">
    <ol className="mw-setup-progress" aria-label="首次建模步骤">
      <li aria-current={setupFields ? 'step' : undefined} className={setupFields ? undefined : 'is-complete'}>
        <span>{setupFields ? '1' : <Icon name="check" size="sm" />}</span>整理字段
      </li>
      <li aria-current={setupFields ? undefined : 'step'}><span>2</span>准备</li>
      <li><span>3</span>自动建模</li>
    </ol>
    <div key={setupFields ? 'field-review' : 'model-preparation'} className="mw-setup-stage">
      {setupFields ? <FieldScreeningReview groupId={groupId} tables={screening} editorOpen={editingScreening}
        onOpen={setFieldEditor} onStatus={setScreening} onBusy={setConfirmingScreening} onDone={finishScreening} />
        : screening === null ? <GraphLoading /> : <ModelingPreparation groupId={groupId} tables={screening} startSignal={startSignal} onReady={updateWindowReady}
          onEditFields={() => { setSetupPhase('fields'); setFieldEditor({}); }} onDocumentsChanged={documentsChanged}
          onStart={startModeling} confirmWindow={tourStep === 'window' && !tour?.paused} />}
    </div>
  </div> : null;
  const setupCommand = (text: string) => {
    if (confirmingScreening) return true;
    const command = text.replace(/[。！!，,\s]+$/u, '');
    if (setupFields && /^(?:请)?(?:查看|整理|确认)(?:建模)?字段$/u.test(command) && screening?.length) {
      setFieldEditor({});
    } else if (!setupFields && screening !== null && /^(?:请)?开始(?:自动)?建模$/u.test(command)) {
      setStartSignal(n => n + 1);
    } else {
      setError(setupFields ? '请先在字段卡片中核对并确认数据表。' : '准备完成后可输入「开始自动建模」，或点击卡片上的按钮。');
      return true;
    }
    setError('');
    return true;
  };
  const openFirstBuild = () => {
    if (!canLeaveField()) return;
    setSetupPhase('prepare');
    setComparisonOpen(false);
  };

  return <div className={`modeling-workbench mw mw-conversation-workspace ${inChat ? 'mw-conversation' : 'mw-board'}${comparingConversation ? ' mw-comparing' : ''}${setupActive ? ' mw-preparation' : ''}`}>
    <div className="mw-top">
      <header className="mw-head">
        <div className="mw-crumb">
          <button type="button" className="mw-crumb-link" title="返回语义建模列表" disabled={confirmingScreening} onClick={returnToModeling}>语义建模</button><span className="mw-crumb-sep">/</span>
          <h1 className="mw-title" title={group.name}>{group.name}</h1>
          <span className={`mw-pill ${hasPublished ? 'mw-pill-ok' : ''}`}
            title={hasPublished && workflow && !workflow.queryAvailable ? '当前不可问数' : undefined}>{hasPublished && <span className="mw-dot" />}{hasPublished ? `线上 v${publishedVersion}` : '尚未发布'}</span>
        </div>
        <div className="mw-actions">
          {!setupActive && <>
            {workflow && <DraftBar groupId={groupId} workflow={workflow} assistantActive={!idle} pendingConfirmations={confirmations.length}
              checksPending={!confirmationsReady || screening === null} compact onLocate={locateConfirmations}
              publishSignal={publishSignal} onSummary={setDraftSummary} />}
            {pendingCount > 0 && <button type="button" className="da-btn da-btn-sm mw-pending-action" onClick={locateConfirmations}
              aria-haspopup="dialog" aria-label={`查看 ${pendingCount} 项待处理`} title={`查看 ${pendingCount} 项待处理`}>
              <Icon name="clock" size="sm" /><span>待处理</span><b>{pendingCount}</b>
            </button>}
            {comparingConversation && <button type="button" className="da-btn da-btn-sm mw-view-switch" onClick={() => { if (canLeaveField()) setComparisonOpen(false); }}>结束对照</button>}
            <button type="button" className="da-btn da-btn-sm mw-view-switch" onClick={inChat ? showBoard : openAssistant}
              aria-label={inChat ? '查看模型' : '建模对话'} title={inChat ? '查看模型' : assistantLabel}>
              <Icon name={inChat ? 'model' : 'chat'} size="sm" /><span>{inChat ? '查看模型' : '建模对话'}</span>
              {!idle && !inChat && <i className="mw-dot" aria-label={assistantLabel} />}
            </button>
          </>}
          {setupActive ? <button type="button" className="mw-back-to-library" disabled={confirmingScreening} onClick={() => void exitSetup(`/configure/datasets/${groupId}`)}>
            <Icon name="book" size="sm" />回到知识库
          </button> : <WorkspaceMenu label="更多操作" items={[
            ...(!hasPublished && screening?.length ? [{ label: '准备自动建模', icon: 'model' as const, disabled: !idle || draftSummary.publishing, onClick: openFirstBuild }] : []),
            ...(screening !== null ? [{ label: '事实表时间范围', icon: 'clock' as const, disabled: !idle, onClick: () => setWindowSignal(n => n + 1) }] : []),
            { label: '工程与快照', icon: 'file', onClick: () => { if (canLeaveField()) { setAdvancedOpen(true); selectTab('semantic'); } } },
            ...(toQuery ? [{ label: '去问数', icon: 'chat' as const, disabled: !!preparing || queryHeld, onClick: toQuery }] : []),
            { label: '回到知识库', icon: 'book', onClick: () => { if (canLeaveField()) navigate(`/configure/datasets/${groupId}`); } },
          ]} />}
        </div>
      </header>
      {visibleBoard && <nav aria-label="语义建模页面" className="mw-tabs">
        {tabs.map(item => <button key={item.key} type="button" aria-current={tab === item.key ? 'page' : undefined} className={`mw-tab ${tab === item.key ? 'mw-tab-on' : ''}`} onClick={() => selectTab(item.key)}>{item.label}</button>)}
      </nav>}
    </div>
    <div className="mw-content">
    {error && <div role="alert" className="da-alert da-alert-error">{error}<button className="da-btn da-btn-sm" onClick={() => void refresh()}>刷新</button></div>}
    {screening !== null && !setupActive && <FactQueryWindowSettings key={groupId} groupId={groupId} automatic initiallyReady={windowReadyRef.current} onReady={updateWindowReady} hideButton openSignal={windowSignal} />}
    {editingScreening && <div className="mw-field-overlay" role="dialog" aria-modal="true" aria-label="整理字段">
      <div className="mw-field-editor">
        <header><strong>整理字段</strong><button type="button" className="da-btn da-btn-sm" autoFocus={fieldEditor !== null} disabled={guidedScreening}
          onClick={async () => { if (!setupLeaveGuard.current || await setupLeaveGuard.current()) setFieldEditor(null); }}>返回对话</button></header>
        <FieldScreeningPanel groupId={groupId} mode="setup" datasetId={fieldEditor?.datasetId ?? (guidedScreening ? screening?.find(table => !table.confirmed)?.datasetId : undefined)}
          pendingOnly={fieldEditor?.pendingOnly} leaveGuard={setupLeaveGuard} onDone={finishScreening} />
      </div>
    </div>}
    {adjusting && <div className="da-modal-overlay" onClick={() => setAdjusting(null)}>
      <div className="da-modal-shell" style={{ width: 'min(1100px, 96vw)', height: '82vh', display: 'flex' }} onClick={e => e.stopPropagation()}>
        <FieldScreeningPanel groupId={groupId} mode="adjust" datasetId={adjusting} onClose={() => setAdjusting(null)}
          onAdjusted={(change, remodel) => {
            setAdjusting(null);
            void refresh();
            if (remodel) { request(remodelRequest(change), 'auto', remodelLabel(change)); openAssistant(); }
          }} />
      </div>
    </div>}
    <div className="modeling-workbench-body mw-body">
      <div className="mw-main" hidden={!visibleBoard}>
      {tab === 'overview' && <ModelingMaterials key={groupId} groupId={groupId} tables={screening} idle={idle} showDocuments
        onManageData={() => navigate(`/configure/datasets/${groupId}`)} onAdjust={setAdjusting} onDocumentsChanged={documentsChanged} onCoach={() => selectTab('coach')} />}
      {tab === 'semantic' && <section className="sm-pane">
        {!snapshot ? <GraphLoading /> : <SemanticWorkbench key={groupId} groupId={groupId} snapshot={snapshot}
          baseline={baseline} files={snapshotFiles} baselineFiles={baselineFiles} compareWithBaseline={comparing} fieldEditGuard={fieldEditGuard}
          views={version === 'draft' ? (view?.views ?? []).map(v => ({ name: v.name, sql: v.sql, description: v.description })) : []}
          issues={version === 'draft' ? (view?.issues ?? []).map(i => i.message) : []}
          editable={version === 'draft'} blocked={!idle ? '建模助手正在运行，完成后才能在页面上修改。' : screening === null ? '正在读取字段状态…' : null}
          view={workbenchView} onViewChange={setWorkbenchView} focusRelation={focusRelation} focusModel={selected}
          onChanged={turnDone} onAsk={askAssistant} toolbar={versionBar} changes={version === 'draft' ? changeMark : null} onChanges={setChangeMark}
          marked={marked} isSeen={isSeen} onSeen={item => markSeen([item])} onOpenPending={locateConfirmations} reveal={reveal} showDetail={!comparingConversation}
          publication={version === 'draft' && (draftSummary.changes > 0 || draftSummary.publishing || draftSummary.publishedVersion !== null)
            ? { summary: draftSummary, nextVersion: publishedVersion + 1, pending: confirmations.length, onPublish: publishDraft,
                onQuery: toQuery, queryReady: preparing === null && !queryHeld, preparing } : null} />}
        {advancedOpen && <AdvancedModelingPanel key={groupId} groupId={groupId} open={advancedOpen} onOpenChange={setAdvancedOpen} version={version} preview={preview}
          views={legacyAsset === 'cubes' ? [] : viewEntries} cubes={legacyAsset === 'views' ? [] : cubeEntries} knowledge={otherKnowledge} selected={selected} initialTab={legacyAsset === 'mdl' ? 'compiled' : legacyAsset === 'views' ? 'views' : legacyAsset === 'cubes' ? 'cubes' : 'files'}
          onSelect={key => setParams({ asset: 'semantic', mode: 'board', selected: key }, { replace: true })} onSelectKnowledge={key => setParams({ asset: 'semantic', mode: 'board', view: 'rules', selected: key }, { replace: true })} />}
      </section>}
      {tab === 'assets' && <SemanticAssetsPanel key={groupId} groupId={groupId} assistantIdle={idle}
        onDraft={(text, label) => { const sent = request(text, 'asset', label); if (sent) openAssistant(); return sent; }} />}
      {tab === 'coach' && <div className="mw-coach-wrap">
      <div className="mw-coach">
        <QueryCoachPanel key={groupId} groupId={groupId} assistantIdle={idle} available={hasPublished} onRequest={request}
          onCompare={comparingConversation ? undefined : pairWithConversation}
          confirmations={confirmations} onAnswer={answers => void answered(answers)} onOpenDocuments={() => selectTab('overview')}
          trial={<aside className="mw-coach-bar qc-trial-entry" aria-label="试问一个问题">
          <span className="mw-coach-bar-icon"><Icon name="chat" /></span><div className="mw-coach-bar-copy"><strong>试问一个问题</strong><p>助手按草稿写出 SQL，你确认正确的问答发布后才会在问数中被召回。</p></div>
          <button type="button" data-tour="trial-open" className={`da-btn ${trialOpen ? 'da-btn-primary' : ''}`} disabled={!windowReady} onClick={() => setTrialOpen(!trialOpen)} aria-expanded={trialOpen}>
            {trialOpen ? '收起试问' : '试问'}{examples ? <span className="mw-inline-count">已确认 {examples}</span> : null}<Icon name="chevron" size="sm" /></button>
        </aside>} />
        <ConfirmedExamplesSection key={groupId} groupId={groupId} initialOpen={asset === 'qa' || asset === 'sql'} />
      </div>
      {trialOpen && windowReady && <div className="mw-trial">
        <TrialPanel groupId={groupId} assistantIdle={idle} open={trialOpen} onClose={() => setTrialOpen(false)} onExamples={setExamples}
          onAsk={text => request(text, 'question')} onWrong={text => request(text, 'question')} />
      </div>}
      </div>}
      </div>
      <section className="mw-conversation-pane" aria-label="建模对话" hidden={!inChat}>
        <ModelingChatPanel key={groupId} groupId={groupId} variant="dock" intro={preparation}
          onClose={showBoard} composerLocked={setupActive || !windowReady} onCompose={setupActive ? setupCommand : undefined}
          composerHint={setupActive ? (setupFields ? '输入「查看字段」' : '输入「开始自动建模」') : undefined}
          composerSuggestion={setupActive && screening?.length ? (setupFields ? '查看字段' : '开始自动建模') : undefined}
          composerNotice={idle && !setupActive && confirmations.length > 0 ? <button type="button" className="mw-conversation-pending" onClick={() => {
            const target = conversationConfirmation.current;
            target?.scrollIntoView({ block: 'start' });
            target?.focus({ preventScroll: true });
          }}><span className="mw-conversation-pending-icon"><Icon name="circleAlert" size="sm" /></span><span className="mw-conversation-pending-label">有 {confirmations.length} 项口径待确认</span><strong>去确认</strong><Icon name="chevron" size="sm" /></button> : undefined}
          quickPrompts={quickPrompts}
          draftPrompt={head?.text ?? prefill?.text} draftPromptId={head?.id ?? prefill?.id} submitDraft={!!head}
          onDraftSubmitted={() => setQueue(previous => ({ groupId, items: previous.items.slice(1) }))}
          onTurnDone={turnDone} draftPurpose={head?.purpose} draftLabel={head?.label} onActivityChange={setAssistantState}
          renderTurnResult={((hasPublished && draftSummary.changes > 0) || tourStep === 'review') ? since => <TurnResultCard groupId={groupId} since={since} disabled={!idle}
            onView={turn => inspectTurn(turn)} onCompare={turn => inspectTurn(turn, true)} /> : undefined}
          footer={!idle || setupActive ? null : <>
            <AssetProposalCards groupId={groupId} disabled={!idle} refreshKey={assistantState}
              onDraft={(text, label) => request(text, 'asset', label)} />
            {confirmations.length ? <div ref={conversationConfirmation} className="mw-conversation-confirmation" data-tour="confirm-cards" tabIndex={-1}>
              <ConfirmationCards key={confirmations.map(c => c.id).join(',')} questions={confirmations} onSubmit={answers => void answered(answers)} />
            </div> : <NextStepCard summary={draftSummary} nextVersion={publishedVersion + 1}
              firstPublishedVersion={workflow?.publishedVersion === 1 && !workflow.draftChanged ? 1 : null} preparing={preparing}
              onView={showBoard}
              onPublish={publishDraft} onQuery={toQuery} queryHeldHint={queryHeld ? TOUR_QUERY_HINT : undefined} />}
          </>} />
      </section>
    </div>
    <dialog className="mw-pending-dialog" aria-labelledby="mw-pending-title" ref={pendingDialog}
      onClick={event => { if (event.target === event.currentTarget) setPendingOpen(false); }}
      onClose={() => setPendingOpen(false)}>
      {pendingOpen && <><header><h2 id="mw-pending-title">待处理</h2><button type="button" aria-label="关闭待处理" className="da-btn da-btn-sm mw-pending-close" onClick={() => setPendingOpen(false)}><Icon name="close" size="sm" /></button></header>
        <PendingPanel groupId={groupId} confirmations={confirmations} onAnswer={answers => void answered(answers)}
          items={changeMark ? marked : []} isSeen={isSeen} onSeen={item => markSeen([item])} onSeenAll={() => markSeen(marked)}
          onLocate={revealChange} onChanged={turnDone} disabled={idle ? null : '建模助手正在运行，完成后才能撤销。'} />
      </>}
    </dialog>
    </div>
  </div>;
}
