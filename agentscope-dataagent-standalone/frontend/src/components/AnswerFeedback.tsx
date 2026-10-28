import { useEffect, useState } from 'react';
import { answerFeedback, AnswerFeedbackResult } from '../api/chat';
import { messagePage } from '../api/sessions';
import Icon from './Icon';
import './AnswerFeedback.css';

export default function AnswerFeedback({ agentId, session, answerId, text }: {
  agentId: string; session: string; answerId?: string; text: string;
}) {
  const [resolved, setResolved] = useState<string | null>(answerId ?? null);
  const [feedback, setFeedback] = useState<AnswerFeedbackResult | null>(null);
  const [busy, setBusy] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [retry, setRetry] = useState(0);
  useEffect(() => {
    let active = true;
    setBusy(true); setError(null);
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
    setBusy(true); setError(null);
    try { setFeedback(await answerFeedback(agentId, session, resolved, vote)); }
    catch (e) { setError(e instanceof Error ? e.message : '反馈未保存，请重试'); }
    finally { setBusy(false); }
  }
  return <div className="answer-feedback" aria-busy={busy}>
    <div className="answer-feedback-toolbar" role="group" aria-label="这次答案的口径和结果正确吗？">
      <span className="answer-feedback-label">评价回答</span>
      <div className="answer-feedback-actions">
        <button type="button" className="answer-feedback-vote" disabled={busy || !resolved}
          aria-pressed={feedback?.vote === 'UP'} title="确认口径和结果正确；成功查询将保存为后续问数的参考样例"
          onClick={() => void submit('UP')}><Icon name="thumbsUp" size="sm" /><span>{feedback?.vote === 'UP' ? '已确认正确' : '正确'}</span></button>
        <button type="button" className="answer-feedback-vote" disabled={busy || !resolved}
          aria-pressed={feedback?.vote === 'DOWN'} title="反馈本次回答的口径或结果不正确"
          onClick={() => void submit('DOWN')}><Icon name="thumbsDown" size="sm" /><span>{feedback?.vote === 'DOWN' ? '已反馈不正确' : '不正确'}</span></button>
      </div>
    </div>
    <p className={`answer-feedback-note${feedback?.memoryStatus === 'FAILED' ? ' is-warning' : ''}`} role="status" aria-live="polite">
      {!busy && feedback?.vote && <Icon name={feedback.memoryStatus === 'FAILED' ? 'info' : 'check'} size="sm" />}
      <span>{busy ? '正在处理反馈…' : feedback?.vote ? feedback.message
        : '反馈可选 · 确认正确后，成功查询会保留为后续问数的参考样例。'}</span>
    </p>
    {error && <div className="answer-feedback-error" role="alert"><Icon name="warn" size="sm" /><span>{error}</span><button type="button" onClick={() => setRetry(v => v + 1)}>重试</button></div>}
  </div>;
}
