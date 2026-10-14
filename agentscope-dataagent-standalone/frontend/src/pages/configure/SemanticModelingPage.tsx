import { useCallback, useEffect, useRef, useState } from 'react';
import { useNavigate, useParams, useSearchParams } from 'react-router-dom';
import ModelingChatPanel from '../../components/ModelingChatPanel';
import MdlPublishPanel from '../../components/MdlPublishPanel';
import MdlGraphView from '../../components/MdlGraphView';
import BusinessDocumentGuide from '../../components/modeling/BusinessDocumentGuide';
import type { DocumentUploadState } from '../../components/modeling/BusinessDocumentGuide';
import QuestionIntakeForm from '../../components/modeling/QuestionIntakeForm';
import ModelSnapshotPanel from '../../components/modeling/ModelSnapshotPanel';
import AssetYamlBrowser from '../../components/modeling/AssetYamlBrowser';
import SqlQaExamplesPanel from '../../components/modeling/SqlQaExamplesPanel';
import type { AssetEntry } from '../../components/modeling/AssetYamlBrowser';
import { getGroupDetail, uploadKnowledge } from '../../api/datasets';
import { getMdlView, getMdlPreview, initializeMdl } from '../../api/semanticModeling';
import type { MdlView, MdlPreview } from '../../api/semanticModeling';
import { getModelingWorkflow } from '../../api/modelingWorkflow';
import type { ModelingWorkflow } from '../../api/modelingWorkflow';
import './SemanticModelingPage.css';

type Tab = 'overview' | 'modeling' | 'publish' | 'assets';
type Asset = 'schema' | 'derived' | 'views' | 'cubes' | 'glossary' | 'sql' | 'mdl';
const tabs: { key: Tab; label: string }[] = [
  { key: 'overview', label: '1 · 数据与资料' },
  { key: 'modeling', label: '2 · 对话建模' },
  { key: 'publish', label: '3 · 检查与发布' },
  { key: 'assets', label: '查看语义模型' },
];
const assetLabels: Record<Asset, string> = {
  schema: '模型与关系', derived: 'SQL 定义模型', views: '视图', cubes: 'Cube',
  glossary: '业务知识与样例', sql: 'SQL 问答对', mdl: 'MDL 与工程快照',
};
const message = (e: unknown) => e instanceof Error ? e.message : String(e);

