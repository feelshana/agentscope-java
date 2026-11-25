import { useEffect, useMemo, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { getKnowledgeBaseBrowse, getMdlPreview, type MdlPreview } from '../../api/semanticModeling';
import CompiledMdlPanel from '../../components/modeling/CompiledMdlPanel';
import EngineeringRules from '../../components/modeling/EngineeringRules';
import ModelSnapshotPanel, { engineeringFiles } from '../../components/modeling/ModelSnapshotPanel';
import './KnowledgeEngineeringPage.css';

const tabs = [['files', '工程文件'], ['compiled', '编译 MDL'], ['rules', '业务规则']] as const;
type Tab = typeof tabs[number][0];

export default function KnowledgeEngineeringPage({ groupId, canManage }: { groupId: string; canManage: boolean }) {
  const [params, setParams] = useSearchParams();
  const [preview, setPreview] = useState<MdlPreview | null>(null);
  const [error, setError] = useState('');
  const [revision, setRevision] = useState(0);
  const [tab, setTab] = useState<Tab>(() => params.get('asset') === 'mdl' || params.get('kind') === 'mdl' ? 'compiled' : 'files');
  const [onlyChanged, setOnlyChanged] = useState(false);
  const compare = canManage && params.get('compare') === '1';
  const version = canManage && params.get('version') !== 'published' ? 'draft' : 'published';

  useEffect(() => {
    let cancelled = false;
    let generation = 0;
    const refresh = async () => {
      const current = ++generation;
      setError('');
      try {
        const result = canManage ? await getMdlPreview(groupId) : await getKnowledgeBaseBrowse(groupId).then(online => ({
          files: [], publishedFiles: online.knowledge, mdlVersion: online.publishedVersion, mdlPublishedAt: null,
          issues: [], mdlState: online.publishedVersion ? 'PUBLISHED' : 'NONE', changed: false,
        }));
        if (!cancelled && current === generation) setPreview(result);
      } catch (e) {
        if (!cancelled && current === generation) setError(e instanceof Error ? e.message : '无法读取工程文件');
      }
    };
    const onUpdate = (event: Event) => { if ((event as CustomEvent<{ groupId: string }>).detail?.groupId === groupId) void refresh(); };
    void refresh();
    window.addEventListener('modeling:updated', onUpdate);
    return () => { cancelled = true; window.removeEventListener('modeling:updated', onUpdate); };
  }, [canManage, groupId, revision]);

  const files = useMemo(() => engineeringFiles(preview), [preview]);
  const changed = [...files.values()].filter(file => file.status !== 'unchanged').length;
  const changeParams = (change: (next: URLSearchParams) => void) => {
    setParams(previous => {
      const next = new URLSearchParams(previous);
      change(next);
      return next;
    }, { replace: true });
  };
  const showVersion = (next: 'draft' | 'published') => {
    setOnlyChanged(false);
    changeParams(search => { search.set('version', next); search.delete('compare'); });
  };
  const toggleCompare = () => changeParams(search => {
    if (compare) search.delete('compare');
    else { search.set('compare', '1'); search.delete('version'); }
  });
  const asset = params.get('kind') ?? params.get('asset');
  const requestedPath = params.get('path') ?? undefined;
  const publishedLabel = preview?.mdlVersion ? `线上 v${preview.mdlVersion}` : '线上版本';

  return <section className="kbw-engineering" aria-label="工程与快照">
    <header className="kbw-engineering-heading">
      <div><h1>工程与快照</h1><p>{canManage ? '只读。草稿与线上按文件对照。' : '只读。可浏览已发布的业务规则与问答文件。'}</p></div>
      <div className="eng-tabs eng-versions" role="group" aria-label="查看版本">
        {canManage && <button type="button" aria-pressed={version === 'draft'} onClick={() => showVersion('draft')}>当前草稿</button>}
        <button type="button" aria-pressed={version === 'published'} disabled={!preview?.mdlVersion} onClick={() => showVersion('published')}>{publishedLabel}</button>
      </div>
    </header>
    <div className="eng-toolbar kbw-engineering-toolbar">
      <div role="group" aria-label="工程内容" className="eng-tabs">
        {tabs.map(([id, title]) => <button type="button" key={id} aria-pressed={tab === id} onClick={() => setTab(id)}>{title}</button>)}
      </div>
      {canManage && tab === 'files' && <div className="eng-tabs">
        <button type="button" aria-pressed={onlyChanged} onClick={() => setOnlyChanged(value => !value)}>仅看改动 ({changed})</button>
        <button type="button" aria-pressed={compare} disabled={!preview?.mdlVersion && !compare} onClick={toggleCompare}>草稿对比</button>
      </div>}
    </div>
    <div className="kbw-engineering-body">
      {error ? <div className="am-error" role="alert"><span>{error}</span><button type="button" className="da-btn" onClick={() => setRevision(value => value + 1)}>重试</button></div>
        : tab === 'files' ? <ModelSnapshotPanel files={files} loading={!preview} version={version} compare={compare} onlyChanged={onlyChanged}
          publishedLabel={publishedLabel} initialPath={requestedPath} initialDirectory={asset === 'views' || asset === 'cubes' ? asset + '/' : undefined}
          onSelect={path => changeParams(search => search.set('path', path))} />
        : tab === 'compiled' ? canManage ? <CompiledMdlPanel groupId={groupId} version={version} />
          : <p className="am-empty">编译产物仅知识库管理者可查看。</p>
        : <EngineeringRules preview={preview} version={version} />}
    </div>
  </section>;
}
