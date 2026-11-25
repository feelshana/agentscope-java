import { useCallback, useEffect, useState } from 'react';
import { getCalibers, judgeCalibers, reviewCalibers, verifyCalibers } from '../../api/calibers';
import type { CaliberCheck, CaliberReviewState, CaliberTable, CaliberTableStatus } from '../../api/calibers';
import Icon from '../Icon';
import { caliberLabel, caliberRequest, caliberSource, conditionLines, keyText, publishBlockers, publishedText, reviewProgress, scopeText, undecided } from './caliberPresentation';
import './ModelingOptimization.css';
import './CaliberPanel.css';

const STATUS: Record<CaliberTableStatus, { label: string; tone: string }> = {
  DRAFTABLE: { label: '可起草', tone: 'mo-amber' },
  OUTDATED: { label: '文档有更新', tone: 'mo-amber' },
  CURRENT: { label: '与文档一致', tone: 'mo-green' },
  MANUAL: { label: '无对应文档', tone: '' },
};
const REVIEW: Record<CaliberReviewState, { label: string; tone: string }> = {
  REVIEW_PENDING: { label: '待核查', tone: 'mo-amber' },
  VERIFY_PENDING: { label: '待验证', tone: 'mo-amber' },
  PROBLEM: { label: '有问题', tone: 'cp-tag-problem' },
  PASSED: { label: '已通过', tone: 'mo-green' },
};
const message = (e: unknown) => e instanceof Error ? e.message : String(e);
type Verdict = 'EXPECTED' | 'PROBLEM';

/**
 * spec149: per table, how its caliber list stands against the documents, and the person's review
 * (rows as shown) and verification (a generated question per caliber, expanded, never executed).
 * Only rows a person passed and verified ship; others keep the published calibers.
 */
