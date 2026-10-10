import React, { useCallback, useEffect, useRef, useState } from 'react';
import { KnowledgeBreadcrumb, WorkspaceMenu } from '../../components/KnowledgeWorkspace';
import Icon from '../../components/Icon';
import { toast } from '../../components/Toast';
import {
  createScheduledTask,
  deleteScheduledTask,
  listScheduledTasks,
  ScheduledTask,
  updateTaskStatus,
} from '../../api/scheduledTasks';
import { ACTIVE_AGENT_ID } from '../../api/activeAgent';

const FREQ_LABEL: Record<string, string> = {
  daily: '每日',
  weekly: '每周',
  monthly: '每月',
};

const emptyDraft = {
  title: '',
  prompt: '',
  email: '',
  knowledgeBaseId: '',
  agentId: ACTIVE_AGENT_ID,
  scheduleFrequency: 'daily',
  scheduleTime: '00:00',
  effectiveFrom: 'permanent',
};

export default function ScheduledTasksPage() {
  const [tasks, setTasks] = useState<ScheduledTask[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [createOpen, setCreateOpen] = useState(false);
  const [draft, setDraft] = useState(emptyDraft);
  const [busy, setBusy] = useState(false);
  const [loading, setLoading] = useState(true);
  const [query, setQuery] = useState('');

  const request = useRef<AbortController | null>(null);
  const refresh = useCallback(async () => {
    request.current?.abort();
    const controller = new AbortController();
    request.current = controller;
    setLoading(true);
    try {
      const result = await listScheduledTasks(controller.signal);
      if (controller.signal.aborted) return;
      setTasks(result);
      setError(null);
    } catch (e) {
      if (controller.signal.aborted) return;
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      if (request.current === controller) setLoading(false);
    }
  }, []);

  useEffect(() => {
    refresh();
    return () => request.current?.abort();
  }, [refresh]);

  async function handleCreate() {
    if (!draft.title.trim()) {
      setError('请输入任务标题');
      return;
    }
    if (!draft.prompt.trim()) {
      setError('请输入提示词');
      return;
    }
    if (!draft.agentId) {
      setError('请选择 Agent');
      return;
    }
    setBusy(true);
    setError(null);
    try {
      await createScheduledTask({
        title: draft.title.trim(),
        prompt: draft.prompt.trim(),
        email: draft.email.trim() || undefined,
        knowledgeBaseId: draft.knowledgeBaseId || undefined,
        agentId: draft.agentId,
        scheduleFrequency: draft.scheduleFrequency,
        scheduleTime: draft.scheduleTime,
        effectiveFrom: draft.effectiveFrom || 'permanent',
      });
      setCreateOpen(false);
      setDraft(emptyDraft);
      toast('创建成功', 'success');
      await refresh();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  }

  async function handleToggleStatus(task: ScheduledTask) {
    const newStatus = task.status === 'active' ? 'paused' : 'active';
    try {
      await updateTaskStatus(task.id, newStatus);
      toast(newStatus === 'active' ? '任务已恢复' : '任务已暂停', 'success');
      await refresh();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
  }

  async function handleDelete(task: ScheduledTask) {
    if (!window.confirm(`确定删除任务「${task.title}」？`)) return;
    try {
      await deleteScheduledTask(task.id);
      toast('任务已删除', 'success');
      await refresh();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
  }

  function formatDate(iso: string): string {
    if (!iso) return '';
    try {
      const d = new Date(iso);
      return `${d.getFullYear()}/${d.getMonth() + 1}/${d.getDate()}`;
    } catch {
      return iso;
    }
  }

  return (
    <div className="kw-page kw-secondary-page">
      <KnowledgeBreadcrumb items={[{ label: '返回对话', to: '/chat' }, { label: '例行任务' }]} />
      <div className="kw-scroll"><div className="kw-content">
        <div className="kw-pagehead"><div className="kw-heading"><h1>例行任务</h1><p>管理周期性分析任务，集中查看执行计划与任务状态。</p></div><button className="da-btn da-btn-primary" onClick={() => setCreateOpen(true)} disabled={busy}><Icon name="plus" size="sm" />创建任务</button></div>
        <div className="kw-toolbar"><label className="kw-search"><Icon name="search" size="sm" /><input aria-label="搜索任务" placeholder="搜索任务名称或分析问题" value={query} onChange={e => setQuery(e.target.value)} /></label><span className="kw-count">{tasks.length} 个任务</span><span className="kw-spacer" /><button className="da-btn da-btn-ghost" onClick={refresh} disabled={loading}><Icon name="refresh" size="sm" />刷新</button></div>
        {error && <div role="alert" className="kw-alert">{error}</div>}
        {loading ? <div className="kw-empty">加载任务…</div> : tasks.length === 0 ? <div className="kw-task-empty"><div className="kw-icon"><Icon name="clock" /></div><h2>安排你的下一次分析</h2><p>创建任务并设置执行计划，在这里管理周期性的数据问题。</p><button className="da-btn da-btn-primary" onClick={() => setCreateOpen(true)}>创建第一个任务</button></div> : <div className="kw-tablebox"><div className="kw-table-scroll"><table className="kw-table"><thead><tr><th>任务名称 / 分析问题</th><th>执行计划</th><th>状态</th><th>运行次数</th><th>创建时间</th><th>操作</th></tr></thead><tbody>{tasks.filter(t => `${t.title} ${t.prompt}`.toLowerCase().includes(query.toLowerCase())).map(task => <tr key={task.id}><td style={{ whiteSpace: 'normal', maxWidth: 440 }}><strong>{task.title}</strong><div className="kw-task-prompt" title={task.prompt}>{task.prompt}</div></td><td>{FREQ_LABEL[task.scheduleFrequency] || task.scheduleFrequency} · {task.scheduleTime}</td><td><span className="kw-status">{task.status === 'active' ? '已启用' : '已暂停'}</span></td><td>{task.runCount}</td><td>{formatDate(task.createdAt)}</td><td><WorkspaceMenu label={`${task.title}操作`} items={[{ label: task.status === 'active' ? '暂停任务' : '恢复任务', icon: 'clock', onClick: () => { void handleToggleStatus(task); } }, { label: '删除任务', icon: 'trash', danger: true, onClick: () => { void handleDelete(task); } }]} /></td></tr>)}</tbody></table></div>{tasks.filter(t => `${t.title} ${t.prompt}`.toLowerCase().includes(query.toLowerCase())).length === 0 && <div className="kw-empty">没有匹配的任务</div>}</div>}
      </div></div>

      {createOpen && (
        <div
          style={{
            position: 'fixed',
            inset: 0,
            background: 'rgba(0,0,0,0.4)',
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            zIndex: 1000,
          }}
          onClick={() => setCreateOpen(false)}
        >
          <div
            style={{
              background: 'var(--da-surface)',
              borderRadius: 12,
              width: '100%',
              maxWidth: 560,
              maxHeight: '90vh',
              overflow: 'auto',
              boxShadow: 'var(--da-shadow-pop)',
            }}
            onClick={(e) => e.stopPropagation()}
          >
            <div
              style={{
                padding: '20px 24px',
                borderBottom: '1px solid var(--da-border)',
                display: 'flex',
                justifyContent: 'space-between',
                alignItems: 'center',
              }}
            >
              <div style={{ fontSize: 18, fontWeight: 600, color: 'var(--da-text)' }}>创建定时任务</div>
              <button
                onClick={() => setCreateOpen(false)}
                className="da-btn da-btn-ghost da-btn-sm"
              >
                <Icon name="close" size="sm" />
              </button>
            </div>

            <div style={{ padding: 24, display: 'flex', flexDirection: 'column', gap: 20 }}>
              <div>
                <label style={{ display: 'block', fontSize: 14, fontWeight: 500, color: 'var(--da-text)', marginBottom: 8 }}>
                  任务标题 <span style={{ color: 'var(--da-danger)' }}>*</span>
                </label>
                <input
                  type="text"
                  value={draft.title}
                  onChange={(e) => setDraft({ ...draft, title: e.target.value })}
                  placeholder="展示在任务列表中，建议明确表达任务用途"
                  style={{
                    width: '100%',
                    padding: '10px 12px',
                    border: '1px solid var(--da-border)',
                    borderRadius: 8,
                    fontSize: 14,
                    background: 'var(--da-surface)',
                    color: 'var(--da-text)',
                    boxSizing: 'border-box',
                  }}
                />
              </div>

              <div>
                <label style={{ display: 'block', fontSize: 14, fontWeight: 500, color: 'var(--da-text)', marginBottom: 8 }}>
                  提示词 <span style={{ color: 'var(--da-danger)' }}>*</span>
                </label>
                <textarea
                  value={draft.prompt}
                  onChange={(e) => setDraft({ ...draft, prompt: e.target.value })}
                  placeholder="Agent 每次执行时收到的固定 Prompt（等同于对话输入）"
                  rows={6}
                  style={{
                    width: '100%',
                    padding: '10px 12px',
                    border: '1px solid var(--da-border)',
                    borderRadius: 8,
                    fontSize: 14,
                    background: 'var(--da-surface)',
                    color: 'var(--da-text)',
                    resize: 'vertical',
                    fontFamily: 'inherit',
                    boxSizing: 'border-box',
                  }}
                />
                <div style={{ fontSize: 12, color: 'var(--da-text-muted)', textAlign: 'right', marginTop: 4 }}>
                  {draft.prompt.length} / 5000
                </div>
              </div>

              <div>
                <label style={{ display: 'block', fontSize: 14, fontWeight: 500, color: 'var(--da-text)', marginBottom: 8 }}>
                  邮箱推送地址
                </label>
                <input
                  type="email"
                  value={draft.email}
                  onChange={(e) => setDraft({ ...draft, email: e.target.value })}
                  placeholder="输入邮箱，定时任务完成后将结果发送至此"
                  style={{
                    width: '100%',
                    padding: '10px 12px',
                    border: '1px solid var(--da-border)',
                    borderRadius: 8,
                    fontSize: 14,
                    background: 'var(--da-surface)',
                    color: 'var(--da-text)',
                    boxSizing: 'border-box',
                  }}
                />
              </div>

              <div>
                <label style={{ display: 'block', fontSize: 14, fontWeight: 500, color: 'var(--da-text)', marginBottom: 8 }}>
                  知识库
                </label>
                <select
                  value={draft.knowledgeBaseId}
                  onChange={(e) => setDraft({ ...draft, knowledgeBaseId: e.target.value })}
                  style={{
                    width: '100%',
                    padding: '10px 12px',
                    border: '1px solid var(--da-border)',
                    borderRadius: 8,
                    fontSize: 14,
                    background: 'var(--da-surface)',
                    color: 'var(--da-text)',
                    boxSizing: 'border-box',
                  }}
                >
                  <option value="">为本次任务执行注入额外数据上下文</option>
                </select>
              </div>

              <div>
                <label style={{ display: 'block', fontSize: 14, fontWeight: 500, color: 'var(--da-text)', marginBottom: 8 }}>
                  Agent <span style={{ color: 'var(--da-danger)' }}>*</span>
                </label>
                <select
                  value={draft.agentId}
                  onChange={(e) => setDraft({ ...draft, agentId: e.target.value })}
                  style={{
                    width: '100%',
                    padding: '10px 12px',
                    border: '1px solid var(--da-border)',
                    borderRadius: 8,
                    fontSize: 14,
                    background: 'var(--da-surface)',
                    color: 'var(--da-text)',
                    boxSizing: 'border-box',
                  }}
                >
                  <option value={ACTIVE_AGENT_ID}>Data Agent（默认）</option>
                </select>
              </div>

              <div>
                <label style={{ display: 'block', fontSize: 14, fontWeight: 500, color: 'var(--da-text)', marginBottom: 8 }}>
                  执行时间 <span style={{ color: 'var(--da-danger)' }}>*</span>
                </label>
                <div style={{ display: 'flex', gap: 12 }}>
                  <select
                    value={draft.scheduleFrequency}
                    onChange={(e) => setDraft({ ...draft, scheduleFrequency: e.target.value })}
                    style={{
                      flex: 1,
                      padding: '10px 12px',
                      border: '1px solid var(--da-border)',
                      borderRadius: 8,
                      fontSize: 14,
                      background: 'var(--da-surface)',
                      color: 'var(--da-text)',
                    }}
                  >
                    <option value="daily">每天</option>
                    <option value="weekly">每周</option>
                    <option value="monthly">每月</option>
                  </select>
                  <input
                    type="time"
                    value={draft.scheduleTime}
                    onChange={(e) => setDraft({ ...draft, scheduleTime: e.target.value })}
                    style={{
                      flex: 1,
                      padding: '10px 12px',
                      border: '1px solid var(--da-border)',
                      borderRadius: 8,
                      fontSize: 14,
                      background: 'var(--da-surface)',
                      color: 'var(--da-text)',
                    }}
                  />
                </div>
              </div>

              <div>
                <label style={{ display: 'block', fontSize: 14, fontWeight: 500, color: 'var(--da-text)', marginBottom: 8 }}>
                  生效时间
                </label>
                <input
                  type="date"
                  value={draft.effectiveFrom === 'permanent' ? '' : draft.effectiveFrom}
                  onChange={(e) =>
                    setDraft({ ...draft, effectiveFrom: e.target.value || 'permanent' })
                  }
                  placeholder="永久生效"
                  style={{
                    width: '100%',
                    padding: '10px 12px',
                    border: '1px solid var(--da-border)',
                    borderRadius: 8,
                    fontSize: 14,
                    background: 'var(--da-surface)',
                    color: 'var(--da-text)',
                    boxSizing: 'border-box',
                  }}
                />
              </div>
            </div>

            <div
              style={{
                padding: '16px 24px',
                borderTop: '1px solid var(--da-border)',
                display: 'flex',
                justifyContent: 'flex-end',
                gap: 12,
              }}
            >
              <button className="da-btn" onClick={() => setCreateOpen(false)}>
                取消
              </button>
              <button className="da-btn da-btn-primary" onClick={handleCreate} disabled={busy}>
                {busy ? '创建中…' : '创建'}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
