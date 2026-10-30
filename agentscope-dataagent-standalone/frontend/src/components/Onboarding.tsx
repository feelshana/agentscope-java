import React, { useEffect, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import { useLocation, useNavigate } from 'react-router-dom';
import {
  dismissOnboarding, markTipSeen, OnboardingStatus, OnboardingTaskId, OnboardingTipId,
  refreshOnboarding, reopenOnboarding, setHelpOpen, useHelpOpen, useOnboarding,
} from '../api/onboarding';
import Icon from './Icon';

/** spec104: new-user checklist, starter questions, empty state, one-time tips and help. */

const STEPS: Record<OnboardingTaskId, { title: string; desc: string; cta: string }> = {
  data: { title: '准备数据', desc: '创建知识库，上传文件或关联外部数据表', cta: '去准备数据' },
  publish: { title: '完成建模并发布', desc: '建模助手自动整理字段与口径，你确认后发布', cta: '去建模' },
  ask: { title: '问第一个问题', desc: '选中知识库，直接用中文提问，推荐问题可一键发送', cta: '去问数' },
  analyze: { title: '用“继续分析”做一次深入分析', desc: '在有日期的回答下方点“为什么变化 / 有没有异常 / 未来走势”', cta: '了解' },
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
  return <span style={{ display: 'inline-flex', alignItems: 'center', gap: 8 }}>
    <span style={{ fontSize: 13, color: 'var(--da-primary)', fontWeight: 600 }}>{status.completed} / {status.tasks.length}</span>
    <span aria-hidden="true" style={{ width: 96, height: 6, borderRadius: 3, background: 'var(--da-surface-sunken)' }}>
      <span style={{ display: 'block', width: `${Math.max(4, (status.completed / status.tasks.length) * 100)}%`, height: 6, borderRadius: 3, background: 'var(--da-primary)' }} />
    </span>
  </span>;
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
  const card: React.CSSProperties = { border: '1px solid var(--da-border)', borderRadius: 12, padding: 14, display: 'flex', flexDirection: 'column', gap: 8, textAlign: 'left', minWidth: 0 };
  return <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(240px, 1fr))', gap: 12 }}>
    <div style={{ ...card, borderColor: 'var(--da-primary)', background: 'var(--da-primary-subtle)' }}>
      <span style={{ display: 'flex', alignItems: 'center', gap: 8 }}><strong style={{ fontSize: 14 }}>上传文件</strong>
        <span style={{ fontSize: 11, fontWeight: 600, color: 'var(--da-primary)', border: '1px solid var(--da-primary)', padding: '0 6px', borderRadius: 4 }}>推荐新手</span></span>
      {!compact && <span style={{ fontSize: 13, color: 'var(--da-text-2)', lineHeight: 1.6 }}>Excel 或 CSV 直接上传，不依赖网络和外部系统，几分钟就能开始问数。</span>}
      <button type="button" className="da-btn da-btn-primary da-btn-sm" style={{ alignSelf: 'flex-start' }} onClick={() => navigate(UPLOAD_PATH)}>
        <Icon name="upload" size="sm" />创建知识库并上传</button>
    </div>
    <div style={card}>
      <strong style={{ fontSize: 14 }}>关联外部数据表</strong>
      {!compact && <span style={{ fontSize: 13, color: 'var(--da-text-2)', lineHeight: 1.6 }}>先<strong>新建数据源</strong>（MySQL 8.0+ 或 Doris，连接测试通过），再在知识库里选择要关联的表。</span>}
      <span style={{ fontSize: 12, color: 'var(--da-warn)', lineHeight: 1.5 }}>需要平台能访问到的数据库；没有时请先用上传文件体验。</span>
      <button type="button" className="da-btn da-btn-sm" style={{ alignSelf: 'flex-start' }} onClick={() => navigate(DATASOURCE_PATH)}>
        <Icon name="database" size="sm" />新建数据源</button>
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
  return <section aria-label="新手任务已完成" style={{ width: '100%', maxWidth: 880, marginTop: 24, flexShrink: 0, background: 'var(--da-surface)', border: '1px solid var(--da-border)', borderRadius: 16, padding: '16px 20px', display: 'flex', flexDirection: 'column', gap: 10, textAlign: 'left' }}>
    <div style={{ display: 'flex', flexWrap: 'wrap', alignItems: 'center', gap: 12 }}>
      <span aria-hidden="true" style={{ width: 28, height: 28, borderRadius: '50%', background: 'var(--da-primary-subtle)', color: 'var(--da-success)', display: 'inline-flex', alignItems: 'center', justifyContent: 'center', flex: 'none' }}><Icon name="check" size="sm" /></span>
      <strong style={{ fontSize: 16 }}>新手任务已全部完成</strong>
      <span style={{ flex: 1 }} />
      <Progress status={status} />
    </div>
    <div style={{ display: 'flex', flexWrap: 'wrap', gap: '6px 16px', fontSize: 13, color: 'var(--da-text-muted)' }}>
      {status.tasks.map(t => <span key={t.id} style={{ display: 'inline-flex', alignItems: 'center', gap: 4 }}><Icon name="check" size="sm" />{STEPS[t.id].title}</span>)}
    </div>
    <div style={{ display: 'flex', flexWrap: 'wrap', alignItems: 'center', gap: 12, fontSize: 13, color: 'var(--da-text-2)' }}>
      <span style={{ flex: 1, minWidth: 240, lineHeight: 1.6 }}>你已经走通了从准备数据、建模发布到问数和深入分析的全过程。明天起这里不再显示，说明可在“更多 › 帮助与引导”查看。</span>
      <button type="button" className="da-btn da-btn-sm" onClick={() => void dismissOnboarding()}>关闭</button>
    </div>
  </section>;
}

