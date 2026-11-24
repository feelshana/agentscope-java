import { useCallback, useEffect, useImperativeHandle, useMemo, useRef, useState } from 'react';
import { archiveModelingQuestion, correctModelingQuestionSql, decideModelingQuestion, editRecommendedQuestion, getQuestionSuggestions, getRecommendedTrialQuestion, planModelingQuestion, regenerateRecommendedQuestion } from '../../api/modelingQuestions';
import type { DraftPlan, QuestionReview, RecommendedQuestion } from '../../api/modelingQuestions';
import { decideAssetProposal } from '../../api/semanticAssets';
import type { AssetProposal } from '../../api/semanticAssets';
import { recommendedQuestion } from '../../api/chat';
import type { TurnPurpose } from '../../api/chat';
import { DEFINITION_FORMAT, questionRequest } from './QuestionIntakeForm';
import { proposalRequest } from './AssetProposalCards';
import SemanticContextPanel from './SemanticContextPanel';
import ConfirmationCards from './ConfirmationCards';
import { listDocuments } from '../../api/draftReview';
import type { Confirmation, ConfirmationAnswer } from '../../api/draftReview';
import ReadableCode from '../ReadableCode';
import SqlEditor from '../SqlEditor';
import { deleteSqlQaExample, listSqlQaExamples, type MdlPreview, type SqlQaExample } from '../../api/semanticModeling';
import SqlQaExampleDetail from './SqlQaExampleDetail';
import { WorkspaceMenu } from '../KnowledgeWorkspace';
import { MaterialsCatalog, WorkspaceBatchInput, WorkspaceBatchToggle, WorkspaceBatchTools, WorkspaceEmpty, WorkspaceObjectTools, WorkspaceToolbar } from './ModelingWorkspace';
import { removeWorkspaceItems, updateWorkspaceSelection } from './workspaceRemoval';
import Icon from '../Icon';
import type { IconName } from '../Icon';
import type { ReactNode, Ref } from 'react';
import ModelingMethodFlow from './ModelingMethodFlow';
import { COACH_PREFIX, isScopedQuestion, newCoachQuestion } from './queryCoachScope';
import type { CoachQuestion } from './queryCoachScope';
import type { QueryCoachData } from './useQueryCoachData';
import './ModelingOptimization.css';

/** Coach questions keep this id prefix, so the list survives reloads without extra storage. */
const MAX_QUESTIONS = 10;
const KIND: Record<string, string> = { rule: '规则', relationship: '关系', calculated_field: '计算字段', view: '查询模板' };
const STEPS = ['分析', '口径/方案', 'SQL', '保存问答'] as const;
type Step = 0 | 1 | 2 | 3;
type Group = 'analyse' | 'meaning' | 'plan' | 'sql' | 'review' | 'failed' | 'done';
type RecommendationDialog = { mode: 'settings' | 'set'; question: string } | { mode: 'reset' };
type Removal = { question: CoachQuestion; review?: QuestionReview; example?: SqlQaExample; label: '移出待处理' | '移出示例库' | '下架示例' | '删除问答示例' };
export type CoachReviewActions = { open: (id: string) => boolean; confirm: (id: string, revision: string) => Promise<string> };
const removalLabel = (review?: QuestionReview): Removal['label'] => review?.published?.available ? '下架示例'
  : review?.status === 'CONFIRMED' || review?.validation?.confirmedAt ? '移出示例库' : '移出待处理';
const GROUPS: { key: Group; label: string; icon: IconName }[] = [
  { key: 'analyse', label: '待分析', icon: 'search' }, { key: 'meaning', label: '待确认口径', icon: 'chat' }, { key: 'plan', label: '待批准方案', icon: 'list' },
  { key: 'sql', label: '待核对 SQL', icon: 'code' }, { key: 'review', label: '待复核', icon: 'refresh' },
  { key: 'failed', label: '分析未完成', icon: 'circleAlert' }, { key: 'done', label: '已确认', icon: 'check' },
];
const queuePriority: Record<Group, number> = { meaning: 0, plan: 1, review: 2, sql: 3, failed: 4, analyse: 5, done: 6 };
const message = (e: unknown) => e instanceof Error ? e.message : String(e);

/** The coach turn request: analyse each question, write its file, and propose what is missing. */
export function coachRequest(questions: string[], ids: string[], documents: string[] = []): string {
  // spec114: the documents are named outright; a prompt-only "also read raw/docs/" was skipped.
  const docs = documents.length
    ? `本知识库有 ${documents.length} 份业务文档：${documents.map(d => `raw/docs/${d}`).join('、')}。分析前先用 read_file 逐份读取；口径在文档里写明的，按文档提议规则并注明出处，不要再提问。\n`
    : '';
  return docs + '请以"问数优化"的方式分析以下常用问题。每题先判断现有模型、关系和业务规则能否稳定答对，'
    + `再写入 knowledge/questions/<ID>.yml（question 原文不改写、definition、sql、analysis；${DEFINITION_FORMAT}），`
    + '只用 wren_cli dry-plan 检查 SQL，不查询数据；对缺口用 propose_modeling_change 提议，question_ids 填题目 ID。\n'
    + questions.map((q, i) => `${i + 1}. [ID: ${ids[i]}] ${q}`).join('\n');
}

/** Approved rules, relationships and calculated fields, written in one ordinary modeling turn. */
function applyRequest(items: AssetProposal[]): string {
  return '请按以下已批准的建模方案写入草稿，不要再向我确认：\n'
    + items.map((p, i) => `${i + 1}.（${KIND[p.kind] ?? p.kind}）${p.name}：${p.need}；要点：${p.outline}`).join('\n')
    + '\n规则写入 knowledge/rules/general.md 的官方小节（默认过滤写在"## Default filters"，术语定义写在"## Naming conventions"）；'
    + '关系写入 relationships.yml；计算字段写入对应模型的 columns（is_calculated、expression）。写完执行 context validate --strict，完成后用一两行说明写了什么。';
}

interface QuestionState { label: string; group: Group; step: Step; conclusion: string }

/**
 * spec114 §8: one status line for every question, and the conclusion derived from what the
 * question is linked to (cards, proposals, SQL) — the assistant never writes the conclusion.
 */
export function questionState(r: QuestionReview | undefined, cards: number, linked: AssetProposal[], waiting = false, interrupted = false): QuestionState {
  if (cards) return { label: '待确认口径', group: 'meaning', step: 1, conclusion: '需要补充业务定义' };
  if (linked.some(p => p.status === 'PENDING')) return { label: '待批准方案', group: 'plan', step: 1, conclusion: '建议完善模型' };
  if (interrupted || r?.status === 'FAILED' && !r.question.sql)
    return { label: '分析未完成', group: 'failed', step: 0, conclusion: '尚未生成可核对的 SQL' };
  if (!r) return { label: waiting ? '正在分析' : '待分析', group: 'analyse', step: 0, conclusion: '待分析' };
  if (!r.question.sql) return linked.some(p => p.status === 'DRAFTED')
    ? { label: '待生成 SQL', group: 'sql', step: 2, conclusion: '建议完善模型' }
    : { label: '待分析', group: 'analyse', step: 0, conclusion: '待分析' };
  const answerable = '现有模型可以回答';
  if (r.status === 'REVIEW') return { label: '口径变更待复核', group: 'review', step: 2, conclusion: answerable };
  if (r.status === 'CONFIRMED')
    return { label: r.published?.current ? '已确认 · 已发布' : '已确认 · 待发布', group: 'done', step: 3, conclusion: answerable };
  return { label: r.status === 'STALE' ? '已修改，待重新核对' : r.status === 'FAILED' ? 'SQL 检查未通过' : '待核对 SQL', group: 'sql', step: 2, conclusion: answerable };
}

/**
 * spec114 / ADR0132 "问数优化": the data developer lists frequent questions; the assistant analyses
 * gaps and proposes rules, relationships, calculated fields or (for logic reused across the batch)
 * query templates; approved items are drafted and each question's SQL is read and confirmed — nothing
 * is executed. One question at a time on the right, publishing stays in the draft bar.
 */
