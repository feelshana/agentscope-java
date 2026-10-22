import { useCallback, useEffect, useRef, useState } from 'react';
import { decideModelingQuestion, getModelingQuestions, getQuestionSuggestions, validateModelingQuestion } from '../../api/modelingQuestions';
import type { QuestionReview } from '../../api/modelingQuestions';
import { questionIds, questionRequest } from './QuestionIntakeForm';
import type { SubmittedQuestion } from './QuestionIntakeForm';
import ReadableCode from '../ReadableCode';

const message = (e: unknown) => e instanceof Error ? e.message : String(e);

/**
 * spec092 §7 "试问": ask the draft model a question. The assistant writes the SQL, the platform
 * runs it (both pass the time gate); a right answer becomes an example (knowledge/sql) that is
 * recalled after publish, a wrong one goes to the conversation.
 */
export default function TrialPanel({ groupId, assistantIdle, onAsk, onWrong, onClose, open, onExamples }: {
  groupId: string;
  assistantIdle: boolean;
  onAsk: (request: string, question: SubmittedQuestion) => boolean;
  onWrong: (text: string) => void;
  onClose: () => void;
  /** Suggestions are generated only once the user opens the panel. */
  open: boolean;
  /** Number of questions set as examples, shown on the closed panel's button. */
  onExamples?: (count: number) => void;
}) {
  const [suggestions, setSuggestions] = useState<string[] | null>(null);
  const [showSuggestions, setShowSuggestions] = useState(true);
  const [input, setInput] = useState('');
  const [reviews, setReviews] = useState<QuestionReview[]>([]);
  const [pending, setPending] = useState<SubmittedQuestion[]>([]);
  const [busy, setBusy] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const validated = useRef<Set<string>>(new Set());
  const active = useRef(groupId); active.current = groupId;

  const load = useCallback(async () => {
    try {
      const list = await getModelingQuestions(groupId);
      if (active.current === groupId) setReviews(list);
    } catch (e) { if (active.current === groupId) setError(message(e)); }
  }, [groupId]);
  useEffect(() => {
    setReviews([]); setPending([]); setSuggestions(null); setError(null); validated.current = new Set();
    void load();
    const update = (event: Event) => {
      if ((event as CustomEvent<{ groupId: string }>).detail?.groupId === groupId) void load();
    };
    window.addEventListener('modeling:updated', update);
    return () => window.removeEventListener('modeling:updated', update);
  }, [groupId, load]);

  // spec092 §7: suggestions cost a model call and take space, so they wait for the user to open 试问.
  useEffect(() => {
    if (!open || suggestions !== null) return;
    setSuggestions([]);
    void getQuestionSuggestions(groupId).then(list => { if (active.current === groupId) setSuggestions(list.slice(0, 3)); }).catch(() => undefined);
  }, [open, suggestions, groupId]);

  // Run each newly written SQL once; the server applies the same time gate as query sessions.
  useEffect(() => {
    if (!assistantIdle) return;
    const next = reviews.find(r => r.question.sql && !r.validation && r.status === 'DRAFT' && !validated.current.has(r.question.id));
    if (!next) return;
    validated.current.add(next.question.id);
    setBusy(next.question.id);
    void validateModelingQuestion(groupId, next.question.id)
      .then(updated => { if (active.current === groupId) setReviews(p => p.map(r => r.question.id === updated.question.id ? updated : r)); })
      .catch(e => { if (active.current === groupId) setError(message(e)); })
      .finally(() => { if (active.current === groupId) setBusy(null); });
  }, [reviews, assistantIdle, groupId]);

  const examples = reviews.filter(r => r.status === 'CONFIRMED').length;
  const reportExamples = useRef(onExamples); reportExamples.current = onExamples;
  useEffect(() => { reportExamples.current?.(examples); }, [examples]);

  const ask = (text: string) => {
    const question = text.trim();
    if (!question) return;
    const [id] = questionIds(1);
    if (onAsk(questionRequest([question], [id]), { id, question })) {
      setPending(p => [...p, { id, question }]);
      setSuggestions(s => s && s.filter(x => x !== question));
      setShowSuggestions(false);
      setInput('');
    }
  };
  const accept = async (review: QuestionReview) => {
    setBusy(review.question.id); setError(null);
    try {
      const updated = await decideModelingQuestion(groupId, review.question.id, true);
      if (active.current !== groupId) return;
      setReviews(p => p.map(r => r.question.id === updated.question.id ? updated : r));
      window.dispatchEvent(new CustomEvent('modeling:updated', { detail: { groupId } }));
    } catch (e) { if (active.current === groupId) setError(message(e)); }
    finally { if (active.current === groupId) setBusy(null); }
  };

  const known = new Set(reviews.map(r => r.question.id));
  const waiting = pending.filter(p => !known.has(p.id));
  const ordered = [...reviews].reverse();
  return <aside aria-label="试问" className="da-card" style={{ display: 'flex', flexDirection: 'column', gap: 10, overflowY: 'auto', minHeight: 0, width: '100%', boxSizing: 'border-box' }}>
    <div style={{ display: 'flex', alignItems: 'flex-start', gap: 8 }}>
      <div style={{ flex: 1 }}><strong>试问</strong><div className="da-small">用草稿回答问题，看看口径对不对。确认正确的问答发布后，问数时会参考。</div></div>
      <button className="da-btn da-btn-sm" aria-label="收起试问" onClick={onClose}>×</button>
    </div>
    {!!suggestions?.length && <div style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
      <button type="button" className="da-small" onClick={() => setShowSuggestions(!showSuggestions)}
        style={{ background: 'none', border: 'none', padding: 0, cursor: 'pointer', textAlign: 'left', color: 'var(--da-text-muted)' }}>
        {showSuggestions ? '▾' : '▸'} 推荐问题（{suggestions.length}）</button>
      {showSuggestions && suggestions.map(s => <button key={s} className="da-btn da-btn-sm" style={{ textAlign: 'left', whiteSpace: 'normal' }} onClick={() => ask(s)}>{s}</button>)}
    </div>}
    {suggestions !== null && suggestions.length === 0 && open && !reviews.length && <div className="da-small" role="status">正在生成推荐问题…</div>}
    <div style={{ display: 'flex', gap: 6 }}>
      <input className="da-input" style={{ flex: 1, minWidth: 0 }} value={input} maxLength={1000} placeholder="输入一个业务问题"
        onChange={e => setInput(e.target.value)} onKeyDown={e => { if (e.key === 'Enter' && !e.nativeEvent.isComposing) ask(input); }} />
      <button className="da-btn da-btn-primary" disabled={!input.trim()} onClick={() => ask(input)}>试问</button>
    </div>
    {error && <div role="alert" className="da-small" style={{ color: 'var(--da-danger)', whiteSpace: 'pre-wrap' }}>{error}</div>}
    {waiting.map(p => <div key={p.id} className="da-card" style={{ padding: 10 }}>
      <strong>{p.question}</strong><div className="da-small" role="status">{assistantIdle ? '排队中…' : '助手生成 SQL 中…'}</div>
    </div>)}
    {ordered.map(r => <TrialAnswer key={r.question.id} review={r} busy={busy === r.question.id}
      onAccept={() => void accept(r)}
      onWrong={() => onWrong(`试问“${r.question.question}”的回答不对（问题 ${r.question.id}）。当前口径：${r.question.definition || '无'}。当前 SQL：${r.question.sql}。`
        + '请先问清我哪里不对，再修正对应的模型说明或业务语义，并重写 knowledge/questions/' + r.question.id + '.yml 的 sql。')} />)}
  </aside>;
}

