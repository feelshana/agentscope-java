import { useEffect, useLayoutEffect, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import { DatasetColumn } from '../../api/datasets';
import Icon from '../../components/Icon';

/** Non-modal field help stays next to its trigger, including inside the portal iframe. */
export default function DatasetFieldInfo({ column, anchor, close }: {
  column: DatasetColumn; anchor: HTMLButtonElement; close: () => void;
}) {
  const panel = useRef<HTMLDivElement>(null);
  const [position, setPosition] = useState<{ top: number; left: number } | null>(null);
  useLayoutEffect(() => {
    const popup = panel.current;
    if (!popup) return;
    const r = anchor.getBoundingClientRect();
    const height = popup.getBoundingClientRect().height;
    const width = popup.getBoundingClientRect().width;
    const below = window.innerHeight - r.bottom - 12;
    const top = below >= height ? r.bottom + 8 : r.top - height - 8;
    const canvasLeft = anchor.closest('.kd-page')?.getBoundingClientRect().left ?? 0;
    const minLeft = Math.min(window.innerWidth - width - 12, Math.max(12, canvasLeft + 12));
    setPosition({ top: Math.max(12, Math.min(top, window.innerHeight - height - 12)),
      left: Math.max(minLeft, Math.min(r.right - width, window.innerWidth - width - 12)) });
  }, [anchor, column]);
  useEffect(() => {
    const popup = panel.current;
    popup?.focus({ preventScroll: true });
    const outside = (event: PointerEvent) => {
      if (!popup?.contains(event.target as Node) && !anchor.contains(event.target as Node)) close();
    };
    const escape = (event: KeyboardEvent) => {
      if (event.key === 'Escape') { event.preventDefault(); close(); }
    };
    const scroll = (event: Event) => {
      if (!(event.target instanceof Node) || !popup?.contains(event.target)) close();
    };
    document.addEventListener('pointerdown', outside);
    document.addEventListener('keydown', escape);
    window.addEventListener('scroll', scroll, true);
    window.addEventListener('resize', close);
    return () => {
      document.removeEventListener('pointerdown', outside);
      document.removeEventListener('keydown', escape);
      window.removeEventListener('scroll', scroll, true);
      window.removeEventListener('resize', close);
      if (popup?.contains(document.activeElement) && anchor.isConnected) anchor.focus({ preventScroll: true });
    };
  }, [anchor, close]);
  return createPortal(<div ref={panel} id="kd-field-help" className="kd-field-popover" role="dialog"
    aria-labelledby="kd-field-help-title" tabIndex={-1}
    style={position ? { ...position, visibility: 'visible' } : { top: 0, left: 0, visibility: 'hidden' }}>
    <header><strong id="kd-field-help-title">{column.displayName?.trim() || column.originalName || column.name}</strong>
      <button className="kd-info" aria-label="关闭字段说明" onClick={close}><Icon name="close" size="sm" /></button></header>
    <code>{column.name} · {column.sqlType}</code><p>{column.description || '暂无字段说明'}</p>
  </div>, document.body);
}
