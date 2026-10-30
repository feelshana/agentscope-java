import React from 'react';
import { useNavigate } from 'react-router-dom';
import { KnowledgeBreadcrumb } from '../components/KnowledgeWorkspace';
import Icon from '../components/Icon';
import { isAdmin } from '../api/auth';
import { setHelpOpen } from '../api/onboarding';
import '../secondary-pages.css';

export default function MorePage() {
  const navigate = useNavigate();
  return <div className="kw-page kw-secondary-page">
    <KnowledgeBreadcrumb items={[{ label: '对话', to: '/chat' }, { label: '更多' }]} />
    <div className="kw-scroll"><div className="kw-content">
      <div className="kw-pagehead"><div className="kw-heading"><h1>更多</h1><p>系统配置与管理。</p></div></div>
      <div className="kw-tablebox"><div className="secondary-docrow"><Icon name="book" /><div><h2>帮助与引导</h2><p>使用说明和新手任务。</p></div><button type="button" className="da-btn" onClick={() => setHelpOpen(true)}>查看</button></div></div>
      {isAdmin() && <div className="kw-tablebox ms-entry"><div className="secondary-docrow"><Icon name="settings" /><div><h2>大模型服务</h2><p>全局管理员 · 配置服务与手动切换</p></div><button className="da-btn da-btn-ghost da-btn-sm" onClick={() => navigate('/admin/models')}><Icon name="chevron" size="sm" />管理</button></div></div>}
      {isAdmin() && <div className="kw-tablebox"><div className="secondary-docrow"><Icon name="book" /><div><h2>公共知识库</h2><p>全局管理员 · 配置全员可使用的知识库</p></div><button className="da-btn" onClick={() => navigate('/admin/public-knowledge-bases')}>管理</button></div></div>}
      {!isAdmin() && <p>暂无面向普通用户的系统配置。分析结果仍可在对话中预览和下载。</p>}
    </div></div>
  </div>;
}