function TrialAnswer({ review, busy, onAccept, onWrong }: { review: QuestionReview; busy: boolean; onAccept: () => void; onWrong: () => void }) {
  const q = review.question;
  const result = review.validation?.result;
  const confirmed = review.status === 'CONFIRMED';
  return <div className="da-card" style={{ padding: 10, display: 'flex', flexDirection: 'column', gap: 6 }}>
    <strong>{q.question}</strong>
    {q.definition && <div className="da-small" style={{ whiteSpace: 'pre-wrap' }}>口径：{q.definition}</div>}
    {review.validation?.error && <div role="alert" className="da-small" style={{ color: 'var(--da-danger)', whiteSpace: 'pre-wrap' }}>{review.validation.error}</div>}
    {busy && !result && <div className="da-small" role="status">运行中…</div>}
    {result && <div style={{ overflowX: 'auto', maxHeight: 220 }}>
      <table className="da-table"><thead><tr>{result.columns.map(c => <th key={c}>{c}</th>)}</tr></thead>
        <tbody>{result.rows.slice(0, 10).map((row, i) => <tr key={i}>{result.columns.map(c => <td key={c}>{row[c] === null ? 'NULL' : String(row[c] ?? '')}</td>)}</tr>)}</tbody></table>
      {result.rows.length > 10 && <div className="da-small">共 {result.rows.length} 行，显示前 10 行</div>}
    </div>}
    {q.sql && <details><summary className="da-small">SQL</summary><ReadableCode text={q.sql} language="sql" title="SQL" /></details>}
    {confirmed
      ? <div className="da-small" style={{ color: 'var(--da-success)' }}>{review.published?.current ? '✓ 已确认正确并发布，问数时会参考' : '✓ 已确认正确，发布后问数时会参考'}</div>
      : q.sql && <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap' }}>
        <button className="da-btn da-btn-primary da-btn-sm" disabled={busy} onClick={onAccept}>确认正确</button>
        <button className="da-btn da-btn-sm" disabled={busy} onClick={onWrong}>不正确，告诉助手</button>
      </div>}
  </div>;
}
