import { useState } from 'react';
import { revertDraftFile, SOURCE_LABEL } from '../../api/draftReview';
import type { Confirmation, ConfirmationAnswer, DraftItem } from '../../api/draftReview';
import ConfirmationCards from './ConfirmationCards';

const CATEGORY: Record<DraftItem['category'], string> = { MODEL: '模型', RELATIONSHIP: '关系', SEMANTIC: '规则', NOTE: '注意事项' };
const KIND: Record<DraftItem['kind'], string> = { ADDED: '新增', MODIFIED: '修改', REMOVED: '删除' };
const message = (e: unknown) => e instanceof Error ? e.message : String(e);

/**
 * spec117 §3c 待确认: what the assistant asks you, and what it changed that you have not looked at.
 * Looking is not approving: changes are already in the draft and publish with it (ADR0111); this
 * list only helps go through them one by one.
 */
export default function PendingPanel({ groupId, confirmations, onAnswer, items, isSeen, onSeen, onSeenAll, onLocate, onChanged, disabled }: {
  groupId: string;
  confirmations: Confirmation[];
  onAnswer: (answers: ConfirmationAnswer[]) => void;
  /** The marked changes (one turn or the whole draft). */
  items: DraftItem[];
  isSeen: (item: DraftItem) => boolean;
  onSeen: (item: DraftItem) => void;
  onSeenAll: () => void;
  onLocate: (item: DraftItem) => void;
  onChanged: () => void;
  /** Why changes cannot be undone right now (the assistant is running), or null. */
  disabled: string | null;
}) {
  const [showSeen, setShowSeen] = useState(false);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState<string | null>(null);
  const [opened, setOpened] = useState<string | null>(null);
  const unseen = items.filter(i => !isSeen(i));
  const shown = showSeen ? items : unseen;
  const undo = async (item: DraftItem) => {
    const others = items.filter(i => i !== item && i.path === item.path).length;
    const warning = `撤销会把 ${item.path} 恢复为线上版本${others ? `，同一文件里另外 ${others} 处改动也会一起撤销` : ''}。继续？`;
    if (!window.confirm(warning)) return;
    setBusy(item.id); setError('');
    try { await revertDraftFile(groupId, item.path); onChanged(); } catch (e) { setError(message(e)); } finally { setBusy(null); }
  };
  return <div className="pp">
    <section className="pp-sec">
      <h4>助手在问你的 <span className="pp-count">{confirmations.length}</span></h4>
      {confirmations.length ? <ConfirmationCards key={confirmations.map(c => c.id).join(',')} questions={confirmations} onSubmit={onAnswer} />
        : <p className="pp-empty">没有等你决定的口径。</p>}
    </section>
    <section className="pp-sec">
      <h4>助手改了、你还没看的 <span className="pp-count pp-count-red">{unseen.length}</span>
        <span className="pp-grow" />
        {items.length > unseen.length && <button type="button" className="pp-link" onClick={() => setShowSeen(!showSeen)}>{showSeen ? '只看未看' : `含已看 ${items.length - unseen.length}`}</button>}
        {unseen.length > 1 && <button type="button" className="pp-link" onClick={onSeenAll}>全部标为已看</button>}
      </h4>
      <p className="pp-hint">改动已经在草稿里，发布时一起生效；这里帮你逐条过一遍，看过的会从红色变成灰色。</p>
      {error && <div role="alert" className="pp-error">{error}</div>}
      {shown.length === 0 && <p className="pp-empty">{items.length ? '都看过了。' : '还没有标出的改动。助手每轮结束后，改动会自动列在这里。'}</p>}
      {shown.map(item => <ChangeItem key={item.id} item={item} seen={isSeen(item)} open={opened === item.id} busy={busy === item.id} disabled={disabled}
        onToggle={() => setOpened(opened === item.id ? null : item.id)} onLocate={() => { onLocate(item); onSeen(item); }} onSeen={() => onSeen(item)} onUndo={() => void undo(item)} />)}
    </section>
  </div>;
}

