import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Link, useParams, useSearchParams } from 'react-router-dom';
import { Dashboard, DashboardGeneration, checkDashboard, getDashboardGeneration, startDashboardGeneration, stopDashboardGeneration, restoreDashboard, getDashboard, publishDashboard } from '../api/dashboards';
import { listGroups } from '../api/datasets';
import { currentSession } from '../api/chat';
import { turns } from '../api/sessions';
import { DashboardMessage, dashboardErrorText, dashboardMessages, dashboardPublishHint, dashboardReplyParts, dashboardStatus, fileCheckLabel } from '../components/dashboardState';
import { ShellNavigationToggle } from '../components/ShellNavigation';
import DashboardLibrary from '../components/DashboardLibrary';
import DashboardMetadataDialog from '../components/DashboardMetadataDialog';
import { canAutomaticallyRepair, dashboardPrompt, dashboardUserText, generationActive, generationMessages, previewSummary, PreviewReport, PanelReference } from '../components/dashboardRuntime';
import DataSourceDialog from '../components/DataSourceDialog';
import DashboardPermissions from '../components/DashboardPermissions';
import { DashboardAssistantMark, DashboardMoreMenu, DashboardConversationStarters, DashboardPromptPicker } from '../components/DashboardWorkbenchUi';
import Icon from '../components/Icon';
import Markdown from '../components/Markdown';
import './dashboards.css';

const AGENT = 'dashboard-agent';
const DashboardPreview = React.lazy(() => import('../components/DashboardPreview'));
const errorText = dashboardErrorText;
const MAX_COMPOSER_LENGTH = 12000;

function AssistantMessage({ text }: { text: string }) {
  const parts = dashboardReplyParts(text);
  return <><Markdown>{parts.main}</Markdown>{parts.details && <details className="dashboard-response-details"><summary>查看完整回复与生成说明</summary><Markdown>{parts.details}</Markdown></details>}</>;
}

