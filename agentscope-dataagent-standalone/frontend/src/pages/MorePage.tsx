import React from 'react';
import { useNavigate } from 'react-router-dom';
import { KnowledgeBreadcrumb } from '../components/KnowledgeWorkspace';
import Icon from '../components/Icon';
import { isAdmin } from '../api/auth';
import '../secondary-pages.css';

export default function MorePage() {
  const navigate = useNavigate();
  return <div className="kw-page kw-secondary-page">
    <KnowledgeBreadcrumb items={[{ label: '对话', to: '/chat' }, { label: '更多' }]} />
    <div className="kw-scroll"><div className="kw-content">
      <div className="kw-pagehead"><div className="kw-heading"><h1>更多</h1><p>工作区保存本人生成的分析结果。</p></div></div>
      <div className="kw-tablebox"><div className="secondary-docrow"><Icon name="folder" /><div><h2>工作区</h2><p>本人分析文件与下载结果</p></div><button className="da-btn da-btn-ghost da-btn-sm" onClick={() => navigate('/workspace')}><Icon name="chevron" size="sm" />打开</button></div></div>
      {isAdmin() && <div className="kw-tablebox ms-entry"><div className="secondary-docrow"><Icon name="settings" /><div><h2>大模型服务</h2><p>全局管理员 · 配置服务与手动切换</p></div><button className="da-btn da-btn-ghost da-btn-sm" onClick={() => navigate('/admin/models')}><Icon name="chevron" size="sm" />管理</button></div></div>}
    </div></div>
  </div>;
}
