import React from 'react';
import { KnowledgeBreadcrumb } from '../../components/KnowledgeWorkspace';
import Icon from '../../components/Icon';
import '../../secondary-pages.css';

/** The entry remains visible; no scheduled-task API is called before the feature opens. */
export default function ScheduledTasksPage() {
  return <div className="kw-page kw-secondary-page">
    <KnowledgeBreadcrumb items={[{ label: '对话', to: '/chat' }, { label: '例行任务' }]} />
    <div className="kw-scroll"><div className="kw-content">
      <div className="kw-pagehead"><div className="kw-heading"><h1>例行任务</h1><p>管理周期性分析任务的内容和执行计划。</p></div><span className="kw-source">后续版本开放</span></div>
      <div className="kw-tablebox"><div className="kw-table-scroll"><table className="kw-table secondary-task-table" aria-label="例行任务">
        <thead><tr>{['任务名称', '知识库', '执行计划', '配置状态', '操作'].map(label => <th scope="col" key={label}>{label}</th>)}</tr></thead>
      </table></div><section className="secondary-task-empty" role="status"><div className="secondary-empty-icon"><Icon name="clock" /></div><h2>后续版本开放</h2><p>例行任务正在完善中，当前版本暂不支持创建和执行任务。</p></section></div>
    </div></div>
  </div>;
}
