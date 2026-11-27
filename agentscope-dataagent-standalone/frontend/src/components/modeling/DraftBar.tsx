import { tourActive, tourLocksDraftDrawer, tourSignal, useTour } from '../../state/guidedTour';
import { useCallback, useEffect, useLayoutEffect, useRef, useState } from 'react';
import { discardDraft, getDraftItems, revertDraftFile } from '../../api/draftReview';
import type { Confirmation, ConfirmationAnswer, DraftItem, DraftReview } from '../../api/draftReview';
import { publishMdl, validateMdl } from '../../api/semanticModeling';
import type { MdlIssue, MdlPublishResult } from '../../api/semanticModeling';
import type { AssetReviewTarget, KnowledgeReleaseDecision, ReviewSection } from './releaseDecision';
import type { KnowledgeReleaseData } from './useKnowledgeReleaseData';
import DraftItemsList from './DraftItemsList';
import { draftChangeGroups } from './draftChangeGroups';
import ConfirmationCards from './ConfirmationCards';
import IncompleteViewDialog from './IncompleteViewDialog';
import Icon from '../Icon';

const message = (e: unknown) => e instanceof Error ? e.message : String(e);

/**
 * spec092 §7 draft bar: everything not yet published, one publish for one finished round. The
 * draft is checked automatically before publishing; open questions only warn, they never block.
 */
/** What the page needs to guide the next step (spec092 §7). */
export type DraftSummary = { changes: number; examples: number; publishing: boolean; publishedVersion: number | null; error: string | null; blocked?: string | null };
export type CanvasDraft = { items: DraftItem[]; review: DraftReview | null };
export type DraftUndoRequest = { path: string | null };

type CenterTab = 'changes' | 'tasks';

