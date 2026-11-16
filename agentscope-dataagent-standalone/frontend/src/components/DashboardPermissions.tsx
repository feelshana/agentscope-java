import React, { useState } from 'react';
import { Link } from 'react-router-dom';
import { Dashboard } from '../api/dashboards';
import DataSourceDialog from './DataSourceDialog';
import KnowledgeBaseMembersModal from './KnowledgeBaseMembersModal';

export default function DashboardPermissions({ dashboard, name, onClose, onChanged }: {
  dashboard: Dashboard; name: string; onClose: () => void; onChanged?: () => void;
}) {
  const [members, setMembers] = useState(false);
  if (members) return <KnowledgeBaseMembersModal groupId={dashboard.groupId} name={name} onClose={onClose} onChanged={onChanged} />;
  return <DataSourceDialog className="dashboard-dialog" title="访问权限" description="这张看板沿用所属知识库的权限。" onClose={onClose}>
    <div className="dashboard-dialog-body"><div className="dashboard-publish-summary"><strong>{dashboard.title}</strong><span>所属知识库：{name || '当前知识库'}</span><span>你当前可{dashboard.canPublish ? '管理看板、编辑、发布和删除' : dashboard.owned ? '编辑及删除自己的看板，发布需知识库管理者操作' : '查看已发布版'}</span></div>
      <ul className="dashboard-permission-rules"><li>看板创建者、知识库管理者：可编辑草稿及删除看板。</li><li>知识库管理者：可发布看板、管理知识库成员。</li><li>知识库使用成员：可查看已发布看板，不能查看他人草稿。</li><li>全局管理员：依系统现有规则可管理所有知识库及相关看板。</li></ul>
      <p className="dashboard-permission-note">知识库使用权限同时允许问数和查看已发布看板，更改授权会影响该库下的所有看板。若知识库开启全员可查询，则所有已登录用户可查看其中的已发布看板；看板没有匿名公开链接。</p>
    </div><footer className="dashboard-dialog-foot"><Link className="dashboard-secondary clear" to={`/configure/datasets/${dashboard.groupId}`}>打开知识库</Link>{dashboard.canPublish && <button className="dashboard-primary" onClick={() => setMembers(true)}>管理知识库成员</button>}<button className="dashboard-secondary" onClick={onClose}>关闭</button></footer>
  </DataSourceDialog>;
}
