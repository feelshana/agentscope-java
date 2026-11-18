import { useEffect, useId, useRef, useState } from 'react';
import Icon, { IconName } from './Icon';

/** A dashboard-shaped mark distinguishes this assistant without changing the shared brand. */
export function DashboardAssistantMark({ small = false }: { small?: boolean }) {
  return <span className={`dashboard-assistant-mark${small ? ' is-small' : ''}`} aria-hidden="true"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.65" strokeLinecap="round" strokeLinejoin="round"><rect x="3.5" y="3.5" width="6.5" height="6.5" rx="1.6" /><rect x="13" y="3.5" width="7.5" height="4.5" rx="1.4" /><rect x="3.5" y="13" width="6.5" height="7.5" rx="1.6" /><path d="M13.5 20v-5.5M17 20v-8.5M20.5 20v-3.5" /></svg></span>;
}

export function DashboardMoreMenu({ canEdit, disabled, onInfo, onPermissions, versionAction }: {
  canEdit: boolean; disabled: boolean; onInfo: () => void; onPermissions: () => void; versionAction?: { label: string; onClick: () => void };
}) {
  const [open, setOpen] = useState(false);
  const root = useRef<HTMLDivElement>(null), trigger = useRef<HTMLButtonElement>(null), menu = useRef<HTMLDivElement>(null);
  const id = useId();
  useEffect(() => {
    if (!open) return;
    const outside = (event: PointerEvent) => { if (!root.current?.contains(event.target as Node)) setOpen(false); };
    const escape = (event: KeyboardEvent) => { if (event.key === 'Escape') { event.preventDefault(); setOpen(false); trigger.current?.focus(); } };
    const frame = requestAnimationFrame(() => menu.current?.querySelector<HTMLButtonElement>('button:not(:disabled)')?.focus());
    document.addEventListener('pointerdown', outside); document.addEventListener('keydown', escape);
    return () => { cancelAnimationFrame(frame); document.removeEventListener('pointerdown', outside); document.removeEventListener('keydown', escape); };
  }, [open]);
  const choose = (action: () => void) => { setOpen(false); trigger.current?.focus(); action(); };
  return <div className="dashboard-more" ref={root}>
    <button ref={trigger} type="button" className="dashboard-more-trigger dashboard-icon-button" aria-label="看板更多操作" title="更多操作" aria-haspopup="menu" aria-expanded={open} aria-controls={open ? id : undefined} onClick={() => setOpen(value => !value)} onKeyDown={event => { if (event.key === 'ArrowDown' || event.key === 'ArrowUp') { event.preventDefault(); setOpen(true); } }}><Icon name="moreHorizontal" /></button>
    {open && <div ref={menu} id={id} role="menu" aria-label="看板操作" className="dashboard-more-menu" onKeyDown={event => {
      if (event.key === 'Tab') { setOpen(false); return; }
      if (!['ArrowDown', 'ArrowUp', 'Home', 'End'].includes(event.key)) return;
      event.preventDefault(); const items = Array.from(menu.current?.querySelectorAll<HTMLButtonElement>('button:not(:disabled)') || []);
      const visible = items.filter(item => item.offsetParent !== null), index = visible.indexOf(document.activeElement as HTMLButtonElement);
      visible[event.key === 'Home' ? 0 : event.key === 'End' ? visible.length - 1 : (index + (event.key === 'ArrowDown' ? 1 : -1) + visible.length) % visible.length]?.focus();
    }}>
      <button type="button" role="menuitem" disabled={canEdit && disabled} onClick={() => choose(onInfo)}><Icon name={canEdit ? 'edit' : 'info'} size="sm" /><span>{canEdit ? '编辑信息' : '看板信息'}<small>{canEdit ? '名称与描述' : '描述与数据来源'}</small></span></button>
      <button type="button" role="menuitem" onClick={() => choose(onPermissions)}><Icon name="shield" size="sm" /><span>访问权限<small>查看成员访问范围</small></span></button>
      {versionAction && <button type="button" role="menuitem" className="dashboard-more-version" disabled={disabled} onClick={() => choose(versionAction.onClick)}><Icon name="link" size="sm" /><span>{versionAction.label}</span></button>}
    </div>}
  </div>;
}

const STARTERS: { title: string; detail: string; icon: IconName; prompt: string }[] = [
  { title: '业务总览', detail: '核心指标 · 趋势 · 排名', icon: 'chart', prompt: '基于当前知识库，生成一张业务总览看板，包含可用的核心指标、趋势和排名。先核实数据范围和指标口径，再选择合适的图表。' },
  { title: '趋势变化', detail: '按日、周或月看变化', icon: 'graph', prompt: '帮我生成一张趋势分析看板，先查看当前知识库有哪些可分析的时间字段和指标，再选择合适的日、周或月粒度，展示趋势与关键变化。' },
  { title: '结构对比', detail: '区域 · 渠道 · 类别', icon: 'sliders', prompt: '基于当前知识库可用的分类维度，生成一张结构对比看板。选择适合的区域、渠道或类别，对比核心指标的分布与占比；只使用已有数据。' },
  { title: 'Top 排名', detail: '找出表现最突出的对象', icon: 'list', prompt: '帮我生成一张排名分析看板，基于当前知识库选择有意义的指标与对象，展示Top排名、贡献占比和明细，并说明排序依据与统计范围。' },
  { title: '异常波动', detail: '发现变化 · 定位线索', icon: 'search', prompt: '基于当前知识库生成一张波动分析看板，展示关键指标随时间的变化，并按已有维度对比。仅在数据支持时标注异常，不把波动直接解释为因果。' },
  { title: '帮我选方向', detail: '从当前数据中寻找灵感', icon: 'compose', prompt: '先看看这个知识库的数据范围和可用指标，推荐几个有价值的看板方向，简要说明各自适合解决什么问题，暂时不用生成页面。' },
];

export function DashboardConversationStarters({ onPick, disabled }: { onPick: (text: string) => void; disabled: boolean }) {
  return <div className="dashboard-chat-welcome"><h2>从一个问题，开始一张看板</h2><p>选个方向，补充你的想法。</p><div className="dashboard-starter-grid">{STARTERS.map(item => <button type="button" key={item.title} disabled={disabled} onClick={() => onPick(item.prompt)}><span className="dashboard-starter-icon"><Icon name={item.icon} size="sm" /></span><strong>{item.title}</strong><small>{item.detail}</small></button>)}</div><span className="dashboard-starter-hint">点选填入输入框，修改后即可发送</span></div>;
}
