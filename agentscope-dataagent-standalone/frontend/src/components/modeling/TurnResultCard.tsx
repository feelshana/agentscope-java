import { useCallback, useEffect, useRef, useState } from 'react';
import { getDraftItems } from '../../api/draftReview';
import type { DraftItem } from '../../api/draftReview';
import DraftItemsList from './DraftItemsList';

/** spec092 §7: "AI 建模结果" under one reply — what that turn wrote and is still unpublished. */
export default function TurnResultCard({ groupId, since, disabled }: { groupId: string; since: string; disabled?: boolean }) {
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
  return <section aria-label="AI建模结果" style={{ marginTop: 10, border: '1px solid var(--da-border)', borderRadius: 8, overflow: 'hidden' }}>
    <button type="button" onClick={() => setExpanded(!expanded)}
      style={{ width: '100%', background: 'var(--da-surface-sunken, #f8fafc)', border: 'none', padding: '6px 10px', cursor: 'pointer', color: 'var(--da-text)', display: 'flex', gap: 8, alignItems: 'center', fontSize: 13 }}>
      <strong>AI建模结果</strong><span style={{ fontSize: 12, color: 'var(--da-text-muted)' }}>{parts.join(' · ')}</span>
      <span style={{ flex: 1 }} /><span style={{ fontSize: 11, color: 'var(--da-text-muted)' }}>草稿 · 发布后生效 {expanded ? '▾' : '▸'}</span>
    </button>
    {expanded && <DraftItemsList groupId={groupId} items={items} disabled={disabled} limit={5} onChanged={() => void load()} />}
  </section>;
}
