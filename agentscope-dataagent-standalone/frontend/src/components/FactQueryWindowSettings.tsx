import { factWindowSetup } from '../utils/factWindowSetup';
import { useEffect, useRef, useState } from 'react';
import { getFactQueryWindowCandidates, saveFactQueryWindow, confirmTableRole } from '../api/modelingWorkflow';
import type { FactQueryWindowCandidate } from '../api/modelingWorkflow';

type Draft = { mode: 'LIMITED' | 'UNLIMITED'; days: number; timeColumn: string };
const message = (error: unknown) => error instanceof Error ? error.message : '时间范围配置失败';

export function FactQueryWindowSettings({ groupId, automatic = false, onReady }: {
  groupId: string;
  automatic?: boolean;
  onReady?: (ready: boolean) => void;
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

  return <div>
    <button className="da-btn" disabled={busy} onClick={() => void load()}>事实表时间范围配置</button>
    {error && <div role="alert">{error}</div>}
    {unknown.length > 0 && <div className="da-card">
      <p>以下表的角色尚未确定，请先确认。维度表不会弹出时间配置。</p>
      {unknown.map(table => <div key={table.datasetId}>
        <strong>{table.tableName}</strong>
        <button className="da-btn" disabled={busy} onClick={() => void setRole(table.datasetId, 'FACT')}>事实表</button>
        <button className="da-btn" disabled={busy} onClick={() => void setRole(table.datasetId, 'DIMENSION')}>维度表</button>
      </div>)}
    </div>}
    {open && <div role="dialog" aria-modal="true" aria-label="事实表时间范围配置" className="da-card"
      style={{ position: 'fixed', inset: '12% 18%', zIndex: 20, overflowY: 'auto', boxShadow: '0 12px 40px #0008' }}>
      <h2>事实表时间范围配置</h2>
      {automatic && <p>采样已完成。请先确认事实表的时间字段与查询窗口，保存后自动继续语义建模。</p>}
      <p>配置独立保存在知识库数据库中，保存后立即生效，无需建模或发布。只检查已登记表；不限制的表跳过。</p>
      {tables.length === 0 && <p>暂无包含采样时间字段的外部表。</p>}
      {tables.map(table => {
        const value = draft[table.datasetId];
        const update = (change: Partial<Draft>) => setDraft(previous => ({
          ...previous, [table.datasetId]: { ...previous[table.datasetId], ...change },
        }));
        return <div key={table.datasetId} className="da-card" style={{ margin: '10px 0' }}>
          <strong>{table.tableName}</strong>
          <button className="da-btn" disabled={busy} onClick={() => void setRole(table.datasetId, 'DIMENSION')}>这是维度表，跳过配置</button>
          <label>数据时间字段：<select disabled={busy} value={value.timeColumn} onChange={e => update({ timeColumn: e.target.value })}>
            {table.temporalColumns.map(column => <option key={column} value={column}>{column}</option>)}
          </select></label>
          <label>查询限制：<select disabled={busy} value={value.mode} onChange={e => update({ mode: e.target.value as Draft['mode'] })}>
            <option value="LIMITED">限制查询窗口</option><option value="UNLIMITED">不限制</option>
          </select></label>
          {value.mode === 'LIMITED' && <label>最大窗口：<select disabled={busy} value={value.days} onChange={e => update({ days: Number(e.target.value) })}>
            {[7, 14, 31, 90].map(days => <option key={days} value={days}>{days} 天</option>)}
          </select></label>}
        </div>;
      })}
      {error && <div role="alert">{error}</div>}
      <button className="da-btn da-btn-primary" disabled={busy || !tables.length} onClick={() => void save()}>{busy ? '保存中…' : '确认并保存'}</button>
      <button className="da-btn" disabled={busy} onClick={() => setOpen(false)}>关闭</button>
    </div>}
  </div>;
}
