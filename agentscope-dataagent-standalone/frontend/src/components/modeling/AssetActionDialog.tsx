import { useId, useLayoutEffect, useRef } from 'react';
import type { ReactNode } from 'react';
import Icon from '../Icon';

export default function AssetActionDialog({ title, busy = false, onClose, actions, children }: {
  title: string;
  busy?: boolean;
  onClose: () => void;
  actions?: ReactNode;
  children: ReactNode;
}) {
  const id = useId();
  const ref = useRef<HTMLDialogElement>(null);
  useLayoutEffect(() => {
    const dialog = ref.current;
    dialog?.showModal();
    return () => dialog?.close();
  }, []);
  return <dialog ref={ref} className="mo-form-dialog va-dialog" aria-labelledby={id}
    onCancel={event => { event.preventDefault(); if (!busy) onClose(); }}>
    <header className="mo-dialog-head"><h2 id={id}>{title}</h2>
      <button type="button" className="da-btn da-btn-sm" aria-label="关闭" onClick={onClose} disabled={busy}><Icon name="close" size="sm" /></button>
    </header>
    <div className="mo-dialog-body">{children}</div>
    {actions && <footer className="va-dialog-actions">{actions}</footer>}
  </dialog>;
}
