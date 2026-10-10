import { useEffect, useState } from 'react';
import { answerFeedback, AnswerFeedbackResult } from '../api/chat';
import { messagePage } from '../api/sessions';

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
  return <div style={{ marginTop: 16, padding: 12, border: '1px solid var(--da-border)', borderRadius: 8 }}>
    <strong>这次答案的口径和结果正确吗？</strong>
    <div style={{ display: 'flex', gap: 8, marginTop: 8 }}>
      <button className="da-btn" disabled={busy || !resolved} aria-pressed={feedback?.vote === 'UP'}
        onClick={() => void submit('UP')}>{feedback?.vote === 'UP' ? '👍 已确认正确' : '👍 正确'}</button>
      <button className="da-btn" disabled={busy || !resolved} aria-pressed={feedback?.vote === 'DOWN'}
        onClick={() => void submit('DOWN')}>{feedback?.vote === 'DOWN' ? '👎 已反馈不正确' : '👎 不正确'}</button>
    </div>
    <p className="da-small" role="status">{busy ? '正在处理…' : feedback?.vote ? feedback.message
      : '反馈可选。只有点击“正确”才会保存本轮成功业务查询的中文问题—SQL 样例，供后续问数参考。'}</p>
    {error && <div role="alert">{error} <button className="da-btn da-btn-sm" onClick={() => setRetry(v => v + 1)}>重试</button></div>}
  </div>;
}
