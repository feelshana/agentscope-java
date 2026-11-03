import { useCallback, useEffect, useState } from 'react';
import { assetExamples, assetImpact, deleteAsset, generateAssetSql, getAsset, judgeAsset, listAssets, reviewAsset, saveAsset, verifyAsset } from '../../api/semanticAssets';
import ReadableCode from '../ReadableCode';
import type { AssetCheck, AssetDetail, AssetExample, AssetStatus, SemanticAsset, Verdict, VerifyCase } from '../../api/semanticAssets';
import { getModelingQuestions } from '../../api/modelingQuestions';
import type { QuestionReview } from '../../api/modelingQuestions';

/** Business names for the two kinds; the technical terms stay out of the page (spec111). */
const KIND_LABEL = { view: '查询模板', ref_sql: '加工表' } as const;
const STATUS: Record<AssetStatus, { label: string; tone: string }> = {
  PUBLISHED: { label: '已发布', tone: 'sm-tag-plain' },
  REVIEW_PENDING: { label: '待核查', tone: 'sm-tag-warn' },
  VERIFY_PENDING: { label: '待验证', tone: 'sm-tag-warn' },
  VERIFIED: { label: '验证通过', tone: 'sm-tag-fact' },
};
const message = (e: unknown) => e instanceof Error ? e.message : String(e);
const isTimeColumn = (c: { name: string; type: string }) => /date|time/i.test(c.type) || /date|time|日期|时间/i.test(c.name);
/** Sent as a declared asset turn: only this purpose opens views/ for the assistant (ADR0128). */
const assetRequest = (need: string) => `请建设语义资产。需求或已有 SQL 如下：\n${need}`;

