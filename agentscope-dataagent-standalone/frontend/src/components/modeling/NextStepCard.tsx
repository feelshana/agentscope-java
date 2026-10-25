import type { ReactNode } from 'react';
import type { DraftSummary } from './DraftBar';
import { useQueryReadiness } from '../useQueryReadiness';

/**
 * spec092 §7: the end of the conversation says what to do next once the assistant is idle and
 * nothing waits for the user's decision — optionally try questions, then check and publish.
 */
export default function NextStepCard({ groupId, summary, unconfirmedTrials = 0, nextVersion, onTrial, onPublish, onQuery }: {
  groupId: string;
  summary: DraftSummary;
  /** Answered trial questions not confirmed yet: they are not published as examples. */
  unconfirmedTrials?: number;
  nextVersion: number;
  onTrial: () => void;
  onPublish: () => void;
  onQuery?: () => void;
}) {
  // A question asked while the query engine prepares only waits, so 去问数 waits with it.
  const phase = useQueryReadiness(groupId, summary.publishedVersion);
  const box = { border: '1px solid var(--da-success)', borderRadius: 14, padding: '12px 16px', background: 'var(--da-surface, #fff)', fontSize: 13, boxShadow: 'var(--da-shadow-card)' };
  if (summary.publishedVersion !== null && summary.changes === 0)
    return <section aria-label="下一步" style={box}>
      <strong style={{ color: 'var(--da-success)' }}>已发布 v{summary.publishedVersion}</strong>
      {phase === undefined ? null : phase
        ? <span role="status" style={{ marginLeft: 8, fontSize: 13, color: 'var(--da-primary)' }}>
            <span className="da-spinner da-spinner-sm" style={{ marginRight: 6, verticalAlign: -2, borderColor: 'var(--da-border)', borderTopColor: 'var(--da-primary)' }} />
            问数准备中（{phase}），约半分钟后可问数</span>
        : <span style={{ marginLeft: 8, color: 'var(--da-text-muted)' }}>问数已可使用新版本。</span>}
      {onQuery && <button className="da-btn da-btn-primary da-btn-sm" style={{ marginLeft: 8 }} disabled={phase !== null}
        title={phase ? '问数准备完成后可用' : undefined} onClick={onQuery}>去问数</button>}
      {unconfirmedTrials > 0 && <div style={{ marginTop: 6, fontSize: 12, color: 'var(--da-warn)' }}>
        有 {unconfirmedTrials} 个试问还没确认正确，没有作为示例发布。<button className="da-btn da-btn-sm" style={{ marginLeft: 6 }} onClick={onTrial}>去确认</button></div>}
    </section>;
  if (summary.changes === 0) return null;
  const step = (n: number, title: string, detail: string, action: ReactNode) => <div style={{ display: 'flex', alignItems: 'center', gap: 10, padding: '6px 0' }}>
    <span style={{ width: 20, height: 20, borderRadius: 10, background: 'var(--da-surface-sunken, #f1f5f9)', display: 'inline-flex', alignItems: 'center', justifyContent: 'center', fontSize: 12 }}>{n}</span>
    <span style={{ flex: 1 }}><span style={{ fontWeight: 500 }}>{title}</span>
      <span style={{ display: 'block', fontSize: 12, color: 'var(--da-text-muted)' }}>{detail}</span></span>
    {action}
  </div>;
  return <section aria-label="下一步" style={box}>
    <strong>建模已完成，下一步</strong>
    {step(1, '试问验证口径（可选）', unconfirmedTrials ? `有 ${unconfirmedTrials} 个试问还没确认正确，不确认不会作为示例发布`
      : summary.examples ? `已确认 ${summary.examples} 个问答正确，发布后问数时会参考` : '用草稿回答几个常问问题，正确的点"确认正确"',
      <button className="da-btn da-btn-sm" onClick={onTrial}>打开试问</button>)}
    {step(2, `检查并发布 v${nextVersion}`, `草稿有 ${summary.changes} 处修改，发布后问数才会使用`,
      <button className="da-btn da-btn-primary da-btn-sm" disabled={summary.publishing} onClick={onPublish}>{summary.publishing ? '检查并发布中…' : '发布'}</button>)}
    {summary.error && <div role="alert" style={{ fontSize: 12, color: 'var(--da-danger)' }}>{summary.error}</div>}
  </section>;
}