export default function DraftBar({ groupId, release, decision, assistantActive, pendingConfirmations, onLocate, confirmations = [], onAnswer,
  reviewQuestions, questionTitles, onReviewQuestion, onReviewAsset, onInspectChange, onLocateChange,
  isUnseen, onSeen, publishSignal = 0, reviewSignal = 0, taskSignal = 0, onSummary, onCanvasDraft, undoRequest, beforePublish, terms, compact = false, editingBlocked = false, checksPending = false,
  importing = false, stateError, reviewRequested, onDismissReview, onOpenEngineering, onPrepareData, onPrepareModel, onCalibers, onRetryState }: {
  groupId: string;
  release: KnowledgeReleaseData;
  decision: KnowledgeReleaseDecision;
  assistantActive: boolean;
  pendingConfirmations: number;
  /** Scrolls to "需要你确定的部分". */
  onLocate: () => void;
  confirmations?: Confirmation[];
  onAnswer?: (answers: ConfirmationAnswer[]) => void;
  reviewQuestions: { id: string; question: string; reasons?: string[] }[];
  questionTitles?: ReadonlyMap<string, string>;
  onReviewQuestion: (id: string) => boolean;
  onReviewAsset?: (target: AssetReviewTarget) => boolean;
  onInspectChange?: (path: string) => boolean;
  onLocateChange?: (item: DraftItem) => boolean;
  isUnseen?: (item: DraftItem) => boolean;
  onSeen?: (items: DraftItem[]) => void;
  /** Incremented by the page's "下一步" card to publish through this bar's checks. */
  publishSignal?: number;
  reviewSignal?: number;
  taskSignal?: number;
  onSummary?: (summary: DraftSummary) => void;
  onCanvasDraft?: (data: CanvasDraft) => void;
  undoRequest?: DraftUndoRequest | null;
  beforePublish?: () => boolean;
  /** A quieter presentation for the terms page; all publish checks remain shared. */
  terms?: { pending: number; editing: boolean };
  compact?: boolean;
  editingBlocked?: boolean;
  checksPending?: boolean;
  importing?: boolean;
  stateError?: string | null;
  reviewRequested?: ReviewSection | null;
  onDismissReview?: () => void;
  onOpenEngineering?: () => boolean;
  onPrepareData?: () => boolean;
  onPrepareModel?: () => boolean;
  /** spec153: opens the caliber page's publish check. */
  onCalibers?: () => boolean;
  onRetryState?: () => void;
}) {
  const [items, setItems] = useState<DraftItem[]>([]);
  const { workflow, review, assetTasks, incompleteViews, reload, recordPublicationFailure } = release;
  const [open, setOpen] = useState(!!reviewRequested);
  const [tab, setTab] = useState<CenterTab>('changes');
  const [busy, setBusy] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [issues, setIssues] = useState<MdlIssue[]>([]);
  const [restorePath, setRestorePath] = useState<string | null>(null);
  const [repairView, setRepairView] = useState<string | null>(null);
  const [repairNotice, setRepairNotice] = useState('');
  const [result, setResult] = useState<MdlPublishResult | null>(null);
  const [publishFromPage, setPublishFromPage] = useState(false);
  const [impactOpen, setImpactOpen] = useState(false);
  const [confirming, setConfirming] = useState<'pending' | 'others' | 'discard' | null>(null);
  const active = useRef(groupId); active.current = groupId;
  const tour = useTour();
  const drawerLocked = !terms && tourLocksDraftDrawer(tour, groupId);
  // Hide synchronously on the step change, before the spotlight measures the covered control.
  const drawerOpen = open && !drawerLocked;
  useEffect(() => {
    if (!drawerLocked) return;
    setOpen(false);
    setConfirming(current => current === 'discard' ? null : current);
  }, [drawerLocked]);
  const drawerHint = drawerLocked ? '请先完成当前引导，退出引导后可查看全部草稿' : undefined;
  const lastReviewSignal = useRef(reviewSignal);
  useEffect(() => {
    if (lastReviewSignal.current === reviewSignal) return;
    lastReviewSignal.current = reviewSignal;
    if (!drawerLocked) { setTab('changes'); setOpen(true); }
  }, [reviewSignal, drawerLocked]);
  const lastTaskSignal = useRef(taskSignal);
  useEffect(() => {
    if (lastTaskSignal.current === taskSignal) return;
    lastTaskSignal.current = taskSignal;
    if (!drawerLocked) { setTab('tasks'); setOpen(true); }
  }, [taskSignal, drawerLocked]);
  const lastUndoRequest = useRef(undoRequest);
  useEffect(() => {
    if (lastUndoRequest.current === undoRequest) return;
    lastUndoRequest.current = undoRequest;
    if (!undoRequest || drawerLocked) return;
    setTab('changes');
    setRestorePath(undoRequest.path);
    setConfirming(undoRequest.path ? null : 'discard');
    setOpen(true);
  }, [undoRequest, drawerLocked]);

  const loadSequence = useRef(0);
  const load = useCallback(async () => {
    const sequence = ++loadSequence.current;
    try {
      const list = await getDraftItems(groupId);
      if (active.current !== groupId || sequence !== loadSequence.current) return;
      setItems(list); setLoadError(null);
    } catch (e) { if (active.current === groupId && sequence === loadSequence.current) setLoadError(message(e)); }
  }, [groupId]);
  useEffect(() => {
    setItems([]); setOpen(false); setIssues([]); setResult(null); setConfirming(null); setError(null); setPublishFromPage(false);
    setRepairView(null); setRepairNotice(''); setRestorePath(null); setLoadError(null);
    setBusy(null); setTab('changes');
    void load();
    const update = (event: Event) => {
      if ((event as CustomEvent<{ groupId: string }>).detail?.groupId === groupId) void load();
    };
    window.addEventListener('modeling:updated', update);
    return () => window.removeEventListener('modeling:updated', update);
  }, [groupId, load]);

  const fileChanges = review?.changes ?? [];
  const restoreFile = restorePath ? fileChanges.find(file => file.path === restorePath && file.revertible) : undefined;
  const { changes, removals, republish, blocker, taskCount } = decision;
  const removalPaths = new Set(removals.map(id => `knowledge/sql/${id}.md`));
  const changesByOthers = changes.filter(change => change.authors.some(author => author !== review?.currentUser)).length;
  const examples = changes.filter(c => c.category === 'EXAMPLE').length;
  const reviewOnly = changes.length === 0 && reviewQuestions.length > 0;
  const running = assistantActive || !!review?.assistantRunning;
  useEffect(() => { onCanvasDraft?.({ items, review }); }, [onCanvasDraft, items, review]);
  const blockerMessage = blocker?.kind === 'asset' ? `视图「${blocker.target.name}」${blocker.target.step === 'REVIEW_PENDING' ? '待核查用途与口径' : '待通过问题验证'}`
    : blocker?.kind === 'incomplete' ? `视图「${blocker.name}」的工程文件不完整` : blocker?.message;
  const waitingReason = stateError ? '读取发布状态失败，请重试'
    : checksPending || !review || !workflow ? '正在读取草稿和待办'
      : editingBlocked ? '请先保存或取消正在编辑的术语'
        : importing ? '数据正在导入，完成后才能发布'
          : running ? '助手正在修改草稿，完成后才能发布' : null;
  const blocked = waitingReason ?? blockerMessage ??
    (changes.length === 0 && !republish ? (reviewOnly ? '请先复核问答，再发布为问数示例' : '当前没有待发布的修改') : null);

  const ready = !blocked && pendingConfirmations === 0;
  const summary = useRef(onSummary); summary.current = onSummary;
  useEffect(() => {
    summary.current?.({ changes: changes.length, examples, publishing: busy === 'publish',
      publishedVersion: result?.ok ? result.mdlVersion : null, error: error ?? loadError ?? (issues.length ? issues[0].message : null), blocked });
  }, [changes.length, examples, busy, result, error, loadError, issues, blocked]);
  const lastSignal = useRef(publishSignal);
  useEffect(() => {
    if (publishSignal === lastSignal.current) return;
    lastSignal.current = publishSignal;
    if (!blocked && busy === null) {
      setTab('changes');
      setPublishFromPage(true);
      void publish();
    } else if (blocker && busy === null) {
      setTab(blocker.kind === 'asset' || blocker.kind === 'incomplete' ? 'tasks' : 'changes');
      setOpen(true);
    }
  }, [publishSignal]); // eslint-disable-line react-hooks/exhaustive-deps

  const publish = async (skipPending = false, includeOthers = false) => {
    if (blocked || busy !== null || beforePublish?.() === false) return;
    if (!skipPending && pendingConfirmations > 0) { setConfirming('pending'); return; }
    if (!includeOthers && changesByOthers > 0) { setConfirming('others'); return; }
    setConfirming(null); setBusy('publish'); setError(null); setResult(null);
    try {
      // Publishing runs the full check itself and reports its issues; checking first only doubles it.
      setIssues([]);
      setRepairNotice('');
      const published = await publishMdl(groupId);
      if (active.current !== groupId) return;
      setResult(published);
      recordPublicationFailure(published.ok ? undefined : published.issues.find(issue => issue.severity === 'error'));
      if (published.ok) {
        const reviewPending = published.issues.some(issue => issue.source === 'questions-review');
        const showReport = reviewPending || published.issues.some(issue => issue.source === 'questions');
        if (reviewPending) setTab('tasks');
        setOpen(showReport);
        if (!showReport) onDismissReview?.();
        tourSignal('published', { groupId });
        // Keep the completion card in progress until the displayed draft count is refreshed.
        await Promise.all([load(), reload()]);
        if (active.current !== groupId) return;
      } else if (published.issues.some(issue => issue.incompleteView || issue.assetReview)) {
        setOpen(true);
      }
      setIssues(published.issues.filter(i => i.severity === 'error'));
      window.dispatchEvent(new CustomEvent('modeling:updated', { detail: { groupId } }));
    } catch (e) { if (active.current === groupId) setError(message(e)); }
    finally { if (active.current === groupId) setBusy(null); }
  };
  const checkAgain = async () => {
    if (waitingReason || busy || beforePublish?.() === false) return;
    setBusy('check'); setError(null); setIssues([]);
    try {
      const result = await validateMdl(groupId);
      if (active.current !== groupId) return;
      if (result.ok) recordPublicationFailure();
      setIssues(result.issues.filter(issue => issue.severity === 'error'));
      setRepairNotice(result.ok ? '当前草稿已通过检查。' : '');
      await reload();
      window.dispatchEvent(new CustomEvent('modeling:updated', { detail: { groupId } }));
    } catch (e) { if (active.current === groupId) setError(message(e)); }
    finally { if (active.current === groupId) setBusy(null); }
  };
  const discard = async () => {
    setConfirming(null); setBusy('discard'); setError(null);
    try {
      await discardDraft(groupId);
      window.dispatchEvent(new CustomEvent('modeling:updated', { detail: { groupId } }));
    } catch (e) { if (active.current === groupId) setError(message(e)); }
    finally { if (active.current === groupId) setBusy(null); }
  };
  const restore = async (path: string) => {
    setBusy('restore'); setError(null);
    try {
      await revertDraftFile(groupId, path);
      if (active.current !== groupId) return;
      setRestorePath(null);
      window.dispatchEvent(new CustomEvent('modeling:updated', { detail: { groupId } }));
    } catch (e) { if (active.current === groupId) setError(message(e)); }
    finally { if (active.current === groupId) setBusy(null); }
  };

  // Confirmed questions whose SQL failed the publish dry-plan are left out of the release.
  const droppedQuestions = result?.ok ? result.issues.filter(i => i.source === 'questions' && i.severity !== 'error') : [];
  const label = busy === 'publish' ? '检查并发布中…' : republish ? '重新发布'
    : `${workflow?.engineeringStatus !== 'PASSED' ? '检查并发布' : '发布'} v${(workflow?.publishedVersion ?? 0) + 1}`;
  const releaseBlockers = new Set(workflow?.blockers.filter(item => item.code !== 'ENGINEERING_REQUIRED').map(item => item.message));
  if (blockerMessage && blocker?.kind !== 'asset' && blocker?.kind !== 'incomplete') releaseBlockers.add(blockerMessage);
  assetTasks.forEach(target => releaseBlockers.add(`视图「${target.name}」${target.step === 'REVIEW_PENDING' ? '待核查用途与口径' : '待通过问题验证'}`));
  incompleteViews.forEach(name => releaseBlockers.add(`视图「${name}」的工程文件不完整`));
  const visitAsset = (target: AssetReviewTarget, primary = false) =>
    <button type="button" className={`da-btn da-btn-sm${primary ? ' da-btn-primary' : ''}`} disabled={!!busy}
      aria-label={`${target.step === 'REVIEW_PENDING' ? '去核查' : '去验证'} ${target.name}`}
      onClick={() => { if (onReviewAsset?.(target)) dismiss(false); }}>
      {target.step === 'REVIEW_PENDING' ? '去核查' : '去验证'}</button>;
  const notices = <>
    {confirming === 'pending' && (compact ? <section className="modeling-publish-pending" data-tour="draft-confirm" aria-label="发布前确认">
      <span className="modeling-publish-pending-icon"><Icon name="circleAlert" size="sm" /></span>
      <div role="status" className="modeling-publish-pending-copy">
        <strong>还有 {pendingConfirmations} 项口径待确认</strong>
        <p>跳过后，本次只发布已写入草稿的内容。待确认口径可稍后处理。</p>
      </div>
      <div className="modeling-publish-pending-actions">
        <button type="button" autoFocus className="da-btn da-btn-primary da-btn-sm" onClick={() => { setConfirming(null); setOpen(false); onLocate(); }}>去处理</button>
        {!tourActive() && <button type="button" className="da-btn da-btn-sm" disabled={busy !== null || !!blocked} onClick={() => void publish(true)}>跳过并发布</button>}
      </div>
    </section> : <div role="alert" data-tour="draft-confirm" style={{ ...note, background: 'rgba(234,179,8,.14)', color: 'var(--da-warn)' }}>
      还有 {pendingConfirmations} 项需要你确定，未确定的口径不会生效。
      <button className="da-btn da-btn-sm" onClick={() => { setConfirming(null); setOpen(false); onLocate(); }}>去处理</button>
      {!tourActive() && <button className="da-btn da-btn-sm" onClick={() => void publish(true)}>跳过并发布</button>}
    </div>)}
    {confirming === 'others' && <div role="alert" data-tour="draft-confirm" style={note}>
      本次发布包含其他成员的 {changesByOthers} 项修改，确认一起发布？
      <button className="da-btn da-btn-primary da-btn-sm" onClick={() => void publish(true, true)}>确认发布</button>
      <button className="da-btn da-btn-sm" onClick={() => setConfirming(null)}>取消</button>
    </div>}
    {error && <div role="alert" style={{ ...note, color: 'var(--da-danger)' }}>{error}</div>}
    {repairNotice && <p role="status">{repairNotice}</p>}
    {issues.length > 0 && <div style={{ ...note, display: 'block' }}>
      <strong role="alert" style={{ color: 'var(--da-danger)' }}>发布检查未通过</strong>
      <ul style={{ margin: '4px 0 0' }}>{issues.map((issue, index) => {
        const view = issue.incompleteView;
        return <li key={index}>
          {issue.message}{view && <> <button type="button" className="da-btn da-btn-sm" disabled={!!busy || running}
            aria-label={`查看草稿文件并处理 ${view}`} onClick={() => setRepairView(view)}>查看草稿文件</button></>}
          {issue.assetReview && onReviewAsset && <> {visitAsset(issue.assetReview)}</>}
        </li>;
      })}</ul>
      {issues.some(issue => !issue.incompleteView && !issue.assetReview) && <p>可在建模对话中处理其余检查项。</p>}
    </div>}
    {droppedQuestions.length > 0 && <div role="alert" style={{ ...note, background: 'rgba(234,179,8,.14)', color: 'var(--da-warn)', display: 'block' }}>
      已发布 v{result?.mdlVersion}，但以下问答的 SQL 未通过检查，没有进入本次发布，已退回为未确认。请在问数优化中修改 SQL 后重新确认并发布：
      <ul style={{ margin: '4px 0 0' }}>{droppedQuestions.map((i, n) => <li key={n}>{i.message}</li>)}</ul>
      <button className="da-btn da-btn-sm" onClick={() => setResult(r => r && { ...r, issues: r.issues.filter(i => i.source !== 'questions') })}>知道了</button>
    </div>}
  </>;
  const assetTaskCount = assetTasks.length + incompleteViews.length;
  const tasks = <>
    {confirmations.length > 0 && onAnswer && <div className="modeling-center-confirmations">
      <p>口径确认后才会写入草稿。发布时可选择暂缓处理这些口径。</p>
      <ConfirmationCards key={confirmations.map(card => card.id).join(',')} questions={confirmations} onSubmit={onAnswer} />
    </div>}
    {assetTaskCount > 0 && <section className="modeling-question-review" aria-label="视图待处理">
      <strong>{assetTaskCount} 项视图待处理</strong>
      <ul>
        {assetTasks.map(target => <li key={`${target.kind}:${target.name}`}>
          <div><span>{target.name}</span><small>{target.kind === 'view' ? '公共视图' : '加工表'}{target.step === 'REVIEW_PENDING' ? '待核查用途和口径' : '待完成业务问题验证'} · 阻止发布</small></div>
          {visitAsset(target)}
        </li>)}
        {incompleteViews.map(view => <li key={view}>
          <div><span>{view}</span><small>视图工程文件不完整 · 阻止发布</small></div>
          <button type="button" className="da-btn da-btn-sm" disabled={!!busy || running}
            aria-label={`查看草稿文件并处理 ${view}`} onClick={() => setRepairView(view)}>查看草稿文件</button>
        </li>)}
      </ul>
    </section>}
    {reviewQuestions.length > 0 && <section className="modeling-question-review" aria-label="问答待复核">
      {result?.ok && <p>已发布 v{result.mdlVersion}</p>}
      <strong>{reviewQuestions.length} 条问答待复核</strong>
      <p>复核前，这些问答暂缓随本次草稿发布。</p>
      <ul>{reviewQuestions.map(question => <li key={question.id}>
        <div><span>{question.question}</span>
          <small>{question.reasons?.length ? question.reasons.join(' · ') : '依赖的建模口径发生变化'}</small></div>
        <button type="button" className="da-btn da-btn-sm" aria-label={`去复核 ${question.question}`}
          onClick={() => { if (onReviewQuestion(question.id)) dismiss(false); }}>去复核</button>
      </li>)}</ul>
    </section>}
    {!taskCount && !stateError && <p role="status" className="modeling-draft-hint">
      {checksPending ? '正在读取待办…' : '暂无待办。'}</p>}
  </>;
  const hasNotices = confirming === 'pending' || confirming === 'others' || droppedQuestions.length > 0;
  const compactOpen = compact && (drawerOpen || hasNotices || (!publishFromPage && (!!error || issues.length > 0 || busy === 'publish')));
  const draftWindow = useRef<HTMLDialogElement>(null);
  const checksRef = useRef<HTMLElement>(null);
  useEffect(() => {
    if (reviewRequested && !drawerLocked) { setTab(reviewRequested === 'tasks' ? 'tasks' : 'changes'); setOpen(true); }
  }, [reviewRequested, drawerLocked]);
  useLayoutEffect(() => {
    const window = draftWindow.current;
    if (!compactOpen || !window) return;
    // The guided spotlight stays interactive while it points at the publish confirmation.
    if (drawerLocked) window.show();
    else window.showModal();
    return () => window.close();
  }, [compactOpen, drawerLocked]);
  useEffect(() => {
    if (compactOpen && reviewRequested === 'checks') checksRef.current?.focus();
  }, [compactOpen, reviewRequested]);
  const dismiss = (clearLocation = true) => {
    if (clearLocation) onDismissReview?.();
    setImpactOpen(false);
    setOpen(false);
    setConfirming(null);
    setError(null);
    setIssues([]);
    setRepairNotice('');
    setRestorePath(null);
    setResult(previous => previous?.ok ? { ...previous, issues: [] } : null);
  };
  const publishButton = <button type="button" className={`da-btn ${ready || compact ? 'da-btn-primary' : ''}`} data-tour="draft-publish"
    disabled={busy !== null || !!blocked || confirming === 'pending' || confirming === 'others'}
    title={blocked ?? undefined} onClick={() => { setTab('changes'); setPublishFromPage(false); void publish(); }}>{label}</button>;
  const leaveFor = (open?: () => boolean) => { if (open?.()) dismiss(false); };
  const nextAction = waitingReason ? (stateError && <button type="button" className="da-btn da-btn-sm" onClick={onRetryState}>重试读取状态</button>)
    : blocker?.kind === 'asset' ? visitAsset(blocker.target, true)
      : blocker?.kind === 'incomplete' ? <button type="button" className="da-btn da-btn-primary" disabled={!!busy}
        onClick={() => setRepairView(blocker.name)}>处理未完成视图</button>
        : blocker?.kind === 'data' || blocker?.kind === 'model' ? <button type="button" className="da-btn da-btn-primary" disabled={!!busy}
          onClick={() => leaveFor(blocker.kind === 'data' ? onPrepareData : onPrepareModel)}>{blocker.kind === 'data' ? '前往数据集' : '继续建模'}</button>
          : blocker?.kind === 'caliber' ? <button type="button" className="da-btn da-btn-primary" disabled={!!busy}
            onClick={() => leaveFor(onCalibers)}>查看口径发布检查</button>
          : blocker?.kind === 'engineering' ? <button type="button" className="da-btn da-btn-primary" data-tour="draft-publish" disabled={!!busy}
            onClick={() => void checkAgain()}>{busy === 'check' ? '正在检查…' : '重新检查'}</button>
            : (changes.length > 0 || republish) && publishButton;
  const remainingChanges = changes.filter(change => !items.some(item => item.path === change.path));
  const draftItems = <>
    {!!items.length && <DraftItemsList groupId={groupId} items={items} grouped disabled={running} onChanged={() => void load()} />}
    {remainingChanges.length > 0 && <ul className="modeling-draft-files" aria-label="其他待发布修改">
      {remainingChanges.map(change => {
        const title = change.category === 'EXAMPLE' ? questionTitles?.get(change.path.slice(change.path.lastIndexOf('/') + 1).replace(/\.[^.]+$/, '')) : undefined;
        return <li key={change.path}>
          <Icon name="file" size="sm" />
          <div><strong>{title ?? change.path}</strong><small>{title ? change.path : change.summary}</small></div>
          <span>{removalPaths.has(change.path) ? '待下架' : change.kind === 'ADDED' ? '新增' : change.kind === 'REMOVED' ? '删除' : '修改'}</span>
        </li>;
      })}
    </ul>}
    {review && !items.length && !remainingChanges.length && !reviewQuestions.length && <p className="modeling-draft-hint">没有待发布的文件改动。</p>}
  </>;
  const discardAction = changes.length > 0 && (confirming === 'discard'
    ? <div className="modeling-draft-discard"><span>放弃后草稿恢复为线上版本，修改无法找回。</span>
        <button type="button" className="da-btn da-btn-sm" disabled={busy !== null || running || !!loadError} onClick={() => void discard()}>确认放弃</button>
        <button type="button" className="da-btn da-btn-sm" onClick={() => setConfirming(null)}>取消</button></div>
    : <button type="button" className="da-btn da-btn-sm" disabled={busy !== null || running} onClick={() => { setRestorePath(null); setConfirming('discard'); }}>放弃全部草稿</button>);
  const draftTitle = `相对${workflow && workflow.publishedVersion > 0 ? `线上 v${workflow.publishedVersion}` : '空白工程'}的修改`;
  const draftCounts = `业务文档 ${changes.filter(c => c.category === 'DOCUMENT').length} · 问答待发布 ${examples - removals.length}`
    + (removals.length ? ` · 待下架 ${removals.length}` : '');
  const objectChanges = draftChangeGroups(changes, questionTitles);
  const itemsByPath = new Map<string, DraftItem[]>();
  for (const item of items) {
    const entries = itemsByPath.get(item.path);
    if (entries) entries.push(item);
    else itemsByPath.set(item.path, [item]);
  }
  const matchedPaths = new Set(changes.map(change => change.path));
  const standaloneItems = items.filter(item => !matchedPaths.has(item.path));
  const emptyDraft = !!review && !objectChanges.length && !standaloneItems.length;
  const unseen = isUnseen ? items.filter(isUnseen) : [];
  const structuredItems = (entries: DraftItem[]) => <DraftItemsList groupId={groupId} items={entries}
    disabled={running || !!busy} onChanged={() => void load()} isUnseen={isUnseen} onSeen={item => onSeen?.([item])}
    onLocate={onLocateChange && (item => {
      if (onLocateChange(item)) { onSeen?.([item]); dismiss(false); }
    })} />;
  const changeList = <>
    {loadError && <p role="alert">{loadError} <button type="button" className="da-btn da-btn-sm" onClick={() => void load()}>重试</button></p>}
    {!review && !loadError && <p className="modeling-draft-hint" role="status">正在读取草稿改动…</p>}
    {restorePath && review && <div className="modeling-draft-discard">
      <span>{restoreFile
        ? restoreFile.kind === 'ADDED' ? `「${restoreFile.path}」是草稿新增文件。撤销会删除整个文件。`
          : `恢复整个文件「${restoreFile.path}」到线上版本。同文件的所有草稿改动都会撤销。`
        : '这个文件已经没有可撤销的改动。'}</span>
      {restoreFile &&
        <button type="button" className="da-btn da-btn-sm" disabled={!!busy || running || !!loadError} onClick={() => void restore(restorePath)}>确认撤销</button>}
      <button type="button" className="da-btn da-btn-sm" disabled={!!busy} onClick={() => setRestorePath(null)}>取消</button>
    </div>}
    {unseen.length > 1 && <div className="modeling-change-file-actions modeling-change-mark">
      <button type="button" className="da-btn da-btn-sm" onClick={() => onSeen?.(unseen)}>全部标为已看（{unseen.length}）</button>
    </div>}
    {objectChanges.map(object => {
      const unread = object.files.some(file => itemsByPath.get(file.path)?.some(item => isUnseen?.(item)));
      return <details key={object.key} className="modeling-change-group">
        <summary><span className="modeling-change-category">{object.category}</span><strong>{object.title}</strong>
          {unread && <span className="modeling-change-unseen">未查看</span>}
          <small>{object.files.length} 个文件</small></summary>
        <ul className="modeling-draft-files">{object.files.map(file => {
          const entries = itemsByPath.get(file.path) ?? [];
          const status = removalPaths.has(file.path) ? '待下架' : file.kind === 'ADDED' ? '新增' : file.kind === 'REMOVED' ? '删除' : '修改';
          return <li key={file.path}>
            <Icon name="file" size="sm" />
            <div><strong>{file.path}</strong><small>{status} · {file.summary}</small>
              {entries.length > 0 && structuredItems(entries)}
            </div>
            <div className="modeling-change-file-actions">
              {onInspectChange && <button type="button" className="da-btn da-btn-sm" aria-label={`查看差异 ${file.path}`}
                onClick={() => { if (onInspectChange(file.path)) { if (entries.length) onSeen?.(entries); dismiss(false); } }}>查看差异</button>}
              {file.revertible && restorePath !== file.path && <button type="button" className="da-btn da-btn-sm" disabled={!!busy || running}
                aria-label={`撤销 ${file.path} 的改动`} onClick={event => {
                  event.currentTarget.closest('.modeling-draft-window-body')?.scrollTo({ top: 0 });
                  setRestorePath(file.path); setConfirming(null);
                }}>撤销</button>}
            </div>
          </li>;
        })}</ul>
      </details>;
    })}
    {standaloneItems.length > 0 && structuredItems(standaloneItems)}
    {emptyDraft && <p className="modeling-draft-hint">没有待发布的文件改动。</p>}
  </>;
  if (compact && drawerLocked && !compactOpen && !changes.length && !republish && !reviewQuestions.length) return null;
  const pendingRelease = changes.length > 0 || republish;
  const publishChecks = <aside className="kbw-release-checks" aria-label="发布检查" ref={checksRef} tabIndex={-1}>
    <h3><Icon name="shield" size="sm" />发布检查</h3>
    <ul>
      <li><strong>模型检查</strong><span>{workflow?.engineeringStatus === 'PASSED' ? '最近一次工程检查通过' : workflow?.engineeringStatus === 'FAILED' ? '最近一次工程检查未通过' : '草稿待检查，发布时校验'}</span></li>
      <li><strong>全库问答确认情况</strong><span>{workflow ? `已确认 ${workflow.questionSummary.confirmed} / ${workflow.questionSummary.total} 条` : '正在读取问答状态'}{reviewQuestions.length > 0 && ` · ${reviewQuestions.length} 条待复核`}</span></li>
      <li><strong>阻断项</strong><span>{stateError ? '读取失败' : checksPending ? '正在读取阻断项' : releaseBlockers.size ? '处理后可继续' : '0 条'}</span>
        {!!releaseBlockers.size && <ul>{[...releaseBlockers].map(item => <li key={item}>{item}</li>)}</ul>}
        {blocker?.kind === 'engineering' && <button type="button" className="sm-text-link" disabled={!!busy}
          onClick={() => leaveFor(onOpenEngineering)}>查看工程</button>}</li>
    </ul>
    <button type="button" className="sm-text-link" disabled={!review} aria-expanded={impactOpen} onClick={() => setImpactOpen(value => !value)}>预览影响范围</button>
    {impactOpen && <div className="modeling-review-impact">
      <p>{pendingRelease ? `发布后问数使用 v${(workflow?.publishedVersion ?? 0) + 1}。` : '当前没有待发布的修改。'}</p>
      {objectChanges.length > 0 && <ul>{objectChanges.map(object => <li key={object.key}>{object.category} · {object.title} · {object.files.length} 个文件</li>)}</ul>}
    </div>}
  </aside>;
  const reviewContent = <>
    {!drawerLocked && <div className="modeling-center-tabs" role="group" aria-label="变更与待办分类">
      <button type="button" aria-pressed={tab === 'changes'} onClick={() => setTab('changes')}>变更 <b>{objectChanges.length}</b></button>
      <button type="button" aria-pressed={tab === 'tasks'} onClick={() => setTab('tasks')}>待办 <b>{taskCount}</b></button>
    </div>}
    <div className={'modeling-review-body' + (drawerLocked ? ' modeling-review-locked' : '')}>
      <div className="modeling-draft-window-body">
        {notices}
        {stateError && <p role="alert" className="modeling-draft-hint">{stateError}</p>}
        {busy === 'publish' && <p role="status" className="modeling-draft-hint">正在校验并发布当前草稿…</p>}
        {!drawerLocked && tab === 'changes' && <div className="modeling-review-counts" aria-label="文件变更统计">
          {([['ADDED', '新增'], ['MODIFIED', '修改'], ['REMOVED', '删除']] as const).map(([kind, caption]) =>
            <span key={kind}>{caption} <b>{changes.filter(change => change.kind === kind).length}</b></span>)}
        </div>}
        {!drawerLocked && (tab === 'changes' ? changeList : tasks)}
        {!drawerLocked && tab === 'changes' && blocked && !blocker && !waitingReason && reviewOnly &&
          <p className="modeling-draft-hint" role="status">{blocked}</p>}
      </div>
      {!drawerLocked && publishChecks}
    </div>
    {!drawerLocked && <footer className="modeling-draft-window-foot">
      <div>{discardAction}</div>
      <div className="kbw-release-action">
        {waitingReason && busy !== 'publish' && <span className="da-small" role="status">{waitingReason}</span>}
        {!waitingReason && blockerMessage && <span className="kbw-release-blocker" role="status">{blockerMessage}</span>}
        {!waitingReason && !blocker && pendingRelease && (pendingConfirmations > 0 || reviewQuestions.length > 0) &&
          <span className="kbw-release-deferrals">
            {[pendingConfirmations ? `${pendingConfirmations} 项口径待确认` : null,
              reviewQuestions.length ? `${reviewQuestions.length} 条问答暂缓` : null].filter(Boolean).join(' · ')}
          </span>}
        {confirming !== 'pending' && confirming !== 'others' && nextAction}
      </div>
    </footer>}
  </>;
  return <div className={`modeling-draft-bar${compact ? ' modeling-draft-compact' : ''}`} aria-label="草稿栏" style={{ position: 'relative' }}>
    {compact && drawerLocked && (blocker ? nextAction : publishButton)}
    {compactOpen && <dialog ref={draftWindow} className="modeling-draft-window" aria-label="变更与待办"
      onCancel={event => { event.preventDefault(); if (!busy) dismiss(); }}>
      <header className="modeling-draft-window-head">
        <div><h2>变更与待办</h2><p>{draftTitle}</p></div>
        <button type="button" className="da-btn da-btn-sm" aria-label="关闭变更与待办" disabled={!!busy} onClick={() => dismiss()}><Icon name="close" size="sm" /></button>
      </header>
      {reviewContent}
    </dialog>}
    {repairView && <IncompleteViewDialog key={groupId + '/' + repairView} groupId={groupId} name={repairView}
      disabled={running || !!busy} onClose={() => setRepairView(null)} onRemoved={() => {
        if (active.current !== groupId) return;
        setIssues(previous => previous.filter(issue => issue.incompleteView !== repairView));
        setRepairNotice(`已从草稿移除「${repairView}」。可以重新检查并发布。`);
        setRepairView(null);
        window.dispatchEvent(new CustomEvent('modeling:updated', { detail: { groupId } }));
      }} />}
    {!compact && drawerOpen && <div role="dialog" aria-label="草稿修改" className="modeling-draft-drawer" style={{ position: 'absolute', left: 0, right: 0, bottom: '100%', marginBottom: 6, maxHeight: '60vh', overflowY: 'auto', zIndex: 20,
      background: 'var(--da-surface)', border: '1px solid var(--da-border)', borderRadius: 8, boxShadow: '0 -8px 24px rgba(15,23,42,.12)' }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 8, padding: '8px 12px', borderBottom: '1px solid var(--da-border)', position: 'sticky', top: 0, background: 'inherit' }}>
        <strong>{draftTitle}</strong>
        <span style={{ fontSize: 12, color: 'var(--da-text-muted)' }}>{draftCounts}</span>
        <span style={{ flex: 1 }} />
        <button className="da-btn da-btn-sm" aria-label="收起" onClick={() => setOpen(false)}>×</button>
      </div>
      {draftItems}
      <div style={{ padding: '8px 12px', borderTop: '1px solid var(--da-border)' }}>
        {discardAction}
      </div>
    </div>}
    {!compact && notices}
    {compact ? null : terms ? <div className="terms-publish-bar">
      <span className="terms-publish-icon"><Icon name="file" /></span>
      <div className="terms-publish-copy">
        <strong>{terms.pending > 0 ? `有 ${terms.pending} 项术语修改待发布` : changes.length > 0 || republish ? '知识库有修改待发布' : review ? (workflow?.publishedVersion ? '术语已与线上版本同步' : '暂无待发布修改') : error ? '暂时无法读取草稿状态' : '正在检查知识库草稿…'}</strong>
        <p>发布会一并包含该知识库的其他建模修改。</p>
        {terms.editing && <p role="status">请先保存或取消正在编辑的术语。</p>}
        {blocked && changes.length > 0 && <p role="status">{blocked}</p>}
        {republish && !running && <p role="status">有修改尚未生效，重新发布后才能问数。</p>}
        {result?.ok && <p className="terms-publish-success" role="status">已发布 v{result.mdlVersion}</p>}
      </div>
      <div className="terms-publish-actions">
        <button className="da-btn" aria-expanded={drawerOpen} disabled={drawerLocked} title={drawerHint} onClick={() => setOpen(!open)}>{drawerOpen ? '收起建模草稿' : '查看建模草稿'}</button>
        <button className="da-btn da-btn-primary" data-tour="draft-publish" disabled={busy !== null || !!blocked || terms.editing || !review} onClick={() => void publish()}>
          {busy === 'publish' ? '检查并发布中…' : republish ? '重新发布知识库' : '发布知识库'}
        </button>
      </div>
    </div> : <div className="modeling-draft-status">
      <span className="modeling-draft-title"><Icon name="file" size="sm" /><strong>草稿</strong></span>
      <span className="modeling-draft-counts"><span>建模结果 <b>{items.length}</b></span><span>待你确定 <b>{pendingConfirmations}</b></span><span>已确认问答 <b>{examples}</b></span></span>
      <button className="da-btn modeling-draft-toggle" aria-label={drawerOpen ? '收起全部草稿' : '查看全部草稿'} aria-expanded={drawerOpen} disabled={drawerLocked} title={drawerHint} onClick={() => setOpen(!open)}>{drawerOpen ? '收起' : '查看全部'}<Icon name={drawerOpen ? 'arrowDown' : 'arrowUp'} size="sm" /></button>
      <span className="modeling-draft-spacer" />
      {result?.ok && <span role="status" className="modeling-draft-success">已发布 v{result.mdlVersion}</span>}
      {blocked && <span className="modeling-draft-hint">{blocked}</span>}
      {republish && !running && <span className="modeling-draft-warning">有修改尚未生效，重新发布后才能问数</span>}
      {ready && <span className="modeling-draft-ready"><Icon name="check" size="sm" />可发布</span>}
      {publishButton}
    </div>}
  </div>;
}

const note = { display: 'flex', gap: 8, alignItems: 'center', flexWrap: 'wrap' as const, fontSize: 12, padding: '6px 10px', marginBottom: 6, borderRadius: 8, border: '1px solid var(--da-border)', background: 'var(--da-surface)' };
