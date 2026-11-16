import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { decideModelingQuestion, editRecommendedQuestion, getModelingQuestions, getRecommendedTrialQuestion, planModelingQuestion, regenerateRecommendedQuestion } from '../../api/modelingQuestions';
import type { DraftPlan, QuestionReview, RecommendedQuestion } from '../../api/modelingQuestions';
import { decideAssetProposal, listAssetProposals } from '../../api/semanticAssets';
import type { AssetProposal } from '../../api/semanticAssets';
import { recommendedQuestion } from '../../api/chat';
import type { TurnPurpose } from '../../api/chat';
import { DEFINITION_FORMAT, questionRequest } from './QuestionIntakeForm';
import { proposalRequest } from './AssetProposalCards';
import SemanticContextPanel from './SemanticContextPanel';
import ConfirmationCards from './ConfirmationCards';
import { listDocuments, resolveConfirmations } from '../../api/draftReview';
import type { Confirmation, ConfirmationAnswer } from '../../api/draftReview';
import ReadableCode from '../ReadableCode';
import Icon from '../Icon';
import type { IconName } from '../Icon';
import type { ReactNode } from 'react';
import ModelingMethodFlow from './ModelingMethodFlow';
import './ModelingOptimization.css';

/** Coach questions keep this id prefix, so the list survives reloads without extra storage. */
const PREFIX = 'q-coach-';
const MAX_QUESTIONS = 10;
const KIND: Record<string, string> = { rule: '规则', relationship: '关系', calculated_field: '计算字段', view: '查询模板' };
const STEPS = ['分析', '口径/方案', 'SQL', '保存问答'] as const;
type Step = 0 | 1 | 2 | 3;
type Group = 'analyse' | 'meaning' | 'plan' | 'sql' | 'review' | 'done';
const GROUPS: { key: Group; label: string; icon: IconName }[] = [
  { key: 'analyse', label: '待分析', icon: 'search' }, { key: 'meaning', label: '待确认口径', icon: 'chat' }, { key: 'plan', label: '待批准方案', icon: 'list' },
  { key: 'sql', label: '待核对 SQL', icon: 'code' }, { key: 'review', label: '待复核', icon: 'refresh' }, { key: 'done', label: '已确认', icon: 'check' },
];
const message = (e: unknown) => e instanceof Error ? e.message : String(e);

/** The coach turn request: analyse each question, write its file, and propose what is missing. */
export function coachRequest(questions: string[], ids: string[], documents: string[] = []): string {
  // spec114: the documents are named outright; a prompt-only "also read raw/docs/" was skipped.
  const docs = documents.length
    ? `本知识库有 ${documents.length} 份业务文档：${documents.map(d => `raw/docs/${d}`).join('、')}。分析前先用 read_file 逐份读取；口径在文档里写明的，按文档提议规则并注明出处，不要再提问。\n`
    : '';
  return docs + '请以"问数优化"的方式分析以下常用问题。每题先判断现有模型、关系和业务规则能否稳定答对，'
    + `再写入 knowledge/questions/<ID>.yml（question 原文不改写、definition、sql、analysis；${DEFINITION_FORMAT}），`
    + '只用 wren_cli dry-plan 检查 SQL，不查询数据；对缺口用 propose_modeling_change 提议，question_ids 填题目 ID。\n'
    + questions.map((q, i) => `${i + 1}. [ID: ${ids[i]}] ${q}`).join('\n');
}

/** Approved rules, relationships and calculated fields, written in one ordinary modeling turn. */
function applyRequest(items: AssetProposal[]): string {
  return '请按以下已批准的建模方案写入草稿，不要再向我确认：\n'
    + items.map((p, i) => `${i + 1}.（${KIND[p.kind] ?? p.kind}）${p.name}：${p.need}；要点：${p.outline}`).join('\n')
    + '\n规则写入 knowledge/rules/general.md 的官方小节（默认过滤写在"## Default filters"，术语定义写在"## Naming conventions"）；'
    + '关系写入 relationships.yml；计算字段写入对应模型的 columns（is_calculated、expression）。写完执行 context validate --strict，完成后用一两行说明写了什么。';
}

interface QuestionState { label: string; group: Group; step: Step; conclusion: string }

/**
 * spec114 §8: one status line for every question, and the conclusion derived from what the
 * question is linked to (cards, proposals, SQL) — the assistant never writes the conclusion.
 */
