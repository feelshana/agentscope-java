import { useId, useState } from 'react';
import { Dashboard, getDashboard, updateDashboardMetadata } from '../api/dashboards';
import DataSourceDialog from './DataSourceDialog';
import { dashboardErrorText } from './dashboardState';

export default function DashboardMetadataDialog({ dashboard, onClose, onSaved }: {
  dashboard: Dashboard; onClose: () => void; onSaved: (dashboard: Dashboard) => void;
}) {
  const id = useId();
  const [base, setBase] = useState(dashboard);
  const [title, setTitle] = useState(dashboard.title);
  const [description, setDescription] = useState(dashboard.description || '');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const changed = title.trim() !== base.title || description.trim() !== (base.description || '');
  async function save(event: React.FormEvent) {
    event.preventDefault(); if (busy || !title.trim() || !changed) return;
    setBusy(true); setError('');
    try { onSaved(await updateDashboardMetadata(base, title, description)); }
    catch (e) { setError(dashboardErrorText(e)); }
    finally { setBusy(false); }
  }
  async function refresh() {
    if (busy) return;
    setBusy(true); setError('');
    try { const next = await getDashboard(base.id, true); setBase(next); setTitle(next.title); setDescription(next.description || ''); }
    catch (e) { setError(dashboardErrorText(e)); }
    finally { setBusy(false); }
  }
  return <DataSourceDialog className="dashboard-dialog" title="编辑基本信息" description="名称和描述保存后立即生效。看板页面内的标题需通过对话修改并发布。" busy={busy} onClose={onClose}>
    <form onSubmit={event => void save(event)}><div className="dashboard-dialog-body">
      {error && <div className="dashboard-notice error" role="alert">{error}<button type="button" className="dashboard-secondary clear" disabled={busy} onClick={() => void refresh()}>重新读取信息</button><small>重新读取会替换表单中尚未保存的内容。</small></div>}
      <label className="dashboard-field" htmlFor={`${id}-title`}>看板名称<input id={`${id}-title`} required maxLength={80} value={title} disabled={busy} onChange={e => setTitle(e.target.value)} /></label>
      <label className="dashboard-field" htmlFor={`${id}-description`}>描述 <span className="dashboard-optional">选填</span><textarea id={`${id}-description`} aria-label="描述（选填）" aria-describedby={`${id}-description-count`} rows={4} maxLength={500} value={description} disabled={busy} placeholder="例如：供区域负责人每周查看销售表现、渠道结构与重点门店。" onChange={e => setDescription(e.target.value)} /><small id={`${id}-description-count`}>{description.length}/500</small></label>
    </div><footer className="dashboard-dialog-foot"><button type="button" className="dashboard-secondary clear" disabled={busy} onClick={onClose}>取消</button><button className="dashboard-primary" type="submit" disabled={busy || !title.trim() || !changed}>{busy ? '正在保存…' : '保存信息'}</button></footer></form>
  </DataSourceDialog>;
}
