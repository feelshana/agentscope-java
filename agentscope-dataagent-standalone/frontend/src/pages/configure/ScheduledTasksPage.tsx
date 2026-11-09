import React from 'react';
import { Link } from 'react-router-dom';
import { KnowledgeBreadcrumb } from '../../components/KnowledgeWorkspace';
import Icon from '../../components/Icon';
import calendarIllustration from '../../assets/scheduled-tasks-calendar.png';
import './ScheduledTasksPage.css';

/** The entry remains visible; no scheduled-task API is called before the feature opens. */
export default function ScheduledTasksPage() {
  return <div className="kw-page workspace-overview scheduled-page">
    <KnowledgeBreadcrumb items={[{ label: '对话', to: '/chat' }, { label: '例行任务' }]} />
    <div className="kw-scroll"><div className="kw-content">
      <div className="kw-pagehead"><div className="kw-heading"><h1>例行任务</h1><p>管理周期性分析任务的内容和执行计划。</p></div></div>
      <section className="overview-surface scheduled-unavailable" aria-labelledby="scheduled-unavailable-title">
        <img className="scheduled-illustration" src={calendarIllustration} alt="" width="1448" height="1086" draggable={false} />
        <h2 id="scheduled-unavailable-title">例行任务，正在准备中</h2>
        <p>当前版本暂不支持创建和执行例行任务。<br />你可以先在对话中发起数据分析。</p>
        <div className="scheduled-actions">
          <Link className="da-btn overview-action is-primary" to="/chat"><Icon name="chart" size="sm" />发起分析</Link>
          <Link className="scheduled-knowledge-link" to="/configure/datasets">查看知识库<Icon name="chevron" size="sm" /></Link>
        </div>
      </section>
    </div></div>
  </div>;
}
