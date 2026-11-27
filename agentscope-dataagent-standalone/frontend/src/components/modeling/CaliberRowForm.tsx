import { useId, useState } from 'react';
import { editCaliber } from '../../api/calibers';
import type { CaliberRow, CaliberRowEdit, CaliberTable } from '../../api/calibers';
import { columnText, editProblems, rowEditChanged, rowEditOf, splitWords } from './caliberPresentation';

const UNRESTRICTED = '不限';
const message = (e: unknown) => e instanceof Error ? e.message : String(e);

/**
 * spec153 P1.5: edits one caliber in business words instead of YAML. The platform compiles the
 * whole list before saving; the row is then recorded as a manual edit and has to be reviewed and
 * verified again. Conditions can only use columns the list already uses.
 */
export default function CaliberRowForm({ groupId, table, row, onSaved, onCancel }: {
  groupId: string;
  table: CaliberTable;
  row: CaliberRow;
  onSaved: (table: CaliberTable) => void;
  onCancel: () => void;
}) {
  const id = useId();
  const [edit, setEdit] = useState<CaliberRowEdit>(() => rowEditOf(row));
  const [synonyms, setSynonyms] = useState(() => rowEditOf(row).synonyms.join('、'));
  const [drafts, setDrafts] = useState<Record<string, string>>({});
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const current: CaliberRowEdit = { ...edit, synonyms: splitWords(synonyms) };
  const problems = editProblems(current);
  const changed = rowEditChanged(row, current);
  const unused = table.columns.filter(c => !Object.keys(edit.where).some(k => k.toLowerCase() === c.name.toLowerCase()));
  const multiple = Object.values(edit.where).some(v => v.length > 1);

  const setValues = (column: string, values: string[]) => setEdit(e => ({ ...e, where: { ...e.where, [column]: values } }));
  const addValue = (column: string, raw: string) => {
    const words = splitWords(raw);
    if (!words.length) return;
    const existing = (edit.where[column] ?? []).filter(v => v !== UNRESTRICTED);
    setValues(column, [...new Set([...existing, ...words])]);
    setDrafts(d => ({ ...d, [column]: '' }));
  };
  const removeColumn = (column: string) => setEdit(e => {
    const where = { ...e.where };
    delete where[column];
    return { ...e, where };
  });
  const save = async () => {
    if (!table.listPath || problems.length || busy) return;
    setBusy(true); setError('');
    try { onSaved(await editCaliber(groupId, table.listPath, row.name, current)); }
    catch (e) { setError(message(e)); } finally { setBusy(false); }
  };

  return <form className="cw-form" aria-label={`编辑口径 ${row.name}`} onSubmit={event => { event.preventDefault(); void save(); }}>
    <div className="cw-row-title"><h2>{row.name}</h2><span className="mo-pill mo-amber">编辑中</span></div>
    <p className="da-small">口径名不能修改。保存后这一条记为“手工编辑”，需要重新核对和验证；之后文档再更新也不会覆盖它，会提示冲突由你选择。</p>
    {error && <div role="alert" className="da-alert da-alert-error">{error}</div>}

    <div className="cw-form-grid">
      <label htmlFor={`${id}-group`}>所属</label>
      <input id={`${id}-group`} className="da-input" value={edit.group} onChange={e => setEdit(x => ({ ...x, group: e.target.value }))} />
      <label htmlFor={`${id}-synonyms`}>也叫</label>
      <div><input id={`${id}-synonyms`} className="da-input" value={synonyms} placeholder="用顿号分隔，例如：视频APP、咪咕视频客户端"
        onChange={e => setSynonyms(e.target.value)} /><span className="da-small">问数时这些说法会对应到这个口径。</span></div>
    </div>

    <section className="cw-block" aria-label="取数条件">
      <h3>取数条件 <span className="da-small">同一口径的各条件同时生效</span></h3>
      {Object.entries(edit.where).map(([column, values]) => {
        const info = table.columns.find(c => c.name.toLowerCase() === column.toLowerCase());
        const listId = `${id}-values-${column}`;
        const unrestricted = values.length === 1 && values[0] === UNRESTRICTED;
        return <div key={column} className="cw-cond">
          <div className="cw-cond-col">{columnText(table.columns, column)}</div>
          <div className="cw-cond-values">
            {unrestricted ? <span className="cw-chip cw-chip-muted">不限（不加条件）</span>
              : values.map(v => <span key={v} className={`cw-chip ${info?.values && !info.values.includes(v) ? 'cw-chip-warn' : ''}`}
                title={info?.values && !info.values.includes(v) ? '当前数据中没有这个取值，以文档为准' : undefined}>{v}
                <button type="button" aria-label={`删除取值 ${v}`} onClick={() => setValues(column, values.filter(x => x !== v))}>×</button></span>)}
            <input className="da-input cw-cond-input" list={listId} value={drafts[column] ?? ''} placeholder="输入或选择取值，回车添加"
              aria-label={`${columnText(table.columns, column)} 的取值`}
              onChange={e => setDrafts(d => ({ ...d, [column]: e.target.value }))}
              onKeyDown={e => { if (e.key === 'Enter') { e.preventDefault(); addValue(column, drafts[column] ?? ''); } }}
              onBlur={() => addValue(column, drafts[column] ?? '')} />
            {info?.values && <datalist id={listId}>{info.values.slice(0, 200).map(v => <option key={v} value={v} />)}</datalist>}
            {!unrestricted && <button type="button" className="sm-text-link" onClick={() => setValues(column, [UNRESTRICTED])}>设为不限</button>}
          </div>
          <button type="button" className="sm-text-link cw-cond-remove" aria-label={`删除条件 ${column}`} onClick={() => removeColumn(column)}>删除条件</button>
        </div>;
      })}
      {unused.length > 0 && <label className="cw-cond-add">添加条件
        <select className="da-input" value="" onChange={e => { if (e.target.value) setValues(e.target.value, []); }}>
          <option value="">选择列…</option>
          {unused.map(c => <option key={c.name} value={c.name}>{columnText(table.columns, c.name)}</option>)}
        </select></label>}
      <p className="da-small">候选取值来自平台对表中数据的统计；文档里有、但当前数据里没有的取值可以直接输入，以文档为准（标为琥珀色）。</p>
      {multiple && <label className="cw-check-line"><input type="checkbox" checked={edit.combine}
        onChange={e => setEdit(x => ({ ...x, combine: e.target.checked }))} />
        合并统计：业务已确认这几个取值对应的行可以相加（统计结果表通常不能相加，不确定时请拆成多个口径）</label>}
    </section>

    <div className="cw-form-grid">
      <label htmlFor={`${id}-unit`}>单位</label>
      <input id={`${id}-unit`} className="da-input" value={edit.unit} placeholder="不写则使用指标的单位" onChange={e => setEdit(x => ({ ...x, unit: e.target.value }))} />
      <label htmlFor={`${id}-note`}>说明</label>
      <textarea id={`${id}-note`} className="da-input" rows={2} value={edit.note} onChange={e => setEdit(x => ({ ...x, note: e.target.value }))} />
      <label htmlFor={`${id}-pending`}>待业务确认</label>
      <textarea id={`${id}-pending`} className="da-input" rows={2} value={edit.pending} placeholder="确认后清空" onChange={e => setEdit(x => ({ ...x, pending: e.target.value }))} />
    </div>

    {problems.length > 0 && <ul className="cw-problems" role="status">{problems.map(p => <li key={p}>{p}</li>)}</ul>}
    <div className="cw-form-actions">
      <button type="button" className="da-btn" disabled={busy} onClick={onCancel}>取消</button>
      <button type="submit" className="da-btn da-btn-primary" disabled={busy || !changed || problems.length > 0}>{busy ? '保存中…' : '保存'}</button>
    </div>
  </form>;
}