/** One change as a single line; details (fields, before/after) only when opened. */
function ChangeItem({ item, seen, open, busy, disabled, onToggle, onLocate, onSeen, onUndo }: {
  item: DraftItem; seen: boolean; open: boolean; busy: boolean; disabled: string | null;
  onToggle: () => void; onLocate: () => void; onSeen: () => void; onUndo: () => void;
}) {
  const fields = item.fields.filter(f => f.name !== '__table__');
  const table = item.fields.find(f => f.name === '__table__');
  const line = item.category === 'MODEL'
    ? item.kind === 'ADDED' ? `新增模型，${fields.length} 个字段，说明已写 ${fields.filter(f => f.after).length}/${fields.length}` : item.summary ?? ''
    : item.category === 'RELATIONSHIP' ? `${item.title}${item.summary ? ` · ${item.summary}` : ''}` : (item.text ?? '').split('\n')[0];
  const locatable = !(item.kind === 'REMOVED' && item.category !== 'MODEL');
  return <article className={`pp-item ${seen ? 'pp-item-seen' : ''} ${item.kind === 'ADDED' ? 'pp-item-added' : ''}`}>
    <div className="pp-item-head">
      <button type="button" className="pp-toggle" aria-expanded={open} onClick={onToggle}>
        <span className="pp-caret">{open ? '▾' : '▸'}</span>
        <span className={`pp-tag pp-tag-${item.kind}`}>{CATEGORY[item.category]} · {KIND[item.kind]}</span>
        <span className="pp-line"><b>{item.category === 'MODEL' ? item.title : ''}</b>{item.category === 'MODEL' ? ' ' : ''}{line}</span>
      </button>
      {locatable && <button type="button" className="pp-mini" onClick={onLocate}>定位</button>}
      {!seen && <button type="button" className="pp-mini" onClick={onSeen}>已看</button>}
    </div>
    {open && <div className="pp-body">
      {item.basis.length > 0 && <div className="pp-hint">来源：{item.basis.map(s => SOURCE_LABEL[s] ?? s).join('、')} · {item.path}</div>}
      {item.category !== 'MODEL' && item.text && <div className="pp-text">{item.text}</div>}
      {item.category === 'MODEL' && item.kind === 'ADDED' && <>
        {table?.after && <Clamp label="表说明" text={table.after} />}
        <table className="pp-fields"><thead><tr><th>字段</th><th>说明</th></tr></thead>
          <tbody>{fields.map(f => <tr key={f.name}><td className="pp-mono">{f.name}</td><td><Clamp text={f.after || '（无说明）'} /></td></tr>)}</tbody></table>
      </>}
      {item.category === 'MODEL' && item.kind !== 'ADDED' && <>
        {table && <Change name="表说明" before={table.before} after={table.after} />}
        {fields.map(f => <Change key={f.name} name={f.name} before={f.before} after={f.after} />)}
      </>}
      <div className="pp-actions"><span className="pp-grow" />
        <button type="button" className="da-btn da-btn-sm pp-danger" disabled={!!disabled || busy} title={disabled ?? '恢复为线上版本'} onClick={onUndo}>撤销</button>
      </div>
    </div>}
  </article>;
}

/** Before above after, each on its own lines; long text folds to three lines. */
function Change({ name, before, after }: { name: string; before: string | null; after: string | null }) {
  return <div className="pp-change">
    <div className="pp-mono pp-change-name">{name}</div>
    <div className="pp-before">{before || '（空）'}</div>
    <Clamp text={after || '（空）'} />
  </div>;
}

function Clamp({ label, text }: { label?: string; text: string }) {
  const [full, setFull] = useState(false);
  const long = text.length > 90;
  return <div className="pp-clamp">
    {label && <span className="pp-clamp-label">{label}</span>}
    <span className={!full && long ? 'pp-clamp-3' : ''}>{text}</span>
    {long && <button type="button" className="pp-link" onClick={() => setFull(!full)}>{full ? '收起' : '展开'}</button>}
  </div>;
}
