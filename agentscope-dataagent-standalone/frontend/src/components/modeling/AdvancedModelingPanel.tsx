import { useState } from 'react';
import type { MdlPreview } from '../../api/semanticModeling';
import CompiledMdlPanel from './CompiledMdlPanel';
import ModelSnapshotPanel from './ModelSnapshotPanel';
import EngineeringDialog, { EngineeringVersions } from './EngineeringDialog';
import type { EngineeringVersion } from './EngineeringDialog';
import EngineeringRules from './EngineeringRules';
import './AdvancedModelingPanel.css';

const tabs = [['files', '工程文件'], ['compiled', '编译 MDL'], ['rules', '业务规则']] as const;
type Tab = typeof tabs[number][0];

export default function AdvancedModelingPanel({ groupId, onClose, initialVersion, preview, initialPath, initialDirectory, initialTab = 'files', initialCompare = false }: {
  groupId: string; onClose: () => void; initialVersion: EngineeringVersion; preview: MdlPreview | null;
  initialPath?: string; initialDirectory?: string; initialTab?: Tab; initialCompare?: boolean;
}) {
  const [version, setVersion] = useState(initialVersion);
  const [tab, setTab] = useState<Tab>(initialTab);
  const [visited, setVisited] = useState<Tab[]>([initialTab]);
  const [compare, setCompare] = useState(initialCompare);
  const canCompare = tab === 'files' && (!!preview?.mdlVersion || initialCompare);
  return <EngineeringDialog title="工程与快照" context="知识库工程 · 只读" onClose={onClose}
    toolbar={<><div role="group" aria-label="工程内容" className="eng-tabs">
      {tabs.map(([key, label]) => <button key={key} type="button" aria-pressed={tab === key} onClick={() => { setTab(key); setVisited(previous => previous.includes(key) ? previous : [...previous, key]); }}>{label}</button>)}
    </div><div className="eng-tabs eng-version-actions">
      <EngineeringVersions version={version} publishedVersion={preview?.mdlVersion} onChange={next => { setVersion(next); setCompare(false); }} />
      {canCompare && <button type="button" aria-pressed={compare} onClick={() => setCompare(value => !value)}>草稿对比</button>}
    </div></>}
    footer={<span>{tab === 'compiled' ? '草稿显示最近一次成功构建，可能早于最新保存的改动。' : '从模型、公共视图或业务规则页面修改草稿。'}</span>}>
    {visited.map(key => <div className="eng-tab-view" key={key} hidden={tab !== key}>
      {key === 'files' ? <ModelSnapshotPanel preview={preview} version={version} compare={compare} initialPath={initialPath} initialDirectory={initialDirectory} showChangedPath={initialCompare} />
        : key === 'compiled' ? <CompiledMdlPanel groupId={groupId} version={version} />
        : <EngineeringRules preview={preview} version={version} />}
    </div>)}
  </EngineeringDialog>;
}
