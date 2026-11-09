import { useEffect, useMemo, useRef, useState } from 'react';
import type { CSSProperties } from 'react';
import { ANALYSIS_NAMES } from '../api/chat';
import type { AnalysisMethod } from '../api/chat';
import Icon from './Icon';
import './AnalysisPanel.css';

/** spec102: analysis menu, parameter card and follow-up buttons of the query page. */

/** 'auto' means no analysis method: the turn is an ordinary query. */
export type MethodChoice = 'auto' | AnalysisMethod;

const METHODS: AnalysisMethod[] = ['attribution', 'anomaly', 'forecast'];

const HINTS: Record<AnalysisMethod, string> = {
  attribution: '比较两个时间段，找出变化最大的维度值',
  anomaly: '检查每天或每小时是否有异常波动',
  forecast: '基于历史走势预测未来，给出预测区间',
};

export const METHOD_PLACEHOLDERS: Record<MethodChoice, string | null> = {
  auto: null,
  attribution: '例如：10 月 1–7 日比 9 月 24–30 日访问次数为什么下降',
  anomaly: '例如：9 月 20 日以来每天访问量有没有异常',
  forecast: '例如：预测未来 7 天访问次数',
};

const trigger: CSSProperties = {
  display: 'inline-flex', alignItems: 'center', gap: 'var(--da-analysis-gap, 6px)', height: 36,
  padding: '0 var(--da-analysis-padding, 10px)', borderRadius: 8,
  border: '1px solid var(--da-border)', background: 'var(--da-surface)',
  color: 'var(--da-text)', fontSize: 13, cursor: 'pointer',
};

/**
 * The composer's "分析" menu: hidden until opened, so a plain question stays a plain query. A
 * chosen method shows as a removable chip; methods are greyed out with the reason when unusable.
 */
export function AnalysisMenu({ value, onChange, disabledReason, disabled }: {
  value: MethodChoice;
  onChange: (m: MethodChoice) => void;
  /** Non-null greys out every method (no time field, several knowledge bases…). */
  disabledReason: string | null;
  disabled?: boolean;
}) {
  const [open, setOpen] = useState(false);
  const root = useRef<HTMLDivElement>(null);
  useEffect(() => {
    if (!open) return;
    const close = (e: MouseEvent) => { if (!root.current?.contains(e.target as Node)) setOpen(false); };
    document.addEventListener('mousedown', close);
    return () => document.removeEventListener('mousedown', close);
  }, [open]);
  const chosen = value === 'auto' ? null : value;
  return (
    <div ref={root} className="da-analysis-menu"
      onKeyDown={e => { if (e.key === 'Escape') setOpen(false); }}>
      {chosen ? (
        <span style={{ ...trigger, cursor: 'default', borderColor: 'var(--da-primary)', background: 'var(--da-primary-soft)', color: 'var(--da-primary)', paddingRight: 4 }}>
          <button type="button" disabled={disabled} onClick={() => setOpen(o => !o)} aria-haspopup="menu" aria-expanded={open}
            style={{ border: 'none', background: 'none', color: 'inherit', font: 'inherit', fontWeight: 600, cursor: 'pointer', display: 'inline-flex', alignItems: 'center', gap: 6, padding: 0 }}>
            <Icon name="chart" size="sm" />{ANALYSIS_NAMES[chosen]}
          </button>
          <button type="button" aria-label="取消分析方法" disabled={disabled} onClick={() => onChange('auto')}
            style={{ border: 'none', background: 'none', color: 'inherit', cursor: 'pointer', width: 24, height: 24, borderRadius: 8, fontSize: 15, lineHeight: '24px' }}>×</button>
        </span>
      ) : (
        <button type="button" disabled={disabled} onClick={() => setOpen(o => !o)} aria-haspopup="menu" aria-expanded={open} style={trigger}
          title="指标归因、异常检测、趋势预测">
          <Icon name="chart" size="sm" />分析<Icon name="chevron" size="sm" />
        </button>
      )}
      {open && (
        <div role="menu" aria-label="分析方法" style={{ position: 'absolute', bottom: 'calc(100% + 8px)', left: 0, zIndex: 20, width: 300, background: 'var(--da-surface)', border: '1px solid var(--da-border)', borderRadius: 12, boxShadow: '0 8px 24px rgba(20,24,31,0.12)', padding: 6 }}>
          <div style={{ fontSize: 12, color: 'var(--da-text-muted)', padding: '6px 10px 4px' }}>
            {disabledReason ? `暂不可用：${disabledReason}` : '选择后提问，先确认参数再分析'}
          </div>
          {METHODS.map(m => {
            const off = !!disabledReason;
            return (
              <button key={m} type="button" role="menuitemradio" aria-checked={value === m} aria-disabled={off}
                onClick={() => { if (!off) { onChange(m); setOpen(false); } }}
                style={{ display: 'flex', flexDirection: 'column', alignItems: 'flex-start', gap: 2, width: '100%', textAlign: 'left', border: 'none', borderRadius: 8, padding: '8px 10px', cursor: off ? 'not-allowed' : 'pointer', background: value === m ? 'var(--da-primary-soft)' : 'transparent', opacity: off ? 0.55 : 1, font: 'inherit' }}>
                <span style={{ fontSize: 14, fontWeight: 600, color: 'var(--da-text)' }}>{ANALYSIS_NAMES[m]}</span>
                <span style={{ fontSize: 12, color: 'var(--da-text-muted)' }}>{HINTS[m]}</span>
              </button>
            );
          })}
        </div>
      )}
    </div>
  );
}