/** spec111: reviews and verifies Views and ref_sql models the assistant or a developer drafted. */
export default function SemanticAssetsPanel({ groupId, assistantIdle, onDraft }: {
  groupId: string;
  assistantIdle: boolean;
  onDraft: (text: string, label: string) => boolean;
}) {
  const [assets, setAssets] = useState<SemanticAsset[] | null>(null);
  const [selected, setSelected] = useState<string | null>(null);
  const [detail, setDetail] = useState<AssetDetail | null>(null);
  const [need, setNeed] = useState('');
  const [examples, setExamples] = useState<AssetExample[] | null>(null);
  const [file, setFile] = useState('');
  const [content, setContent] = useState('');
  const [timeColumns, setTimeColumns] = useState('');
  const [questions, setQuestions] = useState<QuestionReview[]>([]);
  const [question, setQuestion] = useState('');
  const [cases, setCases] = useState<VerifyCase[]>([]);
  const [checks, setChecks] = useState<AssetCheck[]>([]);
  const [verdicts, setVerdicts] = useState<Record<string, Verdict>>({});
  // spec112: expected cases are saved as questions unless the user unticks them.
  const [skipSave, setSkipSave] = useState<Record<string, boolean>>({});
  const [busy, setBusy] = useState('');
  const [error, setError] = useState('');

  const show = useCallback((next: AssetDetail) => {
    setDetail(next);
    setFile(current => next.files.some(f => f.path === current) ? current : next.files[0]?.path ?? '');
    // A column from an earlier review may no longer exist; fall back to the likely time column.
    const kept = next.asset.review?.timeColumns.find(c => !next.outputColumns.length || next.outputColumns.some(o => o.name === c));
    setTimeColumns(kept ?? (next.needsTimeColumn ? next.outputColumns.find(isTimeColumn)?.name ?? '' : ''));
  }, []);
  const refresh = useCallback(async () => {
    const list = await listAssets(groupId);
    setAssets(list);
    return list;
  }, [groupId]);
  useEffect(() => {
    void refresh().catch(e => setError(message(e)));
    void getModelingQuestions(groupId).then(setQuestions).catch(() => setQuestions([]));
    // Examples come from this knowledge base's own model, so they never mention unrelated tables.
    setExamples(null);
    void assetExamples(groupId).then(setExamples).catch(() => setExamples([]));
  }, [groupId, refresh]);
  useEffect(() => {
    const onUpdated = () => void refresh().catch(() => undefined);
    window.addEventListener('modeling:updated', onUpdated);
    return () => window.removeEventListener('modeling:updated', onUpdated);
  }, [refresh]);
  useEffect(() => {
    const asset = assets?.find(a => `${a.kind}:${a.name}` === selected);
    if (!asset) { setDetail(null); return; }
    let cancelled = false;
    setChecks([]); setVerdicts({}); setSkipSave({}); setCases([]); setQuestion('');
    void getAsset(groupId, asset).then(next => { if (!cancelled) show(next); }).catch(e => setError(message(e)));
    return () => { cancelled = true; };
    // Reload only when the selection changes; actions below update the detail themselves.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [selected, groupId, show]);
  useEffect(() => { setContent(detail?.files.find(f => f.path === file)?.content ?? ''); }, [detail, file]);

  const run = async (name: string, action: () => Promise<void>) => {
    setBusy(name); setError('');
    try { await action(); } catch (e) { setError(message(e)); } finally { setBusy(''); }
  };
  const apply = async (next: AssetDetail) => { show(next); await refresh(); };
  const asset = detail?.asset;
  const fileName = file.slice(file.lastIndexOf('/') + 1);
  const edited = detail?.files.find(f => f.path === file)?.content !== content;
  const reviewed = !!asset?.review;
  const changeCases = (next: VerifyCase[]) => { setCases(next); setChecks([]); setVerdicts({}); setSkipSave({}); };
  const allJudged = checks.length > 0 && checks.every(c => !c.error && verdicts[c.key]);
  const caseQuestion = (check: AssetCheck) => cases.find(c => c.sql === check.sql)?.question.trim() ?? '';
  const toSave = checks.filter(c => verdicts[c.key] === 'EXPECTED' && !skipSave[c.key] && caseQuestion(c))
    .map(c => ({ question: caseQuestion(c), sql: c.sql }));

  return <section className="sm-pane sa-pane" aria-label="公共视图">
    <div className="da-card sa-draft">
      <div className="sa-title"><strong>公共视图</strong><span className="sm-tag sa-building">建设中</span></div>
      <div className="sa-lead">把大家反复在问、反复要拼的取数逻辑整理成一份共用的"公共视图"。以后问到这类问题，问数助手直接从公共视图取数：口径统一，不用每次重新拼表、重新算。</div>
      <ol className="sa-plan">
        <li><b>1</b><div><strong>收集已确认的问答</strong><span>试问、问数优化、问数点赞中确认过的问题和 SQL</span></div></li>
        <li><b>2</b><div><strong>找出反复出现的逻辑</strong><span>比如很多题都先算"每人首次访问"，或都把访问记录和人员部门拼在一起</span></div></li>
        <li><b>3</b><div><strong>你来决定建不建</strong><span>每条建议都写明来自哪几道题、出现了几次，批准后才起草</span></div></li>
        <li><b>4</b><div><strong>用原来的问题核对</strong><span>这些问题改用公共视图后重新写 SQL，新旧对照确认后再发布</span></div></li>
      </ol>
      <div className="da-small">自动整理功能正在建设中。在此之前，规则、关系、字段含义照常在"建模对话"和"问数优化"里补充；确实需要建视图时，在建模对话里直接说"建一个视图……"，也可以用下面的快捷入口。</div>
      {/* spec113: every need can be said in the modeling chat; this page drafts on request and reviews. */}
      <details className="sa-where da-small">
        <summary>现在就要建一个视图</summary>
        <table className="da-table">
          <thead><tr><th>需求</th><th>助手怎么处理</th></tr></thead>
          <tbody>
            <tr><td>表和字段的含义、指标口径、业务术语、表之间的关系</td><td>直接写入草稿，不需要建视图</td></tr>
            <tr><td>默认排除或只保留部分数据，如"默认排除用户列表里找不到的账号"</td><td>写成一条默认过滤规则，问数时自动加条件，不需要建视图</td></tr>
            <tr><td>一列按规则换个说法，如"账号以 wx 结尾的算外协"</td><td>加计算字段，不需要建视图</td></tr>
            <tr><td>明确要把几张表固定拼好，或先用多步计算算好结果</td><td>起草视图，再在本页下方核查和验证</td></tr>
          </tbody>
        </table>
        <div className="sa-examples" aria-label="需求示例">{examples === null
          ? <span className="da-small">正在根据当前模型生成示例…</span>
          : examples.map(e => <button key={e.kind} type="button" className="da-btn da-btn-sm"
            title={e.text} onClick={() => setNeed(e.text)}>示例·{e.kind}</button>)}</div>
        <textarea className="da-input" rows={3} aria-label="资产需求或已有 SQL" value={need} onChange={e => setNeed(e.target.value)}
          placeholder="用业务语言描述要建的视图，或粘贴已有 SQL；也可以点上面的示例" />
        <div><button className="da-btn da-btn-primary da-btn-sm" disabled={!need.trim() || !assistantIdle}
          title={assistantIdle ? undefined : '助手空闲后可发送'}
          onClick={() => { if (onDraft(assetRequest(need.trim()), `建设语义资产：${need.trim()}`)) setNeed(''); }}>交给助手起草</button></div>
      </details>
    </div>
    {error && <div role="alert" className="da-alert da-alert-error">{error}</div>}
    {assets !== null && assets.length === 0 ? <div className="da-card sm-empty">还没有已建的视图。建好后会出现在这里，核查并用问题验证通过后才能发布。</div> : <>
    <strong className="sa-list-title">已建的视图与加工表<span className="da-small">　核查并用问题验证通过后才能发布</span></strong>
    <div className="sa-layout">
      <nav className="sa-list" aria-label="资产列表">
        {assets === null ? <div className="da-small">加载中…</div>
          : assets.map(a => { const key = `${a.kind}:${a.name}`; return <button key={key} type="button"
            className={`sa-item ${selected === key ? 'sa-item-on' : ''}`} aria-current={selected === key ? 'true' : undefined} onClick={() => setSelected(key)}>
            <span className="sa-item-name">{a.name}</span>
            <span className="sm-tag sm-tag-plain">{KIND_LABEL[a.kind]}</span>
            <span className={`sm-tag ${STATUS[a.status].tone}`}>{STATUS[a.status].label}</span>
          </button>; })}
      </nav>
      <div className="sa-detail">
        {!asset ? <div className="sm-empty">选择一个资产进行核查和验证</div> : <>
          <div className="da-card sa-section">
            <h3>{asset.name} <span className={`sm-tag ${STATUS[asset.status].tone}`}>{STATUS[asset.status].label}</span>
              <button type="button" className="da-btn da-btn-sm sa-delete" disabled={!!busy} onClick={() => void run('delete', async () => {
                // spec113: rule entries naming the asset go with it; questions using it need review.
                const impact = await assetImpact(groupId, asset);
                const lines = [
                  `确认从草稿中删除${KIND_LABEL[asset.kind]}「${asset.name}」？发布后才在线上生效。`,
                  impact.rules.length ? `\n将一并删除这些默认使用规则：\n${impact.rules.join('\n')}` : '',
                  impact.questions.length ? `\n这些问答用到了它，删除后需要复核：\n${impact.questions.map(q => `- ${q}`).join('\n')}` : '',
                ];
                if (!window.confirm(lines.join('\n'))) return;
                await deleteAsset(groupId, asset);
                setSelected(null);
                await refresh();
                window.dispatchEvent(new CustomEvent('modeling:updated', { detail: { groupId } }));
              })}>{busy === 'delete' ? '删除中…' : '删除'}</button></h3>
            <div className="sa-description">{asset.description?.trim() || '助手尚未写说明，请核查用途、粒度和口径。'}</div>
            <pre className="sa-sql sm-code">{asset.sql}</pre>
            {detail.warnings?.map(w => <div key={w} role="alert" className="sm-warn">{w}</div>)}
            {detail.fixedDates.length > 0 && <div role="alert" className="sm-warn">资产里写了固定日期 {detail.fixedDates.join('、')}，所有查询都只能看到这段时间。除非这是业务口径，否则请去掉。</div>}
          </div>
          <div className="da-card sa-section">
            <h3>文件</h3>
            <div className="sa-files">{detail.files.map(f => <button key={f.path} type="button" className={`da-btn da-btn-sm ${f.path === file ? 'da-btn-primary' : ''}`}
              onClick={() => setFile(f.path)}>{f.path.slice(f.path.lastIndexOf('/') + 1)}</button>)}</div>
            <textarea className="da-input sa-editor sm-code" rows={12} aria-label={`编辑 ${fileName}`} spellCheck={false}
              value={content} onChange={e => setContent(e.target.value)} />
            <div className="sa-actions">
              <button className="da-btn da-btn-sm" disabled={!edited || !!busy} onClick={() => void run('save', async () => { setChecks([]); setVerdicts({}); await apply(await saveAsset(groupId, asset, fileName, content)); })}>
                {busy === 'save' ? '保存中…' : '保存到草稿'}</button>
              <span className="da-small">保存后需要重新核查和验证。</span>
            </div>
          </div>
          <div className="da-card sa-section">
            <h3>1. 核查</h3>
            {asset.review && <div className="da-small">已由 {asset.review.reviewedBy} 核查。</div>}
            <div className="da-small">确认上面的用途、粒度、JOIN 和口径是否正确，通过后才能用问题验证。</div>
            {detail.needsTimeColumn && <>
              <label className="da-small" htmlFor="sa-time">时间列（必选）：这个资产依赖有查询时间限制的事实表，平台用这一列检查每次查询的日期范围。</label>
              {detail.outputColumns.length > 0
                ? <select id="sa-time" className="da-input" value={timeColumns} onChange={e => setTimeColumns(e.target.value)}>
                  <option value="">请选择</option>
                  {detail.outputColumns.map(c => <option key={c.name} value={c.name}>{c.name}{c.type ? `（${c.type}）` : ''}</option>)}
                </select>
                : <input id="sa-time" className="da-input" value={timeColumns} onChange={e => setTimeColumns(e.target.value)} placeholder="资产输出的时间列" />}
            </>}
            <div className="sa-actions"><button className="da-btn da-btn-primary da-btn-sm" disabled={!!busy || edited || asset.status === 'PUBLISHED'}
              title={edited ? '请先保存修改' : undefined}
              onClick={() => void run('review', async () => { await apply(await reviewAsset(groupId, asset, detail.needsTimeColumn && timeColumns.trim() ? [timeColumns.trim()] : [])); })}>
              {busy === 'review' ? '提交中…' : '核查通过'}</button></div>
          </div>
          <div className="da-card sa-section">
            <h3>2. 用问题验证</h3>
            {!reviewed ? <div className="da-small">{asset.status === 'PUBLISHED' ? '这个资产已发布且没有修改，不需要验证。' : '核查通过后才能验证。'}</div> : <>
              <div className="da-small">输入业务问题，助手只用这个资产写 SQL；平台在草稿上展开 SQL 给你核对，不执行查询，不影响线上。</div>
              <div className="sa-ask">
                <input className="da-input" aria-label="验证问题" value={question} onChange={e => setQuestion(e.target.value)} placeholder="输入一个要用这个资产回答的业务问题" />
                <button className="da-btn da-btn-primary da-btn-sm" disabled={!!busy || edited || !question.trim()}
                  title={edited ? '请先保存修改' : undefined}
                  onClick={() => void run('generate', async () => {
                    const { sql } = await generateAssetSql(groupId, asset, question.trim());
                    changeCases([...cases, { question: question.trim(), sql }]);
                    setQuestion('');
                  })}>{busy === 'generate' ? '生成中…' : '生成 SQL'}</button>
                <button className="da-btn da-btn-sm" disabled={!!busy} onClick={() => changeCases([...cases, { question: question.trim(), sql: `SELECT * FROM ${asset.name} LIMIT 20` }])}>手写 SQL</button>
              </div>
              {questions.length > 0 && <div className="sa-suggest"><span className="da-small">已确认问题：</span>
                {questions.map(q => <button key={q.question.id} type="button" className="sm-text-link" onClick={() => setQuestion(q.question.question)}>{q.question.question}</button>)}
              </div>}
              {cases.map((item, index) => <div key={index} className="sa-case">
                <div className="sa-case-head"><strong>{item.question || `SQL ${index + 1}`}</strong>
                  <button type="button" className="sm-text-link" onClick={() => changeCases(cases.filter((_, i) => i !== index))}>移除</button></div>
                <textarea className="da-input sm-code" rows={4} aria-label={`验证 SQL ${index + 1}`} spellCheck={false} value={item.sql}
                  onChange={e => changeCases(cases.map((c, i) => i === index ? { ...c, sql: e.target.value } : c))} />
              </div>)}
              {cases.length > 0 && <div className="sa-actions"><button className="da-btn da-btn-primary da-btn-sm" disabled={!!busy || edited || cases.some(c => !c.sql.trim())}
                onClick={() => void run('verify', async () => {
                  setVerdicts({}); setSkipSave({});
                  setChecks(await verifyAsset(groupId, asset, cases));
                })}>{busy === 'verify' ? '展开中…' : '展开并核对 SQL'}</button></div>}
              {checks.map(check => <div key={check.key} className="sa-check">
                <strong>{check.label}</strong>
                {check.error ? <div role="alert" className="sm-issue">{check.error}</div> : <>
                  {/* spec114: nothing runs; the reviewer reads the SQL as Wren would expand it. */}
                  {check.expanded && <ReadableCode text={check.expanded} language="sql" title="展开后的 SQL（Wren 实际发给数据库的语句，未执行）" />}
                  <div className="sa-verdict" role="radiogroup" aria-label={`判定：${check.label}`}>
                    {(['EXPECTED', 'PROBLEM'] as Verdict[]).map(v => <label key={v}><input type="radio" name={check.key} checked={verdicts[check.key] === v}
                      onChange={() => setVerdicts(all => ({ ...all, [check.key]: v }))} />{v === 'EXPECTED' ? 'SQL 符合预期' : '有问题'}</label>)}
                  </div>
                  {verdicts[check.key] === 'EXPECTED' && (caseQuestion(check)
                    ? <label className="da-small sa-save"><input type="checkbox" checked={!skipSave[check.key]}
                        onChange={e => setSkipSave(all => ({ ...all, [check.key]: !e.target.checked }))} />
                        同时保存为问答（发布后问数可参考）
                        {questions.some(q => q.question.question.trim() === caseQuestion(check)) && '，将替换已有同名问答的 SQL'}</label>
                    : <span className="da-small">没有问题文字，不能保存为问答</span>)}
                </>}
              </div>)}
              {checks.length > 0 && <div className="sa-actions"><button className="da-btn da-btn-primary da-btn-sm" disabled={!!busy || !allJudged}
                title={allJudged ? undefined : '全部问题执行成功并判定后才能提交'}
                onClick={() => void run('verdict', async () => {
                  try { await apply(await judgeAsset(groupId, asset, verdicts, toSave)); }
                  finally {
                    // Verdicts are kept even when saving a question fails; show the stored state.
                    void getAsset(groupId, asset).then(show).catch(() => undefined);
                    void refresh().catch(() => undefined);
                    // Saved questions show up in the trial panel and the question list.
                    if (toSave.length) {
                      void getModelingQuestions(groupId).then(setQuestions).catch(() => undefined);
                      window.dispatchEvent(new CustomEvent('modeling:updated', { detail: { groupId } }));
                    }
                  }
                })}>提交判定</button></div>}
              {asset.review?.verifiedBy && <div className="da-small">{asset.review.passed ? '已通过验证，可以在草稿栏发布。' : '判定有问题，请修改后重新核查。'}</div>}
            </>}
          </div>
        </>}
      </div>
    </div>
    </>}
  </section>;
}