export default function QueryCoachPanel({ groupId, assistantIdle, available, onRequest, confirmations = [], onAnswer, onOpenDocuments, onCompare,
  data, selection, onSelect, onRemove, removalRequest, onRemovalRequestHandled, compact, onPublish, initialLibrary, draftChanges, readyToAsk, preview, reviewActions }: {
  groupId: string;
  assistantIdle: boolean;
  /** The knowledge base has been published once; the coach builds on the basic model. */
  available: boolean;
  onRequest: (text: string, purpose?: TurnPurpose, label?: string) => boolean;
  onCompare?: (question: CoachQuestion) => void;
  initialLibrary: boolean;
  draftChanges: number;
  readyToAsk: boolean;
  data: QueryCoachData;
  preview: MdlPreview | null;
  selection?: string;
  onSelect: (id: string | undefined) => void;
  onRemove: (id: string, online: boolean) => void;
  removalRequest?: string;
  onRemovalRequestHandled: () => void;
  reviewActions?: Ref<CoachReviewActions>;
  compact: boolean;
  onPublish: () => void;
  /** spec114: open confirmation cards; those linked to a coach question are answered here too. */
  confirmations?: Confirmation[];
  onAnswer?: (answers: ConfirmationAnswer[]) => void;
  /** Business documents live on the "资料" tab; the coach only links to it. */
  onOpenDocuments?: () => void;
}) {
  const { reviews: allReviews, pendingRemovals, proposals, previousSql, previousDefinitions, loading, error: loadError, reload: load, submitted, trackSubmission, removeQuestion } = data;
  const [directoryOpen, setDirectoryOpen] = useState(true);
  const [intakeError, setIntakeError] = useState('');
  const [extraSuggestions, setExtraSuggestions] = useState<string[]>([]);
  const [suggestionStatus, setSuggestionStatus] = useState<'idle' | 'loading' | 'loaded' | 'error'>('idle');
  const [recommendationDialog, setRecommendationDialog] = useState<RecommendationDialog>();
  const [recommendationError, setRecommendationError] = useState('');
  const recommendDialog = useRef<HTMLDialogElement>(null);
  const [removal, setRemoval] = useState<{ targets: Removal[]; batch?: boolean }>();
  const [removalResult, setRemovalResult] = useState<{ removed: number; errors: string[] }>();
  const removalDialog = useRef<HTMLDialogElement>(null);
  const openRemoval = useCallback((question: CoachQuestion, review?: QuestionReview) => {
    setRemovalResult(undefined);
    setRemoval({ targets: [{ question, review, label: removalLabel(review) }] });
  }, []);
  useEffect(() => {
    if (removal && !removalDialog.current?.open) removalDialog.current?.showModal();
  }, [removal]);
  const [input, setInput] = useState('');
  const [approved, setApproved] = useState<Set<string>>(new Set());
  const [picked, setPicked] = useState<Set<string>>(new Set());
  const [filter, setFilter] = useState<Group | ''>('');
  const [listGroup, setListGroup] = useState<'todo' | 'done'>(initialLibrary ? 'done' : 'todo');
  const [examples, setExamples] = useState<SqlQaExample[]>([]);
  const [exampleError, setExampleError] = useState('');
  const [libraryStatus, setLibraryStatus] = useState('');
  const [librarySource, setLibrarySource] = useState('');
  const [edit, setEdit] = useState<{ id: string; revision: string; value: string }>();
  const helpDialog = useRef<HTMLDialogElement>(null);
  const exampleLeaveGuard = useRef<(() => boolean) | null>(null);
  const detailFocus = useRef(compact ? undefined : selection);
  const sqlHeading = useRef<HTMLHeadingElement>(null);
  const [confirmationError, setConfirmationError] = useState<{ id: string; message: string }>();
  useEffect(() => {
    if (!available) return;
    let generation = 0;
    const refresh = () => {
      const id = ++generation;
      void listSqlQaExamples(groupId).then(items => {
        if (id === generation) { setExamples(items); setExampleError(''); }
      }).catch(e => { if (id === generation) setExampleError(message(e)); });
    };
    const onUpdated = (event: Event) => {
      if ((event as CustomEvent<{ groupId?: string }>).detail?.groupId === groupId) refresh();
    };
    refresh();
    window.addEventListener('modeling:updated', onUpdated);
    return () => { generation++; window.removeEventListener('modeling:updated', onUpdated); };
  }, [available, groupId]);
  const [batch, setBatch] = useState(false);
  const [codeView, setCodeView] = useState<{ id: string; tab: 'current' | 'previous' | 'expanded' }>();
  const [plans, setPlans] = useState<Record<string, { revision: string; plan: DraftPlan }>>({});
  const [contextQuestion, setContextQuestion] = useState<string | null>(null);
  const [intakeOpen, setIntakeOpen] = useState(false);
  const intake = useRef<HTMLDialogElement>(null);
  const intakeInput = useRef<HTMLTextAreaElement>(null);
  useEffect(() => {
    const dialog = intake.current;
    if (!dialog) return;
    if (intakeOpen && !dialog.open) {
      dialog.showModal();
      intakeInput.current?.focus();
    } else if (!intakeOpen && dialog.open) dialog.close();
  }, [intakeOpen]);
  const [busy, setBusy] = useState('');
  const [error, setError] = useState('');
  const [removalNotice, setRemovalNotice] = useState('');
  const [documents, setDocuments] = useState<string[] | null>(null);
  const [candidate, setCandidate] = useState<string | null>(null);
  /** spec103: the knowledge base's one recommended question (prefilled on the query page). */
  const [recommended, setRecommended] = useState<RecommendedQuestion | null>(null);
  useEffect(() => { void listDocuments(groupId).then(list => setDocuments(list.filter(d => d.status !== 'DELETED').map(d => d.name))).catch(() => setDocuments(null)); }, [groupId]);
  const waitingFor = (id: string) => confirmations.filter(c => (c.questionIds ?? []).includes(id));

  useEffect(() => {
    if (!available) return undefined;
    let active = true;
    void getRecommendedTrialQuestion(groupId).then(value => { if (active) setRecommended(value); }).catch(() => undefined);
    void recommendedQuestion(groupId).then(value => { if (active) setCandidate(value); }).catch(() => undefined);
    return () => { active = false; };
  }, [available, groupId]);

  const byId = useMemo(() => new Map([...allReviews, ...pendingRemovals].map(r => [r.question.id, r])), [allReviews, pendingRemovals]);
  const linkedTo = (id: string) => proposals.filter(p => (p.questionIds ?? []).includes(id) && p.status !== 'DISMISSED');
  const stateOf = (id: string) => {
    const review = byId.get(id);
    const waiting = !review && submitted.some(question => question.id === id);
    return questionState(review, waitingFor(id).length, linkedTo(id), waiting && (!assistantIdle || loading),
      waiting && assistantIdle && !loading && !loadError);
  };
  const questions = useMemo(() => {
    const known = allReviews.map(r => ({ id: r.question.id, question: r.question.question }));
    return [...known, ...submitted.filter(s => !byId.has(s.id))];
  }, [allReviews, submitted, byId]);
  const states = new Map(questions.map(q => [q.id, stateOf(q.id)]));
  const known = new Set(questions.map(q => q.id));
  const unlinked = proposals.filter(p => p.status === 'PENDING' && !(p.questionIds ?? []).some(id => known.has(id)));
  const reviewExamples = allReviews.filter(r => r.status === 'CONFIRMED' || r.status === 'REVIEW' || r.published?.available || r.validation?.confirmedAt);
  const reviewSource = (r: QuestionReview) => r.validation?.origin === 'asset-verify' ? '视图验证'
    : r.question.id.startsWith(COACH_PREFIX) ? '建模确认' : '试问保存';
  const library = [
    ...[...pendingRemovals, ...reviewExamples].map(r => ({ id: r.question.id, title: r.question.question, source: reviewSource(r),
      status: r.status === 'REMOVAL_PENDING' ? '待下架' : r.status === 'REVIEW' ? '需复核'
        : r.status !== 'CONFIRMED' ? '修改待核对' : r.published?.current ? '已发布' : '待发布', example: undefined })),
    ...examples.filter(e => e.source === 'liked' || !byId.has(e.file.replace(/\.md$/, '')))
      .map(e => ({ id: e.id, title: e.question, source: e.source === 'liked' ? '问数点赞' : '建模确认',
        status: e.state === 'INVALID' ? '待检查' : e.source === 'liked' ? '已收录' : '草稿示例', example: e })),
  ];
  const pending = questions.filter(q => states.get(q.id)?.group !== 'done')
    .sort((a, b) => queuePriority[states.get(a.id)!.group] - queuePriority[states.get(b.id)!.group]);
  const selectedReview = selection ? byId.get(selection) : undefined;
  const selected = selection ? questions.find(q => q.id === selection) ?? selectedReview?.question : undefined;
  const activeGroup = compact ? 'todo' : listGroup;
  const inlineIntake = !compact && activeGroup === 'todo' && !pending.length && !draftChanges && !loading && !loadError;
  const boardClass = 'qc-board mo-work-grid' + (directoryOpen ? '' : ' qc-directory-collapsed');
  const shown = pending.filter(q => !filter || states.get(q.id)?.group === filter);
  const shownLibrary = library.filter(row => (!libraryStatus || row.status === libraryStatus) && (!librarySource || row.source === librarySource));
  const librarySelection = shownLibrary.find(row => row.id === selection) ?? shownLibrary[0];
  const libraryReview = librarySelection && !librarySelection.example ? byId.get(librarySelection.id)?.question : undefined;
  const current = compact ? selected : activeGroup === 'done' ? libraryReview : shown.find(q => q.id === selection) ?? shown[0];
  const actionsDisabled = !readyToAsk || !assistantIdle || !!busy || loading || !!loadError;
  const displayedSqlTab = (id: string, sql?: string) => {
    const tab = codeView?.id === id ? codeView.tab : 'current';
    return tab === 'previous' && (!previousSql[id] || previousSql[id] === sql) ? 'current' : tab;
  };
  const canConfirmSql = (review: QuestionReview) => {
    const { id, sql } = review.question;
    const group = states.get(id)?.group;
    return !!sql && (group === 'sql' || group === 'review') && edit?.id !== id && displayedSqlTab(id, sql) !== 'previous';
  };
  const removalBlocked = !assistantIdle || !!busy || loading || !!loadError || !!edit;
  useEffect(() => {
    if (!compact || !available || !removalRequest || loading) return;
    onRemovalRequestHandled();
    if (removalBlocked) {
      setError(edit ? '请先保存或取消正在编辑的 SQL，再移出题目。' : '问数优化当前无法移出题目，请稍后重试。');
      return;
    }
    const question = questions.find(q => q.id === removalRequest);
    if (question) openRemoval(question, byId.get(question.id));
    else setError('这道题已移出，或当前无法读取。');
  }, [available, byId, compact, edit, loading, onRemovalRequestHandled, openRemoval, questions, removalBlocked, removalRequest]);
  const directoryList = useRef<HTMLUListElement>(null);
  const [exampleTools, setExampleTools] = useState<HTMLDivElement | null>(null);
  const activeRow = activeGroup === 'done' ? librarySelection?.id : current?.id;
  useEffect(() => {
    if (!directoryOpen || !activeRow) return;
    directoryList.current?.querySelector('[aria-current="true"]')?.scrollIntoView({ block: 'nearest', inline: 'nearest' });
  }, [activeGroup, activeRow, directoryOpen]);
  const directory = (label: string, title: string, count: string, filters: ReactNode, rows: ReactNode) =>
    <MaterialsCatalog label={label} title={title} count={count} filters={filters} open={directoryOpen}
      actions={batchEntry} listRef={directoryList} onToggle={() => setDirectoryOpen(open => !open)}>{rows}</MaterialsCatalog>;
  const run = async (name: string, action: () => Promise<void>, onError: (reason: string) => void = setError) => {
    setBusy(name); setError('');
    try { await action(); } catch (e) { onError(message(e)); } finally { setBusy(''); }
  };
  const isRecommended = (text: string) => !!recommended?.question && recommended.question.trim() === text.trim();
  const openRecommendation = (dialog: RecommendationDialog) => {
    setRecommendationError('');
    setRecommendationDialog(dialog);
    recommendDialog.current?.showModal();
  };
  const saveRecommendation = (text: string | null) => {
    setRecommendationError('');
    return run('recommend', async () => {
      const result = text === null ? await regenerateRecommendedQuestion(groupId) : await editRecommendedQuestion(groupId, text.trim());
      setRecommended(result);
      setCandidate(result.question);
      recommendDialog.current?.close();
    }, setRecommendationError);
  };
  const recommendationBusy = busy === 'recommend';
  const recommendationTarget = recommendationDialog?.mode === 'set' ? recommendationDialog.question : undefined;
  const invalidRecommendation = recommendationTarget !== undefined && (!recommendationTarget || /[\r\n]/.test(recommendationTarget) || recommendationTarget.length > 200);
  const recommendationTitle = recommendationDialog?.mode === 'reset' ? '恢复自动推荐'
    : recommendationDialog?.mode === 'set' ? '设为问数首页推荐' : '问数首页推荐设置';
  const select = (id?: string) => {
    onSelect(id); setCodeView(undefined); setEdit(undefined); setConfirmationError(undefined);
  };
  const canLeaveSql = () => !edit || edit.value === byId.get(edit.id)?.question.sql || window.confirm('有尚未保存的 SQL 修改，是否放弃并切换？');
  const canSwitch = () => canLeaveSql() && (!exampleLeaveGuard.current || exampleLeaveGuard.current());
  const selectGroup = (group: 'todo' | 'done') => {
    if (group === listGroup || !canSwitch()) return;
    setListGroup(group); setFilter(''); setLibraryStatus(''); setLibrarySource(''); setPicked(new Set()); setBatch(false); select();
  };
  const confirmRemoval = (request: NonNullable<typeof removal>) => run('archive', async () => {
    setRemovalNotice('');
    const { removed, failures } = await removeWorkspaceItems(request.targets, async ({ question, review, example }) => {
      if (example) {
        const latest = examples.find(row => row.id === example.id);
        if (!latest?.editable || latest.file !== example.file || latest.question !== example.question || latest.sql !== example.sql) {
          throw new Error('示例已更新，请重新选择。');
        }
        await deleteSqlQaExample(groupId, example.source, example.file);
        return;
      }
      const latest = byId.get(question.id);
      if (latest?.revision !== review?.revision || !!latest?.published?.available !== !!review?.published?.available
          || latest?.status === 'REMOVAL_PENDING') {
        throw new Error('题目状态已更新，请重新选择。');
      }
      if (review) await archiveModelingQuestion(groupId, question.id, review.revision, !!review.published?.available);
    });
    const ids = new Set(removed.map(target => target.question.id));
    const removedExamples = new Set(removed.flatMap(target => target.example ? [target.example.id] : []));
    if (removedExamples.size) setExamples(previous => previous.filter(row => !removedExamples.has(row.id)));
    for (const target of removed) {
      if (target.example) continue;
      onRemove(target.question.id, target.label === '下架示例');
      removeQuestion(target.question.id);
    }
    setPicked(previous => new Set([...previous].filter(id => !ids.has(id))));
    const activeRemoved = removed.find(target => target.question.id === (current?.id ?? librarySelection?.id));
    if (activeRemoved) {
      if (!request.batch && activeRemoved.label === '下架示例') {
        setListGroup('done'); setLibrarySource(''); setLibraryStatus('');
        detailFocus.current = activeRemoved.question.id;
        select(activeRemoved.question.id);
      } else select();
    }
    if (failures.length) {
      setRemovalResult({ removed: removed.length, errors: failures.map(failure => `${failure.target.question.question} · ${failure.message}`) });
    } else {
      removalDialog.current?.close();
      if (request.batch) {
        const staged = removed.filter(target => target.label === '下架示例').length;
        setRemovalNotice(`已处理 ${removed.length} 项问答${staged ? `，其中 ${staged} 项待发布下架` : ''}。`);
      }
    }
    if (request.targets.some(target => target.review || target.example)) {
      window.dispatchEvent(new CustomEvent('modeling:updated', { detail: { groupId } }));
    }
  });
  const editSql = (review: QuestionReview) => run('edit-sql', async () => {
    if (!edit || edit.id !== review.question.id || edit.revision !== review.revision) return;
    try {
      await correctModelingQuestionSql(groupId, edit.id, edit.revision, edit.value.trim());
      setEdit(undefined); setCodeView(undefined);
      if (!compact) {
        setListGroup('todo'); setFilter(''); setBatch(false); setPicked(new Set()); onSelect(edit.id);
        detailFocus.current = edit.id;
      }
    } finally { window.dispatchEvent(new CustomEvent('modeling:updated', { detail: { groupId } })); }
  });

  const enteredQuestions = [...new Set(input.split('\n').map(line => line.trim()).filter(Boolean))];
  const analyse = () => {
    if (!enteredQuestions.length || !readyToAsk || !assistantIdle || busy || !canSwitch()) return;
    if (enteredQuestions.length > MAX_QUESTIONS) { setIntakeError(`一次最多 ${MAX_QUESTIONS} 道题。`); return; }
    if (enteredQuestions.some(line => line.length > 1000)) { setIntakeError('每道题最多 1000 字。'); return; }
    const rows = enteredQuestions.map(line => pending.find(q => q.question === line) ?? newCoachQuestion(line));
    const added = rows.filter(row => !known.has(row.id));
    if (!added.length || reanalyse(added)) {
      intake.current?.close();
      setInput(''); setIntakeError(''); setIntakeOpen(false); setListGroup('todo'); setFilter(''); setBatch(false);
      detailFocus.current = rows[0].id;
      select(rows[0].id);
    }
  };
  const reanalyse = (list: { id: string; question: string }[]) => {
    if (!list.length) return;
    if (list.length > MAX_QUESTIONS) { setError(`一次最多 ${MAX_QUESTIONS} 个问题`); return; }
    const analysis = coachRequest(list.map(q => q.question), list.map(q => q.id), documents ?? []);
    // This flow can create confirmation cards, unlike the assistant's scoped SQL correction turn.
    const sent = onRequest(analysis, 'coach',
      list.length === 1 ? `问数优化：检查 ${list[0].question}` : `问数优化：分析选中的 ${list.length} 个问题`);
    if (sent) {
      trackSubmission(list.filter(q => !byId.has(q.id)));
      setPicked(new Set());
    }
    return sent;
  };
  const loadSuggestions = () => {
    setSuggestionStatus('loading');
    void getQuestionSuggestions(groupId).then(rows => {
      setExtraSuggestions(rows); setSuggestionStatus('loaded');
    }, () => setSuggestionStatus('error'));
  };
  const draftApproved = (scope: AssetProposal[]) => run('draft', async () => {
    const chosen = scope.filter(p => p.status === 'PENDING' && approved.has(p.id));
    if (!chosen.length) return;
    const others = chosen.filter(p => p.kind !== 'view');
    if (others.length && !onRequest(applyRequest(others), undefined, `问数优化：写入 ${others.length} 项已批准的方案`)) return;
    for (const view of chosen.filter(p => p.kind === 'view'))
      if (!onRequest(proposalRequest(view), 'asset', `问数优化：起草查询模板 ${view.name}`)) return;
    for (const p of chosen) await decideAssetProposal(groupId, p.id, 'DRAFTED');
    setApproved(all => { const next = new Set(all); chosen.forEach(p => next.delete(p.id)); return next; });
    load();
  });
  const dismiss = (p: AssetProposal) => run('dismiss', async () => { await decideAssetProposal(groupId, p.id, 'DISMISSED'); load(); });
  const generate = (list: { id: string; question: string }[], label: string) => {
    if (list.length) onRequest(questionRequest(list.map(q => q.question), list.map(q => q.id)), 'question', label);
  };
  const expand = (review: QuestionReview) => run('plan', async () => {
    const id = review.question.id;
    const plan = await planModelingQuestion(groupId, id);
    setPlans(all => ({ ...all, [id]: { revision: review.revision, plan } }));
  });
  const confirmationInFlight = useRef(false);
  const confirm = async (review: QuestionReview) => {
    if (confirmationInFlight.current) return '正在确认这道题，请稍候。';
    confirmationInFlight.current = true;
    const id = review.question.id;
    setBusy('confirm');
    setError('');
    setConfirmationError(undefined);
    try {
      const result = await decideModelingQuestion(groupId, id, true, review.revision);
      if (result.status !== 'CONFIRMED') throw new Error('请查看题目详情中的待处理事项。');
      data.updateReview(result, review.revision);
      if (!compact && listGroup === 'todo') {
        setLibraryStatus(''); setLibrarySource('');
        setListGroup('done'); setPicked(new Set()); setBatch(false);
        detailFocus.current = id;
        select(id);
      }
      return result.published?.current ? '这道题已确认并同步到线上版本。' : '已确认并保存为草稿示例，发布后可供线上问数参考。';
    } catch (e) {
      const reason = message(e);
      setConfirmationError({ id, message: reason });
      return `确认失败。${reason}`;
    } finally {
      window.dispatchEvent(new CustomEvent('modeling:updated', { detail: { groupId } }));
      confirmationInFlight.current = false;
      setBusy('');
    }
  };

  useImperativeHandle(reviewActions, () => ({
    open(id) {
      if (!canSwitch()) return false;
      setListGroup('todo'); setFilter(''); setPicked(new Set()); setBatch(false);
      detailFocus.current = id;
      select(id);
      return true;
    },
    async confirm(id, revision) {
      if (!compact || selection !== id) return '请打开这道题并核对当前 SQL。';
      const review = byId.get(id);
      if (review && states.get(id)?.group === 'done') return review.published?.current
        ? '这道题已确认并同步到线上版本。' : '这道题已确认并保存为草稿示例。';
      if (review && review.revision !== revision) return '这道题的内容已经更新，请重新核对当前 SQL 后再确认。';
      if (actionsDisabled || !review || !canConfirmSql(review)) {
        const reason = actionsDisabled ? '当前无法确认，请等待页面就绪后重试。'
          : edit?.id === id ? '请先保存或取消正在编辑的 SQL。'
          : displayedSqlTab(id, review?.question.sql) === 'previous' ? '请切回当前 SQL，再确认保存。'
          : '本题暂时无法确认，请先完成题目详情中的待处理事项。';
        setError(reason);
        return reason;
      }
      return confirm(review);
    },
  }));

  if (!available) return <section className="sm-pane qc-pane mo-optimization"><div className="da-card sm-empty">
    完成首次建模并发布后，可以在这里列出业务常问的问题，由助手分析缺少的口径并提议，你批准后再起草。</div></section>;

  const proposalCards = (list: AssetProposal[]) => <div className="qc-proposals">
    {list.map(p => {
      const served = (p.questionIds ?? []).filter(id => known.has(id));
      return <div key={p.id} className={`qc-proposal ${p.status !== 'PENDING' ? 'qc-done' : ''}`}>
        <div className="qc-proposal-head">
          {p.status === 'PENDING' ? <input type="checkbox" aria-label={`批准 ${p.name}`} checked={approved.has(p.id)}
            onChange={e => setApproved(all => { const next = new Set(all); if (e.target.checked) next.add(p.id); else next.delete(p.id); return next; })} />
            : <span className="sm-tag sm-tag-plain">已批准</span>}
          <span className="sm-tag">{KIND[p.kind] ?? p.kind}</span><b>{p.name}</b>
          {p.status === 'PENDING' && <button type="button" className="sm-text-link" disabled={!!busy || !assistantIdle} onClick={() => dismiss(p)}>不需要</button>}
        </div>
        <div className="da-small">{p.need}</div>
        <div className="da-small">要点：{p.outline}</div>
        <div className="da-small">原因：{p.reason}</div>
        {p.status === 'DRAFTED' && <div className="sa-actions">
          <span className="da-small">已交给助手，写入结果可在草稿中核对。</span>
          <button type="button" className="sm-text-link" disabled={!!busy || !assistantIdle} onClick={() =>
            onRequest('请先核对当前草稿。方案已经落实就说明位置，仍有缺项时只补充缺少的部分。\n'
              + (p.kind === 'view' ? proposalRequest(p) : applyRequest([p])), p.kind === 'view' ? 'asset' : undefined,
            '核对已批准的方案：' + p.name)}>核对并补全</button>
        </div>}
        {served.length > 1 && <div className="da-small">同时服务 {served.length} 道题：{served.map(id => questions.find(q => q.id === id)?.question ?? id).join('；')}</div>}
      </div>;
    })}
    {list.some(p => p.status === 'PENDING') && <div className="sa-actions">
      <button className="da-btn da-btn-primary da-btn-sm" disabled={!list.some(p => approved.has(p.id)) || !assistantIdle || !!busy}
        onClick={() => void draftApproved(list)}>按批准的方案起草</button>
      <span className="da-small">查询模板起草后到"公共视图"页核查；规则、关系、计算字段进入草稿栏。</span>
    </div>}
  </div>;

  const questionSuggestions = [...new Set([recommended?.question || candidate, ...extraSuggestions]
    .filter((value): value is string => !!value))].slice(0, 3 + extraSuggestions.length);
  const questionForm = (inline: boolean) => {
    const field = <>
      {inline ? <div className="qc-intake-field-head">
        <label htmlFor="qc-intake-questions">业务问题</label>
        <span id="qc-intake-hint" className="da-small">每行一道，最多 {MAX_QUESTIONS} 道</span>
      </div> : <p id="qc-intake-hint" className="da-small">每行一道，最多 {MAX_QUESTIONS} 道。助手按当前草稿分析，生成 SQL 后逐题核对。</p>}
      <textarea id="qc-intake-questions" className="da-input" ref={intakeInput} rows={5} aria-label={inline ? undefined : '业务问题'}
        aria-describedby="qc-intake-hint"
        placeholder="例如：上个月各品类的净销售额是多少？" value={input}
        onChange={event => { setInput(event.target.value); setIntakeError(''); }} />
      {intakeError && <p role="alert" className="mo-error">{intakeError}</p>}
      {!readyToAsk && <p className="da-small">请先完成事实表时间配置，再分析问题。</p>}
      {readyToAsk && !assistantIdle && <p className="da-small" role="status">助手正在处理其他问题，你可以先填写题目。</p>}
    </>;
    const suggestions = <div className="qc-intake-suggestions" role="group" aria-label="问题建议">
      {(inline || questionSuggestions.length > 0) && <div className="qc-intake-suggestion-head">
        <strong>问题建议</strong>
        {questionSuggestions.length > 0 && <span className="da-small">点击填入，可修改后再分析。</span>}
      </div>}
      {questionSuggestions.length > 0 && <div className="qc-chips">{questionSuggestions.map(title => {
        const filled = enteredQuestions.includes(title);
        return <button type="button" key={title} className="qc-chip" disabled={filled} aria-label={filled ? `已填入 ${title}` : `填入 ${title}`}
          onClick={() => { setInput(previous => [previous.trim(), title].filter(Boolean).join('\n')); setIntakeError(''); intakeInput.current?.focus(); }}>
          {inline ? <span className="qc-intake-suggestion-icon"><Icon name={filled ? 'check' : 'plus'} size="sm" /></span> : <Icon name={filled ? 'check' : 'plus'} size="sm" />}
          <span>{title}</span>
        </button>;
      })}</div>}
      <div className="qc-intake-links">
        {suggestionStatus !== 'loaded' && <button type="button" className={inline ? 'da-btn da-btn-sm' : 'sm-text-link'} disabled={suggestionStatus === 'loading'}
          onClick={loadSuggestions}>{suggestionStatus === 'loading' ? '正在生成建议…' : suggestionStatus === 'error' ? '重新获取建议' : '更多问题建议'}</button>}
        {suggestionStatus === 'loaded' && !extraSuggestions.length && <span className="da-small" role="status">暂时没有更多建议。</span>}
      </div>
    </div>;
    const submit = <button type="submit" className="da-btn da-btn-primary" disabled={!enteredQuestions.length || !readyToAsk || !assistantIdle || !!busy}>
      添加并分析{inline && <Icon name="chevron" size="sm" />}
    </button>;
    return <form className={inline ? 'qc-intake-form qc-intake-form-inline' : 'qc-intake-form'} onSubmit={event => { event.preventDefault(); analyse(); }}>
      {inline ? <>
        <div className="qc-intake-entry">
          <div className="qc-intake-body">{field}</div>
          <div className="qc-intake-actions">
            <output className="da-small" aria-label="已填写题数">{enteredQuestions.length} / {MAX_QUESTIONS} 道</output>
            {submit}
          </div>
        </div>
        <aside className="qc-intake-assist" aria-label="录入帮助">
          {suggestions}
          {library.length > 0 && <button type="button" className="qc-intake-library" onClick={() => selectGroup('done')}>
            <Icon name="book" size="sm" />查看问答示例<Icon name="chevron" size="sm" />
          </button>}
        </aside>
      </> : <>
        <div className="mo-dialog-body qc-intake-body">{field}{suggestions}</div>
        <footer className="mo-dialog-footer"><button type="button" className="da-btn" onClick={() => setIntakeOpen(false)}>取消</button>{submit}</footer>
      </>}
    </form>;
  };

  const exampleRemoval = (example: SqlQaExample): Removal => ({
    question: { id: example.id, question: example.question }, example, label: '删除问答示例',
  });
  const eligible: Removal[] = activeGroup === 'done'
    ? shownLibrary.flatMap(row => {
      if (row.example) return row.example.editable ? [exampleRemoval(row.example)] : [];
      const review = byId.get(row.id);
      return review && review.status !== 'REMOVAL_PENDING' ? [{ question: review.question, review, label: removalLabel(review) }] : [];
    })
    : shown.map(question => {
      const review = byId.get(question.id);
      return { question, review, label: removalLabel(review) };
    });
  const eligibleIds = new Set(eligible.map(target => target.question.id));
  const pickedTargets = eligible.filter(target => picked.has(target.question.id));
  const pickedRows = pickedTargets.filter(target => target.review).map(target => target.question);
  const rewritable = pickedRows.filter(q => ['sql', 'review'].includes(states.get(q.id)?.group ?? ''));
  const batchActive = batch && !compact && (activeGroup === 'done' ? library.length > 0 : pending.length > 0);
  const clearPicked = () => setPicked(new Set());
  const batchEntry = (eligible.length > 0 || batchActive) && <WorkspaceBatchToggle active={batchActive}
    onClick={() => { setBatch(!batch); clearPicked(); setRemovalNotice(''); }} />;
  const batchInput = (id: string, title: string) => batchActive && eligibleIds.has(id) && <WorkspaceBatchInput
    label={title} checked={picked.has(id)} disabled={!!busy}
    onChange={checked => setPicked(previous => updateWorkspaceSelection(previous, id, checked))} />;
  const batchRemoveLabel = pickedTargets.every(target => target.label === '下架示例') ? '下架选中的问答'
    : pickedTargets.every(target => target.example) ? '删除选中的问答示例' : '移除选中的问答';
  const openExampleRemoval = (example: SqlQaExample) => {
    setRemovalResult(undefined);
    setRemoval({ targets: [exampleRemoval(example)] });
  };
  const objectActions = (q: CoachQuestion) => {
    const review = byId.get(q.id);
    if (review?.status === 'REMOVAL_PENDING') return null;
    const state = stateOf(q.id);
    const canRegenerate = state.group === 'review' && !!review?.question.sql && edit?.id !== q.id;
    const removeLabel = removalLabel(review);
    const resetRecommendation = state.group === 'done' && isRecommended(q.question) && recommended?.manual;
    return <>
      {onCompare && isScopedQuestion(q.id) && !compact && <button type="button" className="da-btn" onClick={() => {
        if (!canLeaveSql()) return;
        setEdit(undefined); onCompare(q);
      }}><Icon name="chat" size="sm" />边聊边核对</button>}
      {state.group === 'done' && <button type="button" className="da-btn" aria-haspopup="dialog" disabled={actionsDisabled}
        onClick={() => openRecommendation(resetRecommendation ? { mode: 'reset' } : { mode: 'set', question: q.question.trim() })}>
        {resetRecommendation ? '恢复自动推荐' : '设为问数首页推荐'}
      </button>}
      <WorkspaceMenu label="问答更多操作" items={[
        ...(canRegenerate ? [{ label: '按当前口径重新生成 SQL', icon: 'refresh' as const, disabled: actionsDisabled, onClick: () => generate([q], '问数优化：复核 ' + q.question) }] : []),
        { label: removeLabel, icon: 'trash', danger: true, disabled: removalBlocked, onClick: () => openRemoval(q, review) },
      ]} />
    </>;
  };
  const focusDetail = (q: CoachQuestion, node: HTMLDivElement | null) => {
    if (node && detailFocus.current === q.id) { detailFocus.current = undefined; node.focus(); }
  };

  const workbench = (q: CoachQuestion) => {
    const review = byId.get(q.id);
    if (review?.status === 'REMOVAL_PENDING') return <div className="qc-detail mo-work-detail"
      tabIndex={-1} aria-label={q.question} ref={node => focusDetail(q, node)}>
      <div className="qc-next qc-next-removal">
        <div className="qc-next-copy"><p>新的版本发布前，线上问数仍可引用这条示例。</p></div>
        <button type="button" className="da-btn da-btn-primary da-btn-sm" disabled={!assistantIdle || !!busy || loading} onClick={onPublish}>检查并发布</button>
      </div>
    </div>;
    const state = stateOf(q.id);
    const cards = waitingFor(q.id);
    const linked = linkedTo(q.id);
    const priorSql = previousSql[q.id];
    const currentSql = review?.question.sql;
    const tab = displayedSqlTab(q.id, currentSql);
    const plan = review && plans[q.id]?.revision === review.revision ? plans[q.id].plan : undefined;
    const historicalSql = tab === 'previous';
    const editing = edit?.id === q.id ? edit : undefined;
    const editStale = !!editing && editing.revision !== review?.revision;
    const showSqlActions = !!review && canConfirmSql(review);
    const sqlText = tab === 'expanded' ? plan?.error ? undefined : plan?.expanded
      : tab === 'previous' && priorSql ? priorSql : currentSql;
    const canPublishDraft = state.group === 'done' && !review?.published?.current && !editing && !historicalSql;
    const hint = historicalSql ? '回到当前 SQL 后可确认保存。'
      : state.group === 'meaning' ? '补齐以下口径后，助手可以继续生成 SQL。'
      : state.group === 'plan' ? '选择需要补充的方案，写入草稿后再生成 SQL。'
      : review?.status === 'FAILED' ? review.validation?.error || (currentSql ? '请调整 SQL 后重新核对。' : '重新分析以生成可核对的 SQL。')
      : state.group === 'failed' ? '未生成可核对的 SQL，可以重新分析。'
      : state.group === 'sql' && !currentSql ? '核对方案已写入草稿后，生成这道题的 SQL。'
      : state.group === 'analyse' ? state.label === '正在分析' ? '正在按当前草稿检查模型和业务口径。' : '按当前草稿检查模型和业务口径。' : '';
    const confirmError = confirmationError?.id === q.id ? confirmationError.message : '';
    const showSql = () => {
      sqlHeading.current?.focus({ preventScroll: true });
      sqlHeading.current?.scrollIntoView({ block: 'start' });
    };
    const sqlControls = <div className="qc-sql-head">
      <h4 ref={sqlHeading} tabIndex={-1}>{state.group === 'done' ? '已确认的 SQL' : '核对 SQL'}</h4>
      <div className="qc-sql-tabs" role="group" aria-label="SQL 版本">
        <button type="button" aria-pressed={tab === 'current'} onClick={() => setCodeView({ id: q.id, tab: 'current' })}>当前 SQL</button>
        {priorSql && priorSql !== currentSql && <button type="button" aria-pressed={tab === 'previous'} onClick={() => setCodeView({ id: q.id, tab: 'previous' })}>上一次 SQL</button>}
        <button type="button" aria-pressed={tab === 'expanded'} disabled={actionsDisabled} onClick={() => {
          setCodeView({ id: q.id, tab: 'expanded' });
          if (review && (!plan || plan.error)) void expand(review);
        }}>展开后的 SQL</button>
      </div>
    </div>;
    const editAction = review && currentSql && tab === 'current' && <button type="button" disabled={actionsDisabled}
      onClick={() => setEdit({ id: q.id, revision: review.revision, value: currentSql })}><Icon name="edit" size="sm" />编辑 SQL</button>;
    const detail = <>
      {(hint || !!review?.reviewReasons?.length) && <div className={'qc-next qc-next-' + state.group}>
        <div className="qc-next-copy">
          {hint && <p>{hint}</p>}
          {!!review?.reviewReasons?.length && <ul className="qc-review-reasons">
            {review.reviewReasons.map(reason => <li key={reason}>{reason}</li>)}
          </ul>}
        </div>
        <div className="mo-work-actions">
        {!editing && (historicalSql ? <button type="button" className="da-btn da-btn-sm" onClick={() => { setCodeView({ id: q.id, tab: 'current' }); showSql(); }}>查看当前 SQL</button>
           : state.group === 'failed' || state.group === 'analyse' && state.label !== '正在分析'
             ? <button type="button" className="da-btn da-btn-primary da-btn-sm" disabled={actionsDisabled} onClick={() => reanalyse([q])}>{state.group === 'failed' ? '重试分析' : '分析这道题'}</button>
            : state.group === 'sql' && !currentSql
              ? <button type="button" className="da-btn da-btn-primary da-btn-sm" disabled={actionsDisabled} onClick={() => generate([q], '问数优化：生成 ' + q.question + ' 的 SQL')}>生成 SQL</button> : null)}</div>
      </div>}
      {cards.length > 0 && <div className="qc-waiting">
        <ConfirmationCards key={cards.map(c => c.id).join(',')} questions={cards} onSubmit={answers => onAnswer?.(answers)} />
        <div className="sa-actions">
          {onOpenDocuments && <button type="button" className="da-btn da-btn-sm" onClick={onOpenDocuments}>补充业务资料</button>}
          <button type="button" className="da-btn da-btn-sm" disabled={actionsDisabled} onClick={() => reanalyse([q])}>重新分析</button>
        </div>
      </div>}
      {linked.some(p => p.status === 'PENDING') && proposalCards(linked.filter(p => p.status === 'PENDING'))}
      {linked.some(p => p.status === 'DRAFTED') && <details className="qc-detail-fold">
        <summary>查看已批准方案</summary>{proposalCards(linked.filter(p => p.status === 'DRAFTED'))}
      </details>}
       {state.group !== 'meaning' && state.group !== 'plan' && (currentSql || review?.question.definition || !!review?.analysis?.length) && <div className={sqlText && !editing ? 'qc-answer-layout qc-fit-sql' : 'qc-answer-layout'}><div className={currentSql && (review?.question.definition || review?.analysis?.length) ? 'qc-answer-grid qc-with-definition' : 'qc-answer-grid'}>
       {(review?.question.definition || !!review?.analysis?.length) && <div className="qc-answer-definition">
       {review?.question.definition && <section className="qc-definition-box">
        <h4>业务口径</h4>
        <dl>{review.question.definition.split('\n').filter(line => line.trim()).map((line, index) => {
          const field = /^(指标|粒度|时间范围|过滤|假设)[：:]\s*(.*)$/.exec(line.trim());
          const previous = field && previousDefinitions[q.id]?.split('\n')
            .map(value => /^(指标|粒度|时间范围|过滤|假设)[：:]\s*(.*)$/.exec(value))
            .find(value => value?.[1] === field[1])?.[2];
          return <div key={index}>{field && <dt>{field[1]}</dt>}<dd>
            {previous && previous !== field?.[2] && <del className="mo-definition-previous">{previous}</del>}
            {previous && previous !== field?.[2] ? <strong>{field?.[2]}</strong> : field?.[2] ?? line}
          </dd></div>;
        })}</dl>
      </section>}
      {!!review?.analysis?.length && <details className="qc-detail-fold">
        <summary>查看核对依据</summary>
        <ul className="qc-findings">{review.analysis.map((finding, index) => <li key={index} className={finding.ok ? 'qc-ok' : 'qc-gap'}><span aria-hidden="true">{finding.ok ? '✓' : '!'}</span>{finding.text}</li>)}</ul>
      </details>}
       </div>}
      {editing && review ? <section className="qc-sql-review mo-sql-edit">
        <h4>修改本题 SQL</h4>
        <SqlEditor value={editing.value} autoFocus rows={9} maxLength={16000} disabled={!!busy || editStale}
          ariaLabel="修改本题 SQL" onChange={value => setEdit({ ...editing, value })} />
        <div className="sa-actions">{editStale && <span className="da-small" role="status">当前 SQL 已更新，请重新载入后编辑。</span>}
          <button type="button" className="da-btn" disabled={!!busy} onClick={() => setEdit(undefined)}>取消</button>
          {editStale ? <button type="button" className="da-btn" onClick={() => setEdit({ id: q.id, revision: review.revision, value: currentSql ?? '' })}>载入当前 SQL</button>
            : <button type="button" className="da-btn da-btn-primary" disabled={actionsDisabled || !editing.value.trim() || editing.value.trim() === currentSql?.trim()} onClick={() => void editSql(review)}>保存修改</button>}
        </div>
      </section> : currentSql && <section className="qc-sql-review">
        {!sqlText ? <>
          {sqlControls}
          {plan?.error ? <p className="sm-issue" role="status">{plan.error}</p>
            : <p className="da-small" role="status">{busy === 'plan' ? '正在展开 SQL…' : '点「展开后的 SQL」查看规划结果。'}</p>}
        </> : <ReadableCode
          text={sqlText} language="sql" title={tab === 'expanded' ? '展开后的 SQL' : tab === 'previous' ? '上一次 SQL' : '当前 SQL'}
          fitHeight header={sqlControls} actions={editAction} />}
        <div className="qc-sql-foot"><span className="da-small">{tab === 'expanded' && plan?.expanded && !plan.error ? 'SQL 规划检查通过 · 未执行查询' : 'SQL 预览 · 未执行查询'}</span>
           <button type="button" className="sm-text-link" onClick={() => setContextQuestion(q.question)}>核对规则与版本</button>
        </div>
      </section>}
       </div></div>}
    </>;
    return <div className="qc-question-workbench">
      <div key={q.id} className={'qc-detail' + (compact ? '' : ' mo-work-detail')} aria-label={q.question}
        tabIndex={-1} ref={node => focusDetail(q, node)}>{detail}</div>
      {showSqlActions && <div className="qc-review-actions" role="group" aria-label="SQL 核对操作">
        <div className="qc-review-actions-copy">
          {confirmError ? <p role="alert">确认失败。{confirmError}</p> : <span className="da-small">核对指标、时间和过滤条件后，保存为草稿示例。</span>}
        </div>
        <div className="mo-work-actions">
           <button type="button" className="da-btn da-btn-primary" disabled={actionsDisabled} onClick={() => void confirm(review)}>
            <Icon name="check" size="sm" />
            {busy === 'confirm' ? '正在确认…' : state.group === 'review' ? '确认现有 SQL 正确' : '确认并保存'}
          </button>
        </div>
      </div>}
      {canPublishDraft && <div className="qc-review-actions" role="group" aria-label="问答发布操作">
        <span className="da-small qc-review-actions-copy">已保存到草稿，发布后可供线上问数参考。</span>
        <div className="mo-work-actions">
          <button type="button" className="da-btn da-btn-primary" disabled={actionsDisabled} onClick={onPublish}>检查并发布</button>
        </div>
      </div>}
    </div>;
  };

  return <section className={'sm-pane qc-pane mo-optimization qc-workspace' + (compact ? ' qc-compact' : '')} aria-label="问数优化">

    {compact && current && <WorkspaceObjectTools context={current.question} compact>
      <span className={`qc-status-pill qc-status-${byId.get(current.id)?.status === 'REMOVAL_PENDING' ? 'removal' : stateOf(current.id).group}`}>
        {byId.get(current.id)?.status === 'REMOVAL_PENDING' ? '待下架' : stateOf(current.id).label}
      </span>{objectActions(current)}
    </WorkspaceObjectTools>}
    {!compact && <WorkspaceToolbar label="问数优化范围" active={activeGroup} onSelect={selectGroup} tabs={[
      { id: 'todo', label: '待处理', icon: 'list', count: pending.length },
      { id: 'done', label: '问答示例库', icon: 'book', count: library.length },
    ]} context={!directoryOpen && !batchActive ? current?.question ?? (activeGroup === 'done' ? librarySelection?.title : undefined) : undefined}
      objectActions={batchActive ? <WorkspaceBatchTools count={pickedTargets.length} label={batchRemoveLabel} disabled={removalBlocked}
        onClear={clearPicked} onRemove={() => {
          if (!canSwitch()) return;
          setRemovalResult(undefined);
          setRemoval({ targets: pickedTargets, batch: true });
        }}>
        {activeGroup === 'todo' && <>{pickedRows.length > MAX_QUESTIONS && <span className="da-small">每次最多分析 {MAX_QUESTIONS} 道</span>}
        <button type="button" className="da-btn" disabled={!pickedRows.length || pickedRows.length > MAX_QUESTIONS || actionsDisabled}
          onClick={() => reanalyse(pickedRows)}>重新分析</button>
        <button type="button" className="da-btn" disabled={!rewritable.length || rewritable.length > MAX_QUESTIONS || actionsDisabled}
          onClick={() => generate(rewritable, '问数优化：按新口径重新生成所选题目的 SQL')}>重写 SQL</button>
        </>}
      </WorkspaceBatchTools> : current ? objectActions(current) : activeGroup === 'done' && librarySelection?.example?.editable
        ? <div ref={setExampleTools} className="mo-work-object-slot" /> : null}>
      {!inlineIntake && <button type="button" className="da-btn da-btn-primary"
        disabled={!readyToAsk} onClick={() => { setIntakeError(''); setIntakeOpen(true); }}><Icon name="plus" size="sm" />添加业务问题</button>}
      <WorkspaceMenu label="问数优化更多" items={[
        { label: '使用指南', icon: 'info', onClick: () => helpDialog.current?.showModal() },
        ...(activeGroup === 'done' ? [{ label: '问数首页推荐设置', icon: 'settings' as const,
          onClick: () => openRecommendation({ mode: 'settings', question: recommended?.question ?? candidate ?? '' }) }] : []),
      ]} />
    </WorkspaceToolbar>}
    <dialog ref={helpDialog} className="mo-form-dialog" aria-label="问数优化使用指南">
      <header className="mo-dialog-head"><strong>使用指南</strong><button type="button" className="da-btn" onClick={() => helpDialog.current?.close()}>关闭</button></header>
      <ModelingMethodFlow kind="coach" compact />
    </dialog>
    {(error || loadError) && <div role="alert" className="mo-notice mo-error"><span>{error || loadError}</span>
      {loadError && <button type="button" className="da-btn da-btn-sm" onClick={load}>重试</button>}
    </div>}
    {removalNotice && <p role="status" className="mo-notice">{removalNotice}</p>}
    {contextQuestion !== null && <SemanticContextPanel groupId={groupId} question={contextQuestion} preview={preview} reviews={allReviews} onClose={() => setContextQuestion(null)} />}
    <dialog ref={recommendDialog} className="mo-form-dialog qc-recommend-dialog" aria-labelledby="qc-recommend-title"
      onCancel={event => { if (recommendationBusy) event.preventDefault(); }} onClose={() => setRecommendationDialog(undefined)}>
      <header className="mo-dialog-head"><strong id="qc-recommend-title">{recommendationTitle}</strong>
        <button type="button" className="da-btn da-btn-sm" aria-label="关闭" disabled={recommendationBusy}
          onClick={() => recommendDialog.current?.close()}><Icon name="close" size="sm" /></button></header>
      <div className="mo-dialog-body">
        <p className="da-small">用户在问数页新建对话并选择此知识库时，空输入框会预填这道题。用户可以修改后再发送。</p>
        {recommendationDialog?.mode === 'settings' ? <input className="da-input" aria-label="问数首页推荐问题"
          value={recommendationDialog.question} maxLength={200} disabled={recommendationBusy}
          onChange={event => { setRecommendationError(''); setRecommendationDialog({ mode: 'settings', question: event.target.value }); }} />
          : recommendationDialog && <dl className="qc-recommend-compare">
            <div><dt>当前推荐</dt><dd>{recommended?.question ?? candidate ?? '暂无推荐'}</dd></div>
            <div><dt>{recommendationDialog.mode === 'reset' ? '恢复后' : '新推荐'}</dt>
              <dd>{recommendationDialog.mode === 'reset' ? '由系统为此知识库生成推荐问题' : recommendationDialog.question}</dd></div>
          </dl>}
        {invalidRecommendation && <p className="mo-error" role="status">推荐问题须为一行，最多 200 字。</p>}
        {recommendationError && <p role="alert" className="mo-error">{recommendationError}</p>}
      </div>
      <footer className="mo-dialog-footer">
        {recommendationDialog?.mode === 'settings' ? <>
          <button type="button" className="da-btn" disabled={recommendationBusy} onClick={() => void saveRecommendation(null)}>恢复自动推荐</button>
          <button type="button" className="da-btn da-btn-primary"
            disabled={recommendationBusy || !recommendationDialog.question.trim() || recommendationDialog.question.trim() === (recommended?.question ?? candidate)}
            onClick={() => void saveRecommendation(recommendationDialog.question)}>{recommendationBusy ? '保存中…' : '保存推荐问题'}</button>
        </> : recommendationDialog && <>
          <button type="button" className="da-btn" disabled={recommendationBusy} onClick={() => recommendDialog.current?.close()}>取消</button>
          <button type="button" className="da-btn da-btn-primary" disabled={recommendationBusy || invalidRecommendation}
            onClick={() => void saveRecommendation(recommendationDialog.mode === 'reset' ? null : recommendationDialog.question)}>
            {recommendationBusy ? '保存中…' : recommendationDialog.mode === 'reset' ? '确认恢复' : '确认设置'}
          </button>
        </>}
      </footer>
    </dialog>
    <dialog ref={removalDialog} className="mo-form-dialog qc-recommend-dialog" aria-labelledby="qc-removal-title"
      onCancel={event => { if (busy === 'archive') event.preventDefault(); }} onClose={() => setRemoval(undefined)}>
      <header className="mo-dialog-head"><strong id="qc-removal-title">{removal?.batch ? '批量移除问答' : removal?.targets[0]?.label ?? '移出题目'}</strong>
        <button type="button" className="da-btn da-btn-sm" aria-label="关闭" disabled={busy === 'archive'}
          onClick={() => removalDialog.current?.close()}><Icon name="close" size="sm" /></button></header>
      {removal && <>
        <div className="mo-dialog-body">
          {removalResult ? <div role="alert">
            <p>已处理 {removalResult.removed} 项，{removalResult.errors.length} 项未完成。返回列表后可重新检查。</p>
            <ul>{removalResult.errors.map((reason, index) => <li className="mo-error" key={index}>{reason}</li>)}</ul>
          </div> : <ul className="mo-removal-targets">{removal.targets.map(target => <li key={target.question.id}>
            <strong>{target.question.question}</strong>
            <p className="da-small">{target.label === '下架示例'
              ? '待发布下架。新版本发布后，线上问数停止引用这条示例。'
              : target.example ? '删除问答示例，确认后生效。'
                : target.label === '移出示例库' ? '移出草稿示例，保留历史。'
                  : target.review ? '移出待处理队列，保留历史。' : '移出本次待处理列表。'}</p>
            {isRecommended(target.question.question) && <p className="da-small qc-removal-recommend">这是问数首页推荐。可在示例库的「问数首页推荐设置」里更换预填题。</p>}
          </li>)}</ul>}
        </div>
        <footer className="mo-dialog-footer">
          <button type="button" className="da-btn" disabled={busy === 'archive'} onClick={() => removalDialog.current?.close()}>{removalResult ? '返回列表' : '取消'}</button>
          {!removalResult && <button type="button" className="da-btn da-btn-danger" disabled={removalBlocked}
            onClick={() => void confirmRemoval(removal)}>{busy === 'archive' ? '处理中…' : removal.batch ? '确认批量移除'
              : removal.targets[0]?.label === '下架示例' ? '确认下架' : removal.targets[0]?.example ? '确认删除' : '确认移出'}</button>}
        </footer>
      </>}
    </dialog>
    {!inlineIntake && <dialog ref={intake} className="mo-form-dialog" aria-labelledby="mo-coach-intake-title" onClose={() => setIntakeOpen(false)}>
      <header className="mo-dialog-head"><strong id="mo-coach-intake-title">添加业务问题</strong>
        <button type="button" className="da-btn da-btn-sm" onClick={() => setIntakeOpen(false)}>关闭</button></header>
      {intakeOpen && questionForm(false)}
    </dialog>}
    <div className="qc-list-view">
    {compact ? current ? workbench(current) : <div className="qc-detail"><p className="da-small" role="status">{loading || !assistantIdle ? '正在读取题目…' : '这道题尚未保存。'}</p></div>
      : loading && !questions.length ? <p className="da-small qc-loading" role="status">正在读取业务问题…</p>

      : activeGroup === 'done' ? <>
        {exampleError && <p role="alert" className="mo-notice mo-error">部分示例未能读取。{exampleError}</p>}
        {library.length === 0 ? <WorkspaceEmpty icon="book" title="还没有问答示例" description="核对并保存一道业务问题后，可以在这里查看。">
          <button type="button" className="da-btn" onClick={() => selectGroup('todo')}>返回待处理</button>
        </WorkspaceEmpty> : <div className={boardClass}>
          {directory('示例目录', '示例目录', `${shownLibrary.length} 条`, <>
              <select className="da-input" aria-label="筛选发布状态" value={libraryStatus} onChange={e => { if (canSwitch()) { setLibraryStatus(e.target.value); clearPicked(); select(); } }}><option value="">全部发布状态</option>{[...new Set(library.map(row => row.status))].map(status => <option key={status}>{status}</option>)}</select>
              <select className="da-input" aria-label="筛选示例来源" value={librarySource} onChange={e => { if (canSwitch()) { setLibrarySource(e.target.value); clearPicked(); select(); } }}><option value="">全部来源</option>{[...new Set(library.map(row => row.source))].map(source => <option key={source}>{source}</option>)}</select>
            </>, shownLibrary.map(row => <li key={row.id} className={row.id === librarySelection?.id ? 'qc-queue-selected' : ''}>
              <button type="button" aria-current={row.id === librarySelection?.id ? true : undefined} onClick={() => { if (row.id !== librarySelection?.id && canSwitch()) select(row.id); }}><strong>{row.title}</strong>
                <span className="mo-item-meta"><span className={'qc-status-pill qc-status-' + (row.status === '已发布' ? 'done' : row.status === '待下架' ? 'removal' : row.status === '需复核' ? 'review' : 'sql')}>{row.status}</span>{row.source}
                  {isRecommended(row.title) && <span className="sm-tag qc-recommend-tag">问数首页推荐</span>}</span>
              </button>{batchInput(row.id, row.title)}</li>))}
          {current ? workbench(current) : <div className="qc-detail mo-work-detail">{librarySelection?.example
            ? <SqlQaExampleDetail key={librarySelection.id} groupId={groupId} entry={librarySelection.example} leaveGuard={exampleLeaveGuard}
                onContext={setContextQuestion} toolbarTarget={batchActive ? null : exampleTools}
                onRemove={removalBlocked ? undefined : openExampleRemoval}
                onChanged={() => window.dispatchEvent(new CustomEvent('modeling:updated', { detail: { groupId } }))} />
            : <WorkspaceEmpty icon="search" title="没有匹配的示例"><button type="button" className="da-btn" onClick={() => { setLibraryStatus(''); setLibrarySource(''); }}>清除筛选</button></WorkspaceEmpty>}</div>}
        </div>}
      </> : !pending.length ? !loadError && (draftChanges ? <WorkspaceEmpty icon="check" title="当前没有待处理问题"
        description="草稿有待发布的改动。发布后，线上问数可以使用最新版本。">
        <button type="button" className="da-btn da-btn-primary" disabled={!assistantIdle} onClick={onPublish}>检查并发布</button>
        {library.length > 0 && <button type="button" className="da-btn" onClick={() => selectGroup('done')}>查看问答示例</button>}
      </WorkspaceEmpty> : inlineIntake && <div className="qc-intake-stage">
        <div className="qc-intake-start">
          <header className="qc-intake-heading">
            <h3>添加业务问题</h3>
            <p>用常见问题验证当前模型草稿。生成 SQL 后，在「待处理」逐题核对。</p>
          </header>
          {questionForm(true)}
        </div>
      </div>)
      : <div className={boardClass}>
        {directory('业务问题', '问题目录', `${shown.length} 道`,
          <select aria-label="按状态筛选" className="da-input" value={filter} onChange={e => {
              if (!canSwitch()) return;
              setFilter(e.target.value as Group | ''); setPicked(new Set()); select();
            }}><option value="">全部状态</option>{GROUPS.filter(g => g.key !== 'done').map(g => <option key={g.key} value={g.key}>{g.label}</option>)}</select>,
          shown.map(q => {
            const state = states.get(q.id)!;
            return <li key={q.id} className={q.id === current?.id ? 'qc-queue-selected' : ''}>
              <button type="button" aria-current={q.id === current?.id ? true : undefined} onClick={() => { if (q.id !== current?.id && canSwitch()) select(q.id); }}>
                <strong>{q.question}</strong><span className={'qc-status-pill qc-status-' + state.group}>{state.label}</span>
              </button>{batchInput(q.id, q.question)}
            </li>;
          }))}
        {current ? workbench(current) : <div className="qc-detail mo-work-detail"><WorkspaceEmpty icon="search" title="没有匹配的问题">
          <button type="button" className="da-btn" onClick={() => setFilter('')}>清除筛选</button>
        </WorkspaceEmpty></div>}
      </div>}
    {!compact && unlinked.length > 0 && <details className="qc-detail-fold"><summary>查看未关联题目的方案</summary>{proposalCards(unlinked)}</details>}
    </div>
  </section>;
}
