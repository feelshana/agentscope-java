import { useId, useLayoutEffect, useRef, useState } from 'react';
import type { ReactNode } from 'react';
import Icon from '../Icon';
import './EngineeringDialog.css';

export default function EngineeringDialog({ title, context, toolbar, footer, onClose, canClose, children }: {
  title: string;
  context?: string;
  toolbar?: ReactNode;
  footer?: ReactNode;
  onClose: () => void;
  canClose?: () => boolean;
  children: ReactNode;
}) {
  const id = useId();
  const dialog = useRef<HTMLDialogElement>(null);
  const [expanded, setExpanded] = useState(false);
  useLayoutEffect(() => {
    const element = dialog.current;
    const trigger = document.activeElement;
    element?.showModal();
    return () => {
      element?.close();
      if (trigger instanceof HTMLElement && trigger.isConnected) trigger.focus({ preventScroll: true });
    };
  }, []);

  const close = () => { if (!canClose || canClose()) onClose(); };
  return <dialog ref={dialog} className={`eng-dialog${expanded ? ' eng-full' : ''}`} aria-labelledby={id}
    onCancel={event => { event.preventDefault(); close(); }}>
    <header className="eng-head">
      <Icon name="code" size="sm" />
      <h2 id={id}>{title}</h2>
      {context && <span className="eng-context" title={context}>{context}</span>}
      <div className="eng-window-actions">
        <button type="button" className="da-btn da-btn-sm" aria-pressed={expanded} title={expanded ? '还原弹窗' : '全屏显示'} aria-label={expanded ? '还原弹窗' : '全屏显示'} onClick={() => setExpanded(value => !value)}>
          <Icon name={expanded ? 'minimize' : 'fit'} size="sm" />
        </button>
        <button type="button" className="da-btn da-btn-sm" aria-label="关闭" title="关闭（Esc）" onClick={close}><Icon name="close" size="sm" /></button>
      </div>
    </header>
    {toolbar && <div className="eng-toolbar">{toolbar}</div>}
    <div className="eng-body">{children}</div>
    {footer && <footer className="eng-footer">{footer}</footer>}
  </dialog>;
}

export type EngineeringVersion = 'draft' | 'published';

export function EngineeringVersions({ version, publishedVersion, onChange }: {
  version: EngineeringVersion; publishedVersion?: number | null; onChange: (version: EngineeringVersion) => void;
}) {
  return <div className="sm-switch eng-versions" role="group" aria-label="查看版本">
    <button type="button" aria-pressed={version === 'draft'} className={version === 'draft' ? 'sm-switch-on' : ''} onClick={() => onChange('draft')}>当前草稿</button>
    <button type="button" aria-pressed={version === 'published'} className={version === 'published' ? 'sm-switch-on' : ''} disabled={!publishedVersion} onClick={() => onChange('published')}>{publishedVersion ? `线上 v${publishedVersion}` : '尚未发布'}</button>
  </div>;
}
