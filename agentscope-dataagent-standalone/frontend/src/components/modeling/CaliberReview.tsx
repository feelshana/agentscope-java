import { useEffect, useRef, useState } from 'react';
import { reviewCalibers } from '../../api/calibers';
import type { CaliberRow, CaliberTable } from '../../api/calibers';
import { conditionLines, keyText, nextTodo, reviewQueue, scopeText, SCENARIO_ITEM, stepProgress } from './caliberPresentation';
import type { QueueFilter, QueueState } from './caliberPresentation';
import CaliberRowForm from './CaliberRowForm';
import CaliberStepper from './CaliberStepper';

const FILTERS: { id: QueueFilter; label: string }[] = [
  { id: 'todo', label: '待处理' }, { id: 'problem', label: '有问题' }, { id: 'done', label: '已核对' }, { id: 'all', label: '全部' },
];
const MARK: Record<QueueState, { mark: string; label: string }> = {
  todo: { mark: '○', label: '待核对' }, done: { mark: '✓', label: '已核对' },
  problem: { mark: '●', label: '验证不符合' }, conflict: { mark: '●', label: '有冲突' },
};
const message = (e: unknown) => e instanceof Error ? e.message : String(e);
const typing = (target: EventTarget | null) => target instanceof HTMLElement
  && (target.isContentEditable || ['INPUT', 'TEXTAREA', 'SELECT'].includes(target.tagName));

/**
 * spec153 review: one caliber (or the table description) at a time. Passing binds to the content as
 * shown, so the person reads every condition before the click; J/K move, A passes and moves on.
 */
