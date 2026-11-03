import React from 'react';
import type { BiSyncStatus } from '../api/biSync';
import Icon from './Icon';

const stateLabels: Record<string, string> = {
  NOT_CONFIGURED: '未配置', NOT_INITIALIZED: '待初始化', UNSUPPORTED: '版本待核对',
  UNAVAILABLE: '暂不可用', NOT_STARTED: '尚未运行', RUNNING: '同步中', PAUSING: '正在暂停',
  PAUSED: '已暂停', SUCCESS: '最近执行成功', FAILED: '执行失败', INTERRUPTED: '执行中断', IDLE: '等待执行',
};
const freshnessLabels: Record<string, string> = {
  CURRENT: '已更新至昨天', WAITING: '等待今日任务完成', STALE: '数据待更新', NONE: '尚无完整数据', UNKNOWN: '待核对',
};

export function syncTime(value: string | null): string {
  if (!value || Number.isNaN(Date.parse(value))) return '—';
  return new Intl.DateTimeFormat('zh-CN', { timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit',
    day: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false }).format(new Date(value));
}

export function syncDuration(value: number | null): string {
  if (value === null || value < 0) return '—';
  return value < 1000 ? `${value} 毫秒` : `${(value / 1000).toFixed(1)} 秒`;
}

export function BiSyncStatusPanel({ data }: { data: BiSyncStatus }) {
  const problem = ['FAILED', 'INTERRUPTED', 'UNAVAILABLE', 'UNSUPPORTED'].includes(data.state);
  const run = data.lastRun;
  const tone = problem || data.freshness === 'STALE' ? 'is-warning'
    : data.running ? 'is-running' : data.state === 'SUCCESS' && data.freshness === 'CURRENT' && !data.paused ? 'is-normal' : 'is-muted';
  return <>
    <section className="bi-sync-overview" aria-label="同步概览">
      <div className="bi-sync-overview-title"><span className="bi-sync-mark"><Icon name="database" /></span>
        <div><h2>BI公共知识库数据</h2><p>7 张业务表 · 基础表每日刷新，日志按进度补齐</p></div>
        <span className={`bi-sync-badge ${tone}`}>
          {stateLabels[data.state] || '待核对'}
        </span>
      </div>
      <p className={`bi-sync-message ${problem ? 'is-warning' : ''}`} role={problem ? 'alert' : 'status'}>{data.message}</p>
      <dl className="bi-sync-metrics">
        <div><dt>数据已完整同步至</dt><dd>{data.completedThrough || '尚未完成首次同步'}</dd></div>
        <div><dt>最近一次成功</dt><dd>{syncTime(data.lastSuccessAt)}</dd></div>
        <div><dt>数据更新情况</dt><dd className={data.freshness === 'STALE' ? 'bi-sync-stale' : ''}>{freshnessLabels[data.freshness] || '待核对'}</dd></div>
      </dl>
    </section>
    <div className="bi-sync-grid">
      <section className="bi-sync-card" aria-labelledby="bi-sync-range"><h2 id="bi-sync-range">日志数据范围</h2>
        <p>显示完整日的截止日期，时间口径为北京时间。</p>
        <dl className="bi-sync-details"><div><dt>报表日志</dt><dd>{data.reportThrough ? `截至 ${data.reportThrough}` : '尚无完整记录'}</dd></div>
          <div><dt>仪表盘日志</dt><dd>{data.dashboardThrough ? `截至 ${data.dashboardThrough}` : '尚无完整记录'}</dd></div></dl>
        <p className="bi-sync-caption">保留全部操作记录，不能区分查看与导出。</p>
      </section>
      <section className="bi-sync-card" aria-labelledby="bi-sync-run"><h2 id="bi-sync-run">最近一次执行</h2>
        {run ? <><dl className="bi-sync-details"><div><dt>开始时间</dt><dd>{syncTime(run.startedAt)}</dd></div>
          <div><dt>结束时间</dt><dd>{syncTime(run.finishedAt)}</dd></div>
          <div><dt>耗时</dt><dd>{run.finishedAt ? syncDuration(run.durationMs) : '尚无结束记录'}</dd></div></dl>
          {run.message && <p className="bi-sync-failure">{run.message}</p>}</>
          : <p className="bi-sync-empty">尚无执行记录。完成服务器首次同步后，可在这里查看结果。</p>}
      </section>
    </div>
  </>;
}