interface Proposal {
  method?: AnalysisMethod;
  metric?: string;
  metric_note?: string;
  current_period?: string;
  base_period?: string;
  granularity?: string;
  dimensions?: string[];
  candidate_dimensions?: string[];
  depth?: number;
  horizon?: number;
  filters?: string[];
}

function parseProposal(input?: string): Proposal | null {
  if (!input) return null;
  try {
    const value = JSON.parse(input) as unknown;
    return value && typeof value === 'object' ? value as Proposal : null;
  } catch {
    return null;
  }
}

/** "2026-08-01 至 2026-10-31" → 92 (inclusive); null when the text is not a date range. */
export function periodDays(text: string): number | null {
  const m = text.match(/(\d{4}-\d{2}-\d{2})\s*(?:至|到|~|-|—)\s*(\d{4}-\d{2}-\d{2})/);
  if (!m) return null;
  const from = Date.parse(`${m[1]}T00:00:00Z`);
  const to = Date.parse(`${m[2]}T00:00:00Z`);
  if (Number.isNaN(from) || Number.isNaN(to) || to < from) return null;
  return Math.round((to - from) / 86400000) + 1;
}

const ICONS: Record<AnalysisMethod, 'chart' | 'warn' | 'arrowUp'> = { attribution: 'chart', anomaly: 'warn', forecast: 'arrowUp' };

/**
 * The editable parameter card of an analysis turn, rendered from the assistant's propose_analysis
 * call. Nothing runs until the user starts the analysis here.
 */
