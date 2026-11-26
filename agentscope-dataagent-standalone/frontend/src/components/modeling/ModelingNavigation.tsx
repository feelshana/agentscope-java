import type { ReactNode } from 'react';
import Icon, { type IconName } from '../Icon';
import { WorkspaceMenu } from '../KnowledgeWorkspace';
import type { WorkbenchView } from './SemanticWorkbench';
import './ModelingWorkbenchTheme.css';

export type ModelingModule = 'semantic' | 'coach' | 'assets' | 'overview';
type Destination = WorkbenchView | Exclude<ModelingModule, 'semantic'>;
type Entry = { key: Destination; label: string; icon: IconName };

const graphEntries: Entry[] = [
  { key: 'graph', label: '关系图', icon: 'graph' },
  { key: 'rules', label: '业务规则', icon: 'terms' },
];
const modules: Entry[] = [
  { key: 'coach', label: '问数优化', icon: 'chat' },
  { key: 'assets', label: '公共视图', icon: 'table' },
  { key: 'overview', label: '资料', icon: 'folder' },
];
const readOnlyModules: Entry[] = [{ key: 'coach', label: '已确认问答', icon: 'chat' }];

export default function ModelingNavigation({ name, version, current, readOnly, preparing, busy, queryUnavailable, versionPicker, children, onLibrary, onSelect, onEngineering }: {
  name: string;
  version: number;
  current?: Destination;
  readOnly?: boolean;
  preparing?: boolean;
  busy?: boolean;
  queryUnavailable?: boolean;
  versionPicker?: ReactNode;
  children: ReactNode;
  onLibrary: () => void;
  onSelect: (tab: ModelingModule, view?: WorkbenchView) => void;
  onEngineering?: () => void;
}) {
  const entries = readOnly ? readOnlyModules : modules;
  const destinations = [...graphEntries, ...entries];
  const select = (key: Destination) => key === 'graph' || key === 'rules' ? onSelect('semantic', key) : onSelect(key);
  const button = (entry: Entry) => <button key={entry.key} type="button" className="mw-module-link"
    aria-current={current === entry.key ? 'page' : undefined} onClick={() => select(entry.key)}>
    <Icon name={entry.icon} size="sm" />{entry.label}
  </button>;
  return <>
    <header className="mw-workspace-header">
      <div className="mw-library-identity">
        <button type="button" className="mw-library-link" title="返回当前知识库" aria-label="返回当前知识库" onClick={onLibrary} disabled={busy}>
          <Icon name="book" />
        </button>
        {current && current !== 'graph' && <span className="mw-workspace-scope">语义模型</span>}
        <span className="mw-library-slash" aria-hidden="true">/</span>
        <h1 title={name}>{name}</h1>
        <div className="mw-library-status" title={queryUnavailable ? '当前不可问数' : undefined}>
          {versionPicker ?? <span className={version ? 'mw-pill mw-pill-ok' : 'mw-pill'}>{version ? <><i className="mw-dot" />线上 v{version}</> : '尚未发布'}</span>}
          {readOnly && <span className="mw-pill">只读</span>}
        </div>
      </div>
      {!preparing && <div className="mw-mobile-modules">
        <WorkspaceMenu label="切换建模页面" triggerLabel={destinations.find(entry => entry.key === current)?.label ?? '目录'} triggerIcon="menu" items={[
          ...destinations.map(entry => ({ ...entry, onClick: () => select(entry.key) })),
          ...(onEngineering ? [{ label: '工程与快照', icon: 'code' as const, onClick: onEngineering }] : []),
        ]} />
      </div>}
      <div className="mw-actions">{children}</div>
    </header>
    {!preparing && <aside className="mw-module-sidebar" aria-label="建模工作区">
      <nav className="mw-module-tree" aria-label="语义模型页面">
        <div className="mw-module-group" role="group" aria-label="语义模型">
          <span className="mw-module-heading" aria-hidden="true">语义模型</span>
          {graphEntries.map(button)}
        </div>
        <div className="mw-module-group mw-module-group-secondary" role="group" aria-label="其他建模页面">
          {entries.map(button)}
        </div>
        {onEngineering && <button type="button" className="mw-module-link mw-engineering" onClick={onEngineering} title="工程与快照"><Icon name="code" size="sm" /><span>工程与快照</span></button>}
      </nav>
    </aside>}
  </>;
}
