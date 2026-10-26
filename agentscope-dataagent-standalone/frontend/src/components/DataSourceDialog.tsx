import React, { useId, useLayoutEffect, useRef } from 'react';
import Icon from './Icon';
import './DataSources.css';

/** Native modal behavior provides focus containment, Escape handling and focus restoration. */
export default function DataSourceDialog({ title, description, onClose, busy = false, wide = false, children }: {
  title: string;
  description?: string;
  onClose: () => void;
  busy?: boolean;
  wide?: boolean;
  children: React.ReactNode;
}) {
  const dialog = useRef<HTMLDialogElement>(null);
  const returnFocus = useRef(document.activeElement instanceof HTMLElement ? document.activeElement : null);
  const id = useId();
  useLayoutEffect(() => {
    const node = dialog.current;
    node?.showModal();
    node?.querySelector<HTMLInputElement>('input')?.focus();
    return () => {
      node?.close();
      if (returnFocus.current?.isConnected) returnFocus.current.focus();
    };
  }, []);
  return <dialog ref={dialog} className={`ds-dialog${wide ? ' ds-dialog-wide' : ''}`} aria-labelledby={`${id}-title`}
    aria-describedby={description ? `${id}-description` : undefined}
    onCancel={event => { event.preventDefault(); if (!busy) onClose(); }}
    onClick={event => { if (event.target === event.currentTarget && !busy) onClose(); }}>
    <div className="ds-dialog-shell">
      <header className="ds-dialog-head">
        <div><h2 id={`${id}-title`}>{title}</h2>{description && <p id={`${id}-description`}>{description}</p>}</div>
        <button type="button" className="ds-close" onClick={onClose} disabled={busy} aria-label="关闭弹窗"><Icon name="close" /></button>
      </header>
      {children}
    </div>
  </dialog>;
}
