import React, { useEffect, useId, useRef, useState } from 'react';
import { syncAction } from '../../api/tableSync';
import type { SyncColumn, SyncConnection, SyncGrain, SyncPreview, SyncSelection, SyncTarget, SyncTlsMode } from '../../api/tableSync';
import Icon from '../../components/Icon';

const defaults: SyncConnection = { host: '', port: 3306, database: '', username: '', password: '', sslMode: 'REQUIRED' };
const targetDefaults: SyncTarget = { host: '', port: 3306, username: '', sslMode: 'REQUIRED' };
const tlsOptions = [
  { value: 'REQUIRED', label: '使用 TLS', hint: '默认加密传输，日常保留此项即可。' },
  { value: 'VERIFY_CA', label: '使用 TLS 并校验证书', hint: '校验证书是否由受信任的 CA 签发。' },
  { value: 'VERIFY_IDENTITY', label: '使用 TLS 并校验身份', hint: '同时核对证书是否与数据库地址匹配。' },
  { value: 'DISABLED', label: '关闭 TLS', hint: '使用未加密的数据库连接。' },
];

function SyncTls({ mode, onChange }: { mode: SyncTlsMode; onChange: (mode: SyncTlsMode) => void }) {
  const id = useId();
  const hint = tlsOptions.find(option => option.value === mode)?.hint;
  return <div className="table-sync-tls">
    <label htmlFor={id}>TLS</label>
    <select id={id} className="da-input" aria-describedby={id + '-hint'} value={mode} onChange={event => onChange(event.target.value as SyncTlsMode)}>
      {tlsOptions.map(option => <option key={option.value} value={option.value}>{option.label}</option>)}
    </select>
    <small id={id + '-hint'}>{hint}</small>
  </div>;
}
const formats = {
  DAY: ['YYYYMMDD', 'YYYY-MM-DD', 'YYYY/MM/DD', 'NATIVE'],
  MONTH: ['YYYYMM', 'YYYY-MM', 'NATIVE'],
};

export function toStoragePeriod(value: string, grain: SyncGrain) {
  return grain === 'MONTH' && value ? value + '-01' : value;
}

export function displayPeriod(value: string | null | undefined, grain: SyncGrain) {
  return value ? (grain === 'MONTH' ? value.slice(0, 7) : value) : '—';
}

export function previousPeriod(today: string, grain: SyncGrain) {
  const effective = grain === 'MONTH' ? today.slice(0, 7) + '-01' : today;
  const date = new Date(effective + 'T00:00:00Z');
  date.setUTCDate(date.getUTCDate() - 1);
  return date.toISOString().slice(0, grain === 'MONTH' ? 7 : 10);
}

