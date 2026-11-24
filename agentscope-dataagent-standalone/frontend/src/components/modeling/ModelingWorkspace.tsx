import { useId, type ReactNode, type Ref } from 'react';
import Icon, { type IconName } from '../Icon';

export function WorkspaceToolbar<T extends string>({ label, tabs, active, onSelect, objectActions, context, children }: {
  label: string;
  tabs: readonly { id: T; label: string; icon: IconName; count?: number }[];
  active: T;
  onSelect: (id: T) => void;
  objectActions?: ReactNode;
  context?: string;
  children?: ReactNode;
}) {
  return <header className="mo-workbar">
    <div className="mo-worktabs" role="group" aria-label={label}>
      {tabs.map(tab => <button type="button" key={tab.id} aria-pressed={active === tab.id} onClick={() => onSelect(tab.id)}>
        <Icon name={tab.icon} size="sm" />{tab.label}{tab.count !== undefined && <span>{tab.count}</span>}
      </button>)}
    </div>
    <WorkspaceObjectTools context={context}>{objectActions}</WorkspaceObjectTools>
    <div className="mo-work-actions mo-work-module-tools" role="group" aria-label="模块操作">{children}</div>
  </header>;
}

export function WorkspaceObjectTools({ context, children, compact = false }: {
  context?: string;
  children?: ReactNode;
  compact?: boolean;
}) {
  return <div className={'mo-work-actions mo-work-object-tools' + (compact ? ' mo-work-object-compact' : '')}
    role="group" aria-label="当前对象操作">
    {context && <span className="mo-work-context" title={context}>{context}</span>}
    {children}
  </div>;
}

export function WorkspaceEmpty({ icon, title, description, children }: {
  icon: IconName; title: string; description?: string; children?: ReactNode;
}) {
  return <div className="mo-work-empty">
    <span className="qc-empty-icon"><Icon name={icon} /></span>
    <div><h3>{title}</h3>{description && <p>{description}</p>}{children && <div className="mo-work-empty-actions">{children}</div>}</div>
  </div>;
}

export function WorkspaceBatchInput({ label, checked, disabled, onChange }: {
  label: string; checked: boolean; disabled: boolean; onChange: (checked: boolean) => void;
}) {
  return <label className="mo-batch-checkbox">
    <input type="checkbox" aria-label={`勾选 ${label}`} checked={checked} disabled={disabled}
      onChange={event => onChange(event.target.checked)} />
  </label>;
}

export function WorkspaceBatchToggle({ active, onClick }: { active: boolean; onClick: () => void }) {
  return <button type="button" className="da-btn da-btn-sm mo-batch-toggle" aria-pressed={active}
    onClick={onClick}>{active ? '退出批量处理' : '批量处理'}</button>;
}

export function WorkspaceBatchTools({ count, label, disabled, onRemove, onClear, children }: {
  count: number; label: string; disabled: boolean; onRemove: () => void; onClear: () => void; children?: ReactNode;
}) {
  return <>
    <span className="da-small" role="status">已选 {count} 项</span>
    {children}
    {count > 0 && <>
      <button type="button" className="da-btn" onClick={onClear}>清空选择</button>
      <span className="mo-batch-delete">
        <button type="button" className="da-btn mo-batch-trash" aria-label={label} aria-haspopup="dialog" disabled={disabled}
          onClick={onRemove}><Icon name="trash" size="sm" /></button>
        <span className="mo-batch-tooltip" aria-hidden="true">{label}</span>
      </span>
    </>}
  </>;
}

export function MaterialsCatalog({ title, label, count, open, onToggle, filters, children, listRef, actions }: {
  title: string; count: string; open: boolean; onToggle: () => void; filters: ReactNode; children: ReactNode;
  label?: string; listRef?: Ref<HTMLUListElement>; actions?: ReactNode;
}) {
  const id = useId();
  const toggleLabel = (open ? '收起' : '展开') + title;
  return <aside className="mo-work-catalog mo-material-catalog" aria-label={label ?? title}>
    <div className="qc-directory-heading">
      {open && <strong>{title} <small>{count}</small></strong>}
      <button type="button" className="da-btn qc-directory-toggle" aria-expanded={open} aria-controls={id}
        aria-label={toggleLabel} title={toggleLabel} onClick={onToggle}><Icon name="chevron" size="sm" /></button>
    </div>
    <div id={id} className="mo-material-catalog-content" hidden={!open}>
      <div className="mo-material-catalog-filters">{filters}{actions}</div>
      <ul className="qc-queue-list" ref={listRef}>{children}</ul>
    </div>
  </aside>;
}