export function questionState(r: QuestionReview | undefined, cards: number, linked: AssetProposal[]): QuestionState {
  if (!r) return { label: '分析中', group: 'analyse', step: 0, conclusion: '待分析' };
  if (cards) return { label: '待确认口径', group: 'meaning', step: 1, conclusion: '需要补充业务定义' };
  if (linked.some(p => p.status === 'PENDING')) return { label: '待批准方案', group: 'plan', step: 1, conclusion: '建议完善模型' };
  if (!r.question.sql) return linked.some(p => p.status === 'DRAFTED')
    ? { label: '待生成 SQL', group: 'sql', step: 2, conclusion: '建议完善模型' }
    : { label: '待分析', group: 'analyse', step: 0, conclusion: '待分析' };
  const answerable = '现有模型可以回答';
  if (r.status === 'REVIEW') return { label: '口径变更待复核', group: 'review', step: 2, conclusion: answerable };
  if (r.status === 'CONFIRMED')
    return { label: r.published?.current ? '已确认 · 已发布' : '已确认 · 待发布', group: 'done', step: 3, conclusion: answerable };
  return { label: r.status === 'STALE' ? '已修改，待重新核对' : '待核对 SQL', group: 'sql', step: 2, conclusion: answerable };
}

/**
 * spec114 / ADR0132 "问数优化": the data developer lists frequent questions; the assistant analyses
 * gaps and proposes rules, relationships, calculated fields or (for logic reused across the batch)
 * query templates; approved items are drafted and each question's SQL is read and confirmed — nothing
 * is executed. One question at a time on the right, publishing stays in the draft bar.
 */
