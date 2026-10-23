import { useEffect, useState } from 'react';

export type AssociationStep = { name: string; status: 'waiting' | 'sampling' | 'done' | 'failed'; error?: string };
export type AssociationState = { tables: AssociationStep[]; phase: 'sampling' | 'publishing' | 'done' };

/**
 * Progress of associating external tables: each table is read and sampled in its own request,
 * then the base model is generated once, so the bar reflects real work rather than a timer.
 */
export default function AssociationProgress({ state }: { state: AssociationState }) {
  const [now, setNow] = useState(Date.now());
  const [startedAt] = useState(Date.now());
  useEffect(() => {
    if (state.phase === 'done') return;
    const timer = window.setInterval(() => setNow(Date.now()), 1000);
    return () => window.clearInterval(timer);
  }, [state.phase]);
  const total = state.tables.length + 1;
  const finished = state.tables.filter(t => t.status === 'done' || t.status === 'failed').length
    + (state.phase === 'done' ? 1 : 0);
  const percent = Math.round((finished / total) * 100);
  const current = state.tables.find(t => t.status === 'sampling');
  const failed = state.tables.filter(t => t.status === 'failed').length;
  const title = state.phase === 'publishing' ? '正在生成基础语义模型…'
    : state.phase === 'done' ? (failed ? `关联完成，${failed} 张表失败` : '关联完成，即将进入语义建模')
    : current ? `正在读取并采样 ${current.name}（${state.tables.indexOf(current) + 1}/${state.tables.length}）` : '准备中…';
  const seconds = Math.round((now - startedAt) / 1000);

  return <div className="da-card" style={{ padding: '14px 16px', display: 'flex', flexDirection: 'column', gap: 10 }} role="status" aria-live="polite">
    <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
      {state.phase !== 'done' && <span className="da-spinner da-spinner-sm" style={{ borderColor: 'var(--da-border)', borderTopColor: 'var(--da-primary)' }} />}
      <strong style={{ flex: 1 }}>{title}</strong>
      <span className="da-small">{percent}% · 已用 {seconds} 秒</span>
    </div>
    <div style={{ height: 8, borderRadius: 4, background: 'var(--da-surface-sunken, #eef2f7)', overflow: 'hidden' }}>
      <div style={{ width: `${percent}%`, height: '100%', borderRadius: 4, transition: 'width .4s ease',
        background: failed ? 'var(--da-warn)' : 'var(--da-primary)' }} />
    </div>
    <div style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
      {state.tables.map(t => <div key={t.name} style={{ display: 'flex', alignItems: 'center', gap: 8, fontSize: 13 }}>
        <span style={{ width: 16, textAlign: 'center', color: t.status === 'done' ? 'var(--da-success)' : t.status === 'failed' ? 'var(--da-danger)' : 'var(--da-text-muted)' }}>
          {t.status === 'done' ? '✓' : t.status === 'failed' ? '✕' : t.status === 'sampling' ? '…' : '○'}
        </span>
        <code style={{ flex: '0 0 auto' }}>{t.name}</code>
        <span className="da-small" style={{ flex: 1, minWidth: 0, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap', color: t.status === 'failed' ? 'var(--da-danger)' : undefined }}>
          {t.status === 'done' ? '字段与样例已读取' : t.status === 'sampling' ? '读取字段、采样数据中' : t.status === 'failed' ? t.error : '等待'}
        </span>
      </div>)}
      <div style={{ display: 'flex', alignItems: 'center', gap: 8, fontSize: 13 }}>
        <span style={{ width: 16, textAlign: 'center', color: state.phase === 'done' ? 'var(--da-success)' : 'var(--da-text-muted)' }}>
          {state.phase === 'done' ? '✓' : state.phase === 'publishing' ? '…' : '○'}
        </span>
        <span>生成基础语义模型</span>
      </div>
    </div>
  </div>;
}
