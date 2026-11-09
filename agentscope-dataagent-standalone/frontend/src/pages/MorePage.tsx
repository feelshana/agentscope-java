import React from 'react';
import { Link } from 'react-router-dom';
import { KnowledgeBreadcrumb } from '../components/KnowledgeWorkspace';
import Icon from '../components/Icon';
import type { IconName } from '../components/Icon';
import { isAdmin } from '../api/auth';
import { setHelpOpen } from '../api/onboarding';
import './MorePage.css';

const ADMIN_LINKS: { to: string; label: string; description: string; icon: IconName }[] = [
  { to: '/admin/bi-sync', label: 'BI数据同步', description: '查看数据截止日期、最近执行结果与异常提示。', icon: 'database' },
  { to: '/admin/models', label: '大模型服务', description: '管理服务连接，手动切换当前使用的模型。', icon: 'sliders' },
  { to: '/admin/public-knowledge-bases', label: '公共知识库', description: '设置全体已登录用户可使用的知识库。', icon: 'book' },
  { to: '/admin/conversation-history', label: '用户对话历史', description: '按用户查看问题和回复正文。', icon: 'list' },
  { to: '/admin/administrators', label: '全局管理员', description: '查看、添加和移除全局管理员权限。', icon: 'shield' },
];

export default function MorePage() {
  const admin = isAdmin();
  return <div className="kw-page workspace-overview more-page">
    <KnowledgeBreadcrumb items={[{ label: '对话', to: '/chat' }, { label: '更多' }]} />
    <div className="kw-scroll"><div className="kw-content">
      <div className="kw-pagehead"><div className="kw-heading"><h1>更多</h1><p>使用帮助与管理入口。</p></div></div>
      <section className="overview-surface more-help" aria-labelledby="more-help-title">
        <span className="overview-icon more-help-icon"><Icon name="book" /></span>
        <div className="more-help-copy"><h2 id="more-help-title">帮助与引导</h2><p>查看使用说明，继续完成新手任务。</p></div>
        <button type="button" className="da-btn overview-action is-soft more-help-action" onClick={() => setHelpOpen(true)}>
          打开帮助<Icon name="chevron" size="sm" />
        </button>
      </section>
      {admin && <section className="more-admin" aria-labelledby="more-admin-title">
        <div className="more-section-heading"><h2 id="more-admin-title">管理</h2><p>仅全局管理员可见</p></div>
        <div className="more-entries">
          {ADMIN_LINKS.map(entry => (
            <Link key={entry.to} className="overview-surface more-entry" to={entry.to}>
              <span className="overview-icon more-entry-icon"><Icon name={entry.icon} /></span>
              <span className="more-entry-copy">
                <strong>{entry.label}</strong>
                <small>{entry.description}</small>
              </span>
              <Icon name="chevron" size="sm" className="more-entry-arrow" />
            </Link>
          ))}
        </div>
      </section>}
      <p className="more-note"><Icon name="info" size="sm" /><span>个人对话和分析文件仍在对应会话中查看。</span></p>
    </div></div>
  </div>;
}
