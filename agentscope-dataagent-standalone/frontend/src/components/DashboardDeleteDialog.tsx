import React, { useState } from 'react';
import { Dashboard, deleteDashboard } from '../api/dashboards';
import DataSourceDialog from './DataSourceDialog';
import { dashboardErrorText } from './dashboardState';
import { forgetDashboardThumbnails } from './dashboardThumbnails';

export default function DashboardDeleteDialog({ dashboard, onClose, onDeleted, onChanged }: {
  dashboard: Dashboard; onClose: () => void; onDeleted: () => void; onChanged?: () => void;
}) {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const remove = async () => {
    if (busy) return;
    setBusy(true); setError('');
    try { await deleteDashboard(dashboard.id); forgetDashboardThumbnails(dashboard.id); onDeleted(); }
    catch (e) { setError(dashboardErrorText(e)); onChanged?.(); }
    finally { setBusy(false); }
  };
  return <DataSourceDialog className="dashboard-dialog" title={dashboard.deletionPending ? '重试清理看板？' : '删除这张看板？'} description="删除后无法恢复，已发布的看板也将停止访问。" busy={busy} onClose={onClose}>
    <div className="dashboard-dialog-body">{error && <p className="dashboard-notice error" role="alert">{error}</p>}<div className="dashboard-publish-summary"><strong>{dashboard.title}</strong><span>清理草稿、全部发布版本、独立工程和校验文件。</span><span>清理关联创作会话、历史、上下文、附件和本地封面缓存。</span></div><p className="dashboard-permission-note">知识库原始数据、语义模型和其他看板会保留。正在生成的看板需等待本轮结束后再删除。清理失败可以重试。</p></div>
    <footer className="dashboard-dialog-foot"><button className="dashboard-secondary clear" disabled={busy} onClick={onClose}>取消</button><button className="dashboard-primary danger" disabled={busy} onClick={() => void remove()}>{busy ? '正在清理…' : error || dashboard.deletionPending ? '重试清理' : '确认删除'}</button></footer>
  </DataSourceDialog>;
}
