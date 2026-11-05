import { useEffect, useMemo, useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { getKnowledgeBaseBrowse } from '../../api/semanticModeling';
import type { KnowledgeBaseBrowse } from '../../api/semanticModeling';
import SemanticWorkbench from './SemanticWorkbench';
import type { WorkbenchView } from './SemanticWorkbench';
import YamlContent from './YamlContent';
import { publishedSnapshot } from './semanticSnapshot';

type Tab = 'semantic' | 'qa';
const message = (e: unknown) => e instanceof Error ? e.message : String(e);
const isQa = (path: string) => /^knowledge\/(questions|sql)\//.test(path);

/**
 * spec106: what a QUERY user sees: the published version only, no draft and no assistant.
 * spec117: models, relations and rules are the same 语义模型 workbench, read-only.
 */
export default function SemanticReadOnlyWorkspace({ groupId, requested }: { groupId: string; requested: string | null }) {
  const navigate = useNavigate();
  const [params, setParams] = useSearchParams();
  const [browse, setBrowse] = useState<KnowledgeBaseBrowse | null>(null);
  const [error, setError] = useState('');
  useEffect(() => {
    let cancelled = false;
    setBrowse(null); setError('');
    void getKnowledgeBaseBrowse(groupId).then(value => { if (!cancelled) setBrowse(value); })
      .catch(e => { if (!cancelled) setError(message(e)); });
    return () => { cancelled = true; };
  }, [groupId]);
  const snapshot = useMemo(() => browse ? publishedSnapshot(browse) : { models: [], relations: [] }, [browse]);
  const files = useMemo(() => new Map((browse?.knowledge ?? []).map(k => [k.path, k.content])), [browse]);
  const tab: Tab = requested === 'qa' ? 'qa' : 'semantic';
  const asset = params.get('asset');
  const view: WorkbenchView = params.get('view') === 'rules' || asset === 'knowledge' || asset === 'glossary' ? 'rules' : 'graph';
  const published = (browse?.publishedVersion ?? 0) > 0;
  const qa = (browse?.knowledge ?? []).filter(k => !k.path.endsWith('/.gitkeep') && isQa(k.path));
  const tabs: { key: Tab; label: string }[] = [{ key: 'semantic', label: '语义模型' }, { key: 'qa', label: '问答' }];

  return <div className="modeling-workbench mw">
    <div className="mw-top">
    <header className="mw-head">
      <div className="mw-crumb">
        <button type="button" className="mw-crumb-link" onClick={() => navigate('/configure/modeling')}>语义建模</button>
        <span className="mw-crumb-sep">/</span>
        <h1 className="mw-title">{browse?.name ?? '语义模型'}</h1>
        <span className={`mw-pill ${published ? 'mw-pill-ok' : ''}`}>{published ? `线上 v${browse?.publishedVersion}` : '尚未发布'}</span>
        <span className="mw-pill">只读</span>
      </div>
      <div className="mw-actions">
        {published && <button className="da-btn da-btn-primary da-btn-sm" onClick={() => navigate(`/chat?groups=${encodeURIComponent(groupId)}`)}>去问数 →</button>}
      </div>
    </header>
    <nav aria-label="语义建模页面" className="mw-tabs">
      {tabs.map(item => <button key={item.key} type="button" aria-current={tab === item.key ? 'page' : undefined} className={`mw-tab ${tab === item.key ? 'mw-tab-on' : ''}`}
        onClick={() => setParams({ asset: item.key }, { replace: true })}>{item.label}</button>)}
    </nav>
    </div>
    <div className="mw-content">
      {error && <div role="alert" className="da-alert da-alert-error">{error}</div>}
      {browse && !published && <div className="mw-notice">这个知识库还没有发布，发布后这里才有内容。</div>}
      {browse && published && <main className="modeling-workbench-body" style={{ flex: 1, minHeight: 0, display: 'flex' }}>
        {tab === 'semantic' && <SemanticWorkbench key={groupId} groupId={groupId} snapshot={snapshot} baseline={null} files={files} baselineFiles={null}
          views={[]} issues={[]} editable={false} blocked={null} view={view} focusModel={params.get('selected')}
          onViewChange={next => setParams({ asset: 'semantic', view: next }, { replace: true })} onChanged={() => undefined} />}
        {tab === 'qa' && <section className="sm-scroll">
          <h3>已确认问答</h3>
          {qa.length ? qa.map(k => <details key={k.path} className="sm-doc"><summary>{k.path}</summary><YamlContent text={k.content} /></details>)
            : <div className="da-small">当前版本没有已确认问答。</div>}
        </section>}
      </main>}
    </div>
  </div>;
}
