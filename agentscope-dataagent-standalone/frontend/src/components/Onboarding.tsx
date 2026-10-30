import React, { useEffect, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import { useLocation, useNavigate } from 'react-router-dom';
import {
  dismissOnboarding, markTipSeen, OnboardingStatus, OnboardingTaskId, OnboardingTipId,
  refreshOnboarding, reopenOnboarding, setHelpOpen, useHelpOpen, useOnboarding,
} from '../api/onboarding';
import Icon from './Icon';
import type { IconName } from './Icon';
import './Onboarding.css';

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
  const [dataOpen, setDataOpen] = useState(next === 'data');
  const act = useStepAction(onAsk);
  const dataDone = status.tasks.some(t => t.id === 'data' && t.done);
  return <section aria-label="新手任务" className="da-ob-card">
    <header className="da-ob-head">
      <div className="da-ob-head-text">
        <h2>开始使用 DataAgent</h2>
        <p>4 步走通从准备数据到深入分析，完成的步骤会自动打勾</p>
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
              ? <button type="button" className="da-ob-step-action" aria-expanded={dataOpen} aria-controls="onboarding-data-choices"
                onClick={() => setDataOpen(o => !o)}>{dataOpen ? '收起方式' : '选择方式'}<span className={`da-ob-chevron${dataOpen ? ' is-open' : ''}`}><Icon name="chevron" size="sm" /></span></button>
              : <button type="button" className="da-ob-step-action" onClick={() => act(t.id)}>{step.cta}<Icon name="chevron" size="sm" /></button>}
        </li>;
      })}
    </ol>
    {dataOpen && !dataDone && <div id="onboarding-data-choices" className="da-ob-expand"><DataSourceChoices /></div>}
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
        <span className="da-ob-starter-kb"><Icon name="book" size="sm" /><b>{s.groupName}</b>{s.shared && <em>同事共享</em>}</span>
        <span className="da-ob-starter-q">{s.question}</span>
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
  return <div style={{ padding: '0 10px 8px', flexShrink: 0 }}>
    <button ref={button} type="button" aria-expanded={open} aria-controls="onboarding-popover" onClick={() => setOpen(o => !o)}
      style={{ width: '100%', display: 'flex', alignItems: 'center', gap: 8, padding: '9px 12px', borderRadius: 10, border: '1px solid var(--da-primary)', background: 'var(--da-primary-subtle)', color: 'var(--da-primary)', fontSize: 13, fontWeight: 600, cursor: 'pointer', font: 'inherit' }}>
      <Icon name="list" size="sm" /><span style={{ flex: 1, textAlign: 'left' }}>新手任务</span><span>{status.completed}/{status.tasks.length}</span>
    </button>
    {open && createPortal(<div ref={popover} id="onboarding-popover" role="dialog" aria-label="新手任务"
      style={{ position: 'fixed', left: rect ? rect.right + 12 : 260, bottom: rect ? Math.max(8, window.innerHeight - rect.bottom) : 20, width: 340, maxWidth: 'calc(100vw - 24px)', background: 'var(--da-surface)', border: '1px solid var(--da-border)', borderRadius: 14, boxShadow: 'var(--da-shadow-pop)', padding: 16, display: 'flex', flexDirection: 'column', gap: 10, zIndex: 1000, color: 'var(--da-text)' }}>
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

const HELP_SECTIONS = [
  { title: '问数', body: '在问数页选择知识库，用中文提问。回答里的数字都来自查询结果；选中知识库时会预填一个推荐问题。', link: '去问数', path: '/chat' },
  { title: '准备数据与建模', body: '上传文件或关联外部表后，建模助手自动整理字段、口径与关系，在“AI 建模结果”中逐条查看，确认后发布。', link: '去建模', path: '/configure/modeling' },
  { title: '分析', body: '有日期的回答下方可点“为什么变化 / 有没有异常 / 未来走势”，或用输入框旁的“分析 ▾”；先确认参数再运行。', link: null, path: null },
];

/** Drawer opened from 更多 › 帮助与引导 (and from the checklist's last step). */
export function HelpPanel() {
  const open = useHelpOpen();
  const status = useOnboarding();
  const navigate = useNavigate();
  useEffect(() => { if (open) void refreshOnboarding(true); }, [open]);
  if (!open) return null;
  const close = () => setHelpOpen(false);
  const allDone = !!status && status.completed === status.tasks.length;
  return createPortal(<div className="da-modal-overlay" onClick={close} style={{ justifyContent: 'flex-end', alignItems: 'stretch' }}>
    <aside role="dialog" aria-modal="true" aria-label="帮助与引导" onClick={e => e.stopPropagation()}
      onKeyDown={e => { if (e.key === 'Escape') close(); }}
      style={{ width: 'min(460px, 100vw)', height: '100%', background: 'var(--da-surface)', boxShadow: 'var(--da-shadow-pop)', padding: 24, boxSizing: 'border-box', display: 'flex', flexDirection: 'column', gap: 18, overflow: 'auto', color: 'var(--da-text)' }}>
      <div style={{ display: 'flex', alignItems: 'center' }}>
        <h2 style={{ margin: 0, fontSize: 18, flex: 1 }}>帮助与引导</h2>
        <button type="button" className="da-btn da-btn-ghost da-btn-sm" aria-label="关闭" autoFocus onClick={close}><Icon name="close" size="sm" /></button>
      </div>
      {status && <div style={{ border: '1px solid var(--da-primary)', background: 'var(--da-primary-subtle)', borderRadius: 12, padding: 14, display: 'flex', flexDirection: 'column', gap: 8 }}>
        <div style={{ display: 'flex', alignItems: 'center' }}>
          <strong style={{ fontSize: 14, flex: 1 }}>新手任务</strong>
          <span style={{ fontSize: 13, color: 'var(--da-primary)' }}>{status.completed} / {status.tasks.length}{allDone ? ' · 已全部完成' : status.stage === 'hidden' ? ' · 已隐藏' : status.stage === 'collapsed' ? ' · 在侧栏底部' : ' · 在问数首页'}</span>
        </div>
        <>
            {allDone && <span style={{ fontSize: 12, color: 'var(--da-text-2)' }}>你已经走通了从数据到分析的全部步骤，重新打开会保留已有完成记录。</span>}
            <span style={{ fontSize: 12, color: 'var(--da-text-2)' }}>重新打开后，问数首页会再次显示清单，并从今天起重新计 7 天。</span>
            <button type="button" className="da-btn da-btn-primary da-btn-sm" style={{ alignSelf: 'flex-start' }}
              onClick={() => { void reopenOnboarding(); close(); navigate('/chat'); }}>{status.stage === 'full' ? '查看新手任务' : '重新打开新手任务'}</button>
          </>
      </div>}
      {HELP_SECTIONS.map(s => <section key={s.title} style={{ display: 'flex', flexDirection: 'column', gap: 6, borderBottom: '1px solid var(--da-border)', paddingBottom: 14 }}>
        <strong style={{ fontSize: 14 }}>{s.title}</strong>
        <span style={{ fontSize: 13, color: 'var(--da-text-2)', lineHeight: 1.7 }}>{s.body}</span>
        {s.path && <button type="button" className="da-btn da-btn-ghost da-btn-sm" style={{ alignSelf: 'flex-start' }} onClick={() => { close(); navigate(s.path!); }}>{s.link} ›</button>}
      </section>)}
    </aside>
  </div>, document.body);
}

/** A one-time hint next to a feature; never shown again after "知道了" or dismissing the checklist. */
export function OnboardingTip({ tip, children }: { tip: OnboardingTipId; children: React.ReactNode }) {
  const status = useOnboarding();
  if (!status || status.dismissed || status.tipsSeen.includes(tip)) return null;
  return <div role="note" style={{ marginTop: 8, background: 'var(--da-text)', color: 'var(--da-surface)', borderRadius: 10, padding: '10px 12px', display: 'flex', alignItems: 'flex-start', gap: 10, maxWidth: 420, fontSize: 13, lineHeight: 1.6 }}>
    <span style={{ flex: 1 }}>{children}</span>
    <button type="button" className="da-btn da-btn-primary da-btn-sm" style={{ flex: 'none' }} onClick={() => void markTipSeen(tip)}>知道了</button>
  </div>;
}
