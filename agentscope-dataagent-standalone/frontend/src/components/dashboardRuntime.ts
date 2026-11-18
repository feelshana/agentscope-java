export interface QueryProgress { active: number; queued: number; succeeded: number; failed: number; total: number }
export interface PanelReference { id: string; title: string }
export interface PanelProgress extends PanelReference { state: 'pending' | 'loading' | 'ready' | 'empty' | 'error'; error?: string }
export interface PreviewIssue { kind: 'query' | 'runtime' | 'panel'; message: string; panelId?: string; sql?: string }
export interface PreviewReport { revision: string; initialized: boolean; settled: boolean; queries: QueryProgress; panels: PanelProgress[]; issues: PreviewIssue[] }

export function generationActive(task: import('../api/dashboards').DashboardGeneration | null): boolean { return task?.status === 'QUEUED' || task?.status === 'RUNNING' || task?.status === 'CHECKING'; }
/** Restored native turns take precedence over the bounded live reply snapshot. */
export function generationMessages(history: import('./dashboardState').DashboardMessage[], task: import('../api/dashboards').DashboardGeneration | null): import('./dashboardState').DashboardMessage[] {
  if (!task) return history;
  const userId = `generation-user-${task.id}`;
  let index = -1;
  for (let i = history.length - 1; i >= 0; i--) {
    const message = history[i];
    if (message.id === userId || (message.role === 'user' && message.text === task.message && (message.timestampMs || 0) >= Date.parse(task.startedAt) - 1000)) { index = i; break; }
  }
  if (!generationActive(task) && index >= 0 && history[index].id !== userId && history.slice(index + 1).some(message => message.role === 'assistant')) return history;
  const prefix = index < 0 ? history : history.slice(0, index);
  return [...prefix, { id: userId, role: 'user', text: task.message }, ...(task.answer ? [{ id: `generation-answer-${task.id}`, role: 'assistant' as const, text: task.answer }] : [])];
}

/** FIFO backpressure; limits govern work, not the number of charts a page may show. */
export class DashboardQueryQueue {
  private waiting: { run: () => Promise<unknown>; resolve: (value: unknown) => void; reject: (error: unknown) => void }[] = [];
  private closed = false;
  private progress: QueryProgress = { active: 0, queued: 0, succeeded: 0, failed: 0, total: 0 };
  constructor(private readonly changed: (progress: QueryProgress) => void, private readonly concurrency = 6, private readonly budget = 120) {}
  enqueue(run: () => Promise<unknown>): Promise<unknown> {
    if (this.closed) return Promise.reject(new DOMException('预览已关闭', 'AbortError'));
    this.progress.total++;
    if (this.progress.total > this.budget) {
      this.progress.failed++; this.emit();
      return Promise.reject(new Error('本次预览查询次数超过120次，请刷新或减少重复查询'));
    }
    return new Promise((resolve, reject) => { this.waiting.push({ run, resolve, reject }); this.pump(); });
  }
  close() {
    this.closed = true;
    for (const job of this.waiting.splice(0)) job.reject(new DOMException('预览已关闭', 'AbortError'));
  }
  private emit() {
    this.progress.queued = this.waiting.length;
    if (!this.closed) this.changed({ ...this.progress });
  }
  private pump() {
    while (!this.closed && this.progress.active < this.concurrency && this.waiting.length) {
      const job = this.waiting.shift()!; this.progress.active++;
      Promise.resolve().then(() => { if (this.closed) throw new DOMException('预览已关闭', 'AbortError'); return job.run(); })
        .then(value => { this.progress.succeeded++; job.resolve(value); }, error => { this.progress.failed++; job.reject(error); })
        .finally(() => { this.progress.active--; this.pump(); });
    }
    this.emit();
  }
}

export function previewSummary(report: PreviewReport): string {
  if (report.issues.length || report.panels.some(panel => panel.state === 'error')) return '预览发现问题，需要调整';
  if (!report.initialized) return '正在启动预览…';
  if (report.queries.active || report.queries.queued) return `正在取数 ${report.queries.active} 项${report.queries.queued ? ` · 排队 ${report.queries.queued} 项` : ''}`;
  if (report.panels.length) {
    const done = report.panels.filter(panel => panel.state === 'ready' || panel.state === 'empty').length;
    return done === report.panels.length ? `面板自检 ${done}/${report.panels.length} · ${report.panels.filter(panel => panel.state === 'empty').length} 项暂无数据` : `面板自检 ${done}/${report.panels.length} · 正在加载`;
  }
  if (report.queries.succeeded) return `已完成 ${report.queries.succeeded} 项查询 · 图表显示待核对`;
  return report.settled ? '页面未上报面板或查询，请核对预览内容' : '等待页面取数 · 尚无面板自检';
}

const CONTEXT = '\n\n[DataAgent看板诊断v1]\n';
export function dashboardPrompt(text: string, report?: PreviewReport | null, selected?: PanelReference | null): string {
  if (!report && !selected) return text;
  const payload = {
    revision: report?.revision, target: selected,
    panels: report?.panels.filter(panel => !selected || panel.id === selected.id).slice(0, 12).map(panel => ({ id: panel.id, title: panel.title.slice(0, 120), state: panel.state })),
    issues: report?.issues.filter(issue => !selected || !issue.panelId || issue.panelId === selected.id).slice(0, 2).map(issue => ({ ...issue, message: issue.message.slice(0, 300), sql: issue.sql?.slice(0, 900) })),
  };
  let encoded = JSON.stringify(payload);
  while (encoded.length > 3400 && payload.panels?.length) { payload.panels.pop(); encoded = JSON.stringify(payload); }
  if (encoded.length > 3400 && payload.issues) { payload.issues = payload.issues.map(issue => ({ ...issue, sql: issue.sql?.slice(0, 300) })); encoded = JSON.stringify(payload); }
  return text + CONTEXT + encoded;
}
export function dashboardUserText(text: string): string {
  const index = text.lastIndexOf(CONTEXT);
  if (index < 0) return text;
  try { const data = JSON.parse(text.slice(index + CONTEXT.length)); return data && typeof data === 'object' && ('revision' in data || 'target' in data) ? text.slice(0, index) : text; }
  catch { return text; }
}
export function canAutomaticallyRepair(report: PreviewReport): boolean {
  return report.settled && report.issues.length > 0 && !report.issues.some(issue => /权限|未授权|登录|401|403|版本已变化|新模型|超时|没有新进度|5\d\d|不可用|连接|网络|次数超过|network|fetch|timeout|unauthor|forbidden/i.test(issue.message));
}
