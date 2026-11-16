import { useEffect, useRef, useState } from 'react';
import Icon from '../Icon';
import BusinessDocumentsPanel from './BusinessDocumentsPanel';
import { confirmTableRole, getFactQueryWindowCandidates, saveFactQueryWindow } from '../../api/modelingWorkflow';
import type { FactQueryWindowCandidate } from '../../api/modelingWorkflow';
import type { ScreeningStatus } from '../../api/fieldScreening';
import { factWindowSetup } from '../../utils/factWindowSetup';

const DAYS = [7, 14, 31, 90];
type WindowDraft = { mode: 'LIMITED' | 'UNLIMITED'; days: number; timeColumn: string };
const draftOf = (table: FactQueryWindowCandidate): WindowDraft => ({ mode: table.mode ?? 'LIMITED', days: table.windowDays ?? 31, timeColumn: table.timeColumn ?? table.temporalColumns[0] ?? '' });
const message = (e: unknown) => e instanceof Error ? e.message : '准备信息保存失败';

/** The preparation checklist reuses existing policies and persists defaults before starting. */
export default function ModelingPreparation({ groupId, tables, onEditFields, onReady, onStart, onDocumentsChanged, confirmWindow = false }: {
  groupId: string;
  tables: ScreeningStatus[];
  onEditFields: () => void;
  onReady: (ready: boolean) => void;
  onStart: () => void;
  onDocumentsChanged: (summary: string) => void;
  confirmWindow?: boolean;
}) {
  const [candidates, setCandidates] = useState<FactQueryWindowCandidate[]>([]);
  const [drafts, setDrafts] = useState<Record<string, WindowDraft>>({});
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [documentsReady, setDocumentsReady] = useState(false);
  const active = useRef(true);
  const generation = useRef(0);
  const operation = useRef(false);
  const readyCallback = useRef(onReady); readyCallback.current = onReady;
  const setup = factWindowSetup(candidates, false);
  const included = tables.reduce((sum, table) => sum + table.included, 0);

  async function load() {
    const current = ++generation.current;
    const isCurrent = () => active.current && current === generation.current;
    setLoading(true); setError('');
    try {
      const values = await getFactQueryWindowCandidates(groupId);
      if (!isCurrent()) return;
      setCandidates(values);
      setDrafts(Object.fromEntries(values.map(table => [table.datasetId, draftOf(table)])));
      readyCallback.current(factWindowSetup(values, true).ready);
    } catch (e) { if (isCurrent()) { setError(message(e)); readyCallback.current(false); } }
    finally { if (isCurrent()) setLoading(false); }
  }
  useEffect(() => { active.current = true; void load(); return () => { active.current = false; generation.current++; }; }, [groupId]);

  async function changeWindow(table: FactQueryWindowCandidate, change: Partial<WindowDraft>) {
    if (operation.current) return;
    operation.current = true; setBusy(true); setError('');
    const value = { ...drafts[table.datasetId], ...change };
    setDrafts(previous => ({ ...previous, [table.datasetId]: value }));
    readyCallback.current(false);
    try {
      const saved = await saveFactQueryWindow(groupId, { datasetId: table.datasetId, ...value, days: value.mode === 'LIMITED' ? value.days : undefined });
      if (!active.current) return;
      const next = candidates.map(item => item.datasetId === table.datasetId ? saved : item);
      setCandidates(next);
      readyCallback.current(factWindowSetup(next, true).ready);
    } catch (e) { if (active.current) setError(message(e)); }
    finally { operation.current = false; if (active.current) setBusy(false); }
  }
  async function role(table: FactQueryWindowCandidate, value: 'FACT' | 'DIMENSION') {
    if (operation.current) return;
    operation.current = true; setBusy(true); setError(''); readyCallback.current(false);
    try { await confirmTableRole(groupId, table.datasetId, value); if (active.current) await load(); }
    catch (e) { if (active.current) setError(message(e)); }
    finally { operation.current = false; if (active.current) setBusy(false); }
  }
  async function proceed(startModeling: boolean) {
    if (operation.current || loading || !tables.length || (startModeling && !documentsReady) || setup.unknown.length) return;
    operation.current = true; setBusy(true); setError('');
    try {
      // Persist unsaved defaults or retry failed edits; never start against an unconfigured policy.
      const savedPolicies = new Map<string, FactQueryWindowCandidate>();
      for (const table of setup.facts) {
        const value = drafts[table.datasetId];
        if (!table.mode || table.mode !== value.mode || table.timeColumn !== value.timeColumn || (value.mode === 'LIMITED' && table.windowDays !== value.days)) {
          const saved = await saveFactQueryWindow(groupId, { datasetId: table.datasetId, ...value, days: value.mode === 'LIMITED' ? value.days : undefined });
          savedPolicies.set(table.datasetId, saved);
        }
        if (!active.current) return;
      }
      setCandidates(previous => previous.map(table => savedPolicies.get(table.datasetId) ?? table));
      readyCallback.current(true);
      if (startModeling) onStart();
    } catch (e) { if (active.current) { readyCallback.current(false); setError(message(e)); } }
    finally { operation.current = false; if (active.current) setBusy(false); }
  }

  return <div className="mp-wrap"><section className="mp-card" aria-label="建模准备清单">
    <header className="mp-heading"><span className="mp-complete"><Icon name="check" size="sm" /></span><div>
      <h2>{!tables.length ? '请先准备数据' : `字段整理完成${loading ? '' : setup.facts.length || setup.unknown.length ? '，还有两项要确认' : '，可以开始了'}`}</h2>
      <p>{!tables.length ? '返回知识库上传文件或关联数据表，再来整理字段。' : <>{tables.length} 张表 · {included} 个字段已确认。{loading ? '正在读取准备信息…' : setup.facts.length || setup.unknown.length ? '查询范围已按默认填好，业务文档可选。' : '没有需要配置的查询范围，业务文档可选。'}</>}</p>
    </div></header>
    <div className="mp-row"><span className="mp-row-icon mp-row-icon-ok"><Icon name="table" size="sm" /></span>
      <div className="mp-row-text"><strong>字段</strong><small>{tables.map(t => `${t.name} ${t.included} 纳入`).join(' · ')}</small></div>
      <button type="button" className="mp-button" disabled={busy} onClick={onEditFields}>修改</button>
    </div>
    {loading && <div className="mp-loading" role="status">正在确认查询时间范围…</div>}
    {!loading && (setup.unknown.length > 0 || setup.facts.length > 0) && <div data-tour="window-modal">
    {setup.unknown.map(table => <div key={table.datasetId} className="mp-row mp-role-row">
      <span className="mp-row-icon"><Icon name="clock" size="sm" /></span>
      <div className="mp-row-text"><strong>{table.tableName} 是事实表还是维度表？</strong><small>事实表需要设置查询范围；用户、商品等维度表不需要。</small></div>
      <div className="mp-role-actions"><button type="button" className="mp-button" disabled={busy} onClick={() => void role(table, 'FACT')}>事实表</button><button type="button" className="mp-button" disabled={busy} onClick={() => void role(table, 'DIMENSION')}>维度表</button></div>
    </div>)}
    {setup.facts.map(table => {
      const value = drafts[table.datasetId];
      const unsaved = table.mode !== value.mode || table.timeColumn !== value.timeColumn || (value.mode === 'LIMITED' && table.windowDays !== value.days);
      return <section key={table.datasetId} className="mp-window" aria-label={`${table.tableName} 查询时间范围`}>
        <div className="mp-row"><span className="mp-row-icon mp-row-icon-ok"><Icon name="clock" size="sm" /></span>
          <div className="mp-row-text"><strong>查询时间范围</strong><small>{table.tableName} · 事实表 · {value.mode === 'LIMITED' ? `单次最多 ${value.days} 天` : '不限制'}，按 <code>{value.timeColumn}</code></small></div>
          <span className="mp-quiet">{busy ? '保存中…' : unsaved ? !table.mode && value.days === 31 ? '默认 31 天，开始前保存' : '改动未保存，开始前重试' : '改动即生效'}</span>
        </div>
        <div className="mp-window-fields"><label><span>按哪个时间字段限定</span><select aria-label={`${table.tableName} 时间字段`} disabled={busy} value={value.timeColumn} onChange={e => void changeWindow(table, { timeColumn: e.target.value })}>{table.temporalColumns.map(column => <option key={column} value={column}>{column}</option>)}</select></label>
          <div><span className="mp-field-label">单次查询最多覆盖</span><div className="mp-chips" role="group" aria-label={`${table.tableName} 查询天数`}>{DAYS.map(days => <button type="button" key={days} disabled={busy} aria-pressed={value.mode === 'LIMITED' && value.days === days} onClick={() => void changeWindow(table, { mode: 'LIMITED', days })}>{days} 天</button>)}<button type="button" disabled={busy} aria-pressed={value.mode === 'UNLIMITED'} onClick={() => void changeWindow(table, { mode: 'UNLIMITED' })}>不限制</button></div></div>
          <p className="mp-window-note"><span>{value.mode === 'LIMITED' ? `问数时 ${value.timeColumn} 的范围不能超过 ${value.days} 天，超出时会请用户缩小范围。` : '不限制查询范围，适合数据量较小的表。'}</span><button type="button" className="mp-text-button" disabled={busy} onClick={() => void role(table, 'DIMENSION')}>不是事实表？标为维度表</button></p>
        </div>
      </section>;
    })}
    {confirmWindow && setup.unknown.length === 0 && <div className="mp-tour-window"><button type="button" className="mp-button mp-primary" disabled={busy} onClick={() => void proceed(false)}>确认范围，继续</button></div>}
    </div>}
    <BusinessDocumentsPanel groupId={groupId} variant="setup" onChanged={onDocumentsChanged} onReadyChange={setDocumentsReady} />
    {error && <div className="mp-error" role="alert">{error} <button type="button" className="mp-text-button" disabled={busy} onClick={() => void load()}>重新读取</button></div>}
    <footer className="mp-footer"><p><strong>不传文档也能开始</strong>。助手会读取资料、建模、写草稿并校验。</p>
      <button type="button" className="mp-button mp-primary" data-tour="model-start" disabled={loading || busy || !tables.length || !documentsReady || setup.unknown.length > 0 || (error !== '' && candidates.length === 0)} onClick={() => void proceed(true)}>{busy ? '正在保存准备信息…' : '开始自动建模'}</button>
    </footer>
  </section></div>;
}
