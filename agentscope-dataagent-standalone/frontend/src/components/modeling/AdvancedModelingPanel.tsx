import { useEffect, useId, useState } from 'react';
import type { MdlPreview } from '../../api/semanticModeling';
import Icon from '../Icon';
import AssetYamlBrowser from './AssetYamlBrowser';
import type { AssetEntry } from './AssetYamlBrowser';
import CompiledMdlPanel from './CompiledMdlPanel';
import ModelSnapshotPanel from './ModelSnapshotPanel';
import './AdvancedModelingPanel.css';

type Tab = 'files' | 'compiled' | 'views' | 'cubes' | 'knowledge';

/** A read-only entry into the same engineering data, mounted only when requested. */
export default function AdvancedModelingPanel({ groupId, open, onOpenChange, version, preview, views, cubes, knowledge, selected, onSelect, onSelectKnowledge, initialTab = 'files' }: {
  groupId: string; open: boolean; onOpenChange: (open: boolean) => void; version: 'draft' | 'published'; preview: MdlPreview | null;
  views: AssetEntry[]; cubes: AssetEntry[]; knowledge: AssetEntry[]; selected: string | null;
  onSelect: (key: string) => void; onSelectKnowledge: (key: string) => void;
  initialTab?: Tab;
}) {
  const id = useId();
  const target = selected && views.some(e => e.key === selected) ? 'views' : selected && cubes.some(e => e.key === selected) ? 'cubes' : selected && knowledge.some(e => e.key === selected) ? 'knowledge' : null;
  const [tab, setTab] = useState<Tab>(target ?? initialTab);
  const [visited, setVisited] = useState(() => new Set<Tab>([target ?? initialTab]));
  const choose = (next: Tab) => { setTab(next); setVisited(all => new Set([...all, next])); };
  useEffect(() => { const next = target ?? initialTab; setTab(next); setVisited(all => new Set([...all, next])); }, [selected, target, initialTab]);
  const tabs: { key: Tab; label: string; count?: number }[] = [
    { key: 'files', label: '工程快照' }, { key: 'compiled', label: '编译后的 MDL' },
    ...(views.length ? [{ key: 'views' as const, label: '视图', count: views.length }] : []),
    ...(cubes.length ? [{ key: 'cubes' as const, label: 'Cube', count: cubes.length }] : []),
    ...(knowledge.length ? [{ key: 'knowledge' as const, label: '其他知识文件', count: knowledge.length }] : []),
  ];
  const current = tabs.some(t => t.key === tab) ? tab : 'files';
  return <details className="am" open={open} onToggle={e => onOpenChange(e.currentTarget.open)}>
    <summary className="am-summary"><span className="am-summary-icon" aria-hidden="true"><Icon name="code" /></span><span className="am-summary-copy"><strong>高级 · 工程与快照</strong><span>编译后的 MDL、视图、Cube 与其他业务知识文件</span></span><span className="am-readonly">只读</span><span className="am-summary-action" aria-hidden="true">{open ? '收起' : '展开'}<Icon name="chevron" size="sm" /></span></summary>
    {open && <div className="am-body"><div className="am-tabs" role="tablist" aria-label="高级内容" onKeyDown={event => {
      const buttons = Array.from(event.currentTarget.querySelectorAll<HTMLButtonElement>('[role="tab"]'));
      const index = buttons.indexOf(document.activeElement as HTMLButtonElement);
      const next = event.key === 'Home' ? 0 : event.key === 'End' ? buttons.length - 1 : event.key === 'ArrowRight' ? (index + 1) % buttons.length : event.key === 'ArrowLeft' ? (index - 1 + buttons.length) % buttons.length : null;
      if (next === null) return;
      event.preventDefault(); buttons[next].focus(); choose(tabs[next].key);
    }}>{tabs.map(item => <button type="button" role="tab" key={item.key} id={`${id}-${item.key}`} aria-controls={`${id}-${item.key}-panel`} aria-selected={current === item.key} tabIndex={current === item.key ? 0 : -1} onClick={() => choose(item.key)}>{item.label}{item.count !== undefined && <span>{item.count}</span>}</button>)}</div>
      {tabs.filter(item => visited.has(item.key)).map(item => {
        const entries = item.key === 'views' ? views : item.key === 'cubes' ? cubes : knowledge;
        return <section key={item.key} className="am-tabpanel" role="tabpanel" hidden={current !== item.key} id={`${id}-${item.key}-panel`} aria-labelledby={`${id}-${item.key}`}>
          {item.key === 'files' ? <ModelSnapshotPanel preview={preview} /> : item.key === 'compiled' ? <CompiledMdlPanel groupId={groupId} version={version} /> : <AssetYamlBrowser groupId={groupId} entries={entries} selectedKey={entries.some(e => e.key === selected) ? selected : entries[0]?.key ?? null} onSelect={item.key === 'knowledge' ? onSelectKnowledge : onSelect} emptyText="暂无工程文件" />}
        </section>;
      })}</div>}
  </details>;
}
