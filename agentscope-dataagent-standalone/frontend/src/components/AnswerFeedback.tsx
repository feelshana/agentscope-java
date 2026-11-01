import { useEffect, useId, useRef, useState } from 'react';
import type { ReactNode } from 'react';
import { answerFeedback, AnswerFeedbackResult } from '../api/chat';
import { messagePage } from '../api/sessions';
import Icon from './Icon';
import './AnswerFeedback.css';
import './ConversationPolish.css';

function FeedbackHelp({ analysis }: { analysis: boolean }) {
  const [open, setOpen] = useState(false);
  const pinned = useRef(false);
  const root = useRef<HTMLDivElement>(null);
  const id = useId();
  useEffect(() => {
    if (!open) return;
    const close = () => { pinned.current = false; setOpen(false); };
    const outside = (event: PointerEvent) => { if (!root.current?.contains(event.target as Node)) close(); };
    const escape = (event: KeyboardEvent) => { if (event.key === 'Escape') { event.stopPropagation(); close(); } };
    document.addEventListener('pointerdown', outside);
    document.addEventListener('keydown', escape);
    return () => { document.removeEventListener('pointerdown', outside); document.removeEventListener('keydown', escape); };
  }, [open]);
  return <div ref={root} className="answer-feedback-help"
    onPointerEnter={event => { if (event.pointerType === 'mouse') setOpen(true); }}
    onPointerLeave={() => { if (!pinned.current && !root.current?.contains(document.activeElement)) setOpen(false); }}
    onBlur={event => { if (!event.currentTarget.contains(event.relatedTarget)) { pinned.current = false; setOpen(false); } }}>
    <button type="button" className="answer-feedback-help-trigger" aria-label="评价说明"
      aria-expanded={open} aria-controls={id} aria-describedby={open ? id : undefined}
      onFocus={() => setOpen(true)} onClick={() => { pinned.current = !pinned.current; setOpen(pinned.current); }}>
      <Icon name="info" size="sm" /><span>评价说明</span>
    </button>
    {open && <div id={id} className="answer-feedback-help-content" role="tooltip">
      {analysis ? '反馈可选 · 分析类回答只记录反馈，不保存为问答样例。'
        : '反馈可选 · 确认正确后，成功查询会保留为后续问数的参考样例。'}
    </div>}
  </div>;
}

export default function AnswerFeedback({ agentId, session, answerId, text, analysis = false, copyAction }: {
  agentId: string; session: string; answerId?: string; text: string;
  /** spec102: analysis answers record feedback only; their queries never become examples. */
  analysis?: boolean;
  copyAction?: ReactNode;
}) {
  const [resolved, setResolved] = useState<string | null>(answerId ?? null);
  const [feedback, setFeedback] = useState<AnswerFeedbackResult | null>(null);
  const [busy, setBusy] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [retry, setRetry] = useState(0);
  const [failedVote, setFailedVote] = useState<'UP' | 'DOWN' | null>(null);
  useEffect(() => {
    let active = true;
    setBusy(true); setError(null); setFailedVote(null);
    void (async () => {
      try {
        const id = answerId ?? [...(await messagePage(agentId, session)).items].reverse()
          .find(t => t.role.toUpperCase() === 'ASSISTANT' && t.content === text)?.id;
        if (!id) throw new Error('答案历史尚未就绪，请重试');
        const value = await answerFeedback(agentId, session, id);
        if (active) { setResolved(id); setFeedback(value); }
      } catch (e) { if (active) setError(e instanceof Error ? e.message : '无法读取反馈'); }
      finally { if (active) setBusy(false); }
    })();
    return () => { active = false; };
  }, [agentId, session, answerId, text, retry]);
  async function submit(vote: 'UP' | 'DOWN') {
    if (!resolved || busy) return;
    setBusy(true); setError(null); setFailedVote(null);
    try { setFeedback(await answerFeedback(agentId, session, resolved, vote)); }
    catch (e) { setFailedVote(vote); setError(e instanceof Error ? e.message : '反馈未保存，请重试'); }
    finally { setBusy(false); }
  }
  return <div className="answer-feedback" aria-busy={busy}>
    <div className={`answer-feedback-toolbar${copyAction ? ' has-copy' : ''}`} role="group" aria-label="这次答案的口径和结果正确吗？">
      {copyAction && <>{copyAction}<span className="answer-feedback-divider" aria-hidden="true" /></>}
      <div className="answer-feedback-actions">
        <button type="button" className="answer-action-button answer-feedback-vote" disabled={busy || !resolved}
          aria-pressed={feedback?.vote === 'UP'} aria-label={feedback?.vote === 'UP' ? '已确认正确' : '回答正确'}
          data-tooltip={feedback?.vote === 'UP' ? '已确认正确' : '回答正确'}
          onClick={() => void submit('UP')}><Icon name="thumbsUp" /></button>
        <button type="button" className="answer-action-button answer-feedback-vote" disabled={busy || !resolved}
          aria-pressed={feedback?.vote === 'DOWN'} aria-label={feedback?.vote === 'DOWN' ? '已反馈不正确' : '回答不正确'}
          data-tooltip={feedback?.vote === 'DOWN' ? '已反馈不正确' : '回答不正确'}
          onClick={() => void submit('DOWN')}><Icon name="thumbsDown" /></button>
      </div>
      <FeedbackHelp analysis={analysis} />
    </div>
    <p className={`answer-feedback-note${feedback?.memoryStatus === 'FAILED' ? ' is-warning' : ''}`} role="status" aria-live="polite">
      {!busy && feedback?.vote && <><Icon name={feedback.memoryStatus === 'FAILED' ? 'info' : 'check'} size="sm" /><span>{feedback.message}</span></>}
      {busy && <span className="da-visually-hidden">正在读取或保存评价</span>}
    </p>
    {error && <div className="answer-feedback-error" role="alert"><Icon name="warn" size="sm" /><span>{error}</span><button type="button" disabled={busy} onClick={() => failedVote ? void submit(failedVote) : setRetry(v => v + 1)}>重试</button></div>}
  </div>;
}