export default function CaliberPanel({ groupId, assistantIdle, onDraft }: {
  groupId: string;
  assistantIdle: boolean;
  onDraft: (text: string, label: string) => boolean;
}) {
  const [tables, setTables] = useState<CaliberTable[] | null>(null);
  const [selected, setSelected] = useState<string | null>(null);
  const [picked, setPicked] = useState<Record<string, boolean>>({});
  const [scenario, setScenario] = useState(false);
  const [checks, setChecks] = useState<CaliberCheck[] | null>(null);
  const [verdicts, setVerdicts] = useState<Record<string, Verdict>>({});
  const [busy, setBusy] = useState('');
  const [error, setError] = useState('');

  const refresh = useCallback(async () => {
    const next = await getCalibers(groupId);
    setTables(next);
    setSelected(current => current && next.some(t => t.model === current) ? current : next[0]?.model ?? null);
  }, [groupId]);
  // A finished turn may have drafted a list; reload when the assistant becomes idle.
  useEffect(() => { if (assistantIdle) void refresh().catch(e => setError(message(e))); }, [refresh, assistantIdle]);

  const table = tables?.find(t => t.model === selected) ?? null;
  useEffect(() => { setPicked({}); setScenario(false); setChecks(null); setVerdicts({}); }, [selected]);

  const replace = (next: CaliberTable) => setTables(previous => previous?.map(t => t.model === next.model ? next : t) ?? previous);
  const run = async (label: string, action: () => Promise<void>) => {
    setBusy(label); setError('');
    try { await action(); } catch (e) { setError(message(e)); } finally { setBusy(''); }
  };
  const draft = (t: CaliberTable) => {
    if (onDraft(caliberRequest(t), caliberLabel(t))) setError('');
  };

  const pending = table?.rows.filter(r => r.review === 'REVIEW_PENDING') ?? [];
  const conflicted = new Set(table?.conflicts.map(c => c.caliber) ?? []);
  const chosen = pending.filter(r => picked[r.name] && !conflicted.has(r.name)).map(r => r.name);
  const verifiable = table?.rows.some(r => r.review === 'VERIFY_PENDING' || r.review === 'PROBLEM') ?? false;
  const listPath = table?.listPath ?? null;
  const review = () => listPath && run('review', async () => {
    replace(await reviewCalibers(groupId, listPath, scenario, chosen));
    setPicked({}); setScenario(false);
  });
  const verify = () => listPath && run('verify', async () => {
    setChecks(await verifyCalibers(groupId, listPath)); setVerdicts({});
  });
  const judge = () => listPath && run('judge', async () => {
    replace(await judgeCalibers(groupId, listPath, verdicts));
    setChecks(null); setVerdicts({});
  });
  const progress = table ? reviewProgress(table) : null;
  const blockers = table ? publishBlockers(table) : [];
  const unjudged = undecided(checks, verdicts);

  return <section className="sm-pane mo-optimization cp-pane" aria-label="口径">
    <header className="mo-page-head"><div><h2>口径</h2>
      <p>业务文档开头写明“适用模型”的表，可由助手把文档中的口径表照抄为口径清单；平台按清单生成查询模板和指标。表说明和每个口径都核查、验证通过后才能发布知识库；发布后这张表只按口径名经指标取数，不再直接查原表。</p></div></header>
    {error && <div role="alert" className="da-alert da-alert-error">{error}</div>}
    {tables === null ? <div className="da-small">{error ? '读取失败' : '加载中…'}</div>
      : tables.length === 0 ? <div className="da-card sm-empty">还没有声明了适用模型的业务文档。在“资料”页上传业务文档，并在文档开头写一行“适用模型：`模型名`”。</div>
      : <div className="cp-layout">
        <nav className="cp-list" aria-label="表列表">
          {tables.map(t => <button key={t.model} type="button" className={`cp-item ${t.model === selected ? 'cp-item-on' : ''}`} aria-current={t.model === selected} onClick={() => setSelected(t.model)}>
            <span className="cp-item-name">{t.model}</span>
            <span className="cp-item-meta"><span className={`mo-pill ${STATUS[t.status].tone}`}>{STATUS[t.status].label}</span>
              {t.rows.length > 0 && <small className="da-small">{reviewProgress(t).passed}/{t.rows.length} 已通过</small>}
              {t.listPath && t.published !== 'CURRENT' && <span className="mo-pill mo-amber">{t.published === 'NONE' ? '未发布' : '有改动未发布'}</span>}</span>
            {t.subject && <small className="da-small cp-item-subject">{t.subject}</small>}
          </button>)}
        </nav>
        {table && <div className="cp-detail">
          <header className="cp-detail-head">
            <div className="mo-grow"><strong className="cp-model">{table.model}</strong>
              {table.subject && <p className="cp-subject">{table.subject}</p>}
              <p className="da-small">{table.documents.length ? `文档 ${table.documents.map(d => `《${d}》`).join('、')}` : '无对应文档'}
                {table.cubeName ? ` · 生成指标 ${table.cubeName}` : ''}</p></div>
            {(table.status === 'DRAFTABLE' || table.status === 'OUTDATED') && <button type="button" className="da-btn da-btn-primary" disabled={!assistantIdle} onClick={() => draft(table)}>
              <Icon name="plus" size="sm" />{table.status === 'DRAFTABLE' ? '起草口径清单' : '按文档更新'}</button>}
          </header>
          {table.status === 'OUTDATED' && <p className="da-alert">文档{table.changedDocuments.map(d => `《${d}》`).join('、')}在上次起草后有变化，更新时只改有差异的口径。</p>}
          {table.errors.length > 0 && <div role="alert" className="da-alert da-alert-error">口径清单有问题：{table.errors.join('；')}</div>}
          {table.conflicts.length > 0 && <div className="da-alert" aria-label="冲突">
            以下口径此前由人修改过，文档中的不同版本未覆盖，待你选择保留哪一个：{table.conflicts.map(c => c.caliber).join('、')}</div>}
          {table.ruleWarnings.map(w => <div key={w} className="da-alert">{w}</div>)}
          {table.scenario && <ScenarioCard table={table} />}

          {progress && listPath && <div className={`cp-progress ${table.publishable ? 'cp-progress-ok' : ''}`} aria-label="核查进度">
            <span><strong>{progress.passed}/{progress.total}</strong> 个口径已通过 · 表说明{table.scenarioReviewed ? '已核查' : '待核查'}</span>
            <span className="da-small">{publishedText(table)}</span>
            {!table.publishable && blockers.length > 0 && <span className="cp-blockers" role="status">
              有口径清单的表只按口径问数，清单补完前知识库不能发布。未完成：{blockers.join('、')}</span>}
          </div>}

          {listPath && (pending.length > 0 || !table.scenarioReviewed) && <div className="cp-actions">
            {!table.scenarioReviewed && <label className="cp-check"><input type="checkbox" checked={scenario} onChange={e => setScenario(e.target.checked)} />
              表说明（业务、统计对象、指标、时间、范围）已核对</label>}
            {pending.length > 0 && <button type="button" className="da-btn da-btn-sm" onClick={() => setPicked(Object.fromEntries(pending.filter(r => !conflicted.has(r.name)).map(r => [r.name, true])))}>全选待核查（{pending.length}）</button>}
            <button type="button" className="da-btn da-btn-primary da-btn-sm" disabled={!!busy || (!scenario && chosen.length === 0)}
              onClick={() => void review()}>{busy === 'review' ? '提交中…' : `核查通过${chosen.length ? `（${chosen.length} 个口径）` : ''}`}</button>
            <span className="da-small">逐行核对条件、待确认事项和取值检查后再勾选；结论只对当前内容有效，改动后需重新核查。</span>
          </div>}

          {table.rows.length === 0 ? <div className="sm-empty">{listPath ? '清单中还没有口径。' : '还没有口径清单。点“起草口径清单”，助手会照抄文档中的口径表，文档没有说明的内容标为待业务确认。'}</div>
            : <div className="cp-table-wrap"><table className="da-table cp-table">
              <colgroup><col className="cp-col-pick" /><col className="cp-col-name" /><col className="cp-col-group" /><col className="cp-col-condition" /><col className="cp-col-pending" /><col className="cp-col-check" /><col className="cp-col-state" /></colgroup>
              <thead><tr><th aria-label="选择" /><th>口径</th><th>所属体系</th><th>取数条件</th><th>待业务确认</th><th>取值检查</th><th>状态</th></tr></thead>
              <tbody>{table.rows.map(r => {
                const synonyms = r.synonyms.filter(s => s !== r.name);
                const canPick = r.review === 'REVIEW_PENDING' && !conflicted.has(r.name);
                return <tr key={r.name}>
                  <td>{canPick && <input type="checkbox" aria-label={`核查 ${r.name}`} checked={!!picked[r.name]} onChange={e => setPicked(p => ({ ...p, [r.name]: e.target.checked }))} />}</td>
                  <td><strong>{r.name}</strong>{synonyms.length > 0 && <div className="da-small">也称 {synonyms.join('、')}</div>}
                    <div className="da-small" title={r.source?.document ?? undefined}>来源：{caliberSource(r)}</div></td>
                  <td>{r.group ?? '—'}</td>
                  <td><ul className="cp-conditions">{conditionLines(r).map(line => <li key={line.column} className={line.unrestricted ? 'cp-unrestricted' : ''}>
                    <code>{line.column}</code> {line.text}</li>)}</ul>{r.note && <div className="da-small cp-note">{r.note}</div>}</td>
                  <td>{r.pending ? <div className="cp-pending">{r.pending}</div> : '—'}</td>
                  <td>{r.valueIssues.length === 0 ? <span className="cp-ok">通过</span>
                    : r.valueIssues.map(i => <div key={`${i.column}:${i.value}`} className="cp-pending">
                      {i.kind === 'missing' ? `表中没有 ${i.column}=${i.value}` : `采样中未见 ${i.column}=${i.value}`}</div>)}</td>
                  <td><span className={`mo-pill ${conflicted.has(r.name) ? 'cp-tag-problem' : REVIEW[r.review].tone}`}>{conflicted.has(r.name) ? '冲突' : REVIEW[r.review].label}</span>
                    {r.verifiedBy && r.review === 'PASSED' && <div className="da-small">{r.verifiedBy} 验证</div>}</td>
                </tr>;
              })}</tbody>
            </table></div>}

          {listPath && verifiable && <section className="cp-verify" aria-label="问题验证">
            <header className="cp-verify-head"><strong>问题验证</strong>
              <span className="da-small mo-grow">平台为每个已核查的口径生成一个问题，按生成的指标展开 SQL 并由数据库校验，不执行查询；请核对每个口径的取数条件与文档是否一致。</span>
              <button type="button" className="da-btn da-btn-sm" disabled={!!busy} onClick={() => void verify()}>{busy === 'verify' ? '生成中…' : checks ? '重新生成' : '生成验证问题'}</button></header>
            {checks && checks.length === 0 && <p className="da-small">没有待验证的口径。</p>}
            {checks?.map(c => <article key={c.caliber} className="cp-case">
              <div className="cp-case-head"><strong>{c.question}</strong>
                {c.error ? <span className="cp-pending">{c.error}</span>
                  : <span className="cp-verdict" role="radiogroup" aria-label={`${c.caliber} 的判定`}>
                    {(['EXPECTED', 'PROBLEM'] as Verdict[]).map(v => <label key={v}><input type="radio" name={`v-${c.caliber}`} checked={verdicts[c.caliber] === v}
                      onChange={() => setVerdicts(p => ({ ...p, [c.caliber]: v }))} />{v === 'EXPECTED' ? '符合预期' : '有问题'}</label>)}
                  </span>}</div>
              <div className="da-small">该口径的取数条件（查询模板中对应的一段，直接作用于表）：</div>
              <pre className="cp-sql">{c.segment}</pre>
              <details><summary className="da-small">问数时的指标查询（按口径名查查询模板）</summary>{c.expanded && <pre className="cp-sql">{c.expanded}</pre>}</details>
            </article>)}
            {checks && checks.some(c => !c.error) && <div className="cp-judge">
              {unjudged.length > 0 && Object.keys(verdicts).length > 0 && <p className="cp-blockers" role="status">
                还有 {unjudged.length} 个口径未选判定：{unjudged.join('、')}。未判定的口径仍为待验证，补完前知识库不能发布。</p>}
              <button type="button" className="da-btn da-btn-primary da-btn-sm" disabled={!!busy || Object.keys(verdicts).length === 0}
              onClick={() => void judge()}>{busy === 'judge' ? '提交中…' : `提交判定（${Object.keys(verdicts).length}）`}</button></div>}
          </section>}

          {(table.viewYaml || table.cubeYaml) && <details className="cp-generated"><summary className="da-small">查看生成的查询模板与指标（只读，修改请改口径清单）</summary>
            {table.cubeYaml && <pre className="cp-sql">{table.cubeYaml}</pre>}
            {table.viewYaml && <pre className="cp-sql">{table.viewYaml}</pre>}
          </details>}
        </div>}
      </div>}
  </section>;
}

