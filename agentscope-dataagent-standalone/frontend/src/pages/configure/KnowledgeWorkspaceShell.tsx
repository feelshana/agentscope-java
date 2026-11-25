import { useEffect, useRef, useState, type ReactNode } from 'react';
import type { GroupDetail } from '../../api/datasets';
import Icon from '../../components/Icon';
import type { KnowledgeSection } from './knowledgeWorkspaceRoutes';
import '../../components/modeling/ModelingWorkbenchTheme.css';
import './KnowledgeWorkspaceShell.css';

const directoryIcons = {
  overview: <><rect x="4.1" y="4.1" width="3.6" height="3.6" rx="1.2" /><rect x="12.3" y="4.1" width="3.6" height="3.6" rx="1.2" /><rect x="4.1" y="12.3" width="3.6" height="3.6" rx="1.2" /><rect x="12.3" y="12.3" width="3.6" height="3.6" rx="1.2" /></>,
  datasets: <><rect x="3.2" y="4.2" width="13.6" height="11.6" rx="2" /><path d="M3.2 8.2h13.6M8.4 8.2v7.6" /></>,
  graph: <><circle cx="6" cy="6.2" r="2.1" /><circle cx="14" cy="7.2" r="2.1" /><circle cx="9.4" cy="14.8" r="2.1" /><path d="m7.8 7.3 4.4-.5M6.9 8.1l1.8 4.7m4.5-3.9-2.3 4" /></>,
  rules: <path d="m3.6 5.4 1.25 1.25 2.1-2.2m-3.35 5.6 1.25 1.25 2.1-2.2m-3.35 5.7 1.25 1.25 2.1-2.2M9.4 5.6h7m-7 4.7h7m-7 4.7h7" />,
  coach: <path d="M3.4 6.8A2.6 2.6 0 0 1 6 4.2h8a2.6 2.6 0 0 1 2.6 2.6v4.3a2.6 2.6 0 0 1-2.6 2.6H9.2l-3.6 3v-3.1a2.2 2.2 0 0 1-2.2-2.2Z" />,
  assets: <path d="m10 4.3 6.6 3.5-6.6 3.5-6.6-3.5L10 4.3Zm-6.6 8.1 6.6 3.5 6.6-3.5" />,
  materials: <path d="M3.4 6.2A1.2 1.2 0 0 1 4.6 5h3l1.6 1.9h5.2a1.2 1.2 0 0 1 1.2 1.2v6.7a1.2 1.2 0 0 1-1.2 1.2H4.6a1.2 1.2 0 0 1-1.2-1.2Z" />,
  engineering: <path d="m6.8 5.7-4 4.3 4 4.3m6.4-8.6 4 4.3-4 4.3m-2.3-9.8-1.8 11" />,
  members: <path d="m10 3.2 5.4 1.8v5c0 3.1-2.2 5.5-5.4 6.7-3.2-1.2-5.4-3.6-5.4-6.7V5L10 3.2Zm-2.2 6.7 1.6 1.6 3-3.1" />,
  settings: <><path d="M3.6 6.6h5m3.6 0h4.2m-12.8 6.8h3.6m3.6 0h5.6" /><circle cx="10.4" cy="6.6" r="1.8" /><circle cx="9" cy="13.4" r="1.8" /></>,
} satisfies Record<KnowledgeSection, ReactNode>;

// Optical sizing keeps the outline glyphs balanced inside the same 17px viewport.
const directoryIconScale: Partial<Record<KnowledgeSection, number>> = {
  overview: .978, datasets: .85, graph: .98, rules: 1.048, coach: 1.03,
  assets: 1.025, materials: 1.04, engineering: 1.08, settings: 1.08,
};

const modelingEntries: { id: KnowledgeSection; label: string; manager?: boolean }[] = [
  { id: 'graph', label: '关系图' },
  { id: 'rules', label: '业务规则' },
  { id: 'coach', label: '问数优化' },
  { id: 'assets', label: '公共视图', manager: true },
  { id: 'materials', label: '资料', manager: true },
  { id: 'engineering', label: '工程' },
];

