import { useState } from 'react';
import type { CSSProperties } from 'react';
import { editDraftItem, SOURCE_LABEL } from '../../api/draftReview';
import type { DraftItem, DraftItemEdit } from '../../api/draftReview';

const TABLE = '__table__';
const ICON: Record<DraftItem['category'], string> = { MODEL: '▦', RELATIONSHIP: '⇄', SEMANTIC: '≡', NOTE: '!' };
const GROUP: Record<DraftItem['category'], string> = { MODEL: '模型', RELATIONSHIP: '关系', SEMANTIC: '业务语义', NOTE: '注意事项' };
const ORDER: DraftItem['category'][] = ['MODEL', 'RELATIONSHIP', 'SEMANTIC', 'NOTE'];
const TAG_LABEL: Record<string, string> = { enum: '取值', null: '空值', time: '时间', unit: '单位', pk: '主键', fk: '关联' };

const S: Record<string, CSSProperties> = {
  row: { display: 'flex', alignItems: 'center', gap: 8, padding: '5px 10px', minHeight: 30 },
  icon: { width: 16, textAlign: 'center', color: 'var(--da-text-muted)', flex: '0 0 16px' },
  title: { fontWeight: 500, whiteSpace: 'nowrap' },
  sub: { flex: 1, minWidth: 0, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap', color: 'var(--da-text-muted)', fontSize: 12 },
  pill: { fontSize: 11, padding: '1px 6px', borderRadius: 4, whiteSpace: 'nowrap' },
  basis: { fontSize: 11, color: 'var(--da-text-muted)', whiteSpace: 'nowrap' },
  link: { background: 'none', border: 'none', padding: '0 2px', cursor: 'pointer', color: 'var(--da-primary)', fontSize: 12 },
  tag: { fontSize: 11, padding: '0 5px', borderRadius: 4, background: 'var(--da-surface-sunken, #f1f5f9)', color: 'var(--da-text-muted)', marginRight: 4 },
  detail: { padding: '2px 10px 8px 34px', fontSize: 12.5 },
};

/**
 * A column description split for display: the platform's sampling boilerplate is hidden and
 * "[enum] …" style annotation lines become small labelled notes.
 */
export function readable(text: string | null | undefined): { body: string; tags: { tag: string; text: string }[] } {
  const body: string[] = [];
  const tags: { tag: string; text: string }[] = [];
  for (const raw of (text ?? '').split('\n')) {
    const line = raw.trim();
    if (!line) continue;
    const tagged = /^\[(\w+)\]\s*(.*)$/.exec(line);
    if (tagged) { tags.push({ tag: tagged[1], text: tagged[2] }); continue; }
    const cleaned = line.replace(/[；;]?\s*采样观察值[:：].*$/, '').trim();
    if (cleaned) body.push(cleaned);
  }
  return { body: body.join(' '), tags };
}

function changePill(item: DraftItem): { text: string; style: CSSProperties } {
  if (item.kind === 'REMOVED') return { text: '已删除', style: { background: 'rgba(225,29,72,.10)', color: 'var(--da-danger)' } };
  if (item.category === 'MODEL') {
    const columns = item.fields.filter(f => f.name !== TABLE).length;
    const text = item.kind === 'ADDED' ? '新增' : columns ? `改 ${columns} 个字段` : '改表说明';
    return { text, style: { background: 'rgba(22,163,74,.12)', color: 'var(--da-success)' } };
  }
  return { text: item.kind === 'ADDED' ? '新增' : '修改', style: { background: 'rgba(22,163,74,.12)', color: 'var(--da-success)' } };
}

/**
 * spec092 §7 "AI 建模结果": one compact row per table, relationship, rule or note. A row opens to
 * its detail (models as field | description), can be edited, and — except models — deleted.
 */
export default function DraftItemsList({ groupId, items, disabled, onChanged, limit, grouped = false }: {
  groupId: string;
  items: DraftItem[];
  /** The assistant is running: edits would race its writes. */
  disabled?: boolean;
  onChanged: () => void;
  /** Rows shown before "展开全部". */
  limit?: number;
  /** Show category headings (draft drawer) instead of one flat list (under a reply). */
  grouped?: boolean;
}) {
  const [open, setOpen] = useState<string | null>(null);
  const [editing, setEditing] = useState<string | null>(null);
  const [form, setForm] = useState<Record<string, string>>({});
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [all, setAll] = useState(false);

  const save = async (edit: DraftItemEdit) => {
    setBusy(true); setError(null);
    try {
      await editDraftItem(groupId, edit);
      setEditing(null);
      onChanged();
      window.dispatchEvent(new CustomEvent('modeling:updated', { detail: { groupId } }));
    } catch (e) { setError(e instanceof Error ? e.message : '保存失败'); }
    finally { setBusy(false); }
  };
  const startEdit = (item: DraftItem) => {
    setOpen(item.id); setEditing(item.id); setError(null);
    if (item.category === 'MODEL') {
      const values: Record<string, string> = { [TABLE]: item.text ?? '' };
      item.fields.forEach(f => { values[f.name] = f.after ?? ''; });
      setForm(values);
    } else if (item.category === 'RELATIONSHIP') setForm({ condition: item.title });
    else setForm({ text: item.text ?? '' });
  };
  const submit = (item: DraftItem) => {
    if (item.category === 'MODEL') {
      const { [TABLE]: table, ...columns } = form;
      return save({ category: 'MODEL', model: item.title, tableDescription: table, columns });
    }
    if (item.category === 'RELATIONSHIP')
      return save({ category: 'RELATIONSHIP', name: item.text ?? item.title, condition: form.condition });
    return save({ category: item.category, path: item.path, original: item.text ?? '', text: form.text });
  };
  const remove = (item: DraftItem) => item.category === 'RELATIONSHIP'
    ? save({ category: 'RELATIONSHIP', name: item.text ?? item.title, delete: true })
    : save({ category: item.category, path: item.path, original: item.text ?? '', delete: true });

  const sorted = ORDER.flatMap(c => items.filter(i => i.category === c));
  const shown = limit && !all ? sorted.slice(0, limit) : sorted;
  let lastGroup: string | null = null;
  return <div>
    {error && <div role="alert" style={{ color: 'var(--da-danger)', fontSize: 12, padding: '4px 10px' }}>{error}</div>}
    {shown.map((item, index) => {
      const heading = grouped && item.category !== lastGroup ? GROUP[item.category] : null;
      lastGroup = item.category;
      const expanded = open === item.id;
      const pill = changePill(item);
      const summary = item.category === 'MODEL' ? readable(item.text).body
        : item.category === 'RELATIONSHIP' ? item.summary ?? '' : '';
      const title = item.title;
      return <div key={item.id}>
        {heading && <div style={{ fontSize: 12, color: 'var(--da-text-muted)', padding: '8px 10px 2px' }}>
          {heading}（{items.filter(i => i.category === item.category).length}）</div>}
        <div style={{ borderTop: index && !heading ? '1px solid var(--da-border)' : undefined }}>
          <div style={{ ...S.row, cursor: 'pointer' }} onClick={() => setOpen(expanded ? null : item.id)}>
            <span style={S.icon} aria-hidden="true">{ICON[item.category]}</span>
            <span style={{ ...(item.category === 'MODEL' ? S.title : { flex: summary ? undefined : 1, minWidth: 0, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }),
              textDecoration: item.kind === 'REMOVED' ? 'line-through' : undefined }}>{title}</span>
            {summary ? <span style={S.sub}>{summary}</span> : null}
            <span style={{ ...S.pill, ...pill.style }}>{pill.text}</span>
            {item.basis.length > 0 && <span style={S.basis}>{item.basis.map(s => SOURCE_LABEL[s] ?? s).join('·')}</span>}
            <span style={{ ...S.icon, flex: '0 0 12px' }} aria-hidden="true">{expanded ? '▾' : '▸'}</span>
          </div>
          {expanded && editing !== item.id && <div style={S.detail}>
            <Detail item={item} />
            <div style={{ marginTop: 4 }}>
              {item.editable && <button style={S.link} disabled={disabled || busy} onClick={() => startEdit(item)}>编辑</button>}
              {item.deletable && <button style={{ ...S.link, color: 'var(--da-danger)' }} disabled={disabled || busy} onClick={() => void remove(item)}>删除</button>}
              {disabled && <span style={S.basis}>助手运行中，完成后可编辑</span>}
            </div>
          </div>}
          {editing === item.id && <div style={{ ...S.detail, display: 'flex', flexDirection: 'column', gap: 6 }}>
            {Object.keys(form).map(key => <label key={key} style={{ display: 'block', fontSize: 12 }}>
              <span style={{ color: 'var(--da-text-muted)' }}>{item.category === 'MODEL' ? (key === TABLE ? '表说明' : key) : item.category === 'RELATIONSHIP' ? '关联条件' : '内容'}</span>
              <textarea className="da-input" rows={key === 'text' ? 3 : 2} value={form[key]}
                onChange={e => setForm(previous => ({ ...previous, [key]: e.target.value }))}
                style={{ width: '100%', boxSizing: 'border-box', display: 'block', fontSize: 12.5 }} />
            </label>)}
            <div style={{ display: 'flex', gap: 6 }}>
              <button className="da-btn da-btn-primary da-btn-sm" disabled={disabled || busy} onClick={() => void submit(item)}>{busy ? '保存中…' : '保存'}</button>
              <button className="da-btn da-btn-sm" onClick={() => setEditing(null)}>取消</button>
            </div>
          </div>}
        </div>
      </div>;
    })}
    {limit && sorted.length > limit && <div style={{ ...S.row, borderTop: '1px solid var(--da-border)' }}>
      <span style={S.icon} />
      <button style={S.link} onClick={() => setAll(!all)}>{all ? '收起' : `还有 ${sorted.length - limit} 条 · 展开全部`}</button>
    </div>}
  </div>;
}

function Note({ text }: { text: string | null }) {
  const { body, tags } = readable(text);
  return <span>
    {body || <span style={{ color: 'var(--da-text-muted)' }}>（空）</span>}
    {tags.map((t, i) => <span key={i} style={{ display: 'block', marginTop: 2 }}>
      <span style={S.tag}>{TAG_LABEL[t.tag] ?? t.tag}</span>{t.text}</span>)}
  </span>;
}

function Detail({ item }: { item: DraftItem }) {
  const [before, setBefore] = useState(false);
  if (item.category === 'MODEL') {
    const fields = item.fields.filter(f => f.name !== TABLE);
    const table = item.fields.find(f => f.name === TABLE);
    const hasBefore = item.fields.some(f => f.before);
    return <div>
      {table && <div style={{ marginBottom: 6 }}><span style={{ color: 'var(--da-text-muted)' }}>表说明：</span><Note text={table.after} /></div>}
      {fields.length > 0 && <table style={{ width: '100%', borderCollapse: 'collapse' }}><tbody>
        {fields.map(f => <tr key={f.name} style={{ verticalAlign: 'top' }}>
          <td style={{ padding: '3px 10px 3px 0', whiteSpace: 'nowrap', width: 1 }}><code>{f.name}</code></td>
          <td style={{ padding: '3px 0' }}><Note text={f.after} />
            {before && f.before && <div style={{ color: 'var(--da-text-muted)', textDecoration: 'line-through', marginTop: 2 }}>{readable(f.before).body}</div>}</td>
        </tr>)}
      </tbody></table>}
      {hasBefore && <button style={S.link} onClick={() => setBefore(!before)}>{before ? '隐藏原来' : '查看原来'}</button>}
    </div>;
  }
  if (item.category === 'RELATIONSHIP')
    return <div><code>{item.title}</code>{item.summary ? ` · ${item.summary}` : ''}</div>;
  return <div style={{ whiteSpace: 'pre-wrap', textDecoration: item.kind === 'REMOVED' ? 'line-through' : undefined }}>{item.text}
    <div style={S.basis}>{item.path}</div></div>;
}