export default function CaliberReview({ groupId, table, item, assistantIdle, onItem, onReplace, onBack, onVerify, onAsk, onDraft, onCheck, onPublish }: {
  onPublish: () => void;
  groupId: string;
  table: CaliberTable;
  item: string | null;
  assistantIdle: boolean;
  onItem: (item: string) => void;
  onReplace: (table: CaliberTable) => void;
  onBack: () => void;
  onVerify: () => void;
  onAsk: (text: string) => void;
  onDraft: () => void;
  onCheck: () => void;
}) {
  // A finished table opens on the whole queue rather than an empty "to do" list.
  const [filter, setFilter] = useState<QueueFilter>(() => reviewQueue(table, 'todo').length ? 'todo' : 'all');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  // spec153 P1.5: the caliber being edited in the form, if any.
  const [editing, setEditing] = useState<string | null>(null);
  const all = reviewQueue(table, 'all');
  const shown = reviewQueue(table, filter);
  const current = all.find(i => i.id === item) ?? all.find(i => i.state !== 'done') ?? all[0] ?? null;
  const index = current ? all.indexOf(current) : -1;
  const row = current && current.id !== SCENARIO_ITEM ? table.rows.find(r => r.name === current.id) ?? null : null;
  const conflict = row ? table.conflicts.find(c => c.caliber === row.name) ?? null : null;
  const progress = stepProgress(table);
  const reviewedAll = progress.reviewed === progress.reviewTotal && table.conflicts.length === 0;
  const isEditing = !!row && editing === row.name;
  const canPass = !!current && !!table.listPath && current.state === 'todo' && !isEditing;

  const move = (delta: number) => {
    if (!all.length) return;
    const list = shown.length ? shown : all;
    const at = Math.max(0, list.findIndex(i => i.id === current?.id));
    onItem(list[(at + delta + list.length) % list.length].id);
  };
  const pass = async () => {
    if (!current || !table.listPath || !canPass || busy) return;
    setBusy(true); setError('');
    try {
      const scenario = current.id === SCENARIO_ITEM;
      const next = await reviewCalibers(groupId, table.listPath, scenario, scenario ? [] : [current.id]);
      onReplace(next);
      const following = nextTodo(next, current.id);
      if (following) onItem(following);
    } catch (e) { setError(message(e)); } finally { setBusy(false); }
  };
  const keys = useRef({ move, pass });
  keys.current = { move: (delta: number) => { if (!isEditing) move(delta); }, pass };
  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      if (event.ctrlKey || event.metaKey || event.altKey || typing(event.target)) return;
      const key = event.key.toLowerCase();
      if (key === 'j') { event.preventDefault(); keys.current.move(1); }
      else if (key === 'k') { event.preventDefault(); keys.current.move(-1); }
      else if (key === 'a') { event.preventDefault(); void keys.current.pass(); }
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, []);

  const ask = () => {
    if (!current) return;
    const subject = current.id === SCENARIO_ITEM ? '表说明' : `口径「${current.id}」`;
    onAsk(`请修改模型 ${table.model} 的口径清单 ${table.listPath ?? ''} 中的${subject}：`);
  };
  const groups: { name: string | null; items: typeof shown }[] = [];
  for (const i of shown) {
    const last = groups[groups.length - 1];
    if (last && last.name === i.group) last.items.push(i); else groups.push({ name: i.group, items: [i] });
  }

  return <div className="cw-step-page">
    <CaliberStepper table={table} step="review" onBack={onBack} onCheck={onCheck} onPublish={onPublish} />
    {error && <div role="alert" className="da-alert da-alert-error">{error}</div>}
    {!table.listPath ? <div className="da-card sm-empty">还没有口径清单。
      <button type="button" className="da-btn da-btn-primary" disabled={!assistantIdle} onClick={onDraft}>起草口径清单</button></div>
    : <div className="cw-review">
      <aside className="cw-queue" aria-label="口径队列">
        <div className="cw-filters" role="tablist" aria-label="筛选">
          {FILTERS.map(f => <button key={f.id} type="button" role="tab" aria-selected={filter === f.id}
            className={`cw-filter ${filter === f.id ? 'cw-filter-on' : ''}`} onClick={() => setFilter(f.id)}>
            {f.label} {reviewQueue(table, f.id).length}</button>)}
        </div>
        <div className="cw-queue-list">
          {shown.length === 0 && <p className="da-small cw-queue-empty">{filter === 'todo' ? '都核对完了。' : '没有符合条件的口径。'}</p>}
          {groups.map((g, gi) => <div key={`${g.name}-${gi}`}>
            {g.name && <div className="cw-queue-group">{g.name}</div>}
            {g.items.map(i => <button key={i.id} type="button" className={`cw-queue-item cw-q-${i.state} ${i.id === current?.id ? 'cw-queue-on' : ''}`}
              aria-current={i.id === current?.id} onClick={() => onItem(i.id)}>
              <span className="cw-queue-mark" aria-label={MARK[i.state].label}>{MARK[i.state].mark}</span>
              <span className="cw-queue-name">{i.name}</span>
              {i.state === 'problem' && <span className="cw-queue-tag">不符合</span>}
              {i.state === 'conflict' && <span className="cw-queue-tag">冲突</span>}
            </button>)}
          </div>)}
        </div>
        <div className="da-small cw-queue-legend">○ 待核对　✓ 已核对　● 需修改</div>
      </aside>

      <main className="cw-detail">
        <div className="cw-detail-body">
          {table.status === 'OUTDATED' && <p className="da-alert">文档{table.changedDocuments.map(d => `《${d}》`).join('、')}在上次起草后有变化。
            <button type="button" className="sm-text-link" disabled={!assistantIdle} onClick={onDraft}>按文档更新</button></p>}
          {table.errors.length > 0 && <div role="alert" className="da-alert da-alert-error">口径清单有问题：{table.errors.join('；')}</div>}
          {table.ruleWarnings.map(w => <div key={w} className="da-alert">{w}</div>)}
          {!current ? <p className="da-small">清单中还没有口径。</p>
            : current.id === SCENARIO_ITEM ? <ScenarioDetail table={table} state={current.state} />
            : row && isEditing ? <CaliberRowForm key={row.name} groupId={groupId} table={table} row={row} onCancel={() => setEditing(null)}
              onSaved={next => { setEditing(null); onReplace(next); }} />
            : row && <RowDetail row={row} state={current.state} position={`第 ${index + 1} / ${all.length} 项`} conflict={conflict} />}
          {(table.viewYaml || table.cubeYaml) && <details className="cw-generated"><summary className="da-small">查看生成的查询模板与指标（只读，修改请改口径清单）</summary>
            {table.cubeYaml && <pre className="cw-sql">{table.cubeYaml}</pre>}
            {table.viewYaml && <pre className="cw-sql">{table.viewYaml}</pre>}
          </details>}
        </div>
        {!isEditing && <footer className="cw-actions">
          <span className="da-small cw-keys"><kbd>K</kbd> 上一个　<kbd>J</kbd> 下一个　<kbd>A</kbd> 核对通过</span>
          <span className="mo-grow" />
          {current?.state === 'problem' && <button type="button" className="da-btn" onClick={onVerify}>重新验证</button>}
          <button type="button" className="da-btn" disabled={!current || !assistantIdle} title={!assistantIdle ? '助手正在工作' : undefined} onClick={ask}>让助手改这一条…</button>
          {row && <button type="button" className="da-btn" disabled={!!conflict || !assistantIdle}
            title={conflict ? '先选择保留哪个版本' : !assistantIdle ? '助手正在工作，完成后再编辑' : undefined}
            onClick={() => setEditing(row.name)}>编辑</button>}
          {reviewedAll && current?.state === 'done' && progress.passed === progress.verifyTotal
            ? table.published !== 'CURRENT' && <button type="button" className="da-btn da-btn-primary" onClick={onPublish}>去发布</button>
            : reviewedAll && current?.state === 'done'
            ? <button type="button" className="da-btn da-btn-primary" onClick={onVerify}>去验证</button>
            : <button type="button" className="da-btn da-btn-primary" disabled={!canPass || busy}
              title={current?.state === 'problem' ? '验证不符合，修改后再核对' : current?.state === 'conflict' ? '先选择保留哪个版本' : undefined}
              onClick={() => void pass()}>{busy ? '提交中…' : current?.state === 'done' ? '已核对' : '核对通过，下一个'}</button>}
        </footer>}
      </main>
    </div>}
  </div>;
}

