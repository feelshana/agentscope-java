import { useCallback, useEffect, useRef, useState } from 'react';
import { discardDraft, getDraftItems, getDraftReview } from '../../api/draftReview';
import type { DraftItem, DraftReview } from '../../api/draftReview';
import { publishMdl, validateMdl } from '../../api/semanticModeling';
import type { MdlIssue, MdlPublishResult } from '../../api/semanticModeling';
import type { ModelingWorkflow } from '../../api/modelingWorkflow';
import DraftItemsList from './DraftItemsList';

const message = (e: unknown) => e instanceof Error ? e.message : String(e);

/**
 * spec092 §7 draft bar: everything not yet published, one publish for one finished round. The
 * draft is checked automatically before publishing; open questions only warn, they never block.
 */
/** What the page needs to guide the next step (spec092 §7). */
export type DraftSummary = { changes: number; examples: number; publishing: boolean; publishedVersion: number | null; error: string | null };

export default function DraftBar({ groupId, workflow, assistantActive, pendingConfirmations, onLocate, onPublished, publishSignal = 0, onSummary }: {
  groupId: string;
  workflow: ModelingWorkflow | null;
  assistantActive: boolean;
  pendingConfirmations: number;
  /** Scrolls to "需要你确定的部分". */
  onLocate: () => void;
  onPublished: () => void;
  /** Incremented by the page's "下一步" card to publish through this bar's checks. */
  publishSignal?: number;
  onSummary?: (summary: DraftSummary) => void;
}) {
  const [items, setItems] = useState<DraftItem[]>([]);
  const [review, setReview] = useState<DraftReview | null>(null);
  const [open, setOpen] = useState(false);
  const [busy, setBusy] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [issues, setIssues] = useState<MdlIssue[]>([]);
  const [result, setResult] = useState<MdlPublishResult | null>(null);
  const [confirming, setConfirming] = useState<'pending' | 'others' | 'discard' | null>(null);
  const active = useRef(groupId); active.current = groupId;

  const load = useCallback(async () => {
    try {
      const [list, r] = await Promise.all([getDraftItems(groupId), getDraftReview(groupId)]);
      if (active.current !== groupId) return;
      setItems(list); setReview(r);
    } catch (e) { if (active.current === groupId) setError(message(e)); }
  }, [groupId]);
  useEffect(() => {
    setItems([]); setReview(null); setOpen(false); setIssues([]); setResult(null); setConfirming(null); setError(null);
    void load();
    const update = (event: Event) => {
      if ((event as CustomEvent<{ groupId: string }>).detail?.groupId === groupId) void load();
    };
    window.addEventListener('modeling:updated', update);
    return () => window.removeEventListener('modeling:updated', update);
  }, [groupId, load]);

  const changes = review?.changes ?? [];
  const examples = changes.filter(c => c.category === 'EXAMPLE').length;
  const running = assistantActive || !!review?.assistantRunning;
  // A change can be recorded without a file difference (e.g. confirmed while a publish ran);
  // querying then waits for a publish, so the bar must still offer one.
  const republish = changes.length === 0 && !!workflow && workflow.publishedVersion > 0 && !workflow.queryAvailable;
  const blocked = running ? '助手正在修改草稿，完成后才能发布'
    : changes.length === 0 && !republish ? '草稿与线上版本一致' : null;

  const ready = !blocked && pendingConfirmations === 0;
  const summary = useRef(onSummary); summary.current = onSummary;
  useEffect(() => {
    summary.current?.({ changes: changes.length, examples, publishing: busy === 'publish',
      publishedVersion: result?.ok ? result.mdlVersion : null, error: error ?? (issues.length ? issues[0].message : null) });
  }, [changes.length, examples, busy, result, error, issues]);
  const lastSignal = useRef(publishSignal);
  useEffect(() => {
    if (publishSignal === lastSignal.current) return;
    lastSignal.current = publishSignal;
    if (!blocked && busy === null) void publish();
  }, [publishSignal]); // eslint-disable-line react-hooks/exhaustive-deps

  const publish = async (skipPending = false, includeOthers = false) => {
    if (!skipPending && pendingConfirmations > 0) { setConfirming('pending'); return; }
    if (!includeOthers && (review?.changesByOthers ?? 0) > 0) { setConfirming('others'); return; }
    setConfirming(null); setBusy('publish'); setError(null); setResult(null);
    try {
      const validation = await validateMdl(groupId);
      if (active.current !== groupId) return;
      const errors = validation.issues.filter(i => i.severity === 'error');
      setIssues(errors);
      if (errors.length) return;
      const published = await publishMdl(groupId);
      if (active.current !== groupId) return;
      setResult(published);
      setIssues(published.issues.filter(i => i.severity === 'error'));
      window.dispatchEvent(new CustomEvent('modeling:updated', { detail: { groupId } }));
      onPublished();
    } catch (e) { if (active.current === groupId) setError(message(e)); }
    finally { if (active.current === groupId) setBusy(null); }
  };
  const discard = async () => {
    setConfirming(null); setBusy('discard'); setError(null);
    try {
      await discardDraft(groupId);
      window.dispatchEvent(new CustomEvent('modeling:updated', { detail: { groupId } }));
      onPublished();
    } catch (e) { if (active.current === groupId) setError(message(e)); }
    finally { if (active.current === groupId) setBusy(null); }
  };

  // Confirmed questions whose SQL failed the publish dry-plan are left out of the release.
  const droppedQuestions = result?.ok ? result.issues.filter(i => i.source === 'questions' && i.severity !== 'error') : [];
  const label = busy === 'publish' ? '检查并发布中…' : republish ? '重新发布' : `发布 v${(workflow?.publishedVersion ?? 0) + 1}`;
  const notices = <>
    {confirming === 'pending' && <div role="alert" style={{ ...note, background: 'rgba(234,179,8,.14)', color: 'var(--da-warn)' }}>
      还有 {pendingConfirmations} 项需要你确定，未确定的口径不会生效。
      <button className="da-btn da-btn-sm" onClick={() => { setConfirming(null); setOpen(false); onLocate(); }}>去处理</button>
      <button className="da-btn da-btn-sm" onClick={() => void publish(true)}>跳过并发布</button>
    </div>}
    {confirming === 'others' && <div role="alert" style={note}>
      本次发布包含其他成员的 {review?.changesByOthers} 项修改，确认一起发布？
      <button className="da-btn da-btn-primary da-btn-sm" onClick={() => void publish(true, true)}>确认发布</button>
      <button className="da-btn da-btn-sm" onClick={() => setConfirming(null)}>取消</button>
    </div>}
    {error && <div role="alert" style={{ ...note, color: 'var(--da-danger)' }}>{error}</div>}
    {issues.length > 0 && <div role="alert" style={{ ...note, color: 'var(--da-danger)', display: 'block' }}>
      检查未通过，请在对话中让助手修复：<ul style={{ margin: '4px 0 0' }}>{issues.map((i, n) => <li key={n}>{i.message}</li>)}</ul>
    </div>}
    {droppedQuestions.length > 0 && <div role="alert" style={{ ...note, background: 'rgba(234,179,8,.14)', color: 'var(--da-warn)', display: 'block' }}>
      已发布 v{result?.mdlVersion}，但以下问答的 SQL 未通过检查，没有进入本次发布，已退回为未确认。请在试问中修改 SQL 后重新确认并发布：
      <ul style={{ margin: '4px 0 0' }}>{droppedQuestions.map((i, n) => <li key={n}>{i.message}</li>)}</ul>
      <button className="da-btn da-btn-sm" onClick={() => setResult(r => r && { ...r, issues: r.issues.filter(i => i.source !== 'questions') })}>知道了</button>
    </div>}
  </>;
  return <footer aria-label="草稿栏" style={{ position: 'relative', flex: '0 0 auto' }}>
    {open && <div role="dialog" aria-label="草稿修改" style={{ position: 'absolute', left: 0, right: 0, bottom: '100%', marginBottom: 6, maxHeight: '60vh', overflowY: 'auto', zIndex: 20,
      background: 'var(--da-surface, #fff)', border: '1px solid var(--da-border)', borderRadius: 10, boxShadow: '0 -8px 24px rgba(15,23,42,.12)' }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 8, padding: '8px 12px', borderBottom: '1px solid var(--da-border)', position: 'sticky', top: 0, background: 'inherit' }}>
        <strong>相对{workflow?.queryAvailable ? `线上 v${workflow.publishedVersion}` : '空白工程'}的修改</strong>
        <span style={{ fontSize: 12, color: 'var(--da-text-muted)' }}>业务文档 {changes.filter(c => c.category === 'DOCUMENT').length} · 已确认问答 {examples}</span>
        <span style={{ flex: 1 }} />
        <button className="da-btn da-btn-sm" aria-label="收起" onClick={() => setOpen(false)}>×</button>
      </div>
      {items.length ? <DraftItemsList groupId={groupId} items={items} grouped disabled={running} onChanged={() => void load()} />
        : <div style={{ padding: 12, fontSize: 12, color: 'var(--da-text-muted)' }}>没有待发布的模型、关系或业务语义修改。</div>}
      <div style={{ padding: '8px 12px', borderTop: '1px solid var(--da-border)' }}>
        {changes.length > 0 && (confirming === 'discard'
          ? <span style={{ fontSize: 12 }}>放弃后草稿恢复为线上版本，修改无法找回。
              <button className="da-btn da-btn-sm" style={{ marginLeft: 8 }} disabled={busy !== null || running} onClick={() => void discard()}>确认放弃</button>
              <button className="da-btn da-btn-sm" style={{ marginLeft: 6 }} onClick={() => setConfirming(null)}>取消</button></span>
          : <button className="da-btn da-btn-sm" disabled={busy !== null || running} onClick={() => setConfirming('discard')}>放弃全部草稿</button>)}
      </div>
    </div>}
    {notices}
    <div style={{ display: 'flex', alignItems: 'center', gap: 10, padding: '6px 12px', border: '1px solid var(--da-border)', borderRadius: 12, background: 'var(--da-surface, #fff)', fontSize: 13, boxShadow: 'var(--da-shadow-card)' }}>
      <strong>草稿</strong>
      <span style={{ fontSize: 12, color: 'var(--da-text-muted)' }}>建模结果 {items.length} · 待你确定 {pendingConfirmations} · 已确认问答 {examples}</span>
      <button className="da-btn da-btn-sm" onClick={() => setOpen(!open)}>{open ? '收起' : '查看全部 ↑'}</button>
      <span style={{ flex: 1 }} />
      {result?.ok && <span role="status" style={{ fontSize: 12, color: 'var(--da-success)' }}>已发布 v{result.mdlVersion}</span>}
      {blocked && changes.length > 0 && <span style={{ fontSize: 12, color: 'var(--da-text-muted)' }}>{blocked}</span>}
      {republish && !running && <span style={{ fontSize: 12, color: 'var(--da-warn)' }}>有修改尚未生效，重新发布后才能问数</span>}
      {ready && <span style={{ fontSize: 11, padding: '1px 6px', borderRadius: 4, background: 'rgba(22,163,74,.12)', color: 'var(--da-success)' }}>可发布</span>}
      <button className={`da-btn ${ready ? 'da-btn-primary' : ''} da-btn-sm`} disabled={busy !== null || !!blocked} onClick={() => void publish()}>{label}</button>
    </div>
  </footer>;
}

const note = { display: 'flex', gap: 8, alignItems: 'center', flexWrap: 'wrap' as const, fontSize: 12, padding: '6px 10px', marginBottom: 6, borderRadius: 8, border: '1px solid var(--da-border)', background: 'var(--da-surface, #fff)' };
