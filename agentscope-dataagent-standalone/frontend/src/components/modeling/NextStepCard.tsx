import { useEffect, useState } from 'react';
import type { ReactNode } from 'react';
import { recommendedQuestion } from '../../api/chat';
import type { DraftSummary } from './DraftBar';
import { useQueryReadiness } from '../useQueryReadiness';

/**
 * spec092 §7: the end of the conversation says what to do next once the assistant is idle and
 * nothing waits for the user's decision — check and publish. spec117: modeling no longer asks
 * trial questions; the recommended question is shown, and questions are checked in 问数优化.
 */
export default function NextStepCard({ groupId, summary, unconfirmedTrials = 0, nextVersion, onTrial, onPublish, onQuery, queryHeldHint }: {
  groupId: string;
  summary: DraftSummary;
  /** Answered trial questions not confirmed yet: they are not published as examples. */
  unconfirmedTrials?: number;
  nextVersion: number;
  /** Opens 问数优化, where questions are tried and confirmed. */
  onTrial: () => void;
  onPublish: () => void;
  onQuery?: () => void;
  /** Set while the guided tour will take the user to the query page itself. */
  queryHeldHint?: string;
}) {
  // A question asked while the query engine prepares only waits, so 去问数 waits with it.
  const phase = useQueryReadiness(groupId, summary.publishedVersion);
  const [recommended, setRecommended] = useState<string | null>(null);
  useEffect(() => {
    const controller = new AbortController();
    void recommendedQuestion(groupId, controller.signal).then(setRecommended).catch(() => undefined);
    return () => controller.abort();
  }, [groupId, summary.publishedVersion]);
  const box = { border: '1px solid var(--da-success)', borderRadius: 12, padding: '12px 16px', background: 'var(--da-surface)', fontSize: 13, boxShadow: 'var(--da-shadow-card)' };
  if (summary.publishedVersion !== null && summary.changes === 0)
    return <section aria-label="下一步" style={box}>
      <strong style={{ color: 'var(--da-success)' }}>已发布 v{summary.publishedVersion}</strong>
      {phase === undefined ? null : phase
        ? <span role="status" style={{ marginLeft: 8, fontSize: 13, color: 'var(--da-primary)' }}>
            <span className="da-spinner da-spinner-sm" style={{ marginRight: 6, verticalAlign: -2, borderColor: 'var(--da-border)', borderTopColor: 'var(--da-primary)' }} />
            问数准备中（{phase}），约半分钟后可问数</span>
        : <span style={{ marginLeft: 8, color: 'var(--da-text-muted)' }}>问数已可使用新版本。</span>}
      {onQuery && <button className="da-btn da-btn-primary da-btn-sm" style={{ marginLeft: 8 }} disabled={phase !== null || !!queryHeldHint}
        title={queryHeldHint ?? (phase ? '问数准备完成后可用' : undefined)} onClick={onQuery}>去问数</button>}

    </section>;
  if (summary.changes === 0) return null;
  const step = (n: number, title: string, detail: string, action: ReactNode) => <div style={{ display: 'flex', alignItems: 'center', gap: 10, padding: '6px 0' }}>
    <span style={{ width: 20, height: 20, borderRadius: 8, background: 'var(--da-surface-sunken)', display: 'inline-flex', alignItems: 'center', justifyContent: 'center', fontSize: 12 }}>{n}</span>
    <span style={{ flex: 1 }}><span style={{ fontWeight: 500 }}>{title}</span>
      <span style={{ display: 'block', fontSize: 12, color: 'var(--da-text-muted)' }}>{detail}</span></span>
    {action}
  </div>;
  return <section aria-label="下一步" style={box}>
    <strong>建模已完成，下一步</strong>
    {step(1, '推荐问题', recommended ? `“${recommended}”，问数首页默认显示；想核对问答或换一个，去问数优化` : '发布后平台会生成一个推荐问题，问数首页默认显示',
      <button className="da-btn da-btn-sm" onClick={onTrial}>问数优化</button>)}
    {step(2, `检查并发布 v${nextVersion}`, `草稿有 ${summary.changes} 处修改，发布后问数才会使用`,
      <button className="da-btn da-btn-primary da-btn-sm" disabled={summary.publishing} onClick={onPublish}>{summary.publishing ? '检查并发布中…' : '发布'}</button>)}
    {summary.error && <div role="alert" style={{ fontSize: 12, color: 'var(--da-danger)' }}>{summary.error}</div>}
  </section>;
}