function DashboardWorkbench({ id }: { id: string }) {
  const [search, setSearch] = useSearchParams();
  const published = search.get('view') === 'published';
  const [dashboard, setDashboard] = useState<Dashboard | null>(null);
  const [groupName, setGroupName] = useState('');
  const [messages, setMessages] = useState<DashboardMessage[]>([]);
  const [input, setInput] = useState('');
  const pristineTemplate = useRef<string | null>(null);
  const [promptPicker, setPromptPicker] = useState<{ pending?: string } | null>(null);
  const dismissPromptPicker = useCallback(() => setPromptPicker(null), []);
  const [permissionsOpen, setPermissionsOpen] = useState(false);
  const [metadataOpen, setMetadataOpen] = useState(false);
  const [infoOpen, setInfoOpen] = useState(false);
  const [busy, setBusy] = useState(false);
  const [restoring, setRestoring] = useState(true);
  const [publishing, setPublishing] = useState(false);
  const [confirming, setConfirming] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [activity, setActivity] = useState('');
  const [previewKey, setPreviewKey] = useState(0);
  const [collapsed, setCollapsed] = useState(false);
  const [expanded, setExpanded] = useState(false);
  const [report, setReport] = useState<PreviewReport | null>(null);
  const [selected, setSelected] = useState<(PanelReference & { revision: string }) | null>(null);
  const [checkpoint, setCheckpoint] = useState<string | null>(null);
  const [undoing, setUndoing] = useState(false);
  const [phase, setPhase] = useState('等待你的需求');
  const [generation, setGeneration] = useState<DashboardGeneration | null>(null);
  const [generationLoading, setGenerationLoading] = useState(true);
  const [pollError, setPollError] = useState('');
  const [busyElsewhere, setBusyElsewhere] = useState(false);
  const [checkingFile, setCheckingFile] = useState(false);
  const [checkingManually, setCheckingManually] = useState(false);
  const [syncingDraft, setSyncingDraft] = useState(false);
  const [syncError, setSyncError] = useState('');
  const [stopping, setStopping] = useState(false);
  const generationRef = useRef<DashboardGeneration | null>(null);
  const completed = useRef(new Set<string>());
  const initiated = useRef(new Set<string>());
  const pendingSubmission = useRef<{ key: string; id: string } | null>(null);
  const [chatWidth, setChatWidth] = useState(380);
  const [showLatest, setShowLatest] = useState(false);
  const conversation = useRef<HTMLElement>(null);
  const composerInput = useRef<HTMLTextAreaElement>(null);
  const dashboardRef = useRef(dashboard); dashboardRef.current = dashboard;
  const automatic = useRef<{ revision: string; used: boolean; runId: string } | null>(null);
  const [mobilePanel, setMobilePanel] = useState<'chat' | 'preview'>('preview');
  const active = useRef<AbortController>();
  const mounted = useRef(true);
  const messageList = useRef<HTMLDivElement>(null);
  const followMessages = useRef(true);
  const reloadSeq = useRef(0);
  const reload = useCallback(async () => {
    const seq = ++reloadSeq.current;
    const next = await getDashboard(id, !published); if (mounted.current && seq === reloadSeq.current) setDashboard(next); return next;
  }, [id, published]);
  useEffect(() => {
    let disposed = false; setLoading(true); setError('');
    getDashboard(id, !published).then(next => { if (!disposed) { setDashboard(next); if (!next.html && !published) setMobilePanel('chat'); } })
      .catch(e => { if (!disposed) setError(errorText(e)); }).finally(() => { if (!disposed) setLoading(false); });
    return () => { disposed = true; };
  }, [id, published]);
  useEffect(() => {
    if (!dashboard) return;
    if (published || !dashboard?.canEdit) { setRestoring(false); return; }
    let disposed = false; setRestoring(true);
    currentSession(AGENT, id).then(session => session.exists ? turns(AGENT, id) : []).then(history => {
      if (!disposed) setMessages(dashboardMessages(history));
    }).catch(e => { if (!disposed) setError(`读取对话失败：${errorText(e)}`); })
      .finally(() => { if (!disposed) setRestoring(false); });
    return () => { disposed = true; };
  }, [id, published, dashboard?.canEdit]);
  useEffect(() => {
    let disposed = false;
    if (dashboard?.groupId) listGroups().then(groups => {
      if (!disposed) setGroupName(groups.find(group => group.id === dashboard.groupId)?.name || '当前知识库');
    }).catch(() => { if (!disposed) setGroupName('当前知识库'); });
    return () => { disposed = true; };
  }, [dashboard?.groupId]);
  useEffect(() => { mounted.current = true; return () => { mounted.current = false; active.current?.abort(); }; }, []);
  useEffect(() => { const node = messageList.current; if (node && followMessages.current) node.scrollTop = node.scrollHeight; }, [messages, activity, generation?.answer]);
  useEffect(() => {
    if (!expanded) return;
    const escape = (e: KeyboardEvent) => { if (e.key === 'Escape') setExpanded(false); };
    document.addEventListener('keydown', escape); return () => document.removeEventListener('keydown', escape);
  }, [expanded]);

  const acceptReport = useCallback((next: PreviewReport) => { if (next.revision === dashboardRef.current?.revision) setReport(next); }, []);
  const acceptSelection = useCallback((panel: PanelReference, revision: string) => {
    if (revision !== dashboardRef.current?.revision) return;
    setSelected({ ...panel, revision }); setMobilePanel('chat'); setCollapsed(false); setExpanded(false);
    requestAnimationFrame(() => composerInput.current?.focus());
  }, []);
  const acceptGeneration = useCallback((next: DashboardGeneration | null) => {
    const previous = generationRef.current;
    if (!next) { if (!previous && !active.current) { setBusy(false); setGeneration(null); } return; }
    if (previous && Date.parse(previous.startedAt) > Date.parse(next.startedAt)) return;
    if (previous?.id === next.id && ((!generationActive(previous) && generationActive(next)) || (previous.status === 'CHECKING' && ['QUEUED', 'RUNNING'].includes(next.status)))) return;
    generationRef.current = next; setGeneration(next); setCheckpoint(next.checkpointRevision);
    if (generationActive(next)) {
      if (next.status === 'CHECKING' && previous?.status !== 'CHECKING') void reload().catch(e => { if (mounted.current) setError(errorText(e)); });
      if (!previous && !initiated.current.has(next.id)) setNotice('已恢复正在执行的生成任务。离开页面不会停止任务。');
      setBusy(true); setPhase(next.phase); setActivity(next.phase); return;
    }
    if (!active.current) { setBusy(false); setPhase(next.phase); setActivity(''); }
    if (completed.current.has(next.id)) return;
    completed.current.add(next.id);
    setNotice('');
    setSyncingDraft(true);
    if (initiated.current.has(next.id) && next.status === 'SUCCEEDED' && next.autoAttempt === 0 && next.resultRevision && next.resultRevision !== next.baseRevision) automatic.current = { revision: next.resultRevision, used: false, runId: next.id };
    void reload().then(() => {
      if (mounted.current && generationRef.current?.id === next.id) { setSyncingDraft(false); setSyncError(''); }
    }).catch(e => {
      if (mounted.current && generationRef.current?.id === next.id) {
        completed.current.delete(next.id);
        setSyncError(`读取最新草稿失败，将重新读取：${errorText(e)}`);
      }
    });
    void currentSession(AGENT, id).then(session => session.exists ? turns(AGENT, id) : []).then(history => {
      if (!mounted.current) return;
      if (generationRef.current?.id === next.id) setMessages(dashboardMessages(history));
    }).catch(e => { if (mounted.current) setError(`读取对话失败：${errorText(e)}`); });
  }, [id, reload]);
  const taskActive = generationActive(generation);
  const checking = checkingFile || checkingManually || dashboard?.fileCheck?.status === 'CHECKING' || generation?.status === 'CHECKING';
  const operationPending = checking || busyElsewhere || syncingDraft || publishing || undoing || generationLoading || !!pollError;
  const composerDisabled = busy || restoring || operationPending || !dashboard?.canEdit;
  useEffect(() => {
    setPromptPicker(null);
  }, [composerDisabled, dashboard?.revision, selected?.id]);
  useEffect(() => {
    if (!dashboard?.canEdit || published) return;
    let disposed = false; let timer: ReturnType<typeof setTimeout>;
    const controller = new AbortController();
    const poll = async () => {
      let retry = false;
      try {
        const { task: next, busyElsewhere: otherEditor, checkingFile: fileInProgress } = await getDashboardGeneration(id, controller.signal);
        if (!disposed) {
          acceptGeneration(next); setPollError('');
          if ((busyElsewhere && !otherEditor) || (checkingFile && !fileInProgress)
              || (dashboardRef.current?.fileCheck?.status === 'CHECKING' && !fileInProgress && !generationActive(next))) await reload();
          if (disposed) return;
          setBusyElsewhere(!!otherEditor);
          setCheckingFile(!!fileInProgress);
        }
      } catch (e) { if (!disposed) { retry = true; setPollError(`正在重新连接工作台：${errorText(e)}`); } }
      finally { if (!disposed) { setGenerationLoading(false); timer = setTimeout(() => void poll(), retry || busyElsewhere || checkingFile || generationActive(generationRef.current) || dashboardRef.current?.fileCheck?.status === 'CHECKING' ? 1200 : 15000); } }
    };
    void poll();
    return () => { disposed = true; controller.abort(); clearTimeout(timer); };
  }, [id, published, dashboard?.canEdit, acceptGeneration, taskActive, busyElsewhere, checkingFile, dashboard?.fileCheck?.status === 'CHECKING', reload]);
  const send = async (textOverride?: string, auto = false, diagnostics?: PreviewReport) => {
    const text = (textOverride ?? input).trim();
    if (!text || active.current || busy || restoring || operationPending || !dashboard?.canEdit || published) return;
    const relevant = diagnostics || (report?.revision === dashboard.revision ? report : null);
    const target = selected?.revision === dashboard.revision ? selected : null;
    const request = dashboardPrompt(text, relevant, target);
    const parent = auto ? automatic.current?.runId : undefined;
    const key = `${dashboard.revision}:${parent || ''}:${request}`;
    const requestId = pendingSubmission.current?.key === key ? pendingSubmission.current.id : crypto.randomUUID();
    pendingSubmission.current = { key, id: requestId };
    const controller = new AbortController(); active.current = controller;
    if (!auto) { automatic.current = null; pristineTemplate.current = null; setInput(''); followMessages.current = true; setShowLatest(false); }
    setBusy(true); setPhase('正在提交生成任务'); setActivity('正在提交生成任务…'); setError(''); setNotice('');
    try {
      const next = await startDashboardGeneration(id, requestId, dashboard.revision, request, parent);
      if (!mounted.current) return;
      initiated.current.add(next.id); pendingSubmission.current = null;
      setMessages(previous => [...previous, { id: `generation-user-${next.id}`, role: 'user', text: request }]);
      acceptGeneration(next);
    } catch (e) { if (mounted.current) { setError(errorText(e)); if (!auto) setInput(text); } }
    finally {
      active.current = undefined;
      if (mounted.current) { setBusy(generationActive(generationRef.current)); if (!generationActive(generationRef.current)) setActivity(''); }
    }
  };
  const sendRef = useRef(send); sendRef.current = send;
  useEffect(() => {
    const pending = automatic.current;
    if (!pending || pending.used || busy || restoring || operationPending || published || !dashboard?.canEdit || !dashboard.verified || pending.revision !== dashboard.revision || report?.revision !== pending.revision || !report.settled) return;
    pending.used = true;
    if (!canAutomaticallyRepair(report)) return;
    void sendRef.current('【自动修复一次】请根据运行检查仅修复失败的面板和相关查询，优先局部修改，保留其他内容及统计口径，完成文件检查。', true, report);
  }, [report, busy, restoring, operationPending, published, dashboard?.revision, dashboard?.canEdit, dashboard?.verified]);
  const repair = (next: PreviewReport) => { void send('请根据运行检查修复失败项，优先局部修改，保留其他图表和统计口径，完成文件检查。', false, next); };
  const stop = async () => {
    const task = generationRef.current; if (!task || !generationActive(task) || stopping) return;
    automatic.current = null; setStopping(true);
    try { const next = await stopDashboardGeneration(id, task.id); acceptGeneration(next); if (next.status === 'CANCELLED') setNotice('已停止本轮生成。'); }
    catch (e) { setError(errorText(e)); } finally { setStopping(false); }
  };
  const undo = async () => {
    if (!dashboard || !checkpoint || busy || operationPending || published || dashboard.revision !== generation?.resultRevision) return;
    automatic.current = null; setUndoing(true); setError('');
    try { const next = await restoreDashboard(dashboard, checkpoint); setDashboard(next); setCheckpoint(null); setReport(null); setSelected(null); setNotice('已恢复本轮修改前的页面，聊天记录保留。'); }
    catch (e) { setError(errorText(e)); }
    finally { setUndoing(false); }
  };
  const sizeChat = (width: number) => setChatWidth(Math.max(300, Math.min(560, width)));

  const check = async () => {
    if (!dashboard?.html || !dashboard.canEdit || published || busy || operationPending) return;
    setCheckingManually(true); setError(''); setNotice('');
    try { await checkDashboard(dashboard); }
    catch (e) { if (mounted.current) setError(errorText(e)); }
    finally {
      try { await reload(); } catch (e) { if (mounted.current) setError(errorText(e)); }
      if (mounted.current) setCheckingManually(false);
    }
  };
  const currentReport = report && report.revision === dashboard?.revision ? report : null;
  const publishHint = !dashboard ? '' : busyElsewhere ? '另一位编辑者正在更新草稿' : checking ? '正在检查草稿文件' : undoing ? '正在撤销修改'
    : syncingDraft || generationLoading ? '正在读取草稿状态' : pollError ? '正在重新连接工作台'
    : dashboardPublishHint(dashboard, busy, currentReport);
  const publish = async () => {
    if (!dashboard || publishHint || publishing || !confirming || confirming !== dashboard.revision) return;
    setPublishing(true); setError(''); setNotice('');
    try { setDashboard(await publishDashboard(id, confirming)); setConfirming(null); setNotice('已发布。有此知识库查询权限的成员现在可以查看。'); }
    catch (e) { setConfirming(null); setError(errorText(e)); await reload().catch(err => setError(errorText(err))); }
    finally { setPublishing(false); }
  };
  useEffect(() => {
    if (confirming && !publishing && dashboard?.revision !== confirming) {
      setConfirming(null); setNotice('草稿版本已变化，请查看最新预览后再发布。');
    }
  }, [confirming, publishing, dashboard?.revision]);
  const visibleMessages = useMemo(() => generationMessages(messages, generation), [messages, generation]);
  const setPromptDraft = (draft: string, fromTemplate: boolean) => {
    pristineTemplate.current = fromTemplate ? draft : null;
    setInput(draft);
    dismissPromptPicker();
    composerInput.current?.focus();
  };
  const pickPrompt = (text: string) => {
    if (input.trim() && input !== pristineTemplate.current) setPromptPicker({ pending: text });
    else setPromptDraft(text, true);
  };
  const pendingPrompt = promptPicker?.pending;
  const appendedPrompt = pendingPrompt ? `${input}\n\n${pendingPrompt}` : null;
  const canAppendPrompt = appendedPrompt !== null && appendedPrompt.length <= MAX_COMPOSER_LENGTH;
  if (loading) return <div className="dashboard-loading" role="status">正在打开看板…</div>;
  if (!dashboard) return <div className="dashboard-loading"><p role="alert">{error || '看板不存在'}</p><Link to="/dashboards">返回仪表盘</Link></div>;
  const status = published ? { kind: 'published', label: '已发布' } : dashboardStatus(dashboard);
  const checkLabel = fileCheckLabel(dashboard, checking);
  const latestOutcome = generation && (['FAILED', 'CANCELLED', 'INTERRUPTED'].includes(generation.status)
    || (generation.status === 'CHECK_FAILED' && generation.resultRevision === dashboard.revision && !dashboard.verified && !dashboard.fileCheck?.error && !checking)) ? generation : null;
  const progress = busyElsewhere ? '其他人编辑中' : checking ? checkLabel : busy ? phase : syncingDraft || restoring || generationLoading ? '恢复工作台' : pollError ? '正在重新连接'
    : undoing ? '正在撤销' : publishing ? '正在发布' : latestOutcome ? latestOutcome.phase : dashboard.html ? (dashboard.verified ? '可继续编辑' : checkLabel) : '等待需求';
  const needsAttention = dashboard.fileCheck?.status === 'FAILED' || dashboard.fileCheck?.status === 'MODEL_CHANGED' || !!currentReport?.issues.length;
  const canCheck = !!dashboard.html && dashboard.canEdit && !dashboard.verified && dashboard.fileCheck?.status !== 'MODEL_CHANGED';
  const saving = busy ? activity || '正在生成…' : status.kind === 'published' && !published ? '草稿与已发布版一致'
    : published ? '成员查看页面 · 沿用知识库访问权限' : dashboard.verified ? '草稿已保存 · 修改不会影响已发布版' : '修改仅影响草稿 · 完成文件检查和预览后可发布';
  return <div style={{ '--dashboard-chat-width': `${chatWidth}px` } as React.CSSProperties} className={`dashboard-workbench${published ? ' viewing' : ''}${collapsed ? ' conversation-collapsed' : ''}${expanded ? ' preview-expanded' : ''} mobile-${mobilePanel}`}>
    <header className="dashboard-workbench-header dashboard-compact-header"><div className="dashboard-header-frame">
      <div className="dashboard-header-main"><ShellNavigationToggle />
        <nav className="dashboard-breadcrumb" aria-label="看板导航"><Link to="/dashboards" aria-label="返回仪表盘列表">仪表盘</Link><Icon name="chevron" size="sm" /><div className="dashboard-header-title"><div className="dashboard-title-line"><h1 title={dashboard.title} aria-current="page">{dashboard.title}</h1><span className={`dashboard-badge ${status.kind}`}>{status.label}</span></div></div></nav>
        <span className="dashboard-sr-only" role="status">{saving}</span>
      </div>
      <div className="dashboard-header-actions">
        {dashboard.publishedRevision && dashboard.canEdit && <button className={published ? 'dashboard-primary dashboard-edit-board' : 'dashboard-secondary'} disabled={busy || publishing || undoing} title={published ? '进入对话创作，修改先保存为草稿' : '查看成员当前可见的发布版本'} onClick={() => { setError(''); setNotice(''); setSearch(published ? {} : { view: 'published' }); }}><Icon name={published ? 'edit' : 'link'} size="sm" />{published ? '编辑看板' : '查看已发布版'}</button>}
        {!published && dashboard.canPublish && <span title={publishHint || '发布当前草稿'}><button className="dashboard-primary" disabled={Boolean(publishHint) || publishing} aria-describedby={publishHint ? 'dashboard-publish-hint' : undefined} onClick={() => setConfirming(dashboard.revision)}><Icon name="upload" size="sm" />{publishing ? '正在发布…' : dashboard.publishedRevision ? '发布更新' : '发布看板'}</button></span>}
        <DashboardMoreMenu canEdit={dashboard.canEdit && !dashboard.deletionPending} disabled={busy || publishing || undoing} onInfo={() => dashboard.canEdit && !dashboard.deletionPending ? setMetadataOpen(true) : setInfoOpen(true)} onPermissions={() => setPermissionsOpen(true)} versionAction={!published && dashboard.publishedRevision && dashboard.canEdit ? { label: '查看已发布版', onClick: () => { setError(''); setNotice(''); setSearch({ view: 'published' }); } } : undefined} />
      </div>
    </div></header>
    {!published && <div className="dashboard-mobile-panels dashboard-segment" aria-label="工作区切换"><button aria-pressed={mobilePanel === 'chat'} onClick={() => setMobilePanel('chat')}>对话</button><button aria-pressed={mobilePanel === 'preview'} onClick={() => setMobilePanel('preview')}>预览</button></div>}
    {infoOpen && <DataSourceDialog className="dashboard-dialog" title="看板信息" onClose={() => setInfoOpen(false)}><div className="dashboard-dialog-body dashboard-readonly-info"><dl><dt>看板名称</dt><dd>{dashboard.title}</dd><dt>描述</dt><dd>{dashboard.description?.trim() || '暂无描述'}</dd><dt>知识库</dt><dd>{groupName || '当前知识库'}</dd></dl></div><footer className="dashboard-dialog-foot"><button type="button" className="dashboard-secondary" onClick={() => setInfoOpen(false)}>关闭</button></footer></DataSourceDialog>}
    {metadataOpen && <DashboardMetadataDialog dashboard={dashboard} onClose={() => setMetadataOpen(false)} onSaved={next => { setDashboard(previous => previous ? { ...previous, title: next.title, description: next.description, createdAt: next.createdAt, updatedAt: next.updatedAt } : previous); setMetadataOpen(false); setNotice('基本信息已保存。页面内容和发布版本保持不变。'); }} />}
    {permissionsOpen && <DashboardPermissions dashboard={dashboard} name={groupName} onClose={() => setPermissionsOpen(false)} />}
    {!published && <div className={`dashboard-release-state${needsAttention ? ' needs-attention' : ''}`} role="status">
      <div><strong id="dashboard-publish-hint">{publishing ? '正在复核并发布' : busy && !checking ? phase : publishHint || '草稿可发布'}</strong>
        {dashboard.fileCheck?.status === 'FAILED' && !checking && !busy && <span>{dashboard.fileCheck.error}</span>}
      </div>
      {canCheck && !busy && !checking && dashboard.fileCheck?.status === 'FAILED' && <button type="button" disabled={operationPending} onClick={() => {
        const prompt = `请修复当前草稿的文件检查错误，保留其他面板和统计口径。\n检查结果：${dashboard.fileCheck?.error || '文件检查未通过'}`;
        pristineTemplate.current = null;
        setInput(value => value.includes(prompt) ? value : [value.trimEnd(), prompt].filter(Boolean).join('\n\n'));
        setCollapsed(false); setExpanded(false); setMobilePanel('chat');
        requestAnimationFrame(() => composerInput.current?.focus());
      }}>请助手修复</button>}
      {canCheck && !busy && !checking && <button type="button" disabled={operationPending} onClick={() => void check()}>{dashboard.fileCheck?.status === 'FAILED' ? '重新检查' : '检查草稿'}</button>}
    </div>}
    {(pollError || syncError) && !published && <div className="dashboard-notice error" role="status">{pollError || syncError}</div>}
    {error && <div className="dashboard-notice error" role="alert">{error}</div>}{notice && <div className="dashboard-notice success" role="status">{notice}</div>}
    <div className="dashboard-workbench-body">
      {!published && <section ref={conversation} className="dashboard-conversation" aria-label="看板对话"><header className="dashboard-conversation-heading"><DashboardAssistantMark /><h2>看板助手</h2><span className={`dashboard-assistant-progress${busy || checking || syncingDraft ? ' is-busy' : ''}`} role="status"><i aria-hidden="true" />{progress}</span></header><div className="dashboard-messages" ref={messageList} onScroll={event => { const node = event.currentTarget; followMessages.current = node.scrollHeight - node.clientHeight - node.scrollTop < 64; setShowLatest(!followMessages.current); }}>
        {!dashboard.html && !visibleMessages.length && !restoring && !generationLoading && <DashboardConversationStarters disabled={composerDisabled} onPick={pickPrompt} />}
        {latestOutcome && <div className="dashboard-generation-outcome" role="status"><strong>{latestOutcome.phase}</strong><p>{latestOutcome.error || '任务已停止，可查看当前草稿后继续修改。'}</p></div>}
        {restoring && <p className="dashboard-muted" role="status">正在恢复对话…</p>}
        {visibleMessages.filter(message => message.text.trim()).map(message => <article className={`dashboard-message ${message.role}`} key={message.id} aria-label={message.role === 'user' ? '用户消息' : '看板助手回复'}>{(message.role === 'assistant' || message.text.startsWith('【自动修复一次】')) && <div className="dashboard-message-label">{message.role === 'assistant' && <DashboardAssistantMark small />}<span>{message.role === 'assistant' ? '看板助手' : '自动修复 · 1次上限'}</span></div>}{message.role === 'assistant' ? <AssistantMessage text={message.text} /> : <><p>{dashboardUserText(message.text).replace('【自动修复一次】', '')}</p>{dashboardUserText(message.text) !== message.text && <small className="dashboard-diagnostic-attached">已附带修改目标与运行检查</small>}</>}</article>)}
        {!busy && !syncingDraft && checkpoint && generation?.resultRevision === dashboard.revision && checkpoint !== dashboard.revision && <section className={`dashboard-turn-result${needsAttention ? ' needs-attention' : ''}`} aria-label="本轮页面结果"><div><Icon name={needsAttention ? 'warn' : 'check'} size="sm" /><strong>草稿已保存</strong><span>{checkLabel}</span></div><p>{report?.revision === dashboard.revision ? previewSummary(report) : '正在准备预览自检'}</p><footer><span>{dashboard.revision === dashboard.publishedRevision ? '当前版本已发布' : '发布前仅影响草稿'}</span><button type="button" disabled={operationPending || restoring} title="只恢复页面文件，聊天记录保留" onClick={() => void undo()}>{undoing ? '正在恢复…' : '撤销本轮修改'}</button></footer></section>}
        {busy && <div className="dashboard-generation-state" role="status"><span className="dashboard-spinner" />{activity || '正在处理…'}</div>}
      </div>{showLatest && <button type="button" className="dashboard-jump-latest" onClick={() => { followMessages.current = true; setShowLatest(false); const node = messageList.current; if (node) node.scrollTop = node.scrollHeight; }}><Icon name="arrowDown" size="sm" />回到最新</button>}{selected?.revision === dashboard.revision && <div className="dashboard-target"><span>修改范围：{selected.title}</span><button type="button" aria-label="清除面板选择" onClick={() => setSelected(null)}><Icon name="close" size="sm" /></button></div>}
        <form className="dashboard-composer" onSubmit={event => { event.preventDefault(); void send(); }}>
          <textarea ref={composerInput} name="dashboardMessage" autoComplete="off" value={input}
            maxLength={MAX_COMPOSER_LENGTH} disabled={composerDisabled}
            placeholder={dashboard.html ? '例如：把趋势改为按月，再突出华东表现…' : '描述你想生成的仪表盘…'} aria-label="看板需求"
            onChange={event => { pristineTemplate.current = null; setInput(event.target.value); }}
            onKeyDown={event => {
              if (event.key === 'Enter' && !event.shiftKey && !event.nativeEvent.isComposing) { event.preventDefault(); void send(); }
            }} />
          <div>
            <DashboardPromptPicker
              scope={!dashboard.html ? 'create' : selected?.revision === dashboard.revision ? 'panel' : 'board'}
              disabled={composerDisabled} open={promptPicker !== null} pending={pendingPrompt} canAppend={canAppendPrompt}
              onToggle={() => setPromptPicker(current => current ? null : {})} onDismiss={dismissPromptPicker}
              onPick={pickPrompt} onApply={mode => {
                if (mode === 'replace' && pendingPrompt) setPromptDraft(pendingPrompt, true);
                if (mode === 'append' && canAppendPrompt && appendedPrompt !== null) setPromptDraft(appendedPrompt, false);
              }} />
            <span className="dashboard-composer-note">{dashboard.canPublish ? '仅修改草稿 · 发布由管理者确认' : '仅修改草稿 · 需知识库管理者发布'}</span>
            {busy ? <button type="button" className="dashboard-stop" disabled={stopping || checking || !generationActive(generation)} onClick={() => void stop()} aria-label="停止生成">{checking ? '检查中' : stopping ? '正在停止' : '停止'}</button>
              : <button type="submit" className="dashboard-send" disabled={!input.trim() || composerDisabled} aria-label="发送看板需求"><Icon name="arrowUp" size="sm" /></button>}
          </div>
        </form>
      </section>}
      {!published && !collapsed && !expanded && <div className="dashboard-chat-resizer" role="separator" tabIndex={0} aria-label="调整对话宽度" aria-orientation="vertical" aria-valuemin={300} aria-valuemax={560} aria-valuenow={chatWidth} onPointerDown={event => { event.currentTarget.setPointerCapture(event.pointerId); }} onPointerMove={event => { if (event.currentTarget.hasPointerCapture(event.pointerId) && conversation.current) sizeChat(event.clientX - conversation.current.getBoundingClientRect().left); }} onPointerUp={event => { if (event.currentTarget.hasPointerCapture(event.pointerId)) event.currentTarget.releasePointerCapture(event.pointerId); }} onKeyDown={event => { if (event.key === 'ArrowLeft' || event.key === 'ArrowRight') { event.preventDefault(); sizeChat(chatWidth + (event.key === 'ArrowRight' ? 20 : -20)); } }} />}
      <section className="dashboard-canvas" aria-label="看板预览"><div className="dashboard-canvas-toolbar"><span><Icon name="chart" size="sm" />{published ? '已发布看板' : '看板预览'}</span><div>{!published && dashboard.html && <span className={`dashboard-verify-status${dashboard.verified ? ' is-verified' : ''}`}>{busy && !checking ? '生成中…' : checkLabel}</span>}
        {!published && <button className="dashboard-icon-button" title={collapsed ? '展开对话' : '收起对话'} aria-label={collapsed ? '展开对话' : '收起对话'} aria-expanded={!collapsed} onClick={() => setCollapsed(value => !value)}><Icon name="list" size="sm" /></button>}
        <button className="dashboard-icon-button" title="刷新数据与预览" aria-label="刷新数据与预览" disabled={busy || publishing || undoing} onClick={() => { void reload().then(() => { setReport(null); setPreviewKey(n => n + 1); }).catch(e => setError(errorText(e))); }}><Icon name="refresh" size="sm" /></button>
        <button className="dashboard-icon-button" title={expanded ? '退出展开预览' : '展开预览'} aria-label={expanded ? '退出展开预览' : '展开预览'} aria-pressed={expanded} onClick={() => setExpanded(value => !value)}><Icon name="fit" size="sm" /></button>
      </div></div>
        {dashboard.html ? <React.Suspense fallback={<div className="dashboard-loading" role="status">正在准备图表预览…</div>}><DashboardPreview key={`${id}-${published}-${previewKey}`} dashboard={dashboard} draft={!published} onReport={acceptReport} onSelect={acceptSelection} onRepair={!published && dashboard.canEdit ? repair : undefined} repairDisabled={busy || restoring || operationPending} /></React.Suspense> : <div className="dashboard-canvas-empty"><span className="dashboard-feature-icon"><Icon name="chart" /></span><h2>你的业务全景，即将在这里呈现</h2><p>{busy ? '助手正在梳理指标和数据，生成后会自动更新。' : '在对话中描述想法，开始生成第一版看板。'}</p><span>按知识库权限查询 · 对话修改 · 管理者发布</span></div>}
      </section>
    </div>
    {confirming && <DataSourceDialog className="dashboard-dialog" title={dashboard.publishedRevision ? '发布这次更新？' : '发布这张看板？'} description="发布后，有权限的知识库成员将看到这个版本。" busy={publishing} onClose={() => setConfirming(null)}><div className="dashboard-dialog-body"><div className="dashboard-publish-summary"><strong>{dashboard.title}</strong><span>知识库：{groupName}</span><span>访问范围：沿用知识库查询权限</span><span>草稿修改在发布前不会影响当前版本</span></div></div><footer className="dashboard-dialog-foot"><button className="dashboard-secondary clear" disabled={publishing} onClick={() => setConfirming(null)}>继续编辑</button><button className="dashboard-primary" disabled={publishing || Boolean(publishHint) || confirming !== dashboard.revision} onClick={() => void publish()}>{publishing ? '正在检查并发布…' : '确认发布'}</button></footer></DataSourceDialog>}
  </div>;
}

export default function DashboardsPage() {
  const { dashboardId } = useParams();
  const [params] = useSearchParams();
  return dashboardId ? <DashboardWorkbench key={dashboardId + (params.get('view') === 'published' ? '/published' : '/draft')} id={dashboardId} /> : <DashboardLibrary />;
}
