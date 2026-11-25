import { tourHoldsQuery, tourLocksDraftDrawer, tourSignal, useTour } from '../../state/guidedTour';
import { FactQueryWindowSettings } from '../../components/FactQueryWindowSettings';
import { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import { useNavigate, useSearchParams } from 'react-router-dom';
import ModelingChatPanel from '../../components/ModelingChatPanel';
import type { AssistantState } from '../../components/ModelingChatPanel';
import { confirmationRequest } from '../../components/modeling/modelingHistory';
import { getModelingPrompts } from '../../components/modeling/modelingPrompts';
import SemanticWorkbench from '../../components/modeling/SemanticWorkbench';
import { changeKey } from '../../components/modeling/SemanticWorkbench';
import type { ChangeMark, WorkbenchView } from '../../components/modeling/SemanticWorkbench';
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
import type { ScreeningStatus } from '../../api/fieldScreening';
import ConfirmationCards from '../../components/modeling/ConfirmationCards';
import { questionRequest } from '../../components/modeling/QuestionIntakeForm';
import { getModelingQuestions, interpretCoachConversation } from '../../api/modelingQuestions';
import type { CoachConversationTurn } from '../../api/modelingQuestions';
import DraftBar from '../../components/modeling/DraftBar';
import type { CanvasDraft, DraftSummary, DraftUndoRequest } from '../../components/modeling/DraftBar';
import NextStepCard from '../../components/modeling/NextStepCard';
import TurnResultCard from '../../components/modeling/TurnResultCard';
import KnowledgeEngineeringPage from './KnowledgeEngineeringPage';
import AssetProposalCards from '../../components/modeling/AssetProposalCards';
import QueryCoachPanel, { questionState } from '../../components/modeling/QueryCoachPanel';
import type { CoachReviewActions } from '../../components/modeling/QueryCoachPanel';
import { coachIntent, coachQuestionRequest, isScopedQuestion, newCoachQuestion, resolveCoachTarget } from '../../components/modeling/queryCoachScope';
import type { CoachQuestion } from '../../components/modeling/queryCoachScope';
import type { DatasetGroup } from '../../api/datasets';
import { getKnowledgeBaseBrowse, getMdlView, getMdlPreview } from '../../api/semanticModeling';
import type { KnowledgeBaseBrowse, MdlView, MdlPreview, MdlIssue } from '../../api/semanticModeling';
import type { AssetKind, SemanticAsset } from '../../api/semanticAssets';
import { resolveConfirmations } from '../../api/draftReview';
import type { ConfirmationAnswer } from '../../api/draftReview';
import type { ReviewSection } from '../../components/modeling/releaseDecision';
import type { TurnPurpose } from '../../api/chat';
import { useQueryReadiness } from '../../components/useQueryReadiness';
import SemanticAssetsPanel from '../../components/modeling/SemanticAssetsPanel';
import { knowledgePath, modelingParams, type KnowledgeModelingHost, type ModelingSection } from './knowledgeWorkspaceRoutes';
import './SemanticModelingPage.css';
import '../../components/modeling/ModelingSetup.css';
import '../../components/modeling/ModelingNavigation.css';

type Tab = 'semantic' | 'coach' | 'assets' | 'overview' | 'engineering';
// spec092 §7: the conversation is the workspace; review happens inline, publishing in the draft bar.
// spec117: models, relations and business knowledge are one 语义模型 tab; older addresses still work.
const LEGACY_TABS: Record<string, Tab> = {
  models: 'semantic', relations: 'semantic', knowledge: 'semantic', modeling: 'semantic', qa: 'coach',
  schema: 'semantic', derived: 'semantic', views: 'engineering', cubes: 'engineering', mdl: 'engineering', glossary: 'semantic', sql: 'coach',
};
const TAB_KEYS: Tab[] = ['semantic', 'coach', 'assets', 'overview', 'engineering'];
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
type Request = { id: number; text: string; purpose?: TurnPurpose; documents?: string[]; label?: string; questionId?: string };

/** spec106: managers get the workbench; a QUERY user gets the published version read-only. */
/** The guided tour takes the user to the query page itself once querying is ready. */
const TOUR_QUERY_HINT = '引导会在问数准备好后带你去问数';

export default function SemanticModelingPage({ group, host }: { group: DatasetGroup; host: KnowledgeModelingHost }) {
  return group.canManage ? <ModelingWorkbench key={group.id} group={group} host={host} />
    : <SemanticReadOnlyWorkspace key={group.id} groupId={group.id} host={host} />;
}

function GraphLoading() {
  return <div className="mw-graph-loading" role="status">
    <span>正在加载语义模型…</span>
    <div aria-hidden="true"><i /><b /><i /></div>
  </div>;
}

function ModelingWorkbench({ group, host }: { group: DatasetGroup; host: KnowledgeModelingHost }) {
  const groupId = group.id;
  const navigate = useNavigate();
  const [params, setParams] = useSearchParams();
  const asset = params.get('asset');
  const legacyAsset = asset === 'assets' ? params.get('kind') : asset;
  const explicitTab = tabOf(asset, params.get('kind'));
  const [view, setView] = useState<MdlView | null>(null);
  const [preview, setPreview] = useState<MdlPreview | null>(null);
  const { review, workflow, screening, setScreening, reload: reloadRelease } = host.release;
  const confirmations = host.release.confirmations ?? [];
  const confirmationsReady = !!host.release.confirmations && !host.release.loading;
  const { coach: coachData, onActivity } = host;
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
  const screeningPending = !!screening?.some(t => !t.confirmed);
  const preparationKey = `modeling-prepare:${groupId}`;
  const [setupPhase, setSetupPhase] = useState<'fields' | 'prepare' | null>(() => {
    if (params.get('start') === '1') return 'prepare';
    if (params.get('mode') === 'board' || explicitTab === 'engineering') return null;
    try { return sessionStorage.getItem(preparationKey) === '1' ? 'prepare' : null; } catch { return null; }
  });
  const [fieldEditor, setFieldEditor] = useState<ScreeningSelection | null>(null);
  const [confirmingScreening, setConfirmingScreening] = useState(false);
  const [firstBuildRequested, setFirstBuildRequested] = useState(false);
  const [startSignal, setStartSignal] = useState(0);
  const setupFields = explicitTab !== 'engineering' && (((params.get('mode') !== 'board' || setupPhase !== null) && screeningPending) || (screening === null && setupPhase !== null) || setupPhase === 'fields' || fieldEditor !== null);
  const setupActive = setupFields || setupPhase !== null;
  const setupLeaveGuard = useRef<(() => Promise<boolean>) | null>(null);
  const [adjusting, setAdjusting] = useState<string | null>(null);
  const adjustmentDialog = useRef<HTMLDialogElement>(null);
  const adjustmentGuard = useRef<(() => Promise<boolean>) | null>(null);
  useEffect(() => {
    const dialog = adjustmentDialog.current;
    if (adjusting && dialog && !dialog.open) dialog.showModal();
  }, [adjusting]);
  const [draftSummary, setDraftSummary] = useState<DraftSummary>({ changes: 0, examples: 0, publishing: false, publishedVersion: null, error: null });
  const [publishSignal, setPublishSignal] = useState(0);
  const [checkTarget, setCheckTarget] = useState<HTMLDivElement | null>(null);
  const [reviewDraftSignal, setReviewDraftSignal] = useState(0);
  const [prefill, setPrefill] = useState<{ id: number; text: string } | null>(null);
  const [focusRelation, setFocusRelation] = useState<string | null>(null);
  const [changeMark, setChangeMark] = useState<ChangeMark | null>(null);
  const [versionChoice, setVersionChoice] = useState<'draft' | 'published' | 'compare'>('draft');
  const fieldEditGuard = useRef<(() => boolean) | null>(null);
  const canLeaveField = () => fieldEditGuard.current?.() ?? true;
  const [browse, setBrowse] = useState<KnowledgeBaseBrowse | null>(null);
  const [canvasDraft, setCanvasDraft] = useState<CanvasDraft>();
  const hasModelingChanges = review?.changes.some(change =>
    change.sources.includes('auto') || change.sources.includes('confirm')) === true;
  const [undoRequest, setUndoRequest] = useState<DraftUndoRequest | null>(null);
  const publishedVersion = workflow?.publishedVersion ?? group.publishedVersion ?? 0;
  const tab: Tab = explicitTab ?? 'semantic';
  const mode = params.get('mode');
  const inChat = tab !== 'engineering' && (setupActive || mode === 'chat' || (mode !== 'board' && (!explicitTab || asset === 'modeling')));
  const [comparisonOpen, setComparisonOpen] = useState(false);
  const [coachSelection, setCoachSelection] = useState<string | undefined>(() => params.get('question') ?? undefined);
  const coachReviewActions = useRef<CoachReviewActions>(null);
  const [coachScope, setCoachScope] = useState<CoachQuestion>();
  const [resourceScope, setResourceScope] = useState<{
    kind: 'view' | 'document' | 'table'; name: string; quote?: string; assetKind?: AssetKind;
    version?: 'published' | 'draft'; publishedEvidence?: { description: string; sql: string };
  }>();
  const [composerFocus, setComposerFocus] = useState(0);
  const [coachPicker, setCoachPicker] = useState<{ ids?: string[]; hint?: string; request?: string }>();
  const [coachSearch, setCoachSearch] = useState('');
  const chosenForRequest = useRef<{ text: string; id: string }>();
  const [coachSplitSide, setCoachSplitSide] = useState<'question' | 'chat'>('question');
  const coachSplitOrigin = useRef<'board' | 'chat'>('board');
  const coachDialog = useRef<HTMLDialogElement>(null);
  useEffect(() => {
    const dialog = coachDialog.current;
    if (!dialog) return;
    if (coachPicker && !dialog.open) { setCoachSearch(''); dialog.showModal(); }
    else if (!coachPicker && dialog.open) dialog.close();
  }, [coachPicker]);
  const [taskSignal, setTaskSignal] = useState(0);
  const conversationConfirmation = useRef<HTMLDivElement>(null);
  const showBoard = () => {
    if (!canLeaveField()) return;
    setComparisonOpen(false);
    setParams(previous => { const next = new URLSearchParams(previous); next.set('mode', 'board'); return next; });
  };
  const openAssistant = () => {
    if (!canLeaveField()) return;
    setComparisonOpen(false);
    setParams(previous => { const next = new URLSearchParams(previous); next.set('mode', 'chat'); return next; });
  };
  const [marked, setMarked] = useState<DraftItem[]>([]);
  const [seen, setSeen] = useState<Set<string>>(new Set());
  const [reveal, setReveal] = useState<{ id: number; item: DraftItem } | null>(null);

  const activeGroup = useRef(groupId);
  useLayoutEffect(() => {
    activeGroup.current = groupId;
    return () => { activeGroup.current = ''; };
  }, [groupId]);
  const generation = useRef(0);
  const sequence = useRef(0);
  const selectTab = (key: Tab, nextView?: WorkbenchView) => {
    if (canLeaveField()) {
      setComparisonOpen(false);
      setCoachScope(undefined); setResourceScope(undefined);
      setParams({ asset: key, mode: 'board', ...(nextView ? { view: nextView } : {}) });
    }
  };
  /** {@code label} is what the conversation shows for a request the platform wrote. */
  const request = (text: string, purpose?: TurnPurpose, label?: string, questionId?: string) => {
    if (!windowReadyRef.current) { setError('请先完成事实表时间配置，再开始建模。'); return false; }
    const item = { id: ++sequence.current, text, purpose, label, questionId };
    setQueue(previous => ({ groupId, items: [...previous.items, item] }));
    return true;
  };
  // Several quick document changes merge into one queued request instead of one turn each.
  const documentsChanged = (change: string, removedName?: string) => {
    if (removedName) setResourceScope(scope => scope?.kind === 'document' && scope.name === removedName ? undefined : scope);
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
  const refreshGraph = useCallback(async () => {
    const current = ++generation.current;
    const isCurrent = () => current === generation.current && activeGroup.current === groupId;
    setError('');
    const load = async <T,>(request: Promise<T>, apply: (value: T) => void) => {
      try {
        const value = await request;
        if (isCurrent()) apply(value);
      } catch (e) {
        if (isCurrent()) setError(previous => previous || message(e));
      }
    };
    await load(getMdlView(groupId), setView);
    if (isCurrent()) await load(getMdlPreview(groupId), setPreview);
  }, [groupId]);
  const refresh = async () => { await Promise.all([refreshGraph(), reloadRelease()]); };
  useEffect(() => {
    setReadyGroup(null); windowReadyRef.current = false;
    setView(null); setPreview(null); setAdjusting(null);
    setPrefill(null); setFocusRelation(null); setChangeMark(null); setMarked([]); setReveal(null);
    setCanvasDraft(undefined); setUndoRequest(null);
    try { setSeen(new Set(JSON.parse(localStorage.getItem(`sw-seen:${groupId}`) ?? '[]') as string[])); } catch { setSeen(new Set()); } setVersionChoice('draft'); setBrowse(null);
    void refreshGraph();
    const update = (event: Event) => {
      if ((event as CustomEvent<{ groupId: string }>).detail?.groupId === groupId) void refreshGraph();
    };
    window.addEventListener('modeling:updated', update);
    return () => { generation.current++; window.removeEventListener('modeling:updated', update); };
  }, [groupId, refreshGraph]);
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
    if (tourStep !== 'modeling' || !idle || !confirmationsReady
      || !(workflow?.engineeringStatus === 'PASSED' || workflow?.queryAvailable || confirmations.length > 0 || hasModelingChanges && workflow?.engineeringStatus === 'NOT_CHECKED')) return;
    const timer = window.setTimeout(() => tourSignal('model-idle', { groupId }), 4000);
    return () => window.clearTimeout(timer);
  }, [tourStep, idle, confirmationsReady, confirmations.length, workflow?.engineeringStatus, workflow?.queryAvailable, hasModelingChanges, groupId]);
  const startModeling = () => {
    if (!idle || screeningPending || !request(AUTO_MODELING_REQUEST, 'auto', '开始自动建模')) return;
    setFirstBuildRequested(true);
    tourSignal('model-started', { groupId });
    setSetupPhase(null);
    try { sessionStorage.removeItem(preparationKey); } catch { /* Resume is optional. */ }
    setParams({ asset: 'semantic', mode: 'chat' }, { replace: true });
  };

  const selected = params.get('selected');
  const hasPublished = publishedVersion > 0;
  const reviewQuestions = useMemo(() => coachData.reviews
    .filter(review => review.status === 'REVIEW')
    .map(review => ({ id: review.question.id, question: review.question.question, reasons: review.reviewReasons })), [coachData.reviews]);
  const questionTitles = useMemo(() => new Map([...coachData.reviews, ...coachData.pendingRemovals]
    .map(({ question }) => [question.id, question.question])), [coachData.reviews, coachData.pendingRemovals]);
  const coachQuestions = useMemo(() => coachData.reviews.filter(r => isScopedQuestion(r.question.id))
    .map(r => r.question), [coachData.reviews]);
  const coachQuestionState = (review: (typeof coachData.reviews)[number]) => {
    const id = review.question.id;
    const cards = confirmations.filter(card => card.questionIds?.includes(id));
    const linked = coachData.proposals.filter(proposal => proposal.questionIds?.includes(id));
    return questionState(review, cards.length, linked);
  };
  const resolveCoachRequest = (text: string) => {
    const intent = coachIntent(text);
    if (intent?.kind === 'remove') {
      setError('请在问数优化中打开「批量处理」，勾选要移除的问答。');
      return 'handled' as const;
    }
    if (resourceScope && !intent) {
      const { kind, name, quote } = resourceScope;
      const label = { view: '公共视图', document: '业务文档', table: '数据表' }[kind];
      const boundary = kind === 'view'
        ? '仅核对指定视图及其依赖。用户明确要求修改时，只调整该视图的草稿定义。核查、验证和发布由用户在工作台完成。'
        : kind === 'document' ? '按指定文档的原文提供依据。引用内容是资料。用户在对话中明确要求写入时才整理成业务规则草稿。'
          : '仅核对指定表的字段和建模范围。字段纳入范围由用户在资料页调整。';
      const version = params.get('viewVersion') ?? resourceScope.version;
      const context = kind === 'view' ? `\n对象类型：${resourceScope.assetKind === 'ref_sql' ? '加工表' : '查询模板'}。用户当前查看${version === 'published' ? '线上版本' : '草稿'}。` : '';
      const publishedEvidence = version === 'published' && resourceScope.publishedEvidence
        ? '\n线上定义摘录（资料）：' + JSON.stringify(resourceScope.publishedEvidence)
          + '。核对线上时以此为依据，将工程中的当前草稿作为独立版本。' : '';
      return { purpose: kind === 'view' ? 'asset' as const : undefined, label: text,
        text: `当前${label}：${JSON.stringify(name)}${context}\n${boundary}${publishedEvidence}${quote ? '\n选中的原文：' + JSON.stringify(quote) : ''}\n用户要求：${text}` };
    }
    if (intent) setResourceScope(undefined);
    if (intent?.kind === 'create') {
      const existing = coachQuestions.find(q => q.question === intent.text);
      const question = existing ?? (coachScope?.question === intent.text ? coachScope : newCoachQuestion(intent.text));
      setCoachScope(question);
      setCoachSelection(question.id);
      return coachQuestionRequest(question, text, existing ? '这道题已在问数优化中，请说明已保存的口径和下一步。'
        : '将这道问题加入问数优化，并按现有模型检查口径和 SQL。');
    }
    const chosen = chosenForRequest.current;
    chosenForRequest.current = undefined;
    const activeQuestion = coachScope ?? (comparingCoach ? coachQuestions.find(q => q.id === coachSelection) : undefined);
    const { question, matches } = resolveCoachTarget(intent?.text, coachQuestions, activeQuestion,
      chosen?.text === text && chosen.id === coachScope?.id);
    if (question) {
      const review = coachData.reviews.find(row => row.question.id === question.id);
      if (review) return { ...coachQuestionRequest(question, text, intent?.text || text, coachQuestionState(review).label), interpretAction: true };
      setCoachScope(question);
      setCoachSelection(question.id);
      return coachQuestionRequest(question, text, intent?.text || text);
    }
    if (!intent) return undefined;
    setCoachPicker({ ids: matches.length > 1 ? matches.map(q => q.id) : undefined, request: text,
      hint: matches.length > 1 ? '找到了多道相关问题。请选一道，再发送这条消息。' : '请选择要处理的题目，输入内容会保留。' });
    return null;
  };
  const pickerOptions = coachPicker ? coachQuestions.filter(q =>
    (!coachPicker.ids || coachPicker.ids.includes(q.id)) &&
    q.question.toLocaleLowerCase().includes(coachSearch.trim().toLocaleLowerCase())) : [];
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
    const since = changeMark?.since;
    if (!since) { setMarked([]); return undefined; }
    let cancelled = false;
    void getDraftItems(groupId, since).then(items => { if (!cancelled) setMarked(items); }).catch(() => undefined);
    return () => { cancelled = true; };
  }, [groupId, changeMark?.since, preview]);
  const isSeen = useCallback((item: DraftItem) => seen.has(changeKey(item)), [seen]);
  const markSeen = (items: DraftItem[]) => setSeen(previous => {
    const next = new Set(previous);
    items.forEach(i => next.add(changeKey(i)));
    try { localStorage.setItem(`sw-seen:${groupId}`, JSON.stringify([...next].slice(-500))); } catch { /* per-browser only */ }
    return next;
  });
  const scopeItems = changeMark?.since ? marked : canvasDraft?.items;
  const markedKeys = useMemo(() => new Set(scopeItems?.map(changeKey)), [scopeItems]);
  const revealChange = (item: DraftItem) => {
    if (!canLeaveField()) return false;
    setComparisonOpen(false);
    setVersionChoice('draft');
    setParams({ mode: 'board', asset: 'semantic', view: item.category === 'MODEL' || item.category === 'RELATIONSHIP' ? 'graph' : 'rules' });
    setReveal({ id: ++sequence.current, item });
    return true;
  };
  const openEngineering = (path?: string, compare = false) => {
    if (!canLeaveField()) return false;
    setComparisonOpen(false);
    setParams({ ...modelingParams('engineering'), ...(path ? { path } : {}), ...(compare ? { compare: '1' } : {}),
      ...(version === 'published' && !compare ? { version: 'published' } : {}) });
    return true;
  };
  const requestDraftUndo = (path: string | null) => {
    if (!idle || draftSummary.publishing || tourLocksDraftDrawer(tour, groupId) || !canLeaveField()) return false;
    setUndoRequest({ path });
    return true;
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
  const versionPicker = hasPublished ? <select className="da-input mw-version-select" aria-label="切换模型版本"
    value={versionChoice ?? 'draft'} onChange={event => showVersion(event.target.value as 'draft' | 'published' | 'compare')}>
    <option value="draft">工作草稿 · 基于 v{publishedVersion}</option>
    <option value="published" disabled={!browse}>线上 v{publishedVersion} · 只读</option>
    <option value="compare" disabled={!browse}>对比线上 v{publishedVersion}</option>
  </select> : undefined;
  const waiting = pending.length;
  const assistantLabel = assistantState === 'awaiting' ? '助手有待确认提案'
    : assistantState === 'running' ? (waiting ? `助手运行中 · ${waiting} 个请求排队` : '助手运行中')
    : waiting ? '请求排队中' : '助手空闲';
  const toQuery = workflow?.queryAvailable ? () => { if (canLeaveField()) navigate(`/chat?groups=${encodeURIComponent(groupId)}`); } : undefined;
  const preparationVersion = Math.max(workflow?.publishedVersion ?? 0, draftSummary.publishedVersion ?? 0);
  const preparing = useQueryReadiness(preparationVersion > 0 ? groupId : undefined, preparationVersion);
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
  const locateConfirmations = () => { if (canLeaveField()) setTaskSignal(n => n + 1); };

  const exitSetup = async () => {
    if (confirmingScreening) return false;
    if (setupLeaveGuard.current && !await setupLeaveGuard.current()) return false;
    try { sessionStorage.setItem(preparationKey, '1'); } catch { /* Resume is optional. */ }
    return true;
  };
  useLayoutEffect(() => {
    const guard = () => {
      if (draftSummary.publishing) return false;
      return setupActive ? exitSetup() : canLeaveField();
    };
    const select = async (section: ModelingSection) => {
      if (!await guard()) return;
      if (setupActive) { setFieldEditor(null); setSetupPhase(null); }
      setComparisonOpen(false); setCoachScope(undefined); setResourceScope(undefined);
      setParams(modelingParams(section));
    };
    host.leave.current = guard;
    host.select.current = section => void select(section);
    return () => { if (host.leave.current === guard) { host.leave.current = null; host.select.current = null; } };
  });
  const finishScreening = (tables: ScreeningStatus[]) => {
    setFieldEditor(null);
    setScreening(tables);
    setConfirmingScreening(false);
    setSetupPhase('prepare');
    try { sessionStorage.setItem(preparationKey, '1'); } catch { /* Resume is optional. */ }
    void refresh();
  };

  const comparingConversation = inChat && comparisonOpen && !setupActive;
  const comparingCoach = comparingConversation && tab === 'coach';
  const coachRoute = `${groupId}:${params}:${comparingCoach}:${coachScope?.id ?? ''}:${coachSelection ?? ''}`;
  const currentCoachRoute = useRef(coachRoute);
  currentCoachRoute.current = coachRoute;
  const comparingResource = comparingConversation && (tab === 'assets' || tab === 'overview');
  const comparingAsset = comparingResource && tab === 'assets';
  const splitReview = comparingCoach || comparingResource;
  const assetChatTrigger = useRef<HTMLButtonElement | null>(null);
  useLayoutEffect(() => {
    if (comparingAsset) return;
    assetChatTrigger.current?.focus({ preventScroll: true });
    assetChatTrigger.current = null;
  }, [comparingAsset]);
  const chatConfirmations = comparingCoach
    ? confirmations.filter(card => !coachSelection || !card.questionIds?.includes(coachSelection)) : confirmations;
  const visibleBoard = !inChat || comparingConversation;
  const openQuestionReview = (id: string) => {
    if (!canLeaveField() || coachReviewActions.current?.open(id) === false) return false;
    setComparisonOpen(false);
    setCoachScope(undefined);
    setResourceScope(undefined);
    setCoachSelection(id);
    setParams({ asset: 'coach', mode: 'board', question: id });
    return true;
  };
  const openAssetReview = (target: NonNullable<MdlIssue['assetReview']>) => {
    if (!canLeaveField()) return false;
    setComparisonOpen(false); setResourceScope(undefined); setCoachScope(undefined);
    setParams({ asset: 'assets', mode: 'board', viewGroup: 'pending', viewItem: `${target.kind}:${target.name}`,
      viewVersion: 'draft', viewStage: target.step === 'VERIFY_PENDING' ? 'verify' : 'overview' });
    return true;
  };
  const pairCoachQuestion = (question: CoachQuestion, from: 'board' | 'chat') => {
    if (!canLeaveField()) return false;
    if (!comparingCoach) coachSplitOrigin.current = from;
    setCoachSelection(question.id);
    setResourceScope(undefined);
    setCoachScope(question);
    setCoachSplitSide(from === 'board' ? 'chat' : 'question');
    if (from === 'board') setComposerFocus(n => n + 1);
    setComparisonOpen(true);
    setParams({ asset: 'coach', mode: 'chat', question: question.id });
    return true;
  };
  const resolveCoachAction = async (id: string, text: string, recentTurns: CoachConversationTurn[]) => {
    const review = coachData.reviews.find(row => row.question.id === id);
    if (!review || coachData.error) return null;
    const { group } = coachQuestionState(review);
    const phase = group === 'done' ? 'CONFIRMED'
      : review.question.sql && (group === 'sql' || group === 'review') ? 'REVIEW_SQL' : 'PREPARING';
    const intent = await interpretCoachConversation(groupId, id, { revision: review.revision, phase, message: text, recentTurns })
      .catch(() => null);
    if (currentCoachRoute.current !== coachRoute) return '题目或工作区已切换，请在当前页面重新发送。';
    if (intent?.action === 'REVIEW_PUBLICATION') {
      if (!canLeaveField()) return '请先完成当前编辑，再检查发布。';
      if (tourLocksDraftDrawer(tour, groupId)) return '请先完成当前引导，再查看发布改动。';
      if (!workflow) return '草稿状态尚未就绪，请稍后再试。';
      setReviewDraftSignal(n => n + 1);
      return phase === 'CONFIRMED'
        ? '已打开知识库草稿检查。请核对本次发布的改动，再确认发布。'
        : '已打开知识库草稿检查。这道题仍待验收，确认后才可随草稿发布。';
    }
    if (intent?.action === 'CONFIRM_SQL') {
      if (!comparingCoach || coachSelection !== id) {
        return pairCoachQuestion(review.question, 'chat')
          ? '已打开这道题。请先核对当前 SQL，再确认保存。' : '请结束当前编辑后，再打开题目核对。';
      }
      setCoachSplitSide('question');
      return await coachReviewActions.current?.confirm(id, review.revision) ?? '请打开这道题并核对当前 SQL。';
    }
    setCoachScope(review.question);
    setCoachSelection(id);
    return null;
  };
  const finishCoachRemoval = (id: string, online: boolean) => {
    setCoachScope(scope => scope?.id === id ? undefined : scope);
    if (online) return;
    const close = comparingCoach && coachSelection === id;
    if (close) setComparisonOpen(false);
    if (close || params.get('question') === id) setParams(previous => {
      const next = new URLSearchParams(previous);
      if (next.get('question') === id) next.delete('question');
      if (close) next.set('mode', coachSplitOrigin.current);
      return next;
    }, { replace: true });
  };
  const viewScope = (view: Pick<SemanticAsset, 'kind' | 'name'>, version: 'published' | 'draft', published?: SemanticAsset) => ({
    kind: 'view' as const, name: view.name, assetKind: view.kind, version,
    publishedEvidence: published ? { description: published.description?.slice(0, 3000) ?? '', sql: published.sql.slice(0, 5000) } : undefined,
  });
  const pairResource = (scope?: NonNullable<typeof resourceScope>, trigger?: HTMLButtonElement) => {
    if (!canLeaveField()) return;
    if (trigger) assetChatTrigger.current = trigger;
    coachSplitOrigin.current = 'board';
    setCoachScope(undefined); setResourceScope(scope);
    setCoachSplitSide('chat');
    setComparisonOpen(true); setComposerFocus(n => n + 1);
    if (!scope || scope.kind === 'view') {
      const next = new URLSearchParams(params);
      next.set('asset', 'assets'); next.set('mode', 'chat');
      if (scope) {
        next.set('viewItem', `${scope.assetKind ?? 'view'}:${scope.name}`);
        if (scope.version) next.set('viewVersion', scope.version);
      }
      setParams(next);
    } else setParams({ asset: 'overview', mode: 'chat' });
  };
  const closeComparison = () => {
    if (!canLeaveField()) return;
    setComparisonOpen(false);
    if (comparingCoach || comparingResource) setParams(previous => {
      const next = new URLSearchParams(previous);
      next.set('mode', coachSplitOrigin.current);
      return next;
    }, { replace: true });
  };
  const renderCoachResult = (id: string) => {
    const review = coachData.reviews.find(row => row.question.id === id);
    if (!review) return null;
    const state = coachQuestionState(review);
    return <div className="qc-turn-result">
      <span className="qc-next-icon"><Icon name="file" size="sm" /></span>
      <div><strong>{review.question.question}</strong><small>{state.label}</small></div>
      <button type="button" className="da-btn da-btn-sm" onClick={() => pairCoachQuestion(review.question, 'chat')}>查看这道题<Icon name="chevron" size="sm" /></button>
    </div>;
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
    if (!canLeaveField()) return false;
    setSetupPhase('prepare');
    setComparisonOpen(false);
    setParams({ asset: 'modeling', start: '1' });
    return true;
  };

  const modelCheckOnPage = visibleBoard && tab === 'semantic';
  const currentModule = tab === 'semantic' ? workbenchView : tab;
  const compactWorkbench = visibleBoard && currentModule !== 'graph' && !setupActive;
  const reviewChanges = () => { if (canLeaveField()) setReviewDraftSignal(n => n + 1); };
  const goToRelease = () => {
    if (host.decision.blocker?.kind === 'asset' || host.decision.blocker?.kind === 'incomplete') { locateConfirmations(); return; }
    if (host.decision.blocker?.kind === 'engineering') {
      if (canLeaveField()) setParams(previous => { const next = new URLSearchParams(previous); next.set('review', 'checks'); return next; });
      return;
    }
    reviewChanges();
  };
  const reviewParam = params.get('review');
  const reviewRequested: ReviewSection | null = reviewParam === '1' ? 'changes'
    : reviewParam === 'tasks' || reviewParam === 'checks' ? reviewParam : null;
  const clearReviewRequest = () => {
    if (!reviewRequested) return;
    setParams(previous => {
      const next = new URLSearchParams(previous);
      next.delete('review');
      return next;
    }, { replace: true });
  };
  const serverModeling = review?.assistantRunning === true;
  const headerAssistant = idle ? 'idle' : assistantState === 'awaiting' ? 'awaiting' : 'running';
  useLayoutEffect(() => {
    onActivity({ setup: setupActive, assistant: headerAssistant, publishing: draftSummary.publishing });
  }, [onActivity, setupActive, headerAssistant, draftSummary.publishing]);
  useLayoutEffect(() => () => onActivity(null), [onActivity]);

  const [focusConfirmation, setFocusConfirmation] = useState(false);
  useEffect(() => {
    if (!focusConfirmation || !inChat) return;
    const target = conversationConfirmation.current;
    if (target) {
      target.scrollIntoView({ block: 'start' });
      target.focus({ preventScroll: true });
    }
    setFocusConfirmation(false);
  }, [focusConfirmation, inChat]);
  const goToConfirmation = () => {
    if (!canLeaveField()) return;
    openAssistant();
    setFocusConfirmation(true);
  };

  const firstRelease = !hasPublished && !draftSummary.publishedVersion;
  const firstStep = (() => {
    if (!firstRelease || draftSummary.publishing || setupActive) return null;
    if (assistantState === 'awaiting' || (idle && !serverModeling && confirmationsReady && confirmations.length > 0)) {
      return {
        title: '还有口径待确认', detail: '到建模对话确认关系或业务口径，助手会继续完善草稿。',
        label: inChat && assistantState === 'awaiting' ? undefined : inChat ? '查看待确认项' : '去确认口径', action: goToConfirmation,
      };
    }
    if (!idle || serverModeling) return {
      title: '正在建模', detail: '助手正在完善模型草稿，完成后这里会显示下一步。',
      label: inChat ? undefined : '查看建模进度', action: openAssistant,
    };
    if (!workflow || screening === null || !confirmationsReady) return null;
    if (screeningPending) return {
      title: '确认建模字段', detail: '先核对纳入建模的表和字段，然后进入准备清单。',
      label: '去确认字段', action: openFirstBuild,
    };
    const blocker = host.decision.blocker;
    if (blocker?.kind === 'asset' || blocker?.kind === 'incomplete') return {
      title: '还有视图待处理', detail: '核查用途、完成验证或补齐文件后，再发布当前草稿。',
      label: '查看视图待办', action: goToRelease,
    };
    if (blocker?.kind === 'engineering') return {
      title: '模型校验未通过', detail: '查看发布检查中的具体问题，修正后再发布。',
      label: '查看校验问题', action: goToRelease,
    };
    if (workflow.canPublish && draftSummary.changes > 0) return null;
    if (workflow.stage !== 'DATA_PREPARATION' && hasModelingChanges && draftSummary.changes > 0) return {
      title: '建模草稿待发布', detail: '建模改动已保存。审阅后，系统会在发布时校验当前草稿。',
      label: '审阅并发布', action: goToRelease,
    };
    if (firstBuildRequested) return {
      title: '建模尚未完成', detail: '确认建模结果后再继续发布。可以在对话中查看结果或重试。',
      label: inChat ? '重试自动建模' : '查看建模对话', action: inChat ? startModeling : openAssistant,
    };
    return {
      title: '字段已确认，准备建模', detail: '核对查询时间范围，可以选传业务文档，然后开始自动建模。',
      label: '进入建模准备', action: openFirstBuild,
    };
  })();
  const nextStep = (onBoard: boolean) => firstStep ? <section className="mw-publish-card" aria-label="首次建模下一步">
    <span className="mw-publish-card-icon"><Icon name="model" /></span>
    <div className="mw-publish-card-copy" role="status"><h2>{firstStep.title}</h2><p>{firstStep.detail}</p></div>
    {firstStep.label && <div className="mw-publish-card-actions">
      <button type="button" className="da-btn da-btn-primary" onClick={firstStep.action}>{firstStep.label}<Icon name="chevron" size="sm" /></button>
    </div>}
  </section> : (!firstRelease || draftSummary.publishing || (workflow?.canPublish && confirmationsReady
    && screening !== null && !screeningPending && idle && !serverModeling)) && <NextStepCard summary={draftSummary}
    nextVersion={publishedVersion + 1} firstPublishedVersion={workflow?.publishedVersion === 1 && !workflow.draftChanged ? 1 : null}
    preparing={preparing} onView={onBoard ? undefined : showBoard} onPublish={tourStep === 'publish' ? publishDraft : goToRelease}
    publishLabel={tourStep === 'publish' ? undefined : '审阅并发布'} onQuery={toQuery} queryHeldHint={queryHeld ? TOUR_QUERY_HINT : undefined} />;
  return <div className={`modeling-workbench mw mw-workspace-layout mw-in-knowledge mw-conversation-workspace ${inChat ? 'mw-conversation' : 'mw-board'}${comparingConversation ? ' mw-comparing' : ''}${splitReview ? ' mw-coach-comparing' : ''}${setupActive ? ' mw-preparation' : ''}${compactWorkbench ? ' mw-compact-workbench' : ''}`} data-coach-side={splitReview ? coachSplitSide : undefined}>
    {host.versionTarget && visibleBoard && tab === 'semantic' && versionPicker && createPortal(versionPicker, host.versionTarget)}
    {host.actionsTarget && createPortal(<>
      {!setupActive && <>
        {modelCheckOnPage && snapshot && <div className="mw-header-check" ref={setCheckTarget} />}
        <DraftBar key={groupId} groupId={groupId} release={host.release} decision={host.decision} assistantActive={!idle} pendingConfirmations={confirmations.length}
          checksPending={!confirmationsReady || screening === null || coachData.loading} stateError={host.release.error ?? coachData.error}
          importing={host.importing} compact onLocate={locateConfirmations}
          reviewQuestions={reviewQuestions} questionTitles={questionTitles} onReviewQuestion={openQuestionReview}
          onReviewAsset={openAssetReview} confirmations={confirmations} onAnswer={answers => void answered(answers)}
          onInspectChange={path => openEngineering(path, true)} onLocateChange={revealChange}
          isUnseen={item => !!changeMark && markedKeys.has(changeKey(item)) && !isSeen(item)} onSeen={markSeen}
          publishSignal={publishSignal} reviewSignal={reviewDraftSignal} taskSignal={taskSignal} onSummary={setDraftSummary}
          onCanvasDraft={setCanvasDraft} undoRequest={undoRequest} beforePublish={canLeaveField}
          reviewRequested={reviewRequested} onDismissReview={clearReviewRequest}
          onOpenEngineering={() => openEngineering()} onPrepareData={() => { if (!canLeaveField()) return false; navigate(knowledgePath(groupId) + '?section=datasets'); return true; }}
          onPrepareModel={openFirstBuild} onRetryState={() => { void reloadRelease(); coachData.reload(); }} />
        <button type="button" className="da-btn da-btn-sm mw-view-switch" onClick={comparingConversation ? closeComparison : inChat ? showBoard : tab === 'engineering' ? () => setParams({ asset: 'semantic', mode: 'chat' }) : openAssistant}
          aria-label={inChat ? '查看模型' : '建模对话'} title={inChat ? '查看模型' : assistantLabel}>
          <Icon name={inChat ? 'model' : 'chat'} size="sm" /><span>{inChat ? tab === 'coach' ? '问数优化' : '查看模型' : '建模对话'}</span>
          {!idle && !inChat && <i className="mw-dot" aria-label={assistantLabel} />}
        </button>
      </>}
      {setupActive && <button type="button" className="mw-back-to-library" disabled={confirmingScreening} onClick={async () => { if (await exitSetup()) navigate(knowledgePath(groupId) + '?section=datasets'); }}>
        <Icon name="back" size="sm" />返回数据集
      </button>}
    </>, host.actionsTarget)}
    <div className="mw-content">
    {error && <div role="alert" className="da-alert da-alert-error">{error}<button className="da-btn da-btn-sm" onClick={() => void refresh()}>刷新</button></div>}
    {screening !== null && !setupActive && tab !== 'engineering' && <FactQueryWindowSettings key={groupId} groupId={groupId} automatic initiallyReady={windowReadyRef.current} onReady={updateWindowReady} hideButton />}
    {editingScreening && <div className="mw-field-overlay" role="dialog" aria-modal="true" aria-label="整理字段">
      <div className="mw-field-editor">
        <header><strong>整理字段</strong><button type="button" className="da-btn da-btn-sm" autoFocus={fieldEditor !== null} disabled={guidedScreening}
          onClick={async () => { if (!setupLeaveGuard.current || await setupLeaveGuard.current()) setFieldEditor(null); }}>返回对话</button></header>
        <FieldScreeningPanel groupId={groupId} mode="setup" datasetId={fieldEditor?.datasetId ?? (guidedScreening ? screening?.find(table => !table.confirmed)?.datasetId : undefined)}
          pendingOnly={fieldEditor?.pendingOnly} leaveGuard={setupLeaveGuard} onDone={finishScreening} />
      </div>
    </div>}
    {adjusting && <dialog ref={adjustmentDialog} className="mo-form-dialog mo-field-adjust-dialog" aria-label="调整字段范围"
      onClose={() => setAdjusting(null)} onCancel={event => {
        event.preventDefault();
        void adjustmentGuard.current?.().then(ok => { if (ok) adjustmentDialog.current?.close(); });
      }}>
      <FieldScreeningPanel groupId={groupId} mode="adjust" datasetId={adjusting} leaveGuard={adjustmentGuard}
          onClose={() => adjustmentDialog.current?.close()}
          onAdjusted={(change, remodel) => {
            adjustmentDialog.current?.close();
            void refresh();
            if (remodel && request(remodelRequest(change), 'auto', remodelLabel(change))) openAssistant();
          }} />
    </dialog>}
    {splitReview && <div className="qc-mobile-switch" role="group" aria-label="切换并排区域">
      <button type="button" className="da-btn da-btn-sm" aria-pressed={coachSplitSide === 'question'} onClick={() => setCoachSplitSide('question')}>{comparingCoach ? '题目详情' : tab === 'assets' ? '视图详情' : '资料详情'}</button>
      <button type="button" className="da-btn da-btn-sm" aria-pressed={coachSplitSide === 'chat'} onClick={() => setCoachSplitSide('chat')}>建模对话</button>
    </div>}
    <div className="modeling-workbench-body mw-body">
      <div className="mw-main" hidden={!visibleBoard}>
      {tab === 'engineering' && <KnowledgeEngineeringPage key={groupId} groupId={groupId} canManage />}
      {tab === 'overview' && <ModelingMaterials key={groupId} groupId={groupId} tables={screening} idle={idle} paired={comparingResource}
        onManageData={() => navigate(knowledgePath(groupId) + '?section=datasets')} onAdjust={setAdjusting} onDocumentsChanged={documentsChanged}
        onReference={(name, quote) => pairResource({ kind: 'document', name, quote })}
        onTable={table => pairResource({ kind: 'table', name: table.name })}
        onPublish={() => setReviewDraftSignal(n => n + 1)} />}
      {tab === 'semantic' && <section className="sm-pane">
        {visibleBoard && !comparingConversation && workbenchView === 'graph' && <header className="kbw-graph-heading">
          <h2>关系图</h2>{snapshot && <p>{snapshot.models.length} 个模型 · {snapshot.relations.length} 条关系</p>}
        </header>}
        {!inChat && workbenchView === 'graph' && (!hasPublished || publishedVersion === 1 || draftSummary.publishing || draftSummary.publishedVersion) &&
          <div className="mw-graph-next">{nextStep(true)}</div>}
        {!snapshot ? <GraphLoading /> : <SemanticWorkbench key={groupId} groupId={groupId} snapshot={snapshot}
          baseline={baseline} files={snapshotFiles} baselineFiles={baselineFiles} rulesReady={!!preview} compareWithBaseline={comparing} fieldEditGuard={fieldEditGuard}
          views={version === 'draft' ? (view?.views ?? []).map(v => ({ name: v.name, sql: v.sql, description: v.description })) : []}
          issues={version === 'draft' ? view?.issues ?? [] : []}
          editable={version === 'draft'} blocked={!idle || serverModeling ? '建模助手正在运行，完成后才能在页面上修改。' : screening === null ? '正在读取字段状态…' : null}
          view={workbenchView} onViewChange={setWorkbenchView} focusRelation={focusRelation} focusModel={selected} defaultRailOpen={false}
          onChanged={turnDone} onAsk={askAssistant} checkTarget={checkTarget} fullscreenVersion={versionPicker} changes={version === 'draft' ? changeMark : null} onChanges={setChangeMark}
          marked={scopeItems} canvasDraft={canvasDraft} onUndoDraft={requestDraftUndo}
          isSeen={isSeen} onSeen={item => markSeen([item])} onReviewChanges={reviewChanges} reveal={reveal}
          onEngineering={path => { openEngineering(path); }}
          onReviewAsset={openAssetReview} onDocuments={() => selectTab('overview')}
          showDetail={!comparingConversation} onExpandBoard={showBoard} />}
      </section>}
      {tab === 'assets' && <SemanticAssetsPanel key={groupId} groupId={groupId} assistantIdle={idle} paired={comparingAsset} publishedVersion={publishedVersion}
        onDiscuss={trigger => pairResource(undefined, trigger)}
        onAsk={(view, version, published, trigger) => pairResource(viewScope(view, version, published), trigger)}
        onPublish={() => setReviewDraftSignal(n => n + 1)}
        onDraft={(text, label) => {
          const sent = request(text, 'asset', label);
          if (sent) {
            setResourceScope(undefined); setCoachScope(undefined); setComparisonOpen(false);
            setParams({ asset: 'assets', mode: 'chat', viewGroup: 'pending' });
          }
          return sent;
        }} />}
      {tab === 'coach' && <div className="mw-coach-wrap">
      <div className="mw-coach">
        <QueryCoachPanel key={groupId} groupId={groupId} assistantIdle={idle} available={hasPublished} onRequest={request} preview={preview}
          data={coachData} selection={coachSelection} onSelect={setCoachSelection}
          onRemove={finishCoachRemoval}
          compact={comparingCoach} onPublish={() => setReviewDraftSignal(n => n + 1)}
          initialLibrary={asset === 'qa' || asset === 'sql'} draftChanges={draftSummary.changes} reviewActions={coachReviewActions}
          readyToAsk={windowReady}
          onCompare={q => pairCoachQuestion(q, 'board')}
          confirmations={confirmations} onAnswer={answers => void answered(answers)} onOpenDocuments={() => selectTab('overview')} />
      </div>
      </div>}
      </div>
      <section className="mw-conversation-pane" data-review-chat={comparingAsset || comparingCoach || undefined}
        aria-label={comparingAsset ? '公共视图边聊边核对' : '建模对话'} data-asset-chat={comparingAsset || undefined} hidden={!inChat}
        onKeyDown={comparingAsset || comparingCoach ? event => {
          if (event.key === 'Escape' && !event.defaultPrevented) closeComparison();
        } : undefined}>
        {(comparingAsset || comparingCoach) && <header className="mw-review-chat-heading">
          <strong>边聊边核对</strong>
          <button type="button" className="da-btn da-btn-sm" aria-label="关闭对话" onClick={closeComparison}><Icon name="close" size="sm" /></button>
        </header>}
        <ModelingChatPanel key={groupId} groupId={groupId} variant="dock" intro={preparation} assetChat={comparingAsset}
          onClose={showBoard} composerLocked={setupActive || !windowReady} onCompose={setupActive ? setupCommand : undefined}
          composerFocus={composerFocus}
          composerHint={setupActive ? (setupFields ? '输入「查看字段」' : '输入「开始自动建模」')
            : coachScope ? '描述这道题要修改的口径或 SQL…'
            : resourceScope?.kind === 'document' ? '输入要从这份资料核对的业务口径…'
              : resourceScope || comparingAsset ? '描述要核对或调整的内容…' : undefined}
          composerSuggestion={setupActive && screening?.length ? (setupFields ? '查看字段' : '开始自动建模') : undefined}
          composerNotice={idle && !setupActive && chatConfirmations.length > 0 ? <button type="button" className="mw-conversation-pending" onClick={() => {
            const target = conversationConfirmation.current;
            target?.scrollIntoView({ block: 'start' });
            target?.focus({ preventScroll: true });
          }}><span className="mw-conversation-pending-icon"><Icon name="circleAlert" size="sm" /></span><span className="mw-conversation-pending-label">有 {chatConfirmations.length} 项口径待确认</span><strong>去确认</strong><Icon name="chevron" size="sm" /></button> : undefined}
          quickPrompts={quickPrompts}
          onChooseCoachQuestion={quickPrompts ? () => setCoachPicker({}) : undefined}
          resolveCoachRequest={quickPrompts || resourceScope ? resolveCoachRequest : undefined}
          resolveCoachAction={resolveCoachAction}
          renderQuestionResult={renderCoachResult}
          coachScope={coachScope && quickPrompts ? <div className="qc-composer-scope">
            <Icon name="file" size="sm" /><span>问数优化 · {coachScope.question}</span>
            <button type="button" aria-label="移除题目范围" onClick={() => setCoachScope(undefined)}><Icon name="close" size="sm" /></button>
          </div> : resourceScope ? <div className="mo-source-scope">
            <div className="qc-composer-scope"><Icon name="file" size="sm" /><span>{{ view: '公共视图', document: '业务文档', table: '数据表' }[resourceScope.kind]} · {resourceScope.name}</span>
              <button type="button" aria-label="移除资料或视图范围" onClick={() => setResourceScope(undefined)}><Icon name="close" size="sm" /></button></div>
            {resourceScope.quote && <details key={resourceScope.quote} open>
              <summary onMouseDown={event => { if (event.button === 0) event.preventDefault(); }}>已引用原文</summary>
              <blockquote>{resourceScope.quote}</blockquote>
            </details>}
          </div> : undefined}
          draftPrompt={head?.text ?? prefill?.text} draftPromptId={head?.id ?? prefill?.id} submitDraft={!!head}
          onDraftSubmitted={() => setQueue(previous => ({ groupId, items: previous.items.slice(1) }))}
          onTurnDone={turnDone} draftPurpose={head?.purpose} draftLabel={head?.label} draftQuestionId={head?.questionId} onActivityChange={setAssistantState}
          renderTurnResult={((hasPublished && draftSummary.changes > 0) || tourStep === 'review') ? since => <TurnResultCard groupId={groupId} since={since} disabled={!idle}
            onView={turn => inspectTurn(turn)} onCompare={turn => inspectTurn(turn, true)} /> : undefined}
          footer={setupActive || (!idle && assistantState !== 'awaiting') ? null : <>
            <AssetProposalCards groupId={groupId} disabled={!idle} refreshKey={assistantState}
              onDraft={(text, label) => request(text, 'asset', label)} />
            {chatConfirmations.length ? <div ref={conversationConfirmation} className="mw-conversation-confirmation" data-tour="confirm-cards" tabIndex={-1}>
              <ConfirmationCards key={chatConfirmations.map(c => c.id).join(',')} questions={chatConfirmations} onSubmit={answers => void answered(answers)} />
            </div> : !comparingCoach && !comparingResource && nextStep(false)}
          </>} />
      </section>
    </div>
    <dialog ref={coachDialog} className="mo-form-dialog qc-picker" aria-labelledby="qc-picker-title" onClose={() => setCoachPicker(undefined)}>
      {coachPicker && <><header className="mo-dialog-head"><strong id="qc-picker-title">选择要优化的题目</strong>
        <button type="button" className="da-btn da-btn-sm" onClick={() => setCoachPicker(undefined)}>关闭</button></header>
        <div className="mo-dialog-body">
          {coachPicker.hint && <p className="da-small">{coachPicker.hint}</p>}
          {coachData.loading && !coachQuestions.length ? <p className="da-small" role="status">正在读取业务问题…</p>
            : coachData.error ? <p className="da-small" role="alert">{coachData.error} <button type="button" className="sm-text-link" onClick={coachData.reload}>重试</button></p>
            : !coachQuestions.length ? <p className="da-small">还没有业务问题，可以先添加一道。</p>
            : <><input type="search" className="da-input" aria-label="搜索题目" placeholder="搜索题目" value={coachSearch} onChange={e => setCoachSearch(e.target.value)} />
              {!pickerOptions.length && <p role="status" className="da-small">没有匹配的题目，试试其他关键词。</p>}
              <div className="qc-picker-questions">{pickerOptions.map(q =>
              <button key={q.id} type="button" className="da-btn" onClick={() => {
                chosenForRequest.current = coachPicker.request ? { text: coachPicker.request, id: q.id } : undefined;
                setResourceScope(undefined);
                setCoachScope(q); setCoachSelection(q.id); setCoachPicker(undefined);
              }}><span>{q.question}</span><Icon name="chevron" size="sm" /></button>)}</div></>}
        </div>
        <footer className="mo-dialog-footer"><button type="button" className="da-btn da-btn-sm" onClick={() => {
          setCoachPicker(undefined); setCoachScope(undefined);
          setPrefill({ id: ++sequence.current, text: '添加业务问题：' });
        }}><Icon name="plus" size="sm" />添加一道业务问题</button></footer></>}
    </dialog>
    </div>
  </div>;
}
