import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { getScreeningStatus, getScreeningTable, saveScreening } from '../../api/fieldScreening';
import type { FieldUpdate, ScreeningField, ScreeningReference, ScreeningStatus, ScreeningTable } from '../../api/fieldScreening';

type Draft = { included: boolean; description: string };
type Filter = 'ALL' | 'BUSINESS' | 'TECHNICAL' | 'UNKNOWN';
export type FieldAdjustment = { table: string; added: string[]; removed: string[]; described: string[] };

const message = (e: unknown) => e instanceof Error ? e.message : String(e);
const ROLE: Record<ScreeningField['role'], { label: string; cls: string }> = {
  BUSINESS: { label: '业务', cls: 'fs-tag-ok' },
  TECHNICAL: { label: '技术', cls: 'fs-tag-muted' },
  UNKNOWN: { label: '不确定', cls: 'fs-tag-warn' },
};

/**
 * spec093 field screening. In setup mode the modeler confirms every table before modeling starts;
 * in adjust mode one table is changed after modeling and the page re-models it. Excluded columns
 * never enter the semantic model; descriptions written here are kept by the assistant.
 */
export default function FieldScreeningPanel({ groupId, mode, datasetId, onDone, onAdjusted, onClose }: {
  groupId: string;
  mode: 'setup' | 'adjust';
  /** Adjust mode: the one table to change. */
  datasetId?: string;
  /** Setup mode: every table confirmed. */
  onDone?: () => void;
  onAdjusted?: (change: FieldAdjustment, remodel: boolean) => void;
  onClose?: () => void;
}) {
  const [status, setStatus] = useState<ScreeningStatus[]>([]);
  const [selected, setSelected] = useState<string | null>(datasetId ?? null);
  const [table, setTable] = useState<ScreeningTable | null>(null);
  const [drafts, setDrafts] = useState<Record<string, Draft>>({});
  const [filter, setFilter] = useState<Filter>('ALL');
  const [busy, setBusy] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [references, setReferences] = useState<ScreeningReference[] | null>(null);
  /** A join-key column waiting for the modeler to confirm its exclusion. */
  const [asking, setAsking] = useState<string | null>(null);
  const active = useRef(groupId); active.current = groupId;

  const loadStatus = useCallback(async () => {
    const list = await getScreeningStatus(groupId);
    if (active.current !== groupId) return list;
    setStatus(list);
    setSelected(previous => previous ?? (list.find(t => !t.confirmed) ?? list[0])?.datasetId ?? null);
    return list;
  }, [groupId]);
  useEffect(() => { void loadStatus().catch(e => setError(message(e))); }, [loadStatus]);

  useEffect(() => {
    if (!selected) return;
    let cancelled = false;
    setTable(null); setReferences(null); setAsking(null); setError(null); setBusy('load');
    void getScreeningTable(groupId, selected).then(t => {
      if (cancelled) return;
      setTable(t);
      setDrafts(Object.fromEntries(t.fields.map(f => [f.name, { included: f.included, description: f.description ?? '' }])));
    }).catch(e => { if (!cancelled) setError(message(e)); }).finally(() => { if (!cancelled) setBusy(null); });
    return () => { cancelled = true; };
  }, [groupId, selected]);

  const fields = table?.fields ?? [];
  const counts = useMemo(() => ({
    BUSINESS: fields.filter(f => f.role === 'BUSINESS').length,
    TECHNICAL: fields.filter(f => f.role === 'TECHNICAL').length,
    UNKNOWN: fields.filter(f => f.role === 'UNKNOWN').length,
  }), [fields]);
  const included = fields.filter(f => drafts[f.name]?.included).length;
  const update = (name: string, change: Partial<Draft>) => setDrafts(p => ({ ...p, [name]: { ...p[name], ...change } }));
  const setAll = (pick: (f: ScreeningField) => boolean) =>
    setDrafts(p => Object.fromEntries(fields.map(f => [f.name, { ...p[f.name], included: f.locked || pick(f) }])));

  const save = async (force = false) => {
    if (!table) return;
    const updates: FieldUpdate[] = fields.map(f => {
      const d = drafts[f.name];
      const u: FieldUpdate = { name: f.name };
      if (d.included !== f.included) u.included = d.included;
      if ((d.description ?? '').trim() !== (f.description ?? '').trim()) u.description = d.description;
      return u;
    }).filter(u => u.included !== undefined || u.description !== undefined);
    setBusy('save'); setError(null);
    try {
      const result = await saveScreening(groupId, table.datasetId, updates, true, force);
      if (active.current !== groupId) return;
      if (result.references.length && !force) { setReferences(result.references); return; }
      setReferences(null);
      window.dispatchEvent(new CustomEvent('modeling:updated', { detail: { groupId } }));
      if (mode === 'adjust') {
        onAdjusted?.({
          table: table.name,
          added: fields.filter(f => !f.included && drafts[f.name].included).map(f => f.name),
          removed: fields.filter(f => f.included && !drafts[f.name].included).map(f => f.name),
          described: updates.filter(u => u.description !== undefined).map(u => u.name),
        }, result.remodel);
        return;
      }
      const list = await loadStatus();
      const next = list.find(t => !t.confirmed);
      if (next) setSelected(next.datasetId);
      else setTable(result.table);
    } catch (e) { if (active.current === groupId) setError(message(e)); }
    finally { if (active.current === groupId) setBusy(null); }
  };

  const shown = fields.filter(f => filter === 'ALL' || f.role === filter);
  const confirmedCount = status.filter(t => t.confirmed).length;
  const allConfirmed = status.length > 0 && confirmedCount === status.length;
  const othersPending = status.some(t => !t.confirmed && t.datasetId !== table?.datasetId);
  const dirty = fields.some(f => {
    const d = drafts[f.name];
    return d && (d.included !== f.included || (d.description ?? '').trim() !== (f.description ?? '').trim());
  });
  const toggle = (f: ScreeningField, include: boolean) => {
    if (!include && f.joinKey) { setAsking(f.name); return; }
    update(f.name, { included: include });
  };

  const saveLabel = busy === 'save' ? '保存中…' : mode === 'adjust' ? '保存' : table?.confirmed ? '保存修改' : othersPending ? '确认，下一张表' : '确认此表';

  return <section aria-label="字段整理" className="da-card fs" style={{ padding: 0, display: 'flex', flexDirection: 'column', minHeight: 0, flex: 1, overflow: 'hidden', width: '100%' }}>
    <header className="fs-head">
      <div style={{ minWidth: 0, flex: 1 }}>
        <div className="fs-title">{mode === 'setup' ? '字段整理' : `调整字段 · ${table?.name ?? ''}`}</div>
        <div className="da-small">选择要进入语义模型的字段。未纳入的字段问数时查不到；你写的业务说明建模时原样保留。</div>
      </div>
      {onClose && <button className="da-btn da-btn-sm" onClick={onClose}>关闭</button>}
    </header>
    <div style={{ display: 'flex', flex: 1, minHeight: 0 }}>
      {mode === 'setup' && <nav aria-label="数据表" className="fs-nav">
        <div className="fs-nav-head">
          <span>数据表</span><span>{confirmedCount}/{status.length} 已确认</span>
        </div>
        <div className="fs-progress"><span style={{ width: `${status.length ? confirmedCount * 100 / status.length : 0}%` }} /></div>
        {status.map(t => {
          const on = t.datasetId === selected;
          return <button key={t.datasetId} type="button" className={`fs-nav-item${on ? ' fs-nav-on' : ''}`} onClick={() => setSelected(t.datasetId)}>
            <span className={`fs-state${t.confirmed ? ' fs-state-ok' : on ? ' fs-state-on' : ''}`} aria-hidden>{t.confirmed ? '✓' : ''}</span>
            <span style={{ minWidth: 0 }}>
              <span className="fs-nav-name">{t.name}</span>
              <span className="da-small">纳入 {t.included}/{t.total} · {t.confirmed ? '已确认' : on ? '整理中' : '待整理'}</span>
            </span>
          </button>;
        })}
      </nav>}
      <div style={{ flex: 1, minWidth: 0, display: 'flex', flexDirection: 'column', minHeight: 0 }}>
        {busy === 'load' && <div className="fs-loading" role="status"><span className="fs-spinner" />正在读取字段，AI 预判业务字段与技术字段…</div>}
        {table && <>
          <div className="fs-toolbar">
            <div style={{ display: 'flex', alignItems: 'center', gap: 8, minWidth: 0, flexWrap: 'wrap' }}>
              <strong className="fs-table-name">{table.name}</strong>
              <span className="fs-tag fs-tag-muted">{table.origin === 'datasource' ? '外部表' : '上传表'}</span>
              {table.tableRole === 'FACT' && <span className="fs-tag fs-tag-primary">事实表</span>}
              <span className="da-small">采样 {table.sampledRows} 行 · {fields.length} 个字段</span>
            </div>
            <span style={{ flex: 1 }} />
            <div className="fs-segmented" role="tablist" aria-label="按 AI 判断筛选">
              {(['ALL', 'BUSINESS', 'TECHNICAL', 'UNKNOWN'] as Filter[]).map(k => <button key={k} type="button" role="tab" aria-selected={filter === k}
                className={filter === k ? 'fs-seg-on' : ''} onClick={() => setFilter(k)}>
                {k === 'ALL' ? '全部' : ROLE[k].label}<span className="fs-count">{k === 'ALL' ? fields.length : counts[k]}</span></button>)}
            </div>
          </div>
          <div className="fs-actions">
            <button className="da-btn da-btn-sm" onClick={() => setAll(f => f.role === 'BUSINESS')}>按 AI 建议勾选</button>
            <button className="da-btn da-btn-sm" onClick={() => setAll(() => true)}>全选</button>
            <button className="da-btn da-btn-sm" onClick={() => setAll(() => false)}>全不选</button>
            <span className="da-small">AI 只依据字段名、样例值和空值判断，仅供参考。</span>
          </div>
          <div className="fs-row fs-row-head">
            <span /><span>字段</span><span>样例值</span><span>业务说明</span>
          </div>
          <div className="fs-body">
            {shown.length === 0 && <div className="fs-empty">没有{filter === 'ALL' ? '' : ROLE[filter as Exclude<Filter, 'ALL'>].label}字段</div>}
            {shown.map(f => {
              const d = drafts[f.name] ?? { included: f.included, description: f.description ?? '' };
              const role = f.locked ? { label: '时间 · 锁定', cls: 'fs-tag-primary' }
                : f.joinKey ? { label: '关联键', cls: 'fs-tag-primary' }
                : { label: ROLE[f.role].label, cls: ROLE[f.role].cls };
              return <div key={f.name} className={`fs-row${d.included ? '' : ' fs-row-off'}`}>
                <input type="checkbox" aria-label={`纳入 ${f.name}`} checked={d.included} disabled={f.locked}
                  onChange={e => toggle(f, e.target.checked)} />
                <div style={{ minWidth: 0 }}>
                  <div className="fs-name" title={f.name}>{f.name}</div>
                  {f.originalName && f.originalName !== f.name && <div className="da-small fs-ellipsis" title={f.originalName}>{f.originalName}</div>}
                  <div className="fs-meta">
                    <span className="fs-type">{f.sqlType ?? '未知'}</span>
                    <span className={`fs-tag ${role.cls}`} title={f.reason ?? ''}>{role.label}</span>
                    {!f.locked && !f.joinKey && f.reason && <span className="fs-reason fs-ellipsis" title={f.reason}>{f.reason}</span>}
                  </div>
                  {asking === f.name && <div className="fs-ask" role="alert">
                    同名字段也出现在其他表，可能是关联键；取消后无法与其他表关联。
                    <span style={{ display: 'flex', gap: 6, marginTop: 6 }}>
                      <button className="da-btn da-btn-sm" onClick={() => { update(f.name, { included: false }); setAsking(null); }}>仍然取消</button>
                      <button className="da-btn da-btn-primary da-btn-sm" onClick={() => setAsking(null)}>保留</button>
                    </span>
                  </div>}
                </div>
                <div className="fs-samples" title={f.samples.join('、')}>
                  {f.samples.length ? f.samples.slice(0, 3).map((v, i) => <span key={i} className="fs-sample">{v}</span>)
                    : <span className="da-small">无样例</span>}
                  {f.samples.length > 3 && <span className="da-small">+{f.samples.length - 3}</span>}
                </div>
                <textarea className="da-input fs-desc" rows={2} value={d.description} maxLength={500} disabled={!d.included}
                  placeholder={d.included ? '补充业务含义，建模时原样保留' : '纳入后可填写'}
                  onChange={e => update(f.name, { description: e.target.value })} />
              </div>;
            })}
          </div>
          {references && <div role="alert" className="fs-refs">
            <strong style={{ fontSize: 13 }}>以下草稿内容引用了要取消的字段，取消后会失效：</strong>
            <ul style={{ margin: '6px 0', paddingLeft: 18, fontSize: 12 }}>{references.map((r, i) => <li key={i}><code>{r.path}</code>：{r.line}</li>)}</ul>
            <div style={{ display: 'flex', gap: 6 }}>
              <button className="da-btn da-btn-sm" onClick={() => setReferences(null)}>返回修改</button>
              <button className="da-btn da-btn-primary da-btn-sm" disabled={busy !== null} onClick={() => void save(true)}>仍然取消，并让助手重新建模这张表</button>
            </div>
          </div>}
          {error && <div role="alert" className="da-small" style={{ color: 'var(--da-danger)', padding: '6px 16px' }}>{error}</div>}
        </>}
        {!table && error && <div role="alert" className="da-small" style={{ color: 'var(--da-danger)', padding: 16 }}>{error}</div>}
      </div>
    </div>
    <footer className="fs-foot">
      {table && <span>纳入 <strong>{included}</strong> / {fields.length}</span>}
      <span className="da-small">{table && included === 0 ? '每张表至少纳入 1 个字段'
        : mode === 'setup' ? (allConfirmed ? '全部表已确认，可以配置时间范围' : `还有 ${status.length - confirmedCount} 张表待确认`)
        : '保存后会让助手按新字段重新建模这张表'}</span>
      <span style={{ flex: 1 }} />
      {table && (mode === 'adjust' || !table.confirmed || dirty) && <button
        data-tour="screening-confirm"
        className={`da-btn ${mode === 'setup' && allConfirmed && table.confirmed ? '' : 'da-btn-primary'}`}
        disabled={busy !== null || included === 0} onClick={() => void save()}>{saveLabel}</button>}
      {mode === 'setup' && allConfirmed && <button className="da-btn da-btn-primary" data-tour="screening-confirm" disabled={busy !== null || dirty} onClick={onDone}>完成整理，配置时间范围 →</button>}
    </footer>
  </section>;
}
