import React from 'react';
import { Link } from 'react-router-dom';
import { KnowledgeBreadcrumb } from '../../components/KnowledgeWorkspace';
import calendarIllustration from '../../assets/scheduled-tasks-calendar.png';
import '../../secondary-pages.css';

/** The entry remains visible; no scheduled-task API is called before the feature opens. */
export default function ScheduledTasksPage() {
  return <div className="kw-page kw-secondary-page scheduled-page">
    <KnowledgeBreadcrumb items={[{ label: '对话', to: '/chat' }, { label: '例行任务' }]} />
    <div className="kw-scroll"><div className="kw-content">
      <div className="kw-pagehead"><div className="kw-heading"><h1>例行任务</h1><p>管理周期性分析任务的内容和执行计划。</p></div></div>
      <section className="scheduled-unavailable" aria-labelledby="scheduled-unavailable-title">
        <img className="scheduled-illustration" src={calendarIllustration} alt="" width="1448" height="1086" draggable={false} />
        <h2 id="scheduled-unavailable-title">例行任务，正在准备中</h2>
        <p>当前版本暂不支持创建和执行例行任务。<br />你可以先在对话中发起数据分析。</p>
        <div className="scheduled-actions">
          <Link className="da-btn da-btn-primary" to="/chat">发起分析<span aria-hidden="true">→</span></Link>
          <Link className="scheduled-knowledge-link" to="/configure/datasets">查看知识库<span aria-hidden="true">→</span></Link>
        </div>
      </section>
    </div></div>
  </div>;
}
