import React, { useEffect, useMemo, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { createDashboard, Dashboard, listDashboards } from '../api/dashboards';
import { DatasetGroup, listGroups } from '../api/datasets';
import DataSourceDialog from './DataSourceDialog';
import { KnowledgeBreadcrumb, WorkspaceMenu } from './KnowledgeWorkspace';
import DashboardPermissions from './DashboardPermissions';
import DashboardDeleteDialog from './DashboardDeleteDialog';
import { dashboardErrorText, dashboardStatus } from './dashboardState';
import { dashboardThumbnail } from './dashboardThumbnails';
import Icon from './Icon';

const filters = [['all', '全部'], ['mine', '我创建的'], ['shared', '共享给我']] as const;
function updated(value: string) {
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? '更新时间暂不可用' : `更新于 ${date.toLocaleString('zh-CN', { month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit' })}`;
}

function DashboardCard({ item, group, onPermissions, onDelete }: { item: Dashboard; group?: DatasetGroup; onPermissions: () => void; onDelete: () => void }) {
  const status = dashboardStatus(item);
  const publishedImage = dashboardThumbnail(item.id, item.publishedRevision);
  const image = !item.deletionPending && (publishedImage || (item.canEdit ? dashboardThumbnail(item.id, item.revision) : undefined));
  const path = `/dashboards/${item.id}`;
  const viewing = item.publishedRevision ? `${path}?view=published` : path;
  return <article className="dashboard-library-item">
    <Link className="dashboard-cover" to={viewing} aria-disabled={item.deletionPending || undefined} onClick={event => { if (item.deletionPending) event.preventDefault(); }} aria-label={`${item.publishedRevision ? '查看' : '预览草稿'}：${item.title}`}>
      {image ? <><img src={image} alt={`${item.title}的图表预览`} /><span className="dashboard-cover-caption">{publishedImage ? '最近查看的图表' : '最近查看的草稿图表'}</span></>
        : <div className="dashboard-cover-empty"><span className="dashboard-feature-icon"><Icon name="chart" /></span><strong>{item.publishedRevision ? '业务全景，一目了然' : '从一个想法开始'}</strong><span>{item.publishedRevision ? '打开看板，查看完整图表' : '继续对话，完善第一版看板'}</span></div>}
    </Link>
    <div className="dashboard-card-body"><div className="dashboard-card-heading"><h3 title={item.title}>{item.title}</h3><WorkspaceMenu label={`${item.title}的操作`} items={[{ label: '访问权限', icon: 'shield', onClick: onPermissions }, ...(item.canEdit ? [{ label: item.deletionPending ? '重试清理' : '删除看板', icon: 'trash' as const, danger: true, onClick: onDelete }] : [])]} /></div><span className={`dashboard-badge ${status.kind}`}>{status.label}</span>
      <p className="dashboard-card-source" title={group?.name}><Icon name="book" size="sm" />{group?.name || '已授权知识库'}</p>
      <p className="dashboard-card-updated">{item.owned ? '我创建的' : `共享给我${item.canEdit ? ' · 可编辑' : ''}`} · {updated(item.updatedAt)}</p>
    </div>
    <footer className="dashboard-card-bottom">{item.deletionPending ? <button onClick={onDelete}>重试清理</button> : <><Link to={viewing}><Icon name="chart" size="sm" />{item.publishedRevision ? '查看看板' : '预览草稿'}</Link>{item.canEdit && <Link to={path}><Icon name="edit" size="sm" />继续编辑</Link>}</>}</footer>
  </article>;
}

export default function DashboardLibrary() {
  const navigate = useNavigate();
  const [items, setItems] = useState<Dashboard[]>([]);
  const [groups, setGroups] = useState<DatasetGroup[]>([]);
  const [group, setGroup] = useState('');
  const [title, setTitle] = useState('');
  const [permissions, setPermissions] = useState<Dashboard | null>(null);
  const [removing, setRemoving] = useState<Dashboard | null>(null);
  const [filter, setFilter] = useState<(typeof filters)[number][0]>('all');
  const [query, setQuery] = useState('');
  const [loading, setLoading] = useState(true);
  const [creating, setCreating] = useState(false);
  const [formOpen, setFormOpen] = useState(false);
  const [error, setError] = useState('');
  const [formError, setFormError] = useState('');
  useEffect(() => {
    let disposed = false;
    Promise.all([listDashboards(), listGroups()]).then(([dashboards, knowledge]) => {
      if (disposed) return;
      setItems(dashboards); setGroups(knowledge);
      setGroup(knowledge.find(g => g.canQuery && (g.publishedVersion ?? 0) > 0 && !g.deletionPending)?.id || '');
    }).catch(e => { if (!disposed) setError(dashboardErrorText(e)); }).finally(() => { if (!disposed) setLoading(false); });
    return () => { disposed = true; };
  }, []);
  const sources = groups.filter(g => g.canQuery && (g.publishedVersion ?? 0) > 0 && !g.deletionPending);
  const groupMap = useMemo(() => new Map(groups.map(g => [g.id, g])), [groups]);
  const visible = items.filter(item => (filter === 'all' || (filter === 'mine' ? item.owned : !item.owned))
    && `${item.title} ${groupMap.get(item.groupId)?.name || ''}`.toLocaleLowerCase().includes(query.trim().toLocaleLowerCase()));
  const refresh = async () => {
    try { const [dashboards, knowledge] = await Promise.all([listDashboards(), listGroups()]); setItems(dashboards); setGroups(knowledge); }
    catch (e) { setError(dashboardErrorText(e)); }
  };
  const open = () => { setTitle(''); setFormError(''); setFormOpen(true); };
  const create = async (event: React.FormEvent) => {
    event.preventDefault(); if (!group || !title.trim() || creating) return;
    setCreating(true); setFormError('');
    try {
      const dashboard = await createDashboard(group, title);
      navigate(`/dashboards/${dashboard.id}`);
    } catch (e) { setFormError(dashboardErrorText(e)); setCreating(false); }
  };
  return <div className="dashboard-library kw-page workspace-overview">
    <KnowledgeBreadcrumb items={[{ label: '返回对话', to: '/chat' }, { label: '仪表盘' }]} />
    <div className="kw-scroll"><div className="kw-content">
    <header className="kw-pagehead"><div className="kw-heading"><h1>仪表盘</h1><p>查看业务看板，或新建看板开始对话创作。</p></div><div className="kw-actions"><button className="dashboard-primary" onClick={open} disabled={loading}><Icon name="plus" size="sm" />新建看板</button></div></header>
    {error && <div className="dashboard-notice error" role="alert">{error}</div>}
    <div className="dashboard-library-toolbar"><div className="dashboard-segment" aria-label="看板筛选">{filters.map(([value, label]) => <button key={value} aria-pressed={filter === value} onClick={() => setFilter(value)}>{label}</button>)}</div><label className="dashboard-search"><Icon name="search" size="sm" /><input type="search" aria-label="搜索看板名称或知识库" placeholder="搜索看板名称或知识库" value={query} onChange={e => setQuery(e.target.value)} /></label></div>
    {loading ? <div className="dashboard-loading" role="status">正在读取看板…</div> : visible.length ? <div className="dashboard-grid">{visible.map(item => <DashboardCard key={item.id} item={item} group={groupMap.get(item.groupId)} onPermissions={() => setPermissions(item)} onDelete={() => setRemoving(item)} />)}</div>
      : <div className="dashboard-library-empty"><span className="dashboard-feature-icon"><Icon name="chart" /></span><h3>{items.length ? '没有找到匹配的看板' : '你的第一张看板，从一个问题开始'}</h3><p>{items.length ? '试试其他关键词，或切换看板范围。' : '选好知识库，告诉助手你关心的指标、趋势和业务表现。'}</p></div>}
    </div></div>
    {permissions && <DashboardPermissions dashboard={permissions} name={groupMap.get(permissions.groupId)?.name || '当前知识库'} onClose={() => setPermissions(null)} onChanged={() => void refresh()} />}
    {removing && <DashboardDeleteDialog dashboard={removing} onClose={() => setRemoving(null)} onChanged={() => void refresh()} onDeleted={() => { setItems(previous => previous.filter(item => item.id !== removing.id)); setRemoving(null); }} />}
    {formOpen && <DataSourceDialog className="dashboard-dialog" title="新建看板" description="选择知识库并命名，进入创作页后再聊聊你的需求。" busy={creating} onClose={() => setFormOpen(false)}>
      <form onSubmit={create}><div className="dashboard-dialog-body">{formError && <p className="dashboard-notice error" role="alert">{formError}</p>}
        <label className="dashboard-field" htmlFor="dashboard-group">知识库<select id="dashboard-group" required value={group} disabled={creating} onChange={e => setGroup(e.target.value)}><option value="" disabled>请选择已发布知识库</option>{sources.map(g => <option key={g.id} value={g.id}>{g.name}</option>)}</select><small>{sources.length ? '仅使用你有查询权限的已发布知识库' : <>还没有可用的已发布知识库。<Link to="/configure/datasets">前往知识库完成建模并发布</Link></>}</small></label>
        <label className="dashboard-field" htmlFor="dashboard-title">看板名称<input id="dashboard-title" required value={title} disabled={creating} maxLength={80} placeholder="例如：业务经营概览" onChange={e => setTitle(e.target.value)} /></label>
      </div><footer className="dashboard-dialog-foot"><button className="dashboard-secondary clear" type="button" disabled={creating} onClick={() => setFormOpen(false)}>取消</button><button className="dashboard-primary" disabled={!group || !title.trim() || creating} type="submit">{creating ? '正在创建…' : '创建并进入'}</button></footer></form>
    </DataSourceDialog>}
  </div>;
}