function OnboardingSteps({ status, onAsk }: { status: OnboardingStatus; onAsk?: () => void }) {
  const next = nextStep(status);
  const [open, setOpen] = useState<OnboardingTaskId | null>(next === 'data' ? 'data' : null);
  const act = useStepAction(onAsk);
  return <section aria-label="新手任务" style={{ width: '100%', maxWidth: 880, marginTop: 24, flexShrink: 0, background: 'var(--da-surface)', border: '1px solid var(--da-border)', borderRadius: 16, overflow: 'hidden', textAlign: 'left' }}>
    <header style={{ padding: '14px 20px', display: 'flex', flexWrap: 'wrap', alignItems: 'center', gap: 12, borderBottom: '1px solid var(--da-border)' }}>
      <strong style={{ fontSize: 16 }}>开始使用 DataAgent</strong>
      <span style={{ fontSize: 13, color: 'var(--da-text-muted)' }}>完成 4 步，学会从数据到分析</span>
      <span style={{ flex: 1 }} />
      <Progress status={status} />
    </header>
    <ol style={{ listStyle: 'none', margin: 0, padding: '4px 20px' }}>
      {status.tasks.map((t, i) => {
        const step = STEPS[t.id];
        const current = t.id === next;
        const expandable = t.id === 'data' && !t.done;
        return <li key={t.id} style={{ borderBottom: i < status.tasks.length - 1 ? '1px solid var(--da-border)' : undefined }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 12, padding: '12px 0' }}>
            <Dot index={i + 1} done={t.done} current={current} />
            <span style={{ flex: 1, minWidth: 0, display: 'flex', flexDirection: 'column', gap: 2 }}>
              <span style={{ fontSize: 15, fontWeight: current ? 700 : 400, color: t.done ? 'var(--da-text-muted)' : 'var(--da-text)', textDecoration: t.done ? 'line-through' : undefined }}>{step.title}</span>
              {!t.done && <span style={{ fontSize: 12, color: 'var(--da-text-muted)' }}>{step.desc}</span>}
            </span>
            {t.done
              ? <span style={{ fontSize: 12, color: 'var(--da-success)' }}>已完成</span>
              : expandable
                ? <button type="button" className={`da-btn da-btn-sm${current ? ' da-btn-primary' : ''}`} aria-expanded={open === 'data'}
                  onClick={() => setOpen(open === 'data' ? null : 'data')}>{open === 'data' ? '收起' : '选择方式'}</button>
                : <button type="button" className={`da-btn da-btn-sm${current ? ' da-btn-primary' : ' da-btn-ghost'}`} onClick={() => act(t.id)}>{step.cta}</button>}
          </div>
          {expandable && open === 'data' && <div style={{ padding: '0 0 16px 38px' }}><DataSourceChoices /></div>}
        </li>;
      })}
    </ol>
    <footer style={{ padding: '10px 20px 14px', display: 'flex', flexWrap: 'wrap', alignItems: 'center', gap: 12, fontSize: 12, color: 'var(--da-text-muted)' }}>
      <span>明天起这里会收起到侧栏底部，{status.daysLeft} 天后自动隐藏；随时可在“更多 › 帮助与引导”重新打开。</span>
      <span style={{ flex: 1 }} />
      <button type="button" className="da-btn da-btn-ghost da-btn-sm" onClick={() => void dismissOnboarding()}>不再显示</button>
    </footer>
  </section>;
}