export function AnalysisParamsCard({ input, disabled, onStart, onCancel }: {
  input?: string;
  disabled: boolean;
  onStart: (method: AnalysisMethod, params: Record<string, unknown>) => void;
  onCancel: () => void;
}) {
  const proposal = useMemo(() => parseProposal(input), [input]);
  const method = proposal?.method && proposal.method in ANALYSIS_NAMES ? proposal.method : null;
  const candidates = useMemo(() => {
    const all = [...(proposal?.dimensions ?? []), ...(proposal?.candidate_dimensions ?? [])];
    return [...new Set(all.filter(Boolean))].slice(0, 8);
  }, [proposal]);
  const [metric, setMetric] = useState(proposal?.metric ?? '');
  const [current, setCurrent] = useState(proposal?.current_period ?? '');
  const [base, setBase] = useState(proposal?.base_period ?? '');
  const [granularity, setGranularity] = useState(proposal?.granularity ?? 'day');
  // One dimension by default; more multiply the result rows, so adding them is the user's choice.
  const [dims, setDims] = useState<string[]>((proposal?.dimensions ?? []).slice(0, 1));
  const [depth, setDepth] = useState(proposal?.depth === 2 ? 2 : 1);
  const [horizon, setHorizon] = useState(proposal?.horizon ?? 7);
  const [filters, setFilters] = useState<string[]>(proposal?.filters ?? []);
  const [started, setStarted] = useState(false);
  if (!method) return null;
  const locked = disabled || started;
  const missing = !metric.trim() || !current.trim()
    || (method === 'attribution' && (!base.trim() || dims.length === 0));

  const start = () => {
    if (locked || missing) return;
    setStarted(true);
    const params: Record<string, unknown> = { metric: metric.trim(), metric_note: proposal?.metric_note, current_period: current.trim(), filters };
    if (method === 'attribution') Object.assign(params, { base_period: base.trim(), dimensions: dims, depth: dims.length > 1 ? depth : 1 });
    else Object.assign(params, { granularity });
    if (method === 'forecast') params.horizon = horizon;
    onStart(method, params);
  };
  const toggleDim = (d: string) => setDims(prev => prev.includes(d) ? prev.filter(x => x !== d) : prev.length >= 4 ? prev : [...prev, d]);
  const toggleFilter = (f: string) => setFilters(prev => prev.includes(f) ? prev.filter(x => x !== f) : [...prev, f]);
  const grains = method === 'anomaly' ? [['day', '按天'], ['hour', '按小时']] : [['day', '按天'], ['week', '按周'], ['month', '按月']];
  const attribution = method === 'attribution';
  const days = (text: string) => { const n = periodDays(text); return n ? `共 ${n} 天` : ''; };
  let step = 0;
  const head = (title: string, aside?: string) => <div className="ap-section-head">
    <span className="ap-step">{++step}</span>{title}{aside && <span className="ap-aside">{aside}</span>}
  </div>;

  return (
    <section aria-label="确认分析参数" className="ap-card">
      <header className="ap-head">
        <span className="ap-badge" aria-hidden="true"><Icon name={ICONS[method]} size="sm" /></span>
        <div className="ap-title">
          <strong>{ANALYSIS_NAMES[method]} · 确认分析参数</strong>
          <span>已根据问题和语义模型预填，确认或修改后开始分析</span>
        </div>
        <span className={`ap-state ${started ? 'ap-state-done' : ''}`}>{started ? '已提交' : '待确认'}</span>
      </header>
      <fieldset disabled={locked} className="ap-body">
        <div className="ap-section">
          {head('分析哪个指标')}
          <input className="ap-input" aria-label="分析指标" value={metric} onChange={e => setMetric(e.target.value)} title={metric} />
          {proposal?.metric_note && <div className="ap-note"><Icon name="info" size="sm" />{proposal.metric_note}</div>}
        </div>

        {attribution ? <div className="ap-section">
          {head('比较哪两段时间')}
          <div className="ap-periods">
            <label className="ap-period ap-period-current">
              <span className="ap-period-label"><b>当前期</b>{days(current)}</span>
              <input className="ap-input" value={current} onChange={e => setCurrent(e.target.value)} placeholder="YYYY-MM-DD 至 YYYY-MM-DD" />
            </label>
            <span className="ap-vs">对比</span>
            <label className="ap-period">
              <span className="ap-period-label"><b>对比期</b>{days(base)}</span>
              <input className="ap-input" value={base} onChange={e => setBase(e.target.value)} placeholder="YYYY-MM-DD 至 YYYY-MM-DD" />
            </label>
          </div>
        </div> : <div className="ap-section">
          {head('分析哪段时间')}
          <div className="ap-grid">
            <label className="ap-field">时间范围{days(current) && `（${days(current)}）`}
              <input className="ap-input" value={current} onChange={e => setCurrent(e.target.value)} placeholder="YYYY-MM-DD 至 YYYY-MM-DD" />
            </label>
            <label className="ap-field">时间粒度
              <select className="ap-input" value={granularity} onChange={e => setGranularity(e.target.value)}>
                {grains.map(([v, t]) => <option key={v} value={v}>{t}</option>)}
              </select>
            </label>
            {method === 'forecast' && <label className="ap-field">预测期数
              <input className="ap-input" type="number" min={1} max={60} value={horizon} onChange={e => setHorizon(Math.max(1, Math.min(60, Number(e.target.value) || 1)))} />
            </label>}
          </div>
        </div>}

        {attribution && <div className="ap-section">
          {head('从哪些维度找原因', `已选 ${dims.length} / 4`)}
          <div className="ap-chips" role="group" aria-label="归因维度">
            {candidates.map(d => {
              const on = dims.includes(d);
              return <button key={d} type="button" className={`ap-chip ${on ? 'ap-chip-on' : ''}`} aria-pressed={on}
                disabled={locked || (!on && dims.length >= 4)} onClick={() => toggleDim(d)}>
                {on && <Icon name="check" size="sm" />}{d}
              </button>;
            })}
            {candidates.length === 0 && <span className="ap-note">助手没有提供候选维度，请取消后在问题中说明按什么维度分析</span>}
          </div>
          <div className="ap-depth">
            <span>下钻层级</span>
            <span className="ap-segment" role="group" aria-label="下钻层级">
              {[1, 2].map(n => <button key={n} type="button" aria-pressed={(dims.length > 1 ? depth : 1) === n}
                disabled={locked || (n === 2 && dims.length < 2)} onClick={() => setDepth(n)}>{n} 层{n === 1 ? '（推荐）' : ''}</button>)}
            </span>
            {dims.length < 2 && <span className="ap-note" style={{ marginTop: 0 }}>选 2 个以上维度时可下钻 2 层</span>}
          </div>
        </div>}

        {(proposal?.filters?.length ?? 0) > 0 && <div className="ap-section">
          {head('默认过滤', '来自知识库业务规则')}
          <div className="ap-filters">
            {proposal!.filters!.map(f => (
              <label key={f} className="ap-filter">
                <input type="checkbox" checked={filters.includes(f)} onChange={() => toggleFilter(f)} /> {f}
              </label>
            ))}
          </div>
        </div>}
      </fieldset>
      <footer className="ap-foot">
        <button type="button" className="da-btn da-btn-primary" disabled={locked || missing} onClick={start}>
          <Icon name={ICONS[method]} size="sm" />{started ? '已开始分析' : '开始分析'}</button>
        <button type="button" className="da-btn" disabled={locked} onClick={() => { setStarted(true); onCancel(); }}>取消，按普通问数回答</button>
        {missing && !started && <span className="ap-missing">{attribution && dims.length === 0 ? '请至少选择 1 个归因维度' : '请填写指标和时间'}</span>}
        <span className="ap-eta"><Icon name="clock" size="sm" />预计 20–60 秒，取数后在沙箱中运行分析脚本</span>
      </footer>
    </section>
  );
}