export default function KnowledgeWorkspaceShell({ detail, version, draftCount, current, datasetId,
  statusAction, queryAction, onSelect, onDataset, onLibrary, versionRef, actionsRef, children }: {
  detail: GroupDetail | null;
  version: number;
  draftCount?: number;
  current: KnowledgeSection;
  datasetId?: string;
  statusAction?: ReactNode;
  queryAction?: ReactNode;
  onSelect: (section: KnowledgeSection) => void;
  onDataset: (id: string) => void;
  onLibrary: () => void;
  versionRef: (node: HTMLDivElement | null) => void;
  actionsRef: (node: HTMLDivElement | null) => void;
  children: ReactNode;
}) {
  const [directory, setDirectory] = useState({ datasetId, expanded: !!datasetId });
  const dataExpanded = directory.datasetId === datasetId ? directory.expanded : !!datasetId;
  if (directory.datasetId !== datasetId) setDirectory({ datasetId, expanded: dataExpanded });
  const [menu, setMenu] = useState(false);
  const trigger = useRef<HTMLButtonElement>(null);
  const drawer = useRef<HTMLElement>(null);
  const group = detail?.group;
  const tables = detail?.datasets ?? [];
  const canManage = group?.canManage === true && !group.deletionPending;
  const hasDraft = canManage && !!draftCount;
  const modeling = modelingEntries.filter(entry => !entry.manager || canManage);
  const select = (section: KnowledgeSection) => { setMenu(false); onSelect(section); };
  useEffect(() => {
    if (!menu) return;
    drawer.current?.querySelector<HTMLButtonElement>('.kbw-nav-item[aria-current], .kbw-nav-item')?.focus();
    const resize = () => { if (window.innerWidth > 720) setMenu(false); };
    window.addEventListener('resize', resize);
    return () => {
      window.removeEventListener('resize', resize);
      if (drawer.current?.contains(document.activeElement)) trigger.current?.focus();
    };
  }, [menu]);
  useEffect(() => {
    if (!group?.name) return;
    const previous = document.title;
    document.title = `${group.name} · 知识库`;
    return () => { document.title = previous; };
  }, [group?.name]);
  const row = (id: KnowledgeSection, label: string, dim = false) => (
    <button type="button" className="kbw-nav-item" aria-current={current === id && !datasetId ? 'page' : undefined}
      data-dim={dim || undefined} onClick={() => select(id)}>
      <svg viewBox="0 0 20 20" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
        <g transform={directoryIconScale[id] ? `translate(10 10) scale(${directoryIconScale[id]}) translate(-10 -10)` : undefined}>{directoryIcons[id]}</g>
      </svg><span>{label}</span>
    </button>
  );
  return <div className="kbw">
    <header className="kbw-header">
      <div className="kbw-identity">
        <button ref={trigger} type="button" className="kbw-mobile-trigger da-btn" aria-label="知识库目录" aria-expanded={menu} aria-controls="kbw-directory" onClick={() => setMenu(value => !value)}><Icon name="menu" size="sm" /></button>
        <nav className="kbw-crumb" aria-label="知识库位置">
          <button type="button" onClick={onLibrary}><Icon name="chevron" size="sm" />知识库</button><span aria-hidden="true">/</span>
          <strong title={group?.name}>{group?.name ?? '加载中…'}</strong>
        </nav>
        {group && <span className="kbw-version" data-published={version > 0 || undefined}>
          <Icon name={version ? 'check' : 'file'} size="sm" />{group.deletionPending ? '删除待完成' : version ? `已发布 v${version}` : tables.length ? '尚未发布' : '待添加数据'}
        </span>}
        <div className="kbw-version-slot" ref={versionRef} />
      </div>
      <div className="kbw-header-actions">
        <div className="kbw-model-actions mw-actions" ref={actionsRef} />
        {statusAction}
        {queryAction}
      </div>
    </header>
    {menu && <button type="button" tabIndex={-1} aria-label="关闭知识库目录" className="kbw-backdrop" onClick={() => setMenu(false)} />}
    <aside ref={drawer} className={`kbw-sidebar${menu ? ' is-open' : ''}`} id="kbw-directory" aria-label="知识库目录"
      onKeyDown={event => { if (menu && event.key === 'Escape') { event.stopPropagation(); setMenu(false); trigger.current?.focus(); } }}>
      <nav className="kbw-nav" aria-label="工作区功能">
        <div className="kbw-nav-group"><h2>工作区</h2>{row('overview', '概览')}</div>
        <div className="kbw-nav-group"><h2>数据</h2>
          <div className="kbw-data-entry">
            {row('datasets', '数据集')}
            {tables.length > 0 && <button type="button" className="kbw-data-toggle" aria-label={dataExpanded ? '收起数据集目录' : '展开数据集目录'}
              aria-controls="kbw-table-directory" aria-expanded={dataExpanded} onClick={() => setDirectory({ datasetId, expanded: !dataExpanded })}>
              <Icon name="chevron" size="sm" />
            </button>}
          </div>
          {tables.length > 0 && <div id="kbw-table-directory" className="kbw-subnav" hidden={!dataExpanded}>
            {tables.map(table => (
              <button type="button" key={table.id} title={table.name} aria-label={table.name} aria-current={datasetId === table.id ? 'page' : undefined}
                onClick={() => { setMenu(false); onDataset(table.id); }}>
                <Icon name="table" size="sm" /><span className="kbw-subname">{table.name}</span><small>{table.columns.length}</small>
              </button>
            ))}
          </div>}
        </div>
        <div className="kbw-nav-group"><h2>语义模型
          {hasDraft && <small data-draft>{draftCount} 处草稿</small>}</h2>
          {modeling.map(entry => <div key={entry.id}>{row(entry.id, entry.id === 'coach' && !canManage ? '已确认问答' : entry.label,
            !!detail && !tables.length)}</div>)}
          {canManage && detail && !tables.length && <p>先添加数据集</p>}
        </div>
        {group && <div className="kbw-nav-group"><h2>协作与管理</h2>
          {row('members', '成员与权限')}{row('settings', '设置')}
        </div>}
      </nav>
    </aside>
    <div className="kbw-main">{children}</div>
  </div>;
}
