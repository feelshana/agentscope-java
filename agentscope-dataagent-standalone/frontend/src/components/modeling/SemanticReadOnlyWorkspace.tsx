import { useEffect, useMemo, useState } from 'react';
import { createPortal } from 'react-dom';
import { useSearchParams } from 'react-router-dom';
import { getKnowledgeBaseBrowse } from '../../api/semanticModeling';
import type { KnowledgeBaseBrowse } from '../../api/semanticModeling';
import SemanticWorkbench from './SemanticWorkbench';
import KnowledgeEngineeringPage from '../../pages/configure/KnowledgeEngineeringPage';
import type { KnowledgeModelingHost } from '../../pages/configure/knowledgeWorkspaceRoutes';
import type { WorkbenchView } from './SemanticWorkbench';
import YamlContent from './YamlContent';
import { publishedSnapshot } from './semanticSnapshot';

type Tab = 'semantic' | 'qa' | 'engineering';
const message = (e: unknown) => e instanceof Error ? e.message : String(e);
const isQa = (path: string) => /^knowledge\/(questions|sql)\//.test(path);

/**
 * spec106: what a QUERY user sees: the published version only, no draft and no assistant.
 * spec117: models, relations and rules are the same 语义模型 workbench, read-only.
 */
export default function SemanticReadOnlyWorkspace({ groupId, host }: { groupId: string; host: KnowledgeModelingHost }) {
  const [params, setParams] = useSearchParams();
  const [browse, setBrowse] = useState<KnowledgeBaseBrowse | null>(null);
  const [error, setError] = useState('');
  const [checkTarget, setCheckTarget] = useState<HTMLDivElement | null>(null);
  useEffect(() => {
    let cancelled = false;
    setBrowse(null); setError('');
    void getKnowledgeBaseBrowse(groupId).then(value => { if (!cancelled) setBrowse(value); })
      .catch(e => { if (!cancelled) setError(message(e)); });
    return () => { cancelled = true; };
  }, [groupId]);
  const snapshot = useMemo(() => browse ? publishedSnapshot(browse) : { models: [], relations: [] }, [browse]);
  const files = useMemo(() => new Map((browse?.knowledge ?? []).map(k => [k.path, k.content])), [browse]);
  const asset = params.get('asset');
  const tab: Tab = asset === 'engineering' || asset === 'mdl' || asset === 'views' || asset === 'cubes' ? 'engineering' : asset === 'coach' || asset === 'qa' || asset === 'sql' ? 'qa' : 'semantic';
  const view: WorkbenchView = params.get('view') === 'rules' || (params.get('view') !== 'graph' && (asset === 'knowledge' || asset === 'glossary')) ? 'rules' : 'graph';
  const published = (browse?.publishedVersion ?? 0) > 0;
  const qa = (browse?.knowledge ?? []).filter(k => !k.path.endsWith('/.gitkeep') && isQa(k.path));
  return <div className={`modeling-workbench mw mw-workspace-layout mw-in-knowledge${tab === 'semantic' && view === 'rules' ? ' mw-compact-workbench' : ''}`}>
    {host.actionsTarget && createPortal(<>
        {tab === 'semantic' && published && <div className="mw-header-check" ref={setCheckTarget} />}
        <span className="kbw-version">只读</span>
    </>, host.actionsTarget)}
    <div className="mw-content">
      {error && <div role="alert" className="da-alert da-alert-error">{error}</div>}
      {!browse && !error && <p className="mw-notice" role="status">正在读取线上版本…</p>}
      {tab === 'engineering' && <KnowledgeEngineeringPage groupId={groupId} canManage={false} />}
      {tab !== 'engineering' && browse && !published && <div className="mw-notice">这个知识库还没有发布，发布后这里才有内容。</div>}
      {published && tab === 'semantic' && view === 'graph' && <header className="kbw-graph-heading">
        <h2>关系图</h2><p>{snapshot.models.length} 个模型 · {snapshot.relations.length} 条关系</p>
      </header>}
      {tab !== 'engineering' && browse && published && <main className="modeling-workbench-body" style={{ flex: 1, minHeight: 0, display: 'flex' }}>
        {tab === 'semantic' && <SemanticWorkbench key={groupId} groupId={groupId} snapshot={snapshot} baseline={null} files={files} baselineFiles={null}
          views={[]} issues={[]} editable={false} checkTarget={checkTarget} blocked={null} view={view} focusModel={params.get('selected')} defaultRailOpen={false} onEngineering={path => setParams({ asset: 'engineering', ...(path ? { path } : {}) })}
          onViewChange={next => setParams({ asset: 'semantic', view: next }, { replace: true })} onChanged={() => undefined} />}
        {tab === 'qa' && <section className="sm-scroll">
          {qa.length ? qa.map(k => <details key={k.path} className="sm-doc"><summary>{k.path}</summary><YamlContent text={k.content} /></details>)
            : <div className="da-small">当前版本没有已确认问答。</div>}
        </section>}
      </main>}
    </div>
  </div>;
}
