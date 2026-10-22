import { useState } from 'react';
import type { Confirmation, ConfirmationAnswer } from '../../api/draftReview';

type Choice = number | 'none' | 'own';

/**
 * spec092 §7: "需要你确定的部分" as one card inside the conversation, one question at a time like
 * a coding agent asking. The first option is recommended; answers are handed to the assistant
 * together once the last question is answered or left for later.
 */
export default function ConfirmationCards({ questions, onSubmit }: {
  questions: Confirmation[];
  onSubmit: (answers: ConfirmationAnswer[]) => void;
}) {
  const [index, setIndex] = useState(0);
  const [choice, setChoice] = useState<Choice>(0);
  const [own, setOwn] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [answers, setAnswers] = useState<ConfirmationAnswer[]>([]);
  const current = questions[index];
  if (!current) return null;

  const next = (answer: ConfirmationAnswer | null) => {
    const collected = answer ? [...answers, answer] : answers;
    if (index + 1 >= questions.length) { setAnswers([]); setIndex(0); onSubmit(collected); return; }
    setAnswers(collected); setIndex(index + 1); setChoice(0); setOwn(''); setError(null);
  };
  const confirm = () => {
    if (choice === 'own' && !own.trim()) { setError('写下你的口径，或选择其他选项'); return; }
    next({
      id: current.id,
      answer: choice === 'none' ? null : choice === 'own' ? own.trim() : current.options[choice].rule,
      dismissed: choice === 'none',
    });
  };
  const option = (key: Choice, title: string, detail?: string, recommended = false) => <label key={String(key)}
    style={{ display: 'flex', gap: 8, alignItems: 'flex-start', padding: '5px 8px', borderRadius: 6, cursor: 'pointer',
      background: choice === key ? 'color-mix(in srgb, var(--da-primary) 8%, transparent)' : undefined }}>
    <input type="radio" name={`confirm-${current.id}`} checked={choice === key} onChange={() => { setChoice(key); setError(null); }} style={{ marginTop: 3 }} />
    <span style={{ flex: 1, minWidth: 0 }}>{title}{recommended && <span className="da-badge" style={{ marginLeft: 6 }}>推荐</span>}
      {detail && <span style={{ display: 'block', fontSize: 12, color: 'var(--da-text-muted)' }}>{detail}</span>}</span>
  </label>;

  return <section aria-label="需要你确定的部分" style={{ border: '1px solid var(--da-primary)', borderRadius: 10, padding: '10px 12px', background: 'var(--da-surface, #fff)', fontSize: 13 }}>
    <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
      <strong>需要你确定</strong>
      <span style={{ fontSize: 12, color: 'var(--da-text-muted)' }}>{index + 1}/{questions.length} · 会影响统计结果</span>
    </div>
    <div style={{ margin: '6px 0 2px', fontWeight: 500 }}>{current.question}</div>
    {current.basis && <div style={{ fontSize: 12, color: 'var(--da-text-muted)', marginBottom: 4 }}>依据：{current.basis}</div>}
    {current.options.map((o, i) => option(i, o.label, o.rule, i === 0))}
    {option('own', '自己写')}
    {choice === 'own' && <textarea className="da-input" rows={2} maxLength={500} value={own} autoFocus
      onChange={e => { setOwn(e.target.value); setError(null); }} placeholder="写下你要的口径"
      style={{ width: '100%', boxSizing: 'border-box', marginTop: 4 }} />}
    {option('none', '不采用', '不设这条口径，删除这个问题')}
    {error && <div role="alert" style={{ fontSize: 12, color: 'var(--da-danger)' }}>{error}</div>}
    <div style={{ display: 'flex', gap: 8, alignItems: 'center', marginTop: 8 }}>
      <button className="da-btn da-btn-primary da-btn-sm" onClick={confirm}>确定</button>
      <button className="da-btn da-btn-sm" onClick={() => next(null)}>稍后</button>
      {answers.length > 0 && <span style={{ fontSize: 12, color: 'var(--da-text-muted)' }}>已确定 {answers.length} 题，最后一题后一起交给助手</span>}
    </div>
  </section>;
}
