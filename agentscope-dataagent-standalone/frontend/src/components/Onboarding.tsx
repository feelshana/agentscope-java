import React, { useEffect, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import { useLocation, useNavigate } from 'react-router-dom';
import {
  dismissOnboarding, markTipSeen, OnboardingStatus, OnboardingTaskId, OnboardingTipId,
  refreshOnboarding, reopenOnboarding, resetOnboarding, setHelpOpen, useHelpOpen, useOnboarding,
} from '../api/onboarding';
import { forgetTour, resumeTour, startTour, useTour } from '../state/guidedTour';
import Icon from './Icon';
import type { IconName } from './Icon';
import './Onboarding.css';
import '../admin-help.css';

/** spec104: new-user checklist, starter questions, empty state, one-time tips and help. */

const STEPS: Record<OnboardingTaskId, { title: string; desc: string; cta: string; icon: IconName }> = {
  data: { title: '准备数据', desc: '创建知识库，上传文件或关联外部数据表', cta: '去准备数据', icon: 'database' },
  publish: { title: '完成建模并发布', desc: '建模助手整理字段与口径，你确认后发布', cta: '去建模', icon: 'model' },
  ask: { title: '问第一个问题', desc: '选中知识库，用中文直接提问，推荐问题可一键发送', cta: '去问数', icon: 'chat' },
  analyze: { title: '完成一次深入分析', desc: '在有日期的回答下方点“继续分析”，看归因、异常或走势', cta: '了解', icon: 'chart' },
};

export const UPLOAD_PATH = '/configure/datasets?create=1';
export const DATASOURCE_PATH = '/configure/datasources?create=1';

const nextStep = (s: OnboardingStatus) => s.tasks.find(t => !t.done)?.id ?? null;

/** Moves the user to where a step is done; "ask" uses the first starter question when there is one. */
function useStepAction(onAsk?: () => void) {
  const navigate = useNavigate();
  return (id: OnboardingTaskId) => {
    if (id === 'data') navigate(UPLOAD_PATH);
    else if (id === 'publish') navigate('/configure/modeling');
    else if (id === 'ask') { if (onAsk) onAsk(); else navigate('/chat'); }
    else setHelpOpen(true);
  };
}

/** spec105: the first step opens the guided tour, resuming it when the user left halfway. */
function useBeginTour() {
  const navigate = useNavigate();
  const tour = useTour();
  return () => {
    if (tour) { resumeTour(); return; }
    startTour();
    navigate(UPLOAD_PATH);
  };
}

function Progress({ status }: { status: OnboardingStatus }) {
  return <div className="da-ob-progress" aria-label={`已完成 ${status.completed} / ${status.tasks.length}`}>
    <span><strong>{status.completed}</strong> / {status.tasks.length} 已完成</span>
    <span className="da-ob-segs" aria-hidden="true">
      {status.tasks.map(t => <span key={t.id} className={t.done ? 'is-done' : undefined} />)}
    </span>
  </div>;
}

function Dot({ index, done, current }: { index: number; done: boolean; current: boolean }) {
  const base: React.CSSProperties = { width: 26, height: 26, borderRadius: '50%', display: 'inline-flex', alignItems: 'center', justifyContent: 'center', fontSize: 13, flex: 'none', boxSizing: 'border-box' };
  if (done) return <span aria-hidden="true" style={{ ...base, background: 'var(--da-primary-subtle)', color: 'var(--da-success)' }}><Icon name="check" size="sm" /></span>;
  if (current) return <span aria-hidden="true" style={{ ...base, background: 'var(--da-primary)', color: '#fff', fontWeight: 700 }}>{index}</span>;
  return <span aria-hidden="true" style={{ ...base, border: '2px solid var(--da-border-strong)', color: 'var(--da-text-muted)' }}>{index}</span>;
}

/** The two ways to prepare data, side by side; uploading needs nothing outside the platform. */
export function DataSourceChoices({ compact = false }: { compact?: boolean }) {
  const navigate = useNavigate();
  return <div className="da-ob-choices">
    <div className="da-ob-choice is-recommended">
      <span className="da-ob-choice-icon" aria-hidden="true"><Icon name="upload" /></span>
      <div className="da-ob-choice-body">
        <span className="da-ob-choice-title">上传文件<span className="da-ob-badge">推荐新手</span></span>
        {!compact && <p>Excel 或 CSV 直接上传，不依赖网络和外部系统，几分钟就能开始问数。</p>}
        <button type="button" className="da-btn da-btn-primary da-btn-sm" onClick={() => navigate(UPLOAD_PATH)}>创建知识库并上传</button>
      </div>
    </div>
    <div className="da-ob-choice">
      <span className="da-ob-choice-icon" aria-hidden="true"><Icon name="database" /></span>
      <div className="da-ob-choice-body">
        <span className="da-ob-choice-title">关联外部数据表</span>
        {!compact && <p>先新建数据源（MySQL 8.0+ 或 Doris，连接测试通过），再在知识库里选择要关联的表。</p>}
        <p className="da-ob-note"><Icon name="warn" size="sm" />需要平台能访问到的数据库，没有时请先上传文件体验。</p>
        <button type="button" className="da-btn da-btn-sm" onClick={() => navigate(DATASOURCE_PATH)}>新建数据源</button>
      </div>
    </div>
  </div>;
}

/** Full checklist under the home composer, on the first day only. */
export function OnboardingChecklist({ status, onAsk }: { status: OnboardingStatus; onAsk?: () => void }) {
  if (status.completed === status.tasks.length) return <OnboardingCompleted status={status} />;
  return <OnboardingSteps status={status} onAsk={onAsk} />;
}

/** All steps done: stays as a short completion card for the rest of that day. */
function OnboardingCompleted({ status }: { status: OnboardingStatus }) {
  return <section aria-label="新手任务已完成" className="da-ob-card">
    <div className="da-ob-banner">
      <span className="da-ob-banner-icon" aria-hidden="true"><Icon name="check" /></span>
      <div className="da-ob-banner-body">
        <h2>新手任务已全部完成</h2>
        <p>你已经走通了从准备数据、建模发布到问数和深入分析的全过程。明天起这里不再显示，说明可在“更多 › 帮助与引导”查看。</p>
        <div className="da-ob-done-list">
          {status.tasks.map(t => <span key={t.id}><Icon name="check" size="sm" />{STEPS[t.id].title}</span>)}
        </div>
      </div>
      <button type="button" className="da-btn da-btn-ghost da-btn-sm" onClick={() => void dismissOnboarding()}>关闭</button>
    </div>
  </section>;
}

function OnboardingSteps({ status, onAsk }: { status: OnboardingStatus; onAsk?: () => void }) {
  const next = nextStep(status);
  const act = useStepAction(onAsk);
  const begin = useBeginTour();
  return <section aria-label="新手任务" className="da-ob-card">
    <header className="da-ob-head">
      <div className="da-ob-head-text">
        <h2>开始使用 DataAgent</h2>
        <p>{status.tasks.length} 步走通{status.tasks[0]?.id === 'data' ? '从准备数据到深入分析' : '提问和深入分析'}，可以全程使用示例数据，完成的步骤会自动打勾</p>
      </div>
      <Progress status={status} />
    </header>
    <ol className="da-ob-steps">
      {status.tasks.map((t, i) => {
        const step = STEPS[t.id];
        const current = t.id === next;
        return <li key={t.id} className={`da-ob-step${current ? ' is-current' : ''}${t.done ? ' is-done' : ''}`} aria-current={current ? 'step' : undefined}>
          <div className="da-ob-step-top">
            <span className="da-ob-step-no">第 {i + 1} 步</span>
            <span className="da-ob-step-icon" aria-hidden="true"><Icon name={t.done ? 'check' : step.icon} size="sm" /></span>
          </div>
          <span className="da-ob-step-title">{step.title}</span>
          <p className="da-ob-step-desc">{step.desc}</p>
          {t.done
            ? <span className="da-ob-step-state">已完成</span>
            : t.id === 'data'
              ? <button type="button" className="da-ob-step-action" onClick={begin}>开始第一步<Icon name="chevron" size="sm" /></button>
              : <button type="button" className="da-ob-step-action" onClick={() => act(t.id)}>{step.cta}<Icon name="chevron" size="sm" /></button>}
        </li>;
      })}
    </ol>
    <footer className="da-ob-foot">
      <span>明天起收起到侧栏底部，{status.daysLeft} 天后自动隐藏；可在“更多 › 帮助与引导”重新打开。</span>
      <button type="button" className="da-ob-link" onClick={() => void dismissOnboarding()}>不再显示</button>
    </footer>
  </section>;
}

const STARTER_FILLERS: { title: string; desc: string; path: string; icon: 'upload' | 'database' }[] = [
  { title: '上传新数据', desc: '新建知识库并上传 Excel / CSV，建模发布后即可问数', path: UPLOAD_PATH, icon: 'upload' },
  { title: '关联外部数据表', desc: '新建数据源，选择要分析的数据库表', path: DATASOURCE_PATH, icon: 'database' },
];

/** Recommended questions of published knowledge bases the user may query; independent of the checklist. */
export function StarterQuestions({ starters, onPick }: {
  starters: OnboardingStatus['starters'];
  onPick: (starter: OnboardingStatus['starters'][number]) => void;
}) {
  const navigate = useNavigate();
  if (starters.length === 0) return null;
  // Fewer than three questions: the remaining slots point to adding data, so the home page never
  // looks unfinished.
  const fillers = STARTER_FILLERS.slice(0, Math.max(0, 3 - starters.length));
  return <section aria-label="从这些问题开始" className="da-ob-section">
    <div className="da-ob-section-head">
      <h2>从这些问题开始</h2>
      <span>来自你能使用的知识库，点一下即选中知识库并填入问题</span>
    </div>
    <div className="da-ob-grid">
      {starters.map(s => <button key={s.groupId} type="button" className="da-ob-starter" onClick={() => onPick(s)}>
        <span className="da-ob-starter-q">{s.question}</span>
        <span className="da-ob-starter-kb"><Icon name="book" size="sm" /><b>{s.groupName}</b>{s.shared && <em>同事共享</em>}</span>
        <span className="da-ob-starter-go">填入问题<Icon name="chevron" size="sm" /></span>
      </button>)}
      {fillers.map(f => <button key={f.title} type="button" className="da-ob-starter is-filler" onClick={() => navigate(f.path)}>
        <span className="da-ob-starter-kb"><Icon name={f.icon} size="sm" />{f.title}</span>
        <span className="da-ob-starter-q">{f.desc}</span>
        <span className="da-ob-starter-go">去添加<Icon name="chevron" size="sm" /></span>
      </button>)}
    </div>
  </section>;
}

/** Shown on the home page when no published knowledge base can be queried yet. */
export function NoKnowledgeBaseNotice({ unpublished }: { unpublished: string[] }) {
  const navigate = useNavigate();
  return <section aria-label="还没有可以问数的知识库" className="da-ob-card">
    <div className="da-ob-banner">
      <span className="da-ob-banner-icon is-neutral" aria-hidden="true"><Icon name="book" /></span>
      <div className="da-ob-banner-body">
        <h2>还没有可以问数的知识库</h2>
        <p>问数需要一个已发布的知识库。可以自己准备数据并建模，也可以请同事共享知识库给你，共享后会直接出现在“选择知识库”里。未选择知识库时只能普通对话，回答不会查询数据。</p>
        {unpublished.length > 0
          ? <div style={{ display: 'flex', alignItems: 'center', gap: 10, flexWrap: 'wrap', fontSize: 13, color: 'var(--da-text-2)' }}>
            <span>“{unpublished.join('”“')}”还没有发布，完成建模后点击“发布”即可问数。</span>
            <button type="button" className="da-btn da-btn-primary da-btn-sm" onClick={() => navigate('/configure/modeling')}>去建模</button>
          </div>
          : <DataSourceChoices compact />}
      </div>
    </div>
  </section>;
}

/** Day two onwards: a small entry above the user menu with the remaining steps in a popover. */
export function OnboardingSidebarEntry() {
  const status = useOnboarding();
  const [open, setOpen] = useState(false);
  const button = useRef<HTMLButtonElement | null>(null);
  const popover = useRef<HTMLDivElement | null>(null);
  const act = useStepAction();
  const { pathname, search } = useLocation();
  useEffect(() => { if (open) void refreshOnboarding(true); }, [open]);
  useEffect(() => { setOpen(false); }, [pathname, search]);
  useEffect(() => {
    if (!open) return;
    const close = (e: MouseEvent | KeyboardEvent) => {
      if (e instanceof KeyboardEvent) { if (e.key === 'Escape') setOpen(false); return; }
      const target = e.target as Node;
      if (!popover.current?.contains(target) && !button.current?.contains(target)) setOpen(false);
    };
    document.addEventListener('mousedown', close);
    document.addEventListener('keydown', close);
    return () => { document.removeEventListener('mousedown', close); document.removeEventListener('keydown', close); };
  }, [open]);
  if (!status || status.stage !== 'collapsed') return null;
  const next = nextStep(status);
  const rect = button.current?.getBoundingClientRect();
  return <div className="rail-onboarding">
    <button ref={button} type="button" aria-expanded={open} aria-controls="onboarding-popover" onClick={() => setOpen(o => !o)}
      className="rail-onboarding-trigger">
      <Icon name="list" size="sm" /><span style={{ flex: 1, textAlign: 'left' }}>新手任务</span><span>{status.completed}/{status.tasks.length}</span><Icon name="chevron" size="sm" />
    </button>
    {open && createPortal(<div ref={popover} id="onboarding-popover" role="dialog" aria-label="新手任务"
      style={{ position: 'fixed', left: rect ? rect.right + 12 : 260, bottom: rect ? Math.max(8, window.innerHeight - rect.bottom) : 20, width: 340, maxWidth: 'calc(100vw - 24px)', background: 'var(--da-surface)', border: '1px solid var(--da-border)', borderRadius: 12, boxShadow: 'var(--da-shadow-pop)', padding: 16, display: 'flex', flexDirection: 'column', gap: 10, zIndex: 1000, color: 'var(--da-text)' }}>
      <div style={{ display: 'flex', alignItems: 'center' }}>
        <strong style={{ fontSize: 15, flex: 1 }}>新手任务 {status.completed}/{status.tasks.length}</strong>
        <button type="button" className="da-btn da-btn-ghost da-btn-sm" aria-label="收起" onClick={() => setOpen(false)}><Icon name="close" size="sm" /></button>
      </div>
      {status.tasks.map((t, i) => <div key={t.id} style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
        <Dot index={i + 1} done={t.done} current={t.id === next} />
        <span style={{ flex: 1, fontSize: 13, color: t.done ? 'var(--da-text-muted)' : 'var(--da-text)', fontWeight: t.id === next ? 600 : 400 }}>{STEPS[t.id].title}</span>
        {t.id === next && <button type="button" className="da-btn da-btn-primary da-btn-sm" onClick={() => { setOpen(false); act(t.id); }}>{STEPS[t.id].cta}</button>}
      </div>)}
      <div style={{ display: 'flex', alignItems: 'center', fontSize: 12, color: 'var(--da-text-muted)' }}>
        <span style={{ flex: 1 }}>{status.daysLeft} 天后自动隐藏</span>
        <button type="button" className="da-btn da-btn-ghost da-btn-sm" onClick={() => { setOpen(false); void dismissOnboarding(); }}>不再显示</button>
      </div>
    </div>, document.body)}
  </div>;
}

const HELP_SECTIONS: { id: string; icon: IconName; title: string; body: string; tags: string; link: string | null; path: string | null }[] = [
  { id: 'ask', icon: 'chat', title: '问数', body: '在问数页选择知识库，用中文提问。回答里的数字都来自查询结果；选中知识库时会预填一个推荐问题。', tags: '查询 问题 日期 数据来源', link: '去问数', path: '/chat' },
  { id: 'model', icon: 'model', title: '准备数据与建模', body: '上传文件或关联外部表后，建模助手整理字段、口径与关系。在“AI 建模结果”中逐条查看，确认后统一发布，问数使用已发布版本。', tags: '上传 文件 Excel CSV 数据源 字段 业务说明 口径 关系 模型 发布', link: '去建模', path: '/configure/modeling' },
  { id: 'analyze', icon: 'chart', title: '分析', body: '有日期的回答下方可点“为什么变化 / 有没有异常 / 未来走势”，或用输入框旁的“分析”；先确认参数再运行。', tags: '归因 异常 预测 趋势 日期 统计', link: null, path: null },
  { id: 'access', icon: 'shield', title: '权限与共享', body: '知识库的“仅可使用”权限用于问数，“可管理”权限支持维护数据、建模和发布。创建人与全局管理员可以管理知识库，个人会话仍按原有权限访问。', tags: '成员 授权 管理员 共享 QUERY MANAGE', link: '查看知识库', path: '/configure/datasets' },
];

/** Searchable help uses the existing checklist and guided-tour actions. */
export function HelpPanel() {
  const open = useHelpOpen();
  return open ? createPortal(<HelpDrawer />, document.body) : null;
}
function HelpDrawer() {
  const status = useOnboarding();
  const navigate = useNavigate();
  const tour = useTour();
  const begin = useBeginTour();
  const dialog = useRef<HTMLDialogElement>(null);
  const search = useRef<HTMLInputElement>(null);
  const body = useRef<HTMLDivElement>(null);
  const [query, setQuery] = useState('');
  const [active, setActive] = useState('tasks');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const close = () => setHelpOpen(false);
  useEffect(() => {
    const element = dialog.current;
    element?.showModal();
    void refreshOnboarding(true);
    return () => element?.close();
  }, []);
  async function changeTasks(reset: boolean) {
    if (busy) return;
    if (reset && !window.confirm('重置后新手任务回到第一步，可以重新体验一遍。已有的知识库和数据都会保留，也照常可用，只是不再计入新手任务。确定重置吗？')) return;
    setBusy(true); setError('');
    try {
      if (reset) { await resetOnboarding(); forgetTour(); }
      else await reopenOnboarding();
      close(); navigate('/chat');
    } catch (e) { setError((e as Error).message); }
    finally { setBusy(false); }
  }
  const steps = (['data', 'publish', 'ask', 'analyze'] as const);
  const done = new Set(status?.tasks.filter(task => task.done).map(task => task.id));
  const next = steps.find(id => !done.has(id));
  const matches = HELP_SECTIONS.filter(section => [section.title, section.body, section.tags].join(' ').toLowerCase().includes(query.trim().toLowerCase()));
  function jump(id: string) {
    setQuery(''); setActive(id);
    requestAnimationFrame(() => body.current?.querySelector('#help-' + id)?.scrollIntoView({ block: 'start' }));
  }
  return <dialog ref={dialog} className="help-drawer" aria-labelledby="help-title"
    onCancel={e => { e.preventDefault(); if (!busy) close(); }}
    onKeyDown={e => { if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'k') { e.preventDefault(); search.current?.focus(); } }}
    onClick={e => { const r = e.currentTarget.getBoundingClientRect(); if (!busy && e.target === e.currentTarget && (e.clientX < r.left || e.clientX > r.right || e.clientY < r.top || e.clientY > r.bottom)) close(); }}>
    <header className="help-head"><div><h2 id="help-title">帮助与引导</h2><p>从准备数据到问数分析，按需找到下一步。</p></div><button className="am-close" aria-label="关闭帮助" disabled={busy} onClick={close}><Icon name="close" /></button></header>
    <label className="am-search help-search"><Icon name="search" /><input ref={search} autoFocus type="search" placeholder="搜索帮助，例如“口径”“建模”“权限”" aria-label="搜索帮助" maxLength={160} value={query} onChange={e => setQuery(e.target.value)} /><kbd>Ctrl K</kbd></label>
    <nav className="help-anchors" aria-label="帮助分类">{[{ id: 'tasks', title: '新手任务' }, ...HELP_SECTIONS].map(section => <button key={section.id} className={active === section.id ? 'active' : ''} onClick={() => jump(section.id)}>{section.id === 'model' ? '建模' : section.id === 'access' ? '权限' : section.title}</button>)}</nav>
    <div ref={body} className="help-body">
      <section id="help-tasks" className="help-tasks"><header><strong>新手任务</strong><span>{status ? status.completed : '—'} / 4{status?.completed === 4 ? ' · 已全部完成' : status?.stage === 'hidden' ? ' · 已隐藏' : status?.stage === 'collapsed' ? ' · 在侧栏底部' : ''}</span></header>
        <div className="help-segments" aria-label={status ? '已完成 ' + status.completed + ' / 4 步' : '读取任务进度中'}>{steps.map(id => <span key={id} className={done.has(id) ? 'done' : ''} />)}</div>
        <ol className="help-steps">{steps.map(id => <li key={id} className={done.has(id) ? 'done' : id === next ? 'current' : ''}><i />{id === 'publish' ? '建模发布' : id === 'ask' ? '去问数' : id === 'analyze' ? '做分析' : '准备数据'}</li>)}</ol>
        {!status && <p className="help-note">暂未读取到任务进度，<button className="am-text" onClick={() => void refreshOnboarding(true)}>重新加载</button></p>}
        <div className="help-task-actions"><button className="am-btn primary small" disabled={busy} onClick={() => { close(); begin(); }}>{tour?.paused ? '继续逐步指引' : '开始逐步指引'}<Icon name="chevron" /></button><button className="am-btn small" disabled={busy} onClick={() => void changeTasks(false)}>重新打开新手任务</button><button className="am-text danger" disabled={busy} onClick={() => void changeTasks(true)}>重置新手任务</button></div>
        <p className="help-note">重新打开会保留已有进度，并从今天起重新计 7 天；重置只重置任务记录，保留已有知识库和数据。</p>
        {error && <p className="am-error" role="alert">{error}</p>}
      </section>
      {query && <p className="help-result-count" role="status">找到 {matches.length} 项使用说明</p>}
      {matches.map(section => <section key={section.id} id={'help-' + section.id} className="help-section"><header><span className="help-section-icon"><Icon name={section.icon} /></span><h3>{section.title}</h3>{section.path && <button className="am-text help-go" onClick={() => { close(); navigate(section.path!); }}>{section.link}<Icon name="chevron" /></button>}</header><p>{section.body}</p></section>)}
      {!matches.length && <div className="am-feedback">没有找到相关说明，试试“问数”“建模”或“权限”。</div>}
      <footer className="help-foot"><Icon name="info" /><span>开始逐步指引，可体验创建知识库、上传示例数据到问答分析的完整流程。</span></footer>
    </div>
  </dialog>;
}

/** A one-time hint next to a feature; never shown again after "知道了" or dismissing the checklist. */
export function OnboardingTip({ tip, children }: { tip: OnboardingTipId; children: React.ReactNode }) {
  const status = useOnboarding();
  if (!status || status.dismissed || status.tipsSeen.includes(tip)) return null;
  return <div role="note" style={{ marginTop: 8, background: 'var(--da-text)', color: 'var(--da-surface)', borderRadius: 8, padding: '10px 12px', display: 'flex', alignItems: 'flex-start', gap: 10, maxWidth: 420, fontSize: 13, lineHeight: 1.6 }}>
    <span style={{ flex: 1 }}>{children}</span>
    <button type="button" className="da-btn da-btn-primary da-btn-sm" style={{ flex: 'none' }} onClick={() => void markTipSeen(tip)}>知道了</button>
  </div>;
}
