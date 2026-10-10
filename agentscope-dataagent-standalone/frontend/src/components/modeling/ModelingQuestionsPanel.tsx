import { useCallback, useEffect, useRef, useState } from 'react';
import { archiveModelingQuestion, correctModelingQuestionSql, decideModelingQuestion, getModelingQuestions, validateModelingQuestion } from '../../api/modelingQuestions';
import type { QuestionReview } from '../../api/modelingQuestions';
import { validateMdl } from '../../api/semanticModeling';
import AddQuestionsDialog from './AddQuestionsDialog';
import type { ModelingWorkflow } from '../../api/modelingWorkflow';
import ReadableCode from '../ReadableCode';

const LABEL: Record<QuestionReview['status'], string> = {
  DRAFT: '待完善与验证', EXECUTED: '待业务确认', CONFIRMED: '已确认',
  STALE: '已过期，需重验', FAILED: '验证失败', REJECTED: '已退回',
};

export default function ModelingQuestionsPanel({ groupId, onDiscuss, onAddQuestions, hideAdd = false, workflow, onPublish, onQuery, mode = 'acceptance' }: {
  groupId: string;
  onDiscuss: (message: string) => void;
  onAddQuestions?: (message: string) => void;
  hideAdd?: boolean;
  workflow?: ModelingWorkflow | null;
  onPublish?: () => void;
  onQuery?: () => void;
  mode?: 'requirements' | 'acceptance';
}) {
  const [reviews, setReviews] = useState<QuestionReview[]>([]);
  const [selectedId, setSelectedId] = useState<string | null>(null);

  const [busy, setBusy] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [adding, setAdding] = useState(false);
  const [editing, setEditing] = useState<{ id: string; revision: string; sql: string } | null>(null);
  const [removing, setRemoving] = useState<string | null>(null);
  const generation = useRef(0);
  const activeGroup = useRef(groupId);
  activeGroup.current = groupId;
  const requirements = mode === 'requirements';
  const [progress, setProgress] = useState('');
  const refresh = useCallback(async () => {
    const current = ++generation.current;
    try {
      const value = await getModelingQuestions(groupId);
      if (generation.current === current) setReviews(value);
    } catch (e) {
      if (generation.current === current) setError(e instanceof Error ? e.message : '加载失败');
    }
  }, [groupId]);

  useEffect(() => {
    setReviews([]); setSelectedId(null); setBusy(null); setError(null); setNotice(null); setAdding(false); setEditing(null); setRemoving(null);
    void refresh();
    const update = (event: Event) => {
      if ((event as CustomEvent<{ groupId: string }>).detail?.groupId === groupId) void refresh();
    };
    window.addEventListener('modeling:updated', update);
    return () => { generation.current++; window.removeEventListener('modeling:updated', update); };
  }, [groupId, refresh]);

  async function act(id: string, action: () => Promise<QuestionReview>) {
    setBusy(id); setError(null); setNotice(null);
    try {
      const updated = await action();
      if (activeGroup.current !== groupId) return;
      generation.current++;
      setReviews(previous => previous.map(review => review.question.id === id ? updated : review));
      setNotice(updated.status === 'CONFIRMED' ? '确认已保存。当前草稿的口径与结果已确认，发布后用于问数。'
        : updated.status === 'REJECTED' ? '已退回。可以修改本题 SQL；如果业务模型有误，请单独提出模型修复。'
        : updated.status === 'FAILED' ? '验证失败，请查看问题卡中的错误并修正。' : '验证已完成，请审阅口径、SQL 依据与实际结果。');
      window.dispatchEvent(new CustomEvent('modeling:updated', { detail: { groupId } }));
    } catch (e) { if (activeGroup.current === groupId) setError(e instanceof Error ? e.message : '操作失败'); }
    finally { if (activeGroup.current === groupId) setBusy(null); }
  }

  async function validateAll() {
    let failure: string | null = null;
    setBusy('batch'); setError(null); setProgress('正在校验模型结构…');
    try {
      const engineering = await validateMdl(groupId);
      if (activeGroup.current !== groupId) return;
      if (!engineering.ok) throw new Error('模型结构校验未通过：' + engineering.issues.map(i => i.message).join('；'));
      const latest = await getModelingQuestions(groupId);
      if (activeGroup.current !== groupId) return;
      const pending = latest.filter(r => r.question.definition && r.question.sql && !['CONFIRMED', 'EXECUTED'].includes(r.status));
      const failures: string[] = [];
      for (const review of pending) {
        if (activeGroup.current !== groupId) return;
        setProgress(`正在验证：${review.question.question}`);
        try { const result = await validateModelingQuestion(groupId, review.question.id); if (result.status === 'FAILED') failures.push(result.validation?.error || '问题验证失败'); }
        catch (e) { failures.push(e instanceof Error ? e.message : '问题验证失败'); }
      }
      if (failures.length) failure = failures.join('；');
    } catch (e) { failure = e instanceof Error ? e.message : '验证失败'; }
    finally {
      if (activeGroup.current === groupId) {
        await refresh();
        if (failure) setError(failure);
        window.dispatchEvent(new CustomEvent('modeling:updated', { detail: { groupId } }));
        setBusy(null); setProgress('');
      }
    }
  }
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 14 }}>
      <div className="da-card" style={{ padding: 16 }}>
        <div style={{ display: 'flex', gap: 10, alignItems: 'center', justifyContent: 'space-between', flexWrap: 'wrap' }}>
          <div className="da-h2">{requirements ? '本次分析问题' : '验证已有问题并确认结果'}</div>
          {!hideAdd && <button className="da-btn da-btn-primary" disabled={busy !== null} onClick={() => setAdding(true)}>＋ 添加问题</button>}
        </div>
        <p className="da-small">{requirements ? '可选问题保存后出现在这里，也可直接对话说明业务目标。共用口径只需澄清一次。' : '这里展示已登记的可选问题。需要保存建模示例时，可用 Wren 查询草稿并审阅结果；不测试也可发布模型。'}</p>
        {!requirements && <p className="da-small">
          执行成功只说明查询可运行。请检查指标、粒度、单位、时间和过滤是否符合业务，再确认。
          模型或问题变化后需重新验证。
        </p>}
        {!requirements && <div className="da-small" style={{ marginBottom: 10 }}>
      {reviews.length ? '预设问题测试可选。确认 SQL 与结果后可保存为示例；未测试或未确认的问题不阻碍模型发布。' : '基础模型已可问数。可选填写问题，也可直接对话完善业务模型。'}
        </div>}
        <div className="da-small">已保存 {reviews.length} 个问题 · 已确认 {reviews.filter(r => r.status === 'CONFIRMED').length}/{reviews.length}。可随时补充和选择验证，不作为发布前提。</div>
        {!requirements && workflow && <div className="da-card" style={{ margin: '12px 0', padding: 16, border: '2px solid var(--da-primary)' }} role="status">
          <strong>下一步：{workflow.nextAction.label}</strong><p className="da-small">{workflow.nextAction.message}</p>
          {workflow.draftChanged && <p>已确认 {workflow.questionSummary.confirmed}/{workflow.questionSummary.total} 个问题 · 模型校验{workflow.engineeringStatus === 'PASSED' ? '已通过' : '尚未通过'}</p>}
        {workflow.draftChanged && onPublish && <button className="da-btn da-btn-primary" disabled={!workflow.canPublish || busy !== null} onClick={onPublish}>{workflow.canPublish ? '模型检查通过 → 发布' : '完成模型工程检查后即可发布'}</button>}
          {workflow.blockers.length > 0 && <ul>{workflow.blockers.map((blocker, i) => <li key={i}>{blocker.message}</li>)}</ul>}
          {workflow.stage === 'COMPLETE' && onQuery && <button className="da-btn" onClick={onQuery}>开始问数</button>}
        </div>}
        {!requirements && <button className="da-btn da-btn-primary" disabled={busy !== null} onClick={() => void validateAll()}>
          {busy === 'batch' ? progress : '校验模型并验证待验问题'}
        </button>}
      </div>
      {error && <div role="alert" className="da-small" style={{ color: 'var(--da-danger)' }}>{error}</div>}
      {notice && <div role="status" className="da-card" style={{ padding: 12 }}>{notice}</div>}
      {adding && <AddQuestionsDialog onClose={() => setAdding(false)} onSubmit={onAddQuestions ?? onDiscuss} />}
       {!reviews.length && !error && <div className="da-small">尚无已保存的问题。请先用问题表单提交，再确认助手提出的变更。</div>}
      {!!reviews.length && <nav aria-label="分析问题列表"><ol style={{ margin: 0, padding: 0, listStyle: 'none', display: 'grid', gap: 6 }}>
        {reviews.map((review, index) => <li key={review.question.id}><button type="button" aria-pressed={review.question.id === (reviews.some(r => r.question.id === selectedId) ? selectedId : reviews[0]?.question.id)} onClick={() => setSelectedId(review.question.id)} style={{ width: '100%', textAlign: 'left', padding: '12px 14px', border: '1px solid var(--da-border)', borderRadius: 8, background: review.question.id === (reviews.some(r => r.question.id === selectedId) ? selectedId : reviews[0]?.question.id) ? 'color-mix(in srgb, var(--da-primary) 10%, var(--da-surface))' : 'var(--da-surface)', color: 'var(--da-text)', cursor: 'pointer' }}>
          <div style={{ display: 'flex', gap: 8 }}><span>{index + 1}.</span><strong style={{ flex: 1 }}>{review.question.question}</strong></div>
          <div style={{ marginTop: 6, display: 'flex', gap: 6, flexWrap: 'wrap' }}><span className="da-badge">{review.published?.current ? '已发布 · 问数可用' : review.published?.available ? '已发布版本可用 · 草稿待更新' : LABEL[review.status]}</span><span className="da-small">{review.coverage?.assets.map(a => `${a.kind === 'VIEW' ? '视图' : '模型'}：${a.name}`).join('、') || 'SQL 示例'}</span></div>
        </button></li>)}
      </ol></nav>}
      {reviews.filter(r => r.question.id === (reviews.some(item => item.question.id === selectedId) ? selectedId : reviews[0]?.question.id)).map(({ question: q, status, validation, coverage, published, revision }) => {
        const result = validation?.result;
        const canDecide = status === 'EXECUTED' && !validation?.truncated;
        return (
          <div className="da-card" style={{ padding: 16 }} key={q.id}>
            <div style={{ display: 'flex', gap: 8, alignItems: 'center', flexWrap: 'wrap' }}>
              <strong>{q.question}</strong><span className="da-badge">{status === 'DRAFT' ? !q.definition ? '待补充口径' : !q.sql ? '待完善模型' : '待验证' : LABEL[status]}</span>
            </div>
            {coverage && <p role="status" style={{ color: coverage.ready ? 'var(--da-text-muted)' : 'var(--da-danger)' }}>{coverage.message}</p>}
            {q.modeling?.reason && <p>建模依据：{q.modeling.reason}</p>}
            {requirements ? <details style={{ margin: '10px 0' }}><summary>业务口径</summary><p style={{ whiteSpace: 'pre-wrap' }}>{q.definition || '口径尚未明确，请通过对话补充。'}</p></details> : <p style={{ whiteSpace: 'pre-wrap' }}>{q.definition || '口径尚未明确，请通过对话补充。'}</p>}
            {status === 'STALE' && <p className="da-small">以下为旧验证结果，不能用它确认当前模型。</p>}
            {validation?.error && <pre style={{ whiteSpace: 'pre-wrap', color: 'var(--da-danger)' }}>{validation.error}</pre>}
            {validation?.truncated && <p className="da-small">结果已截断，需改为聚合或缩小范围后重新验证，不能确认。</p>}
            {!requirements && result && <div style={{ overflowX: 'auto', maxHeight: 320 }}>
              <table className="da-table">
                <thead><tr>{result.columns.map(c => <th key={c}>{c}</th>)}</tr></thead>
                <tbody>{result.rows.slice(0, 100).map((row, i) =>
                  <tr key={i}>{result.columns.map(c => <td key={c}>{row[c] === null ? 'NULL' : String(row[c] ?? '')}</td>)}</tr>)}</tbody>
              </table>
              <div className="da-small">{result.rows.length} 行{result.rows.length > 100 ? '，页面仅展示前 100 行' : ''}</div>
            </div>}
            {!requirements && <details style={{ margin: '12px 0' }}><summary>查看问题查询 SQL{validation?.evidence ? '（本次验证快照）' : '（当前草稿）'}</summary>
              <ReadableCode text={validation?.evidence?.sql || q.sql || '尚未生成'} language="sql" title="问题查询 SQL" />
            </details>}
            {!requirements && validation && <details style={{ margin: '12px 0' }}><summary>查看视图定义 SQL（本次验证快照）</summary>
              {validation.evidence ? <>
                <p className="da-small">以下为本次验证时的全部视图定义目录，供核对引用视图及其过滤、关联和计算；不是物理执行计划。模型指纹：{validation.modelHash}</p>
                {!validation.evidence.views.length && <p className="da-small">本次验证模型没有视图定义。</p>}
                {validation.evidence.views.map(view => <details key={view.path} open={validation.evidence?.views.length === 1}>
                  <summary>{view.name}</summary><p>{view.description}</p><div className="da-small">{view.path}</div>
                  <ReadableCode text={view.sql} language="sql" title={view.name} />
                </details>)}
              </> : <p className="da-small">这份旧验证凭据没有视图定义快照。请重新通过 Wren 验证，以获得对应结果的 SQL 依据。</p>}
            </details>}
            {!requirements && validation && <div className="da-small" style={{ marginBottom: 10 }}>
              执行时间：{validation.executedAt}{validation.confirmedBy ? `；确认人：${validation.confirmedBy}；确认时间：${validation.confirmedAt}` : ''}
            </div>}
            {status === 'CONFIRMED' && <p role="status" style={{ color: 'var(--da-success)' }}>{published?.current ? '✓ 已发布，问数已可复用此问题及其模型。' : '✓ 确认已保存。模型工程检查通过后，可点击上方按钮发布，无需确认其他问题。'}</p>}
            <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
              {!requirements && <button className="da-btn" disabled={busy !== null} onClick={() => setEditing({ id: q.id, revision, sql: q.sql })}>修改本次查询 SQL</button>}
              {!requirements && <><button className="da-btn" disabled={busy !== null || !q.sql || !q.definition || coverage?.ready === false}
                onClick={() => void act(q.id, () => validateModelingQuestion(groupId, q.id))}>
                {busy === q.id ? '处理中…' : status === 'CONFIRMED' ? '重新验证' : 'Wren 验证当前草稿'}
              </button>
              {status !== 'CONFIRMED' && <button className="da-btn da-btn-primary" disabled={busy !== null || !canDecide}
                onClick={() => validation && void act(q.id, () => decideModelingQuestion(groupId, q.id, validation.validationId, true))}>
                口径与结果正确，确认
              </button>}
              <button className="da-btn" disabled={busy !== null || !validation || !['EXECUTED', 'CONFIRMED'].includes(status)}
                onClick={() => validation && void act(q.id, () => decideModelingQuestion(groupId, q.id, validation.validationId, false))}>退回</button></>}
              <details><summary>问题设置</summary><div style={{ display: 'flex', gap: 8, flexWrap: 'wrap', marginTop: 8 }}>
              <button className="da-btn" disabled={busy !== null} onClick={() => setRemoving(q.id)}>移出本次验收</button>
              </div></details>
              <button className="da-btn" onClick={() => onDiscuss(
                `请诊断问题 ${q.id}：“${q.question}”。现有口径：${q.definition}。现有查询：${q.sql}。验证错误：${validation?.error || '无，请先询问我发现的问题'}。这是独立的模型修复请求，不是授权直接修改资产。先读取已有模型、规则和问题，定位缺失定义、关联歧义或查询错误，展示有依据的修复建议及受影响问题。若只需修改本题 SQL，请明确建议使用查询编辑入口；若需修改共享语义资产，先让我审阅业务变更，实际写入必须经过受保护工具与 HITL，不把 SQL 差异自动反向写入模型。完成后重验失败问题与受影响的已确认问题，不自动确认结果。`
              )}>建议修复模型</button>
            </div>
            {editing?.id === q.id && <div className="da-card" style={{ padding: 14, marginTop: 12 }}>
              <strong>修改本题 SQL</strong><p>仅更新这个问题，不修改视图或其他模型。保存后旧确认失效，重新验证成功后仍需确认结果。</p>
              <textarea aria-label="本题 SQL" className="da-input" value={editing.sql} maxLength={16000} rows={10}
                style={{ width: '100%', boxSizing: 'border-box', fontFamily: 'monospace', fontSize: 14 }}
                onChange={e => setEditing({ ...editing, sql: e.target.value })} />
              <button className="da-btn da-btn-primary" disabled={busy !== null || !editing.sql.trim()} onClick={() => {
                const correction = editing;
                void act(q.id, async () => {
                  const saved = await correctModelingQuestionSql(groupId, q.id, correction.revision, correction.sql);
                  if (activeGroup.current !== groupId) return saved;
                  setEditing(null);
                  generation.current++;
                  setReviews(previous => previous.map(review => review.question.id === q.id ? saved : review));
                  window.dispatchEvent(new CustomEvent('modeling:updated', { detail: { groupId } }));
                  setNotice('SQL 已保存，正在通过 Wren 重新验证；模型资产未修改。');
                  return validateModelingQuestion(groupId, q.id);
                });
              }}>保存 SQL 并重新验证</button>
              <button className="da-btn" disabled={busy !== null} onClick={() => setEditing(null)}>取消</button>
            </div>}
            {removing === q.id && <div role="alert" className="da-card" style={{ padding: 14, marginTop: 12 }}>
              <p>移出“{q.question}”？它将不再阻塞本次发布，源文件、历史记录和模型资产保留。已发布版本在下次发布前继续有效。</p>
              <button className="da-btn" disabled={busy !== null} onClick={() => {
                setBusy(q.id); setError(null);
                void archiveModelingQuestion(groupId, q.id, revision).then(async () => {
                  if (activeGroup.current !== groupId) return;
                  setRemoving(null); await refresh(); setNotice('已移出本次验收；历史记录和模型资产保留。');
                  window.dispatchEvent(new CustomEvent('modeling:updated', { detail: { groupId } }));
                }).catch(e => { if (activeGroup.current === groupId) setError(e instanceof Error ? e.message : '移出失败'); })
                  .finally(() => { if (activeGroup.current === groupId) setBusy(null); });
              }}>确认移出</button>
              <button className="da-btn" disabled={busy !== null} onClick={() => setRemoving(null)}>取消</button>
            </div>}
          </div>
        );
      })}
    </div>
  );
}
