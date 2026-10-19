import React from 'react';
import { KnowledgeBreadcrumb } from '../../components/KnowledgeWorkspace';
import Icon from '../../components/Icon';

/** The entry remains visible; no scheduled-task API is called before the feature opens. */
export default function ScheduledTasksPage() {
  return <div style={{ padding: '24px 32px', flex: 1, overflow: 'auto' }}>
    <KnowledgeBreadcrumb items={[{ label: '返回对话', to: '/chat' }, { label: '例行任务' }]} />
    <header style={{ margin: '32px 0' }}><h1 className="da-page-title">例行任务</h1></header>
    <section role="status" style={{ display: 'flex', flexDirection: 'column', alignItems: 'center',
      justifyContent: 'center', minHeight: 320, padding: 24, textAlign: 'center',
      border: '1px solid var(--da-border)', borderRadius: 16, background: 'var(--da-surface)' }}>
      <Icon name="clock" size="lg" />
      <h2 style={{ fontSize: 18, margin: '20px 0 8px' }}>后续版本开放</h2>
      <p style={{ fontSize: 14, lineHeight: 1.7, color: 'var(--da-text-muted)', margin: 0 }}>
        例行任务正在完善中，当前版本暂不支持创建和执行任务。
      </p>
    </section>
  </div>;
}
