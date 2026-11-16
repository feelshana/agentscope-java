import React, { useCallback, useEffect, useRef, useState } from 'react';
import { Link, useParams, useSearchParams } from 'react-router-dom';
import { Dashboard, getDashboard, publishDashboard } from '../api/dashboards';
import { listGroups } from '../api/datasets';
import { currentSession, stream } from '../api/chat';
import { turns } from '../api/sessions';
import { DashboardMessage, dashboardErrorText, dashboardMessages, dashboardPublishHint, dashboardReplyParts, dashboardStatus, dashboardToolProgress } from '../components/dashboardState';
import { ShellNavigationToggle } from '../components/ShellNavigation';
import DashboardLibrary from '../components/DashboardLibrary';
import DataSourceDialog from '../components/DataSourceDialog';
import DashboardPermissions from '../components/DashboardPermissions';
import Icon from '../components/Icon';
import Markdown from '../components/Markdown';
import './dashboards.css';

const AGENT = 'dashboard-agent';
const DashboardPreview = React.lazy(() => import('../components/DashboardPreview'));
const errorText = dashboardErrorText;

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
  const [permissionsOpen, setPermissionsOpen] = useState(false);
  const [busy, setBusy] = useState(false);
  const [restoring, setRestoring] = useState(true);
  const [publishing, setPublishing] = useState(false);
  const [confirming, setConfirming] = useState(false);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [activity, setActivity] = useState('');
  const [previewKey, setPreviewKey] = useState(0);
  const [collapsed, setCollapsed] = useState(false);
  const [expanded, setExpanded] = useState(false);
  const [mobilePanel, setMobilePanel] = useState<'chat' | 'preview'>('preview');
  const active = useRef<AbortController>();
  const mounted = useRef(true);
  const messageList = useRef<HTMLDivElement>(null);
  const reload = useCallback(async () => {
    const next = await getDashboard(id, !published); if (mounted.current) setDashboard(next); return next;
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
  useEffect(() => { const node = messageList.current; if (node) node.scrollTop = node.scrollHeight; }, [messages, activity]);
  useEffect(() => {
    if (!expanded) return;
    const escape = (e: KeyboardEvent) => { if (e.key === 'Escape') setExpanded(false); };
    document.addEventListener('keydown', escape); return () => document.removeEventListener('keydown', escape);
  }, [expanded]);

  const send = async (textOverride?: string) => {
    const text = (textOverride ?? input).trim();
    if (!text || active.current || busy || restoring || !dashboard?.canEdit || published || publishing) return;
    const answerId = crypto.randomUUID();
    setInput(''); setBusy(true); setError(''); setNotice(''); setActivity('正在准备看板…');
    setMessages(previous => [...previous, { id: crypto.randomUUID(), role: 'user', text }, { id: answerId, role: 'assistant', text: '' }]);
    const controller = new AbortController(); active.current = controller;
    let nextAnswer = false;
    try {
      for await (const event of stream(AGENT, { message: text, sessionKey: id, groupIds: [dashboard.groupId], webSearch: false }, controller.signal)) {
        if (!mounted.current || controller.signal.aborted) break;
        if (event.type === 'token' && event.data) {
          const clear = nextAnswer; nextAnswer = false;
          setMessages(previous => previous.map(m => m.id === answerId ? { ...m, text: (clear ? '' : m.text) + event.data } : m));
        }
        if (event.type === 'capacity') setActivity(event.message || '正在等待处理…');
        if (event.type === 'tool_call') nextAnswer = true;
        const progress = dashboardToolProgress(event.type, event.toolName, event.toolResult);
        if (progress) setActivity(progress);
        if (event.type === 'error') throw new Error(event.error || '生成失败，请重试');
      }
    } catch (e) { if (mounted.current && !controller.signal.aborted) {
      const message = errorText(e); setError(message); setInput(text);
      setMessages(previous => previous.map(m => m.id === answerId ? { ...m, text: message } : m));
    } } finally {
      active.current = undefined;
      if (mounted.current) {
        setBusy(false); setActivity('');
        try { await reload(); } catch (e) { if (mounted.current) setError(errorText(e)); }
      }
    }
  };

  const publish = async () => {
    if (!dashboard || dashboardPublishHint(dashboard, busy) || publishing) return;
    setPublishing(true); setError(''); setNotice('');
    try { setDashboard(await publishDashboard(id, dashboard.revision)); setConfirming(false); setNotice('已发布。有此知识库查询权限的成员现在可以查看。'); }
    catch (e) { setConfirming(false); setError(errorText(e)); }
    finally { setPublishing(false); }
  };
  if (loading) return <div className="dashboard-loading" role="status">正在打开看板…</div>;
  if (!dashboard) return <div className="dashboard-loading"><p role="alert">{error || '看板不存在'}</p><Link to="/dashboards">返回仪表盘</Link></div>;
  const status = published ? { kind: 'published', label: '已发布' } : dashboardStatus(dashboard);
  const publishHint = dashboardPublishHint(dashboard, busy);
  const saving = busy ? activity || '正在生成…' : status.kind === 'published' && !published ? '草稿与已发布版一致'
    : published ? '成员查看页面 · 沿用知识库访问权限' : dashboard.verified ? '草稿已保存 · 修改不会影响已发布版' : '修改仅影响草稿 · 完成构建检查后可发布';
  return <div className={`dashboard-workbench${published ? ' viewing' : ''}${collapsed ? ' conversation-collapsed' : ''}${expanded ? ' preview-expanded' : ''} mobile-${mobilePanel}`}>
    <header className="dashboard-workbench-header"><ShellNavigationToggle /><Link className="dashboard-back dashboard-icon-button" to="/dashboards" aria-label="返回仪表盘"><Icon name="back" size="sm" /></Link><div className="dashboard-header-title"><div className="dashboard-title-line"><h1 title={dashboard.title}>{dashboard.title}</h1><span className={`dashboard-badge ${status.kind}`}>{status.label}</span></div><p className="dashboard-save-hint">{saving}</p></div>
      {!published && <div className="dashboard-mobile-panels dashboard-segment" aria-label="工作区切换"><button aria-pressed={mobilePanel === 'chat'} onClick={() => setMobilePanel('chat')}>对话</button><button aria-pressed={mobilePanel === 'preview'} onClick={() => setMobilePanel('preview')}>预览</button></div>}
      <div className="dashboard-header-actions"><button className="dashboard-icon-button" aria-label="访问权限" title="访问权限" onClick={() => setPermissionsOpen(true)}><Icon name="shield" size="sm" /></button>{dashboard.publishedRevision && dashboard.canEdit && <button className="dashboard-secondary clear" disabled={busy || publishing} onClick={() => { setError(''); setNotice(''); setSearch(published ? {} : { view: 'published' }); }}>{published ? '返回编辑' : '查看已发布版'}<Icon name={published ? 'edit' : 'link'} size="sm" /></button>}
        {!published && dashboard.canPublish && <span title={publishHint || '发布当前草稿'}><button className="dashboard-primary" disabled={Boolean(publishHint) || publishing} aria-describedby={publishHint ? 'dashboard-publish-hint' : undefined} onClick={() => setConfirming(true)}><Icon name="upload" size="sm" />{publishing ? '正在检查并发布…' : dashboard.publishedRevision ? '发布更新' : '发布看板'}</button></span>}
      </div>
    </header>
    {permissionsOpen && <DashboardPermissions dashboard={dashboard} name={groupName} onClose={() => setPermissionsOpen(false)} />}
    {publishHint && !published && <span className="dashboard-sr-only" id="dashboard-publish-hint">{publishHint}</span>}
    {error && <div className="dashboard-notice error" role="alert">{error}</div>}{notice && <div className="dashboard-notice success" role="status">{notice}</div>}
    <div className="dashboard-workbench-body">
      {!published && <section className="dashboard-conversation" aria-label="看板对话"><header className="dashboard-conversation-heading"><span className="dashboard-ai-icon" aria-hidden="true">AI</span><div><h2>一起完善这张看板</h2><p>说出想法，边看边调整</p></div></header><div className="dashboard-scope-label"><Icon name="book" size="sm" /><span title={groupName}>{groupName || '正在读取知识库…'}</span></div><div className="dashboard-messages" ref={messageList}>
        {!messages.length && !restoring && <div className="dashboard-chat-welcome"><h2>你想了解哪些业务表现？</h2><p>描述关注的指标、时间和维度。助手会查询知识库，生成右侧看板。</p>{['生成最近一个月的业务概览，包含核心指标、趋势和排名。', '先看看这个知识库适合做哪些仪表盘，给我几个建议。'].map(text => <button key={text} onClick={() => setInput(text)}>{text}<Icon name="chevron" size="sm" /></button>)}</div>}
        {restoring && <p className="dashboard-muted" role="status">正在恢复对话…</p>}
        {messages.filter(message => message.text.trim()).map(message => <div className={`dashboard-message ${message.role}`} key={message.id}><span className="dashboard-message-label">{message.role === 'user' ? '你' : '看板助手'}</span>{message.role === 'assistant' ? <AssistantMessage text={message.text} /> : <p>{message.text}</p>}</div>)}
        {busy && <div className="dashboard-generation-state" role="status"><span className="dashboard-spinner" />{activity || '正在处理…'}</div>}
      </div><form className="dashboard-composer" onSubmit={event => { event.preventDefault(); void send(); }}><textarea value={input} maxLength={12000} disabled={busy || restoring || publishing} placeholder={dashboard.html ? '例如：把趋势改为按月，再突出华东表现…' : '描述你想生成的仪表盘…'} aria-label="看板需求" onChange={event => setInput(event.target.value)} onKeyDown={event => { if (event.key === 'Enter' && !event.shiftKey && !event.nativeEvent.isComposing) { event.preventDefault(); void send(); } }} /><div><span>{dashboard.canPublish ? '仅修改草稿 · 发布由管理者确认' : '仅修改草稿 · 需知识库管理者发布'}</span><button type="submit" className="dashboard-send" disabled={!input.trim() || busy || restoring || publishing} aria-label="发送看板需求"><Icon name="arrowUp" size="sm" /></button></div></form></section>}
      <section className="dashboard-canvas" aria-label="看板预览"><div className="dashboard-canvas-toolbar"><span><Icon name="chart" size="sm" />{published ? '已发布看板' : '看板预览'}</span><div>{!published && dashboard.html && <span className={`dashboard-verify-status${dashboard.verified ? ' is-verified' : ''}`}>{busy ? '生成中…' : dashboard.verified ? '构建检查通过' : '等待构建检查'}</span>}
        {!published && <button className="dashboard-icon-button" title={collapsed ? '展开对话' : '收起对话'} aria-label={collapsed ? '展开对话' : '收起对话'} aria-expanded={!collapsed} onClick={() => setCollapsed(value => !value)}><Icon name="list" size="sm" /></button>}
        <button className="dashboard-icon-button" title="刷新数据与预览" aria-label="刷新数据与预览" disabled={busy || publishing} onClick={() => { void reload().then(() => setPreviewKey(n => n + 1)).catch(e => setError(errorText(e))); }}><Icon name="refresh" size="sm" /></button>
        <button className="dashboard-icon-button" title={expanded ? '退出展开预览' : '展开预览'} aria-label={expanded ? '退出展开预览' : '展开预览'} aria-pressed={expanded} onClick={() => setExpanded(value => !value)}><Icon name="fit" size="sm" /></button>
      </div></div>
        {dashboard.html ? <React.Suspense fallback={<div className="dashboard-loading" role="status">正在准备图表预览…</div>}><DashboardPreview key={`${id}-${published}-${previewKey}`} dashboard={dashboard} draft={!published} /></React.Suspense> : <div className="dashboard-canvas-empty"><span className="dashboard-feature-icon"><Icon name="chart" /></span><h2>你的业务全景，即将在这里呈现</h2><p>{busy ? '助手正在梳理指标和数据，生成后会自动更新。' : '在对话中描述想法，开始生成第一版看板。'}</p><span>按知识库权限查询 · 对话修改 · 管理者发布</span></div>}
      </section>
    </div>
    {confirming && <DataSourceDialog className="dashboard-dialog" title={dashboard.publishedRevision ? '发布这次更新？' : '发布这张看板？'} description="发布后，有权限的知识库成员将看到这个版本。" busy={publishing} onClose={() => setConfirming(false)}><div className="dashboard-dialog-body"><div className="dashboard-publish-summary"><strong>{dashboard.title}</strong><span>知识库：{groupName}</span><span>访问范围：沿用知识库查询权限</span><span>草稿修改在发布前不会影响当前版本</span></div></div><footer className="dashboard-dialog-foot"><button className="dashboard-secondary clear" disabled={publishing} onClick={() => setConfirming(false)}>继续编辑</button><button className="dashboard-primary" disabled={publishing || Boolean(publishHint)} onClick={() => void publish()}>{publishing ? '正在检查并发布…' : '确认发布'}</button></footer></DataSourceDialog>}
  </div>;
}

export default function DashboardsPage() {
  const { dashboardId } = useParams();
  return dashboardId ? <DashboardWorkbench key={dashboardId} id={dashboardId} /> : <DashboardLibrary />;
}
