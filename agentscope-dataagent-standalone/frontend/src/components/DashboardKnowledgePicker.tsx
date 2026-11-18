import { useEffect, useId, useRef, useState } from 'react';
import type { DatasetGroup } from '../api/datasets';
import Icon from './Icon';

export default function DashboardKnowledgePicker({ id, sources, value, onChange, disabled }: {
  id: string; sources: DatasetGroup[]; value: string; onChange: (id: string) => void; disabled: boolean;
}) {
  const listId = useId();
  const root = useRef<HTMLDivElement>(null);
  const trigger = useRef<HTMLButtonElement>(null);
  const search = useRef<HTMLInputElement>(null);
  const [open, setOpen] = useState(false);
  const [term, setTerm] = useState('');
  const [active, setActive] = useState(0);
  const chosen = sources.find(item => item.id === value);
  const text = term.trim().toLocaleLowerCase();
  const options = sources.filter(item => `${item.name} ${item.description || ''}`.toLocaleLowerCase().includes(text));
  useEffect(() => {
    if (!open) return;
    search.current?.focus();
    const close = (event: PointerEvent) => { if (!root.current?.contains(event.target as Node)) setOpen(false); };
    document.addEventListener('pointerdown', close);
    return () => document.removeEventListener('pointerdown', close);
  }, [open]);
  useEffect(() => { if (open) document.getElementById(`${listId}-${active}`)?.scrollIntoView({ block: 'nearest' }); }, [open, active, listId]);
  const pick = (group: DatasetGroup) => { onChange(group.id); setOpen(false); trigger.current?.focus(); };
  const show = () => { setTerm(''); setActive(0); setOpen(true); };
  return <div className="dashboard-knowledge-picker" ref={root} onBlur={event => { if (!event.currentTarget.contains(event.relatedTarget as Node | null)) setOpen(false); }}>
    <button ref={trigger} id={id} type="button" className="dashboard-knowledge-trigger" role="combobox" aria-labelledby={`${id}-label`} aria-required="true" aria-expanded={open} aria-haspopup="listbox" aria-controls={open ? listId : undefined} disabled={disabled || !sources.length}
      onClick={() => open ? setOpen(false) : show()} onKeyDown={event => { if (event.key === 'ArrowDown' || event.key === 'ArrowUp') { event.preventDefault(); show(); } }}>
      <span className="dashboard-knowledge-symbol"><Icon name="book" size="sm" /></span><span className="dashboard-knowledge-chosen"><strong>{chosen?.name || '请选择已发布知识库'}</strong><small>{chosen ? `${chosen.datasetCount ?? 0} 个数据集 · 已发布` : '仅显示你有查询权限的知识库'}</small></span><Icon name="chevron" size="sm" />
    </button>
    {open && <div className="dashboard-knowledge-popover" onKeyDown={event => {
      if (event.nativeEvent.isComposing) return;
      if (event.key === 'Escape') { event.preventDefault(); event.stopPropagation(); setOpen(false); trigger.current?.focus(); }
      else if (event.key === 'ArrowDown' || event.key === 'ArrowUp') { event.preventDefault(); setActive(index => Math.max(0, Math.min(options.length - 1, index + (event.key === 'ArrowDown' ? 1 : -1)))); }
      else if (event.key === 'Enter') { event.preventDefault(); if (options[active]) pick(options[active]); }
    }}>
      <div className="dashboard-knowledge-search"><Icon name="search" size="sm" /><input ref={search} type="search" role="combobox" aria-label="搜索知识库" aria-expanded="true" aria-controls={listId} aria-autocomplete="list" aria-activedescendant={options[active] ? `${listId}-${active}` : undefined} placeholder="搜索知识库名称或说明…" maxLength={160} value={term} onChange={event => { setTerm(event.target.value); setActive(0); }} /></div>
      <div className="dashboard-knowledge-options" id={listId} role="listbox" tabIndex={-1} aria-label="可用知识库">{options.length ? options.map((item, index) => <button id={`${listId}-${index}`} key={item.id} type="button" role="option" aria-selected={item.id === value} tabIndex={-1} className={index === active ? 'is-active' : ''} onMouseEnter={() => setActive(index)} onMouseDown={event => event.preventDefault()} onClick={() => pick(item)}>
        <span className="dashboard-knowledge-symbol"><Icon name="book" size="sm" /></span><span className="dashboard-knowledge-option-text"><strong>{item.name}</strong><small>{item.description?.trim() || `${item.datasetCount ?? 0} 个数据集 · 已发布`}</small></span>{item.id === value && <Icon name="check" size="sm" />}
      </button>) : <p className="dashboard-knowledge-empty" role="status">没有匹配的知识库，试试其他关键词。</p>}</div>
      <p className="dashboard-knowledge-count">{options.length} 个可用知识库 · ↑↓ 选择，Enter 确认</p>
    </div>}
  </div>;
}