export default function SemanticModelingPage() {
  const { groupId = '' } = useParams();
  const navigate = useNavigate();
  const [params, setParams] = useSearchParams();
  const requested = params.get('asset') || 'overview';
  const tab: Tab = tabs.some(t => t.key === requested) ? requested as Tab
    : Object.prototype.hasOwnProperty.call(assetLabels, requested) ? 'assets' : 'overview';
  const asset: Asset = Object.prototype.hasOwnProperty.call(assetLabels, requested) ? requested as Asset
    : Object.prototype.hasOwnProperty.call(assetLabels, params.get('kind') || '') ? params.get('kind') as Asset : 'schema';
  const [name, setName] = useState('语义模型');
  const [document, setDocument] = useState('');
  const [upload, setUpload] = useState<DocumentUploadState>({ status: 'idle', filename: '' });
  const [view, setView] = useState<MdlView | null>(null);
  const [preview, setPreview] = useState<MdlPreview | null>(null);
  const [workflow, setWorkflow] = useState<ModelingWorkflow | null>(null);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const [draft, setDraft] = useState<{ groupId: string; text: string; id: number } | null>(null);
  const [questionsOpen, setQuestionsOpen] = useState(false);
  const input = useRef<HTMLInputElement>(null);
  const activeGroup = useRef(groupId); activeGroup.current = groupId;
  const generation = useRef(0);
  const documentRevision = useRef(0);
  const draftSequence = useRef(0);
  const selectTab = (key: Tab) => setParams({ asset: key }, { replace: true });
  const request = (text: string) => {
    setDraft({ groupId, text, id: ++draftSequence.current });
    selectTab('modeling');
  };
  const refresh = useCallback(async () => {
    const current = ++generation.current;
    const results = await Promise.allSettled([getMdlView(groupId), getMdlPreview(groupId), getModelingWorkflow(groupId)]);
    if (current !== generation.current || activeGroup.current !== groupId) return;
    if (results[0].status === 'fulfilled') setView(results[0].value);
    if (results[1].status === 'fulfilled') setPreview(results[1].value);
    if (results[2].status === 'fulfilled') setWorkflow(results[2].value);
    const failed = results.find(r => r.status === 'rejected');
    setError(failed?.status === 'rejected' ? message(failed.reason) : '');
  }, [groupId]);
  useEffect(() => {
    setView(null); setPreview(null); setWorkflow(null); setDocument(''); setName('语义模型');
    setUpload({ status: 'idle', filename: '' }); setQuestionsOpen(false);
    let cancelled = false;
    const revision = ++documentRevision.current;
    void getGroupDetail(groupId).then(detail => {
      if (!cancelled) { setName(detail.group.name); if (revision === documentRevision.current) setDocument(detail.knowledge || ''); }
    }).catch(e => { if (!cancelled) setError(message(e)); });
    void refresh();
    const update = (event: Event) => {
      if ((event as CustomEvent<{ groupId: string }>).detail?.groupId === groupId) void refresh();
    };
    window.addEventListener('modeling:updated', update);
    return () => { cancelled = true; generation.current++; window.removeEventListener('modeling:updated', update); };
  }, [groupId, refresh]);
  const uploadDocument = async (file: File) => {
    if (upload.status === 'uploading') return;
    documentRevision.current++;
    setUpload({ status: 'uploading', filename: file.name });
    try {
      const content = await uploadKnowledge(groupId, file);
      if (activeGroup.current !== groupId) return;
      setDocument(content); setUpload({ status: 'success', filename: file.name });
      await refresh();
    } catch (e) {
      if (activeGroup.current === groupId) setUpload({ status: 'error', filename: file.name, error: message(e) });
    }
  };
  const prepare = async () => {
    setBusy(true); setError('');
    try {
      const result = await initializeMdl(groupId);
      if (!result.ok) throw new Error(result.issues.map(i => i.message).join('；'));
      await refresh();
    } catch (e) { setError(message(e)); } finally { setBusy(false); }
  };
  const files = preview?.files || [];
  const published = new Map(preview?.publishedFiles.map(f => [f.path, f.content]) || []);
  const dirty = (path: string | null) => !!path && files.some(f => f.path === path && published.get(path) !== f.content);
  const entries: AssetEntry[] = asset === 'schema' ? (view?.models || []).map(m => ({
    key: m.modelName, title: m.modelName, subtitle: m.description || `${m.columns.length} 列`, file: m.path, dirty: dirty(m.path),
  })) : asset === 'derived' ? (view?.derivedModels || []).map(m => ({
    key: m.modelName, title: m.modelName, subtitle: m.description || undefined, file: m.refSqlPath || m.path, dirty: dirty(m.path) || dirty(m.refSqlPath),
  })) : asset === 'views' ? (view?.views || []).map(v => ({
    key: v.name, title: v.name, subtitle: v.description || undefined, file: v.path, dirty: dirty(v.path),
  })) : asset === 'cubes' ? (view?.cubes || []).map(c => ({
    key: c.name, title: c.name, subtitle: `${c.baseModel} · ${c.measures.length} 个指标`, file: c.path, dirty: dirty(c.path),
  })) : files.filter(f => f.path.startsWith('knowledge/')).map(f => ({ key: f.path, title: f.path, file: f.path, dirty: dirty(f.path) }));
  const selected = params.get('selected') || entries[0]?.key || null;
  const docs = <BusinessDocumentGuide content={document} upload={upload} onUpload={() => input.current?.click()} />;
  return <div className="modeling-workbench" style={{ flex: 1, minHeight: 0, display: 'flex', flexDirection: 'column', padding: 16, gap: 12 }}>
    <input ref={input} type="file" hidden onChange={e => { const file = e.target.files?.[0]; e.target.value = ''; if (file) void uploadDocument(file); }} />
    <header className="da-card" style={{ display: 'flex', alignItems: 'center', gap: 12, flexWrap: 'wrap' }}>
      <button className="da-btn" onClick={() => navigate(`/configure/datasets/${groupId}`)}>← 知识库</button>
      <strong>{name} · Wren 语义建模</strong>
      <span className="da-badge">{workflow?.queryAvailable ? `问数使用已发布 v${workflow.publishedVersion}` : '基础模型待发布'}{workflow?.draftChanged ? ' · 草稿有变更' : ''}</span>
      {workflow?.queryAvailable && <button className="da-btn" onClick={() => navigate(`/chat?groups=${encodeURIComponent(groupId)}`)}>去问数</button>}
    </header>
    <nav aria-label="建模流程" style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
      {tabs.map(item => <button key={item.key} className={`da-btn ${tab === item.key ? 'da-btn-primary' : ''}`} onClick={() => selectTab(item.key)}>{item.label}</button>)}
    </nav>
    {error && <div role="alert" className="da-alert da-alert-error">{error}<button className="da-btn" onClick={() => void refresh()}>刷新</button></div>}
    <main className="modeling-workbench-body" style={{ flex: 1, minHeight: 0, display: 'flex', gap: 16 }}>
      {tab === 'overview' && <section style={{ flex: 1, overflowY: 'auto', display: 'flex', flexDirection: 'column', gap: 12 }}>
        <div className="da-card"><h3>准备数据</h3><p>上传 CSV / Excel 或关联表后生成基础模型。基础模型发布成功即可问数，后续可逐步完善业务含义。</p>
          <button className="da-btn" onClick={() => navigate(`/configure/datasets/${groupId}`)}>上传数据 / 关联表</button>
          {!workflow?.queryAvailable && <button className="da-btn" disabled={busy} onClick={() => void prepare()}>{busy ? '生成中…' : '生成并发布基础模型'}</button>}
        </div>
        {docs}
        <div className="da-card"><h3>常用问题（可选）</h3><p>可以批量填写、后续追加，也可以直接对话。问题不是模型发布的前置条件。</p>
          {questionsOpen ? <QuestionIntakeForm initial onSubmit={request} onCancel={() => setQuestionsOpen(false)} />
            : <button className="da-btn" onClick={() => setQuestionsOpen(true)}>＋ 批量填写问题</button>}
        </div>
        <button className="da-btn da-btn-primary" onClick={() => request('请加载官方 Wren 技能，检查当前工程与可选业务文档，按官方建模流程完善语义模型；先澄清影响业务口径的歧义，技术校验、编译和查询检查由你完成。预设问题可选，完成后引导我发布。')}>开始对话建模</button>
      </section>}
      <section aria-label="对话建模工作区" style={{ display: tab === 'modeling' ? 'flex' : 'none', flex: 1, minWidth: 0, minHeight: 0, gap: 16 }}>
        <aside className="modeling-progress-column" style={{ flex: '0 0 280px', overflowY: 'auto', display: 'flex', flexDirection: 'column', gap: 12 }}>
          {docs}
          <div className="da-card"><strong>当前草稿</strong><p>通过对话补充业务含义，助手使用官方 Wren 工具处理模型与检查。确认权限只授权草稿写入，发布后才影响问数。</p>
            <button className="da-btn" onClick={() => setQuestionsOpen(!questionsOpen)}>＋ 添加常用问题（可选）</button>
            {tab === 'modeling' && questionsOpen && <QuestionIntakeForm onSubmit={request} onCancel={() => setQuestionsOpen(false)} />}
          </div>
          <button className="da-btn" onClick={() => selectTab('assets')}>查看模型 / 关系 / 视图 / Cube / MDL</button>
          <button className="da-btn" onClick={() => request('请用官方 Wren 完成当前草稿的校验、编译与查询检查；失败时修正草稿，不改写官方编译产物。预设问题验证可选，检查通过后引导我进入检查与发布。')}>请助手检查草稿</button>
          <button className="da-btn da-btn-primary" onClick={() => selectTab('publish')}>下一步：检查与发布 →</button>
        </aside>
        <div style={{ flex: 1, minWidth: 0, minHeight: 0, display: 'flex' }}><ModelingChatPanel key={groupId} groupId={groupId} variant="dock"
          draftPrompt={draft?.groupId === groupId ? draft.text : undefined} draftPromptId={draft?.id} submitDraft={draft?.groupId === groupId}
          onAttachDocument={() => input.current?.click()} onDraftSubmitted={() => setDraft(null)} onClose={() => undefined} /></div>
      </section>
      {tab === 'publish' && <section style={{ flex: 1, overflowY: 'auto' }}>
        <p>业务含义确认后发布。平台通过官方 Wren 校验、构建和查询检查；失败会保留原发布版本，预设问题无需逐题验收。</p>
        <MdlPublishPanel groupId={groupId} workflow={workflow} onPublished={refresh} />
        <button className="da-btn" onClick={() => selectTab('modeling')}>返回助手修正草稿</button>
        {workflow?.queryAvailable && <button className="da-btn da-btn-primary" onClick={() => navigate(`/chat?groups=${encodeURIComponent(groupId)}`)}>用已发布模型问数 →</button>}
      </section>}
      {tab === 'assets' && <section style={{ flex: 1, overflowY: 'auto' }}>
        <p>当前草稿只读展示；问数使用已发布工程。修改请返回对话建模。</p>
        <select className="da-input" aria-label="语义资产类型" value={asset} onChange={e => setParams({ asset: 'assets', kind: e.target.value }, { replace: true })}>
          {Object.entries(assetLabels).map(([key, label]) => <option key={key} value={key}>{label}</option>)}
        </select>
        {asset === 'sql' ? <SqlQaExamplesPanel groupId={groupId} /> : asset === 'mdl' ? <><MdlGraphView groupId={groupId} /><ModelSnapshotPanel groupId={groupId} /></>
          : <><AssetYamlBrowser groupId={groupId} entries={entries} selectedKey={selected}
            onSelect={key => setParams({ asset: 'assets', kind: asset, selected: key }, { replace: true })} emptyText="当前草稿暂无此类资产" />
            {asset === 'schema' && <MdlGraphView groupId={groupId} />}</>}
      </section>}
    </main>
  </div>;
}
