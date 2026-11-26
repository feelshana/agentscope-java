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
    className="mc-opt" data-on={choice === key}
    style={{ display: 'flex', gap: 9, alignItems: 'flex-start', padding: '7px 10px', borderRadius: 9, cursor: 'pointer', marginTop: 3 }}>
    <input type="radio" name={`confirm-${current.id}`} checked={choice === key} onChange={() => { setChoice(key); setError(null); }} style={{ marginTop: 3, accentColor: 'var(--da-primary)', flex: 'none' }} />
    <span style={{ flex: 1, minWidth: 0, fontSize: 13 }}>{title}{recommended && <span className="da-badge" style={{ marginLeft: 6 }}>推荐</span>}
      {detail && <span style={{ display: 'block', marginTop: 1, fontSize: 12, color: 'var(--da-text-muted)' }}>{detail}</span>}</span>
  </label>;

  return <section aria-label="需要你确定的部分" style={{
    border: '1px solid color-mix(in srgb, var(--da-primary) 20%, var(--da-border))',
    borderLeft: '3px solid var(--da-primary)',
    borderRadius: 12, padding: '12px 14px 13px',
    background: 'color-mix(in srgb, var(--da-primary) 3%, var(--da-surface))',
    fontSize: 13,
  }}>
    <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
      <strong style={{ fontSize: 13, fontWeight: 650 }}>需要你确定</strong>
      <span style={{ padding: '1px 8px', borderRadius: 999, background: 'var(--da-primary-subtle)', color: 'var(--da-primary)', fontSize: 11.5, fontWeight: 600, fontVariantNumeric: 'tabular-nums' }}>{index + 1}/{questions.length}</span>
      {current.source === 'caliber'
        ? <span style={{ padding: '1px 8px', borderRadius: 6, background: '#efeafd', color: '#5b45b8', fontSize: 11.5, fontWeight: 600 }}>口径清单 · 答复写回清单</span>
        : <span style={{ fontSize: 11.5, color: 'var(--da-text-muted)' }}>会影响统计结果</span>}
    </div>
    <div style={{ margin: '8px 0 3px', fontSize: 14, fontWeight: 550, lineHeight: 1.55 }}>{current.question}</div>
    {current.basis && <div style={{ fontSize: 12, color: 'var(--da-text-muted)', marginBottom: 4, lineHeight: 1.55 }}>依据：{current.basis}</div>}
    {current.options.map((o, i) => option(i, o.label, o.rule, i === 0))}
    {option('own', '自己写')}
    {choice === 'own' && <textarea className="da-input" rows={2} maxLength={500} value={own} autoFocus
      onChange={e => { setOwn(e.target.value); setError(null); }} placeholder="写下你要的口径"
      style={{ width: '100%', boxSizing: 'border-box', marginTop: 4 }} />}
    {option('none', '不采用', '不设这条口径，删除这个问题')}
    {error && <div role="alert" style={{ fontSize: 12, color: 'var(--da-danger)' }}>{error}</div>}
    <div style={{ display: 'flex', gap: 8, alignItems: 'center', marginTop: 10 }}>
      <button className="da-btn da-btn-primary da-btn-sm" onClick={confirm}>确定</button>
      <button className="da-btn da-btn-sm" onClick={() => next(null)}>稍后</button>
      {answers.length > 0 && <span style={{ fontSize: 12, color: 'var(--da-text-muted)' }}>已确定 {answers.length} 题，最后一题后一起交给助手</span>}
    </div>
  </section>;
}