export default function QueryCoachPanel({ groupId, assistantIdle, available, onRequest, confirmations = [], onAnswer, onOpenDocuments, trial }: {
  groupId: string;
  assistantIdle: boolean;
  /** The knowledge base has been published once; the coach builds on the basic model. */
  available: boolean;
  onRequest: (text: string, purpose?: TurnPurpose, label?: string) => boolean;
  /** spec114: open confirmation cards; those linked to a coach question are answered here too. */
  confirmations?: Confirmation[];
  onAnswer?: (answers: ConfirmationAnswer[]) => void;
  /** Business documents live on the "资料" tab; the coach only links to it. */
  onOpenDocuments?: () => void;
  trial?: ReactNode;
}) {
  const [reviews, setReviews] = useState<QuestionReview[]>([]);
  const [proposals, setProposals] = useState<AssetProposal[]>([]);
  const [submitted, setSubmitted] = useState<{ id: string; question: string }[]>([]);
  const [input, setInput] = useState('');
  const [approved, setApproved] = useState<Set<string>>(new Set());
  const [picked, setPicked] = useState<Set<string>>(new Set());
  const [filter, setFilter] = useState<Group | ''>('');
  const [selected, setSelected] = useState<string | null>(null);
  const [view, setView] = useState<Step | null>(null);
  const [plans, setPlans] = useState<Record<string, DraftPlan>>({});
  const [reason, setReason] = useState('');
  const [showContext, setShowContext] = useState(false);
  const [intakeOpen, setIntakeOpen] = useState(false);
  const intake = useRef<HTMLDialogElement>(null);
  const intakeInput = useRef<HTMLTextAreaElement>(null);
  const contextDialog = useRef<HTMLDialogElement>(null);
  useEffect(() => { if (!intake.current) return; if (intakeOpen && !intake.current.open) { intake.current.showModal(); intakeInput.current?.focus(); } else if (!intakeOpen && intake.current.open) intake.current.close(); }, [intakeOpen]);
  useEffect(() => { if (showContext && contextDialog.current && !contextDialog.current.open) contextDialog.current.showModal(); }, [showContext]);
  const [busy, setBusy] = useState('');
  const [error, setError] = useState('');
  const [documents, setDocuments] = useState<string[] | null>(null);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState('');
  const loadGeneration = useRef(0);
  /** Questions already asked elsewhere (trial questions, the recommended one) to start from. */
  const [suggestions, setSuggestions] = useState<string[]>([]);
  /** spec103: the knowledge base's one recommended question (prefilled on the query page). */
  const [recommended, setRecommended] = useState<RecommendedQuestion | null>(null);
  useEffect(() => { void listDocuments(groupId).then(list => setDocuments(list.filter(d => d.status !== 'DELETED').map(d => d.name))).catch(() => setDocuments(null)); }, [groupId]);
  const waitingFor = (id: string) => confirmations.filter(c => (c.questionIds ?? []).includes(id));

  const load = useCallback(() => {
    const generation = ++loadGeneration.current;
    const active = () => generation === loadGeneration.current;
    setLoading(true); setLoadError('');
    void getRecommendedTrialQuestion(groupId).then(value => { if (active()) setRecommended(value); }).catch(() => undefined);
    void Promise.all([getModelingQuestions(groupId), listAssetProposals(groupId)]).then(([list, proposed]) => {
      if (!active()) return;
      const coach = list.filter(r => r.question.id.startsWith(PREFIX));
      setReviews(coach); setProposals(proposed.filter(p => !p.explicit));
      const asked = list.filter(r => !r.question.id.startsWith(PREFIX)).map(r => r.question.question);
      void recommendedQuestion(groupId).catch(() => null).then(value => {
        if (!active()) return;
        const known = new Set(coach.map(r => r.question.question));
        setSuggestions([...new Set([...(value ? [value] : []), ...asked])].filter(q => !known.has(q)).slice(0, 6));
      });
    }).catch(e => { if (active()) setLoadError(message(e)); }).finally(() => { if (active()) setLoading(false); });
  }, [groupId]);
  useEffect(() => { load(); return () => { loadGeneration.current++; }; }, [load, assistantIdle]);
  useEffect(() => {
    const update = (event: Event) => {
      if ((event as CustomEvent<{ groupId: string }>).detail?.groupId === groupId) load();
    };
    window.addEventListener('modeling:updated', update);
    return () => window.removeEventListener('modeling:updated', update);
  }, [groupId, load]);

  const byId = useMemo(() => new Map(reviews.map(r => [r.question.id, r])), [reviews]);
  const linkedTo = (id: string) => proposals.filter(p => (p.questionIds ?? []).includes(id) && p.status !== 'DISMISSED');
  const stateOf = (id: string) => questionState(byId.get(id), waitingFor(id).length, linkedTo(id));
  const questions = useMemo(() => {
    const known = reviews.map(r => ({ id: r.question.id, question: r.question.question }));
    return [...known, ...submitted.filter(s => !byId.has(s.id))];
  }, [reviews, submitted, byId]);
  const states = new Map(questions.map(q => [q.id, stateOf(q.id)]));
  const shown = filter ? questions.filter(q => states.get(q.id)?.group === filter) : questions;
  const known = new Set(questions.map(q => q.id));
  const unlinked = proposals.filter(p => p.status === 'PENDING' && !(p.questionIds ?? []).some(id => known.has(id)));
  const current = selected ? questions.find(q => q.id === selected) : undefined;
  const run = async (name: string, action: () => Promise<void>) => {
    setBusy(name); setError('');
    try { await action(); } catch (e) { setError(message(e)); } finally { setBusy(''); }
  };
  const isRecommended = (text: string) => !!recommended?.question && recommended.question.trim() === text.trim();
  const setAsRecommended = (text: string) => {
    if (text.includes('\n') || text.trim().length > 200) { setError('推荐问题需为一行，不超过 200 字'); return; }
    if (recommended?.question && !window.confirm(`把推荐问题换成这道题？\n\n现在：${recommended.question}\n换成：${text.trim()}\n\n问数页和新手任务会立即使用新问题；之后发布不会覆盖，取消后恢复自动生成。`)) return;
    return run('recommend', async () => { setRecommended(await editRecommendedQuestion(groupId, text.trim())); });
  };
  const select = (id: string | null) => { setSelected(id); setView(null); setReason(''); };

  const analyse = () => {
    const lines = input.split('\n').map(l => l.trim()).filter(Boolean);
    if (!lines.length) return;
    if (lines.length > MAX_QUESTIONS) { setError(`一次最多 ${MAX_QUESTIONS} 个问题`); return; }
    const stamp = Date.now().toString(36);
    const ids = lines.map((_, i) => `${PREFIX}${stamp}-${i + 1}`);
    if (onRequest(coachRequest(lines, ids, documents ?? []), 'coach', `问数优化：分析 ${lines.length} 个常用问题`)) {
      setSubmitted(all => [...all, ...ids.map((id, i) => ({ id, question: lines[i] }))]);
      setInput(''); setIntakeOpen(false); select(ids[0]);
    }
  };
  const reanalyse = (list: { id: string; question: string }[]) => {
    if (!list.length) return;
    if (list.length > MAX_QUESTIONS) { setError(`一次最多 ${MAX_QUESTIONS} 个问题`); return; }
    if (onRequest(coachRequest(list.map(q => q.question), list.map(q => q.id), documents ?? []), 'coach',
      list.length === 1 ? `问数优化：重新分析 ${list[0].question}` : `问数优化：分析选中的 ${list.length} 个问题`)) setPicked(new Set());
  };
  const draftApproved = (scope: AssetProposal[]) => run('draft', async () => {
    const chosen = scope.filter(p => p.status === 'PENDING' && approved.has(p.id));
    if (!chosen.length) return;
    const others = chosen.filter(p => p.kind !== 'view');
    if (others.length && !onRequest(applyRequest(others), undefined, `问数优化：写入 ${others.length} 项已批准的方案`)) return;
    for (const view of chosen.filter(p => p.kind === 'view'))
      if (!onRequest(proposalRequest(view), 'asset', `问数优化：起草查询模板 ${view.name}`)) return;
    for (const p of chosen) await decideAssetProposal(groupId, p.id, 'DRAFTED');
    setApproved(all => { const next = new Set(all); chosen.forEach(p => next.delete(p.id)); return next; });
    load();
  });
  const dismiss = (p: AssetProposal) => run('dismiss', async () => { await decideAssetProposal(groupId, p.id, 'DISMISSED'); load(); });
  const generate = (list: { id: string; question: string }[], label: string) => {
    if (list.length) onRequest(questionRequest(list.map(q => q.question), list.map(q => q.id)), 'question', label);
  };
  const expand = (id: string) => run('plan', async () => { const plan = await planModelingQuestion(groupId, id); setPlans(all => ({ ...all, [id]: plan })); });
  const confirm = (id: string) => run('confirm', async () => {
    await decideModelingQuestion(groupId, id, true);
    load();
    window.dispatchEvent(new CustomEvent('modeling:updated', { detail: { groupId } }));
  });
  const fix = async (r: QuestionReview) => {
    if (!reason.trim()) return;
    const q = r.question;
    // spec114: explaining the meaning here answers the open card, so it must not linger.
    const open = waitingFor(q.id);
    if (open.length) {
      try { await resolveConfirmations(groupId, open.map(c => ({ id: c.id, answer: null, dismissed: true }))); }
      catch (e) { setError(message(e)); return; }
      window.dispatchEvent(new CustomEvent('modeling:updated', { detail: { groupId } }));
    }
    if (onRequest(`试问"${q.question}"的回答不对（问题 ${q.id}）。我的说明：${reason.trim()}\n当前口径：${q.definition || '无'}\n当前 SQL：${q.sql || '无'}\n`
      + `请按我的说明直接修正：口径属于业务定义时更新 knowledge/rules/ 或对应列描述；然后重写 knowledge/questions/${q.id}.yml 的 definition 与 sql（${DEFINITION_FORMAT}），只用 wren_cli dry-plan 检查新 SQL，不查询数据。`,
      'question', `问数优化：修正 ${q.question}`)) setReason('');
  };

  if (!available) return <section className="sm-pane qc-pane mo-optimization"><div className="da-card sm-empty">
    完成首次建模并发布后，可以在这里列出业务常问的问题，由助手分析缺少的口径并提议，你批准后再起草。</div></section>;

  const proposalCards = (list: AssetProposal[]) => <div className="qc-proposals">
    {list.map(p => {
      const served = (p.questionIds ?? []).filter(id => known.has(id));
      return <div key={p.id} className={`qc-proposal ${p.status !== 'PENDING' ? 'qc-done' : ''}`}>
        <div className="qc-proposal-head">
          {p.status === 'PENDING' ? <input type="checkbox" aria-label={`批准 ${p.name}`} checked={approved.has(p.id)}
            onChange={e => setApproved(all => { const next = new Set(all); if (e.target.checked) next.add(p.id); else next.delete(p.id); return next; })} />
            : <span className="sm-tag sm-tag-plain">已起草</span>}
          <span className="sm-tag">{KIND[p.kind] ?? p.kind}</span><b>{p.name}</b>
          {p.status === 'PENDING' && <button type="button" className="sm-text-link" onClick={() => dismiss(p)}>不需要</button>}
        </div>
        <div className="da-small">{p.need}</div>
        <div className="da-small">要点：{p.outline}</div>
        <div className="da-small">原因：{p.reason}</div>
        {served.length > 1 && <div className="da-small">同时服务 {served.length} 道题：{served.map(id => questions.find(q => q.id === id)?.question ?? id).join('；')}</div>}
      </div>;
    })}
    {list.some(p => p.status === 'PENDING') && <div className="sa-actions">
      <button className="da-btn da-btn-primary da-btn-sm" disabled={!list.some(p => approved.has(p.id)) || !assistantIdle || !!busy}
        onClick={() => void draftApproved(list)}>按批准的方案起草</button>
      <span className="da-small">查询模板起草后到"公共视图"页核查；规则、关系、计算字段进入草稿栏。</span>
    </div>}
  </div>;

  const workbench = () => {
    if (!current) return null;
    const r = byId.get(current.id);
    const state = stateOf(current.id);
    const step = view ?? state.step;
    const cards = waitingFor(current.id);
    const linked = linkedTo(current.id);
    const next = state.step > step ? state.step : null;
    // The assistant sometimes lists a gap yet settles it by assumption without a card or proposal
    // (2026-11-03, twice); such items are shown as assumptions so they never contradict the conclusion.
    const gaps = r?.analysis?.filter(f => !f.ok).length ?? 0;
    const assumed = gaps > 0 && !cards.length && !linked.some(p => p.status === 'PENDING');
    return <>
      <div className="qc-bench-head"><b>{current.question}</b><span className="sm-tag sm-tag-plain">{state.label}</span></div>
      <ol className="qc-steps" aria-label="处理进度">
        {STEPS.map((name, i) => <li key={name}><button type="button" disabled={i > state.step}
          className={`qc-step ${i === step ? 'qc-step-on' : ''} ${i < state.step ? 'qc-step-done' : ''}`}
          aria-current={i === step ? 'step' : undefined} onClick={() => setView(i as Step)}>{i + 1} {name}</button></li>)}
      </ol>
      {step === 0 && <div className="qc-step-body">
        {!r ? <div className="da-small">助手正在分析这道题，完成后自动刷新。</div> : <>
          <strong>现有能力分析</strong>
          {r.analysis?.length ? <ul className="qc-findings">{r.analysis.map(f => <li key={f.text} className={f.ok ? 'qc-ok' : 'qc-gap'}>
            <span aria-hidden="true">{f.ok ? '✓' : '!'}</span>{f.text}{!f.ok && assumed && <span className="qc-assumed">按假设处理</span>}</li>)}</ul>
            : <div className="da-small">助手没有写这道题的能力分析，可以点"重新分析"。</div>}
          <div className="qc-conclusion">结论：{state.conclusion}{assumed ? `（有 ${gaps} 处助手按假设处理、没有提问，见口径说明的"假设"行，核对 SQL 时请留意；不认可可在 SQL 步骤说明）` : ''}</div>
          {r.question.definition && <pre className="da-small qc-definition">{r.question.definition}</pre>}
        </>}
        <div className="sa-actions">
          {next !== null && <button type="button" className="da-btn da-btn-primary da-btn-sm" onClick={() => setView(next)}>
            {next === 1 ? (cards.length ? '下一步：确认口径' : '下一步：批准方案') : next === 2 ? '下一步：核对 SQL' : '查看问答'}</button>}
          {r && state.group !== 'done' && <button type="button" className="da-btn da-btn-sm" disabled={!assistantIdle}
            onClick={() => reanalyse([current])}>重新分析</button>}
        </div>
      </div>}
      {step === 1 && <div className="qc-step-body">
        {cards.length > 0 && <div className="qc-waiting">
          <div className="da-small">这道题的口径还没定，先确认口径再写 SQL。可以在这里回答，或上传写明口径的业务文档后点"重新分析"，也可以在 SQL 步骤直接说明口径。</div>
          <ConfirmationCards key={cards.map(c => c.id).join(',')} questions={cards} onSubmit={answers => onAnswer?.(answers)} />
          <div className="sa-actions">
            {onOpenDocuments && <button type="button" className="da-btn da-btn-sm" onClick={onOpenDocuments}>上传业务文档</button>}
            <button type="button" className="da-btn da-btn-sm" disabled={!assistantIdle} onClick={() => reanalyse([current])}>重新分析</button>
          </div>
        </div>}
        {linked.length > 0 ? proposalCards(linked)
          : !cards.length && <div className="da-small">这道题不需要补口径或改模型。</div>}
      </div>}
      {step === 2 && r && <div className="qc-step-body">
        {r.reviewReasons?.length ? <ul className="da-small">{r.reviewReasons.map(x => <li key={x}>{x}</li>)}</ul> : null}
        {r.question.definition && <pre className="da-small qc-definition">{r.question.definition}</pre>}
        {!r.question.sql ? <div className="sa-actions">
          <span className="da-small">批准的方案已起草，按新口径生成这道题的 SQL。</span>
          <button className="da-btn da-btn-primary da-btn-sm" disabled={!assistantIdle} onClick={() => generate([current], `问数优化：生成 ${current.question} 的 SQL`)}>生成 SQL</button>
        </div> : <>
          <ReadableCode text={r.question.sql} language="sql" title="问数助手写的 SQL" />
          <div className="sa-actions">
            <button className="da-btn da-btn-sm" disabled={!!busy} onClick={() => expand(current.id)}>{busy === 'plan' ? '展开中…' : '查看展开后的 SQL'}</button>
            {r.status !== 'CONFIRMED' && <button className="da-btn da-btn-primary da-btn-sm" disabled={!!busy} onClick={() => confirm(current.id)}>确认 SQL 正确</button>}
          </div>
        </>}
        {plans[current.id] && (plans[current.id].error
          ? <div role="alert" className="sm-issue">{plans[current.id].error}</div>
          : <ReadableCode text={plans[current.id].expanded ?? ''} language="sql" title="展开后的 SQL（Wren 实际发给数据库的语句，未执行）" />)}
        {fixBox(r)}
      </div>}
      {step === 3 && r && <div className="qc-step-body">
        <div className="qc-conclusion">已确认，保存为问答{r.validation?.confirmedAt ? `（${r.validation.confirmedBy ?? ''} ${new Date(r.validation.confirmedAt).toLocaleString()}）` : ''}。</div>
        <div className="da-small">{r.published?.current ? '已发布，问数时可以被召回。' : '待发布：在页面底部草稿栏统一发布后，问数时才会召回。'}</div>
        <div className="qc-recommend">
          {isRecommended(r.question.question)
            ? <><span className="sm-tag qc-recommend-tag">推荐问题</span>
              <span className="da-small">问数页选中这个知识库时会预填这道题，新手任务也用它做起步问题。</span>
              <button type="button" className="sm-text-link" disabled={!!busy} onClick={() => void run('recommend', async () => {
                setRecommended(await regenerateRecommendedQuestion(groupId));
              })}>{busy === 'recommend' ? '恢复中…' : '取消，恢复自动生成'}</button></>
            : <><button type="button" className="da-btn da-btn-sm" disabled={!!busy} onClick={() => void setAsRecommended(r.question.question)}>
              {busy === 'recommend' ? '设置中…' : '设为推荐问题'}</button>
              <span className="da-small">{recommended?.question ? `替换当前推荐问题「${recommended.question}」` : '问数页选中这个知识库时会预填这道题'}</span></>}
        </div>
        <ReadableCode text={r.question.sql} language="sql" title="已确认的 SQL" />
        {fixBox(r)}
      </div>}
    </>;
  };
  const fixBox = (r: QuestionReview) => <details className="qc-fix"><summary>不正确？说明原因，让助手修正口径并重写 SQL</summary>
    <textarea className="da-input" rows={2} value={reason} onChange={e => setReason(e.target.value)} placeholder="不正确？说明原因，让助手修正口径并重写 SQL" />
    <button className="da-btn da-btn-sm" disabled={!reason.trim() || !assistantIdle} onClick={() => void fix(r)}>交给助手修正</button>
  </details>;

  const pickable = (id: string) => states.get(id)?.group !== 'done' && byId.has(id);
  const addSuggestion = (q: string) => setInput(text => {
    const lines = text.split('\n').map(l => l.trim()).filter(Boolean);
    return lines.includes(q) ? text : [...lines, q].join('\n');
  });
  const emptyGuidance = <div className="qc-empty">
    <strong className="qc-empty-title">从业务最常问的问题开始</strong>
    <p>挑业务同事经常问、你又担心答不准的问题。比如带"活跃""流失""有效"这类口径词的，要跨表统计的，或者要排名、算占比的。</p>
    {suggestions.length > 0 && <>
      <div className="da-small">这些问题已经有人问过或试问过，点一下加入左侧：</div>
      <div className="qc-chips">{suggestions.map(q => <button key={q} type="button" className="qc-chip"
        disabled={input.split('\n').map(l => l.trim()).includes(q)} onClick={() => { addSuggestion(q); setIntakeOpen(true); }}>＋ {q}</button>)}</div>
    </>}
    <ul className="qc-empty-tips">
      <li><b>能答对的题</b>：直接给出 SQL，你确认后存为问答样例，以后同类问题照着答。</li>
      <li><b>答不对的题</b>：助手会说明缺什么（某个口径没定义、两张表没关联上），请你回答或批准补充。</li>
      <li><b>不会乱改模型</b>：没有你的批准不会写规则；这里不建视图，多道题反复用到的取数逻辑以后在"公共视图"统一整理。</li>
    </ul>
  </div>;
  const overview = <section className="qc-overview" aria-label="处理进度">
    <header><h3>处理进度</h3><span>共 {questions.length} 个业务问题</span></header>
    <div className="qc-counts">{GROUPS.map(g => {
      const n = questions.filter(q => states.get(q.id)?.group === g.key).length;
      return <button key={g.key} type="button" className={`qc-count qc-count-${g.key} ${filter === g.key ? 'qc-count-on' : ''}`} disabled={!n}
        aria-label={`${g.label} ${n} 个`} title={n ? `只看${g.label}的题` : undefined} onClick={() => setFilter(filter === g.key ? '' : g.key)}><span className="qc-count-icon"><Icon name={g.icon} size="sm" /></span><b>{n}</b><span>{g.label}</span></button>;
    })}</div>
    <div className="da-small">点左侧任意一道题开始处理；点上面的数字可以只看某一类题。</div>
    {questions.length === 0 && emptyGuidance}
    {(picked.size > 0 || questions.some(q => ['sql', 'review'].includes(states.get(q.id)?.group ?? ''))) && <div className="sa-actions">
      {picked.size > 0 && <button className="da-btn da-btn-primary da-btn-sm" disabled={!assistantIdle}
        onClick={() => reanalyse(questions.filter(q => picked.has(q.id)))}>重新分析勾选的 {picked.size} 道题</button>}
      {questions.some(q => ['sql', 'review'].includes(states.get(q.id)?.group ?? '')) && <button className="da-btn da-btn-sm" disabled={!assistantIdle}
        title="规则或关系改过之后，用最新口径重写还没确认的 SQL"
        onClick={() => generate(questions.filter(q => ['sql', 'review'].includes(states.get(q.id)?.group ?? '')), '问数优化：按新口径重新生成 SQL')}>按最新口径重写待核对的 SQL</button>}
    </div>}
    {unlinked.length > 0 && <><strong>还没有对应问题的提议</strong>{proposalCards(unlinked)}</>}
  </section>;

  return <section className="sm-pane qc-pane mo-optimization" aria-label="问数优化">
    <header className="mo-page-head"><div><h2>问数优化</h2><p>用业务最常问的问题检查模型：补齐口径、核对 SQL，再把正确的问答保存为示例。</p></div>
      <div className="mo-head-actions"><button type="button" className="da-btn" onClick={() => setShowContext(true)}><Icon name="book" size="sm" />MDL 与规则</button>
        <button type="button" className="da-btn da-btn-primary" onClick={() => setIntakeOpen(true)}><Icon name="plus" size="sm" />新增业务问题</button></div>
    </header>
    <div className={`qc-introduction ${trial ? '' : 'qc-introduction-only'}`}><ModelingMethodFlow kind="coach" compact onAdd={() => setIntakeOpen(true)} />{trial}</div>
    <div className="mo-notice"><Icon name="info" size="sm" /><span>只检查 SQL 怎么写，不查询数据；所有改动先进草稿，在底部草稿栏发布后问数才会用到。
      {documents !== null && (documents.length ? `已有 ${documents.length} 份业务文档，文档里写明的口径会直接采用。` : '还没有业务文档：口径写在文档里时助手会直接采用，不用逐条问你。')}</span>
      {onOpenDocuments && <button type="button" className="sm-text-link" onClick={onOpenDocuments}>{documents?.length ? '管理文档' : '上传文档'}</button>}
    </div>
    {error && <div role="alert" className="da-alert da-alert-error">{error}</div>}
    {loadError && <div role="alert" className="mo-notice mo-error"><span>问题列表读取失败：{loadError}</span><button type="button" className="da-btn" onClick={load}>重试</button></div>}
    {showContext && <dialog ref={contextDialog} className="mo-form-dialog" aria-label="问数助手依据的内容" onClose={() => setShowContext(false)}>
      <header className="mo-dialog-head"><strong>问数助手依据的内容</strong><button type="button" className="da-btn" onClick={() => { contextDialog.current?.close(); setShowContext(false); }}>关闭</button></header><SemanticContextPanel groupId={groupId} />
    </dialog>}
    <dialog ref={intake} className="mo-form-dialog" aria-labelledby="mo-coach-intake-title" onClose={() => setIntakeOpen(false)}>
      <header className="mo-dialog-head"><strong id="mo-coach-intake-title">写下业务常问的问题</strong><button type="button" className="da-btn" onClick={() => { intake.current?.close(); setIntakeOpen(false); }}>关闭</button></header>
      <div className="mo-dialog-body"><p className="da-small">像业务同事那样提问，每行一个，一次最多 10 个。</p>
        <textarea ref={intakeInput} className="da-input" rows={6} value={input} onChange={e => setInput(e.target.value)} aria-label="添加业务问题" placeholder={'上个月各品类的净销售额是多少？\n最近 30 天的流失用户有哪些'} />
        {error && <p className="mo-error" role="alert">{error}</p>}
        {suggestions.length > 0 && <div className="qc-chips">{suggestions.map(q => <button key={q} type="button" className="qc-chip" disabled={input.split('\n').map(l => l.trim()).includes(q)} onClick={() => addSuggestion(q)}>＋ {q}</button>)}</div>}
      </div><footer className="mo-dialog-footer"><button type="button" className="da-btn" onClick={() => { intake.current?.close(); setIntakeOpen(false); }}>取消</button><button type="button" className="da-btn da-btn-primary" disabled={!input.trim() || !assistantIdle} onClick={analyse}>{assistantIdle ? '让助手逐题检查' : '助手正在处理…'}</button></footer>
    </dialog>
    <div className="qc-layout">
      <div className="da-card qc-questions">
        <div className="qc-list-head">{current ? <button type="button" className="qc-back-overview" onClick={() => select(null)}><Icon name="back" size="sm" />返回处理概况</button> : <strong>业务问题</strong>}<span className="da-small">{loading && !questions.length ? '读取中…' : `${questions.length} 个`}</span>
          <select className="da-input qc-filter" aria-label="按状态筛选" value={filter} onChange={e => setFilter(e.target.value as Group | '')}>
            <option value="">全部状态</option>{GROUPS.map(g => <option key={g.key} value={g.key}>{g.label}</option>)}
          </select><button type="button" className="da-btn da-btn-primary qc-add-question" onClick={() => setIntakeOpen(true)}><Icon name="plus" size="sm" />添加问题</button></div>
        <ul className="qc-question-list">
          {shown.map(q => <li key={q.id} className={`qc-item ${selected === q.id ? 'qc-item-on' : ''}`}>
            <input type="checkbox" aria-label={`选中 ${q.question}`} disabled={!pickable(q.id)} checked={picked.has(q.id)}
              onChange={e => setPicked(all => { const next = new Set(all); if (e.target.checked) next.add(q.id); else next.delete(q.id); return next; })} />
            <button type="button" className="qc-item-main" onClick={() => select(selected === q.id ? null : q.id)}>
              <span className="qc-item-name">{q.question}</span>
              <span className={`qc-item-status qc-status-${states.get(q.id)?.group}`}>{states.get(q.id)?.label}{isRecommended(q.question) && <span className="sm-tag qc-recommend-tag">推荐问题</span>}</span>
            </button>
            {states.get(q.id)?.group === 'done' && (isRecommended(q.question)
              ? <button type="button" className="qc-item-action" disabled={!!busy} title="恢复为平台自动生成的推荐问题"
                onClick={() => void run('recommend', async () => { setRecommended(await regenerateRecommendedQuestion(groupId)); })}>取消推荐</button>
              : <button type="button" className="qc-item-action" disabled={!!busy}
                title={recommended?.question ? `替换当前推荐问题「${recommended.question}」` : '问数页选中这个知识库时预填这道题'}
                onClick={() => void setAsRecommended(q.question)}>设为推荐问题</button>)}</li>)}
        </ul>
        {!shown.length && !loading && !loadError && <p className="qc-list-empty">{questions.length ? '当前状态下没有业务问题' : '添加业务问题，开始检查口径'}</p>}
      </div>
      <div className="da-card qc-bench">{current ? workbench() : loadError && !questions.length ? <p className="da-small">问题列表暂时未能加载，请重试。</p> : loading && !questions.length ? <p className="da-small" role="status">正在读取业务问题…</p> : overview}</div>
    </div>
  </section>;
}
