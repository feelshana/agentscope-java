import { FactQueryWindowSettings } from '../../components/FactQueryWindowSettings';
import { useCallback, useEffect, useRef, useState } from 'react';
import { useNavigate, useParams, useSearchParams } from 'react-router-dom';
import ModelingChatPanel from '../../components/ModelingChatPanel';
import type { AssistantState } from '../../components/ModelingChatPanel';
import MdlGraphView from '../../components/MdlGraphView';
import BusinessDocumentsPanel from '../../components/modeling/BusinessDocumentsPanel';
import FieldScreeningPanel from '../../components/modeling/FieldScreeningPanel';
import type { FieldAdjustment } from '../../components/modeling/FieldScreeningPanel';
import { getScreeningStatus } from '../../api/fieldScreening';
import type { ScreeningStatus } from '../../api/fieldScreening';
import ConfirmationCards from '../../components/modeling/ConfirmationCards';
import DraftBar from '../../components/modeling/DraftBar';
import type { DraftSummary } from '../../components/modeling/DraftBar';
import NextStepCard from '../../components/modeling/NextStepCard';
import TrialPanel from '../../components/modeling/TrialPanel';
import TurnResultCard from '../../components/modeling/TurnResultCard';
import ModelSnapshotPanel from '../../components/modeling/ModelSnapshotPanel';
import AssetYamlBrowser from '../../components/modeling/AssetYamlBrowser';
import SqlQaExamplesPanel from '../../components/modeling/SqlQaExamplesPanel';
import type { AssetEntry } from '../../components/modeling/AssetYamlBrowser';
import { getGroupDetail } from '../../api/datasets';
import { getMdlView, getMdlPreview } from '../../api/semanticModeling';
import type { MdlView, MdlPreview } from '../../api/semanticModeling';
import { getModelingWorkflow } from '../../api/modelingWorkflow';
import type { ModelingWorkflow } from '../../api/modelingWorkflow';
import { listConfirmations, resolveConfirmations } from '../../api/draftReview';
import type { Confirmation, ConfirmationAnswer, ConfirmationDecision } from '../../api/draftReview';
import type { TurnPurpose } from '../../api/chat';
import { useQueryReadiness } from '../../components/useQueryReadiness';
import './SemanticModelingPage.css';

type Tab = 'modeling' | 'overview' | 'assets';
type Asset = 'schema' | 'derived' | 'views' | 'cubes' | 'glossary' | 'sql' | 'mdl';
// spec092 §7: the conversation is the workspace; review happens inline, publishing in the draft bar.
const tabs: { key: Tab; label: string }[] = [
  { key: 'modeling', label: '建模对话' },
  { key: 'overview', label: '资料' },
  { key: 'assets', label: '语义模型' },
];
const assetLabels: Record<Asset, string> = {
  schema: '模型与关系', derived: 'SQL 定义模型', views: '视图', cubes: 'Cube',
  glossary: '业务知识与样例', sql: 'SQL 问答对', mdl: 'MDL 与工程快照',
};
const message = (e: unknown) => e instanceof Error ? e.message : String(e);
const AUTO_MODELING_REQUEST = '请加载官方 Wren 技能，按官方 Auto-pilot 完善当前采样后的语义模型。事实表时间范围已独立保存到数据库，必要业务 SQL 遵守工具时间门禁；数据探查遵守运行上下文中的表来源与探查边界；运行上下文列出"本次新增的表"时只处理这些表。'
  + '业务文档位于 raw/docs/（只读，可能没有）。完成校验后按回复写法简短说明（不列文件与命令）；修改进入草稿，由我在对话中查看后统一发布。';
/** spec092: sent automatically after documents change; the assistant reconciles, never regenerates. */
const documentRequest = (changes: string[]) => `业务文档有变化：${changes.join('；')}。文档位于 raw/docs/（只读）。`
  + '请读取有变化的文档，与当前模型描述、knowledge/rules 和 knowledge/caveats 对比后更新：文档写清楚的指标定义、默认过滤、术语直接写入 knowledge/rules/（注明来源文档），'
  + '字段含义写入对应模型的列描述，不要向我确认；只有文档有歧义、且不同理解会改变统计结果时，才用 request_user_confirmation 提问。'
  + '文档被删除时，把可能来自该文档的规则改为待确认说明写入 knowledge/caveats/。不要重新生成模型，不要重新加载已加载的技能，不要写 Cube/View。'
  + '只修改与文档有差异的内容，完成后执行校验，按回复写法简短说明。';
