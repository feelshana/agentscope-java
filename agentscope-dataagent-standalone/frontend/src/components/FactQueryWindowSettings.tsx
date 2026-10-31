import { factWindowSetup } from '../utils/factWindowSetup';
import { useEffect, useRef, useState } from 'react';
import type { CSSProperties } from 'react';
import { getFactQueryWindowCandidates, saveFactQueryWindow, confirmTableRole } from '../api/modelingWorkflow';
import type { FactQueryWindowCandidate } from '../api/modelingWorkflow';

type Draft = { mode: 'LIMITED' | 'UNLIMITED'; days: number; timeColumn: string };
const message = (error: unknown) => error instanceof Error ? error.message : '时间范围配置失败';
const DAYS = [7, 14, 31, 90];

export function FactQueryWindowSettings({ groupId, automatic = false, onReady, hideButton = false, openSignal = 0 }: {
  groupId: string;
  automatic?: boolean;
  onReady?: (ready: boolean) => void;
  /** The page shows its own trigger (e.g. in its header) and bumps {@code openSignal}. */
  hideButton?: boolean;
  openSignal?: number;
}) {
  const [tables, setTables] = useState<FactQueryWindowCandidate[]>([]);
  const [unknown, setUnknown] = useState<FactQueryWindowCandidate[]>([]);
  const [draft, setDraft] = useState<Record<string, Draft>>({});
  const [open, setOpen] = useState(false);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const generation = useRef(0);

  useEffect(() => {
    generation.current++;
    setTables([]); setUnknown([]); setOpen(false); setError(''); setBusy(false);
    if (automatic) { onReady?.(false); void load(true); }
    return () => { generation.current++; };
  }, [groupId, automatic]);

  useEffect(() => { if (openSignal > 0) void load(false); }, [openSignal]); // eslint-disable-line react-hooks/exhaustive-deps

  async function load(pendingOnly = automatic) {
    const current = ++generation.current;
    setBusy(true); setError('');
    try {
      const all = await getFactQueryWindowCandidates(groupId);
      const setup = factWindowSetup(all, pendingOnly);
      const candidates = setup.facts;
      if (current !== generation.current) return;
      setTables(candidates);
      setUnknown(setup.unknown);
      setDraft(Object.fromEntries(candidates.map(table => [table.datasetId, {
        mode: table.mode || 'LIMITED', days: table.windowDays || 31,
        timeColumn: table.timeColumn || table.temporalColumns[0] || '',
      }])));
      setOpen(candidates.length > 0);
      if (pendingOnly) onReady?.(setup.ready);
    } catch (e) { if (current === generation.current) setError(message(e)); }
    finally { if (current === generation.current) setBusy(false); }
  }

  async function save() {
    const current = generation.current;
    setBusy(true); setError('');
    try {
      for (const table of tables) {
        if (current !== generation.current) return;
        const value = draft[table.datasetId];
        await saveFactQueryWindow(groupId, {
          datasetId: table.datasetId, mode: value.mode, timeColumn: value.timeColumn,
          days: value.mode === 'LIMITED' ? value.days : undefined,
        });
      }
      if (current === generation.current) {
        setOpen(false);
        if (automatic) await load(true);
      }
    } catch (e) { if (current === generation.current) setError(message(e)); }
    finally { if (current === generation.current) setBusy(false); }
  }

  async function setRole(datasetId: string, role: 'FACT' | 'DIMENSION') {
    const current = generation.current;
    setBusy(true); setError('');
    try {
      await confirmTableRole(groupId, datasetId, role);
      if (current === generation.current) await load(automatic);
    } catch (e) { if (current === generation.current) setError(message(e)); }
    finally { if (current === generation.current) setBusy(false); }
  }

  const chip = (active: boolean): CSSProperties => ({
    padding: '4px 12px', borderRadius: 999, fontSize: 13, cursor: busy ? 'default' : 'pointer',
    border: `1px solid ${active ? 'var(--da-primary)' : 'var(--da-border)'}`,
    background: active ? 'color-mix(in srgb, var(--da-primary) 10%, var(--da-surface))' : 'var(--da-surface)',
    color: active ? 'var(--da-primary)' : 'var(--da-text)', fontWeight: active ? 600 : 400,
  });
  const label: CSSProperties = { fontSize: 12, color: 'var(--da-text-muted)', marginBottom: 6 };

  return <div>
    {!hideButton && <button className="da-btn da-btn-sm" disabled={busy} onClick={() => void load()}>事实表时间范围</button>}
    {error && !open && <div role="alert" className="da-small" style={{ color: 'var(--da-danger)' }}>{error}</div>}
    {unknown.length > 0 && <div className="da-card" style={{ marginTop: 8 }}>
      <div style={{ fontWeight: 600, marginBottom: 4 }}>请先确认表的角色</div>
      <div className="da-small" style={{ marginBottom: 8 }}>事实表（如访问明细、订单）需要配置查询时间范围；维度表（如用户、项目）不需要。</div>
      {unknown.map(table => <div key={table.datasetId} style={{ display: 'flex', alignItems: 'center', gap: 8, padding: '6px 0', borderTop: '1px solid var(--da-border)' }}>
        <code style={{ flex: 1 }}>{table.tableName}</code>
        <button className="da-btn da-btn-sm" disabled={busy} onClick={() => void setRole(table.datasetId, 'FACT')}>事实表</button>
        <button className="da-btn da-btn-sm" disabled={busy} onClick={() => void setRole(table.datasetId, 'DIMENSION')}>维度表</button>
      </div>)}
    </div>}
    {open && <div className="da-modal-overlay" onClick={() => { if (!busy) setOpen(false); }}>
      <div role="dialog" aria-modal="true" aria-label="事实表查询时间范围" className="da-modal-shell" data-tour="window-modal"
        style={{ width: 'min(640px, 94vw)', maxHeight: '86vh', display: 'flex', flexDirection: 'column' }}
        onClick={e => e.stopPropagation()}>
        <div className="da-modal-head">
          <div className="da-modal-title">确认事实表的查询时间范围</div>
          <div className="da-small" style={{ marginTop: 6 }}>
            {automatic ? '采样已完成。' : ''}为大表限定单次查询可覆盖的天数，避免全表扫描拖慢数据库。保存后立即生效，不需要发布。
          </div>
        </div>
        <div className="da-modal-body" style={{ overflowY: 'auto', display: 'flex', flexDirection: 'column', gap: 12 }}>
          {tables.map(table => {
            const value = draft[table.datasetId];
            const update = (change: Partial<Draft>) => setDraft(previous => ({
              ...previous, [table.datasetId]: { ...previous[table.datasetId], ...change },
            }));
            return <section key={table.datasetId} style={{ border: '1px solid var(--da-border)', borderRadius: 10, padding: '12px 14px' }}>
              <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 12 }}>
                <code style={{ fontSize: 14, fontWeight: 600 }}>{table.tableName}</code>
                <span className="da-badge">事实表</span>
                <span style={{ flex: 1 }} />
                <button type="button" disabled={busy} onClick={() => void setRole(table.datasetId, 'DIMENSION')}
                  style={{ background: 'none', border: 'none', color: 'var(--da-text-muted)', fontSize: 12, cursor: 'pointer' }}>不是事实表？标为维度表</button>
              </div>
              <div style={{ display: 'grid', gridTemplateColumns: 'minmax(0, 1fr) minmax(0, 1.5fr)', gap: 16 }}>
                <div>
                  <div style={label}>按哪个时间字段限定</div>
                  <select className="da-input" disabled={busy} value={value.timeColumn} onChange={e => update({ timeColumn: e.target.value })}
                    style={{ width: '100%', boxSizing: 'border-box' }}>
                    {table.temporalColumns.map(column => <option key={column} value={column}>{column}</option>)}
                  </select>
                </div>
                <div>
                  <div style={label}>单次查询最多覆盖</div>
                  <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap' }}>
                    {DAYS.map(d => <button key={d} type="button" disabled={busy} style={chip(value.mode === 'LIMITED' && value.days === d)}
                      onClick={() => update({ mode: 'LIMITED', days: d })}>{d} 天</button>)}
                    <button type="button" disabled={busy} style={chip(value.mode === 'UNLIMITED')}
                      onClick={() => update({ mode: 'UNLIMITED' })}>不限制</button>
                  </div>
                </div>
              </div>
              <div className="da-small" style={{ marginTop: 10 }}>
                {value.mode === 'LIMITED'
                  ? `问数时 ${value.timeColumn || '时间字段'} 的范围不能超过 ${value.days} 天，超出时会请用户缩小范围。`
                  : '不限制查询范围，适合数据量较小的表。'}
              </div>
            </section>;
          })}
          {error && <div role="alert" className="da-small" style={{ color: 'var(--da-danger)' }}>{error}</div>}
        </div>
        <div className="da-modal-foot" style={{ display: 'flex', justifyContent: 'flex-end', gap: 8 }}>
          <button className="da-btn" disabled={busy} onClick={() => setOpen(false)}>稍后配置</button>
          <button className="da-btn da-btn-primary" disabled={busy || !tables.length} onClick={() => void save()}>{busy ? '保存中…' : '保存'}</button>
        </div>
      </div>
    </div>}
  </div>;
}
