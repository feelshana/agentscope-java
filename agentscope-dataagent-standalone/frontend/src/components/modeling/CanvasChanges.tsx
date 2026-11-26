import { useEffect, useRef, useState } from 'react';
import type { DraftItem, DraftReview } from '../../api/draftReview';
import Icon from '../Icon';
import type { ChangeMark } from './SemanticWorkbench';

export default function CanvasChanges({ items, review, changes, canEdit, isSeen, onChanges, onUndo, modelLabel, canLocate, onLocate }: {
  items: DraftItem[];
  review: DraftReview | null;
  changes: ChangeMark | null | undefined;
  canEdit: boolean;
  isSeen?: (item: DraftItem) => boolean;
  onChanges: (changes: ChangeMark | null) => void;
  onUndo?: (path: string | null) => boolean;
  modelLabel: (name: string) => string;
  canLocate: (item: DraftItem) => boolean;
  onLocate: (item: DraftItem) => boolean;
}) {
  const [open, setOpen] = useState(false);
  const root = useRef<HTMLDivElement>(null);
  const trigger = useRef<HTMLButtonElement>(null);
  useEffect(() => {
    if (!open) return;
    const dismiss = (event: PointerEvent) => {
      if (!root.current?.contains(event.target as Node)) setOpen(false);
    };
    document.addEventListener('pointerdown', dismiss);
    return () => document.removeEventListener('pointerdown', dismiss);
  }, [open]);

  const revertible = new Set(review?.changes.filter(file => file.revertible).map(file => file.path));
  const canUndo = canEdit && !!review && !review.assistantRunning;
  const title = changes?.since ? '本轮改动' : '草稿改动';
  const undo = (path: string | null) => {
    if (onUndo?.(path)) {
      setOpen(false);
      trigger.current?.focus();
    }
  };
  return <div className="sw-canvas-changes" ref={root} onKeyDown={event => {
    if (event.key === 'Escape' && open) {
      event.stopPropagation();
      setOpen(false);
      trigger.current?.focus();
    }
  }}>
    <button type="button" ref={trigger} className="da-btn da-btn-sm sw-change-trigger" data-highlighted={!!changes || undefined}
      aria-expanded={open} aria-label={`${title} ${items.length} 项`} onClick={() => setOpen(value => !value)}>
      <Icon name="edit" size="sm" />{title}<b>{items.length}</b><Icon name="chevron" size="sm" />
    </button>
    {open && <section className="sw-change-popover" aria-label={title}>
      <header><strong>{title} <span>{items.length} 项</span></strong>
        <button type="button" className="sw-link" aria-pressed={!!changes} onClick={() => onChanges(changes ? null : { since: null })}>高亮改动</button>
      </header>
      {changes?.since && <button type="button" className="sw-link sw-change-scope" onClick={() => onChanges({ since: null })}>显示全部草稿改动</button>}
      {items.length ? <ul>{items.map(item => {
        const name = item.category === 'MODEL' ? modelLabel(item.title) : item.title;
        const detail = item.summary || item.text || (item.kind === 'ADDED' ? '新增' : item.kind === 'REMOVED' ? '已删除' : '已修改');
        const row = <><strong title={name}>{name}</strong><small title={detail}>{detail}</small></>;
        return <li key={item.id}>
          {canLocate(item) ? <button type="button" className="sw-change-item" onClick={() => {
            if (onLocate(item)) { setOpen(false); trigger.current?.focus(); }
          }}>{row}</button> : <div className="sw-change-item">{row}</div>}
          {onUndo && revertible.has(item.path) && <button type="button" className="sw-link sw-change-undo" disabled={!canUndo}
            aria-label={`撤销 ${name} 的文件改动`} onClick={() => undo(item.path)}>撤销文件…</button>}
          {changes && !isSeen?.(item) && <span className="sw-change-unseen" title="未看" aria-label="未看" />}
        </li>;
      })}</ul> : <p className="sw-hint">没有可定位的改动。</p>}
      <footer><span>发布后才生效</span>
        {onUndo && !!review?.changes.length && <button type="button" className="sw-link sw-change-undo" disabled={!canUndo}
          onClick={() => undo(null)}>放弃全部草稿…</button>}
      </footer>
    </section>}
  </div>;
}