/** spec092: hands the user's answers to the assistant to write where queries read them. */
const confirmRequest = (decisions: ConfirmationDecision[]) => '我确定了以下口径，请整理后写入草稿：指标定义、默认过滤、术语写入 knowledge/rules/，'
  + '字段含义写入对应模型的列描述，确定建立的关系写入 relationships.yml；不要再次向我确认这些内容，完成后用一两行说明更新了哪些口径。\n'
  + decisions.map((d, i) => `${i + 1}. 问题：${d.question}\n   我的确定：${d.answer}`).join('\n');

/** spec093: after the modeler changed one table's fields, only that table is re-modeled. */
const remodelRequest = (c: FieldAdjustment) => `建模人员调整了表 ${c.table} 的字段：`
  + (c.added.length ? `新纳入 ${c.added.join('、')}；` : '') + (c.removed.length ? `取消纳入 ${c.removed.join('、')}；` : '')
  + (c.described.length ? `改写了 ${c.described.join('、')} 的说明；` : '')
  + `草稿模型已按此更新。请只处理这张表：为新纳入且没有说明的字段补充列描述；检查 knowledge/rules、relationships.yml 与 knowledge/questions 中引用被取消字段的内容并修正，无法修正的写入 knowledge/caveats 说明；`
  + '不要修改带 description_source: user 的说明，不要改动其他表。完成后执行校验，按回复写法简短说明。';

/** Short texts the conversation shows for the requests above. */
const documentLabel = (changes: string[]) => `业务文档有变化，请同步到模型：${changes.join('；')}`;
const confirmLabel = (decisions: ConfirmationDecision[]) => `我确定了 ${decisions.length} 项口径：\n`
  + decisions.map(d => `· ${d.question} → ${d.answer}`).join('\n');
const remodelLabel = (c: FieldAdjustment) => {
  const parts = [c.added.length ? `新纳入 ${c.added.join('、')}` : '', c.removed.length ? `取消 ${c.removed.join('、')}` : ''].filter(Boolean);
  return `我调整了 ${c.table} 的字段，请按新字段重新建模这张表${parts.length ? `（${parts.join('；')}）` : ''}`;
};

/** A request waiting for the assistant; requests run one after another in the conversation. */
type Request = { id: number; text: string; purpose?: TurnPurpose; documents?: string[]; label?: string };

