import { tourActive, tourLocksDraftDrawer, tourSignal, useTour } from '../../state/guidedTour';
import { useCallback, useEffect, useLayoutEffect, useRef, useState } from 'react';
import { discardDraft, getDraftItems, getDraftReview, revertDraftFile } from '../../api/draftReview';
import type { Confirmation, ConfirmationAnswer, DraftItem, DraftReview } from '../../api/draftReview';
import type { QuestionReview } from '../../api/modelingQuestions';
import { publishMdl } from '../../api/semanticModeling';
import type { MdlIssue, MdlPublishResult } from '../../api/semanticModeling';
import { getAssetCatalog } from '../../api/semanticAssets';
import type { ModelingWorkflow } from '../../api/modelingWorkflow';
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

type AssetTask = NonNullable<MdlIssue['assetReview']>;
type CenterTab = 'changes' | 'tasks';

export default function DraftBar({ groupId, workflow, assistantActive, pendingConfirmations, onLocate, confirmations = [], onAnswer,
  reviewQuestions, questionTitles, pendingRemovals = [], onReviewQuestion, onReviewAsset, onInspectChange, onLocateChange,
  isUnseen, onSeen, publishSignal = 0, reviewSignal = 0, taskSignal = 0, onSummary, terms, compact = false, editingBlocked = false, checksPending = false }: {
  groupId: string;
  workflow: ModelingWorkflow | null;
  assistantActive: boolean;
  pendingConfirmations: number;
  /** Scrolls to "需要你确定的部分". */
  onLocate: () => void;
  confirmations?: Confirmation[];
  onAnswer?: (answers: ConfirmationAnswer[]) => void;
  reviewQuestions: { id: string; question: string; reasons?: string[] }[];
  questionTitles?: ReadonlyMap<string, string>;
  pendingRemovals?: readonly QuestionReview[];
  onReviewQuestion: (id: string) => boolean;
  onReviewAsset?: (target: NonNullable<MdlIssue['assetReview']>) => boolean;
  onInspectChange?: (path: string) => boolean;
  onLocateChange?: (item: DraftItem) => boolean;
  isUnseen?: (item: DraftItem) => boolean;
  onSeen?: (items: DraftItem[]) => void;
  /** Incremented by the page's "下一步" card to publish through this bar's checks. */
  publishSignal?: number;
  reviewSignal?: number;
  taskSignal?: number;
  onSummary?: (summary: DraftSummary) => void;
  /** A quieter presentation for the terms page; all publish checks remain shared. */
  terms?: { pending: number; editing: boolean };
  compact?: boolean;
  editingBlocked?: boolean;
  checksPending?: boolean;
}) {
  const [items, setItems] = useState<DraftItem[]>([]);
  const [review, setReview] = useState<DraftReview | null>(null);
  const [open, setOpen] = useState(false);
  const [tab, setTab] = useState<CenterTab>('changes');
  const [busy, setBusy] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [issues, setIssues] = useState<MdlIssue[]>([]);
  const [assetTasks, setAssetTasks] = useState<AssetTask[]>([]);
  const [incompleteViews, setIncompleteViews] = useState<string[]>([]);
  const [catalogStatus, setCatalogStatus] = useState<'loading' | 'ready' | 'failed'>('loading');
  const [restorePath, setRestorePath] = useState<string | null>(null);
  const [repairView, setRepairView] = useState<string | null>(null);
  const [repairNotice, setRepairNotice] = useState('');
  const [result, setResult] = useState<MdlPublishResult | null>(null);
  const [publishFromPage, setPublishFromPage] = useState(false);
  const [confirming, setConfirming] = useState<'pending' | 'others' | 'discard' | null>(null);
  const active = useRef(groupId); active.current = groupId;
  const tour = useTour();
  const drawerLocked = !terms && tourLocksDraftDrawer(tour, groupId);
  // Hide synchronously on the step change, before the spotlight measures the covered control.
  const drawerOpen = open && !drawerLocked;
  const canLocateAssets = !!onReviewAsset;
  useEffect(() => {
    if (!canLocateAssets || (!compact && !drawerOpen)) return;
    let cancelled = false;
    let visit = 0;
    const refresh = () => {
      const request = ++visit;
      void getAssetCatalog(groupId).then(catalog => {
        if (cancelled || request !== visit) return;
        setAssetTasks(catalog.draft.flatMap<AssetTask>(asset =>
          asset.status === 'REVIEW_PENDING' || asset.status === 'VERIFY_PENDING'
            ? [{ kind: asset.kind, name: asset.name, step: asset.status }] : []));
        setIncompleteViews([...new Set(catalog.incompleteViews ?? [])]);
        setCatalogStatus('ready');
      }).catch(() => { if (!cancelled && request === visit) setCatalogStatus('failed'); });
    };
    const updated = (event: Event) => {
      if ((event as CustomEvent<{ groupId: string }>).detail?.groupId === groupId) refresh();
    };
    refresh();
    window.addEventListener('modeling:updated', updated);
    return () => { cancelled = true; window.removeEventListener('modeling:updated', updated); };
  }, [groupId, drawerOpen, canLocateAssets, compact]);
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

  const loadSequence = useRef(0);
  const load = useCallback(async () => {
    const sequence = ++loadSequence.current;
    try {
      const [list, r] = await Promise.all([getDraftItems(groupId), getDraftReview(groupId)]);
      if (active.current !== groupId || sequence !== loadSequence.current) return;
      setItems(list); setReview(r); setLoadError(null);
    } catch (e) { if (active.current === groupId && sequence === loadSequence.current) setLoadError(message(e)); }
  }, [groupId]);
  useEffect(() => {
    setItems([]); setReview(null); setOpen(false); setIssues([]); setResult(null); setConfirming(null); setError(null); setPublishFromPage(false);
    setRepairView(null); setRepairNotice(''); setRestorePath(null); setLoadError(null);
    setAssetTasks([]); setIncompleteViews([]); setCatalogStatus('loading'); setBusy(null); setTab('changes');
    void load();
    const update = (event: Event) => {
      if ((event as CustomEvent<{ groupId: string }>).detail?.groupId === groupId) void load();
    };
    window.addEventListener('modeling:updated', update);
    return () => window.removeEventListener('modeling:updated', update);
  }, [groupId, load]);

  const heldPaths = new Set(reviewQuestions.map(question => `knowledge/sql/${question.id}.md`));
  const fileChanges = review?.changes ?? [];
  const removedPaths = new Set(fileChanges.filter(change => change.kind === 'REMOVED').map(change => change.path));
  const removals = pendingRemovals.map(({ question }) => question.id)
    .filter(id => removedPaths.has(`knowledge/sql/${id}.md`));
  const removalPaths = new Set(removals.map(id => `knowledge/sql/${id}.md`));
  const removalDefinitions = new Set(removals.map(id => `knowledge/questions/${id}.yml`));
  const changes = fileChanges.filter(change => !heldPaths.has(change.path) && !removalDefinitions.has(change.path));
  const changesByOthers = changes.filter(change => change.authors.some(author => author !== review?.currentUser)).length;
  const examples = changes.filter(c => c.category === 'EXAMPLE').length;
  const reviewOnly = changes.length === 0 && reviewQuestions.length > 0;
  const running = assistantActive || !!review?.assistantRunning;
  // A change can be recorded without a file difference (e.g. confirmed while a publish ran);
  // querying then waits for a publish, so the bar must still offer one.
  const republish = fileChanges.length === 0 && !!workflow && workflow.publishedVersion > 0 && !workflow.queryAvailable;
  const blocked = loadError ? '请在草稿状态恢复后发布' : checksPending || !review ? '正在读取草稿和待确认状态' : editingBlocked ? '请先保存或取消正在编辑的术语' : running ? '助手正在修改草稿，完成后才能发布'
    : changes.length === 0 && !republish ? (reviewOnly ? '请先复核问答，再发布为问数示例' : '当前没有待发布的修改') : null;

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
    }
  }, [publishSignal]); // eslint-disable-line react-hooks/exhaustive-deps

  const publish = async (skipPending = false, includeOthers = false) => {
    if (blocked) return;
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
      if (published.ok) {
        const reviewPending = published.issues.some(issue => issue.source === 'questions-review');
        if (reviewPending) setTab('tasks');
        setOpen(reviewPending);
        tourSignal('published', { groupId });
        // Keep the completion card in progress until the displayed draft count is refreshed.
        await load();
        if (active.current !== groupId) return;
      } else if (published.issues.some(issue => issue.incompleteView || issue.assetReview)) {
        setOpen(true);
      }
      setIssues(published.issues.filter(i => i.severity === 'error'));
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
  const label = busy === 'publish' ? '检查并发布中…' : republish ? '重新发布' : `发布 v${(workflow?.publishedVersion ?? 0) + 1}`;
  const visitAsset = (target: NonNullable<MdlIssue['assetReview']>) =>
    <button type="button" className="da-btn da-btn-sm" disabled={!!busy}
      aria-label={`${target.step === 'REVIEW_PENDING' ? '去核查' : '去验证'} ${target.name}`}
      onClick={() => { if (onReviewAsset?.(target)) dismiss(); }}>
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
  const taskCount = pendingConfirmations + reviewQuestions.length + assetTaskCount;
  const tasks = <>
    {confirmations.length > 0 && onAnswer && <div className="modeling-center-confirmations">
      <p>口径确认后才会写入草稿。发布时可选择暂缓处理这些口径。</p>
      <ConfirmationCards key={confirmations.map(card => card.id).join(',')} questions={confirmations} onSubmit={onAnswer} />
    </div>}
    {canLocateAssets && catalogStatus === 'loading' && <p role="status" className="modeling-draft-hint">正在读取公共视图待办…</p>}
    {canLocateAssets && catalogStatus === 'failed' && <p role="status" className="modeling-draft-hint">暂时无法读取公共视图待办，可打开公共视图查看。</p>}
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
          onClick={() => { if (onReviewQuestion(question.id)) dismiss(); }}>去复核</button>
      </li>)}</ul>
    </section>}
    {!taskCount && (!canLocateAssets || catalogStatus === 'ready') && <p role="status" className="modeling-draft-hint">
      {checksPending ? '正在读取待办…' : '暂无待办。'}</p>}
  </>;
  const hasNotices = confirming === 'pending' || confirming === 'others' || droppedQuestions.length > 0;
  const compactOpen = compact && (drawerOpen || hasNotices || (!publishFromPage && (!!error || issues.length > 0 || busy === 'publish')));
  const draftWindow = useRef<HTMLDialogElement>(null);
  useLayoutEffect(() => {
    const window = draftWindow.current;
    if (!compactOpen || !window) return;
    // The guided spotlight stays interactive while it points at the publish confirmation.
    if (drawerLocked) window.show();
    else window.showModal();
    return () => window.close();
  }, [compactOpen, drawerLocked]);
  const dismiss = () => {
    setOpen(false);
    setConfirming(null);
    setError(null);
    setIssues([]);
    setRepairNotice('');
    setRestorePath(null);
    setResult(previous => previous?.ok ? { ...previous, issues: [] } : null);
  };
  const publishButton = <button type="button" className={`da-btn ${ready || compact ? 'da-btn-primary' : ''}`} data-tour="draft-publish"
    disabled={busy !== null || !!blocked} onClick={() => { setPublishFromPage(false); void publish(); }}>{label}</button>;
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
        <button type="button" className="da-btn da-btn-sm" disabled={busy !== null || running} onClick={() => void discard()}>确认放弃</button>
        <button type="button" className="da-btn da-btn-sm" onClick={() => setConfirming(null)}>取消</button></div>
    : <button type="button" className="da-btn da-btn-sm" disabled={busy !== null || running} onClick={() => setConfirming('discard')}>放弃全部草稿</button>);
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
  const unseen = isUnseen ? items.filter(isUnseen) : [];
  const structuredItems = (entries: DraftItem[]) => <DraftItemsList groupId={groupId} items={entries}
    disabled={running || !!busy} onChanged={() => void load()} isUnseen={isUnseen} onSeen={item => onSeen?.([item])}
    onLocate={onLocateChange && (item => {
      if (onLocateChange(item)) { onSeen?.([item]); dismiss(); }
    })} />;
  const changeList = <>
    {loadError && <p role="alert">{loadError} <button type="button" className="da-btn da-btn-sm" onClick={() => void load()}>重试</button></p>}
    {!review && !loadError && <p className="modeling-draft-hint" role="status">正在读取草稿改动…</p>}
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
              {restorePath === file.path && <div className="modeling-draft-discard">
                <span>撤销会将整个文件恢复为线上版本。</span>
                <button type="button" className="da-btn da-btn-sm" disabled={!!busy || running} onClick={() => void restore(file.path)}>确认撤销</button>
                <button type="button" className="da-btn da-btn-sm" disabled={!!busy} onClick={() => setRestorePath(null)}>取消</button>
              </div>}
            </div>
            <div className="modeling-change-file-actions">
              {onInspectChange && <button type="button" className="da-btn da-btn-sm" aria-label={`查看差异 ${file.path}`}
                onClick={() => { if (onInspectChange(file.path)) { if (entries.length) onSeen?.(entries); dismiss(); } }}>查看差异</button>}
              {file.revertible && restorePath !== file.path && <button type="button" className="da-btn da-btn-sm" disabled={!!busy || running}
                aria-label={`撤销 ${file.path} 的改动`} onClick={() => setRestorePath(file.path)}>撤销</button>}
            </div>
          </li>;
        })}</ul>
      </details>;
    })}
    {standaloneItems.length > 0 && structuredItems(standaloneItems)}
    {review && !objectChanges.length && !standaloneItems.length && <p className="modeling-draft-hint">没有待发布的文件改动。</p>}
  </>;
  if (compact && drawerLocked && !compactOpen && !changes.length && !republish && !reviewQuestions.length) return null;
  return <div className={`modeling-draft-bar${compact ? ' modeling-draft-compact' : ''}`} aria-label="草稿栏" style={{ position: 'relative' }}>
    {compact && (drawerLocked ? publishButton : <button type="button" className="da-btn da-btn-sm modeling-draft-trigger"
      aria-expanded={compactOpen} aria-haspopup="dialog" aria-label={`变更与待办，${objectChanges.length} 项变更，${taskCount} 项待办`}
      title="变更与待办"
      onClick={() => { if (compactOpen) dismiss(); else { setTab(!objectChanges.length && taskCount ? 'tasks' : 'changes'); setOpen(true); } }}>
      <Icon name="file" size="sm" /><span>变更与待办</span>
      {objectChanges.length > 0 && <b title="待发布变更">{objectChanges.length}</b>}
      {taskCount > 0 && <b className="modeling-review-count" title="待办">{taskCount}</b>}
    </button>)}
    {compactOpen && <dialog ref={draftWindow} className="modeling-draft-window" aria-label="变更与待办"
      onCancel={event => { event.preventDefault(); if (!busy) dismiss(); }}>
      <header className="modeling-draft-window-head">
        <div><h2>变更与待办</h2><p>{draftTitle}</p></div>
        <button type="button" className="da-btn da-btn-sm" aria-label="关闭变更与待办" disabled={!!busy} onClick={dismiss}><Icon name="close" size="sm" /></button>
      </header>
      {!drawerLocked && <div className="modeling-center-tabs" role="group" aria-label="变更与待办分类">
        <button type="button" aria-pressed={tab === 'changes'} onClick={() => setTab('changes')}>变更 <b>{objectChanges.length}</b></button>
        <button type="button" aria-pressed={tab === 'tasks'} onClick={() => setTab('tasks')}>待办 <b>{taskCount}</b></button>
      </div>}
      <div className="modeling-draft-window-body">
        {notices}
        {!drawerLocked && (tab === 'changes' ? changeList : tasks)}
        {!drawerLocked && tab === 'changes' && blocked && (changes.length > 0 || republish || reviewOnly) &&
          <p className="modeling-draft-hint" role="status">{blocked}</p>}
      </div>
      {!drawerLocked && tab === 'changes' && <footer className="modeling-draft-window-foot">
        <div>{discardAction}</div>
        {confirming !== 'pending' && confirming !== 'others' && publishButton}
      </footer>}
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
