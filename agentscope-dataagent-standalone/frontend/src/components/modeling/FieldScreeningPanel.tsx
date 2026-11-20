import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import type { MutableRefObject } from 'react';
import Icon from '../Icon';
import { getScreeningStatus, getScreeningTable, saveScreening } from '../../api/fieldScreening';
import type { FieldUpdate, ScreeningField, ScreeningReference, ScreeningStatus, ScreeningTable } from '../../api/fieldScreening';
import { needsFieldReview } from './fieldScreeningActions';

type Draft = { included: boolean; description: string };
type Filter = 'ALL' | 'TECHNICAL' | 'UNKNOWN';
export type FieldAdjustment = { table: string; added: string[]; removed: string[]; described: string[] };

const message = (e: unknown) => e instanceof Error ? e.message : String(e);

/**
 * spec093 field screening. In setup mode the modeler confirms every table before modeling starts;
 * in adjust mode one table is changed after modeling and the page re-models it. Excluded columns
 * never enter the semantic model; descriptions written here are kept by the assistant.
 */
export default function FieldScreeningPanel({ groupId, mode, datasetId, pendingOnly = false, onDone, onAdjusted, onClose, leaveGuard }: {
  groupId: string;
  mode: 'setup' | 'adjust';
  /** Table to open first, or the one table to change in adjust mode. */
  datasetId?: string;
  pendingOnly?: boolean;
  /** Setup mode: every table confirmed. */
  onDone?: (tables: ScreeningStatus[]) => void;
  onAdjusted?: (change: FieldAdjustment, remodel: boolean) => void;
  onClose?: () => void;
  leaveGuard?: MutableRefObject<(() => Promise<boolean>) | null>;
}) {
  const [status, setStatus] = useState<ScreeningStatus[]>([]);
  const [selected, setSelected] = useState<string | null>(datasetId ?? null);
  const [table, setTable] = useState<ScreeningTable | null>(null);
  const [drafts, setDrafts] = useState<Record<string, Draft>>({});
  const [filter, setFilter] = useState<Filter>(pendingOnly ? 'UNKNOWN' : 'ALL');
  const [busy, setBusy] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [references, setReferences] = useState<ScreeningReference[] | null>(null);
  /** A join-key column waiting for the modeler to confirm its exclusion. */
  const [asking, setAsking] = useState<string | null>(null);
  const [savingDescription, setSavingDescription] = useState(false);
  const [descriptionState, setDescriptionState] = useState('');
  const [reloadTable, setReloadTable] = useState(0);
  const descriptionFlight = useRef<Promise<boolean> | null>(null);
  const confirmFlight = useRef(false);
  const latest = useRef({ table, drafts }); latest.current = { table, drafts };
  const mounted = useRef(true);
  useEffect(() => { mounted.current = true; return () => { mounted.current = false; }; }, []);
  const active = useRef(groupId); active.current = groupId;

  const loadStatus = useCallback(async () => {
    const list = await getScreeningStatus(groupId);
    if (active.current !== groupId) return list;
    setStatus(list);
    setSelected(previous => previous && list.some(t => t.datasetId === previous) ? previous : list[0]?.datasetId ?? null);
    return list;
  }, [groupId]);
  useEffect(() => { void loadStatus().catch(e => setError(message(e))); }, [loadStatus]);

  useEffect(() => {
    if (!selected) return;
    let cancelled = false;
    setTable(null); setReferences(null); setAsking(null); setError(null); setDescriptionState(''); setBusy('load');
    void getScreeningTable(groupId, selected).then(t => {
      if (cancelled) return;
      setTable(t);
      setDrafts(Object.fromEntries(t.fields.map(f => [f.name, { included: f.included, description: f.description ?? '' }])));
    }).catch(e => { if (!cancelled) setError(message(e)); }).finally(() => { if (!cancelled) setBusy(null); });
    return () => { cancelled = true; };
  }, [groupId, selected, reloadTable]);

  const fields = table?.fields ?? [];
  const counts = useMemo(() => ({
    BUSINESS: fields.filter(f => f.role === 'BUSINESS').length,
    TECHNICAL: fields.filter(f => f.role === 'TECHNICAL').length,
    UNKNOWN: fields.filter(needsFieldReview).length,
  }), [fields]);
  const included = fields.filter(f => drafts[f.name]?.included).length;
  const update = (name: string, change: Partial<Draft>) => setDrafts(p => ({ ...p, [name]: { ...p[name], ...change } }));
  const setAll = (pick: (f: ScreeningField) => boolean) =>
    setDrafts(p => Object.fromEntries(fields.map(f => [f.name, { ...p[f.name], included: f.locked || pick(f) }])));

  const flushDescriptions = async (): Promise<boolean> => {
    if (mode !== 'setup') return true;
    if (descriptionFlight.current) {
      if (!await descriptionFlight.current) return false;
      return flushDescriptions();
    }
    const snapshot = latest.current;
    if (!snapshot.table) return true;
    const updates = snapshot.table.fields.filter(f => (snapshot.drafts[f.name]?.description ?? '').trim() !== (f.description ?? '').trim())
      .map(f => ({ name: f.name, description: snapshot.drafts[f.name].description }));
    if (!updates.length) return true;
    const id = snapshot.table.datasetId;
    setSavingDescription(true); setDescriptionState('正在保存说明…'); setError(null);
    const work = (async () => {
      try {
        const result = await saveScreening(groupId, id, updates, false);
        if (!mounted.current || active.current !== groupId) return false;
        if (latest.current.table?.datasetId === id) {
          latest.current = { ...latest.current, table: result.table };
          setTable(result.table);
          setDescriptionState('说明已保存');
        }
        return true;
      } catch (e) {
        if (mounted.current && active.current === groupId) { setError(message(e)); setDescriptionState('说明未保存，请重试'); }
        return false;
      }
    })();
    descriptionFlight.current = work;
    const ok = await work;
    descriptionFlight.current = null;
    if (mounted.current) setSavingDescription(false);
    return ok;
  };
  const canLeave = async () => {
    if (busy !== null || !await flushDescriptions()) return false;
    const snapshot = latest.current;
    const changed = snapshot.table?.fields.some(f => {
      const value = snapshot.drafts[f.name];
      return value && (value.included !== f.included || (mode === 'adjust' && value.description.trim() !== (f.description ?? '').trim()));
    });
    return !changed || window.confirm('当前表还有未确认的修改，离开后这些修改不会保留。确定离开吗？');
  };
  useEffect(() => {
    if (leaveGuard) leaveGuard.current = canLeave;
    return () => { if (leaveGuard) leaveGuard.current = null; };
  });
  useEffect(() => {
    const warn = (event: BeforeUnloadEvent) => {
      const snapshot = latest.current;
      if (descriptionFlight.current || snapshot.table?.fields.some(f => {
        const value = snapshot.drafts[f.name];
        return value && (value.included !== f.included || value.description.trim() !== (f.description ?? '').trim());
      })) { event.preventDefault(); event.returnValue = ''; }
    };
    window.addEventListener('beforeunload', warn);
    return () => window.removeEventListener('beforeunload', warn);
  }, []);
  const switchTable = async (id: string) => { if (id !== selected && await canLeave()) setSelected(id); };

  const save = async (force = false) => {
    if (!table || confirmFlight.current) return;
    confirmFlight.current = true;
    setBusy('save');
    if (!await flushDescriptions()) { confirmFlight.current = false; setBusy(null); return; }
    const currentTable = latest.current.table;
    if (!currentTable) { confirmFlight.current = false; setBusy(null); return; }
    const currentDrafts = latest.current.drafts;
    const currentFields = currentTable.fields;
    const updates: FieldUpdate[] = currentFields.map(f => {
      const d = currentDrafts[f.name];
      const u: FieldUpdate = { name: f.name };
      if (d.included !== f.included) u.included = d.included;
      if ((d.description ?? '').trim() !== (f.description ?? '').trim()) u.description = d.description;
      return u;
    }).filter(u => u.included !== undefined || u.description !== undefined);
    setBusy('save'); setError(null);
    try {
      const result = await saveScreening(groupId, currentTable.datasetId, updates, true, force);
      if (!mounted.current || active.current !== groupId) return;
      if (result.references.length && !force) { setReferences(result.references); return; }
      setReferences(null);
      window.dispatchEvent(new CustomEvent('modeling:updated', { detail: { groupId } }));
      if (mode === 'adjust') {
        onAdjusted?.({
          table: currentTable.name,
          added: currentFields.filter(f => !f.included && currentDrafts[f.name].included).map(f => f.name),
          removed: currentFields.filter(f => f.included && !currentDrafts[f.name].included).map(f => f.name),
          described: updates.filter(u => u.description !== undefined).map(u => u.name),
        }, result.remodel);
        return;
      }
      const list = await loadStatus();
      if (!mounted.current || active.current !== groupId) return;
      const next = list.find(t => !t.confirmed);
      setTable(result.table);
      if (!next) onDone?.(list);
      else if (!currentTable.confirmed) {
        setFilter('ALL');
        setSelected(next.datasetId);
      }
    } catch (e) { if (active.current === groupId) setError(message(e)); }
    finally { confirmFlight.current = false; if (mounted.current && active.current === groupId) setBusy(null); }
  };

  const shown = fields.filter(f => filter === 'ALL' || (filter === 'UNKNOWN' ? needsFieldReview(f) : f.role === filter));
  const confirmedCount = status.filter(t => t.confirmed).length;
  const othersPending = status.some(t => !t.confirmed && t.datasetId !== table?.datasetId);
  const toggle = (f: ScreeningField, include: boolean) => {
    if (!include && f.joinKey) { setAsking(f.name); return; }
    update(f.name, { included: include });
  };

  const saveLabel = busy === 'save' ? '保存中…' : mode === 'adjust' ? '保存' : table?.confirmed ? '保存修改' : othersPending ? '确认，下一张表' : '确认此表';

  return <section aria-label="字段整理" className={`fs fs-redesign${mode === 'adjust' ? ' fs-adjust' : ''}`}>
    {mode === 'adjust' && <header className="fs-adjust-head"><strong>调整字段 · {table?.name ?? ''}</strong>{onClose && <button type="button" className="mp-button" onClick={() => void canLeave().then(ok => { if (ok) onClose(); })}>关闭</button>}</header>}
    <div className="fs-layout">
      {mode === 'setup' && <nav aria-label="数据表" className="fs-nav">
        <div className="fs-nav-head"><span>数据表</span><span>{confirmedCount} / {status.length} 已确认</span></div>
        <div className="fs-progress"><span style={{ width: `${status.length ? confirmedCount * 100 / status.length : 0}%` }} /></div>
        <div className="fs-nav-list">{status.map(t => {
          const on = t.datasetId === selected;
          return <button key={t.datasetId} type="button" aria-current={on ? 'true' : undefined} disabled={busy !== null} className={`fs-nav-item${on ? ' fs-nav-on' : ''}`} onClick={() => void switchTable(t.datasetId)}>
            <span className="fs-dataset-icon"><Icon name="table" size="sm" /></span>
            <span className="fs-nav-copy"><span className="fs-nav-name" title={t.name}>{t.name}</span><small>{t.total} 个字段 · {t.confirmed ? '已确认' : on ? '整理中' : '待整理'}</small></span>
            <span className={`fs-nav-status${t.confirmed ? ' is-confirmed' : on ? ' is-current' : ''}`} title={t.confirmed ? '已确认' : '待确认'} />
          </button>;
        })}</div>
        <p className="fs-nav-tip">每张表单独确认，全部完成后进入准备。业务说明可以随时补充。</p>
      </nav>}
      <div className="fs-main">
        {busy === 'load' && <div className="fs-loading" role="status"><span className="fs-spinner" />正在读取字段，AI 预判业务字段与技术字段…</div>}
        {table && <>
          <header className="fs-toolbar">
            <strong className="fs-table-name" title={table.name}>{table.name}</strong>
            {table.tableRole === 'FACT' || table.tableRole === 'DIMENSION' ? <span className="fs-tag fs-tag-primary">{table.tableRole === 'FACT' ? '事实表' : '维度表'}</span> : <span className="fs-tag fs-tag-muted">{table.origin === 'datasource' ? '外部表' : '上传表'}</span>}
            <span className="fs-table-meta">采样 {table.sampledRows.toLocaleString()} 行</span>
            <div className="fs-segmented" role="tablist" aria-label="按 AI 判断筛选">
              {(['ALL', 'UNKNOWN', 'TECHNICAL'] as Filter[]).map(k => <button key={k} type="button" role="tab" aria-selected={filter === k} className={filter === k ? 'fs-seg-on' : ''} onClick={() => setFilter(k)}>
                {k === 'ALL' ? '全部' : k === 'UNKNOWN' ? '待确认' : '技术'}<span className={`fs-count${k === 'UNKNOWN' && counts.UNKNOWN ? ' fs-count-warn' : ''}`}>{k === 'ALL' ? fields.length : counts[k]}</span></button>)}
            </div>
          </header>
          <div className="fs-actions">
            <button type="button" className="mp-button fs-ai-suggest" disabled={busy !== null} onClick={() => setAll(f => f.role === 'BUSINESS')}>按 AI 建议勾选</button>
            <button type="button" className="mp-button" disabled={busy !== null} onClick={() => setAll(() => true)}>全选</button>
            <button type="button" className="mp-button" disabled={busy !== null} onClick={() => setAll(() => false)}>全不选</button>
            <span className="fs-action-hint">AI 只依据字段名、样例值和空值判断，仅供参考。</span>
            <button type="button" className="mp-button fs-pending-button" aria-pressed={filter === 'UNKNOWN'} onClick={() => setFilter(filter === 'UNKNOWN' ? 'ALL' : 'UNKNOWN')}>只看待确认</button>
          </div>
          <div className="fs-body" role="region" aria-label={`${table.name} 字段列表`}>
            <div className="fs-row fs-row-head"><span /><span>字段</span><span>样例值</span><span>业务说明 · 点开即可改</span></div>
            {shown.length === 0 && <div className="fs-empty">{filter === 'UNKNOWN' ? '没有待确认的字段' : filter === 'TECHNICAL' ? '没有技术字段' : '没有字段'}</div>}
            {shown.map(f => {
              const d = drafts[f.name] ?? { included: f.included, description: f.description ?? '' };
              const pending = f.role === 'UNKNOWN' && !f.userDescribed;
              return <div key={f.name} className={`fs-row${d.included ? '' : ' fs-row-off'}`}>
                <input type="checkbox" aria-label={`纳入 ${f.name}`} checked={d.included} disabled={f.locked || busy !== null} onChange={e => toggle(f, e.target.checked)} title={f.locked ? '查询时间字段必须纳入' : undefined} />
                <div className="fs-field"><div className="fs-name-row"><strong className="fs-name" title={f.name}>{f.name}</strong>
                  {f.locked ? <span className="fs-key fs-key-time" title="查询时间字段必须纳入">时间 · 锁定</span> : f.joinKey ? <span className="fs-key">关联</span> : f.role === 'TECHNICAL' ? <span className="fs-key fs-key-muted">技术</span> : null}
                </div><small className="fs-field-meta" title={[f.originalName, f.sqlType, f.reason].filter(Boolean).join(' · ')}>{f.originalName && f.originalName !== f.name ? `${f.originalName} · ` : ''}{f.sqlType ?? '未知类型'}</small>
                  {asking === f.name && <div className="fs-ask" role="alert">同名字段也出现在其他表，可能是关联键；取消后无法与其他表关联。
                    <div><button type="button" className="mp-button" onClick={() => { update(f.name, { included: false }); setAsking(null); }}>仍然取消</button><button type="button" className="mp-button mp-primary" onClick={() => setAsking(null)}>保留</button></div>
                  </div>}
                </div>
                <div className="fs-samples" title={f.samples.join('、')}>{f.samples.length ? f.samples.slice(0, 2).map((v, i) => <span key={i} className="fs-sample">{v}</span>) : <span className="fs-table-meta">无样例</span>}{f.samples.length > 2 && <span className="fs-sample-more">+{f.samples.length - 2}</span>}</div>
                <div className={`fs-description${pending ? ' fs-description-pending' : ''}`}>
                  <textarea className="fs-desc" rows={1} aria-label={`${f.name} 业务说明`} title={d.description} value={d.description} maxLength={500} disabled={busy !== null}
                    placeholder="补充业务含义，建模时原样保留" onChange={e => { update(f.name, { description: e.target.value }); e.target.style.height = 'auto'; e.target.style.height = `${Math.min(112, Math.max(28, e.target.scrollHeight))}px`; }}
                    onFocus={e => { e.target.style.height = 'auto'; e.target.style.height = `${Math.min(112, Math.max(28, e.target.scrollHeight))}px`; }}
                    onBlur={e => { e.target.style.height = '28px'; if (mode === 'setup') void flushDescriptions(); }} />
                  <Icon name="edit" size="sm" className="fs-desc-pen" />
                  {!d.included && <small className="fs-excluded-note">未纳入，说明不会进入模型</small>}
                </div>
              </div>;
            })}
          </div>
          {references && <div role="alert" className="fs-refs"><strong>以下草稿内容引用了要取消的字段，取消后会失效：</strong><ul>{references.map((r, i) => <li key={i}><code>{r.path}</code>：{r.line}</li>)}</ul>
            <div><button type="button" className="mp-button" onClick={() => setReferences(null)}>返回修改</button><button type="button" className="mp-button mp-primary" disabled={busy !== null} onClick={() => void save(true)}>仍然取消，并让助手重新建模这张表</button></div>
          </div>}
        </>}
        {error && <div role="alert" className="fs-error">{error}{!table
          ? <button type="button" className="mp-text-button" onClick={() => { void loadStatus().catch(e => setError(message(e))); setReloadTable(n => n + 1); }}>重新读取</button>
          : mode === 'setup' && <button type="button" className="mp-text-button" disabled={savingDescription} onClick={() => void flushDescriptions()}>重试保存说明</button>}</div>}
        <footer className="fs-foot">
          {table && <span>纳入 <strong>{included}</strong> / {fields.length}</span>}
          <span className="fs-foot-divider" />
          <span className="fs-foot-status" title="用户说明为已保存的人工补充；待确认表示 AI 尚不能明确判断的字段。">{table && included === 0 ? '每张表至少纳入 1 个字段' : mode === 'setup' ? `用户说明 ${fields.filter(f => f.userDescribed).length} 条 · ${counts.UNKNOWN} 待确认` : '保存后会让助手按新字段重新建模这张表'}</span>
          <span className="fs-autosave" role="status">{descriptionState}</span>
          {table && <button type="button" data-tour="screening-confirm" className="mp-button mp-primary" disabled={busy !== null || included === 0} onClick={() => void save()}>
            {saveLabel}<Icon name="chevron" size="sm" /></button>}
        </footer>
      </div>
    </div>
  </section>;
}
