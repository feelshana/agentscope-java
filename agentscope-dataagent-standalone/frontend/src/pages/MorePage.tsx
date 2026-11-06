import React from 'react';
import { useNavigate } from 'react-router-dom';
import { KnowledgeBreadcrumb } from '../components/KnowledgeWorkspace';
import Icon from '../components/Icon';
import { isAdmin } from '../api/auth';
import { setHelpOpen } from '../api/onboarding';
import '../secondary-pages.css';
import './MorePage.css';

export default function MorePage() {
  const navigate = useNavigate();
  const admin = isAdmin();
  return <div className="kw-page kw-secondary-page more-page">
    <KnowledgeBreadcrumb items={[{ label: '对话', to: '/chat' }, { label: '更多' }]} />
    <div className="kw-scroll"><div className="kw-content more-content">
      <div className="kw-pagehead"><div className="kw-heading"><h1>更多</h1><p>使用帮助与管理入口。</p></div></div>
      <section className="more-help" aria-labelledby="more-help-title">
        <div className="more-help-icon"><Icon name="book" /></div>
        <div className="more-help-copy"><h2 id="more-help-title">帮助与引导</h2><p>查看使用说明，继续完成新手任务。</p></div>
        <button type="button" className="da-btn more-help-action" onClick={() => setHelpOpen(true)}>打开帮助<span aria-hidden="true">↗</span></button>
      </section>
      {admin && <section className="more-admin" aria-labelledby="more-admin-title">
        <div className="more-section-heading"><h2 id="more-admin-title"><Icon name="shield" size="sm" />管理</h2><span>仅全局管理员可见</span></div>
        <div className="more-entries">
          <button type="button" className="more-entry" onClick={() => navigate('/admin/bi-sync')}>
            <Icon name="database" /><span className="more-entry-copy"><span className="more-entry-title">BI数据同步</span><span className="more-entry-description">查看数据截止日期、最近执行结果与异常提示。</span></span><span className="more-entry-go">查看<Icon name="chevron" size="sm" /></span>
          </button>
          <button type="button" className="more-entry" onClick={() => navigate('/admin/models')}>
            <Icon name="sliders" /><span className="more-entry-copy"><span className="more-entry-title">大模型服务</span><span className="more-entry-description">管理服务连接，手动切换当前使用的模型。</span></span><span className="more-entry-go">管理<Icon name="chevron" size="sm" /></span>
          </button>
          <button type="button" className="more-entry" onClick={() => navigate('/admin/public-knowledge-bases')}>
            <Icon name="book" /><span className="more-entry-copy"><span className="more-entry-title">公共知识库</span><span className="more-entry-description">设置全体已登录用户可使用的知识库。</span></span><span className="more-entry-go">管理<Icon name="chevron" size="sm" /></span>
          </button>
          <button type="button" className="more-entry" onClick={() => navigate('/admin/conversation-history')}>
            <Icon name="list" /><span className="more-entry-copy"><span className="more-entry-title">用户对话历史</span><span className="more-entry-description">按用户查看问题和回复正文。</span></span><span className="more-entry-go">查看<Icon name="chevron" size="sm" /></span>
          </button>
          <button type="button" className="more-entry" onClick={() => navigate('/admin/administrators')}>
            <Icon name="shield" /><span className="more-entry-copy"><span className="more-entry-title">全局管理员</span><span className="more-entry-description">查看、添加和移除全局管理员权限。</span></span><span className="more-entry-go">管理<Icon name="chevron" size="sm" /></span>
          </button>
        </div>
      </section>}
      <p className="more-note"><Icon name="info" size="sm" /><span>个人对话和分析文件仍在对应会话中查看。</span></p>
    </div></div>
  </div>;
}
