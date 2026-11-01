import { useMemo, useState } from 'react';
import YamlContent from './YamlContent';
import { modelTag, relationsOf, unrelatedModels } from './semanticSnapshot';
import type { SemanticColumn, SemanticModel, SemanticSnapshot } from './semanticSnapshot';

function columnMark(model: SemanticModel, column: SemanticColumn, snapshot: SemanticSnapshot) {
  if (column.primaryKey) return 'PK';
  const joined = relationsOf(snapshot, model.name).some(r => (r.left === model.name ? r.sourceColumns : r.targetColumns).includes(column.name));
  if (joined) return '关联键';
  return column.calculated ? '计算字段' : '';
}

/**
 * spec106 模型页: list on the left, one model's fields and joins on the right. Read-only; a change
 * goes through the modeling conversation so it lands in the draft like any other.
 */
export default function ModelsPanel({ snapshot, selected, onSelect, files, onOpenRelation, onAsk, emptyText }: {
  snapshot: SemanticSnapshot;
  selected: string | null;
  onSelect: (name: string) => void;
  /** Project files by path; omitted where this user cannot read YAML. */
  files?: Map<string, string>;
  onOpenRelation: (name: string) => void;
  onAsk?: (text: string) => void;
  emptyText: string;
}) {
  const [query, setQuery] = useState('');
  const [yaml, setYaml] = useState(false);
  const unrelated = useMemo(() => new Set(unrelatedModels(snapshot).map(m => m.name)), [snapshot]);
  const keyword = query.trim().toLowerCase();
  const shown = snapshot.models.filter(m => !keyword || m.name.toLowerCase().includes(keyword)
    || (m.description ?? '').toLowerCase().includes(keyword) || m.columns.some(c => c.name.toLowerCase().includes(keyword)));
  const model = snapshot.models.find(m => m.name === selected) ?? shown[0] ?? null;
  const text = model?.path ? files?.get(model.path) : undefined;
  if (!snapshot.models.length) return <div className="sm-empty">{emptyText}</div>;

  return <div className="sm-models">
    <div className="sm-model-list">
      <input className="da-input" aria-label="搜索模型或字段" placeholder="搜索模型或字段" value={query} onChange={e => setQuery(e.target.value)} />
      <div className="sm-model-items">
        {shown.map(m => {
          const tag = modelTag(m, unrelated.has(m.name));
          return <button key={m.name} type="button" className={`sm-model-item ${m.name === model?.name ? 'sm-model-on' : ''}`} onClick={() => onSelect(m.name)}>
            <span className="sm-model-line"><strong title={m.name}>{m.name}</strong><span className={`sm-tag sm-tag-${tag.tone}`}>{tag.label}</span></span>
            <small>{m.columns.length} 个字段</small>
          </button>;
        })}
        {!shown.length && <div className="da-small">没有匹配的模型或字段</div>}
      </div>
    </div>
    {model && <div className="sm-model-detail">
      <div className="sm-detail-head">
        <div style={{ flex: 1, minWidth: 240 }}><h2>{model.name}</h2><p>{model.description || '业务含义待补充'}</p></div>
        {text !== undefined && <div role="group" aria-label="查看方式" className="sm-switch">
          <button type="button" aria-pressed={!yaml} className={!yaml ? 'sm-switch-on' : ''} onClick={() => setYaml(false)}>字段</button>
          <button type="button" aria-pressed={yaml} className={yaml ? 'sm-switch-on' : ''} onClick={() => setYaml(true)}>YAML</button>
        </div>}
      </div>
      <div className="sm-links">
        {relationsOf(snapshot, model.name).length > 0 && <span>关联到</span>}
        {relationsOf(snapshot, model.name).map(r => <button key={r.name} type="button" className="sm-link" onClick={() => onOpenRelation(r.name)}>{r.left === model.name ? r.right : r.left}</button>)}
        {unrelated.has(model.name) && <span className="sm-warn">这个模型没有关联到其他模型，问数时不能和其他表一起分析。</span>}
      </div>
      {yaml && text !== undefined ? <div className="sm-yaml"><div className="da-small">{model.path}</div><YamlContent text={text} /></div>
        : <div className="sm-table-box"><table className="sm-table">
          <thead><tr><th>字段</th><th>类型</th><th>业务含义</th><th>标记</th></tr></thead>
          <tbody>{model.columns.map(c => <tr key={c.name}>
            <td className="sm-code">{c.name}</td><td>{c.type || '—'}</td>
            <td>{c.description || '—'}{c.expression && c.calculated ? <div className="da-small sm-code">{c.expression}</div> : null}</td>
            <td>{columnMark(model, c, snapshot)}</td>
          </tr>)}</tbody>
        </table></div>}
      {onAsk && <p className="sm-hint">这里只读。需要调整时，<button type="button" className="sm-text-link" onClick={() => onAsk(`请调整模型 ${model.name}：`)}>让建模助手修改</button>，改动进入草稿，发布后生效。</p>}
    </div>}
  </div>;
}
