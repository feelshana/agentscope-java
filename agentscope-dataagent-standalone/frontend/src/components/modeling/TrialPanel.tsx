import { useCallback, useEffect, useRef, useState } from 'react';
import { correctModelingQuestionSql, decideModelingQuestion, getModelingQuestions, getQuestionSuggestions } from '../../api/modelingQuestions';
import type { QuestionReview } from '../../api/modelingQuestions';
import { DEFINITION_FORMAT, questionIds, questionRequest } from './QuestionIntakeForm';
import type { SubmittedQuestion } from './QuestionIntakeForm';
import ReadableCode from '../ReadableCode';

const message = (e: unknown) => e instanceof Error ? e.message : String(e);

/**
 * spec092 §7 "试问": ask the draft model a question. The assistant writes the SQL and only plans
 * it (no data is read); the user reads the SQL and confirms it, and a confirmed answer becomes an
 * example (knowledge/sql) recalled after publish. A wrong one is fixed either by the assistant,
 * from the user's reason, or by the user editing the SQL.
 */
export default function TrialPanel({ groupId, assistantIdle, onAsk, onWrong, onClose, open, onExamples }: {
  groupId: string;
  assistantIdle: boolean;
  onAsk: (request: string, question: SubmittedQuestion) => boolean;
  /** Sends a question-turn request asking the assistant to fix one question's SQL. */
  onWrong: (text: string) => boolean;
  onClose: () => void;
  /** Suggestions are generated only once the user opens the panel. */
  open: boolean;
  /** Confirmed examples, and answered questions not confirmed yet (they are not published as examples). */
  onExamples?: (count: number, unconfirmed: number) => void;
}) {
  const [suggestions, setSuggestions] = useState<string[] | null>(null);
  const [showSuggestions, setShowSuggestions] = useState(true);
  const [input, setInput] = useState('');
  const [reviews, setReviews] = useState<QuestionReview[]>([]);
  const [pending, setPending] = useState<SubmittedQuestion[]>([]);
  const [busy, setBusy] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  // Questions handed to the assistant for fixing, with the revision they had at that moment.
  const [fixing, setFixing] = useState<Record<string, string>>({});
  const active = useRef(groupId); active.current = groupId;

  const load = useCallback(async () => {
    try {
      const list = await getModelingQuestions(groupId);
      if (active.current === groupId) setReviews(list);
    } catch (e) { if (active.current === groupId) setError(message(e)); }
  }, [groupId]);
  useEffect(() => {
    setReviews([]); setPending([]); setSuggestions(null); setError(null); setFixing({});
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

  // A fix is done once the question's revision changed (the assistant rewrote it).
  useEffect(() => {
    setFixing(previous => {
      const still = Object.fromEntries(Object.entries(previous).filter(([id, revision]) =>
        reviews.some(r => r.question.id === id && r.revision === revision)));
      return Object.keys(still).length === Object.keys(previous).length ? previous : still;
    });
  }, [reviews]);

  const examples = reviews.filter(r => r.status === 'CONFIRMED').length;
  const reportExamples = useRef(onExamples); reportExamples.current = onExamples;
  const unconfirmed = reviews.filter(r => r.status !== 'CONFIRMED' && r.question.sql).length;
  useEffect(() => { reportExamples.current?.(examples, unconfirmed); }, [examples, unconfirmed]);

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
  const askFix = (review: QuestionReview, reason: string) => {
    const q = review.question;
    const sent = onWrong(`试问"${q.question}"的回答不对（问题 ${q.id}）。我的说明：${reason.trim()}\n`
      + `当前口径：${q.definition || '无'}\n当前 SQL：${q.sql}\n`
      + '请按我的说明直接修正，不要再向我确认：口径属于业务定义时更新 knowledge/rules/ 或对应列描述；'
      + `然后重写 knowledge/questions/${q.id}.yml 的 definition 与 sql（${DEFINITION_FORMAT}），只用 wren_cli dry-plan 检查新 SQL，不查询数据。`
      + '完成后用一两行说明更新了哪些口径。');
    if (sent) setFixing(p => ({ ...p, [q.id]: review.revision }));
    return sent;
  };
  const saveSql = async (review: QuestionReview, sql: string) => {
    setBusy(review.question.id); setError(null);
    try {
      const updated = await correctModelingQuestionSql(groupId, review.question.id, review.revision, sql);
      if (active.current !== groupId) return false;
      setReviews(p => p.map(r => r.question.id === updated.question.id ? updated : r));
      window.dispatchEvent(new CustomEvent('modeling:updated', { detail: { groupId } }));
      return true;
    } catch (e) { if (active.current === groupId) setError(message(e)); return false; }
    finally { if (active.current === groupId) setBusy(null); }
  };

  const known = new Set(reviews.map(r => r.question.id));
  const waiting = pending.filter(p => !known.has(p.id));
  const ordered = [...reviews].reverse();
  return <aside aria-label="试问" className="da-card" style={{ display: 'flex', flexDirection: 'column', gap: 10, overflowY: 'auto', minHeight: 0, width: '100%', boxSizing: 'border-box' }}>
    <div style={{ display: 'flex', alignItems: 'flex-start', gap: 8 }}>
      <div style={{ flex: 1 }}><strong>试问</strong><div className="da-small">助手按草稿口径写出 SQL，你核对 SQL 是否正确。确认正确的问答发布后，问数时会参考。</div></div>
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
    {ordered.map(r => <TrialAnswer key={`${r.question.id}:${r.revision}`} review={r} busy={busy === r.question.id}
      fixing={r.question.id in fixing} assistantIdle={assistantIdle}
      onAccept={() => void accept(r)} onAskFix={reason => askFix(r, reason)} onSaveSql={sql => saveSql(r, sql)} />)}
  </aside>;
}

function TrialAnswer({ review, busy, fixing, assistantIdle, onAccept, onAskFix, onSaveSql }: {
  review: QuestionReview;
  busy: boolean;
  fixing: boolean;
  assistantIdle: boolean;
  onAccept: () => void;
  onAskFix: (reason: string) => boolean;
  onSaveSql: (sql: string) => Promise<boolean>;
}) {
  const q = review.question;
  const [mode, setMode] = useState<null | 'choose' | 'ai' | 'edit'>(null);
  const [reason, setReason] = useState('');
  const [sql, setSql] = useState(q.sql);
  const stale = review.status === 'STALE';
  // An old result belongs to the previous SQL; hide it until the new SQL has run.
  const result = stale ? null : review.validation?.result;
  const confirmed = review.status === 'CONFIRMED';
  const small = { fontSize: 12, color: 'var(--da-text-muted)' } as const;
  return <div className="da-card" style={{ padding: 10, display: 'flex', flexDirection: 'column', gap: 6 }}>
    <div style={{ display: 'flex', gap: 8, alignItems: 'flex-start' }}>
      <strong style={{ flex: 1, minWidth: 0 }}>{q.question}</strong>
      <span style={{ flex: 'none', fontSize: 11, padding: '1px 7px', borderRadius: 999,
        background: confirmed ? 'rgba(22,163,74,.12)' : 'var(--da-surface-sunken, #f1f5f9)',
        color: confirmed ? 'var(--da-success)' : 'var(--da-text-muted)' }}>{confirmed ? '已确认' : stale ? '待重新运行' : '待确认'}</span>
    </div>
    {q.definition && <Definition text={q.definition} />}
    {fixing && <div role="status" style={{ ...small, color: 'var(--da-primary)' }}>{assistantIdle ? '已交给助手，排队中…' : '助手正在按你的说明修正口径并重写 SQL…'}</div>}
    {stale && !fixing && <div role="status" style={{ ...small, color: 'var(--da-primary)' }}>SQL 已修改，请重新核对后确认</div>}
    {!stale && review.validation?.error && <div role="alert" className="da-small" style={{ color: 'var(--da-danger)', whiteSpace: 'pre-wrap' }}>{review.validation.error}</div>}
    {result && <div style={{ overflowX: 'auto', maxHeight: 220 }}>
      <table className="da-table"><thead><tr>{result.columns.map(c => <th key={c}>{c}</th>)}</tr></thead>
        <tbody>{result.rows.slice(0, 10).map((row, i) => <tr key={i}>{result.columns.map(c => <td key={c}>{row[c] === null ? 'NULL' : String(row[c] ?? '')}</td>)}</tr>)}</tbody></table>
      {result.rows.length > 10 && <div className="da-small">共 {result.rows.length} 行，显示前 10 行</div>}
    </div>}
    {q.sql && mode !== 'edit' && <ReadableCode text={q.sql} language="sql" title="SQL" />}
    {confirmed && <div className="da-small" style={{ color: 'var(--da-success)' }}>{review.published?.current ? '✓ 已确认正确并发布，问数时会参考' : '✓ 已确认正确，发布后问数时会参考'}</div>}
    {!confirmed && q.sql && !fixing && mode === null && <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap' }}>
      <button className="da-btn da-btn-primary da-btn-sm" disabled={busy} onClick={onAccept}>确认 SQL 正确</button>
      <button className="da-btn da-btn-sm" disabled={busy} onClick={() => setMode('choose')}>不正确</button>
      <span style={{ ...small, alignSelf: 'center' }}>确认正确后才会作为示例</span>
    </div>}
    {mode === 'choose' && <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap', alignItems: 'center' }}>
      <span style={small}>怎么修正？</span>
      <button className="da-btn da-btn-sm" onClick={() => setMode('ai')}>说明原因，让助手重新生成</button>
      <button className="da-btn da-btn-sm" onClick={() => { setSql(q.sql); setMode('edit'); }}>自己修改 SQL</button>
      <button className="da-btn da-btn-sm" onClick={() => setMode(null)}>取消</button>
    </div>}
    {mode === 'ai' && <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
      <textarea className="da-input" rows={3} autoFocus value={reason} maxLength={1000} onChange={e => setReason(e.target.value)}
        placeholder="哪里不对？例如：查看次数不需要限制 log_type；应按用户去重" style={{ width: '100%', boxSizing: 'border-box' }} />
      <div style={{ display: 'flex', gap: 6 }}>
        <button className="da-btn da-btn-primary da-btn-sm" disabled={!reason.trim()}
          onClick={() => { if (onAskFix(reason)) { setMode(null); setReason(''); } }}>交给助手修正</button>
        <button className="da-btn da-btn-sm" onClick={() => setMode('choose')}>返回</button>
      </div>
      <span style={small}>助手会按你的说明更新口径（规则或字段说明）并重写这道题的 SQL，完成后在这里刷新。</span>
    </div>}
    {mode === 'edit' && <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
      <textarea className="da-input" rows={8} autoFocus value={sql} maxLength={16000} onChange={e => setSql(e.target.value)}
        style={{ width: '100%', boxSizing: 'border-box', fontFamily: 'var(--da-mono, monospace)', fontSize: 12.5 }} />
      <div style={{ display: 'flex', gap: 6 }}>
        <button className="da-btn da-btn-primary da-btn-sm" disabled={busy || !sql.trim() || sql.trim() === q.sql.trim()}
          onClick={() => void onSaveSql(sql).then(ok => { if (ok) setMode(null); })}>{busy ? '保存中…' : '保存'}</button>
        <button className="da-btn da-btn-sm" onClick={() => setMode('choose')}>返回</button>
      </div>
      <span style={small}>只修改这道题的 SQL，不改模型和业务规则；保存后再确认。</span>
    </div>}
  </div>;
}

const DEFINITION_KEYS = ['指标', '粒度', '时间范围', '过滤', '假设'];
const PRIMARY_KEYS = new Set(['指标', '时间范围', '过滤']);

/** A question's definition as labelled lines; grain and assumptions fold away. */
function Definition({ text }: { text: string }) {
  const [more, setMore] = useState(false);
  const items: { key: string; value: string }[] = [];
  let unlabelled = '';
  for (const line of text.split(/\r?\n/).map(l => l.trim()).filter(Boolean)) {
    const match = line.match(/^[-·*\s]*(指标|粒度|时间范围|过滤|假设)\s*[：:]\s*(.*)$/);
    if (match) items.push({ key: match[1], value: match[2] });
    else if (items.length) items[items.length - 1].value += ` ${line}`;
    else unlabelled += (unlabelled ? ' ' : '') + line;
  }
  const clamp = { display: '-webkit-box', WebkitBoxOrient: 'vertical' as const, WebkitLineClamp: 3, overflow: 'hidden' };
  if (!items.length) return <div className="da-small" title={text} style={clamp}>口径：{unlabelled}</div>;
  items.sort((a, b) => DEFINITION_KEYS.indexOf(a.key) - DEFINITION_KEYS.indexOf(b.key));
  const hidden = items.filter(i => !PRIMARY_KEYS.has(i.key));
  const shown = more ? items : items.filter(i => PRIMARY_KEYS.has(i.key));
  return <div style={{ display: 'flex', flexDirection: 'column', gap: 4, padding: '8px 10px', borderRadius: 8, background: 'var(--da-surface-sunken, #f8fafc)' }}>
    {shown.map(i => <div key={i.key} style={{ display: 'flex', gap: 8, fontSize: 12, lineHeight: 1.55 }}>
      <span style={{ flex: '0 0 52px', color: 'var(--da-text-muted)' }}>{i.key}</span>
      <span title={i.value} style={{ flex: 1, minWidth: 0, color: 'var(--da-text-2, #334155)', ...(more ? {} : clamp) }}>{i.value || '无'}</span>
    </div>)}
    {hidden.length > 0 && <button type="button" onClick={() => setMore(v => !v)}
      style={{ alignSelf: 'flex-start', background: 'none', border: 'none', padding: 0, cursor: 'pointer', fontSize: 12, color: 'var(--da-primary)' }}>
      {more ? '收起' : `更多口径（${hidden.map(i => i.key).join('、')}）`}</button>}
  </div>;
}
