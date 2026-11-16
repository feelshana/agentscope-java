import { useCallback, useEffect, useRef, useState } from 'react';
import { getDraftItems } from '../../api/draftReview';
import type { DraftItem } from '../../api/draftReview';
import DraftItemsList from './DraftItemsList';

/** spec092 §7: "AI 建模结果" under one reply — what that turn wrote and is still unpublished. */
export default function TurnResultCard({ groupId, since, disabled, onView }: {
  groupId: string; since: string; disabled?: boolean;
  /** spec117: opens 语义模型 with this turn's changes marked. */
  onView?: (since: string) => void;
}) {
  const [items, setItems] = useState<DraftItem[] | null>(null);
  const [expanded, setExpanded] = useState(true);
  const generation = useRef(0);
  const load = useCallback(async () => {
    const current = ++generation.current;
    try {
      const value = await getDraftItems(groupId, since);
      if (current === generation.current) setItems(value);
    } catch { if (current === generation.current) setItems([]); }
  }, [groupId, since]);
  useEffect(() => {
    void load();
    const update = (event: Event) => {
      if ((event as CustomEvent<{ groupId: string }>).detail?.groupId === groupId) void load();
    };
    window.addEventListener('modeling:updated', update);
    return () => { generation.current++; window.removeEventListener('modeling:updated', update); };
  }, [groupId, load]);

  if (!items?.length) return null;
  const count = (c: DraftItem['category']) => items.filter(i => i.category === c).length;
  const parts = [['模型', count('MODEL')], ['关系', count('RELATIONSHIP')], ['业务语义', count('SEMANTIC')], ['注意事项', count('NOTE')]]
    .filter(([, n]) => n).map(([label, n]) => `${label} ${n}`);
  return <section aria-label="AI建模结果" data-tour="turn-result" style={{ marginTop: 12, border: '1px solid color-mix(in srgb, var(--da-primary) 8%, var(--da-border))', borderRadius: 12, overflow: 'hidden', background: 'var(--da-surface)' }}>
    <div style={{ display: 'flex', alignItems: 'center', gap: 10, padding: '7px 10px 7px 12px', borderBottom: expanded ? '1px solid var(--da-border)' : 'none' }}>
      <span aria-hidden="true" style={{ width: 20, height: 20, borderRadius: 6, background: 'var(--da-primary-subtle)', color: 'var(--da-primary)', fontSize: 11, fontWeight: 700, display: 'inline-flex', alignItems: 'center', justifyContent: 'center', flex: 'none' }}>写</span>
      <button type="button" onClick={() => setExpanded(!expanded)}
        style={{ flex: 1, minWidth: 0, background: 'none', border: 'none', padding: 0, cursor: 'pointer', color: 'var(--da-text)', display: 'flex', gap: 7, alignItems: 'center', fontSize: 13, textAlign: 'left' }}>
        <strong style={{ fontWeight: 650, whiteSpace: 'nowrap' }}>AI建模结果</strong>
        <span style={{ display: 'inline-flex', gap: 5, minWidth: 0, overflow: 'hidden' }}>
          {parts.map(part => <span key={part} style={{ padding: '1px 7px', borderRadius: 999, background: 'var(--da-surface-sunken)', fontSize: 11.5, lineHeight: 1.6, color: 'var(--da-text-2)', whiteSpace: 'nowrap' }}>{part}</span>)}
        </span>
        <span style={{ flex: 1 }} />
        <span style={{ fontSize: 11.5, color: 'var(--da-text-muted)', whiteSpace: 'nowrap' }}>草稿 · 发布后生效</span>
        <span aria-hidden="true" style={{ fontSize: 9, color: 'var(--da-text-muted)', transform: expanded ? 'rotate(90deg)' : 'none', transition: 'transform .16s ease' }}>▶</span>
      </button>
      {onView && <button type="button" onClick={() => onView(since)} title="在关系图和业务规则中标出这一轮的改动" className="mc-link"
        style={{ border: 'none', borderRadius: 7, background: 'none', color: 'var(--da-primary)', padding: '5px 8px', cursor: 'pointer', fontSize: 12, fontWeight: 600, whiteSpace: 'nowrap' }}>在语义模型中查看 →</button>}
    </div>
    {expanded && <DraftItemsList groupId={groupId} items={items} disabled={disabled} limit={5} onChanged={() => void load()} />}
  </section>;
}
