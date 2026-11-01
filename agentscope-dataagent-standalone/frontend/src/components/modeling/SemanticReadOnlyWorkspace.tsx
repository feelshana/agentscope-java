import { useEffect, useMemo, useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { getKnowledgeBaseBrowse } from '../../api/semanticModeling';
import type { KnowledgeBaseBrowse } from '../../api/semanticModeling';
import BusinessTermsPanel from './BusinessTermsPanel';
import ModelsPanel from './ModelsPanel';
import RelationsPanel from './RelationsPanel';
import YamlContent from './YamlContent';
import { publishedSnapshot } from './semanticSnapshot';

type Tab = 'models' | 'relations' | 'knowledge' | 'qa';
const message = (e: unknown) => e instanceof Error ? e.message : String(e);
const isQa = (path: string) => /^knowledge\/(questions|sql)\//.test(path);

/** spec106: what a QUERY user sees: the published version only, no draft and no assistant. */
export default function SemanticReadOnlyWorkspace({ groupId, requested }: { groupId: string; requested: string | null }) {
  const navigate = useNavigate();
  const [params, setParams] = useSearchParams();
  const [browse, setBrowse] = useState<KnowledgeBaseBrowse | null>(null);
  const [error, setError] = useState('');
  const [relation, setRelation] = useState<string | null>(null);
  useEffect(() => {
    let cancelled = false;
    setBrowse(null); setError('');
    void getKnowledgeBaseBrowse(groupId).then(value => { if (!cancelled) setBrowse(value); })
      .catch(e => { if (!cancelled) setError(message(e)); });
    return () => { cancelled = true; };
  }, [groupId]);
  const snapshot = useMemo(() => browse ? publishedSnapshot(browse) : { models: [], relations: [] }, [browse]);
  const tab: Tab = requested === 'relations' || requested === 'knowledge' || requested === 'qa' ? requested : 'models';
  const select = (key: Tab) => setParams({ asset: key }, { replace: true });
  const published = (browse?.publishedVersion ?? 0) > 0;
  const documents = (browse?.knowledge ?? []).filter(k => !k.path.endsWith('/.gitkeep') && (tab === 'qa' ? isQa(k.path) : !isQa(k.path)));
  const tabs: { key: Tab; label: string }[] = [
    { key: 'models', label: `模型 ${snapshot.models.length}` }, { key: 'relations', label: `关系 ${snapshot.relations.length}` },
    { key: 'knowledge', label: '业务知识' }, { key: 'qa', label: '问答' },
  ];

  return <div className="modeling-workbench mw">
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
      {tabs.map(item => <button key={item.key} type="button" aria-current={tab === item.key ? 'page' : undefined} className={`mw-tab ${tab === item.key ? 'mw-tab-on' : ''}`} onClick={() => select(item.key)}>{item.label}</button>)}
    </nav>
    <div className="mw-content">
      {error && <div role="alert" className="da-alert da-alert-error">{error}</div>}
      {browse && !published && <div className="mw-notice">这个知识库还没有发布，发布后这里才有内容。</div>}
      {browse && published && <main className="modeling-workbench-body" style={{ flex: 1, minHeight: 0, display: 'flex' }}>
        {tab === 'models' && <ModelsPanel snapshot={snapshot} selected={params.get('selected')} emptyText="当前版本没有模型"
          onSelect={name => setParams({ asset: 'models', selected: name }, { replace: true })}
          onOpenRelation={name => { setRelation(name); select('relations'); }} />}
        {tab === 'relations' && <RelationsPanel snapshot={snapshot} focus={relation} emptyText="当前版本没有模型"
          onOpenModel={name => setParams({ asset: 'models', selected: name }, { replace: true })} />}
        {(tab === 'knowledge' || tab === 'qa') && <section className="sm-scroll">
          {tab === 'knowledge' && <BusinessTermsPanel groupId={groupId} />}
          <h3>{tab === 'qa' ? '已确认问答' : '业务规则与说明'}</h3>
          {documents.length ? documents.map(k => <details key={k.path} className="sm-doc"><summary>{k.path}</summary><YamlContent text={k.content} /></details>)
            : <div className="da-small">{tab === 'qa' ? '当前版本没有已确认问答。' : '当前版本没有其他业务规则。'}</div>}
        </section>}
      </main>}
    </div>
  </div>;
}