function RowDetail({ row, state, position, conflict }: {
  row: CaliberRow; state: QueueState; position: string; conflict: CaliberTable['conflicts'][number] | null;
}) {
  const synonyms = row.synonyms.filter(s => s !== row.name);
  const source = row.source?.kind === 'document' ? `文档《${row.source.document ?? ''}》` : row.source?.kind === 'chat' ? '对话' : row.source?.kind === 'edit' ? '手工编辑' : '—';
  return <article className="cw-row" aria-label={`口径 ${row.name}`}>
    <div className="cw-row-title"><h2>{row.name}</h2>
      <span className={`mo-pill ${state === 'done' ? 'mo-green' : state === 'todo' ? 'mo-amber' : 'cw-pill-problem'}`}>{MARK[state].label}</span>
      <span className="mo-pill">{position}</span></div>
    <p className="da-small">{synonyms.length ? `也叫：${synonyms.join('、')} · ` : ''}所属：{row.group ?? '未分组'} · 来源：{source}
      {row.reviewedBy ? ` · ${row.reviewedBy} 核对` : ''}{row.verifiedBy ? ` · ${row.verifiedBy} 验证` : ''}</p>
    {conflict && <div className="da-alert" role="status">这个口径此前由人修改过，文档{conflict.document ? `《${conflict.document}》` : ''}中的版本不同，未覆盖。
      选择保留哪个版本后才能核对：让助手按文档版本更新，或保留当前版本。
      {conflict.proposed && <pre className="cw-sql">{JSON.stringify(conflict.proposed, null, 2)}</pre>}</div>}
    <section className="cw-block" aria-label="取数条件">
      <h3>取数条件</h3>
      {row.condition && <p className="cw-condition-text">{row.condition}</p>}
      <dl className="cw-conditions">{conditionLines(row).map(line => <div key={line.column} className={line.unrestricted ? 'cw-unrestricted' : ''}>
        <dt><code>{line.column}</code></dt><dd>{line.text}</dd></div>)}</dl>
      {row.combine && <p className="da-small">合并方式：{row.combine === 'sum' ? '按条件合并后在一行内统计' : row.combine}</p>}
      <p className="da-small">统计结果表：一个结果只取一行，不同口径之间不相加。</p>
    </section>
    {row.note && <section className="cw-block"><h3>说明</h3><p>{row.note}</p></section>}
    {row.pending && <section className="cw-block cw-block-warn"><h3>待业务确认</h3><p>{row.pending}</p></section>}
    {row.valueIssues.length > 0 && <p className="cw-hint" aria-label="取值检查">
      取值检查（只提示，不影响核对和发布）：{row.valueIssues.map(i => `${i.kind === 'missing' ? '表中没有' : '采样中未见'} ${i.column}=${i.value}`).join('；')}。当前数据可能不全，以文档为准。</p>}
  </article>;
}

