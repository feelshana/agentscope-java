import { useEffect, useMemo, useRef, useState } from 'react';
import type { CSSProperties } from 'react';
import { ANALYSIS_NAMES } from '../api/chat';
import type { AnalysisMethod } from '../api/chat';
import Icon from './Icon';

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
  display: 'inline-flex', alignItems: 'center', gap: 6, height: 36, padding: '0 10px', borderRadius: 10,
  border: '1px solid var(--da-border, #E3E6EB)', background: 'var(--da-surface, #FFFFFF)',
  color: 'var(--da-text, #3D434C)', fontSize: 13, cursor: 'pointer',
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
    <div ref={root} style={{ position: 'relative', display: 'inline-flex' }}
      onKeyDown={e => { if (e.key === 'Escape') setOpen(false); }}>
      {chosen ? (
        <span style={{ ...trigger, cursor: 'default', borderColor: 'var(--da-primary, #4B3FD0)', background: 'var(--da-primary-soft, #F1EFFE)', color: 'var(--da-primary-strong, #3A2FB0)', paddingRight: 4 }}>
          <button type="button" disabled={disabled} onClick={() => setOpen(o => !o)} aria-haspopup="menu" aria-expanded={open}
            style={{ border: 'none', background: 'none', color: 'inherit', font: 'inherit', fontWeight: 600, cursor: 'pointer', display: 'inline-flex', alignItems: 'center', gap: 6, padding: 0 }}>
            <Icon name="chart" size="sm" />{ANALYSIS_NAMES[chosen]}
          </button>
          <button type="button" aria-label="取消分析方法" disabled={disabled} onClick={() => onChange('auto')}
            style={{ border: 'none', background: 'none', color: 'inherit', cursor: 'pointer', width: 24, height: 24, borderRadius: 6, fontSize: 15, lineHeight: '24px' }}>×</button>
        </span>
      ) : (
        <button type="button" disabled={disabled} onClick={() => setOpen(o => !o)} aria-haspopup="menu" aria-expanded={open} style={trigger}
          title="指标归因、异常检测、趋势预测">
          <Icon name="chart" size="sm" />分析<Icon name="chevron" size="sm" />
        </button>
      )}
      {open && (
        <div role="menu" aria-label="分析方法" style={{ position: 'absolute', bottom: 'calc(100% + 8px)', left: 0, zIndex: 20, width: 300, background: 'var(--da-surface, #FFFFFF)', border: '1px solid var(--da-border, #E3E6EB)', borderRadius: 12, boxShadow: '0 8px 24px rgba(20,24,31,0.12)', padding: 6 }}>
          <div style={{ fontSize: 12, color: 'var(--da-text-muted, #5C6370)', padding: '6px 10px 4px' }}>
            {disabledReason ? `暂不可用：${disabledReason}` : '选择后提问，先确认参数再分析'}
          </div>
          {METHODS.map(m => {
            const off = !!disabledReason;
            return (
              <button key={m} type="button" role="menuitemradio" aria-checked={value === m} aria-disabled={off}
                onClick={() => { if (!off) { onChange(m); setOpen(false); } }}
                style={{ display: 'flex', flexDirection: 'column', alignItems: 'flex-start', gap: 2, width: '100%', textAlign: 'left', border: 'none', borderRadius: 8, padding: '8px 10px', cursor: off ? 'not-allowed' : 'pointer', background: value === m ? 'var(--da-primary-soft, #F1EFFE)' : 'transparent', opacity: off ? 0.55 : 1, font: 'inherit' }}>
                <span style={{ fontSize: 14, fontWeight: 600, color: 'var(--da-text, #1F2329)' }}>{ANALYSIS_NAMES[m]}</span>
                <span style={{ fontSize: 12, color: 'var(--da-text-muted, #5C6370)' }}>{HINTS[m]}</span>
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

const field: CSSProperties = { display: 'flex', flexDirection: 'column', gap: 6, minWidth: 0 };
const label: CSSProperties = { fontSize: 13, fontWeight: 600, color: 'var(--da-text, #3D434C)' };
const control: CSSProperties = {
  height: 40, border: '1px solid var(--da-border, #D5D9E0)', borderRadius: 8, padding: '0 10px',
  fontSize: 14, background: 'var(--da-surface, #FFFFFF)', color: 'var(--da-text, #1F2329)', minWidth: 0,
};

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
  const [dims, setDims] = useState<string[]>((proposal?.dimensions ?? []).slice(0, 4));
  const [depth, setDepth] = useState(proposal?.depth === 1 ? 1 : 2);
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
    if (method === 'attribution') Object.assign(params, { base_period: base.trim(), dimensions: dims, depth });
    else Object.assign(params, { granularity });
    if (method === 'forecast') params.horizon = horizon;
    onStart(method, params);
  };
  const toggleDim = (d: string) => setDims(prev => prev.includes(d) ? prev.filter(x => x !== d) : prev.length >= 4 ? prev : [...prev, d]);
  const toggleFilter = (f: string) => setFilters(prev => prev.includes(f) ? prev.filter(x => x !== f) : [...prev, f]);
  const grains = method === 'anomaly' ? [['day', '按天'], ['hour', '按小时']] : [['day', '按天'], ['week', '按周'], ['month', '按月']];

  return (
    <section aria-label="确认分析参数" style={{ border: '1px solid var(--da-border, #E3E6EB)', borderRadius: 12, overflow: 'hidden', background: 'var(--da-surface, #FFFFFF)', marginTop: 12 }}>
      <header style={{ padding: '14px 18px', borderBottom: '1px solid var(--da-border-soft, #EEF0F3)', display: 'flex', flexWrap: 'wrap', alignItems: 'baseline', gap: 10 }}>
        <strong style={{ fontSize: 15 }}>确认分析参数 · {ANALYSIS_NAMES[method]}</strong>
        <span style={{ fontSize: 12, color: 'var(--da-text-muted, #5C6370)' }}>已根据问题和语义模型预填，可修改后开始</span>
      </header>
      <fieldset disabled={locked} style={{ border: 'none', margin: 0, padding: '16px 18px', display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(240px, 1fr))', gap: '14px 20px' }}>
        <label style={field}><span style={label}>分析指标</span>
          <input style={control} value={metric} onChange={e => setMetric(e.target.value)} />
          {proposal?.metric_note && <span style={{ fontSize: 12, color: 'var(--da-text-muted, #5C6370)' }}>{proposal.metric_note}</span>}
        </label>
        <label style={field}><span style={label}>{method === 'attribution' ? '当前期' : '时间范围'}</span>
          <input style={control} value={current} onChange={e => setCurrent(e.target.value)} placeholder="YYYY-MM-DD 至 YYYY-MM-DD" />
        </label>
        {method === 'attribution' && <>
          <label style={field}><span style={label}>对比期</span>
            <input style={control} value={base} onChange={e => setBase(e.target.value)} placeholder="YYYY-MM-DD 至 YYYY-MM-DD" />
          </label>
          <label style={field}><span style={label}>下钻层级</span>
            <select style={control} value={depth} onChange={e => setDepth(Number(e.target.value))}>
              <option value={2}>2 层（推荐）</option><option value={1}>1 层</option>
            </select>
          </label>
          <div style={{ ...field, gridColumn: '1 / -1' }}>
            <span style={label}>归因维度（最多 4 个）</span>
            <div style={{ display: 'flex', flexWrap: 'wrap', gap: 8 }}>
              {candidates.map(d => (
                <label key={d} style={{ display: 'flex', alignItems: 'center', gap: 6, fontSize: 14, padding: '6px 10px', borderRadius: 8, border: `1px solid ${dims.includes(d) ? 'var(--da-primary, #4B3FD0)' : 'var(--da-border, #D5D9E0)'}` }}>
                  <input type="checkbox" checked={dims.includes(d)} onChange={() => toggleDim(d)} /> {d}
                </label>
              ))}
              {candidates.length === 0 && <span style={{ fontSize: 13, color: 'var(--da-text-muted, #5C6370)' }}>助手没有提供候选维度，请取消后在问题中说明按什么维度分析</span>}
            </div>
          </div>
        </>}
        {method !== 'attribution' && (
          <label style={field}><span style={label}>时间粒度</span>
            <select style={control} value={granularity} onChange={e => setGranularity(e.target.value)}>
              {grains.map(([v, t]) => <option key={v} value={v}>{t}</option>)}
            </select>
          </label>
        )}
        {method === 'forecast' && (
          <label style={field}><span style={label}>预测期数</span>
            <input style={control} type="number" min={1} max={60} value={horizon} onChange={e => setHorizon(Math.max(1, Math.min(60, Number(e.target.value) || 1)))} />
          </label>
        )}
        {(proposal?.filters?.length ?? 0) > 0 && (
          <div style={{ ...field, gridColumn: '1 / -1', background: 'var(--da-surface-muted, #F8F9FB)', borderRadius: 8, padding: '10px 12px' }}>
            <span style={label}>默认过滤（来自知识库业务规则）</span>
            {proposal!.filters!.map(f => (
              <label key={f} style={{ display: 'flex', alignItems: 'center', gap: 8, fontSize: 14 }}>
                <input type="checkbox" checked={filters.includes(f)} onChange={() => toggleFilter(f)} /> {f}
              </label>
            ))}
          </div>
        )}
      </fieldset>
      <footer style={{ padding: '12px 18px', borderTop: '1px solid var(--da-border-soft, #EEF0F3)', display: 'flex', flexWrap: 'wrap', alignItems: 'center', gap: 10 }}>
        <button type="button" className="da-btn da-btn-primary" disabled={locked || missing} onClick={start}>{started ? '已开始分析' : '开始分析'}</button>
        <button type="button" className="da-btn" disabled={locked} onClick={() => { setStarted(true); onCancel(); }}>取消，按普通问数回答</button>
        <span style={{ fontSize: 12, color: 'var(--da-text-muted, #5C6370)' }}>预计 20–60 秒：取数后在沙箱中运行分析脚本</span>
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
    <div style={{ display: 'flex', flexWrap: 'wrap', alignItems: 'center', gap: 8, marginTop: 12, paddingTop: 10, borderTop: '1px dashed var(--da-border, #E3E6EB)' }}>
      <span style={{ fontSize: 12, color: 'var(--da-text-muted, #5C6370)' }}>继续分析</span>
      {methods.map(m => (
        <button key={m} type="button" disabled={disabled} onClick={() => onPick(m)}
          style={{ fontSize: 13, padding: '6px 12px', borderRadius: 999, border: '1px solid var(--da-primary-soft-border, #C9C3F7)', background: 'var(--da-primary-soft, #F7F6FE)', color: 'var(--da-primary-strong, #3A2FB0)', cursor: disabled ? 'not-allowed' : 'pointer' }}>
          {FOLLOW_UP_LABELS[m]}
        </button>
      ))}
    </div>
  );
}
