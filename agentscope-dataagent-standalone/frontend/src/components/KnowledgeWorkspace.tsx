import React, { useEffect, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import { useNavigate } from 'react-router-dom';
import Icon, { IconName } from './Icon';
import '../knowledge-workspace.css';

export function KnowledgeBreadcrumb({ items }: { items: { label: string; to?: string }[] }) {
  const navigate = useNavigate();
  return <header className="kw-breadcrumb" aria-label="当前位置">
    {items.map((item, index) => <React.Fragment key={`${index}-${item.label}`}>
      {index > 0 && <Icon name="chevron" size="sm" />}
      {item.to ? <button onClick={() => { if (item.to) navigate(item.to); }}>{item.label}</button> : <span aria-current="page">{item.label}</span>}
    </React.Fragment>)}
  </header>;
}

export function WorkspaceMenu({ label, items }: {
  label: string;
  items: { label: string; icon: IconName; danger?: boolean; disabled?: boolean; onClick: () => void }[];
}) {
  const [position, setPosition] = useState<{ top: number; left: number } | null>(null);
  const trigger = useRef<HTMLButtonElement>(null);
  const panel = useRef<HTMLDivElement>(null);
  useEffect(() => {
    if (!position) return;
    const node = panel.current;
    if (node) {
      const rect = node.getBoundingClientRect();
      if (rect.bottom > window.innerHeight - 8) node.style.top = `${Math.max(8, (trigger.current?.getBoundingClientRect().top ?? 8) - rect.height - 6)}px`;
      node.querySelector<HTMLButtonElement>('button:not(:disabled)')?.focus();
    }
    const outside = (e: PointerEvent) => {
      if (!panel.current?.contains(e.target as Node) && !trigger.current?.contains(e.target as Node)) setPosition(null);
    };
    const close = () => setPosition(null);
    const keys = (e: KeyboardEvent) => {
      if (e.key === 'Escape') { setPosition(null); trigger.current?.focus(); }
      if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
        e.preventDefault();
        const buttons = Array.from(panel.current?.querySelectorAll<HTMLButtonElement>('button:not(:disabled)') ?? []);
        if (!buttons.length) return;
        const i = buttons.indexOf(document.activeElement as HTMLButtonElement);
        buttons[(i + (e.key === 'ArrowDown' ? 1 : buttons.length - 1)) % buttons.length]?.focus();
      }
    };
    document.addEventListener('pointerdown', outside);
    document.addEventListener('keydown', keys);
    window.addEventListener('resize', close);
    window.addEventListener('scroll', close, true);
    return () => {
      document.removeEventListener('pointerdown', outside);
      document.removeEventListener('keydown', keys);
      window.removeEventListener('resize', close);
      window.removeEventListener('scroll', close, true);
    };
  }, [position]);
  return <>
    <button ref={trigger} className="kw-more" type="button" aria-label={label} aria-haspopup="menu" aria-expanded={!!position}
      onClick={e => { e.stopPropagation(); const r = e.currentTarget.getBoundingClientRect(); setPosition(position ? null : { top: r.bottom + 6, left: Math.max(8, Math.min(window.innerWidth - 188, r.right - 180)) }); }}>
      <Icon name="moreHorizontal" size="sm" />
    </button>
    {position && createPortal(<div ref={panel} className="kw-menu" role="menu" aria-label={label} style={position} onClick={e => e.stopPropagation()}>
      {items.map(item => <button role="menuitem" key={item.label} disabled={item.disabled} className={item.danger ? 'danger' : ''}
        onClick={() => { setPosition(null); trigger.current?.focus(); item.onClick(); }}><Icon name={item.icon} size="sm" />{item.label}</button>)}
    </div>, document.body)}
  </>;
}