/** spec149 P5: what the table answers and how it is chosen, reviewed as the table description. */
function ScenarioCard({ table }: { table: CaliberTable }) {
  const s = table.scenario!;
  const fixed = Object.entries(s.fixedScope);
  return <section className="cp-scenario" aria-label="表说明">
    <dl className="cp-scenario-grid">
      <dt>业务</dt><dd>{keyText(s.business)}</dd>
      <dt>统计对象</dt><dd>{keyText(s.audience)}</dd>
      {s.description && <><dt>说明</dt><dd>{s.description}</dd></>}
      {s.time && <><dt>时间</dt><dd>{s.time.column}（{s.time.grain === 'month' ? '按月' : '按日'}，{s.time.format}）</dd></>}
      <dt>指标</dt><dd><ul className="cp-scenario-list">{s.measures.map(m => <li key={m.name}>
        {m.isDefault && <span className="mo-pill mo-green">默认</span>} {m.label ?? m.column} <code>{m.name}</code>
        {m.description ? `：${m.description}` : ''}{m.synonyms.length ? `（说法：${m.synonyms.join('、')}）` : ''}</li>)}</ul></dd>
      {s.scope.length > 0 && <><dt>范围</dt><dd><ul className="cp-scenario-list">{s.scope.map(d => <li key={d.name}>{scopeText(d)}</li>)}</ul></dd></>}
      {fixed.length > 0 && <><dt>固定范围</dt><dd>{fixed.map(([k, v]) => `${k}=${v}`).join('；')}</dd></>}
      {s.useFor.length > 0 && <><dt>适用</dt><dd>{s.useFor.join('；')}</dd></>}
      {s.notFor.length > 0 && <><dt>不适用</dt><dd>{s.notFor.join('；')}</dd></>}
    </dl>
    {table.scopeIssues.length > 0 && <div className="da-alert" aria-label="范围取值检查">
      范围取值在表中{table.scopeIssues.some(i => i.kind === 'sample') ? '（或采样中）' : ''}找不到：{table.scopeIssues.map(i => `${i.column}=${i.value}`).join('、')}</div>}
  </section>;
}