const FOLLOW_UP_LABELS: Record<AnalysisMethod, string> = {
  attribution: '为什么变化？（指标归因）',
  anomaly: '有没有异常？（异常检测）',
  forecast: '未来走势？（趋势预测）',
};

/** "继续分析" buttons under a query answer; which ones show is decided by analysisSuggestions. */
export function AnalysisFollowUps({ methods, disabled, onPick }: {
  methods: AnalysisMethod[];
  disabled: boolean;
  onPick: (m: AnalysisMethod) => void;
}) {
  if (methods.length === 0) return null;
  return (
    <div style={{ display: 'flex', flexWrap: 'wrap', alignItems: 'center', gap: 8, marginTop: 12, paddingTop: 10, borderTop: '1px dashed var(--da-border)' }}>
      <span style={{ fontSize: 12, color: 'var(--da-text-muted)' }}>继续分析</span>
      {methods.map(m => (
        <button key={m} type="button" disabled={disabled} onClick={() => onPick(m)}
          style={{ fontSize: 13, padding: '6px 12px', borderRadius: 999, border: '1px solid var(--da-primary-line)', background: 'var(--da-primary-soft)', color: 'var(--da-primary)', cursor: disabled ? 'not-allowed' : 'pointer' }}>
          {FOLLOW_UP_LABELS[m]}
        </button>
      ))}
    </div>
  );
}
