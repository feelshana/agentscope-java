import type { ReactNode } from 'react';
import type { DraftSummary } from './DraftBar';

/**
 * spec092 §7: the end of the conversation says what to do next once the assistant is idle and
 * nothing waits for the user's decision — optionally try questions, then check and publish.
 */
export default function NextStepCard({ summary, nextVersion, onTrial, onPublish, onQuery }: {
  summary: DraftSummary;
  nextVersion: number;
  onTrial: () => void;
  onPublish: () => void;
  onQuery?: () => void;
}) {
  const box = { border: '1px solid var(--da-success)', borderRadius: 10, padding: '10px 12px', background: 'var(--da-surface, #fff)', fontSize: 13, maxWidth: 720 };
  if (summary.publishedVersion !== null && summary.changes === 0)
    return <section aria-label="下一步" style={box}>
      <strong style={{ color: 'var(--da-success)' }}>已发布 v{summary.publishedVersion}</strong>
      <span style={{ marginLeft: 8, color: 'var(--da-text-muted)' }}>问数开始使用新版本。</span>
      {onQuery && <button className="da-btn da-btn-primary da-btn-sm" style={{ marginLeft: 8 }} onClick={onQuery}>去问数</button>}
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
    {step(1, '试问验证口径（可选）', summary.examples ? `已确认 ${summary.examples} 个问答正确，发布后问数时会参考` : '用草稿回答几个常问问题，正确的点"确认正确"',
      <button className="da-btn da-btn-sm" onClick={onTrial}>打开试问</button>)}
    {step(2, `检查并发布 v${nextVersion}`, `草稿有 ${summary.changes} 处修改，发布后问数才会使用`,
      <button className="da-btn da-btn-primary da-btn-sm" disabled={summary.publishing} onClick={onPublish}>{summary.publishing ? '检查并发布中…' : '发布'}</button>)}
    {summary.error && <div role="alert" style={{ fontSize: 12, color: 'var(--da-danger)' }}>{summary.error}</div>}
  </section>;
}
