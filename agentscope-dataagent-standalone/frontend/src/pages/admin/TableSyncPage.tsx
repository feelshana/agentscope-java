import React, { useEffect, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { getSyncTask, listSyncTasks, syncAction } from '../../api/tableSync';
import type { SyncDetail, SyncGrain, SyncList, SyncMonthlyPlan, SyncRun } from '../../api/tableSync';
import Icon from '../../components/Icon';
import { KnowledgeBreadcrumb } from '../../components/KnowledgeWorkspace';
import TableSyncWizard, { displayPeriod, previousPeriod, toStoragePeriod } from './TableSyncWizard';
import '../../secondary-pages.css';
import './table-sync.css';

const grains = { FULL: '全量刷新', DAY: '按业务日', MONTH: '按业务月' };
const states = { QUEUED: '排队中', RUNNING: '同步中', SUCCESS: '成功', FAILED: '失败' };
const kinds: Record<string, string> = { INITIAL: '首次同步', DAILY: '每日计划', MONTHLY: '每月计划', MANUAL: '手动执行' };
const phases: Record<string, string> = { CHECKING: '核对源表和目标表', SCANNING: '扫描源表并匹配日期', READING: '分批写入目标' };
const runErrors: Record<string, string> = {
  EMPTY_RESULT: '源端本次返回零行，已保留原数据。',
  SOURCE_QUERY: '源库连接或读取失败。',
  SOURCE_NOT_ALLOWED: '源地址已不在允许列表。',
  SOURCE_TIME_ZONE: '源端 TIMESTAMP 会话时区不受支持。',
  SCHEMA_CHANGED: '源字段结构有变化，请先核对源表。',
  DATE_INDEX_REQUIRED: '源表日期字段已失去可用索引。',
  DATE_FIELD_REQUIRED: '请从已选列中选择业务日期字段。',
  NULL_DATE: '源表日期字段含空值。',
  UNSUPPORTED_TABLE: '源表类型或存储引擎已变更。',
  UNSUPPORTED_COLUMN: '源表列定义变更为不支持的类型。',
  INVALID_FORMAT: '源列类型与日期格式不匹配。',
  INVALID_SOURCE_FILTER: '源筛选列已不存在，或不再支持此等值条件。',
  TARGET_EXISTS: '目标表名已被占用。',
  TARGET_OWNERSHIP: '目标表归属或结构与任务不符。',
  CREDENTIAL_ERROR: '无法读取旧任务的同步凭证。',
  ROW_LIMIT: '超过单次事务的行数或体积限制，本期写入已回滚。',
  UNINDEXED_SCAN_LIMIT: '无源索引时，整张源表最多扫描 10 万行或估算 128 MiB。本次扫描超限。',
  UNINDEXED_BUFFER_LIMIT: '选定日期范围匹配的数据超过 64 MiB 缓冲上限，请缩小同步范围。',
  TARGET_NOT_EMPTY: '首次同步需要空目标表或空周期。',
  ROW_TOO_LARGE: '源表单行数据超过 4 MiB 的写入限制。',
  TRANSFER_ERROR: '数据读取或目标写入失败，请检查连接和字段内容。',
  COUNT_MISMATCH: '读取与写入行数不一致，已停止。',
  DATABASE_ERROR: '数据库操作失败，请检查连接、权限或目标结构。',
};
const shanghaiTime = new Intl.DateTimeFormat('zh-CN', {
  month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit',
  hour12: false, timeZone: 'Asia/Shanghai',
});
const formatTime = (value?: string | null) => value ? shanghaiTime.format(new Date(value)) : '—';
const range = (from: string | null, through: string | null, grain: SyncGrain) =>
  from === through ? displayPeriod(from, grain) : `${displayPeriod(from, grain)} 至 ${displayPeriod(through, grain)}`;
const active = (run: SyncRun | null) => run?.state === 'QUEUED' || run?.state === 'RUNNING';

export default function TableSyncPage() {
  const [params, setParams] = useSearchParams();
  const id = params.get('task');
  const [list, setList] = useState<SyncList>();
  const [details, setDetails] = useState<SyncDetail>();
  const [newTask, setNewTask] = useState(false);
  const [revision, setRevision] = useState(0);
  const [error, setError] = useState('');
  const detail = details?.id === id ? details : undefined;

  useEffect(() => {
    const controller = new AbortController();
    let timer: ReturnType<typeof setTimeout>;
    async function refresh() {
      try {
        const [tasks, selected] = await Promise.all([
          listSyncTasks(controller.signal),
          id ? getSyncTask(id, controller.signal).catch((reason: unknown) => reason) : Promise.resolve(undefined),
        ]);
        if (controller.signal.aborted) return;
        setList(tasks);
        if (selected instanceof Error) {
          setError(selected.message);
        } else {
          setDetails(selected as SyncDetail | undefined);
          setError('');
        }
      } catch (reason) {
        if (!controller.signal.aborted) setError(reason instanceof Error ? reason.message : '读取任务失败。');
      } finally {
        if (!controller.signal.aborted) timer = setTimeout(() => void refresh(), 5000);
      }
    }
    void refresh();
    return () => { controller.abort(); clearTimeout(timer); };
  }, [id, revision]);

  return <div className="kw-page kw-secondary-page table-sync-page">
    <KnowledgeBreadcrumb items={[{ label: '更多', to: '/more' }, { label: '数据同步' }]} />
    <div className="kw-scroll"><div className="kw-content table-sync-content">
      <header className="kw-pagehead">
        <div className="kw-heading"><h1>数据同步</h1><p>将 MySQL 源表复制到 data_agent，管理首次导入、定时更新和历史补跑。</p></div>
        <div className="table-sync-actions">
          <Link className="da-btn" to="/admin/bi-sync/legacy"><Icon name="database" />已有 BI 同步监控</Link>
          <button className="da-btn da-btn-primary" type="button" disabled={!list} onClick={() => setNewTask(true)}>新建同步任务</button>
        </div>
      </header>
      {error && <div className="secondary-alert" role="alert">{error} <button type="button" className="da-btn da-btn-sm" onClick={() => setRevision(value => value + 1)}>重试</button></div>}
      {!list && !error && <p role="status">正在读取同步任务…</p>}
      {list?.targetError && <p className="secondary-alert" role="alert">无法读取目标库的任务。可在「新建同步任务」中检查或更新目标库连接。</p>}
      {list && !id && <section className="table-sync-card">
        <div className="table-sync-section-head"><h2>同步任务 <small>{list.tasks.length}</small></h2>
          <button type="button" className="da-btn da-btn-sm" onClick={() => setRevision(value => value + 1)}>刷新</button></div>
        {list.tasks.length === 0 ? <div className="table-sync-empty"><Icon name="database" /><h3>还没有同步任务</h3>
          <p>{list.target ? '选择一张源表，确认字段及目标表后开始首次同步。' : '填写目标库和源库连接后，选择要同步的表。'}</p>
          <button type="button" className="da-btn" onClick={() => setNewTask(true)}>创建第一个任务</button></div> :
          <div className="table-sync-scroll"><table className="table-sync-table">
            <thead><tr><th>任务</th><th>同步方式</th><th>运行状态</th><th>下次计划（东八区）</th><th>操作</th></tr></thead>
            <tbody>{list.tasks.map(task => <tr key={task.id}>
              <td><Link className="table-sync-link" to={`?task=${task.id}`}>{task.name}</Link><small>{task.sourceTable} → {task.targetTable}</small></td>
              <td>{grains[task.grain]}</td><td>{task.lastRun && <RunStatus run={task.lastRun} />}</td>
              <td>{task.nextRunAt ? formatTime(task.nextRunAt) : '未启用'}</td>
              <td><Link className="da-btn da-btn-sm" to={`?task=${task.id}`}>查看</Link></td>
            </tr>)}</tbody>
          </table></div>}
      </section>}
      {id && <button className="da-btn da-btn-sm table-sync-back" type="button" onClick={() => setParams({})}>返回任务列表</button>}
      {id && !detail && !error && <p role="status">正在读取任务详情…</p>}
      {detail && list?.today && <TaskDetail key={id} task={detail} today={list.today} onChange={() => setRevision(value => value + 1)} />}
      {newTask && list?.today && <TableSyncWizard today={list.today} target={list.target}
        onTargetSaved={() => setRevision(value => value + 1)} onClose={() => setNewTask(false)}
        onCreated={taskId => { setNewTask(false); setParams({ task: taskId }); }} />}
    </div></div>
  </div>;
}

function RunStatus({ run }: { run: SyncRun }) {
  return <span className={`table-sync-status is-${run.state.toLowerCase()}`}>{states[run.state]}</span>;
}

function TaskDetail({ task, today, onChange }: { task: SyncDetail; today: string; onChange: () => void }) {
  const [time, setTime] = useState(task.scheduleTime ?? '02:00');
  const [monthlyPlan, setMonthlyPlan] = useState<SyncMonthlyPlan>(task.monthlyPlan ?? 'MONTHLY');
  const [from, setFrom] = useState('');
  const [through, setThrough] = useState('');
  const [acceptEmpty, setAcceptEmpty] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [message, setMessage] = useState('');
  const running = task.runs.some(active);
  const latestPeriod = previousPeriod(today, task.grain);
  const periodType = task.grain === 'MONTH' ? 'month' : 'date';
  const periodName = task.grain === 'MONTH' ? '月份' : '日期';

  async function change(action: string, data: object, notice: string) {
    setBusy(true); setError(''); setMessage('');
    try {
      await syncAction(action, { id: task.id, ...data });
      setAcceptEmpty(false); setMessage(notice); onChange();
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : '操作失败，请重试。');
    } finally { setBusy(false); }
  }

  return <div className="table-sync-detail">
    {error && <p className="secondary-alert" role="alert">{error}</p>}
    {message && <p className="table-sync-feedback" role="status">{message}</p>}
    <section className="table-sync-card">
      <div className="table-sync-section-head"><div><h2>{task.name}</h2><p>{task.sourceTable} → data_agent.{task.targetTable}</p>
        {task.sourceFilter && <p>源表筛选 · {task.sourceFilter.field} 等于 {task.sourceFilter.value}</p>}</div>
        {task.lastRun && <RunStatus run={task.lastRun} />}</div>
      <dl className="table-sync-stats">
        <div><dt>同步方式</dt><dd>{grains[task.grain]}</dd></div>
        <div><dt>首次导入</dt><dd>{task.initialized ? '已完成' : '待完成'}</dd></div>
        {task.grain !== 'FULL' && <>
          <div><dt>首次导入范围</dt><dd>{range(task.from, task.through, task.grain)}</dd></div>
          <div><dt>连续覆盖至</dt><dd>{displayPeriod(task.continuousThrough, task.grain)}</dd></div>
          <div><dt>已同步的空期</dt><dd>{task.emptyPeriods ?? 0}</dd></div>
          {task.grain === 'MONTH' && task.currentMonthRows != null &&
            <div><dt>{today.slice(0, 7)} 当月累计</dt><dd>{task.currentMonthRows.toLocaleString()} 行</dd></div>}
        </>}
      </dl>
      {!!task.gaps?.length && <details className="table-sync-gaps"><summary>尚未同步 · {task.gaps.length} 段</summary>
        <ul>{task.gaps.map(item => <li key={item.from}>{range(item.from, item.through, task.grain)}</li>)}</ul></details>}
    </section>

    <section className="table-sync-card"><h2>运行计划</h2>
      <p>{task.grain === 'MONTH' ? '可按月同步上月，或每天刷新上月与当月。' : task.grain === 'DAY' ? '每天刷新前两个完整业务日。' : '每天刷新整张表。'}计划时间采用东八区。到点后任务进入队列，请安排在上游落数之后。</p>
      {!task.initialized && <p>首次同步完成后可以启用计划。</p>}
      {task.nextRunAt && <p>下次 {formatTime(task.nextRunAt)}{task.nextPeriod ? ` · 同步 ${range(task.nextPeriodFrom ?? task.nextPeriod, task.nextPeriod, task.grain)}` : ''}</p>}
      <div className="table-sync-inline">
        {task.grain === 'MONTH' && <label>计划频率<select className="da-input" value={monthlyPlan}
          onChange={event => setMonthlyPlan(event.target.value as SyncMonthlyPlan)} disabled={!task.initialized || busy}>
          <option value="MONTHLY">每月 1 日同步上月</option>
          <option value="DAILY">每天刷新上月和当月</option>
        </select></label>}
        <label>计划时间<input className="da-input" type="time" value={time} onChange={event => setTime(event.target.value)} disabled={!task.initialized || busy} /></label>
        <button className="da-btn" type="button" disabled={!task.initialized || !time || busy}
          onClick={() => void change('schedule', { time, monthlyPlan: task.grain === 'MONTH' ? monthlyPlan : undefined }, '运行计划已保存。')}>{task.scheduleTime ? '更新计划' : '启用计划'}</button>
        {task.scheduleTime && <button className="da-btn" type="button" disabled={busy}
          onClick={() => void change('schedule', { time: null }, '已暂停后续计划。')}>暂停计划</button>}
      </div>
    </section>

    <section className="table-sync-card"><h2>手动执行</h2>
      <p>{running ? '已有任务排队或运行中。' : !task.initialized ? '继续首次同步，已提交的历史分段无需重复读取。' :
        task.grain === 'FULL' ? '立即刷新整张表。' : `立即同步 ${latestPeriod}，或选择历史${periodName}补跑。`}</p>
      {task.initialized && task.grain !== 'FULL' && <div className="table-sync-inline">
        <label>补跑起始{periodName}<input className="da-input" type={periodType} min={displayPeriod(task.from, task.grain)} max={latestPeriod} value={from} onChange={e => setFrom(e.target.value)} /></label>
        <label>补跑截止{periodName}<input className="da-input" type={periodType} min={from || displayPeriod(task.from, task.grain)} max={latestPeriod} value={through} onChange={e => setThrough(e.target.value)} /></label>
        <button className="da-btn" type="button" disabled={!from || !through || from > through || running || busy}
          onClick={() => void change('rerun', { from: toStoragePeriod(from, task.grain), through: toStoragePeriod(through, task.grain), acceptEmpty }, '已提交补跑，状态见运行记录。')}>补跑所选范围</button>
      </div>}
      {task.initialized && task.lastRun?.error_code === 'EMPTY_RESULT' && <label className="table-sync-checkbox">
        <input type="checkbox" checked={acceptEmpty} onChange={e => setAcceptEmpty(e.target.checked)} />
        <span>我已确认源数据确实为空，允许本次手动执行将对应目标数据更新为空。</span>
      </label>}
      <button className="da-btn" type="button" disabled={running || busy}
        onClick={() => void change('run-now', { acceptEmpty }, '已提交同步，状态见运行记录。')}>{task.initialized ? '立即运行' : '继续首次同步'}</button>
    </section>

    <section className="table-sync-card"><h2>最近运行记录</h2>
      <div className="table-sync-scroll"><table className="table-sync-table">
        <thead><tr><th>任务</th><th>业务范围</th><th>状态与进度</th><th>读取／写入</th><th>开始／结束</th></tr></thead>
        <tbody>{task.runs.map(run => <tr key={run.id}><td>{kinds[run.kind] ?? run.kind}<small>{run.actor}</small></td>
          <td>{run.date_from ? range(run.date_from, run.date_through, task.grain) : '整表'}</td>
          <td><RunStatus run={run} />{run.error_code ? <small>{run.progress_date && `${displayPeriod(run.progress_date, task.grain)} · `}{runErrors[run.error_code] ?? '任务异常，请重试。'}</small> :
            active(run) && run.phase && <small>{phases[run.phase]} {run.progress_date ? displayPeriod(run.progress_date, task.grain) : ''}</small>}</td>
          <td>{run.rows_read.toLocaleString()} / {run.rows_committed.toLocaleString()}</td>
          <td>{formatTime(run.started_at ?? run.queued_at)}<small>{formatTime(run.finished_at)}</small></td></tr>)}</tbody>
      </table></div>
    </section>
  </div>;
}