export default function SemanticModelingPage() {
  const { groupId = '' } = useParams();
  const navigate = useNavigate();
  const [params, setParams] = useSearchParams();
  const requested = params.get('asset') || 'modeling';
  const tab: Tab = tabs.some(t => t.key === requested) ? requested as Tab
    : Object.prototype.hasOwnProperty.call(assetLabels, requested) ? 'assets' : 'modeling';
  const asset: Asset = Object.prototype.hasOwnProperty.call(assetLabels, requested) ? requested as Asset
    : Object.prototype.hasOwnProperty.call(assetLabels, params.get('kind') || '') ? params.get('kind') as Asset : 'schema';
  const [name, setName] = useState('语义模型');
  const [view, setView] = useState<MdlView | null>(null);
  const [preview, setPreview] = useState<MdlPreview | null>(null);
  const [workflow, setWorkflow] = useState<ModelingWorkflow | null>(null);
  const [confirmations, setConfirmations] = useState<Confirmation[]>([]);
  const [error, setError] = useState('');
  const [queue, setQueue] = useState<{ groupId: string; items: Request[] }>({ groupId, items: [] });
  const pending = queue.groupId === groupId ? queue.items : [];
  const head = pending[0];
  const [assistantState, setAssistantState] = useState<AssistantState>('idle');
  const idle = assistantState === 'idle' && !head;
  const [readyGroup, setReadyGroup] = useState<string | null>(null);
  const windowReady = readyGroup === groupId;
  const updateWindowReady = useCallback((ready: boolean) => setReadyGroup(ready ? groupId : null), [groupId]);
  // spec092 §7: with ?start=1 the page offers documents first and starts only on the user's click.
  const [startOffer, setStartOffer] = useState<string | null>(null);
  // spec093: tables waiting for field screening; null until loaded.
  const [screening, setScreening] = useState<ScreeningStatus[] | null>(null);
  const screeningPending = !!screening?.some(t => !t.confirmed);
  const [adjusting, setAdjusting] = useState<string | null>(null);
  const [windowSignal, setWindowSignal] = useState(0);
  const startOffered = useRef<string | null>(null);
  // The ?start=1 intent is remembered: switching tabs rewrites the URL, and the 2026-10-22 test
  // lost automatic modeling that way (only the document update ran on a new knowledge base).
  const startIntent = useRef<string | null>(null);
  if (params.get('start') === '1' && startOffered.current !== groupId) startIntent.current = groupId;
  const confirmAnchor = useRef<HTMLDivElement>(null);
  // spec092 §7: 试问 opens only when the user asks for it.
  const [trialOpen, setTrialOpen] = useState(false);
  const [examples, setExamples] = useState(0);
  const [unconfirmedTrials, setUnconfirmedTrials] = useState(0);
  const [draftSummary, setDraftSummary] = useState<DraftSummary>({ changes: 0, examples: 0, publishing: false, publishedVersion: null, error: null });
  const [publishSignal, setPublishSignal] = useState(0);

  const activeGroup = useRef(groupId); activeGroup.current = groupId;
  const generation = useRef(0);
  const sequence = useRef(0);
  const selectTab = (key: Tab) => setParams({ asset: key }, { replace: true });
  /** {@code label} is what the conversation shows for a request the platform wrote. */
  const request = (text: string, purpose?: TurnPurpose, label?: string) => {
    if (!windowReady) { setError('请先完成事实表时间配置，再开始建模。'); return false; }
    setQueue(previous => ({ groupId, items: [...(previous.groupId === groupId ? previous.items : []), { id: ++sequence.current, text, purpose, label }] }));
    return true;
  };
  // Several quick document changes merge into one queued request instead of one turn each.
  const documentsChanged = (change: string) => {
    window.dispatchEvent(new CustomEvent('modeling:updated', { detail: { groupId } }));
    // Before modeling starts, the automatic modeling turn reads every document anyway.
    if (!windowReady || startOffer === groupId || (startIntent.current === groupId && startOffered.current !== groupId)) return;
    setQueue(previous => {
      const items = previous.groupId === groupId ? previous.items : [];
      const waiting = items.findIndex(r => r.purpose === 'document');
      if (waiting < 0) {
        const documents = [change];
        return { groupId, items: [...items, { id: ++sequence.current, text: documentRequest(documents), purpose: 'document', documents, label: documentLabel(documents) }] };
      }
      const documents = [...(items[waiting].documents ?? []), change];
      return { groupId, items: items.map((r, i) => i === waiting ? { ...r, text: documentRequest(documents), documents, label: documentLabel(documents) } : r) };
    });
  };
  const answered = async (answers: ConfirmationAnswer[]) => {
    if (!answers.length) return;
    try {
      const decisions = await resolveConfirmations(groupId, answers);
      if (activeGroup.current !== groupId) return;
      if (decisions.length) request(confirmRequest(decisions), 'confirm', confirmLabel(decisions));
      await refresh();
    } catch (e) { if (activeGroup.current === groupId) setError(message(e)); }
  };
  const refresh = useCallback(async () => {
    const current = ++generation.current;
    const results = await Promise.allSettled([getMdlView(groupId), getMdlPreview(groupId), getModelingWorkflow(groupId), listConfirmations(groupId)]);
    void getScreeningStatus(groupId).then(list => { if (activeGroup.current === groupId) setScreening(list); }).catch(() => undefined);
    if (current !== generation.current || activeGroup.current !== groupId) return;
    if (results[0].status === 'fulfilled') setView(results[0].value);
    if (results[1].status === 'fulfilled') setPreview(results[1].value);
    if (results[2].status === 'fulfilled') setWorkflow(results[2].value);
    if (results[3].status === 'fulfilled') setConfirmations(results[3].value);
    const failed = results.find(r => r.status === 'rejected');
    setError(failed?.status === 'rejected' ? message(failed.reason) : '');
  }, [groupId]);
  useEffect(() => {
    setReadyGroup(null); setStartOffer(null); startOffered.current = null;
    if (startIntent.current !== groupId) startIntent.current = null; setTrialOpen(false); setExamples(0); setUnconfirmedTrials(0);
    setView(null); setPreview(null); setWorkflow(null); setName('语义模型'); setConfirmations([]); setScreening(null); setAdjusting(null);
    let cancelled = false;
    void getGroupDetail(groupId).then(detail => { if (!cancelled) setName(detail.group.name); })
      .catch(e => { if (!cancelled) setError(message(e)); });
    void refresh();
    const update = (event: Event) => {
      if ((event as CustomEvent<{ groupId: string }>).detail?.groupId === groupId) void refresh();
    };
    window.addEventListener('modeling:updated', update);
    return () => { cancelled = true; generation.current++; window.removeEventListener('modeling:updated', update); };
  }, [groupId, refresh]);
  useEffect(() => {
    if (!windowReady || startIntent.current !== groupId || startOffered.current === groupId) return;
    startOffered.current = groupId;
    setStartOffer(groupId);
  }, [windowReady, groupId, params]);
  const startModeling = () => {
    setStartOffer(null);
    setParams({ asset: 'modeling' }, { replace: true });
    request(AUTO_MODELING_REQUEST, 'auto', '开始建模');
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
  const waiting = pending.length;
  const assistantLabel = assistantState !== 'idle' ? (waiting ? `助手运行中 · ${waiting} 个请求排队` : '助手运行中')
    : waiting ? '请求排队中' : '助手空闲';
  const toQuery = workflow?.queryAvailable ? () => navigate(`/chat?groups=${encodeURIComponent(groupId)}`) : undefined;
  const preparing = useQueryReadiness(groupId, workflow?.publishedVersion);
  // Any turn may rewrite drafts or trial questions; every panel listening for updates reloads.
  const turnDone = () => {
    window.dispatchEvent(new CustomEvent('modeling:updated', { detail: { groupId } }));
  };
  const locateConfirmations = () => {
    selectTab('modeling');
    setTimeout(() => confirmAnchor.current?.scrollIntoView({ behavior: 'smooth', block: 'center' }), 50);
  };

  return <div className="modeling-workbench mw">
    <header className="mw-head">
      <div className="mw-crumb">
        <button type="button" className="mw-crumb-link" onClick={() => navigate('/configure/modeling')}>语义建模</button>
        <span className="mw-crumb-sep">/</span>
        <h1 className="mw-title">{name}</h1>
        <span className={`mw-pill ${workflow?.queryAvailable ? 'mw-pill-ok' : ''}`}>{workflow?.queryAvailable ? `线上 v${workflow.publishedVersion}` : '尚未发布'}</span>
        <span className={`mw-pill ${idle ? '' : 'mw-pill-live'}`}><span className="mw-dot" />{assistantLabel}</span>
      </div>
      <div className="mw-actions">
        {!screeningPending && screening !== null && <button className="da-btn da-btn-sm" onClick={() => setWindowSignal(n => n + 1)}>事实表时间范围</button>}
        <button className="da-btn da-btn-sm" onClick={() => navigate(`/configure/datasets/${groupId}`)}>知识库</button>
        {tab === 'modeling' && windowReady && <button className={`da-btn da-btn-sm ${trialOpen ? 'da-btn-primary' : ''}`} onClick={() => setTrialOpen(!trialOpen)}>
          试问{examples ? ` · ${examples}` : ''}</button>}
        {toQuery && <button className="da-btn da-btn-primary da-btn-sm" disabled={!!preparing} title={preparing ? `问数准备中（${preparing}），约半分钟后可用` : undefined}
          onClick={toQuery}>{preparing ? '问数准备中…' : '去问数 →'}</button>}
      </div>
    </header>
    <nav aria-label="建模页面" className="mw-tabs">
      {tabs.map(item => <button key={item.key} type="button" className={`mw-tab ${tab === item.key ? 'mw-tab-on' : ''}`} onClick={() => selectTab(item.key)}>{item.label}</button>)}
    </nav>
    <div className="mw-content">
    {error && <div role="alert" className="da-alert da-alert-error">{error}<button className="da-btn" onClick={() => void refresh()}>刷新</button></div>}
    {(screeningPending || !windowReady || startOffer === groupId) && screening !== null && <SetupSteps steps={[
      { label: '采样', done: true },
      { label: '字段整理', done: !screeningPending },
      { label: '时间范围', done: !screeningPending && windowReady },
      { label: '业务文档', done: !screeningPending && windowReady && startOffer !== groupId },
      { label: '开始建模', done: false },
    ]} />}
    {screening !== null && !screeningPending && <FactQueryWindowSettings key={groupId} groupId={groupId} automatic onReady={updateWindowReady} hideButton openSignal={windowSignal} />}
    {screeningPending && <div style={{ flex: 1, minHeight: 0, display: 'flex' }}>
      <FieldScreeningPanel groupId={groupId} mode="setup" onDone={() => void refresh()} />
    </div>}
    {!screeningPending && screening !== null && !windowReady && <div className="mw-notice">正在确认事实表的查询时间范围，完成后可以开始建模。关闭弹窗后，可从右上角"事实表时间范围"重新配置。</div>}
    {adjusting && <div className="da-modal-overlay" onClick={() => setAdjusting(null)}>
      <div className="da-modal-shell" style={{ width: 'min(1100px, 96vw)', height: '82vh', display: 'flex' }} onClick={e => e.stopPropagation()}>
        <FieldScreeningPanel groupId={groupId} mode="adjust" datasetId={adjusting} onClose={() => setAdjusting(null)}
          onAdjusted={(change, remodel) => {
            setAdjusting(null);
            void refresh();
            if (remodel) { request(remodelRequest(change), 'auto', remodelLabel(change)); selectTab('modeling'); }
          }} />
      </div>
    </div>}
    {startOffer === groupId && <div className="da-card" style={{ border: '1px solid var(--da-primary)', display: 'flex', flexDirection: 'column', gap: 10 }}>
      <strong>开始建模前，可以先上传业务文档</strong>
      <span className="da-small">指标口径、过滤条件、术语说明等文档能让助手写出更准的业务语义；没有也可以直接开始，之后随时在"资料"中补充。</span>
      <BusinessDocumentsPanel groupId={groupId} onChanged={documentsChanged} />
      <div style={{ display: 'flex', gap: 8 }}>
        <button className="da-btn da-btn-primary" onClick={startModeling}>开始自动建模</button>
        <button className="da-btn" onClick={startModeling}>没有文档，直接开始</button>
      </div>
    </div>}
    <main className="modeling-workbench-body" style={{ flex: 1, minHeight: 0, display: screeningPending ? 'none' : 'flex', gap: 16 }}>
      {tab === 'overview' && <section style={{ flex: 1, overflowY: 'auto', display: 'flex', flexDirection: 'column', gap: 12 }}>
        <div className="da-card"><h3 style={{ marginTop: 0 }}>数据</h3>
          <p className="da-small">在知识库页上传 CSV / Excel 或关联外部表；采样后回到这里开始建模，新加的表只处理新表，修改进入草稿。</p>
          <button className="da-btn" onClick={() => navigate(`/configure/datasets/${groupId}`)}>上传数据 / 关联表</button>
          {!!screening?.length && <div style={{ marginTop: 12 }}>
            {screening.map(t => <div key={t.datasetId} style={{ display: 'flex', alignItems: 'center', gap: 8, padding: '6px 0', borderTop: '1px solid var(--da-border)' }}>
              <code style={{ flex: 1 }}>{t.name}</code>
              <span className="da-small">纳入 {t.included}/{t.total} 个字段</span>
              <button className="da-btn da-btn-sm" disabled={!idle} title={idle ? '' : '助手运行中，完成后可调整'} onClick={() => setAdjusting(t.datasetId)}>字段</button>
            </div>)}
            <div className="da-small" style={{ marginTop: 6 }}>调整字段后会进入草稿，并让助手只重新建模这张表。</div>
          </div>}
        </div>
        {startOffer !== groupId && <BusinessDocumentsPanel groupId={groupId} onChanged={documentsChanged} />}
      </section>}
      <section aria-label="建模对话工作区" style={{ display: tab === 'modeling' ? 'flex' : 'none', flex: 1, minWidth: 0, minHeight: 0, flexDirection: 'column', gap: 8 }}>
        <div className="modeling-chat-row" style={{ flex: 1, minHeight: 0, display: 'flex', gap: 12 }}>
          <div className="modeling-chat-main" style={{ flex: 1, minWidth: 0, minHeight: 0, display: 'flex' }}>{windowReady ? <ModelingChatPanel key={groupId} groupId={groupId} variant="dock"
            draftPrompt={head?.text} draftPromptId={head?.id} submitDraft={!!head && startOffer !== groupId}
            onAttachDocument={() => selectTab('overview')} onClose={() => undefined}
            onDraftSubmitted={() => {
              setQueue(previous => ({ groupId, items: (previous.groupId === groupId ? previous.items : []).slice(1) }));
            }}
            onTurnDone={turnDone} draftPurpose={head?.purpose} draftLabel={head?.label}
            onActivityChange={setAssistantState}
            renderTurnResult={since => <TurnResultCard groupId={groupId} since={since} disabled={!idle} />}
            // Before modeling starts there is nothing to try or publish yet.
            footer={!idle || startOffer === groupId ? null : confirmations.length > 0 ? <div ref={confirmAnchor}>
              <ConfirmationCards key={confirmations.map(c => c.id).join(',')} questions={confirmations} onSubmit={answers => void answered(answers)} />
            </div> : <NextStepCard groupId={groupId} summary={draftSummary} unconfirmedTrials={unconfirmedTrials} nextVersion={(workflow?.publishedVersion ?? 0) + 1}
              onTrial={() => setTrialOpen(true)} onPublish={() => setPublishSignal(n => n + 1)} onQuery={toQuery} />} /> : <div className="da-card">事实表时间配置完成后，建模助手会在此继续。</div>}</div>
          {windowReady && <div className="modeling-trial-column" style={{ flex: '0 0 380px', minHeight: 0, display: trialOpen ? 'flex' : 'none' }}>
            <TrialPanel groupId={groupId} assistantIdle={idle} open={trialOpen} onClose={() => setTrialOpen(false)} onExamples={(count, unconfirmed) => { setExamples(count); setUnconfirmedTrials(unconfirmed); }}
              onAsk={text => request(text, 'question')} onWrong={text => request(text, 'question')} />
          </div>}
        </div>
        <DraftBar groupId={groupId} workflow={workflow} assistantActive={!idle} pendingConfirmations={confirmations.length}
          onLocate={locateConfirmations} onPublished={() => void refresh()}
          publishSignal={publishSignal} onSummary={setDraftSummary} />
      </section>
      {tab === 'assets' && <section style={{ flex: 1, overflowY: 'auto' }}>
        <p>当前草稿只读展示；问数使用已发布版本。修改请到"建模对话"。</p>
        <select className="da-input" aria-label="语义资产类型" value={asset} onChange={e => setParams({ asset: 'assets', kind: e.target.value }, { replace: true })}>
          {Object.entries(assetLabels).map(([key, label]) => <option key={key} value={key}>{label}</option>)}
        </select>
        {asset === 'sql' ? <SqlQaExamplesPanel groupId={groupId} /> : asset === 'mdl' ? <><MdlGraphView groupId={groupId} /><ModelSnapshotPanel groupId={groupId} /></>
          : <><AssetYamlBrowser groupId={groupId} entries={entries} selectedKey={selected}
            onSelect={key => setParams({ asset: 'assets', kind: asset, selected: key }, { replace: true })} emptyText="当前草稿暂无此类资产" />
            {asset === 'schema' && <MdlGraphView groupId={groupId} />}</>}
      </section>}
    </main>
    </div>
  </div>;
}

/** spec093: where the knowledge base is on its way to the first modeling turn. */
function SetupSteps({ steps }: { steps: { label: string; done: boolean }[] }) {
  const current = steps.findIndex(s => !s.done);
  return <div style={{ display: 'flex', alignItems: 'center', gap: 6, flexWrap: 'wrap', fontSize: 13 }} aria-label="建模准备步骤">
    {steps.map((s, i) => <span key={s.label} style={{ display: 'inline-flex', alignItems: 'center', gap: 6,
      color: s.done ? 'var(--da-success)' : i === current ? 'var(--da-primary)' : 'var(--da-text-muted)', fontWeight: i === current ? 600 : 400 }}>
      {i > 0 && <span style={{ color: 'var(--da-border)' }}>—</span>}
      {s.done ? '✓' : `${i + 1}`} {s.label}
    </span>)}
  </div>;
}