const STARTER_FILLERS: { title: string; desc: string; path: string; icon: 'upload' | 'database' | 'book' }[] = [
  { title: '上传新数据', desc: '新建知识库，上传 Excel / CSV，建模发布后即可问数', path: UPLOAD_PATH, icon: 'upload' },
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
  return <section aria-label="从这些问题开始" style={{ width: '100%', maxWidth: 880, marginTop: 24, flexShrink: 0, display: 'flex', flexDirection: 'column', gap: 10, textAlign: 'left' }}>
    <div style={{ display: 'flex', alignItems: 'baseline', gap: 8, flexWrap: 'wrap' }}>
      <strong style={{ fontSize: 15 }}>从这些问题开始</strong>
      <span style={{ fontSize: 12, color: 'var(--da-text-muted)' }}>来自你能使用的知识库，点一下即选中知识库并填入问题</span>
    </div>
    <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(220px, 1fr))', gap: 12 }}>
      {starters.map(s => <button key={s.groupId} type="button" onClick={() => onPick(s)}
        style={{ textAlign: 'left', background: 'var(--da-surface)', border: '1px solid var(--da-border)', borderRadius: 12, padding: 14, display: 'flex', flexDirection: 'column', gap: 8, cursor: 'pointer', color: 'var(--da-text)', font: 'inherit' }}>
        <span style={{ fontSize: 12, color: 'var(--da-primary)', fontWeight: 600 }}>{s.groupName}{s.shared ? ' · 同事共享' : ''}</span>
        <span style={{ fontSize: 14, lineHeight: 1.6 }}>{s.question}</span>
      </button>)}
      {fillers.map(f => <button key={f.title} type="button" onClick={() => navigate(f.path)}
        style={{ textAlign: 'left', background: 'transparent', border: '1px dashed var(--da-border-strong)', borderRadius: 12, padding: 14, display: 'flex', flexDirection: 'column', gap: 8, cursor: 'pointer', color: 'var(--da-text)', font: 'inherit' }}>
        <span style={{ fontSize: 12, color: 'var(--da-text-muted)', fontWeight: 600, display: 'inline-flex', alignItems: 'center', gap: 4 }}><Icon name={f.icon} size="sm" />{f.title}</span>
        <span style={{ fontSize: 14, lineHeight: 1.6, color: 'var(--da-text-2)' }}>{f.desc}</span>
      </button>)}
    </div>
  </section>;
}

/** Shown on the home page when no published knowledge base can be queried yet. */
export function NoKnowledgeBaseNotice({ unpublished }: { unpublished: string[] }) {
  const navigate = useNavigate();
  return <section aria-label="还没有可以问数的知识库" style={{ width: '100%', maxWidth: 880, marginTop: 24, flexShrink: 0, background: 'var(--da-surface)', border: '1px dashed var(--da-border-strong)', borderRadius: 16, padding: '20px 24px', display: 'flex', flexDirection: 'column', gap: 12, textAlign: 'left' }}>
    <strong style={{ fontSize: 16 }}>还没有可以问数的知识库</strong>
    <span style={{ fontSize: 13, color: 'var(--da-text-2)', lineHeight: 1.7 }}>问数需要一个<strong>已发布</strong>的知识库。可以自己准备数据并建模，也可以请同事把知识库共享给你，共享后会直接出现在“选择知识库”里。未选择知识库时只能普通对话，回答不会查询数据。</span>
    {unpublished.length > 0
      ? <div style={{ display: 'flex', alignItems: 'center', gap: 10, flexWrap: 'wrap', fontSize: 13 }}>
        <span>“{unpublished.join('”“')}”还没有发布，完成建模后点击“发布”即可问数。</span>
        <button type="button" className="da-btn da-btn-primary da-btn-sm" onClick={() => navigate('/configure/modeling')}>去建模</button>
      </div>
      : <DataSourceChoices compact />}
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
