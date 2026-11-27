import { useCallback, useEffect, useRef, useState } from 'react';
import { judgeCalibers, verifyCalibers } from '../../api/calibers';
import type { CaliberCheck, CaliberTable } from '../../api/calibers';
import { conditionLines, stepProgress, whereClause } from './caliberPresentation';
import CaliberStepper from './CaliberStepper';

const message = (e: unknown) => e instanceof Error ? e.message : String(e);
type CaseState = 'pass' | 'fail' | 'todo' | 'error';
const STATE: Record<CaseState, { mark: string; label: string }> = {
  pass: { mark: '✓', label: '符合' }, fail: { mark: '✕', label: '不符合 · 已退回核对' },
  todo: { mark: '', label: '待判定' }, error: { mark: '!', label: '展开失败' },
};

/**
 * spec153 verification: one generated question per caliber, expanded on the draft and checked by the
 * database without running it (ADR0132). The person compares the conditions and judges each one;
 * "does not match" sends the caliber back to review.
 */
export default function CaliberVerify({ groupId, table, item, onItem, onReplace, onBack, onReview, onCheck, onPublish }: {
  onPublish: () => void;
  groupId: string;
  table: CaliberTable;
  item: string | null;
  onItem: (item: string) => void;
  onReplace: (table: CaliberTable) => void;
  onBack: () => void;
  onReview: (item: string | null) => void;
  onCheck: () => void;
}) {
  const [checks, setChecks] = useState<CaliberCheck[] | null>(null);
  const [busy, setBusy] = useState('');
  const [error, setError] = useState('');
  // A judged case shows its verdict; buttons come back only when the person chooses to judge again.
  const [rejudge, setRejudge] = useState<string | null>(null);
  const progress = stepProgress(table);
  const reviewedAll = progress.reviewed === progress.reviewTotal && table.conflicts.length === 0;
  const listPath = table.listPath;

  const run = useCallback(async () => {
    if (!listPath) return;
    setBusy('verify'); setError('');
    try { setChecks(await verifyCalibers(groupId, listPath)); } catch (e) { setError(message(e)); } finally { setBusy(''); }
  }, [groupId, listPath]);
  const started = useRef(false);
  const waiting = table.rows.some(r => r.review === 'VERIFY_PENDING' || r.review === 'PROBLEM');
  useEffect(() => {
    if (started.current || !reviewedAll || !waiting) return;
    started.current = true;
    void run();
  }, [reviewedAll, waiting, run]);

  const stateOf = (name: string): CaseState => {
    const check = checks?.find(c => c.caliber === name);
    if (check?.error) return 'error';
    const review = table.rows.find(r => r.name === name)?.review;
    return review === 'PASSED' ? 'pass' : review === 'PROBLEM' ? 'fail' : 'todo';
  };
  const names = table.rows.filter(r => r.review !== 'REVIEW_PENDING').map(r => r.name);
  const current = names.find(n => n === item) ?? names.find(n => stateOf(n) === 'todo') ?? names[0] ?? null;
  const check = checks?.find(c => c.caliber === current) ?? null;
  const row = table.rows.find(r => r.name === current) ?? null;
  const counts = { pass: 0, fail: 0, todo: 0, error: 0 };
  names.forEach(n => counts[stateOf(n)]++);
  const total = names.length || 1;
  const finished = names.length > 0 && counts.todo === 0 && counts.error === 0;
  const judging = !!current && !!check && !check.error && (stateOf(current) === 'todo' || rejudge === current);

  const judge = async (verdict: 'EXPECTED' | 'PROBLEM') => {
    if (!listPath || !current || !check || check.error || busy) return;
    setBusy('judge'); setError('');
    try {
      const next = await judgeCalibers(groupId, listPath, { [current]: verdict });
      onReplace(next);
      setRejudge(null);
      const after = names.indexOf(current);
      const following = [...names.slice(after + 1), ...names.slice(0, after)]
        .find(n => next.rows.find(r => r.name === n)?.review === 'VERIFY_PENDING' && checks?.some(c => c.caliber === n && !c.error));
      if (following) onItem(following);
    } catch (e) { setError(message(e)); } finally { setBusy(''); }
  };

  return <div className="cw-step-page">
    <CaliberStepper table={table} step="verify" onBack={onBack} onCheck={onCheck} onPublish={onPublish} />
    {error && <div role="alert" className="da-alert da-alert-error">{error}</div>}
    {!reviewedAll ? <div className="da-card sm-empty">
      <p>还有 {progress.reviewTotal - progress.reviewed} 项未核对{table.conflicts.length ? `、${table.conflicts.length} 个冲突` : ''}。全部核对后再验证，验证问题按核对过的内容生成。</p>
      <button type="button" className="da-btn da-btn-primary" onClick={() => onReview(null)}>回到核对</button></div>
    : <div className="cw-review">
      <aside className="cw-queue" aria-label="验证问题">
        <div className="cw-verify-head">
          <div className="cw-verify-title"><strong>验证问题</strong><span className="da-small">每个口径一题</span>
            <button type="button" className="da-btn da-btn-sm" disabled={!!busy} onClick={() => void run()}>
              {busy === 'verify' ? '生成中…' : checks ? '重新生成' : '生成验证问题'}</button></div>
          <div className="cw-bar" role="img" aria-label={`符合 ${counts.pass}，不符合 ${counts.fail}，待判定 ${counts.todo + counts.error}`}>
            <span className="cw-bar-pass" style={{ width: `${(counts.pass / total) * 100}%` }} />
            <span className="cw-bar-fail" style={{ width: `${(counts.fail / total) * 100}%` }} /></div>
          <div className="da-small">符合 {counts.pass} · 不符合 {counts.fail} · 待判定 {counts.todo + counts.error}</div>
        </div>
        <div className="cw-queue-list">
          {names.map(n => {
            const s = stateOf(n);
            const c = checks?.find(x => x.caliber === n);
            return <button key={n} type="button" className={`cw-case cw-case-${s} ${n === current ? 'cw-queue-on' : ''}`} aria-current={n === current} onClick={() => onItem(n)}>
              <span className="cw-case-dot" aria-hidden="true">{STATE[s].mark}</span>
              <span className="cw-case-text"><span>{c?.question ?? n}</span><span className="da-small">{n} · {STATE[s].label}</span></span>
            </button>;
          })}
        </div>
      </aside>

      <main className="cw-detail">
        <div className="cw-detail-body">
          {!current ? <p className="da-small">没有可验证的口径。</p>
          : stateOf(current) === 'pass' && !check ? <div className="cw-row">
              <div className="cw-row-title"><h2>{current}</h2><span className="mo-pill mo-green">符合</span></div>
              <p className="da-small">{row?.verifiedBy ? `${row.verifiedBy} 判定符合。` : '已判定符合。'}口径改动后需要重新核对和验证。</p></div>
          : !check ? <p className="da-small">{busy === 'verify' ? '正在生成验证问题并展开查询（不执行）…' : '点“生成验证问题”为待验证的口径生成问题。'}</p>
          : <div className="cw-row">
            <div className="da-small">验证口径「{check.caliber}」</div>
            <div className="cw-row-title"><h2>{check.question}</h2>
              <span className={`mo-pill ${stateOf(current) === 'pass' ? 'mo-green' : stateOf(current) === 'todo' ? 'mo-amber' : 'cw-pill-problem'}`}>{STATE[stateOf(current)].label}</span></div>
            {check.error ? <div role="alert" className="da-alert da-alert-error">查询展开失败：{check.error}。回到核对修改口径，或让助手修正清单。</div> : <>
              <section className="cw-block" aria-label="平台生成的查询">
                <h3>平台生成的查询 <span className="da-small">只展开、由数据库校验，不执行，不读取数据</span></h3>
                <pre className="cw-sql">{check.query}</pre>
              </section>
              <div className="cw-compare">
                <section className="cw-block" aria-label="口径要求">
                  <h3>口径要求</h3>
                  <dl className="cw-conditions">{row && conditionLines(row).map(line => <div key={line.column} className={line.unrestricted ? 'cw-unrestricted' : ''}>
                    <dt><code>{line.column}</code></dt><dd>{line.text}</dd></div>)}</dl>
                </section>
                <section className="cw-block" aria-label="查询模板中的取数条件">
                  <h3>查询模板中的取数条件</h3>
                  {/* The label columns (caliber_name, caliber_condition…) read like filters; only WHERE filters. */}
                  <pre className="cw-sql">{whereClause(check.segment) ?? check.segment}</pre>
                  {whereClause(check.segment) && <details className="cw-generated"><summary className="da-small">
                    完整 SQL（开头的 caliber_name、caliber_condition 等是给结果行打的标签，不参与筛选）</summary>
                    <pre className="cw-sql">{check.segment}</pre></details>}
                </section>
              </div>
              {check.expanded && <details className="cw-generated"><summary className="da-small">展开后的完整 SQL（按口径名查查询模板）</summary>
                <pre className="cw-sql">{check.expanded}</pre></details>}
            </>}
          </div>}
          {finished && <div className="cw-done" role="status">
            这张表的口径都已判定{counts.fail ? `，${counts.fail} 个不符合，已退回核对，修改后重新核对和验证。` : '，全部符合。'}
            {!counts.fail && (table.published === 'CURRENT' ? '已发布，问数按这些口径取数。' : '下一步是发布知识库，发布后问数才按这些口径取数。')}</div>}
        </div>
        <footer className="cw-actions">
          {judging ? <>
            <span className="da-small">判定由你完成；“不符合”会把口径退回核对，在核对页让助手修改。</span>
            <span className="mo-grow" />
            {rejudge === current && <button type="button" className="da-btn" disabled={!!busy} onClick={() => setRejudge(null)}>取消</button>}
            <button type="button" className="da-btn cw-btn-danger" disabled={!!busy} onClick={() => void judge('PROBLEM')}>不符合，退回核对</button>
            <button type="button" className="da-btn da-btn-primary" disabled={!!busy} onClick={() => void judge('EXPECTED')}>
              {busy === 'judge' ? '提交中…' : '符合，下一题'}</button>
          </> : <>
            <span className="da-small">{current && stateOf(current) !== 'todo' ? `「${current}」已判定为${STATE[stateOf(current)].label}。` : ''}</span>
            <span className="mo-grow" />
            {current && check && !check.error && stateOf(current) !== 'todo' &&
              <button type="button" className="da-btn" onClick={() => setRejudge(current)}>重新判定</button>}
            {finished && counts.fail > 0 && <button type="button" className="da-btn da-btn-primary" onClick={() => onReview(null)}>去修改</button>}
            {finished && counts.fail === 0 && table.published !== 'CURRENT' &&
              <button type="button" className="da-btn da-btn-primary" onClick={onPublish}>去发布</button>}
            {finished && counts.fail === 0 && table.published === 'CURRENT' &&
              <button type="button" className="da-btn da-btn-primary" onClick={onBack}>回到口径总览</button>}
          </>}
        </footer>
      </main>
    </div>}
  </div>;
}