export default function TableSyncWizard({ today, target: initialTarget, onTargetSaved, onClose, onCreated }: {
  today: string; target?: SyncTarget; onTargetSaved: () => void; onClose: () => void; onCreated: (id: string) => void;
}) {
  const dialog = useRef<HTMLDialogElement>(null);
  const [source, setSource] = useState(defaults);
  const [savedTarget, setSavedTarget] = useState(initialTarget);
  const [targetDraft, setTargetDraft] = useState(() => ({ ...(initialTarget ?? targetDefaults), password: '' }));
  const [editingTarget, setEditingTarget] = useState(!initialTarget);
  const [discovery, setDiscovery] = useState<{ version: string; tables: string[]; truncated: boolean }>();
  const [search, setSearch] = useState('');
  const [table, setTable] = useState('');
  const [columns, setColumns] = useState<SyncColumn[]>([]);
  const [selected, setSelected] = useState<string[]>([]);
  const [name, setName] = useState('');
  const [target, setTarget] = useState('');
  const [grain, setGrain] = useState<SyncGrain>('FULL');
  const [field, setField] = useState('');
  const [format, setFormat] = useState('NATIVE');
  const [zone, setZone] = useState('+08:00');
  const [from, setFrom] = useState('');
  const [sample, setSample] = useState('');
  const [sampleResult, setSampleResult] = useState<{ matches: number; sampleValues: string[] }>();
  const [preview, setPreview] = useState<SyncPreview>();
  const [busy, setBusy] = useState('');
  const [error, setError] = useState('');
  const through = previousPeriod(today, grain);
  const dateField = columns.find(column => column.name === field);
  const selection: SyncSelection = {
    ...source, table, columns: selected, targetTable: target.trim(), grain,
    dateField: field, dateFormat: format, storageZone: zone,
  };

  useEffect(() => { dialog.current?.showModal(); }, []);

  function invalidate() { setPreview(undefined); setSampleResult(undefined); }
  function changeSource(patch: Partial<SyncConnection>) {
    setSource(value => ({ ...value, ...patch }));
    setDiscovery(undefined); setTable(''); setSelected([]); setColumns([]); invalidate();
  }

  async function perform(label: string, action: () => Promise<void>) {
    setError(''); setBusy(label);
    try { await action(); } catch (reason) {
      setError(reason instanceof Error ? reason.message : '操作失败，请重试。');
    } finally { setBusy(''); }
  }

  function editTarget() {
    setTargetDraft({ ...(savedTarget ?? targetDefaults), password: '' });
    setEditingTarget(true);
    setPreview(undefined);
  }

  function saveTarget() {
    void perform('验证目标库', async () => {
      const saved = await syncAction<SyncTarget>('target', targetDraft);
      setSavedTarget(saved);
      setTargetDraft({ ...saved, password: '' });
      setEditingTarget(false);
      onTargetSaved();
    });
  }

  function searchTables() {
    void perform('读取表列表', async () => {
      setDiscovery(await syncAction('tables', { ...source, search: search.trim() }));
    });
  }

  function selectTable(value: string) {
    setTable(value); setColumns([]); setSelected([]); setField(''); invalidate();
    if (!value) return;
    void perform('读取字段', async () => {
      const result = await syncAction<{ columns: SyncColumn[] }>('columns', { ...source, table: value });
      setColumns(result.columns); setSelected(result.columns.map(column => column.name));
      setName(value);
      const match = value.toLowerCase().replace(/[^a-z0-9_]+/g, '_').replace(/^_+/, '');
      setTarget(match ? `sync_${match.slice(0, 59)}` : 'sync_');
    });
  }

  function changeGrain(value: SyncGrain) {
    setGrain(value); setField(''); setFormat('NATIVE'); setFrom(''); setSample(''); invalidate();
  }

  return <dialog className="table-sync-dialog" ref={dialog} onCancel={event => {
    event.preventDefault(); if (!busy) onClose();
  }} aria-labelledby="new-sync-title">
    <header><div><h2 id="new-sync-title">新建同步任务</h2><p>从 MySQL 源表复制到 data_agent 中的新表</p></div>
      <button className="da-btn da-btn-sm" type="button" onClick={onClose} disabled={!!busy} aria-label="关闭"><Icon name="close" /></button>
    </header>
    <form onSubmit={event => {
      event.preventDefault();
      if (!preview?.targetReady || busy || editingTarget) return;
      void perform('保存任务', async () => {
        const result = await syncAction<{ id: string }>('create', {
          ...selection, name: name.trim(), fingerprint: preview.fingerprint,
          from: toStoragePeriod(from, grain), through: toStoragePeriod(through, grain),
        });
        onCreated(result.id);
      });
    }}>
      <div className="table-sync-form-body">
        {error && <p className="secondary-alert" role="alert">{error}</p>}
        <fieldset disabled={!!busy}><legend>目标库连接 · data_agent</legend>
          {editingTarget ? <>
            <div className="table-sync-form-grid">
              <label>目标主机<input className="da-input" value={targetDraft.host} readOnly={!!savedTarget} onChange={event => setTargetDraft({ ...targetDraft, host: event.target.value.trim() })} /></label>
              <label>目标端口<input className="da-input" type="number" min="1" max="65535" value={targetDraft.port || ''} readOnly={!!savedTarget} onChange={event => setTargetDraft({ ...targetDraft, port: Number(event.target.value) })} /></label>
              <label>目标写入账号<input className="da-input" autoComplete="off" value={targetDraft.username} onChange={event => setTargetDraft({ ...targetDraft, username: event.target.value })} /></label>
              <label>目标密码<input className="da-input" type="password" autoComplete="new-password" value={targetDraft.password} onChange={event => setTargetDraft({ ...targetDraft, password: event.target.value })} /></label>
              <SyncTls mode={targetDraft.sslMode} onChange={sslMode => setTargetDraft({ ...targetDraft, sslMode })} />
            </div>
            <div className="table-sync-inline">
              <button className="da-btn" type="button" disabled={!targetDraft.host || !targetDraft.port || !targetDraft.username || !targetDraft.password} onClick={saveTarget}>验证并保存目标库</button>
              {!savedTarget && <small>请先在 BI 数据开发手工创建平台同步表。</small>}
              {savedTarget && <button className="da-btn" type="button" onClick={() => { setTargetDraft({ ...savedTarget, password: '' }); setEditingTarget(false); }}>取消修改</button>}
            </div>
          </> : <div className="table-sync-inline">
            <span>{savedTarget?.host}:{savedTarget?.port} · {savedTarget?.username}</span>
            <button className="da-btn da-btn-sm" type="button" onClick={editTarget}>更新目标连接</button>
          </div>}
        </fieldset>
        <fieldset disabled={!!busy}><legend>源库连接</legend>
          <div className="table-sync-form-grid">
            <label>主机<input className="da-input" value={source.host} onChange={e => changeSource({ host: e.target.value.trim() })} placeholder="源库域名或 IP" /></label>
            <label>端口<input className="da-input" type="number" min="1" max="65535" value={source.port || ''} onChange={e => changeSource({ port: Number(e.target.value) })} /></label>
            <label>数据库<input className="da-input" value={source.database} onChange={e => changeSource({ database: e.target.value })} /></label>
            <label>只读账号<input className="da-input" autoComplete="off" value={source.username} onChange={e => changeSource({ username: e.target.value })} /></label>
            <label>密码<input className="da-input" type="password" autoComplete="new-password" value={source.password} onChange={e => changeSource({ password: e.target.value })} /></label>
            <SyncTls mode={source.sslMode} onChange={sslMode => changeSource({ sslMode })} />
          </div>
          <div className="table-sync-inline">
            <button className="da-btn" type="button" disabled={!source.host || !source.port || !source.database || !source.username || !source.password} onClick={searchTables}>连接并读取表</button>
            {discovery && <span>已连接 MySQL {discovery.version}</span>}
          </div>
        </fieldset>
        {discovery && <fieldset disabled={!!busy}><legend>源表与字段</legend>
          <div className="table-sync-inline table-sync-search"><input className="da-input" aria-label="搜索源表" placeholder="按表名搜索" value={search} onChange={e => setSearch(e.target.value)} />
            <button className="da-btn" type="button" onClick={searchTables}>搜索</button></div>
          <label>源表<select className="da-input" value={table} onChange={e => selectTable(e.target.value)}>
            <option value="">{discovery.tables.length ? '请选择源表' : '没有匹配的 InnoDB 表'}</option>
            {table && !discovery.tables.includes(table) && <option value={table}>{table}</option>}
            {discovery.tables.map(item => <option key={item}>{item}</option>)}
          </select></label>
          {discovery.truncated && <small>当前显示前 500 张表，可通过搜索缩小范围。</small>}
          {columns.length > 0 && <div className="table-sync-fields">
            <div className="table-sync-inline"><strong>已选 {selected.length} / {columns.length} 列</strong>
              <button className="da-btn da-btn-sm" type="button" onClick={() => { setSelected(columns.map(c => c.name)); invalidate(); }}>全选</button></div>
            <div className="table-sync-field-list">
              {columns.map(col => <label key={col.name} title={col.comment}>
                <input type="checkbox" checked={selected.includes(col.name)} onChange={event => {
                  setSelected(old => event.target.checked ? [...old, col.name] : old.filter(item => item !== col.name));
                  if (!event.target.checked && col.name === field) setField('');
                  invalidate();
                }} /><span>{col.name}</span><small>{col.type}{col.indexed ? ' · 有索引' : ''}</small>
              </label>)}
            </div>
          </div>}
        </fieldset>}
        {columns.length > 0 && <fieldset disabled={!!busy}><legend>目标表与同步范围</legend>
          <div className="table-sync-form-grid">
            <label>任务名称<input className="da-input" required maxLength={100} value={name} onChange={e => setName(e.target.value)} /></label>
            <label>目标表名<input className="da-input" required maxLength={64} value={target} onChange={e => { setTarget(e.target.value); invalidate(); }} /></label>
            <label>同步方式<select className="da-input" value={grain} onChange={e => changeGrain(e.target.value as SyncGrain)}>
              <option value="FULL">全量刷新</option><option value="DAY">按业务日</option><option value="MONTH">按业务月</option>
            </select></label>
          </div>
          {grain !== 'FULL' && <div className="table-sync-form-grid">
            <label>日期字段<select className="da-input" value={field} onChange={e => { setField(e.target.value); invalidate(); }}>
              <option value="">请选择有索引的字段</option>
              {columns.filter(col => col.indexed && selected.includes(col.name)).map(col => <option key={col.name}>{col.name}</option>)}
            </select></label>
            <label>存储格式<select className="da-input" value={format} onChange={e => { setFormat(e.target.value); invalidate(); }}>
              {formats[grain].map(item => <option key={item} value={item}>{item === 'NATIVE' ? 'MySQL 日期／时间类型' : item}</option>)}
            </select></label>
            {format === 'NATIVE' && dateField?.kind === 'datetime' && <label>时间存储时区<select className="da-input" value={zone} onChange={e => { setZone(e.target.value); invalidate(); }}>
              <option value="+08:00">东八区</option><option value="+00:00">UTC</option>
            </select></label>}
            <label>首次起始{grain === 'MONTH' ? '月' : '日'}<input className="da-input" type={grain === 'MONTH' ? 'month' : 'date'} required max={through} value={from} onChange={e => setFrom(e.target.value)} /></label>
            <label>首次截止{grain === 'MONTH' ? '月' : '日'}<input className="da-input" value={through} readOnly /></label>
          </div>}
          {grain !== 'FULL' && <div className="table-sync-inline table-sync-sample">
            <label>测试{grain === 'MONTH' ? '月份' : '日期'}<input className="da-input" type={grain === 'MONTH' ? 'month' : 'date'} value={sample} onChange={e => { setSample(e.target.value); setSampleResult(undefined); }} /></label>
            <button className="da-btn" type="button" disabled={!field || !sample} onClick={() => {
              setSampleResult(undefined);
              void perform('查询样本', async () => {
                setSampleResult(await syncAction('test-date', { ...selection, sampleDate: toStoragePeriod(sample, grain) }));
              });
            }}>测试日期查询</button>
            {sampleResult && <span role="status">{sampleResult.matches ? `命中 ${sampleResult.matches} 条样本 · ${sampleResult.sampleValues.join('、')}` : '查询成功，该期未查到数据。'}</span>}
          </div>}
          <button className="da-btn" type="button" disabled={!savedTarget || editingTarget || !target || !selected.length || (grain !== 'FULL' && !field)}
            onClick={() => void perform('核对表结构', async () => { setPreview(await syncAction('preview', selection)); })}>{preview ? '重新核对目标表' : '生成目标建表 SQL'}</button>
          {preview && <>
            <small role="status">{preview.targetReady ? '已核对目标表结构。' : '请在 BI 数据开发执行以下 SQL，再点击「重新核对目标表」。'}</small>
            <pre className="table-sync-ddl" aria-label="目标表结构">{preview.ddl}</pre>
          </>}
        </fieldset>}
      </div>
      <footer><span aria-live="polite">{busy ? `正在${busy}…` : ''}</span>
        <button className="da-btn" type="button" onClick={onClose} disabled={!!busy}>取消</button>
        <button className="da-btn da-btn-primary" type="submit" disabled={!preview?.targetReady || !!busy || editingTarget || !name.trim() || (grain !== 'FULL' && !from)}>创建并首次同步</button>
      </footer>
    </form>
  </dialog>;
}
