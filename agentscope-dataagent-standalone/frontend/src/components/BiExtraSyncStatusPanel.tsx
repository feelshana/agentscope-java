import React from 'react';
import type { BiExtraSyncStatus } from '../api/biSync';
import { syncTime, syncDuration } from './BiSyncStatusPanel';
import Icon from './Icon';

const labels: Record<string, string> = { NOT_INITIALIZED: '待初始化', NOT_CONFIGURED: '未配置',
  UNAVAILABLE: '暂不可用', UNSUPPORTED: '版本待核对', NOT_STARTED: '尚未运行', RUNNING: '执行中',
  PAUSING: '正在暂停', PAUSED: '已暂停', SUCCESS: '最近执行成功', SUCCESS_WITH_GAPS: '执行完成 · 有历史缺口',
  FAILED: '执行失败', INTERRUPTED: '执行中断', CURRENT: '已更新', STALE: '待更新', GAPS: '有缺口', UNKNOWN: '待核对' };

export function BiExtraSyncStatusPanel({ data }: { data: BiExtraSyncStatus }) {
  const problem = ['FAILED', 'INTERRUPTED', 'UNAVAILABLE', 'UNSUPPORTED'].includes(data.state);
  const run = data.lastRun;
  return <>
    <section className="bi-sync-overview" aria-label="扩展同步概览">
      <div className="bi-sync-overview-title"><span className="bi-sync-mark"><Icon name="database" /></span>
        <div><h2>BI业务扩展数据</h2><p>6 张业务表 · 多源独立任务，快照与按日数据分别同步</p></div>
        <span className={`bi-sync-badge ${problem || data.hasGaps ? 'is-warning' : data.running ? 'is-running' : 'is-muted'}`}>{labels[data.state] || '待核对'}</span>
      </div>
      <p className={`bi-sync-message ${problem ? 'is-warning' : ''}`} role={problem ? 'alert' : 'status'}>{data.message}</p>
      <dl className="bi-sync-metrics">
        <div><dt>最近一次操作</dt><dd>{run ? data.action === 'VERIFY' ? '数据核验（非同步）' : data.action === 'SYNC' ? '数据同步' : '操作类型待核对' : '尚无执行记录'}</dd></div>
        <div><dt>结束时间</dt><dd>{syncTime(run?.finishedAt ?? null)}</dd></div>
        <div><dt>历史覆盖</dt><dd>{data.tables.length === 0 ? '尚无状态记录' : data.hasGaps ? '存在缺失区间，请逐表核对' : '请以逐表进度为准'}</dd></div>
      </dl>
    </section>
    {run && <section className="bi-sync-card bi-extra-run"><h2>最近一次执行</h2><dl className="bi-sync-details">
      <div><dt>开始时间</dt><dd>{syncTime(run.startedAt)}</dd></div>
      <div><dt>执行耗时</dt><dd>{run.finishedAt ? syncDuration(run.durationMs) : '尚无结束记录'}</dd></div>
    </dl>{run.message && <p className="bi-sync-failure">{run.message}</p>}</section>}
    {data.tables.length > 0 && <section className="bi-sync-card bi-extra-tables"><h2>逐表同步情况</h2>
      <p>日期均为北京时间。处理至某日不代表此前无缺口；当前快照不代表历史快照。</p>
      <div className="bi-extra-table-scroll"><table><thead><tr><th>数据表</th><th>同步方式</th><th>最近进度</th><th>数据情况</th></tr></thead>
        <tbody>{data.tables.map(table => <tr key={table.name}>
          <td><strong>{table.label}</strong><code>{table.name}</code></td>
          <td>{table.snapshot ? '当前全量快照' : '按完整业务日'}</td>
          <td>{table.snapshot ? syncTime(table.refreshedAt) : table.processedThrough ? `处理至 ${table.processedThrough}` : '尚无完成记录'}
            {!table.snapshot && table.coverageFrom && <small>已覆盖起日 {table.coverageFrom}</small>}
            {!table.snapshot && table.availableFrom && <small>源可用起日 {table.availableFrom}</small>}
            {table.snapshot && table.rowCount !== null && <small>{table.rowCount.toLocaleString('zh-CN')} 行（最近快照）</small>}
          </td>
          <td><span className={['STALE', 'GAPS'].includes(table.state) ? 'bi-sync-stale' : ''}>{labels[table.state] || '待核对'}</span>
            {table.gapCount > 0 && <details><summary>查看 {table.gapCount} 段缺口</summary>
              {table.gaps.map((gap, i) => <p key={i}>{gap.from} 至 {gap.through}<br />{gap.reason}</p>)}
              {table.gapCount > table.gaps.length && <p>此处展示前 {table.gaps.length} 段，完整记录请在服务器查看。</p>}
            </details>}
          </td>
        </tr>)}</tbody></table></div>
    </section>}
  </>;
}
