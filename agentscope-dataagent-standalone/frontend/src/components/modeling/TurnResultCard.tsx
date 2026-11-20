import { useCallback, useEffect, useId, useRef, useState } from 'react';
import { getDraftItems } from '../../api/draftReview';
import type { DraftItem } from '../../api/draftReview';
import DraftItemsList from './DraftItemsList';
import Icon from '../Icon';

/** spec092 §7: "AI 建模结果" under one reply — what that turn wrote and is still unpublished. */
export default function TurnResultCard({ groupId, since, disabled, onView, onCompare }: {
  groupId: string; since: string; disabled?: boolean;
  /** spec117: opens 语义模型 with this turn's changes marked. */
  onView?: (since: string) => void;
  onCompare?: (since: string) => void;
}) {
  const [items, setItems] = useState<DraftItem[] | null>(null);
  const [expanded, setExpanded] = useState(false);
  const detailsId = useId();
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
  return <section className="mc-result" aria-label="AI建模结果" data-tour="turn-result">
    <div className="mc-result-head">
      <span className="mc-result-icon"><Icon name="file" size="sm" /></span>
      <div className="mc-result-summary">
        <strong>AI 建模结果</strong><span className="mc-result-state">草稿 · 发布后生效</span>
        {parts.length > 0 && <p>{parts.join(' · ')}</p>}
      </div>
      <button type="button" className="da-btn da-btn-sm mc-result-toggle" aria-expanded={expanded} aria-controls={detailsId}
        onClick={() => setExpanded(!expanded)}>
        {expanded ? '收起改动' : '查看改动'}<Icon name="chevron" size="sm" />
      </button>
    </div>
    <div id={detailsId} className="mc-result-details" hidden={!expanded}>
      {expanded && <>
        <DraftItemsList groupId={groupId} items={items} disabled={disabled} limit={5} onChanged={() => void load()} />
        {(onView || onCompare) && <div className="mc-result-actions">
          {onView && <button type="button" onClick={() => onView(since)} title="在关系图和业务规则中标出这一轮的改动" className="da-btn da-btn-sm">查看模型</button>}
          {onCompare && <button type="button" className="da-btn da-btn-sm mw-compare-action" onClick={() => onCompare(since)}>对照查看</button>}
        </div>}
      </>}
    </div>
  </section>;
}