function ScenarioDetail({ table, state }: { table: CaliberTable; state: QueueState }) {
  const s = table.scenario!;
  const fixed = Object.entries(s.fixedScope);
  return <article className="cw-row" aria-label="表说明">
    <div className="cw-row-title"><h2>表说明</h2>
      <span className={`mo-pill ${state === 'done' ? 'mo-green' : 'mo-amber'}`}>{state === 'done' ? '已核对' : '待核对'}</span></div>
    <p className="da-small">问数先按业务和统计对象选表，再按指标、时间和范围取数。</p>
    <dl className="cw-scenario">
      <dt>业务</dt><dd>{keyText(s.business)}</dd>
      <dt>统计对象</dt><dd>{keyText(s.audience)}</dd>
      {s.description && <><dt>说明</dt><dd>{s.description}</dd></>}
      {s.time && <><dt>时间</dt><dd>{s.time.column}（{s.time.grain === 'month' ? '按月' : '按日'}，{s.time.format}）</dd></>}
      <dt>指标</dt><dd><ul>{s.measures.map(m => <li key={m.name}>
        {m.isDefault && <span className="mo-pill mo-green">默认</span>} {m.label ?? m.column} <code>{m.name}</code>
        {m.description ? `：${m.description}` : ''}{m.synonyms.length ? `（说法：${m.synonyms.join('、')}）` : ''}</li>)}</ul>
        {!s.measures.some(m => m.isDefault) && <p className="cw-hint cw-hint-warn">没有默认指标：问数时没说指标，助手会先问用哪个。</p>}</dd>
      {s.scope.length > 0 && <><dt>范围</dt><dd><ul>{s.scope.map(d => <li key={d.name}>{scopeText(d)}</li>)}</ul></dd></>}
      {fixed.length > 0 && <><dt>固定范围</dt><dd>{fixed.map(([k, v]) => `${k}=${v}`).join('；')}</dd></>}
      {s.useFor.length > 0 && <><dt>适用</dt><dd>{s.useFor.join('；')}</dd></>}
      {s.notFor.length > 0 && <><dt>不适用</dt><dd>{s.notFor.join('；')}</dd></>}
    </dl>
    {table.scopeIssues.length > 0 && <p className="cw-hint" aria-label="范围取值检查">
      范围取值检查（只提示）：{table.scopeIssues.map(i => `${i.column}=${i.value}`).join('、')} 在当前数据中没有找到，以文档为准。</p>}
  </article>;
}
