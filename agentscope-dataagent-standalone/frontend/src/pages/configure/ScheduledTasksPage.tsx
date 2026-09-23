import React, { useCallback, useEffect, useState } from 'react';
import BackToChatHeader from '../../components/BackToChatHeader';
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

const panelStyle: React.CSSProperties = {
  flex: 1,
  minHeight: 0,
  overflow: 'auto',
  padding: 24,
};

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
  const [menuOpenId, setMenuOpenId] = useState<string | null>(null);

  const refresh = useCallback(async () => {
    setLoading(true);
    try {
      setTasks(await listScheduledTasks());
      setError(null);
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    refresh();
  }, [refresh]);

  useEffect(() => {
    function handleClick() {
      setMenuOpenId(null);
    }
    if (menuOpenId) {
      document.addEventListener('click', handleClick);
      return () => document.removeEventListener('click', handleClick);
    }
  }, [menuOpenId]);

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
    <div style={{ display: 'flex', flexDirection: 'column', height: '100%', minHeight: 0 }}>
      <BackToChatHeader title="例行任务" subtitle="设置定时执行的 Agent 任务，自动分析并推送结果" />

      <div
        style={{
          padding: '16px 24px',
          display: 'flex',
          justifyContent: 'flex-end',
          alignItems: 'center',
          gap: 12,
        }}
      >
        <div style={{ position: 'relative', width: 280 }}>
          <input
            type="text"
            placeholder="搜索任务名称"
            style={{
              width: '100%',
              height: 40,
              padding: '0 12px 0 36px',
              border: '1px solid var(--da-border)',
              borderRadius: 20,
              fontSize: 14,
              background: 'var(--da-surface)',
              color: 'var(--da-text)',
              boxSizing: 'border-box',
              outline: 'none',
            }}
          />
          <span
            style={{
              position: 'absolute',
              left: 12,
              top: '50%',
              transform: 'translateY(-50%)',
              color: 'var(--da-text-muted)',
              pointerEvents: 'none',
              display: 'flex',
              alignItems: 'center',
            }}
          >
            <Icon name="search" size="sm" />
          </span>
        </div>
        <button
          className="da-btn da-btn-primary"
          onClick={() => setCreateOpen(true)}
          disabled={busy}
          style={{ height: 40, borderRadius: 20, padding: '0 20px', fontSize: 14 }}
        >
          + 创建定时任务
        </button>
      </div>

      <div style={panelStyle}>
        {loading && (
          <div style={{ color: 'var(--da-text-muted)', padding: 24 }}>加载中…</div>
        )}

        {!loading && tasks.length === 0 && (
          <div
            style={{
              display: 'flex',
              flexDirection: 'column',
              alignItems: 'center',
              justifyContent: 'center',
              padding: '80px 24px',
              color: 'var(--da-text-muted)',
            }}
          >
            <svg width="120" height="120" viewBox="0 0 120 120" fill="none" aria-hidden="true">
              <rect x="20" y="30" width="80" height="60" rx="8" fill="var(--da-surface-sunken)" stroke="var(--da-border)" strokeWidth="2" />
              <rect x="30" y="42" width="60" height="8" rx="4" fill="var(--da-border)" opacity="0.5" />
              <rect x="30" y="56" width="45" height="8" rx="4" fill="var(--da-border)" opacity="0.3" />
              <rect x="30" y="70" width="50" height="8" rx="4" fill="var(--da-border)" opacity="0.3" />
              <circle cx="90" cy="30" r="12" fill="var(--da-primary)" opacity="0.2" />
              <path d="M90 24v6l4 4" stroke="var(--da-primary)" strokeWidth="2" strokeLinecap="round" />
            </svg>
            <div style={{ fontSize: 14, marginTop: 16 }}>暂无例行任务</div>
            <button
              onClick={() => setCreateOpen(true)}
              style={{
                marginTop: 12,
                background: 'transparent',
                border: 'none',
                color: 'var(--da-primary)',
                cursor: 'pointer',
                fontSize: 14,
              }}
            >
              创建定时任务
            </button>
          </div>
        )}

        {!loading && tasks.length > 0 && (
          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(360px, 1fr))', gap: 16 }}>
            {tasks.map((task) => (
              <div
                key={task.id}
                className="da-card"
                style={{
                  padding: 20,
                  display: 'flex',
                  flexDirection: 'column',
                  gap: 12,
                  position: 'relative',
                }}
              >
                <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start' }}>
                  <div style={{ flex: 1, minWidth: 0 }}>
                    <div style={{ fontSize: 16, fontWeight: 600, color: 'var(--da-text)', marginBottom: 8 }}>
                      {task.title}
                    </div>
                    <div style={{ display: 'flex', gap: 8, alignItems: 'center', flexWrap: 'wrap' }}>
                      <span
                        style={{
                          padding: '2px 8px',
                          background: 'var(--da-surface-sunken)',
                          border: '1px solid var(--da-border)',
                          borderRadius: 4,
                          fontSize: 12,
                          color: 'var(--da-text-muted)',
                        }}
                      >
                        {FREQ_LABEL[task.scheduleFrequency] || task.scheduleFrequency} · {task.scheduleTime}
                      </span>
                      <span style={{ fontSize: 12, color: 'var(--da-text-muted)' }}>
                        {task.prompt.length > 30 ? task.prompt.substring(0, 30) + '...' : task.prompt}
                      </span>
                    </div>
                  </div>
                  <div style={{ display: 'flex', gap: 4, position: 'relative' }}>
                    <button
                      onClick={() => handleToggleStatus(task)}
                      className="da-btn da-btn-ghost da-btn-sm"
                      title={task.status === 'active' ? '暂停任务' : '恢复任务'}
                      style={{ display: 'flex', alignItems: 'center', gap: 4 }}
                    >
                      {task.status === 'active' ? (
                        <>
                          <svg width="14" height="14" viewBox="0 0 24 24" fill="currentColor">
                            <rect x="6" y="4" width="4" height="16" rx="1" />
                            <rect x="14" y="4" width="4" height="16" rx="1" />
                          </svg>
                          暂停任务
                        </>
                      ) : (
                        <>
                          <svg width="14" height="14" viewBox="0 0 24 24" fill="currentColor">
                            <path d="M8 5v14l11-7z" />
                          </svg>
                          恢复任务
                        </>
                      )}
                    </button>
                    <button
                      onClick={(e) => {
                        e.stopPropagation();
                        setMenuOpenId(menuOpenId === task.id ? null : task.id);
                      }}
                      className="da-btn da-btn-ghost da-btn-sm"
                      title="更多操作"
                    >
                      <Icon name="moreHorizontal" size="sm" />
                    </button>
                    {menuOpenId === task.id && (
                      <div
                        style={{
                          position: 'absolute',
                          right: 0,
                          top: '100%',
                          marginTop: 4,
                          background: 'var(--da-surface)',
                          border: '1px solid var(--da-border)',
                          borderRadius: 8,
                          boxShadow: 'var(--da-shadow-pop)',
                          zIndex: 10,
                          minWidth: 120,
                        }}
                        onClick={(e) => e.stopPropagation()}
                      >
                        <button
                          onClick={() => {
                            handleDelete(task);
                            setMenuOpenId(null);
                          }}
                          className="da-navitem"
                          style={{ color: 'var(--da-danger)', width: '100%', textAlign: 'left' }}
                        >
                          <Icon name="trash" size="sm" />
                          删除任务
                        </button>
                      </div>
                    )}
                  </div>
                </div>

                <div
                  style={{
                    fontSize: 12,
                    color: 'var(--da-text-muted)',
                    display: 'flex',
                    gap: 12,
                    flexWrap: 'wrap',
                  }}
                >
                  <span>{task.createdBy || '当前用户'}</span>
                  <span>·</span>
                  <span>创建于 {formatDate(task.createdAt)}</span>
                  <span>·</span>
                  <span>{task.runCount} 次运行</span>
                </div>
              </div>
            ))}
          </div>
        )}

        {error && (
          <div style={{ color: 'var(--da-danger)', padding: '12px 0', fontSize: 14 }}>{error}</div>
        )}
      </div>

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
                  <option value={ACTIVE_AGENT_ID}>红海DataAgent（默认）</option>
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
